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
    fun fetch(songId: Long, rawUserToken: String, translationLanguage: String): LyricsState? {
        val userToken = normalizeUserToken(rawUserToken)
        if (userToken.isEmpty()) return null
        val storefront = storefront(userToken) ?: DEFAULT_STOREFRONT

        for (endpoint in listOf("syllable-lyrics", "lyrics")) {
            val url = "$AMP_BASE/v1/catalog/$storefront/songs/$songId/$endpoint"
            var response = request(url, userToken)
            if (response.code == 401 || response.code == 403) {
                // The web token rotates; fetch a fresh one once.
                developerToken = null
                response = request(url, userToken)
            }
            when {
                response.code == 401 || response.code == 403 -> {
                    log("Lyrics: Apple Music rejected the saved media-user-token; paste a fresh one in Settings")
                    return null
                }

                response.code == 404 -> continue
                response.body == null -> return null
            }
            val ttml = runCatching {
                JSONObject(response.body!!)
                    .optJSONArray("data")?.optJSONObject(0)
                    ?.optJSONObject("attributes")?.optString("ttml")
            }.getOrNull().orEmpty()
            if (ttml.isBlank()) continue
            val converted = AppleTtml.convert(ttml, translationLanguage) ?: continue
            val state = AppleMusicLyricsClient.parseResponse(converted.json.toString(), songId) ?: continue
            return state.copy(writerCredit = converted.songwriters)
        }
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
        val html = http.requestText(
            "https://music.apple.com/",
            maxBytes = 2 * 1024 * 1024,
            accept = "text/html,application/xhtml+xml",
            readTimeoutMs = 8_000,
            headers = headers,
        )
        if (html == null) {
            log("Lyrics: could not load music.apple.com to read the web token")
            return null
        }
        findDeveloperToken(decodeUrl(html))?.let { return remember(it) }

        for (path in scriptPaths(html)) {
            val script = http.requestText(
                absoluteUrl(path),
                maxBytes = MAX_SCRIPT_BYTES,
                accept = "*/*",
                connectTimeoutMs = 4_000,
                readTimeoutMs = 15_000,
                headers = headers,
            ) ?: continue
            findDeveloperToken(script)?.let { return remember(it) }
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
        private const val MAX_SCRIPT_BYTES = 8 * 1024 * 1024
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        // ES256 JWT: header always begins with {"alg":"ES256"...
        private val jwt = Regex("eyJhbGciOiJFUzI1NiI[A-Za-z0-9_-]*\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        private val scriptSrc = Regex("src=\"([^\"]*?/assets/[^\"]*?\\.js)\"")
        private val cookiePair = Regex("media-user-token=([^;\\s]+)")

        internal fun findDeveloperToken(text: String): String? = jwt.find(text)?.value

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
