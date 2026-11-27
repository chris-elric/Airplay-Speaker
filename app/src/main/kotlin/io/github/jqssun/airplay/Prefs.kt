package io.github.jqssun.airplay

import android.media.MediaFormat

/** Centralized preference keys and defaults. */
object Prefs {
    const val NAME = "settings"

    const val SERVER_NAME = "server_name"; const val DEF_SERVER_NAME = "Airplay Speaker"
    const val SERVER_PORT = "server_port"; const val DEF_SERVER_PORT = 7000
    const val AUTO_START = "auto_start"; const val DEF_AUTO_START = true
    const val BOOT_AUTO_START = "boot_auto_start"; const val DEF_BOOT_AUTO_START = true
    const val RUN_IN_BACKGROUND = "run_in_background"; const val DEF_RUN_IN_BACKGROUND = true
    const val H265_ENABLED = "h265_enabled"; const val DEF_H265_ENABLED = true
    const val ENFORCE_SDR = "enforce_sdr"; const val DEF_ENFORCE_SDR = true
    val KEY_ALLOW_FRAME_DROP: String = MediaFormat.KEY_ALLOW_FRAME_DROP; const val DEF_KEY_ALLOW_FRAME_DROP = true
    val KEY_PRIORITY: String = MediaFormat.KEY_PRIORITY; const val DEF_KEY_PRIORITY = true
    val KEY_OPERATING_RATE: String = MediaFormat.KEY_OPERATING_RATE; const val DEF_KEY_OPERATING_RATE = true
    const val SCHEDULED_OUTPUT_BUFFER_RELEASE = "scheduled_output_buffer_release"; const val DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE = false
    const val AUDIO_BUFFER_MULTIPLIER = "audio_buffer_multiplier"; const val DEF_AUDIO_BUFFER_MULTIPLIER = 4
    const val ALAC_ENABLED = "alac_enabled"; const val DEF_ALAC_ENABLED = true
    const val SW_ALAC_ENABLED = "sw_alac_enabled"; const val DEF_SW_ALAC_ENABLED = true
    const val AAC_ENABLED = "aac_enabled"; const val DEF_AAC_ENABLED = true
    const val LOSSLESS_OUTPUT = "lossless_output"; const val DEF_LOSSLESS_OUTPUT = true
    const val SPATIAL_ENHANCEMENT = "spatial_enhancement"; const val DEF_SPATIAL_ENHANCEMENT = false
    const val KARAOKE_ENABLED = "karaoke_enabled"; const val DEF_KARAOKE_ENABLED = false
    const val KARAOKE_VOCAL_LEVEL = "karaoke_vocal_level"; const val DEF_KARAOKE_VOCAL_LEVEL = 35
    const val AI_VOCAL_SEPARATION = "ai_vocal_separation"; const val DEF_AI_VOCAL_SEPARATION = false
    const val APP_VOLUME = "app_volume"; const val DEF_APP_VOLUME = 100
    const val RESOLUTION = "resolution"; const val DEF_RESOLUTION = "auto"
    const val MAX_FPS = "max_fps"; const val DEF_MAX_FPS = 60
    const val OVERSCANNED = "overscanned"; const val DEF_OVERSCANNED = false
    const val REQUIRE_PIN = "require_pin"; const val DEF_REQUIRE_PIN = false
    const val ALLOW_NEW_CONN = "allow_new_conn"; const val DEF_ALLOW_NEW_CONN = true
    const val AUDIO_LATENCY_MS = "audio_latency_ms"; const val DEF_AUDIO_LATENCY_MS = -1
    const val DEBUG_ENABLED = "debug_enabled"; const val DEF_DEBUG_ENABLED = false
    const val DEVELOPER_OPTIONS = "developer_options"; const val DEF_DEVELOPER_OPTIONS = false
    const val BENCHMARK_LOG = "benchmark_log"; const val DEF_BENCHMARK_LOG = false
    const val IDLE_PREVIEW = "idle_preview"; const val DEF_IDLE_PREVIEW = false
    const val MOTION_ARTWORK_ENABLED = "motion_artwork_enabled"; const val DEF_MOTION_ARTWORK_ENABLED = true
    const val FULLSCREEN_MOTION_ARTWORK = "fullscreen_motion_artwork"; const val DEF_FULLSCREEN_MOTION_ARTWORK = true
    const val LYRICS_VISIBLE = "lyrics_visible"; const val DEF_LYRICS_VISIBLE = true
    const val APPLE_MEDIA_USER_TOKEN = "apple_media_user_token"; const val DEF_APPLE_MEDIA_USER_TOKEN = ""
    const val MUSIXMATCH_TOKEN = "musixmatch_token"; const val DEF_MUSIXMATCH_TOKEN = ""
    // Legacy on/off switch, only read to migrate to LYRICS_EXTRA_MODE.
    const val LYRICS_TRANSLATION = "lyrics_translation"; const val DEF_LYRICS_TRANSLATION = false
    const val LYRICS_EXTRA_MODE = "lyrics_extra_mode"
    const val LYRICS_DELAY_MS = "lyrics_delay_ms"; const val DEF_LYRICS_DELAY_MS = 0
    const val LYRICS_DELAY_LIMIT_MS = 3000
    const val LYRICS_TRANSLATION_LANGUAGE = "lyrics_translation_language"; const val DEF_LYRICS_TRANSLATION_LANGUAGE = "es"
    const val AUTO_FULLSCREEN = "auto_fullscreen"; const val DEF_AUTO_FULLSCREEN = true
    const val KEEP_SCREEN_ON = "keep_screen_on"; const val DEF_KEEP_SCREEN_ON = true
    const val ADVERTISE_VIDEO = "advertise_video"; const val DEF_ADVERTISE_VIDEO = false
    const val ADVERTISE_AUDIO = "advertise_audio"; const val DEF_ADVERTISE_AUDIO = true
    const val LAUNCH_ON_CONNECT = "launch_on_connect"; const val DEF_LAUNCH_ON_CONNECT = true

    fun lyricsExtraMode(prefs: android.content.SharedPreferences): io.github.jqssun.airplay.lyrics.LyricsExtraMode =
        io.github.jqssun.airplay.lyrics.LyricsExtraMode.fromKey(
            prefs.getString(LYRICS_EXTRA_MODE, null),
            legacyTranslationOn = prefs.getBoolean(LYRICS_TRANSLATION, DEF_LYRICS_TRANSLATION),
        )
}
