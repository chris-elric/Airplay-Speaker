package io.github.jqssun.airplay.lyrics

import org.json.JSONObject

/**
 * Reads Apple Music's own `syllable-lyrics` for the user's account.
 *
 * Apple's lyrics API needs two tokens: an anonymous "developer" token that the
 * public web player itself ships (read from music.apple.com), and the user's
 * personal `media-user-token`, which the user pastes into Settings. Nothing is
 * sent anywhere except Apple.
 */
internal class AppleMusicDirectClient(
    private val http: LyricsHttp,
    private val log: (String) -> Unit,
) {
    @Volatile private var developerToken: String? = null
    @Volatile private var storefrontFor: Pair<String, String>? = null

    /**
     * Word-timed lyrics for one Apple Music catalogue song, or null when the
     * account has none, the tokens are rejected, or the request fails.
     */
    fun fetch(
        songId: Long,
        rawUserToken: String,
        translationLanguage: String,
        wantRomanization: Boolean = false,
    ): LyricsState? {
        val userToken = normalizeUserToken(rawUserToken)
        if (userToken.isEmpty()) return null
        val storefronts = listOf(storefront(userToken) ?: DEFAULT_STOREFRONT, DEFAULT_STOREFRONT).distinct()

        for (storefront in storefronts) {
            for (endpoint in listOf("syllable-lyrics", "lyrics")) {
                val baseUrl = "$AMP_BASE/v1/catalog/$storefront/songs/$songId/$endpoint"
                // Ask Apple for its own translation / transliteration. If the
                // extended request is refused, the plain one still works.
                val extendedUrl = localizationQuery(translationLanguage, wantRomanization)
                    ?.let { baseUrl + it }
                val url = extendedUrl ?: baseUrl
                var response = request(url, userToken)
                if (extendedUrl != null && (response.code == 400 || response.code == 422 || response.code in 500..599)) {
                    log("Lyrics: Apple Music refused the translation request (HTTP ${response.code}); retrying plain")
                    response = request(baseUrl, userToken)
                }
                if (response.code == 401 || response.code == 403) {
                    // The web token rotates; fetch a fresh one once.
                    developerToken = null
                    response = request(url, userToken)
                }
                when {
                    response.code == 401 || response.code == 403 -> {
                        log("Lyrics: Apple Music rejected the request (HTTP ${response.code}); paste a fresh media-user-token in Settings")
                        return null
                    }

                    response.code == 404 -> continue
                    response.body == null -> return null
                }
                val ttml = extractTtml(response.body!!)
                if (ttml.isBlank()) continue
                val converted = AppleTtml.convert(
                    ttml,
                    translationLanguage,
                    extractLocalizations(response.body!!),
                ) ?: continue
                val state = AppleMusicLyricsClient.parseResponse(converted.json.toString(), songId) ?: continue
                log("Lyrics: Apple Music $endpoint found for $songId in storefront $storefront")
                return state.copy(writerCredit = converted.songwriters)
            }
        }
        log("Lyrics: Apple Music has no lyrics for $songId on this account (token accepted, 404)")
        return null
    }

    private fun request(url: String, userToken: String): LyricsHttpResponse {
        val developer = developerToken() ?: return LyricsHttpResponse(-1, null)
        return http.get(
            url,
            maxBytes = MAX_BYTES,
            connectTimeoutMs = 3_500,
            readTimeoutMs = 9_000,
            headers = apiHeaders(developer, userToken),
        )
    }

    private fun apiHeaders(developer: String, userToken: String) = mapOf(
        "Authorization" to "Bearer $developer",
        "Media-User-Token" to userToken,
        "Music-User-Token" to userToken,
        "Origin" to "https://music.apple.com",
        "Referer" to "https://music.apple.com/",
        "User-Agent" to BROWSER_USER_AGENT,
    )

    /** The account's storefront ("mx", "us", ...), cached per user token. */
    private fun storefront(userToken: String): String? {
        storefrontFor?.takeIf { it.first == userToken }?.let { return it.second }
        val developer = developerToken() ?: return null
        val body = http.requestText(
            "$AMP_BASE/v1/me/storefront",
            maxBytes = 64 * 1024,
            readTimeoutMs = 6_000,
            headers = apiHeaders(developer, userToken),
        ) ?: return null
        val id = runCatching {
            JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optString("id")
        }.getOrNull()?.trim().orEmpty()
        if (id.isEmpty()) return null
        storefrontFor = userToken to id
        return id
    }

    /**
     * Reads the developer token the Apple Music web player embeds in its page
     * or in its main script bundle. Cached for the life of the process.
     */
    @Synchronized
    private fun developerToken(): String? {
        developerToken?.let { return it }
        val headers = mapOf("User-Agent" to BROWSER_USER_AGENT)
        // The page is ~2.4 MB but the bundle <script> tag sits in its first
        // few KB, so only the head is downloaded.
        val html = http.requestText(
            "https://music.apple.com/",
            maxBytes = HTML_HEAD_BYTES,
            accept = "text/html,application/xhtml+xml",
            readTimeoutMs = 8_000,
            headers = headers,
            truncate = true,
        )
        if (html == null) {
            log("Lyrics: could not load music.apple.com to read the web token")
            return null
        }
        findDeveloperToken(decodeUrl(html))?.let { return remember(it) }

        for (path in scriptPaths(html)) {
            val token = http.scan(
                absoluteUrl(path),
                maxBytes = MAX_SCRIPT_BYTES,
                headers = headers,
                find = ::findCompleteDeveloperToken,
            )
            if (token != null) return remember(token)
        }
        log("Lyrics: no Apple Music web token found; the player page may have changed")
        return null
    }

    private fun remember(token: String): String {
        developerToken = token
        return token
    }

    companion object {
        private const val AMP_BASE = "https://amp-api.music.apple.com"
        private const val DEFAULT_STOREFRONT = "us"
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val HTML_HEAD_BYTES = 256 * 1024
        private const val MAX_SCRIPT_BYTES = 8 * 1024 * 1024
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        // The web player's ES256 token. Apple's header is {"typ":"JWT","alg":"ES256",
        // "kid":"WebPlayKid"} today (base64 starts eyJ0eXAi); older builds put "alg" first.
        private val jwt = Regex(
            "eyJ(?:0eXAi|hbGci)[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{20,}",
        )
        private val scriptSrc = Regex("src=\"([^\"]*?/assets/[^\"]*?\\.js)\"")
        private val cookiePair = Regex("media-user-token=([^;\\s]+)")

        /** Region Apple expects for `l[lyrics]`; a bare "es" becomes Mexican Spanish. */
        internal fun appleLanguageTag(language: String): String {
            val code = language.trim()
            if (code.isEmpty() || code.contains('-')) return code
            return when (code.lowercase()) {
                "es" -> "es-MX"
                "en" -> "en-US"
                "pt" -> "pt-BR"
                "fr" -> "fr-FR"
                "de" -> "de-DE"
                "it" -> "it-IT"
                "ja" -> "ja-JP"
                "ko" -> "ko-KR"
                "zh" -> "zh-Hans-CN"
                else -> code
            }
        }

        /** "?l[lyrics]=es-MX&l[script]=en-Latn&extend=ttmlLocalizations" or null when neither is wanted. */
        internal fun localizationQuery(language: String, wantRomanization: Boolean): String? {
            if (language.isBlank() && !wantRomanization) return null
            return lyricsQuery(
                "l[lyrics]" to appleLanguageTag(language),
                "l[script]" to if (wantRomanization) "en-Latn" else "",
                "extend" to "ttmlLocalizations",
            )
        }

        /** Every TTML document inside `attributes.ttmlLocalizations` (string, list or map). */
        internal fun extractLocalizations(body: String): List<String> = runCatching {
            val attributes = JSONObject(body).optJSONArray("data")?.optJSONObject(0)
                ?.optJSONObject("attributes") ?: return@runCatching emptyList()
            val out = ArrayList<String>()
            fun collect(value: Any?) {
                when (value) {
                    is String -> if (value.contains("<")) out += value
                    is JSONObject -> value.keys().forEach { collect(value.opt(it)) }
                    is org.json.JSONArray -> for (i in 0 until value.length()) collect(value.opt(i))
                }
            }
            collect(attributes.opt("ttmlLocalizations"))
            out
        }.getOrDefault(emptyList())

        internal fun extractTtml(body: String): String = runCatching {
            val attributes = JSONObject(body).optJSONArray("data")?.optJSONObject(0)
                ?.optJSONObject("attributes") ?: return@runCatching ""
            attributes.optString("ttml").ifBlank {
                (attributes.opt("ttmlLocalizations") as? String).orEmpty()
            }
        }.getOrDefault("")

        internal fun findDeveloperToken(text: String): String? = jwt.find(text)?.value

        /** Like [findDeveloperToken] but ignores a token cut off by the end of a streamed chunk. */
        internal fun findCompleteDeveloperToken(window: String): String? =
            jwt.find(window)?.takeIf { it.range.last < window.length - 1 }?.value

        /** Main bundle first: that is where the token lives. */
        internal fun scriptPaths(html: String): List<String> = scriptSrc.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .sortedByDescending { it.substringAfterLast('/').startsWith("index") }
            .take(4)
            .toList()

        private fun absoluteUrl(path: String): String = when {
            path.startsWith("http") -> path
            path.startsWith("//") -> "https:$path"
            else -> "https://music.apple.com" + (if (path.startsWith("/")) path else "/$path")
        }

        private fun decodeUrl(value: String): String =
            runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

        /** Accepts the bare value, "media-user-token=...", or a whole Cookie header. */
        internal fun normalizeUserToken(raw: String): String {
            val trimmed = raw.trim().trim('"')
            cookiePair.find(trimmed)?.let { return it.groupValues[1] }
            return trimmed.trimEnd(';').trim()
        }
    }
}
