package io.github.jqssun.airplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSourcesTest {

    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
            xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word">
          <head><metadata>
            <iTunesMetadata><songwriters><songwriter>Jane Doe</songwriter></songwriters>
              <translations><translation xml:lang="es-MX"><text for="L1">Hola mundo</text></translation></translations>
            </iTunesMetadata></metadata></head>
          <body><div>
            <p begin="0:01.000" end="0:03.000" itunes:key="L1" ttm:agent="v1"><span begin="0:01.000" end="0:01.500">Hel</span><span begin="0:01.500" end="0:02.000">lo</span> <span begin="0:02.000" end="0:03.000">world</span><span ttm:role="x-bg"><span begin="0:02.000" end="0:02.500">(ooh)</span></span></p>
          </div></body></tt>
    """.trimIndent()

    @Test
    fun parsesTimes() {
        assertEquals(83_456L, AppleTtml.parseTimeMs("1:23.456"))
        assertEquals(83_456L, AppleTtml.parseTimeMs("83.456s"))
        assertEquals(3_723_000L, AppleTtml.parseTimeMs("01:02:03"))
        assertNull(AppleTtml.parseTimeMs("abc"))
    }

    @Test
    fun ttmlBecomesWordSyncedLineWithTranslation() {
        val result = requireNotNull(AppleTtml.convert(ttml, "es"))
        assertEquals("Jane Doe", result.songwriters)
        val state = requireNotNull(AppleMusicLyricsClient.parseResponse(result.json.toString(), 1L))
        assertEquals(WordSyncMode.ENHANCED, state.wordSyncMode)
        val line = state.lines.single()
        assertEquals("Hello world", line.text)
        assertEquals("Hola mundo", line.translation)
        assertEquals("v1", line.agentId)
        assertEquals("ooh", line.backgroundText.trim('(', ')'))
        assertTrue(line.words.size >= 2)
    }

    @Test
    fun ttmlWithoutTranslationLanguageHasNone() {
        val result = requireNotNull(AppleTtml.convert(ttml, ""))
        val state = requireNotNull(AppleMusicLyricsClient.parseResponse(result.json.toString(), 1L))
        assertEquals("", state.lines.single().translation)
    }

    @Test
    fun doctypeIsRejected() {
        assertNull(AppleTtml.convert("<!DOCTYPE tt [<!ENTITY x \"y\">]><tt><body><p begin=\"0\">&x;</p></body></tt>"))
    }

    @Test
    fun lyricsPlusSyllabusConverts() {
        val body = """{"type":"Syllable","metadata":{"source":"apple","songWriters":["A","B"]},
          "lyrics":[{"time":1000,"duration":2000,"text":"Hello world","element":{"singer":"v2"},
            "syllabus":[{"time":1000,"duration":500,"text":"Hel","isPartOfWord":true},
                        {"time":1500,"duration":500,"text":"lo "},
                        {"time":2000,"duration":1000,"text":"world"}]}]}"""
        val result = requireNotNull(LyricsPlus.convert(body))
        assertEquals("A, B", result.songwriters)
        val state = requireNotNull(AppleMusicLyricsClient.parseResponse(result.json.toString(), 0L))
        assertEquals(WordSyncMode.ENHANCED, state.wordSyncMode)
        assertEquals("Hello world", state.lines.single().text)
    }

    @Test
    fun lyricsPlusLineOnly() {
        val body = """{"lyrics":[{"time":500,"text":"Just a line"}]}"""
        val result = requireNotNull(LyricsPlus.convert(body))
        val state = requireNotNull(AppleMusicLyricsClient.parseResponse(result.json.toString(), 0L))
        requireNotNull(state.lines.single())
        assertNull(LyricsPlus.convert("""{"lyrics":[]}"""))
        assertNull(LyricsPlus.convert("nope"))
    }

    @Test
    fun tokenHelpers() {
        val jwt = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ.eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxfQ.c2lnbmF0dXJlLXNpZ25hdHVyZS1fLQ"
        assertEquals(jwt, AppleMusicDirectClient.findDeveloperToken("x=\"$jwt\";"))
        assertNull(AppleMusicDirectClient.findDeveloperToken("nothing"))
        assertEquals("abc123", AppleMusicDirectClient.normalizeUserToken("  media-user-token=abc123; other=1"))
        assertEquals("abc123", AppleMusicDirectClient.normalizeUserToken("\"abc123\""))
        val paths = AppleMusicDirectClient.scriptPaths("""<script src="/assets/other.js"></script><script src="/assets/index-1.js"></script>""")
        assertEquals("/assets/index-1.js", paths.first())
    }

    @Test
    fun translatorParsers() {
        assertEquals(
            listOf("Hola", "Adiós"),
            LyricsTranslator.splitGoogle("""[[["Hola\n","Hello\n",null],["Adiós","Bye",null]],null,"en"]"""),
        )
        assertEquals(99L, LyricsTranslator.parseTrackId("""{"message":{"body":{"track":{"track_id":99}}}}"""))
        assertNull(LyricsTranslator.parseTrackId("""{"message":{"body":[]}}"""))
        assertEquals("tok", LyricsTranslator.parseUserToken("""{"message":{"body":{"user_token":"tok"}}}"""))
        val map = LyricsTranslator.parseMusixmatchTranslations(
            """{"message":{"body":{"translations_list":[{"translation":{"matched_line":"Hello, World!","description":"Hola, mundo"}}]}}}""",
        )
        assertEquals("Hola, mundo", map[LyricsTranslator.normalizeKey("hello world")])
    }

    @Test
    fun streamedTokenIsNotCutOff() {
        val jwt = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ.eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxfQ.c2lnbmF0dXJlLXNpZ25hdHVyZS1fLQ"
        val full = "const a=\"$jwt\";"
        assertEquals(jwt, AppleMusicDirectClient.findCompleteDeveloperToken(full))
        assertNull(AppleMusicDirectClient.findCompleteDeveloperToken(full.substring(0, full.length - 4)))
        assertNull(AppleMusicDirectClient.findCompleteDeveloperToken("const a=\"" + jwt))
    }

    @Test
    fun asiFueSessionsIsRankedFirst() {
        val json = """{"resultCount":3,"results":[
          {"trackId":1774311616,"trackName":"LA NUBE EN EL JARDÍN","artistName":"Ed Maverick","collectionName":"LA NUBE EN EL JARDÍN","trackTimeMillis":3229000},
          {"trackId":1486681372,"trackName":"karpe diem (En Vivo)","artistName":"Ed Maverick","collectionName":"karpe diem (En Vivo) - Single","trackTimeMillis":388413},
          {"trackId":6833728368,"trackName":"Así Fue (Apple Music Sessions)","artistName":"Ed Maverick","collectionName":"Sessions","trackTimeMillis":345232}]}"""
        for (title in listOf("Así Fue (Apple Music Sessions)", "Asi Fue", "Así Fue")) {
            val ids = AppleMusicLyricsClient.rankTrackIds(
                json,
                LyricsRequest(title, "Ed Maverick", "Sessions", 345_000L),
            )
            assertEquals("title=$title", 6833728368L, ids.firstOrNull())
        }
    }

    private val ttmlWithLocalizations = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
            xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word">
          <head><metadata><iTunesMetadata>
            <translations><translation xml:lang="es-MX"><text for="L1">Te quiero mucho</text></translation></translations>
            <transliterations><transliteration xml:lang="ja-Latn"><text for="L1"><span begin="0:01.000" end="0:02.000">aishi</span> <span begin="0:02.000" end="0:03.000">teru</span><span ttm:role="x-bg"><span begin="0:02.000" end="0:03.000">(yeah)</span></span></text></transliteration></transliterations>
          </iTunesMetadata></metadata></head>
          <body><div>
            <p begin="0:01.000" end="0:03.000" itunes:key="L1" ttm:agent="v1"><span begin="0:01.000" end="0:02.000">愛し</span><span begin="0:02.000" end="0:03.000">てる</span></p>
          </div></body></tt>
    """.trimIndent()

    @Test
    fun appleRomanizationAndTranslationFromLocalizationDocument() {
        val main = ttmlWithLocalizations.substringBefore("<head>") + "<head><metadata/></head>" +
            "<body>" + ttmlWithLocalizations.substringAfter("<body>")
        val result = requireNotNull(AppleTtml.convert(main, "es", listOf(ttmlWithLocalizations)))
        val line = requireNotNull(AppleMusicLyricsClient.parseResponse(result.json.toString(), 1L)).lines.single()
        assertEquals("Te quiero mucho", line.translation)
        assertEquals("aishi teru", line.romanization)
    }

    @Test
    fun appleRomanizationInsideMainDocument() {
        val result = requireNotNull(AppleTtml.convert(ttmlWithLocalizations, ""))
        val line = requireNotNull(AppleMusicLyricsClient.parseResponse(result.json.toString(), 1L)).lines.single()
        assertEquals("aishi teru", line.romanization)
        assertEquals("", line.translation)
    }

    @Test
    fun extraModeFiltersWhatTheUiSees() {
        val state = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(LyricLine(0, "愛", translation = "amor", romanization = "ai")),
        )
        val both = state.forMode(LyricsExtraMode.BOTH)
        assertTrue(both === state)
        val onlyTranslation = state.forMode(LyricsExtraMode.TRANSLATION).lines.single()
        assertEquals("amor", onlyTranslation.translation); assertEquals("", onlyTranslation.romanization)
        val onlyRoman = state.forMode(LyricsExtraMode.ROMANIZATION).lines.single()
        assertEquals("", onlyRoman.translation); assertEquals("ai", onlyRoman.romanization)
        val off = state.forMode(LyricsExtraMode.OFF).lines.single()
        assertEquals("", off.translation + off.romanization)
        assertEquals(LyricsExtraMode.BOTH, LyricsExtraMode.OFF.next().next().next())
        assertEquals(LyricsExtraMode.TRANSLATION, LyricsExtraMode.fromKey(null, legacyTranslationOn = true))
        assertEquals(LyricsExtraMode.OFF, LyricsExtraMode.fromKey("bogus"))
    }

    @Test
    fun appleLocalizationRequestQuery() {
        assertNull(AppleMusicDirectClient.localizationQuery("", false))
        assertEquals("es-MX", AppleMusicDirectClient.appleLanguageTag("es"))
        assertEquals("pt-PT", AppleMusicDirectClient.appleLanguageTag("pt-PT"))
        val query = requireNotNull(AppleMusicDirectClient.localizationQuery("es", true))
        assertTrue(query.contains("l%5Blyrics%5D=es-MX"))
        assertTrue(query.contains("l%5Bscript%5D=en-Latn"))
        assertTrue(query.contains("extend=ttmlLocalizations"))
        val romanOnly = requireNotNull(AppleMusicDirectClient.localizationQuery("", true))
        assertTrue(!romanOnly.contains("lyrics"))
    }

    @Test
    fun localizationDocumentsAreCollectedFromAnyShape() {
        val asString = """{"data":[{"attributes":{"ttml":"<tt/>","ttmlLocalizations":"<tt><a/></tt>"}}]}"""
        assertEquals(1, AppleMusicDirectClient.extractLocalizations(asString).size)
        val asObject = """{"data":[{"attributes":{"ttmlLocalizations":{"es":"<tt/>","ja":"<tt/>"}}}]}"""
        assertEquals(2, AppleMusicDirectClient.extractLocalizations(asObject).size)
        assertEquals(0, AppleMusicDirectClient.extractLocalizations("""{"data":[]}""").size)
    }

    @Test
    fun romanizerSkipsLatinAndRunsOnOtherScripts() {
        assertTrue(!Romanizer.needsRomanization("Hola, ¿cómo estás? 123"))
        assertTrue(Romanizer.needsRomanization("愛してる"))
        assertTrue(Romanizer.needsRomanization("사랑해 baby"))
        val out = Romanizer().romanize(listOf(LyricLine(0, "Hello"), LyricLine(1, "你好")))
        assertEquals(listOf("", "romaji:你好"), out)
        assertNull(Romanizer().romanize(listOf(LyricLine(0, "Hello"))))
    }
}
