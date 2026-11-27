package io.github.jqssun.airplay.lyrics

import android.icu.text.Transliterator

/**
 * On-device romanization for lyrics that came without one: pinyin, Korean
 * romanization, kana to romaji, Cyrillic, Thai, Arabic and so on, using the
 * ICU data that ships with Android (API 24+), so nothing is downloaded or sent.
 *
 * Kanji are transliterated with their Chinese reading, which is not how they
 * are sung in Japanese; Apple's own romanization is used whenever it exists.
 */
internal class Romanizer {
    private val transliterator: Transliterator? by lazy {
        runCatching { Transliterator.getInstance("Any-Latin") }.getOrNull()
    }

    /** One romanized string per line; "" where the line is already Latin or fails. */
    fun romanize(lines: List<LyricLine>): List<String>? {
        val engine = transliterator ?: return null
        var any = false
        val result = lines.map { line ->
            if (!needsRomanization(line.text)) return@map ""
            val romanized = runCatching { engine.transliterate(line.text) }.getOrDefault("").trim()
            if (romanized.isNotEmpty() && romanized != line.text.trim()) any = true else return@map ""
            romanized
        }
        return result.takeIf { any }
    }

    companion object {
        /** True when the text has letters from a non-Latin script. */
        internal fun needsRomanization(text: String): Boolean {
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                index += Character.charCount(codePoint)
                if (!Character.isLetter(codePoint)) continue
                val script = runCatching { Character.UnicodeScript.of(codePoint) }.getOrNull() ?: continue
                if (script != Character.UnicodeScript.LATIN &&
                    script != Character.UnicodeScript.COMMON &&
                    script != Character.UnicodeScript.INHERITED
                ) return true
            }
            return false
        }
    }
}
