package io.github.jqssun.airplay.lyrics

import org.json.JSONArray
import org.json.JSONObject

/**
 * LyricsPlus (the open "YouLy+" API) is a public aggregator that can return
 * Apple Music's syllable-timed lyrics without any account. Its JSON is
 * converted to the provider shape read by [AppleMusicLyricsClient.parseResponse].
 */
internal object LyricsPlus {

    /**
     * Public instances, tried in order. Checked live on 2026-11-21: only the
     * first answers (the others have an invalid certificate, a disabled
     * deployment or no route). It allows 2 requests per 10 seconds.
     */
    val BASE_URLS = listOf("https://lyricsplus.binimum.org")

    /** Sources that make up the Apple-origin attempt. */
    const val SOURCE_APPLE = "apple"

    /** Word-synced providers other than Apple, used as the next best thing. */
    const val SOURCES_OTHER = "lyricsplus,musixmatch-word,musixmatch,spotify"

    class Result(
        val json: JSONObject,
        val songwriters: String,
        val source: String,
    )

    fun convert(value: String): Result? {
        val root = runCatching { JSONObject(value) }.getOrNull() ?: return null
        val rows = root.optJSONArray("lyrics") ?: return null
        val content = JSONArray()
        var wordTimed = false
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val start = row.optLong("time", -1L)
            if (start < 0L) continue
            val duration = row.optLong("duration", 0L)
            val line = JSONObject()
            line.put("timestamp", start)
            if (duration > 0L) line.put("endtime", start + duration)
            row.optJSONObject("element")?.optString("singer")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { line.put("agent", it) }

            val syllabus = row.optJSONArray("syllabus")
            val main = JSONArray()
            val background = JSONArray()
            if (syllabus != null) {
                for (syllableIndex in 0 until syllabus.length()) {
                    val syllable = syllabus.optJSONObject(syllableIndex) ?: continue
                    val text = syllable.optString("text")
                    if (text.isEmpty()) continue
                    val token = JSONObject()
                    token.put("text", text)
                    val syllableStart = syllable.optLong("time", -1L)
                    if (syllableStart >= 0L) {
                        token.put("timestamp", syllableStart)
                        val syllableDuration = syllable.optLong("duration", 0L)
                        if (syllableDuration > 0L) token.put("endtime", syllableStart + syllableDuration)
                    }
                    if (syllable.optBoolean("isPartOfWord", false)) token.put("part", true)
                    if (syllable.optBoolean("isBackground", false)) background.put(token) else main.put(token)
                }
            }
            if (main.length() > 0) {
                wordTimed = true
                line.put("text", main)
                if (background.length() > 0) line.put("background", background)
            } else {
                val text = row.optString("text").trim()
                if (text.isEmpty()) continue
                line.put("text", text)
            }
            content.put(line)
        }
        if (content.length() == 0) return null

        val provider = JSONObject()
        provider.put("type", if (wordTimed) "Syllable" else "Line")
        provider.put("content", content)

        val metadata = root.optJSONObject("metadata")
        val writers = metadata?.optJSONArray("songWriters")
        val songwriters = buildList {
            if (writers != null) {
                for (index in 0 until writers.length()) {
                    writers.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }.distinct().joinToString(", ")
        return Result(provider, songwriters, metadata?.optString("source").orEmpty())
    }
}
