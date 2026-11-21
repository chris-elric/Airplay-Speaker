package io.github.jqssun.airplay.lyrics

/** User-controlled lyric options, read fresh for every lookup. */
data class LyricsSettings(
    /**
     * Value of the `media-user-token` cookie from the user's own Apple Music
     * web session. It unlocks Apple's word-by-word lyrics for that account.
     */
    val appleMediaUserToken: String = "",
    /** Optional personal Musixmatch user token; an anonymous one is used when blank. */
    val musixmatchToken: String = "",
    val translationEnabled: Boolean = false,
    /** BCP-47 language code such as "es". */
    val translationLanguage: String = "es",
)
