package io.github.jqssun.airplay.lyrics

import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * Converts the TTML that Apple Music returns from its `syllable-lyrics` and
 * `lyrics` endpoints into the provider JSON shape understood by
 * [AppleMusicLyricsClient.parseResponse], so word-by-word handling, duet
 * placement and background vocals all stay in one code path.
 */
internal object AppleTtml {

    class Result(
        /** {"type": "Syllable"|"Line", "content": [...]} */
        val json: JSONObject,
        /** Songwriters listed in the TTML metadata, comma separated. */
        val songwriters: String,
    )

    private class Token(
        val text: String,
        val beginMs: Long?,
        val endMs: Long?,
        var spaceAfter: Boolean = false,
    )

    /** "1:23.456", "83.456s" or "01:02:03.456"; null when unparsable. */
    fun parseTimeMs(value: String?): Long? {
        val raw = value?.trim()?.removeSuffix("s")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = raw.split(':')
        if (parts.size > 3) return null
        var seconds = 0.0
        for (part in parts) {
            seconds = seconds * 60.0 + (part.toDoubleOrNull() ?: return null)
        }
        return (seconds * 1_000.0).roundToLong()
    }

    /**
     * @param targetLanguage when the TTML already carries a translation in this
     * language (for example "es"), it is attached to each line.
     */
    fun convert(ttml: String, targetLanguage: String = ""): Result? {
        val document = parseDocument(ttml) ?: return null
        val root = document.documentElement ?: return null

        val translations = readTranslations(root, targetLanguage)
        val lines = JSONArray()
        var wordTimed = false
        val paragraphs = elementsNamed(root, "p")
        for (paragraph in paragraphs) {
            val main = mutableListOf<Token>()
            val background = mutableListOf<Token>()
            val directText = StringBuilder()
            collect(paragraph, inBackground = false, main, background, directText)

            val key = paragraph.getAttribute("itunes:key")
            val agent = paragraph.getAttribute("ttm:agent")
            val mainTimed = main.isNotEmpty() && main.all { it.beginMs != null }
            val line = JSONObject()
            val begin = parseTimeMs(paragraph.getAttribute("begin"))
                ?: main.firstNotNullOfOrNull { it.beginMs }
                ?: background.firstNotNullOfOrNull { it.beginMs }
                ?: continue
            val end = parseTimeMs(paragraph.getAttribute("end"))
                ?: main.lastNotNullOfOrNull { it.endMs }
            line.put("timestamp", begin)
            if (end != null) line.put("endtime", end)
            if (agent.isNotBlank()) line.put("agent", agent)
            translations[key]?.let { line.put("translation", it) }

            if (mainTimed) {
                wordTimed = true
                line.put("text", tokensToJson(main))
                if (background.isNotEmpty() && background.all { it.beginMs != null }) {
                    line.put("background", tokensToJson(stripParentheses(background)))
                }
            } else {
                // Line-level TTML: the whole row is one block of text.
                val text = (if (main.isNotEmpty()) main.joinToString("") { it.text } else directText.toString())
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (text.isEmpty()) continue
                line.put("text", text)
            }
            lines.put(line)
        }
        if (lines.length() == 0) return null

        val result = JSONObject()
        result.put("type", if (wordTimed) "Syllable" else "Line")
        result.put("content", lines)
        return Result(result, readSongwriters(root))
    }

    private fun tokensToJson(tokens: List<Token>): JSONArray {
        val array = JSONArray()
        tokens.forEachIndexed { index, token ->
            val item = JSONObject()
            // Explicit spacing from the TTML wins over script heuristics.
            item.put("text", if (token.spaceAfter) token.text + " " else token.text)
            token.beginMs?.let { item.put("timestamp", it) }
            token.endMs?.let { item.put("endtime", it) }
            // A span that touches the next one without whitespace is a syllable
            // inside the same word.
            if (!token.spaceAfter && index < tokens.lastIndex) item.put("part", true)
            array.put(item)
        }
        return array
    }

    /** Apple wraps backing vocals in "(...)"; the UI adds its own brackets. */
    private fun stripParentheses(tokens: List<Token>): List<Token> {
        if (tokens.isEmpty()) return tokens
        val copy = tokens.toMutableList()
        val first = copy.first()
        if (first.text.startsWith("(")) {
            copy[0] = Token(first.text.removePrefix("("), first.beginMs, first.endMs, first.spaceAfter)
        }
        val last = copy.last()
        if (last.text.endsWith(")")) {
            copy[copy.lastIndex] = Token(last.text.removeSuffix(")"), last.beginMs, last.endMs, last.spaceAfter)
        }
        return copy.filter { it.text.isNotEmpty() }
    }

    private fun collect(
        parent: Element,
        inBackground: Boolean,
        main: MutableList<Token>,
        background: MutableList<Token>,
        directText: StringBuilder,
    ) {
        val target = if (inBackground) background else main
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    val text = node.nodeValue.orEmpty()
                    if (text.isBlank()) {
                        target.lastOrNull()?.spaceAfter = true
                    } else if (!inBackground && parent.localTag() == "p") {
                        directText.append(text)
                    }
                }

                Node.ELEMENT_NODE -> {
                    val element = node as Element
                    if (element.localTag() != "span") continue
                    val role = element.getAttribute("ttm:role")
                    when {
                        role == "x-bg" -> collect(element, true, main, background, directText)
                        role.startsWith("x-translation") || role.startsWith("x-roman") -> Unit
                        hasSpanChild(element) -> collect(element, inBackground, main, background, directText)
                        else -> {
                            val text = element.textContent.orEmpty()
                            if (text.isEmpty()) continue
                            val token = Token(
                                text = text,
                                beginMs = parseTimeMs(element.getAttribute("begin")),
                                endMs = parseTimeMs(element.getAttribute("end")),
                            )
                            // Whitespace inside the span itself also separates words.
                            if (text.last().isWhitespace()) token.spaceAfter = true
                            target += token
                        }
                    }
                }
            }
        }
    }

    private fun hasSpanChild(element: Element): Boolean {
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == Node.ELEMENT_NODE && (child as Element).localTag() == "span") return true
        }
        return false
    }

    /** key ("L1") -> translated line, for the requested language only. */
    private fun readTranslations(root: Element, targetLanguage: String): Map<String, String> {
        val wanted = targetLanguage.trim().lowercase(Locale.ROOT).substringBefore('-')
        if (wanted.isEmpty()) return emptyMap()
        val result = HashMap<String, String>()
        for (translation in elementsNamed(root, "translation")) {
            val language = translation.getAttribute("xml:lang").lowercase(Locale.ROOT).substringBefore('-')
            if (language != wanted) continue
            for (text in elementsNamed(translation, "text")) {
                val key = text.getAttribute("for")
                val value = text.textContent.orEmpty().trim()
                if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
            }
        }
        return result
    }

    private fun readSongwriters(root: Element): String = elementsNamed(root, "songwriter")
        .map { it.textContent.orEmpty().trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(", ")

    private fun elementsNamed(root: Element, name: String): List<Element> {
        val all = root.getElementsByTagName("*")
        val result = ArrayList<Element>()
        for (index in 0 until all.length) {
            val element = all.item(index) as? Element ?: continue
            if (element.localTag() == name) result += element
        }
        return result
    }

    private fun Element.localTag(): String = tagName.substringAfter(':')

    private fun <T : Any, R : Any> List<T>.lastNotNullOfOrNull(transform: (T) -> R?): R? {
        for (index in indices.reversed()) transform(this[index])?.let { return it }
        return null
    }

    private fun parseDocument(ttml: String): org.w3c.dom.Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // Never resolve entities or external DTDs from a network response.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.isExpandEntityReferences = false
        factory.newDocumentBuilder().parse(InputSource(StringReader(ttml)))
    }.getOrNull()
}
