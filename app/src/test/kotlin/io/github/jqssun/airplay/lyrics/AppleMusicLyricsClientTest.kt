package io.github.jqssun.airplay.lyrics

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicLyricsClientTest {

    @Test
    fun parsesOppositeSingerAndBackgroundVocalTiming() {
        val response = """
            {
              "provider": "apple_music",
              "syncType": "Syllable",
              "lyrics": [
                {
                  "timestamp": 39495,
                  "endtime": 40912,
                  "oppositeTurn": false,
                  "text": [
                    {"text":"A", "timestamp":39495, "endtime":39600},
                    {"text":"face", "timestamp":39600, "endtime":40000}
                  ],
                  "backgroundText": [
                    {"text":"And", "timestamp":40912, "endtime":41196},
                    {"text":"watches", "timestamp":41196, "endtime":41549}
                  ]
                },
                {
                  "timestamp": 124238,
                  "endtime": 131197,
                  "oppositeTurn": true,
                  "agent": "v2",
                  "text": [
                    {"text":"The", "timestamp":124238, "endtime":124800},
                    {"text":"sun", "timestamp":124800, "endtime":131197}
                  ],
                  "backgroundText": []
                }
              ]
            }
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseResponse(response, 590431777L)

        assertNotNull(state)
        assertEquals(LyricsStatus.SYNCED, state?.status)
        assertEquals("A face", state?.lines?.first()?.text)
        assertEquals("And watches", state?.lines?.first()?.backgroundText)
        assertEquals(2, state?.lines?.first()?.words?.size)
        assertEquals(2, state?.lines?.first()?.backgroundWords?.size)
        assertEquals(41_549L, state?.lines?.first()?.endMs)
        assertEquals(LyricAlignment.LEADING, state?.lines?.first()?.alignment)
        assertEquals(LyricAlignment.TRAILING, state?.lines?.last()?.alignment)
        assertEquals("The sun", state?.lines?.last()?.text)
        assertTrue(state?.lines?.all { it.exactWordTiming } == true)
    }

    @Test
    fun selectsTheStudioTrackWithMatchingDuration() {
        val response = """
            {
              "results": [
                {"trackId":1,"trackName":"Papercut (Live)","artistName":"LINKIN PARK",
                 "collectionName":"Live","trackTimeMillis":230556},
                {"trackId":2,"trackName":"Papercut","artistName":"LINKIN PARK",
                 "collectionName":"Hybrid Theory","trackTimeMillis":184469}
              ]
            }
        """.trimIndent()

        val id = AppleMusicLyricsClient.selectTrackId(
            response,
            LyricsRequest("Papercut", "LINKIN PARK", "Hybrid Theory", 184_866L),
        )

        assertEquals(2L, id)
    }

    @Test
    fun keepsAlternateAppleTracksSoALyriclessFirstMatchCanFallBack() {
        val response = """
            {
              "results": [
                {"trackId":11,"trackName":"Stay","artistName":"Example Artist",
                 "collectionName":"Stay - Single","trackTimeMillis":201000},
                {"trackId":12,"trackName":"Stay (Remastered 2024)","artistName":"Example Artist",
                 "collectionName":"The Album","trackTimeMillis":202500},
                {"trackId":13,"trackName":"Stay (Live)","artistName":"Example Artist",
                 "collectionName":"Live at Home","trackTimeMillis":286000}
              ]
            }
        """.trimIndent()

        val ids = AppleMusicLyricsClient.rankTrackIds(
            response,
            LyricsRequest("Stay", "Example Artist", "The Album", 202_000L),
        )

        assertEquals(listOf(12L, 11L, 13L), ids)
    }

    @Test
    fun returnsThePreferredLyricsWithoutRequestingABrokenDuplicate() = runBlocking {
        val requested = mutableListOf<Long>()

        val result = AppleMusicLyricsClient.resolveFirstCandidate(
            listOf(908_544_388L, 910_881_256L),
        ) { trackId ->
            requested += trackId
            if (trackId == 908_544_388L) "73 synced lines" else {
                error("The lower-ranked duplicate must not be requested")
            }
        }

        assertEquals(908_544_388L, result?.first)
        assertEquals("73 synced lines", result?.second)
        assertEquals(listOf(908_544_388L), requested)
    }

    @Test
    fun continuesToTheNextCandidateWhenThePreferredRequestFails() = runBlocking {
        val result = AppleMusicLyricsClient.resolveFirstCandidate(
            listOf(11L, 12L),
        ) { trackId ->
            if (trackId == 11L) throw IOException("Provider failure")
            "fallback lyrics"
        }

        assertEquals(12L, result?.first)
        assertEquals("fallback lyrics", result?.second)
    }

    @Test
    fun matchesFeaturedArtistsAndVersionSuffixesFromAirplayMetadata() {
        val response = """
            {
              "results": [
                {"trackId":42,"trackName":"Northern Lights - Remastered 2025",
                 "artistName":"Nova Bloom & Mira Lane",
                 "collectionName":"Northern Lights","trackTimeMillis":224000}
              ]
            }
        """.trimIndent()

        val id = AppleMusicLyricsClient.selectTrackId(
            response,
            LyricsRequest(
                "Northern Lights (Remastered Version)",
                "Nova Bloom feat. Mira Lane",
                "Northern Lights",
                224_300L,
            ),
        )

        assertEquals(42L, id)
    }

    @Test
    fun createsCleanSearchFallbacksForFeaturedAndRemasteredMetadata() {
        val terms = AppleMusicLyricsClient.searchTerms(
            LyricsRequest(
                "Northern Lights (Remastered Version)",
                "Nova Bloom feat. Mira Lane",
                "Northern Lights",
                224_300L,
            ),
        )

        assertTrue(terms.contains("Nova Bloom Northern Lights"))
        assertTrue(terms.contains("Northern Lights Nova Bloom"))
        assertTrue(terms.contains("Northern Lights"))
    }

    @Test
    fun findsAnAlbumCollectionEvenWhenSearchOmitsTheRequestedTrack() {
        val siblingSearchResponse = """
            {
              "results": [
                {"collectionId":908544384,"trackId":908544386,
                 "trackName":"Action Cat","artistName":"Gerard Way",
                 "collectionName":"Hesitant Alien","trackTimeMillis":187000},
                {"collectionId":908544384,"trackId":908544388,
                 "trackName":"Brother","artistName":"Gerard Way",
                 "collectionName":"Hesitant Alien","trackTimeMillis":270580}
              ]
            }
        """.trimIndent()
        val request = LyricsRequest("No Shows", "Gerard Way", "Hesitant Alien", 252_329L)

        val collectionIds = AppleMusicLyricsClient.rankCollectionIds(
            siblingSearchResponse,
            request,
        )
        val albumTerms = AppleMusicLyricsClient.albumSearchTerms(request)

        assertEquals(listOf(908_544_384L), collectionIds)
        assertTrue(albumTerms.contains("Gerard Way Hesitant Alien"))
    }

    @Test
    fun selectsAnOmittedSongFromTheFullAlbumLookup() {
        val albumLookupResponse = """
            {
              "results": [
                {"wrapperType":"collection","collectionId":908544384,
                 "artistName":"Gerard Way","collectionName":"Hesitant Alien"},
                {"wrapperType":"track","collectionId":908544384,
                 "trackId":908544387,"trackName":"No Shows",
                 "artistName":"Gerard Way","collectionName":"Hesitant Alien",
                 "trackTimeMillis":252329}
              ]
            }
        """.trimIndent()

        val ids = AppleMusicLyricsClient.rankTrackIds(
            albumLookupResponse,
            LyricsRequest("No Shows", "Gerard Way", "Hesitant Alien", 252_329L),
        )

        assertEquals(listOf(908_544_387L), ids)
    }

    @Test
    fun aSingleAgentLabelDoesNotPushTheWholeSongToTheRight() {
        val response = """
            {
              "lyrics": [
                {
                  "timestamp": 1000,
                  "endtime": 2000,
                  "agent": "v2",
                  "text": [
                    {"text":"Second singer", "timestamp":1000, "endtime":2000}
                  ]
                }
              ]
            }
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseResponse(response, 1L)

        assertEquals(LyricAlignment.LEADING, state?.lines?.single()?.alignment)
    }

    @Test
    fun distinctAgentsRestoreDuetSidesWhenBooleanFlagIsMissing() {
        val response = """
            {
              "lyrics": [
                {
                  "timestamp": 1000,
                  "agent": "v2",
                  "text": [{"text":"First singer", "timestamp":1000, "endtime":2000}]
                },
                {
                  "timestamp": 3000,
                  "agent": "v1",
                  "text": [{"text":"Second singer", "timestamp":3000, "endtime":4000}]
                }
              ]
            }
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseResponse(response, 1L)

        assertEquals(LyricAlignment.LEADING, state?.lines?.first()?.alignment)
        assertEquals(LyricAlignment.TRAILING, state?.lines?.last()?.alignment)
    }

    @Test
    fun lineSyncedAppleLyricsStayLineSyncedWithoutInventedWords() {
        val response = """
            {
              "lyrics": [
                {"timestamp":1000,"endtime":2500,"text":"Whole line one"},
                {"timestamp":3000,"endtime":4500,"text":"Whole line two"}
              ]
            }
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseResponse(response, 1L)

        assertEquals(LyricsStatus.SYNCED, state?.status)
        assertTrue(state?.lines?.all { it.words.isEmpty() } == true)
        assertTrue(state?.lines?.none { it.exactWordTiming } == true)
        assertEquals(WordSyncMode.LINE, state?.wordSyncMode)
    }

    @Test
    fun lineSyncTypeDoesNotTreatAWholeRowTokenAsWordByWord() {
        val response = """
            {
              "syncType":"Line",
              "lyrics":[
                {
                  "timestamp":19000,
                  "endtime":23748,
                  "text":[
                    {
                      "text":"Take away the sensation inside",
                      "timestamp":19000,
                      "endtime":23748
                    }
                  ]
                },
                {
                  "timestamp":25030,
                  "endtime":29152,
                  "text":[
                    {
                      "text":"Bittersweet",
                      "timestamp":25030,
                      "endtime":27000
                    },
                    {
                      "text":"migraine in my head",
                      "timestamp":27000,
                      "endtime":29152
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseResponse(response, 207192831L)

        assertEquals(LyricsStatus.SYNCED, state?.status)
        assertEquals(WordSyncMode.LINE, state?.wordSyncMode)
        assertEquals(
            listOf(
                "Take away the sensation inside",
                "Bittersweet migraine in my head",
            ),
            state?.lines?.map { it.text },
        )
        assertTrue(state?.lines?.all { it.words.isEmpty() } == true)
        assertTrue(state?.lines?.none { it.exactWordTiming } == true)
    }

    @Test
    fun explicitSyllableSyncKeepsEvenASingleTimedWord() {
        val response = """
            {
              "syncType":"Syllable",
              "lyrics":[
                {
                  "timestamp":1000,
                  "endtime":1800,
                  "text":[
                    {"text":"Hey","timestamp":1000,"endtime":1800}
                  ]
                }
              ]
            }
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseResponse(response, 1L)

        assertEquals(WordSyncMode.ENHANCED, state?.wordSyncMode)
        assertTrue(state?.lines?.single()?.exactWordTiming == true)
        assertEquals(1, state?.lines?.single()?.words?.size)
    }

    @Test
    fun ttmlClockStringsRemainLineSynced() {
        val response = """
            {
              "lyrics": [
                {"timestamp":"00:01.250","endtime":"00:03.000","text":"First timed row"},
                {"timestamp":"3.5s","endtime":"PT5.25S","text":"Second timed row"}
              ]
            }
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseResponse(response, 1L)

        assertEquals(LyricsStatus.SYNCED, state?.status)
        assertEquals(WordSyncMode.LINE, state?.wordSyncMode)
        assertEquals(listOf(1_250L, 3_500L), state?.lines?.map { it.timeMs })
        assertEquals(listOf(3_000L, 5_250L), state?.lines?.map { it.endMs })
        assertTrue(state?.lines?.all { it.words.isEmpty() } == true)
    }

    @Test
    fun unsynchronisedAppleLyricsRemainReadablePlainText() {
        val response = """
            {
              "lyrics": [
                {"text":"First plain line"},
                {"text":"Second plain line"}
              ]
            }
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseResponse(response, 1L)

        assertEquals(LyricsStatus.PLAIN, state?.status)
        assertEquals(WordSyncMode.NONE, state?.wordSyncMode)
        assertEquals("First plain line\nSecond plain line", state?.plainLyrics)
        assertTrue(state?.lines?.isEmpty() == true)
    }

    @Test
    fun timedMainVocalSurvivesAnUntimedCompactBackingVocal() {
        val response = """
            {
              "lyrics": [
                {
                  "timestamp":1000,
                  "endtime":3000,
                  "text":[
                    {"text":"Main","timestamp":1000,"endtime":1600},
                    {"text":"line","timestamp":1600,"endtime":2200}
                  ],
                  "backgroundText":"soft echo"
                }
              ]
            }
        """.trimIndent()

        val line = AppleMusicLyricsClient.parseResponse(response, 1L)?.lines?.single()

        assertTrue(line?.exactWordTiming == true)
        assertEquals("Main line", line?.text)
        assertEquals("soft echo", line?.backgroundText)
        assertEquals("Main line", line?.words?.joinToString("") { it.text })
        assertTrue(line?.backgroundWords?.isEmpty() == true)
    }

    @Test
    fun timedCompactVocalSurvivesAnUntimedMainVocal() {
        val response = """
            {
              "lyrics": [
                {
                  "timestamp":1000,
                  "endtime":3000,
                  "text":"Big line",
                  "backgroundText":[
                    {"text":"small","timestamp":1500,"endtime":2000},
                    {"text":"voice","timestamp":2000,"endtime":2500}
                  ]
                }
              ]
            }
        """.trimIndent()

        val line = AppleMusicLyricsClient.parseResponse(response, 1L)?.lines?.single()

        assertTrue(line?.exactWordTiming == true)
        assertEquals("Big line", line?.text)
        assertTrue(line?.words?.isEmpty() == true)
        assertEquals("small voice", line?.backgroundText)
        assertEquals("small voice", line?.backgroundWords?.joinToString("") { it.text })
    }

    @Test
    fun missingSyllableEndsUseTheNextOnsetAndPublishedLineEnd() {
        val response = """
            {
              "lyrics": [
                {
                  "timestamp":1000,
                  "endtime":2800,
                  "text":[
                    {"text":"Sing","timestamp":1000},
                    {"text":"it","timestamp":1600},
                    {"text":"smooth","timestamp":2100}
                  ]
                }
              ]
            }
        """.trimIndent()

        val words = AppleMusicLyricsClient.parseResponse(response, 1L)
            ?.lines
            ?.single()
            ?.words
            .orEmpty()

        assertEquals(listOf(1_600L, 2_100L, 2_800L), words.map { it.endMs })
        assertTrue(words.all { it.endMs - it.startMs >= 500L })
    }

    @Test
    fun tokenSpacingFollowsTheWritingSystemAndExplicitProviderSpaces() {
        assertEquals("", AppleMusicLyricsClient.separatorAfter("ฉัน", "รัก"))
        assertEquals("", AppleMusicLyricsClient.separatorAfter("你", "好"))
        assertEquals("", AppleMusicLyricsClient.separatorAfter("あ", "い"))
        assertEquals("", AppleMusicLyricsClient.separatorAfter("ຂ້ອຍ", "ຮັກ"))
        assertEquals("", AppleMusicLyricsClient.separatorAfter("ស្រឡាញ់", "អ្នក"))
        assertEquals("", AppleMusicLyricsClient.separatorAfter("မင်္ဂလာ", "ပါ"))

        // Korean, Arabic, Cyrillic and Latin normally retain inter-word spaces.
        assertEquals(" ", AppleMusicLyricsClient.separatorAfter("나는", "너를"))
        assertEquals(" ", AppleMusicLyricsClient.separatorAfter("أنا", "أحبك"))
        assertEquals(" ", AppleMusicLyricsClient.separatorAfter("я", "тебя"))
        assertEquals(" ", AppleMusicLyricsClient.separatorAfter("love", "you"))

        // Existing spaces and attached punctuation must never be doubled.
        assertEquals("", AppleMusicLyricsClient.separatorAfter("love ", "you"))
        assertEquals("", AppleMusicLyricsClient.separatorAfter("love", " you"))
        assertEquals("", AppleMusicLyricsClient.separatorAfter("hello", ","))
        assertEquals("", AppleMusicLyricsClient.separatorAfter("don", "'t"))
        assertEquals("", AppleMusicLyricsClient.separatorAfter("ca", "n't"))
        assertEquals("", AppleMusicLyricsClient.separatorAfter("rock-", "and"))
        assertEquals("", AppleMusicLyricsClient.separatorAfter("你", "「"))
        assertEquals("", AppleMusicLyricsClient.separatorAfter("，", "好"))
    }

    @Test
    fun thaiWordTimingKeepsNaturalCompactSpacing() {
        val response = """
            {
              "lyrics": [
                {
                  "timestamp":1000,
                  "endtime":3000,
                  "text":[
                    {"text":"ฉัน","timestamp":1000,"endtime":1500},
                    {"text":"รัก","timestamp":1500,"endtime":2000},
                    {"text":"เธอ ","timestamp":2000,"endtime":2500},
                    {"text":"มาก","timestamp":2500,"endtime":3000}
                  ]
                }
              ]
            }
        """.trimIndent()

        val line = AppleMusicLyricsClient.parseResponse(response, 1L)?.lines?.single()

        assertEquals("ฉันรักเธอ มาก", line?.text)
        assertEquals("ฉันรักเธอ มาก", line?.words?.joinToString("") { it.text })
        assertTrue(line?.exactWordTiming == true)
    }

    @Test
    fun parsesOnlyWritingCreditsFromAppleCompositionSection() {
        val applePage = """
            <script>
            {
              "id":"composer-and-lyrics",
              "items":[
                {"name":"Madonna","roleNames":["Songwriter"]},
                {"name":"Stephen Bray","roleNames":["Composer","Songwriter"]},
                {"name":"Madonna","roleNames":["Lyricist"]},
                {"name":"Example Producer","roleNames":["Producer"]}
              ]
            }
            </script>
        """.trimIndent()

        val credit = AppleMusicLyricsClient.parseWriterCredit(applePage)

        assertEquals("Madonna, Stephen Bray", credit)
    }

    @Test
    fun missingAppleCompositionSectionDoesNotInventAWriter() {
        val credit = AppleMusicLyricsClient.parseWriterCredit(
            """<script>{"id":"artist-biography","items":[]}</script>"""
        )

        assertEquals("", credit)
    }

    @Test
    fun readsTheRowListFromAContentKeyAndTypeLabel() {
        val response = """
            {
              "type": "Syllable",
              "content": [
                {
                  "timestamp": 1000,
                  "endtime": 2400,
                  "oppositeTurn": false,
                  "text": [
                    {"text":"Hello", "timestamp":1000, "endtime":1500},
                    {"text":"world", "timestamp":1500, "endtime":2400}
                  ],
                  "background": [
                    {"text":"echo", "timestamp":2000, "endtime":2400}
                  ]
                }
              ]
            }
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseResponse(response, 7L)

        assertEquals(LyricsStatus.SYNCED, state?.status)
        assertEquals(WordSyncMode.ENHANCED, state?.wordSyncMode)
        assertEquals("Hello world", state?.lines?.single()?.text)
        assertEquals("echo", state?.lines?.single()?.backgroundText)
        assertEquals(2, state?.lines?.single()?.words?.size)
    }

    @Test
    fun unwrapsAnEnvelopeAndATopLevelArray() {
        val enveloped = """
            {"status":"ok","data":{"lyrics":[
              {"timestamp":1000,"endtime":2000,"text":"Wrapped line"}
            ]}}
        """.trimIndent()
        val bare = """[{"timestamp":1000,"endtime":2000,"text":"Bare line"}]"""

        assertEquals(
            "Wrapped line",
            AppleMusicLyricsClient.parseResponse(enveloped, 1L)?.lines?.single()?.text,
        )
        assertEquals(
            "Bare line",
            AppleMusicLyricsClient.parseResponse(bare, 1L)?.lines?.single()?.text,
        )
    }

    @Test
    fun anErrorPayloadIsNotMistakenForLyrics() {
        assertEquals(
            null,
            AppleMusicLyricsClient.parseResponse("""{"error":"rate limited"}""", 1L),
        )
    }

    @Test
    fun parsesLrcRowsAndIgnoresMetadata() {
        val lrc = """
            [ar:Some Artist]
            [ti:Some Title]
            [00:01.50] First line
            [00:04.00]<00:04.00>Second <00:04.50>line
            [00:07.25]
            [00:09.5][01:00.000] Repeated chorus
        """.trimIndent()

        val lines = AppleMusicLyricsClient.parseLrc(lrc)

        assertEquals(
            listOf("First line", "Second line", "Repeated chorus", "Repeated chorus"),
            lines.map { it.text },
        )
        assertEquals(listOf(1_500L, 4_000L, 9_500L, 60_000L), lines.map { it.timeMs })
        // A blank row closes the previous line instead of leaving it open.
        assertEquals(7_250L, lines[1].endMs)
        assertEquals(4_000L, lines[0].endMs)
        assertTrue(lines.all { it.words.isEmpty() && !it.exactWordTiming })
    }

    @Test
    fun lrclibItemsBecomeLineSyncedPlainOrInstrumental() {
        val synced = org.json.JSONObject(
            """{"id":5,"syncedLyrics":"[00:01.00] One\n[00:02.00] Two","plainLyrics":"One\nTwo"}"""
        )
        val plainOnly = org.json.JSONObject(
            """{"id":6,"syncedLyrics":null,"plainLyrics":"Just text"}"""
        )
        val instrumental = org.json.JSONObject("""{"id":7,"instrumental":true}""")

        val syncedState = AppleMusicLyricsClient.parseLrclibItem(synced)
        assertEquals(LyricsStatus.SYNCED, syncedState?.status)
        assertEquals(WordSyncMode.LINE, syncedState?.wordSyncMode)
        assertEquals(listOf("One", "Two"), syncedState?.lines?.map { it.text })
        assertEquals(5L, syncedState?.sourceId)

        val plainState = AppleMusicLyricsClient.parseLrclibItem(plainOnly)
        assertEquals(LyricsStatus.PLAIN, plainState?.status)
        assertEquals("Just text", plainState?.plainLyrics)

        assertEquals(
            LyricsStatus.INSTRUMENTAL,
            AppleMusicLyricsClient.parseLrclibItem(instrumental)?.status,
        )
    }

    @Test
    fun lrclibSearchPrefersTheSyncedResultWithTheClosestDuration() {
        val response = """
            [
              {"id":1,"trackName":"Stay","artistName":"Example Artist","duration":260,
               "syncedLyrics":"[00:01.00] Wrong length"},
              {"id":2,"trackName":"Stay","artistName":"Example Artist","duration":201,
               "syncedLyrics":null,"plainLyrics":"Plain only"},
              {"id":3,"trackName":"Stay","artistName":"Example Artist","duration":203,
               "syncedLyrics":"[00:01.00] Right song"},
              {"id":4,"trackName":"Other Song","artistName":"Example Artist","duration":202,
               "syncedLyrics":"[00:01.00] Wrong title"}
            ]
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseLrclibSearch(
            response,
            LyricsRequest("Stay", "Example Artist", "The Album", 202_000L),
        )

        assertEquals(3L, state?.sourceId)
        assertEquals("Right song", state?.lines?.single()?.text)
    }

    @Test
    fun neteaseSearchPicksTheRecordingWithTheMatchingDuration() {
        val response = """
            {"code":200,"result":{"songs":[
              {"id":101,"name":"Stay (Live)","artists":[{"name":"Example Artist"}],
               "album":{"name":"Live at Home"},"duration":286000},
              {"id":102,"name":"Stay","artists":[{"name":"Example Artist"},{"name":"Guest"}],
               "album":{"name":"The Album"},"duration":201500},
              {"id":103,"name":"Stay","artists":[{"name":"Someone Else"}],
               "album":{"name":"Other"},"duration":202000}
            ]}}
        """.trimIndent()

        val ids = AppleMusicLyricsClient.rankNeteaseSongIds(
            response,
            LyricsRequest("Stay", "Example Artist", "The Album", 202_000L),
        )

        assertEquals(listOf(102L), ids)
    }

    @Test
    fun neteaseLyricsDropLeadingCreditRowsAndKeepSungLines() {
        val response = """
            {"code":200,"lrc":{"lyric":"[00:00.000] 作词 : Someone\n[00:01.000] 作曲 : Someone\n[00:12.340]First sung line\n[00:15.000]Second sung line"}}
        """.trimIndent()

        val state = AppleMusicLyricsClient.parseNeteaseLyric(response, 102L)

        assertEquals(LyricsStatus.SYNCED, state?.status)
        assertEquals(WordSyncMode.LINE, state?.wordSyncMode)
        assertEquals(listOf("First sung line", "Second sung line"), state?.lines?.map { it.text })
        assertEquals(12_340L, state?.lines?.first()?.timeMs)
        assertEquals(102L, state?.sourceId)
    }

    @Test
    fun neteaseInstrumentalAndUncollectedFlagsAreHonoured() {
        assertEquals(
            LyricsStatus.INSTRUMENTAL,
            AppleMusicLyricsClient.parseNeteaseLyric("""{"nolyric":true}""", 1L)?.status,
        )
        assertEquals(
            null,
            AppleMusicLyricsClient.parseNeteaseLyric("""{"uncollected":true}""", 1L),
        )
    }

    @Test
    fun qqSearchReadsMidsAndRejectsWrongDurations() {
        val response = """
            {"code":0,"data":{"song":{"list":[
              {"songmid":"000AAA","songid":11,"songname":"Stay","albumname":"The Album",
               "singer":[{"name":"Example Artist"}],"interval":202},
              {"songmid":"000BBB","songid":12,"songname":"Stay","albumname":"Live",
               "singer":[{"name":"Example Artist"}],"interval":290}
            ]}}}
        """.trimIndent()

        val songs = AppleMusicLyricsClient.rankQqSongs(
            response,
            LyricsRequest("Stay", "Example Artist", "The Album", 202_000L),
        )

        assertEquals(listOf("000AAA"), songs.map { it.mid })
        assertEquals(11L, songs.single().id)
    }

    @Test
    fun qqLyricsDecodeHtmlEntitiesAndJsonpWrappers() {
        val body = "MusicJsonCallback({\"retcode\":0,\"code\":0,\"lyric\":" +
            "\"[ti:Stay]&#10;[00&#58;01&#46;50]Don&apos;t go&#10;[00&#58;04&#46;00]Stay here\"})"

        val state = AppleMusicLyricsClient.parseQqLyric(body, 11L)

        assertEquals(LyricsStatus.SYNCED, state?.status)
        assertEquals(listOf("Don't go", "Stay here"), state?.lines?.map { it.text })
        assertEquals(listOf(1_500L, 4_000L), state?.lines?.map { it.timeMs })
        assertEquals(null, AppleMusicLyricsClient.parseQqLyric("""{"retcode":-1901}""", 1L))
    }

    @Test
    fun htmlUnescapeHandlesNumericHexAndNamedEntities() {
        assertEquals(
            "a:b. & <c> \"d\" 'e'",
            AppleMusicLyricsClient.htmlUnescape("a&#58;b&#x2E; &amp; &lt;c&gt; &quot;d&quot; &apos;e&apos;"),
        )
        assertEquals("plain", AppleMusicLyricsClient.htmlUnescape("plain"))
        assertEquals("&#99999999;", AppleMusicLyricsClient.htmlUnescape("&#99999999;"))
    }

    @Test
    fun fallbackChoiceRanksSyncedFirstThenSourceOrder() {
        val plain = LyricsState(status = LyricsStatus.PLAIN, plainLyrics = "text", sourceId = 1L)
        val syncedQq = LyricsState(
            status = LyricsStatus.SYNCED,
            lines = listOf(LyricLine(1_000L, "qq")),
            sourceId = 2L,
        )
        val syncedLrclib = syncedQq.copy(sourceId = 3L)
        val instrumental = LyricsState(status = LyricsStatus.INSTRUMENTAL, sourceId = 4L)

        assertEquals(2L, AppleMusicLyricsClient.chooseFallback(listOf(plain, syncedQq, syncedLrclib))?.sourceId)
        assertEquals(1L, AppleMusicLyricsClient.chooseFallback(listOf(instrumental, plain))?.sourceId)
        assertEquals(null, AppleMusicLyricsClient.chooseFallback(listOf(null, null)))
    }
}

