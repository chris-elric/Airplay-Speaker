package io.github.jqssun.airplay.lyrics

/** Which extra line is shown under each lyric. */
enum class LyricsExtraMode(val key: String) {
    OFF("off"),
    TRANSLATION("translation"),
    ROMANIZATION("romanization"),
    BOTH("both");

    val wantsTranslation: Boolean get() = this == TRANSLATION || this == BOTH
    val wantsRomanization: Boolean get() = this == ROMANIZATION || this == BOTH

    /** Settings order: off -> translation -> romanization -> both -> off. */
    fun next(): LyricsExtraMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromKey(key: String?, legacyTranslationOn: Boolean = false): LyricsExtraMode =
            entries.firstOrNull { it.key == key } ?: if (legacyTranslationOn) TRANSLATION else OFF
    }
}

/** User-controlled lyric options, read fresh for every lookup. */
data class LyricsSettings(
    /**
     * Value of the `media-user-token` cookie from the user's own Apple Music
     * web session. It unlocks Apple's word-by-word lyrics for that account.
     */
    val appleMediaUserToken: String = "",
    /** Optional personal Musixmatch user token; an anonymous one is used when blank. */
    val musixmatchToken: String = "",
    val extraMode: LyricsExtraMode = LyricsExtraMode.OFF,
    /** BCP-47 language code such as "es". */
    val translationLanguage: String = "es",
) {
    val translationEnabled: Boolean get() = extraMode.wantsTranslation
    val romanizationEnabled: Boolean get() = extraMode.wantsRomanization
}
