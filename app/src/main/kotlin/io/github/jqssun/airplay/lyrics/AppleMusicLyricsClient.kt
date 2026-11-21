package io.github.jqssun.airplay.lyrics

import io.github.jqssun.airplay.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolves normalized Apple-style syllable lyrics, including `oppositeTurn`
 * duet placement and background vocals. No credentials are stored in the app.
 */
class AppleMusicLyricsClient(
    /** Receives short diagnostics so lookup failures show up in the in-app log. */
    private val log: (String) -> Unit = {},
    /** Read fresh on every lookup so Settings changes apply to the next song. */
    private val settings: () -> LyricsSettings = { LyricsSettings() },
) {

    private val http = LyricsHttp(log, USER_AGENT)
    private val appleDirect = AppleMusicDirectClient(http, log)
    private val translator = LyricsTranslator(http, log)

    /**
     * Adds a per-line translation (target language from Settings) to [state].
     * Returns [state] untouched when translation is off, already present, or
     * no source could translate it.
     */
    suspend fun withTranslation(state: LyricsState, request: LyricsRequest): LyricsState =
        withContext(Dispatchers.IO) {
            val config = settings()
            if (!config.translationEnabled || state.lines.isEmpty()) return@withContext state
            val translated = withTimeoutOrNull(TRANSLATION_TIMEOUT_MS) {
                runCatching {
                    translator.translate(state.lines, request, config.translationLanguage, config.musixmatchToken)
                }.getOrNull()
            } ?: return@withContext state
            state.copy(lines = state.lines.mapIndexed { index, line ->
                translated.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { line.copy(translation = it) } ?: line
            })
        }

    private val cache = object : LinkedHashMap<String, LyricsState>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LyricsState>?,
        ): Boolean = size > CACHE_ENTRIES
    }

    suspend fun find(request: LyricsRequest): LyricsState? = withContext(Dispatchers.IO) {
        val cacheKey = listOf(request.title, request.artist, request.album, request.durationMs.toString())
            .joinToString("\u0000") { it.trim().lowercase(Locale.ROOT) }
        synchronized(cache) { cache[cacheKey] }?.let { return@withContext it }

        val result = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            val apple = withTimeoutOrNull(APPLE_LOOKUP_TIMEOUT_MS) {
                runCatching { findFromAppleSources(request) }
                    .onFailure { log("Lyrics: Apple lookup failed (${it.javaClass.simpleName}: ${it.message.orEmpty()})") }
                    .getOrNull()
            }
            if (apple != null && apple.wordSyncMode == WordSyncMode.ENHANCED) {
                apple
            } else {
                // Apple had nothing, or only whole-line timing: look for a
                // word-timed copy elsewhere before settling.
                val other = findFromFallbackSources(request)
                when {
                    other != null && other.wordSyncMode == WordSyncMode.ENHANCED -> other
                    apple != null && apple.status == LyricsStatus.SYNCED -> apple
                    else -> apple ?: other
                }
            }
        }
        result?.also { resolved ->
            synchronized(cache) { cache[cacheKey] = resolved }
        }
    }


    /**
     * Apple Music first. The three Apple-origin routes run side by side and the
     * best one wins, in this order of trust: the user's own Apple account
     * (syllable-lyrics), the open LyricsPlus aggregator pinned to Apple's data,
     * then the paxsenix mirror. Word-timed results beat line-timed ones.
     */
    private suspend fun findFromAppleSources(request: LyricsRequest): LyricsState? = coroutineScope {
        val config = settings()
        val candidates = async(Dispatchers.IO) { searchCandidateIds(request) }
        val language = if (config.translationEnabled) config.translationLanguage else ""

        val direct = async(Dispatchers.IO) {
            if (AppleMusicDirectClient.normalizeUserToken(config.appleMediaUserToken).isEmpty()) {
                log("Lyrics: no Apple Music token saved; add one in Settings for the most reliable word-by-word lyrics")
                return@async null
            }
            runCatching {
                val ids = candidates.await().take(MAX_DIRECT_CANDIDATES)
                resolveFirstCandidate(ids) { id ->
                    appleDirect.fetch(id, config.appleMediaUserToken, language)
                }?.second
            }.onFailure { log("Lyrics: Apple Music account lookup failed (${it.javaClass.simpleName})") }
                .getOrNull()
                ?.also { log("Lyrics: Apple Music account returned ${it.status} / ${it.wordSyncMode}") }
        }
        val plus = async(Dispatchers.IO) {
            runCatching { findFromLyricsPlus(request, LyricsPlus.SOURCE_APPLE) }
                .onFailure { log("Lyrics: LyricsPlus lookup failed (${it.javaClass.simpleName})") }
                .getOrNull()
                ?.also { log("Lyrics: LyricsPlus(Apple) returned ${it.status} / ${it.wordSyncMode}") }
        }
        val mirror = async(Dispatchers.IO) {
            runCatching { findUncached(request, candidates) }
                .onFailure { log("Lyrics: Apple mirror failed (${it.javaClass.simpleName}: ${it.message.orEmpty()})") }
                .getOrNull()
                ?.also { log("Lyrics: Apple mirror returned ${it.status} / ${it.wordSyncMode}") }
        }
        val results = listOf(direct, plus, mirror).awaitAll().filterNotNull()
        results.firstOrNull { it.wordSyncMode == WordSyncMode.ENHANCED }
            ?: results.firstOrNull { it.status == LyricsStatus.SYNCED }
            ?: results.firstOrNull()
    }

    /** LyricsPlus aggregator; returns the first mirror that has the song. */
    private fun findFromLyricsPlus(request: LyricsRequest, sources: String): LyricsState? {
        val params = lyricsQuery(
            "title" to request.title,
            "artist" to request.artist,
            "album" to request.album,
            "duration" to (request.durationMs / 1_000L).takeIf { it > 0 }?.toString().orEmpty(),
            "source" to sources,
        )
        for (base in LyricsPlus.BASE_URLS.take(MAX_LYRICSPLUS_MIRRORS)) {
            val response = http.get(
                "$base/v2/lyrics/get$params",
                maxBytes = MAX_LYRICS_BYTES,
                connectTimeoutMs = 2_500,
                readTimeoutMs = 7_000,
            )
            if (response.code == 404) return null
            val body = response.body ?: continue
            val converted = LyricsPlus.convert(body) ?: continue
            val state = parseResponse(converted.json.toString(), 0L) ?: continue
            return state.copy(writerCredit = converted.songwriters)
        }
        return null
    }

    private suspend fun searchCandidateIds(request: LyricsRequest): List<Long> = coroutineScope {
        searchRequests(request)
            .map { searchRequest ->
                async {
                    requestText(
                        buildString {
                            append(ITUNES_SEARCH_URL)
                            append(query(
                                "term" to searchRequest.term,
                                "media" to "music",
                                "entity" to "song",
                                "country" to searchRequest.country,
                                "limit" to SEARCH_RESULT_LIMIT.toString(),
                            ))
                        },
                        maxBytes = MAX_SEARCH_BYTES,
                    )
                }
            }
            .awaitAll()
            .filterNotNull()
            .flatMap { rankTrackIds(it, request) }
            .distinct()
            .take(MAX_LYRICS_CANDIDATES)
    }

    private suspend fun findUncached(
        request: LyricsRequest,
        candidates: kotlinx.coroutines.Deferred<List<Long>>,
    ): LyricsState? = coroutineScope {
        val candidateIds = candidates.await()

        // Try candidates in ranking order. Launching every duplicate at once
        // made one slow or broken regional ID cancel a valid Apple result.
        // The best match normally succeeds first, so avoid touching lower
        // ranked duplicates unless the preferred candidate has no lyrics.
        val directResult = resolveLyricsCandidates(candidateIds)
        // Generic titles can be omitted from iTunes search results even while
        // their Apple Music page still exists. Resolve the album catalogue and
        // inspect its full track list as a second, exact metadata path.
        val (trackId, resolved) = directResult
            ?: resolveLyricsCandidates(findAlbumCandidateIds(request))
            ?: return@coroutineScope null
        val writerCredit = withTimeoutOrNull(CREDITS_TIMEOUT_MS) {
            requestText(
                "$APPLE_MUSIC_SONG_URL/$trackId",
                maxBytes = MAX_APPLE_PAGE_BYTES,
                accept = "text/html,application/xhtml+xml",
                connectTimeoutMs = 2_500,
                readTimeoutMs = 3_200,
            )
        }
            ?.let(::parseWriterCredit)
            .orEmpty()
        resolved.copy(writerCredit = writerCredit)
    }

    private suspend fun resolveLyricsCandidates(
        candidateIds: List<Long>,
    ): Pair<Long, LyricsState>? = resolveFirstCandidate(
        candidateIds.distinct().take(MAX_LYRICS_CANDIDATES),
    ) { candidateId ->
        val body = requestText(
            "$LYRICS_URL" + query("id" to candidateId.toString(), "v" to "2"),
            maxBytes = MAX_LYRICS_BYTES,
            readTimeoutMs = LYRICS_READ_TIMEOUT_MS,
            attempts = 2,
        )
        val parsed = body?.let { parseResponse(it, candidateId) }
        if (body != null && parsed == null) {
            log("Lyrics: unrecognised lyrics payload for id $candidateId (${describePayload(body)})")
        }
        parsed
    }

    private suspend fun findAlbumCandidateIds(request: LyricsRequest): List<Long> {
        if (request.album.isBlank() || request.artist.isBlank()) return emptyList()
        return coroutineScope {
            val albumSearchResponses = albumSearchRequests(request)
                .map { searchRequest ->
                    async {
                        requestText(
                            buildString {
                                append(ITUNES_SEARCH_URL)
                                append(query(
                                    "term" to searchRequest.term,
                                    "media" to "music",
                                    "entity" to "song",
                                    "country" to searchRequest.country,
                                    "limit" to SEARCH_RESULT_LIMIT.toString(),
                                ))
                            },
                            maxBytes = MAX_SEARCH_BYTES,
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
            val collectionIds = albumSearchResponses
                .flatMap { rankCollectionIds(it, request) }
                .distinct()
                .take(MAX_ALBUM_CANDIDATES)
            if (collectionIds.isEmpty()) return@coroutineScope emptyList()

            collectionIds
                .flatMap { collectionId ->
                    SEARCH_COUNTRIES.map { country -> collectionId to country }
                }
                .map { (collectionId, country) ->
                    async {
                        requestText(
                            ITUNES_LOOKUP_URL + query(
                                "id" to collectionId.toString(),
                                "entity" to "song",
                                "country" to country,
                            ),
                            maxBytes = MAX_SEARCH_BYTES,
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
                .flatMap { rankTrackIds(it, request) }
                .distinct()
                .take(MAX_LYRICS_CANDIDATES)
        }
    }

    private data class SearchRequest(
        val term: String,
        val country: String,
    )

    private fun searchRequests(request: LyricsRequest): List<SearchRequest> {
        return buildList {
            SEARCH_COUNTRIES.forEach { country ->
                searchTerms(request).forEach { term ->
                    add(SearchRequest(term = term, country = country))
                }
            }
        }.distinct().take(MAX_SEARCH_REQUESTS)
    }

    private fun albumSearchRequests(request: LyricsRequest): List<SearchRequest> {
        return buildList {
            SEARCH_COUNTRIES.forEach { country ->
                albumSearchTerms(request).forEach { term ->
                    add(SearchRequest(term = term, country = country))
                }
            }
        }.distinct().take(MAX_ALBUM_SEARCH_REQUESTS)
    }

    private class HttpOutcome(val body: String?, val retryable: Boolean = false)

    /**
     * Blocking GET. [attempts] > 1 retries only when the server answers with a
     * transient status (429 or 5xx); a 404 or a timeout is never repeated.
     */
    private fun requestText(
        url: String,
        maxBytes: Int,
        accept: String = "application/json",
        connectTimeoutMs: Int = 3_500,
        readTimeoutMs: Int = 6_500,
        attempts: Int = 1,
        headers: Map<String, String> = emptyMap(),
        postBody: String? = null,
    ): String? {
        for (attempt in 1..attempts) {
            val outcome = requestOnce(
                url, maxBytes, accept, connectTimeoutMs, readTimeoutMs, headers, postBody,
            )
            if (outcome.body != null) return outcome.body
            if (!outcome.retryable || attempt == attempts) return null
            Thread.sleep(RETRY_DELAY_MS)
        }
        return null
    }

    private fun requestOnce(
        url: String,
        maxBytes: Int,
        accept: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        headers: Map<String, String>,
        postBody: String?,
    ): HttpOutcome {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (postBody != null) "POST" else "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "th-TH,th;q=0.9,en;q=0.8")
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (postBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        val where = url.substringBefore('?')
        return try {
            if (postBody != null) {
                connection.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                log("Lyrics: HTTP $code from $where")
                HttpOutcome(null, retryable = code == 429 || code in 500..599)
            } else if (connection.contentLengthLong > maxBytes) {
                log("Lyrics: response from $where is larger than $maxBytes bytes")
                HttpOutcome(null)
            } else {
                val body = connection.inputStream.use { it.readBoundedUtf8(maxBytes) }
                if (body == null) log("Lyrics: response from $where exceeded $maxBytes bytes")
                HttpOutcome(body)
            }
        } catch (error: IOException) {
            log("Lyrics: ${error.javaClass.simpleName} from $where (${error.message.orEmpty()})")
            HttpOutcome(null)
        } finally {
            connection.disconnect()
        }
    }

    /** Lists only the top-level JSON keys, so diagnostics never contain lyric text. */
    private fun describePayload(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) return "top-level array"
        val keys = runCatching { JSONObject(trimmed).keys().asSequence().take(8).toList() }
            .getOrNull()
            ?: return "not JSON"
        return "keys: " + keys.joinToString(",")
    }

    /**
     * Fallback: LRCLIB is a free public catalogue of synced (LRC) lyrics that
     * needs no account. Used only when the Apple Music mirror yields nothing.
     */
    private fun findFromLrclib(request: LyricsRequest): LyricsState? {
        val title = cleanSearchTitle(request.title).ifBlank { request.title.trim() }
        val artist = primarySearchArtist(request.artist).ifBlank { request.artist.trim() }
        if (title.isBlank() || artist.isBlank()) return null
        val seconds = ((request.durationMs + 500L) / 1_000L).takeIf { it > 0L }

        requestText(
            LRCLIB_GET_URL + query(
                "track_name" to title,
                "artist_name" to artist,
                "album_name" to request.album.trim(),
                "duration" to seconds?.toString().orEmpty(),
            ),
            maxBytes = MAX_LYRICS_BYTES,
            readTimeoutMs = LRCLIB_READ_TIMEOUT_MS,
        )
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let(::parseLrclibItem)
            ?.let { return it }

        return requestText(
            LRCLIB_SEARCH_URL + query(
                "track_name" to title,
                "artist_name" to artist,
            ),
            maxBytes = MAX_SEARCH_BYTES,
            readTimeoutMs = LRCLIB_READ_TIMEOUT_MS,
        )?.let { parseLrclibSearch(it, request) }
    }

    /**
     * Runs the unofficial fallback catalogues side by side and keeps the best
     * result: synced beats plain text beats "instrumental", and ties go to the
     * source listed first (NetEase, QQ Music, LRCLIB).
     */
    private suspend fun findFromFallbackSources(request: LyricsRequest): LyricsState? {
        log("Lyrics: no Apple Music lyrics, trying LyricsPlus, NetEase, QQ Music and LRCLIB")
        val sources = listOf<Pair<String, (LyricsRequest) -> LyricsState?>>(
            "LyricsPlus" to { findFromLyricsPlus(it, LyricsPlus.SOURCES_OTHER) },
            "NetEase" to ::findFromNetease,
            "QQ Music" to ::findFromQqMusic,
            "LRCLIB" to ::findFromLrclib,
        )
        val results = withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
            coroutineScope {
                sources.map { (name, lookup) ->
                    async(Dispatchers.IO) {
                        runCatching { lookup(request) }
                            .onFailure { log("Lyrics: $name lookup failed (${it.javaClass.simpleName}: ${it.message.orEmpty()})") }
                            .getOrNull()
                            ?.also { log("Lyrics: $name returned ${it.status}") }
                    }
                }.awaitAll()
            }
        }.orEmpty()
        return chooseFallback(results)
    }

    /** NetEase Cloud Music: keyless legacy web endpoints, LRC text. */
    private fun findFromNetease(request: LyricsRequest): LyricsState? {
        val term = fallbackSearchTerm(request)
        if (term.isBlank()) return null
        val search = requestText(
            NETEASE_SEARCH_URL,
            maxBytes = MAX_SEARCH_BYTES,
            connectTimeoutMs = FALLBACK_CONNECT_TIMEOUT_MS,
            readTimeoutMs = FALLBACK_READ_TIMEOUT_MS,
            headers = mapOf("Referer" to "https://music.163.com/"),
            postBody = query(
                "s" to term,
                "type" to "1",
                "offset" to "0",
                "limit" to "10",
            ).removePrefix("?"),
        ) ?: return null
        for (id in rankNeteaseSongIds(search, request).take(MAX_FALLBACK_CANDIDATES)) {
            val body = requestText(
                NETEASE_LYRIC_URL + query(
                    "id" to id.toString(),
                    "lv" to "1",
                    "kv" to "1",
                    "tv" to "-1",
                ),
                maxBytes = MAX_LYRICS_BYTES,
                connectTimeoutMs = FALLBACK_CONNECT_TIMEOUT_MS,
                readTimeoutMs = FALLBACK_READ_TIMEOUT_MS,
                headers = mapOf("Referer" to "https://music.163.com/"),
            ) ?: continue
            parseNeteaseLyric(body, id)?.let { return it }
        }
        return null
    }

    /** QQ Music: legacy y.qq.com endpoints; they only answer with a y.qq.com Referer. */
    private fun findFromQqMusic(request: LyricsRequest): LyricsState? {
        val term = fallbackSearchTerm(request)
        if (term.isBlank()) return null
        val referer = mapOf("Referer" to "https://y.qq.com/")
        val search = requestText(
            QQ_SEARCH_URL + query(
                "w" to term,
                "format" to "json",
                "inCharset" to "utf-8",
                "outCharset" to "utf-8",
                "platform" to "yqq.json",
                "needNewCode" to "0",
                "n" to "10",
                "p" to "1",
                "t" to "0",
            ),
            maxBytes = MAX_SEARCH_BYTES,
            connectTimeoutMs = FALLBACK_CONNECT_TIMEOUT_MS,
            readTimeoutMs = FALLBACK_READ_TIMEOUT_MS,
            headers = referer,
        ) ?: return null
        for (candidate in rankQqSongs(search, request).take(MAX_FALLBACK_CANDIDATES)) {
            val body = requestText(
                QQ_LYRIC_URL + query(
                    "songmid" to candidate.mid,
                    "format" to "json",
                    "nobase64" to "1",
                    "g_tk" to "5381",
                    "inCharset" to "utf8",
                    "outCharset" to "utf-8",
                ),
                maxBytes = MAX_LYRICS_BYTES,
                connectTimeoutMs = FALLBACK_CONNECT_TIMEOUT_MS,
                readTimeoutMs = FALLBACK_READ_TIMEOUT_MS,
                headers = referer,
            ) ?: continue
            parseQqLyric(body, candidate.id)?.let { return it }
        }
        return null
    }

    private fun fallbackSearchTerm(request: LyricsRequest): String =
        "${cleanSearchTitle(request.title)} ${primarySearchArtist(request.artist)}".trim()

    private fun InputStream.readBoundedUtf8(maxBytes: Int): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1_024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun query(vararg pairs: Pair<String, String>): String = pairs
        .filter { it.second.isNotBlank() }
        .joinToString(prefix = "?", separator = "&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"
        private const val ITUNES_LOOKUP_URL = "https://itunes.apple.com/lookup"
        private const val LYRICS_URL = "https://lyrics.paxsenix.org/apple-music/lyrics"
        private const val APPLE_MUSIC_SONG_URL = "https://music.apple.com/th/song"
        private const val NETEASE_SEARCH_URL = "https://music.163.com/api/search/get/"
        private const val NETEASE_LYRIC_URL = "https://music.163.com/api/song/lyric"
        private const val QQ_SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        private const val QQ_LYRIC_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
        private const val LRCLIB_GET_URL = "https://lrclib.net/api/get"
        private const val LRCLIB_SEARCH_URL = "https://lrclib.net/api/search"
        private val USER_AGENT =
            "AirplaySpeaker/${BuildConfig.VERSION_NAME} " +
                "(https://github.com/l3kza08/Airplay-Speaker)"
        private const val MAX_SEARCH_BYTES = 1024 * 1024
        // Syllable-synced responses for long songs can exceed the old 768 KB
        // cap, which silently dropped otherwise valid lyrics.
        private const val MAX_LYRICS_BYTES = 2 * 1024 * 1024
        private const val MAX_APPLE_PAGE_BYTES = 768 * 1024
        private const val CREDITS_TIMEOUT_MS = 3_800L
        // Whole lookup (Apple mirror first, then the fallback) and the share of
        // it the Apple mirror may use. The service-level window must be larger.
        private const val LOOKUP_TIMEOUT_MS = 46_000L
        private const val APPLE_LOOKUP_TIMEOUT_MS = 26_000L
        private const val FALLBACK_TIMEOUT_MS = 20_000L
        private const val FALLBACK_CONNECT_TIMEOUT_MS = 3_000
        private const val FALLBACK_READ_TIMEOUT_MS = 5_000
        private const val MAX_FALLBACK_CANDIDATES = 3
        // Different recordings of one song are usually several seconds apart,
        // and LRC timings only fit the recording they were made for.
        private const val FALLBACK_MAX_DURATION_DIFFERENCE_MS = 6_000L
        // The mirror builds syllable timelines on demand and is often slower
        // than the previous 5.5 s read timeout allowed.
        private const val LYRICS_READ_TIMEOUT_MS = 10_000
        private const val LRCLIB_READ_TIMEOUT_MS = 6_000
        private const val RETRY_DELAY_MS = 700L
        private const val LRCLIB_MAX_DURATION_DIFFERENCE_S = 3.0
        private const val MAX_CREDIT_SECTION_CHARS = 256 * 1024
        private const val CACHE_ENTRIES = 12
        private const val SEARCH_RESULT_LIMIT = 50
        private const val MAX_SEARCH_REQUESTS = 6
        private const val MAX_ALBUM_SEARCH_REQUESTS = 6
        private const val MAX_ALBUM_CANDIDATES = 3
        private const val MAX_LYRICS_CANDIDATES = 5
        private const val TRANSLATION_TIMEOUT_MS = 25_000L
        private const val MAX_DIRECT_CANDIDATES = 3
        private const val MAX_LYRICSPLUS_MIRRORS = 3
        private const val MAX_LINES = 750
        private const val MAX_WORDS_PER_LINE = 160
        private val SEARCH_COUNTRIES = listOf("TH", "US")
        private val versionNoise = Regex(
            "[\\[(][^\\])]*(?:feat|ft\\.?|remaster|version|edit|mix|live|acoustic|instrumental|karaoke)[^\\])]*[\\])]",
            RegexOption.IGNORE_CASE,
        )
        private val trailingVersionNoise = Regex(
            "\\s*[-–—:]\\s*(?:\\d{4}\\s+)?(?:remaster(?:ed)?|version|edit|mix|live|acoustic|instrumental|karaoke|radio).*?$",
            RegexOption.IGNORE_CASE,
        )
        private val featuredArtistTail = Regex(
            "\\s+(?:feat(?:uring)?\\.?|ft\\.?)\\s+.+$",
            RegexOption.IGNORE_CASE,
        )
        private val combiningMarks = Regex("\\p{M}+")
        private val nonAlphaNumeric = Regex("[^\\p{L}\\p{N}]+")
        private val noSpaceBeforeCharacters = setOf(
            ',', '.', ';', ':', '!', '?', '%',
            ')', ']', '}', '…',
            '，', '。', '、', '；', '：', '！', '？',
            '）', '】', '》', '〉', '〕', '〗', '〙', '〛',
            '」', '』', '”', '’',
        )
        private val noSpaceAfterCharacters = setOf(
            '(', '[', '{',
            '（', '【', '《', '〈', '〔', '〖', '〘', '〚',
            '「', '『', '“', '‘',
        )
        private val inlineJoinCharacters = setOf(
            '\'', '’', 'ʼ', '‛',
            '-', '‐', '‑', '‒', '–', '—',
            '/', '\\',
        )

        internal fun selectTrackId(value: String, request: LyricsRequest): Long? {
            return rankTrackIds(value, request).firstOrNull()
        }

        internal suspend fun <T : Any> resolveFirstCandidate(
            candidateIds: List<Long>,
            resolver: suspend (Long) -> T?,
        ): Pair<Long, T>? {
            for (candidateId in candidateIds) {
                val resolved = try {
                    resolver(candidateId)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (resolved != null) return candidateId to resolved
            }
            return null
        }

        internal fun rankTrackIds(value: String, request: LyricsRequest): List<Long> {
            val results = runCatching { JSONObject(value).optJSONArray("results") }.getOrNull()
                ?: return emptyList()
            val wantedTitle = normalize(request.title)
            val wantedArtist = normalize(request.artist)
            val wantedPrimaryArtist = normalize(primarySearchArtist(request.artist))
            val wantedAlbum = normalize(request.album)
            val ranked = mutableListOf<Pair<Long, Int>>()
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val title = normalize(item.optString("trackName"))
                val artist = normalize(item.optString("artistName"))
                val primaryArtist = normalize(primarySearchArtist(item.optString("artistName")))
                val album = normalize(item.optString("collectionName"))
                // "Song (Apple Music Sessions)" / "Song - Live": the same song with a
                // version tag the player did not send.
                val baseTitle = normalize(
                    item.optString("trackName").substringBefore("(").substringBefore(" - ").substringBefore("["),
                )
                val titleScore = maxOf(
                    matchScore(title, wantedTitle),
                    if (wantedTitle.isNotBlank() && baseTitle == wantedTitle) 140 else 0,
                )
                val artistScore = maxOf(
                    matchScore(artist, wantedArtist),
                    matchScore(primaryArtist, wantedPrimaryArtist),
                )
                if (titleScore <= 0 || (wantedArtist.isNotBlank() && artistScore <= 0)) continue

                val candidateDuration = item.optLong("trackTimeMillis", 0L)
                val hasDuration = request.durationMs > 0L && candidateDuration > 0L
                val durationDifference = if (hasDuration) {
                    kotlin.math.abs(request.durationMs - candidateDuration)
                } else {
                    0L
                }
                val exactIdentity = titleScore == 180 && artistScore == 180
                val exactAlbum = wantedAlbum.isNotBlank() && album == wantedAlbum
                if (
                    hasDuration &&
                    durationDifference > MAX_DURATION_DIFFERENCE_MS &&
                    !exactIdentity &&
                    !exactAlbum
                ) {
                    continue
                }

                var score = titleScore + artistScore
                if (wantedAlbum.isNotBlank()) {
                    score += when {
                        album == wantedAlbum -> 110
                        closeMatch(album, wantedAlbum) -> 45
                        else -> 0
                    }
                }
                if (hasDuration) {
                    score += when {
                        durationDifference <= 2_000L -> 65
                        durationDifference <= 6_000L -> 35
                        durationDifference <= 15_000L -> 15
                        durationDifference <= 35_000L -> 4
                        else -> -(durationDifference / 1_000L).coerceAtMost(120L).toInt()
                    }
                }
                val id = item.optLong("trackId", -1L)
                if (id > 0L) {
                    ranked += id to score
                }
            }
            return ranked
                .sortedWith(
                    compareByDescending<Pair<Long, Int>> { it.second }
                        .thenBy { it.first },
                )
                .map { it.first }
                .distinct()
        }

        internal fun rankCollectionIds(value: String, request: LyricsRequest): List<Long> {
            val results = runCatching { JSONObject(value).optJSONArray("results") }.getOrNull()
                ?: return emptyList()
            val wantedAlbum = normalize(request.album)
            val wantedArtist = normalize(request.artist)
            val wantedPrimaryArtist = normalize(primarySearchArtist(request.artist))
            if (wantedAlbum.isBlank()) return emptyList()

            val ranked = mutableListOf<Pair<Long, Int>>()
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val album = normalize(item.optString("collectionName"))
                val artist = normalize(item.optString("artistName"))
                val primaryArtist = normalize(primarySearchArtist(item.optString("artistName")))
                val albumScore = matchScore(album, wantedAlbum)
                val artistScore = maxOf(
                    matchScore(artist, wantedArtist),
                    matchScore(primaryArtist, wantedPrimaryArtist),
                )
                if (albumScore <= 0 || artistScore <= 0) continue
                val collectionId = item.optLong("collectionId", -1L)
                if (collectionId > 0L) {
                    ranked += collectionId to (albumScore * 2 + artistScore)
                }
            }
            return ranked
                .sortedWith(
                    compareByDescending<Pair<Long, Int>> { it.second }
                        .thenBy { it.first },
                )
                .map { it.first }
                .distinct()
        }

        private fun matchScore(candidate: String, wanted: String): Int {
            if (wanted.isBlank()) return 1
            if (candidate == wanted) return 180
            if (candidate.isBlank()) return 0
            val shorter = minOf(candidate.length, wanted.length)
            val longer = maxOf(candidate.length, wanted.length)
            return when {
                (candidate.contains(wanted) || wanted.contains(candidate)) &&
                    shorter >= 3 &&
                    shorter.toDouble() / longer.toDouble() >= 0.45 -> 95
                else -> 0
            }
        }

        private fun cleanSearchTitle(value: String): String = value
            .replace(versionNoise, " ")
            .replace(trailingVersionNoise, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun primarySearchArtist(value: String): String = value
            .replace(featuredArtistTail, "")
            .trim()

        internal fun searchTerms(request: LyricsRequest): List<String> {
            val title = request.title.trim()
            val artist = request.artist.trim()
            val cleanTitle = cleanSearchTitle(title)
            val primaryArtist = primarySearchArtist(artist)
            return linkedSetOf(
                "$artist $title".trim(),
                "$primaryArtist $cleanTitle".trim(),
                "$cleanTitle $primaryArtist".trim(),
                cleanTitle,
            ).filter { it.isNotBlank() }
        }

        internal fun albumSearchTerms(request: LyricsRequest): List<String> {
            val album = request.album.trim()
            val artist = request.artist.trim()
            val primaryArtist = primarySearchArtist(artist)
            return linkedSetOf(
                "$artist $album".trim(),
                "$primaryArtist $album".trim(),
                "$album $primaryArtist".trim(),
            ).filter { it.isNotBlank() }
        }

        private fun normalize(value: String): String = Normalizer
            .normalize(cleanSearchTitle(value), Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)
            .replace(combiningMarks, "")
            .replace(nonAlphaNumeric, "")

        private const val MAX_DURATION_DIFFERENCE_MS = 90_000L

        // The mirror's v2 payload has been seen with the row list under
        // "lyrics" and under "content", and some deployments wrap it in an
        // envelope. Reading only "lyrics" made every such response look empty.
        private val lyricArrayKeys = listOf("lyrics", "content", "lines")
        private val plainTextKeys = listOf("lyrics", "content", "plainLyrics", "plain")
        private val envelopeKeys = listOf("data", "result", "response")
        private val backgroundKeys = listOf("backgroundText", "background")

        private fun lyricsArray(root: JSONObject): JSONArray? =
            lyricArrayKeys.firstNotNullOfOrNull { root.optJSONArray(it) }

        private fun plainText(root: JSONObject): String? = plainTextKeys.firstNotNullOfOrNull { key ->
            (root.opt(key) as? String)?.trim()?.takeIf { it.isNotBlank() }
        }

        private fun unwrapPayload(value: String): JSONObject? {
            val trimmed = value.trim()
            if (trimmed.startsWith("[")) {
                val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return null
                return JSONObject().put("lyrics", array)
            }
            var current = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            for (depth in 0 until 2) {
                if (lyricsArray(current) != null || plainText(current) != null) return current
                current = envelopeKeys.firstNotNullOfOrNull { current.optJSONObject(it) }
                    ?: return current
            }
            return current
        }

        internal fun parseResponse(value: String, trackId: Long): LyricsState? {
            val root = unwrapPayload(value) ?: return null
            val rawLines = lyricsArray(root)
            if (rawLines == null) {
                val plainLyrics = plainText(root)
                return plainLyrics?.let {
                    LyricsState(
                        status = LyricsStatus.PLAIN,
                        plainLyrics = it,
                        sourceId = trackId,
                        wordSyncMode = WordSyncMode.NONE,
                    )
                }
            }
            val declaredWordTiming = declaredWordTiming(
                root.optString("syncType", "").ifBlank { root.optString("type", "") },
            )
            val allTexts = mutableListOf<String>()
            val parsedLines = buildList {
                for (index in 0 until minOf(rawLines.length(), MAX_LINES)) {
                    val rawLine = rawLines.optJSONObject(index) ?: continue
                    val declaredEnd = rawLine.optTimestampMs("endtime", "endTime") ?: -1L
                    val main = parseTextField(
                        value = rawLine.opt("text"),
                        fallbackEndMs = declaredEnd.takeIf { it > 0L },
                    )
                    val mainText = main.text
                    val backgroundKey = backgroundKeys.firstOrNull { key ->
                        val candidate = rawLine.opt(key)
                        candidate is JSONArray || candidate is String
                    }
                    val background = parseTextField(
                        value = backgroundKey?.let(rawLine::opt),
                        fallbackEndMs = declaredEnd.takeIf { it > 0L },
                    )
                    if (mainText.isBlank() && background.text.isBlank()) continue
                    val combinedText = if (background.text.isNotBlank()) {
                        listOf(
                            mainText,
                            "(${background.text})",
                        ).filter(String::isNotBlank).joinToString(" ")
                    } else {
                        mainText
                    }
                    allTexts += combinedText
                    val timeMs = rawLine.optTimestampMs("timestamp", "time") ?: -1L
                    if (timeMs < 0L) continue
                    val mainHasWordTiming = hasTrustedProviderWordTiming(
                        declaredWordTiming = declaredWordTiming,
                        text = mainText,
                        words = main.words,
                    )
                    val backgroundHasWordTiming = background.text.isNotBlank() &&
                        hasTrustedProviderWordTiming(
                            declaredWordTiming = declaredWordTiming,
                            text = background.text,
                            words = background.words,
                        )
                    val mainWords = main.words.takeIf { mainHasWordTiming }.orEmpty()
                    val backgroundWords = background.words
                        .takeIf { backgroundHasWordTiming }
                        .orEmpty()
                    // Preserve a valid large or compact vocal independently.
                    // One untimed Apple part must not disable karaoke for the
                    // other part on the same visual row.
                    val hasAnyCompleteWordTiming =
                        mainHasWordTiming || backgroundHasWordTiming
                    val wordEnd = (mainWords + backgroundWords)
                        .maxOfOrNull { it.endMs }
                        ?: timeMs
                    val endMs = maxOf(declaredEnd, wordEnd).takeIf { it > timeMs }
                    val agentId = rawLine.optString("agent").trim()
                    val translation = (rawLine.opt("translation") as? String).orEmpty().trim()
                    add(
                        ParsedProviderLine(
                            line = LyricLine(
                                timeMs = timeMs,
                                text = mainText,
                                words = mainWords,
                                exactWordTiming = hasAnyCompleteWordTiming,
                                endMs = endMs,
                                backgroundText = background.text,
                                backgroundWords = backgroundWords,
                                agentId = agentId,
                                translation = translation,
                            ),
                            vocalTurn = ProviderVocalTurn(
                                explicitOpposite = rawLine.optBooleanOrNull("oppositeTurn"),
                                agent = agentId,
                            ),
                        )
                    )
                }
            }.sortedBy { it.line.timeMs }
            if (parsedLines.isEmpty()) {
                val plainLyrics = allTexts.joinToString("\n").trim()
                return plainLyrics.takeIf { it.isNotBlank() }?.let {
                    LyricsState(
                        status = LyricsStatus.PLAIN,
                        plainLyrics = it,
                        sourceId = trackId,
                        wordSyncMode = WordSyncMode.NONE,
                    )
                }
            }
            val alignments = resolveProviderDuetAlignments(parsedLines.map { it.vocalTurn })
            val lines = parsedLines.mapIndexed { index, parsed ->
                parsed.line.copy(alignment = alignments[index])
            }
            return LyricsState(
                status = LyricsStatus.SYNCED,
                lines = lines,
                plainLyrics = allTexts.joinToString("\n").ifBlank {
                    lines.joinToString("\n", transform = LyricLine::combinedDisplayText)
                },
                sourceId = trackId,
                wordSyncMode = if (lines.any { it.exactWordTiming }) {
                    WordSyncMode.ENHANCED
                } else {
                    WordSyncMode.LINE
                },
            )
        }

        /**
         * The v2 Apple mirror represents line-synced rows as a one-item text
         * array whose timestamp covers the entire row. That object is a line
         * token, not word timing. Trust the response's syncType first; only
         * infer enhanced timing for older payloads that omit syncType and
         * contain multiple independently timed tokens.
         */
        private fun hasTrustedProviderWordTiming(
            declaredWordTiming: Boolean?,
            text: String,
            words: List<LyricWord>,
        ): Boolean {
            if (declaredWordTiming == false || !lyricWordsMatchLine(text, words)) {
                return false
            }
            if (declaredWordTiming == true) return true
            return words.size >= 2 &&
                words.asSequence().map(LyricWord::startMs).distinct().take(2).count() >= 2
        }

        private fun declaredWordTiming(syncType: String): Boolean? {
            val normalized = syncType
                .lowercase(Locale.ROOT)
                .filter(Char::isLetterOrDigit)
            return when (normalized) {
                "syllable", "syllables", "word", "words",
                "wordbyword", "enhanced" -> true

                "line", "lines", "linebyline", "none",
                "unsynced", "unsynchronized" -> false

                else -> null
            }
        }

        /**
         * Reads the public Composition & Lyrics section embedded in the
         * matching Apple Music song page. Only writing roles are retained;
         * producers, performers and sampled artists are deliberately ignored.
         */
        internal fun parseWriterCredit(page: String): String {
            val sectionStart = page.indexOf("\"id\":\"composer-and-lyrics\"")
            if (sectionStart < 0) return ""
            val itemsMarker = page.indexOf("\"items\":[", startIndex = sectionStart)
            if (itemsMarker < 0) return ""
            val arrayStart = page.indexOf('[', startIndex = itemsMarker)
            val itemsJson = extractJsonArray(page, arrayStart) ?: return ""
            val items = runCatching { JSONArray(itemsJson) }.getOrNull() ?: return ""
            return buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val roles = item.optJSONArray("roleNames")
                    val hasWritingRole = roles != null &&
                        (0 until roles.length()).any { roleIndex ->
                            val role = roles.optString(roleIndex).lowercase(Locale.ROOT)
                            role.contains("songwriter") ||
                                role.contains("composer") ||
                                role.contains("lyricist")
                        }
                    if (!hasWritingRole) continue
                    item.optString("name")
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }.distinct().joinToString(", ")
        }

        private val lrcLeadingTimestamp = Regex("^\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        private val lrcWordTag = Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>")

        /**
         * Parses LRC text ("[mm:ss.xx] lyric"). Metadata tags such as [ar:...]
         * are ignored and each row ends where the next timestamp begins.
         */
        internal fun parseLrc(lrc: String): List<LyricLine> {
            val rows = mutableListOf<Pair<Long, String>>()
            for (rawLine in lrc.lineSequence()) {
                var rest = rawLine.trim()
                val times = mutableListOf<Long>()
                while (true) {
                    val match = lrcLeadingTimestamp.find(rest) ?: break
                    val minutes = match.groupValues[1].toLong()
                    val seconds = match.groupValues[2].toLong()
                    val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLong()
                    times += minutes * 60_000L + seconds * 1_000L + fraction
                    rest = rest.substring(match.range.last + 1).trimStart()
                }
                if (times.isEmpty()) continue
                val text = rest.replace(lrcWordTag, "").trim()
                times.forEach { rows += it to text }
            }
            rows.sortBy { it.first }
            return buildList {
                for (index in rows.indices) {
                    val (timeMs, text) = rows[index]
                    if (text.isBlank()) continue
                    val nextTime = rows.getOrNull(index + 1)?.first
                    add(
                        LyricLine(
                            timeMs = timeMs,
                            text = text,
                            endMs = nextTime?.takeIf { it > timeMs },
                        )
                    )
                }
            }
        }

        internal fun parseLrclibItem(item: JSONObject): LyricsState? {
            val id = item.optLong("id", -1L).takeIf { it > 0L }
            if (item.optBoolean("instrumental", false)) {
                return LyricsState(status = LyricsStatus.INSTRUMENTAL, sourceId = id)
            }
            val lines = parseLrc((item.opt("syncedLyrics") as? String).orEmpty())
            if (lines.isNotEmpty()) {
                return LyricsState(
                    status = LyricsStatus.SYNCED,
                    lines = lines,
                    plainLyrics = lines.joinToString("\n") { it.text },
                    sourceId = id,
                    wordSyncMode = WordSyncMode.LINE,
                )
            }
            val plain = (item.opt("plainLyrics") as? String)?.trim().orEmpty()
            return plain.takeIf { it.isNotBlank() }?.let {
                LyricsState(
                    status = LyricsStatus.PLAIN,
                    plainLyrics = it,
                    sourceId = id,
                    wordSyncMode = WordSyncMode.NONE,
                )
            }
        }

        /** Picks the closest-duration synced match from an LRCLIB search result list. */
        internal fun parseLrclibSearch(value: String, request: LyricsRequest): LyricsState? {
            val array = runCatching { JSONArray(value) }.getOrNull() ?: return null
            val wantedTitle = normalize(request.title)
            val wantedArtist = normalize(primarySearchArtist(request.artist))
            val wantedSeconds = request.durationMs / 1_000.0
            val candidates = (0 until array.length())
                .mapNotNull { array.optJSONObject(it) }
                .filter { item ->
                    matchScore(normalize(item.optString("trackName")), wantedTitle) > 0 &&
                        matchScore(
                            normalize(primarySearchArtist(item.optString("artistName"))),
                            wantedArtist,
                        ) > 0
                }
                .map { item ->
                    val difference = if (request.durationMs > 0L && item.has("duration")) {
                        kotlin.math.abs(item.optDouble("duration", 0.0) - wantedSeconds)
                    } else {
                        0.0
                    }
                    item to difference
                }
                .filter { (_, difference) -> difference <= LRCLIB_MAX_DURATION_DIFFERENCE_S }
                .sortedWith(
                    compareByDescending<Pair<JSONObject, Double>> { (item, _) ->
                        !(item.opt("syncedLyrics") as? String).isNullOrBlank()
                    }.thenBy { it.second }
                )
            return candidates.firstNotNullOfOrNull { (item, _) -> parseLrclibItem(item) }
        }

        private val creditRow = Regex(
            "^(?:作词|作曲|编曲|制作人?|监制|混音|母带|录音|和声|词|曲|" +
                "lyrics?|lyricist|composer|composed by|written by|arranger|producer)\\s*[:：]",
            RegexOption.IGNORE_CASE,
        )
        private val htmlEntity = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);")

        /** Chinese catalogues put "作词 : …" credit rows before the first sung line. */
        internal fun parseProviderLrc(lrc: String): List<LyricLine> =
            parseLrc(lrc).filterIndexed { index, line ->
                !(index < MAX_LEADING_CREDIT_ROWS && creditRow.containsMatchIn(line.text))
            }

        private const val MAX_LEADING_CREDIT_ROWS = 12

        /** QQ Music returns lyrics with HTML entities such as "&#10;" and "&#58;". */
        internal fun htmlUnescape(value: String): String {
            if ('&' !in value) return value
            return htmlEntity.replace(value) { match ->
                val entity = match.groupValues[1]
                when {
                    entity.startsWith("#x", ignoreCase = true) ->
                        entity.substring(2).toIntOrNull(16)?.let(::codePointToString)
                    entity.startsWith("#") ->
                        entity.substring(1).toIntOrNull()?.let(::codePointToString)
                    entity == "amp" -> "&"
                    entity == "lt" -> "<"
                    entity == "gt" -> ">"
                    entity == "quot" -> "\""
                    entity == "apos" -> "'"
                    entity == "nbsp" -> " "
                    else -> null
                } ?: match.value
            }
        }

        private fun codePointToString(codePoint: Int): String? =
            runCatching { String(Character.toChars(codePoint)) }.getOrNull()

        private fun jsonObjectFromPossiblyWrapped(value: String): JSONObject? {
            val start = value.indexOf('{')
            val end = value.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { JSONObject(value.substring(start, end + 1)) }.getOrNull()
        }

        /** Shared candidate scoring for the keyless fallback catalogues; null = reject. */
        private fun fallbackMatchScore(
            request: LyricsRequest,
            title: String,
            artists: List<String>,
            album: String,
            durationMs: Long,
        ): Int? {
            val titleScore = matchScore(normalize(title), normalize(request.title))
            if (titleScore <= 0) return null
            val wantedArtist = normalize(request.artist)
            val wantedPrimaryArtist = normalize(primarySearchArtist(request.artist))
            val artistScore = (artists + artists.joinToString(" "))
                .maxOfOrNull { name ->
                    val candidate = normalize(name)
                    maxOf(
                        matchScore(candidate, wantedArtist),
                        matchScore(candidate, wantedPrimaryArtist),
                    )
                } ?: 0
            if (wantedArtist.isNotBlank() && artistScore <= 0) return null

            var score = titleScore + artistScore
            val wantedAlbum = normalize(request.album)
            val candidateAlbum = normalize(album)
            if (wantedAlbum.isNotBlank() && candidateAlbum.isNotBlank()) {
                score += when {
                    candidateAlbum == wantedAlbum -> 110
                    closeMatch(candidateAlbum, wantedAlbum) -> 45
                    else -> 0
                }
            }
            if (request.durationMs > 0L && durationMs > 0L) {
                val difference = kotlin.math.abs(request.durationMs - durationMs)
                if (difference > FALLBACK_MAX_DURATION_DIFFERENCE_MS) return null
                score += if (difference <= 2_000L) 65 else 35
            }
            return score
        }

        private fun JSONArray?.artistNames(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { index ->
                optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
            }
        }

        internal fun rankNeteaseSongIds(value: String, request: LyricsRequest): List<Long> {
            val songs = runCatching {
                JSONObject(value).optJSONObject("result")?.optJSONArray("songs")
            }.getOrNull() ?: return emptyList()
            val ranked = mutableListOf<Pair<Long, Int>>()
            for (index in 0 until songs.length()) {
                val item = songs.optJSONObject(index) ?: continue
                val id = item.optLong("id", -1L)
                if (id <= 0L) continue
                val score = fallbackMatchScore(
                    request = request,
                    title = item.optString("name"),
                    artists = (item.optJSONArray("artists") ?: item.optJSONArray("ar")).artistNames(),
                    album = (item.optJSONObject("album") ?: item.optJSONObject("al"))
                        ?.optString("name").orEmpty(),
                    durationMs = maxOf(item.optLong("duration", 0L), item.optLong("dt", 0L)),
                ) ?: continue
                ranked += id to score
            }
            return ranked
                .sortedWith(compareByDescending<Pair<Long, Int>> { it.second }.thenBy { it.first })
                .map { it.first }
                .distinct()
        }

        internal fun parseNeteaseLyric(value: String, songId: Long): LyricsState? {
            val root = runCatching { JSONObject(value) }.getOrNull() ?: return null
            if (root.optBoolean("uncollected", false)) return null
            if (root.optBoolean("nolyric", false)) {
                return LyricsState(status = LyricsStatus.INSTRUMENTAL, sourceId = songId)
            }
            val lrc = (root.optJSONObject("lrc")?.opt("lyric") as? String).orEmpty()
            val lines = parseProviderLrc(lrc)
            if (lines.isNotEmpty()) {
                return LyricsState(
                    status = LyricsStatus.SYNCED,
                    lines = lines,
                    plainLyrics = lines.joinToString("\n") { it.text },
                    sourceId = songId,
                    wordSyncMode = WordSyncMode.LINE,
                )
            }
            return lrc.trim().takeIf { it.isNotBlank() }?.let {
                LyricsState(
                    status = LyricsStatus.PLAIN,
                    plainLyrics = it,
                    sourceId = songId,
                    wordSyncMode = WordSyncMode.NONE,
                )
            }
        }

        internal data class QqSong(val mid: String, val id: Long)

        internal fun rankQqSongs(value: String, request: LyricsRequest): List<QqSong> {
            val songs = runCatching {
                jsonObjectFromPossiblyWrapped(value)
                    ?.optJSONObject("data")
                    ?.optJSONObject("song")
                    ?.optJSONArray("list")
            }.getOrNull() ?: return emptyList()
            val ranked = mutableListOf<Pair<QqSong, Int>>()
            for (index in 0 until songs.length()) {
                val item = songs.optJSONObject(index) ?: continue
                val mid = item.optString("songmid").trim()
                if (mid.isEmpty()) continue
                val score = fallbackMatchScore(
                    request = request,
                    title = item.optString("songname"),
                    artists = item.optJSONArray("singer").artistNames(),
                    album = item.optString("albumname"),
                    durationMs = item.optLong("interval", 0L) * 1_000L,
                ) ?: continue
                ranked += QqSong(mid, item.optLong("songid", -1L).takeIf { it > 0L } ?: 0L) to score
            }
            return ranked
                .sortedWith(compareByDescending<Pair<QqSong, Int>> { it.second }.thenBy { it.first.mid })
                .map { it.first }
                .distinctBy { it.mid }
        }

        internal fun parseQqLyric(value: String, songId: Long): LyricsState? {
            val root = jsonObjectFromPossiblyWrapped(value) ?: return null
            if (root.optLong("retcode", root.optLong("code", 0L)) != 0L) return null
            val lrc = htmlUnescape((root.opt("lyric") as? String).orEmpty())
            val lines = parseProviderLrc(lrc)
            if (lines.isEmpty()) return null
            return LyricsState(
                status = LyricsStatus.SYNCED,
                lines = lines,
                plainLyrics = lines.joinToString("\n") { it.text },
                sourceId = songId.takeIf { it > 0L },
                wordSyncMode = WordSyncMode.LINE,
            )
        }

        private fun fallbackRank(status: LyricsStatus): Int = when (status) {
            LyricsStatus.SYNCED -> 0
            LyricsStatus.PLAIN -> 1
            LyricsStatus.INSTRUMENTAL -> 2
            else -> 3
        }

        /** [results] are in source-priority order; synced beats plain beats instrumental. */
        internal fun chooseFallback(results: List<LyricsState?>): LyricsState? = results
            .mapIndexedNotNull { index, state -> state?.let { index to it } }
            .minWithOrNull(
                compareBy<Pair<Int, LyricsState>>({ fallbackRank(it.second.status) }, { it.first })
            )
            ?.second

        private fun extractJsonArray(value: String, startIndex: Int): String? {
            if (startIndex !in value.indices || value[startIndex] != '[') return null
            val scanEnd = minOf(value.length, startIndex + MAX_CREDIT_SECTION_CHARS)
            var depth = 0
            var inString = false
            var escaped = false
            for (index in startIndex until scanEnd) {
                val char = value[index]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> inString = false
                    }
                    continue
                }
                when (char) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return value.substring(startIndex, index + 1)
                    }
                }
            }
            return null
        }

        private data class ParsedWords(val text: String, val words: List<LyricWord>)
        private data class ParsedProviderLine(
            val line: LyricLine,
            val vocalTurn: ProviderVocalTurn,
        )

        private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
            if (has(key) && !isNull(key)) optBoolean(key, false) else null

        private fun JSONObject.optTimestampMs(vararg keys: String): Long? {
            keys.forEach { key ->
                if (has(key) && !isNull(key)) {
                    parseTimestampMs(opt(key))?.let { return it }
                }
            }
            return null
        }

        /**
         * Apple lyric mirrors expose time values as integer milliseconds in
         * most responses, but some line-synced catalogues retain TTML-style
         * clock strings. Accept both without inventing word timing.
         */
        internal fun parseTimestampMs(value: Any?): Long? = when (value) {
            is Number -> value.toDouble()
                .takeIf { it.isFinite() && it >= 0.0 }
                ?.roundToLong()

            is String -> {
                val raw = value.trim().lowercase(Locale.ROOT)
                when {
                    raw.isBlank() -> null
                    raw.startsWith("pt") && raw.endsWith("s") -> {
                        raw.removePrefix("pt")
                            .removeSuffix("s")
                            .toDoubleOrNull()
                            ?.takeIf { it >= 0.0 }
                            ?.times(1_000.0)
                            ?.roundToLong()
                    }

                    raw.endsWith("ms") -> raw.removeSuffix("ms")
                        .toDoubleOrNull()
                        ?.takeIf { it >= 0.0 }
                        ?.roundToLong()

                    raw.endsWith("s") -> raw.removeSuffix("s")
                        .toDoubleOrNull()
                        ?.takeIf { it >= 0.0 }
                        ?.times(1_000.0)
                        ?.roundToLong()

                    ':' in raw -> {
                        val parts = raw.split(':')
                        if (parts.size !in 2..3) {
                            null
                        } else {
                            parts.map(String::toDoubleOrNull)
                                .takeIf { values -> values.all { it != null } }
                                ?.map { it ?: 0.0 }
                                ?.fold(0.0) { totalSeconds, part ->
                                    totalSeconds * 60.0 + part
                                }
                                ?.takeIf { it >= 0.0 }
                                ?.times(1_000.0)
                                ?.roundToLong()
                        }
                    }

                    else -> raw.toDoubleOrNull()
                        ?.takeIf { it >= 0.0 }
                        ?.roundToLong()
                }
            }

            else -> null
        }

        /** True when this token is a syllable that continues into the next one. */
        private fun JSONObject.joinsNextToken(): Boolean =
            optBoolean("part", false) || optBoolean("isPartOfWord", false)

        private fun parseTextField(
            value: Any?,
            fallbackEndMs: Long?,
        ): ParsedWords = when (value) {
            is JSONArray -> parseWords(value, fallbackEndMs)
            is String -> ParsedWords(value.trim(), emptyList())
            else -> ParsedWords("", emptyList())
        }

        private fun parseWords(
            values: JSONArray?,
            fallbackEndMs: Long?,
        ): ParsedWords {
            if (values == null) return ParsedWords("", emptyList())
            val tokens = buildList {
                for (index in 0 until minOf(values.length(), MAX_WORDS_PER_LINE)) {
                    val item = values.optJSONObject(index) ?: continue
                    val text = item.optString("text")
                    if (text.isEmpty()) continue
                    add(item to text)
                }
            }
            if (tokens.isEmpty()) return ParsedWords("", emptyList())

            // Apple payload variants do not always include an explicit endtime
            // for every syllable. The following syllable onset is the most
            // faithful boundary; the published line end closes the last token.
            // Keeping a 1 ms placeholder here made the repair stage discard
            // otherwise complete word-by-word rows.
            val nextTimedStart = LongArray(tokens.size) { -1L }
            var followingStart = -1L
            for (index in tokens.indices.reversed()) {
                nextTimedStart[index] = followingStart
                val start = tokens[index].first.optTimestampMs("timestamp", "time") ?: -1L
                if (start >= 0L) followingStart = start
            }
            val words = tokens.mapIndexedNotNull { index, (item, token) ->
                val start = item.optTimestampMs("timestamp", "time") ?: -1L
                val declaredEnd = item.optTimestampMs("endtime", "endTime") ?: -1L
                val duration = item.optTimestampMs("duration") ?: 0L
                if (start < 0L) return@mapIndexedNotNull null
                val nextToken = tokens.getOrNull(index + 1)?.second
                val separator = if (item.joinsNextToken()) "" else separatorAfter(token, nextToken)
                val end = when {
                    declaredEnd > start -> declaredEnd
                    duration > 0L -> start + duration
                    nextTimedStart[index] > start -> nextTimedStart[index]
                    fallbackEndMs != null && fallbackEndMs > start -> fallbackEndMs
                    else -> start + DEFAULT_LAST_TOKEN_DURATION_MS
                }
                LyricWord(token + separator, start, end)
            }
            val text = tokens.mapIndexed { index, (item, token) ->
                token + if (item.joinsNextToken()) {
                    ""
                } else {
                    separatorAfter(token, tokens.getOrNull(index + 1)?.second)
                }
            }.joinToString("").trimEnd()
            return ParsedWords(text, words)
        }

        /**
         * Reconstructs Apple token rows without forcing Latin spacing rules
         * onto scripts that normally flow without inter-word spaces.
         *
         * Explicit whitespace always wins. This also prevents the old
         * double-space bug when a provider token already included its own
         * trailing separator.
         */
        internal fun separatorAfter(current: String, next: String?): String {
            if (next == null || current.isEmpty() || next.isEmpty()) return ""
            if (current.last().isWhitespace() || next.first().isWhitespace()) return ""

            val currentCodePoint = current.codePointBefore(current.length)
            val nextCodePoint = next.codePointAt(0)
            val currentCharacter = currentCodePoint.toBmpCharOrNull()
            val nextCharacter = nextCodePoint.toBmpCharOrNull()

            if (
                nextCharacter != null && nextCharacter in noSpaceBeforeCharacters ||
                currentCharacter != null && currentCharacter in noSpaceAfterCharacters ||
                nextCharacter != null && nextCharacter in inlineJoinCharacters ||
                currentCharacter != null && currentCharacter in inlineJoinCharacters
            ) {
                return ""
            }
            if (
                nextCodePoint.isCombiningOrFormatCharacter() ||
                currentCodePoint == ZERO_WIDTH_JOINER ||
                nextCodePoint == ZERO_WIDTH_JOINER
            ) {
                return ""
            }

            val currentScript = Character.UnicodeScript.of(currentCodePoint)
            val nextScript = Character.UnicodeScript.of(nextCodePoint)
            if (
                nextScript.isContinuousLyricScript() &&
                currentCharacter != null &&
                currentCharacter in noSpaceBeforeCharacters ||
                currentScript.isContinuousLyricScript() &&
                nextCharacter != null &&
                nextCharacter in noSpaceAfterCharacters
            ) {
                return ""
            }
            if (
                currentScript.isContinuousLyricScript() &&
                nextScript.isContinuousLyricScript()
            ) {
                return ""
            }
            // ASCII numbers are commonly embedded directly inside Thai/CJK
            // titles and lyric words (for example "24時間"). Do not create a
            // visual gap that Apple did not publish.
            if (
                currentScript.isContinuousLyricScript() &&
                Character.isDigit(nextCodePoint) ||
                nextScript.isContinuousLyricScript() &&
                Character.isDigit(currentCodePoint)
            ) {
                return ""
            }
            val contraction = next.lowercase(Locale.ROOT)
            if (contraction.startsWith("n't") || contraction.startsWith("n’t")) return ""
            return " "
        }

        private fun Int.toBmpCharOrNull(): Char? =
            takeIf { it <= Char.MAX_VALUE.code }?.toChar()

        private fun Int.isCombiningOrFormatCharacter(): Boolean = when (Character.getType(this)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            Character.FORMAT.toInt() -> true
            else -> false
        }

        private fun Character.UnicodeScript.isContinuousLyricScript(): Boolean = when (this) {
            Character.UnicodeScript.THAI,
            Character.UnicodeScript.LAO,
            Character.UnicodeScript.KHMER,
            Character.UnicodeScript.MYANMAR,
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA -> true
            else -> false
        }

        private fun closeMatch(candidate: String, wanted: String): Boolean =
            candidate == wanted || (
                candidate.isNotEmpty() && wanted.isNotEmpty() &&
                    (candidate.contains(wanted) || wanted.contains(candidate))
                )

        private const val DEFAULT_LAST_TOKEN_DURATION_MS = 520L
        private const val ZERO_WIDTH_JOINER = 0x200D
    }
}
