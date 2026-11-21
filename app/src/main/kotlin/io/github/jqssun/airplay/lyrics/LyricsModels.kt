package io.github.jqssun.airplay.lyrics

data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

enum class LyricAlignment {
    LEADING,
    TRAILING,
}

data class LyricLine(
    val timeMs: Long,
    /** Primary vocal text exactly as it should be presented. */
    val text: String,
    /** Apple-supplied timing for the primary vocal only. */
    val words: List<LyricWord> = emptyList(),
    /** True when at least one visible vocal stream has precise timing. */
    val exactWordTiming: Boolean = false,
    /**
     * Legacy combined-row range retained for older cached/provider payloads.
     * New Apple rows keep backing vocals in [backgroundText]/[backgroundWords].
     */
    val asideWordRange: IntRange? = null,
    /** Optional Apple-provided end time for a synchronized lyric line. */
    val endMs: Long? = null,
    /** Vocal turns from a second singer use the opposite edge in the TV UI. */
    val alignment: LyricAlignment = LyricAlignment.LEADING,
    /** Compact backing vocal, kept independent because it may overlap the main vocal. */
    val backgroundText: String = "",
    /** Apple-supplied timing for [backgroundText], on its own monotonic timeline. */
    val backgroundWords: List<LyricWord> = emptyList(),
    /** Stable singer/agent identifier used to resolve duet placement. */
    val agentId: String = "",
    /** Optional translation of [text] into the viewer's language. */
    val translation: String = "",
)

enum class WordSyncMode {
    NONE,
    /** Apple publishes timestamps for each full lyric row, but not each word. */
    LINE,
    /** Apple publishes a complete syllable/word timeline for at least one vocal part. */
    ENHANCED,
}

enum class LyricsStatus {
    IDLE,
    LOADING,
    SYNCED,
    PLAIN,
    INSTRUMENTAL,
    NOT_FOUND,
    ERROR,
}

data class LyricsState(
    val status: LyricsStatus = LyricsStatus.IDLE,
    val lines: List<LyricLine> = emptyList(),
    val plainLyrics: String = "",
    /** Composition & Lyrics names published on the matching Apple Music page. */
    val writerCredit: String = "",
    val sourceId: Long? = null,
    val wordSyncMode: WordSyncMode = WordSyncMode.NONE,
)

data class LyricsRequest(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)
