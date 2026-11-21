package io.github.jqssun.airplay.lyrics

import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Line-by-line lyric translation shown under each lyric, like Apple Music's
 * "Translation" view. Order of preference: Apple's own translation (already
 * attached by [AppleTtml]), Musixmatch community translations, then Google's
 * public machine translation as a last resort.
 */
internal class LyricsTranslator(
    private val http: LyricsHttp,
    private val log: (String) -> Unit,
) {
    @Volatile private var anonymousMusixmatchToken: String? = null

    /** One translated string per entry of [lines]; "" where none is available. */
    fun translate(
        lines: List<LyricLine>,
        request: LyricsRequest,
        language: String,
        musixmatchToken: String,
    ): List<String>? {
        val target = language.trim().lowercase(Locale.ROOT).substringBefore('-')
        if (target.isEmpty() || lines.isEmpty()) return null
        if (lines.any { it.translation.isNotBlank() }) return null

        val fromMusixmatch = runCatching { musixmatch(lines, request, target, musixmatchToken) }
            .onFailure { log("Lyrics: Musixmatch translation failed (${it.javaClass.simpleName})") }
            .getOrNull()
        if (fromMusixmatch != null && fromMusixmatch.count { it.isNotBlank() } * 2 >= lines.size) {
            log("Lyrics: translated to $target with Musixmatch")
            return fromMusixmatch
        }
        val fromGoogle = runCatching { google(lines, target) }
            .onFailure { log("Lyrics: Google translation failed (${it.javaClass.simpleName})") }
            .getOrNull()
        if (fromGoogle != null) log("Lyrics: translated to $target with Google")
        return fromGoogle
    }

    // region Musixmatch

    private fun musixmatch(
        lines: List<LyricLine>,
        request: LyricsRequest,
        target: String,
        userToken: String,
    ): List<String>? {
        val token = userToken.trim().ifEmpty { anonymousToken() } ?: return null
        val match = http.requestText(
            "$MXM_BASE/matcher.track.get" + lyricsQuery(
                "format" to "json",
                "app_id" to MXM_APP_ID,
                "usertoken" to token,
                "q_track" to request.title,
                "q_artist" to request.artist,
                "q_album" to request.album,
            ),
            maxBytes = 256 * 1024,
            headers = MXM_HEADERS,
        ) ?: return null
        val trackId = parseTrackId(match) ?: return null
        val body = http.requestText(
            "$MXM_BASE/crowd.track.translations.get" + lyricsQuery(
                "format" to "json",
                "app_id" to MXM_APP_ID,
                "usertoken" to token,
                "track_id" to trackId.toString(),
                "selected_language" to target,
            ),
            maxBytes = 1024 * 1024,
            headers = MXM_HEADERS,
        ) ?: return null
        val map = parseMusixmatchTranslations(body)
        if (map.isEmpty()) return null
        return lines.map { map[normalizeKey(it.text)].orEmpty() }
    }

    private fun anonymousToken(): String? {
        anonymousMusixmatchToken?.let { return it }
        val body = http.requestText(
            "$MXM_BASE/token.get" + lyricsQuery(
                "format" to "json",
                "app_id" to MXM_APP_ID,
                "user_language" to "en",
                "t" to System.currentTimeMillis().toString(),
            ),
            maxBytes = 64 * 1024,
            headers = MXM_HEADERS,
        ) ?: return null
        val token = parseUserToken(body) ?: return null
        anonymousMusixmatchToken = token
        return token
    }

    // endregion

    // region Google

    private fun google(lines: List<LyricLine>, target: String): List<String>? {
        val result = MutableList(lines.size) { "" }
        var any = false
        for (start in lines.indices step GOOGLE_CHUNK) {
            val chunk = lines.subList(start, minOf(lines.size, start + GOOGLE_CHUNK))
            val sources = chunk.map { it.text.replace('\n', ' ').trim() }
            val joined = sources.joinToString("\n")
            val translated = requestGoogle(joined, target)?.let(::splitGoogle)
            if (translated != null && translated.size == sources.size) {
                translated.forEachIndexed { index, text ->
                    if (sources[index].isNotEmpty()) result[start + index] = text
                }
                any = true
            } else {
                // Sentence splitting misaligned the batch; do this chunk line by line.
                sources.forEachIndexed { index, text ->
                    if (text.isEmpty()) return@forEachIndexed
                    val single = requestGoogle(text, target)?.let(::splitGoogle)?.joinToString(" ")
                    if (!single.isNullOrBlank()) {
                        result[start + index] = single
                        any = true
                    }
                }
            }
        }
        if (!any) return null
        return result.mapIndexed { index, text ->
            if (normalizeKey(text) == normalizeKey(lines[index].text)) "" else text
        }
    }

    private fun requestGoogle(text: String, target: String): String? = http.requestText(
        "$GOOGLE_URL?client=gtx&sl=auto&tl=${java.net.URLEncoder.encode(target, "UTF-8")}&dt=t",
        maxBytes = 512 * 1024,
        readTimeoutMs = 7_000,
        postBody = "q=" + java.net.URLEncoder.encode(text, "UTF-8"),
        attempts = 2,
    )

    // endregion

    companion object {
        private const val MXM_BASE = "https://apic-desktop.musixmatch.com/ws/1.1"
        private const val MXM_APP_ID = "web-desktop-app-v1.0"
        private val MXM_HEADERS = mapOf("Cookie" to "AWSELBCORS=0; AWSELB=0")
        private const val GOOGLE_URL = "https://translate.googleapis.com/translate_a/single"
        private const val GOOGLE_CHUNK = 40

        private val combiningMarks = Regex("\\p{M}+")
        private val nonAlphaNumeric = Regex("[^\\p{L}\\p{N}]+")

        internal fun normalizeKey(value: String): String = Normalizer
            .normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .replace(nonAlphaNumeric, "")

        internal fun parseUserToken(value: String): String? = runCatching {
            JSONObject(value).optJSONObject("message")?.optJSONObject("body")
                ?.optString("user_token")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "UpgradeOnlyUpgradeOnlyUpgradeOnlyUpgradeOnly" }

        internal fun parseTrackId(value: String): Long? = runCatching {
            JSONObject(value).optJSONObject("message")?.optJSONObject("body")
                ?.optJSONObject("track")?.optLong("track_id", 0L)
        }.getOrNull()?.takeIf { it > 0L }

        /** normalized original line -> translated line. */
        internal fun parseMusixmatchTranslations(value: String): Map<String, String> {
            val list = runCatching {
                JSONObject(value).optJSONObject("message")?.optJSONObject("body")
                    ?.optJSONArray("translations_list")
            }.getOrNull() ?: return emptyMap()
            val result = HashMap<String, String>()
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index)?.optJSONObject("translation") ?: continue
                val original = item.optString("matched_line")
                val translated = item.optString("description")
                if (original.isNotBlank() && translated.isNotBlank()) {
                    result.putIfAbsent(normalizeKey(original), translated.trim())
                }
            }
            return result
        }

        /** Google "gtx" reply -> translated text split back into lines. */
        internal fun splitGoogle(value: String): List<String>? {
            val segments = runCatching { JSONArray(value).optJSONArray(0) }.getOrNull() ?: return null
            val text = StringBuilder()
            for (index in 0 until segments.length()) {
                text.append(segments.optJSONArray(index)?.optString(0).orEmpty())
            }
            return text.toString().trimEnd('\n').split('\n').map { it.trim() }
        }
    }
}
