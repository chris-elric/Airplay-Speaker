package io.github.jqssun.airplay.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Surface
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.artwork.AnimatedArtworkRequest
import io.github.jqssun.airplay.artwork.AppleMusicArtworkClient
import io.github.jqssun.airplay.artwork.RemoteArtworkLoader
import io.github.jqssun.airplay.audio.DacpQueueState
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.lyrics.LyricsExtraMode
import io.github.jqssun.airplay.lyrics.LyricsState
import io.github.jqssun.airplay.lyrics.forMode
import io.github.jqssun.airplay.renderer.AudioMeterLevels
import io.github.jqssun.airplay.renderer.isAiVocalSeparationSupported
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class DebugInfo(
    val videoCodec: String = "",
    val videoRes: String = "",
    val videoFps: Int = 0,
    val videoBitrate: Long = 0,
    val videoFrames: Long = 0,
    val droppedFrames: Long = 0,
    val framePacingJitterUs: Long = 0,
    val audioCodec: String = "",
    val audioVolume: Int = 100,
    val connections: Int = 0,
) {
    val bitrateStr: String get() {
        val kbps = videoBitrate / 1000
        return if (kbps >= 1000) "${"%.1f".format(kbps / 1000.0)} Mbps" else "$kbps Kbps"
    }
    val jitterStr: String get() {
        return if (framePacingJitterUs >= 1000) {
            "${"%.1f".format(framePacingJitterUs / 1000.0)} ms"
        } else {
            "$framePacingJitterUs us"
        }
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    private val logFile = File(app.filesDir, "airplay_logs.txt")
    private val animatedArtworkClient = AppleMusicArtworkClient()
    private val remoteArtworkLoader = RemoteArtworkLoader()
    private var service: AirPlayService? = null
    val dacpPlayer: Player? get() = service?.dacpPlayer
    val aiVocalSeparationSupported: Boolean = isAiVocalSeparationSupported()

    fun audioVolumeUp() {
        setAppVolume((_appVolume.value + APP_VOLUME_STEP).coerceAtMost(100))
    }
    fun audioVolumeDown() {
        setAppVolume((_appVolume.value - APP_VOLUME_STEP).coerceAtLeast(0))
    }
    fun audioScanBegin(forward: Boolean) {
        service?.dacpController?.let { if (forward) it.beginFastForward() else it.beginRewind() }
    }
    fun audioScanEnd() { service?.dacpController?.playResume() }
    fun audioMuteToggle() {
        if (_appVolume.value == 0) {
            setAppVolume(lastAudibleAppVolume.coerceAtLeast(APP_VOLUME_STEP))
        } else {
            lastAudibleAppVolume = _appVolume.value
            setAppVolume(0)
        }
    }
    fun playPreviousQueueTrack() { service?.dacpController?.prevItem() }
    fun playQueuedTrack(relativeIndex: Int) {
        if (relativeIndex > 0) service?.dacpController?.playQueueItem(relativeIndex)
    }
    fun sampleAudioPositionMs(): Long {
        val activeService = service
        return if (activeService?.audioOnly?.value == true) {
            activeService.currentPositionMs()
        } else {
            _audioPositionMs.value
        }
    }

    fun sampleAudioMeterLevels(): AudioMeterLevels {
        val activeService = service
        return if (
            activeService?.audioOnly?.value == true &&
            activeService.playing.value
        ) {
            activeService.audioRenderer.audioMeterLevels()
        } else {
            AudioMeterLevels.Silent
        }
    }

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    private val _pinCode = MutableStateFlow<String?>(null)
    val pinCode: StateFlow<String?> = _pinCode.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution: StateFlow<String> = _videoResolution.asStateFlow()

    private val _serverName = MutableStateFlow(prefs.getString(Prefs.SERVER_NAME, Prefs.DEF_SERVER_NAME)!!)
    val serverName: StateFlow<String> = _serverName.asStateFlow()

    // settings
    private val _serverPort = MutableStateFlow(prefs.getInt(Prefs.SERVER_PORT, Prefs.DEF_SERVER_PORT))
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _autoStart = MutableStateFlow(prefs.getBoolean(Prefs.AUTO_START, Prefs.DEF_AUTO_START))
    val autoStart: StateFlow<Boolean> = _autoStart.asStateFlow()

    private val _bootAutoStart = MutableStateFlow(prefs.getBoolean(Prefs.BOOT_AUTO_START, Prefs.DEF_BOOT_AUTO_START))
    val bootAutoStart: StateFlow<Boolean> = _bootAutoStart.asStateFlow()

    private val _runInBackground = MutableStateFlow(prefs.getBoolean(Prefs.RUN_IN_BACKGROUND, Prefs.DEF_RUN_IN_BACKGROUND))
    val runInBackground: StateFlow<Boolean> = _runInBackground.asStateFlow()

    private val _h265Enabled = MutableStateFlow(prefs.getBoolean(Prefs.H265_ENABLED, Prefs.DEF_H265_ENABLED))
    val h265Enabled: StateFlow<Boolean> = _h265Enabled.asStateFlow()

    private val _enforceSdr = MutableStateFlow(prefs.getBoolean(Prefs.ENFORCE_SDR, Prefs.DEF_ENFORCE_SDR))
    val enforceSdr: StateFlow<Boolean> = _enforceSdr.asStateFlow()

    private val _keyAllowFrameDrop = MutableStateFlow(prefs.getBoolean(Prefs.KEY_ALLOW_FRAME_DROP, Prefs.DEF_KEY_ALLOW_FRAME_DROP))
    val keyAllowFrameDrop: StateFlow<Boolean> = _keyAllowFrameDrop.asStateFlow()

    private val _realtimeDecoderPriority = MutableStateFlow(prefs.getBoolean(Prefs.KEY_PRIORITY, Prefs.DEF_KEY_PRIORITY))
    val realtimeDecoderPriority: StateFlow<Boolean> = _realtimeDecoderPriority.asStateFlow()

    private val _operatingRateHint = MutableStateFlow(prefs.getBoolean(Prefs.KEY_OPERATING_RATE, Prefs.DEF_KEY_OPERATING_RATE))
    val operatingRateHint: StateFlow<Boolean> = _operatingRateHint.asStateFlow()

    private val _scheduledOutputBufferRelease = MutableStateFlow(prefs.getBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, Prefs.DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE))
    val scheduledOutputBufferRelease: StateFlow<Boolean> = _scheduledOutputBufferRelease.asStateFlow()

    private val _benchmarkLog = MutableStateFlow(prefs.getBoolean(Prefs.BENCHMARK_LOG, Prefs.DEF_BENCHMARK_LOG))
    val benchmarkLog: StateFlow<Boolean> = _benchmarkLog.asStateFlow()

    private val _alacEnabled = MutableStateFlow(prefs.getBoolean(Prefs.ALAC_ENABLED, Prefs.DEF_ALAC_ENABLED))
    val alacEnabled: StateFlow<Boolean> = _alacEnabled.asStateFlow()

    private val _swAlacEnabled = MutableStateFlow(prefs.getBoolean(Prefs.SW_ALAC_ENABLED, Prefs.DEF_SW_ALAC_ENABLED))
    val swAlacEnabled: StateFlow<Boolean> = _swAlacEnabled.asStateFlow()

    private val _aacEnabled = MutableStateFlow(prefs.getBoolean(Prefs.AAC_ENABLED, Prefs.DEF_AAC_ENABLED))
    val aacEnabled: StateFlow<Boolean> = _aacEnabled.asStateFlow()

    private val _losslessOutput = MutableStateFlow(
        prefs.getBoolean(Prefs.LOSSLESS_OUTPUT, Prefs.DEF_LOSSLESS_OUTPUT)
    )
    val losslessOutput: StateFlow<Boolean> = _losslessOutput.asStateFlow()

    private val _spatialEnhancement = MutableStateFlow(
        prefs.getBoolean(Prefs.SPATIAL_ENHANCEMENT, Prefs.DEF_SPATIAL_ENHANCEMENT)
    )
    val spatialEnhancement: StateFlow<Boolean> = _spatialEnhancement.asStateFlow()

    private val _karaokeEnabled = MutableStateFlow(
        prefs.getBoolean(Prefs.KARAOKE_ENABLED, Prefs.DEF_KARAOKE_ENABLED)
    )
    val karaokeEnabled: StateFlow<Boolean> = _karaokeEnabled.asStateFlow()

    private val _karaokeVocalLevel = MutableStateFlow(
        prefs.getInt(Prefs.KARAOKE_VOCAL_LEVEL, Prefs.DEF_KARAOKE_VOCAL_LEVEL)
            .coerceIn(0, 100)
    )
    val karaokeVocalLevel: StateFlow<Int> = _karaokeVocalLevel.asStateFlow()

    private val _aiVocalSeparation = MutableStateFlow(
        aiVocalSeparationSupported &&
            prefs.getBoolean(
                Prefs.AI_VOCAL_SEPARATION,
                Prefs.DEF_AI_VOCAL_SEPARATION,
            )
    )
    val aiVocalSeparation: StateFlow<Boolean> = _aiVocalSeparation.asStateFlow()

    private val _resolution = MutableStateFlow(prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION)!!)
    val resolution: StateFlow<String> = _resolution.asStateFlow()

    private val _maxFps = MutableStateFlow(prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS))
    val maxFps: StateFlow<Int> = _maxFps.asStateFlow()

    private val _overscanned = MutableStateFlow(prefs.getBoolean(Prefs.OVERSCANNED, Prefs.DEF_OVERSCANNED))
    val overscanned: StateFlow<Boolean> = _overscanned.asStateFlow()

    private val _requirePin = MutableStateFlow(prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN))
    val requirePin: StateFlow<Boolean> = _requirePin.asStateFlow()

    private val _allowNewConn = MutableStateFlow(prefs.getBoolean(Prefs.ALLOW_NEW_CONN, Prefs.DEF_ALLOW_NEW_CONN))
    val allowNewConn: StateFlow<Boolean> = _allowNewConn.asStateFlow()

    private val _audioLatencyMs = MutableStateFlow(prefs.getInt(Prefs.AUDIO_LATENCY_MS, Prefs.DEF_AUDIO_LATENCY_MS))
    val audioLatencyMs: StateFlow<Int> = _audioLatencyMs.asStateFlow()

    private val _idlePreview = MutableStateFlow(prefs.getBoolean(Prefs.IDLE_PREVIEW, Prefs.DEF_IDLE_PREVIEW))
    val idlePreview: StateFlow<Boolean> = _idlePreview.asStateFlow()

    private val _motionArtworkEnabled = MutableStateFlow(
        prefs.getBoolean(Prefs.MOTION_ARTWORK_ENABLED, Prefs.DEF_MOTION_ARTWORK_ENABLED)
    )
    val motionArtworkEnabled: StateFlow<Boolean> = _motionArtworkEnabled.asStateFlow()

    private val _fullscreenMotionArtwork = MutableStateFlow(
        prefs.getBoolean(
            Prefs.FULLSCREEN_MOTION_ARTWORK,
            Prefs.DEF_FULLSCREEN_MOTION_ARTWORK,
        )
    )
    val fullscreenMotionArtwork: StateFlow<Boolean> = _fullscreenMotionArtwork.asStateFlow()

    private val _lyricsVisible = MutableStateFlow(
        prefs.getBoolean(Prefs.LYRICS_VISIBLE, Prefs.DEF_LYRICS_VISIBLE)
    )
    val lyricsVisible: StateFlow<Boolean> = _lyricsVisible.asStateFlow()

    private val _appleMediaUserToken = MutableStateFlow(
        prefs.getString(Prefs.APPLE_MEDIA_USER_TOKEN, Prefs.DEF_APPLE_MEDIA_USER_TOKEN).orEmpty()
    )
    val appleMediaUserToken: StateFlow<String> = _appleMediaUserToken.asStateFlow()

    private val _musixmatchToken = MutableStateFlow(
        prefs.getString(Prefs.MUSIXMATCH_TOKEN, Prefs.DEF_MUSIXMATCH_TOKEN).orEmpty()
    )
    val musixmatchToken: StateFlow<String> = _musixmatchToken.asStateFlow()

    private val _lyricsExtraMode = MutableStateFlow(Prefs.lyricsExtraMode(prefs))
    val lyricsExtraMode: StateFlow<LyricsExtraMode> = _lyricsExtraMode.asStateFlow()

    /** Positive values show lyrics earlier (use when the audio runs ahead of them). */
    private val _lyricsDelayMs = MutableStateFlow(
        prefs.getInt(Prefs.LYRICS_DELAY_MS, Prefs.DEF_LYRICS_DELAY_MS)
            .coerceIn(-Prefs.LYRICS_DELAY_LIMIT_MS, Prefs.LYRICS_DELAY_LIMIT_MS)
    )
    val lyricsDelayMs: StateFlow<Int> = _lyricsDelayMs.asStateFlow()

    private val _lyricsTranslationLanguage = MutableStateFlow(
        prefs.getString(Prefs.LYRICS_TRANSLATION_LANGUAGE, Prefs.DEF_LYRICS_TRANSLATION_LANGUAGE)
            .orEmpty().ifBlank { Prefs.DEF_LYRICS_TRANSLATION_LANGUAGE }
    )
    val lyricsTranslationLanguage: StateFlow<String> = _lyricsTranslationLanguage.asStateFlow()

    private val initialAppVolume = prefs.getInt(Prefs.APP_VOLUME, Prefs.DEF_APP_VOLUME)
        .coerceIn(0, 100)
    private val _appVolume = MutableStateFlow(initialAppVolume)
    val appVolume: StateFlow<Int> = _appVolume.asStateFlow()
    private val _appVolumeActivity = MutableStateFlow(0L)
    val appVolumeActivity: StateFlow<Long> = _appVolumeActivity.asStateFlow()
    private var lastAudibleAppVolume = initialAppVolume.takeIf { it > 0 } ?: Prefs.DEF_APP_VOLUME

    private val _autoFullscreen = MutableStateFlow(prefs.getBoolean(Prefs.AUTO_FULLSCREEN, Prefs.DEF_AUTO_FULLSCREEN))
    val autoFullscreen: StateFlow<Boolean> = _autoFullscreen.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(Prefs.KEEP_SCREEN_ON, Prefs.DEF_KEEP_SCREEN_ON))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _advertiseVideo = MutableStateFlow(prefs.getBoolean(Prefs.ADVERTISE_VIDEO, Prefs.DEF_ADVERTISE_VIDEO))
    val advertiseVideo: StateFlow<Boolean> = _advertiseVideo.asStateFlow()

    private val _advertiseAudio = MutableStateFlow(prefs.getBoolean(Prefs.ADVERTISE_AUDIO, Prefs.DEF_ADVERTISE_AUDIO))
    val advertiseAudio: StateFlow<Boolean> = _advertiseAudio.asStateFlow()

    private val _launchOnConnect = MutableStateFlow(prefs.getBoolean(Prefs.LAUNCH_ON_CONNECT, Prefs.DEF_LAUNCH_ON_CONNECT))
    val launchOnConnect: StateFlow<Boolean> = _launchOnConnect.asStateFlow()

    // debug
    private val _debugEnabled = MutableStateFlow(prefs.getBoolean(Prefs.DEBUG_ENABLED, Prefs.DEF_DEBUG_ENABLED))
    val debugEnabled: StateFlow<Boolean> = _debugEnabled.asStateFlow()

    private val _developerOptions = MutableStateFlow(prefs.getBoolean(Prefs.DEVELOPER_OPTIONS, Prefs.DEF_DEVELOPER_OPTIONS))
    val developerOptions: StateFlow<Boolean> = _developerOptions.asStateFlow()

    private val _audioBufferMultiplier = MutableStateFlow(prefs.getInt(Prefs.AUDIO_BUFFER_MULTIPLIER, Prefs.DEF_AUDIO_BUFFER_MULTIPLIER))
    val audioBufferMultiplier: StateFlow<Int> = _audioBufferMultiplier.asStateFlow()

    private val _debugInfo = MutableStateFlow(DebugInfo())
    val debugInfo: StateFlow<DebugInfo> = _debugInfo.asStateFlow()

    // audio mode
    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    private val _videoPlaybackActive = MutableStateFlow(false)
    val videoPlaybackActive: StateFlow<Boolean> = _videoPlaybackActive.asStateFlow()

    // video channel polling but /play not received yet
    private val _videoSessionPending = MutableStateFlow(false)
    val videoSessionPending: StateFlow<Boolean> = _videoSessionPending.asStateFlow()

    // airplay video transport overlay state

    // bumping the tick shows the overlay and restarts its auto-hide timer
    private val _videoOverlayTick = MutableStateFlow(0L)
    val videoOverlayTick: StateFlow<Long> = _videoOverlayTick.asStateFlow()

    private val _videoPositionMs = MutableStateFlow(0L)
    val videoPositionMs: StateFlow<Long> = _videoPositionMs.asStateFlow()

    private val _videoDurationMs = MutableStateFlow(0L)
    val videoDurationMs: StateFlow<Long> = _videoDurationMs.asStateFlow()

    private val _videoPlaybackAspect = MutableStateFlow(16f / 9f)
    val videoPlaybackAspect: StateFlow<Float> = _videoPlaybackAspect.asStateFlow()

    private val _videoPlaybackSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoPlaybackSize: StateFlow<Pair<Int, Int>?> = _videoPlaybackSize.asStateFlow()

    private val _videoBuffering = MutableStateFlow(false)
    val videoBuffering: StateFlow<Boolean> = _videoBuffering.asStateFlow()

    private val _videoTitle = MutableStateFlow("")
    val videoTitle: StateFlow<String> = _videoTitle.asStateFlow()

    private val _videoLocation = MutableStateFlow<String?>(null)
    val videoLocation: StateFlow<String?> = _videoLocation.asStateFlow()

    // optimistic for instant icon feedback, re-synced from the delayed snapshot
    private val _videoPlaying = MutableStateFlow(true)
    val videoPlaying: StateFlow<Boolean> = _videoPlaying.asStateFlow()
    private var _videoActionAt = 0L

    // null = idle, -1 = unknown, else 0..100
    private val _videoDownloadProgress = MutableStateFlow<Int?>(null)
    val videoDownloadProgress: StateFlow<Int?> = _videoDownloadProgress.asStateFlow()

    // mirror the player's playback parameters; sender /rate commands reset speed to 1
    private val _videoSpeed = MutableStateFlow(1f)
    val videoSpeed: StateFlow<Float> = _videoSpeed.asStateFlow()
    private val _videoSkipSilence = MutableStateFlow(false)
    val videoSkipSilence: StateFlow<Boolean> = _videoSkipSilence.asStateFlow()
    private var _videoParamsActionAt = 0L

    // non-null while a scrub is in flight; committed once after a debounce
    private val _videoScrubPositionMs = MutableStateFlow<Long?>(null)
    val videoScrubPositionMs: StateFlow<Long?> = _videoScrubPositionMs.asStateFlow()
    private var _scrubJob: Job? = null
    private var _lastVideoPlaySeq = 0L

    private val _mirroringActive = MutableStateFlow(false)
    val mirroringActive: StateFlow<Boolean> = _mirroringActive.asStateFlow()

    private val _trackInfo = MutableStateFlow(TrackInfo())
    val trackInfo: StateFlow<TrackInfo> = _trackInfo.asStateFlow()

    private val _previousTrack = MutableStateFlow<TrackInfo?>(null)
    val previousTrack: StateFlow<TrackInfo?> = _previousTrack.asStateFlow()

    private val _nextTrack = MutableStateFlow<TrackInfo?>(null)
    val nextTrack: StateFlow<TrackInfo?> = _nextTrack.asStateFlow()
    private val _upNextTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    val upNextTracks: StateFlow<List<TrackInfo>> = _upNextTracks.asStateFlow()
    private val _queueName = MutableStateFlow("")
    val queueName: StateFlow<String> = _queueName.asStateFlow()
    private var localPreviousTrack: TrackInfo? = null
    private var queueArtworkKey = ""
    private var queueArtworkJob: Job? = null

    private val _lyrics = MutableStateFlow(LyricsState())
    /** Lyrics as the UI should show them: only the extra line(s) chosen in Settings. */
    val lyrics: StateFlow<LyricsState> = kotlinx.coroutines.flow
        .combine(_lyrics, _lyricsExtraMode) { state, mode -> state.forMode(mode) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LyricsState())

    private val _audioPositionMs = MutableStateFlow(0L)
    val audioPositionMs: StateFlow<Long> = _audioPositionMs.asStateFlow()

    private val _audioOutputStatus = MutableStateFlow("")
    val audioOutputStatus: StateFlow<String> = _audioOutputStatus.asStateFlow()

    private val _audioPlaying = MutableStateFlow(false)
    val audioPlaying: StateFlow<Boolean> = _audioPlaying.asStateFlow()

    private val _animatedArtworkUrl = MutableStateFlow<String?>(null)
    val animatedArtworkUrl: StateFlow<String?> = _animatedArtworkUrl.asStateFlow()
    private val _animatedArtworkFullScreenUrl = MutableStateFlow<String?>(null)
    val animatedArtworkFullScreenUrl: StateFlow<String?> =
        _animatedArtworkFullScreenUrl.asStateFlow()
    private val _animatedArtworkCover = MutableStateFlow<Bitmap?>(null)
    val animatedArtworkCover: StateFlow<Bitmap?> = _animatedArtworkCover.asStateFlow()
    private val _animatedArtworkPreview = MutableStateFlow<Bitmap?>(null)
    val animatedArtworkPreview: StateFlow<Bitmap?> = _animatedArtworkPreview.asStateFlow()
    private var animatedArtworkKey = ""
    private var animatedArtworkJob: Job? = null


    // logs
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val _logLock = Any()
    private val _logList = mutableListOf<String>()
    private var logPersistJob: Job? = null
    private val _dateFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    init {
        loadPersistedLogs()
    }

    fun addLog(msg: String) {
        val line = "${_dateFmt.get()!!.format(Date())} $msg"
        val snapshot: List<String>
        synchronized(_logLock) {
            _logList.add(line)
            while (_logList.size > LOG_HISTORY_LIMIT) _logList.removeAt(0)
            snapshot = _logList.toList()
        }
        _logs.value = snapshot
        scheduleLogPersist()
    }

    fun clearLogs() {
        logPersistJob?.cancel()
        synchronized(_logLock) {
            _logList.clear()
        }
        _logs.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { logFile.delete() }
        }
    }

    fun exportLogs() {
        val ctx = getApplication<Application>()
        val file = File(ctx.cacheDir, "airplay_logs.txt")
        val exportText = synchronized(_logLock) { _logList.joinToString("\n") }
        file.writeText(exportText)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooserTitle = ctx.getString(io.github.jqssun.airplay.R.string.export_logs_chooser_title)
        ctx.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun loadPersistedLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val restored = runCatching {
                if (logFile.exists()) {
                    logFile.readLines().takeLast(LOG_HISTORY_LIMIT)
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())
            val snapshot = synchronized(_logLock) {
                // Native callbacks can arrive while the old file is loading.
                // Keep those fresh rows after the restored history.
                val fresh = _logList.toList()
                _logList.clear()
                _logList.addAll(restored)
                _logList.addAll(fresh)
                while (_logList.size > LOG_HISTORY_LIMIT) _logList.removeAt(0)
                _logList.toList()
            }
            _logs.value = snapshot
        }
    }

    private fun scheduleLogPersist() {
        logPersistJob?.cancel()
        logPersistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(LOG_PERSIST_DEBOUNCE_MS)
            val content = synchronized(_logLock) { _logList.joinToString("\n") }
            runCatching { logFile.writeText(content) }
        }
    }

    // settings setters
    private var _restartJob: Job? = null

    private fun _applyByServerRestart() {
        _restartJob?.cancel()
        _restartJob = viewModelScope.launch {
            delay(SERVER_RESTART_DEBOUNCE_MS)
            val svc = service ?: return@launch
            if (svc.serverState.value == ServerState.RUNNING) {
                svc.stopServer()
                svc.startServer(_serverName.value)
            }
        }
    }

    fun setServerPort(port: Int) { _serverPort.value = port; prefs.edit().putInt(Prefs.SERVER_PORT, port).apply(); _applyByServerRestart() }
    fun setServerName(name: String) { _serverName.value = name; prefs.edit().putString(Prefs.SERVER_NAME, name).apply(); _applyByServerRestart() }
    fun setAutoStart(v: Boolean) { _autoStart.value = v; prefs.edit().putBoolean(Prefs.AUTO_START, v).apply() }
    fun setBootAutoStart(v: Boolean) { _bootAutoStart.value = v; prefs.edit().putBoolean(Prefs.BOOT_AUTO_START, v).apply() }
    fun setRunInBackground(v: Boolean) { _runInBackground.value = v; prefs.edit().putBoolean(Prefs.RUN_IN_BACKGROUND, v).apply() }
    fun setH265Enabled(v: Boolean) { _h265Enabled.value = v; prefs.edit().putBoolean(Prefs.H265_ENABLED, v).apply(); _applyByServerRestart() }
    fun setEnforceSdr(v: Boolean) { _enforceSdr.value = v; prefs.edit().putBoolean(Prefs.ENFORCE_SDR, v).apply(); _applyByServerRestart() }
    fun setKeyAllowFrameDrop(v: Boolean) {
        _keyAllowFrameDrop.value = v
        prefs.edit().putBoolean(Prefs.KEY_ALLOW_FRAME_DROP, v).apply()
        _applyByServerRestart()
    }
    fun setRealtimeDecoderPriority(v: Boolean) {
        _realtimeDecoderPriority.value = v
        prefs.edit().putBoolean(Prefs.KEY_PRIORITY, v).apply()
        _applyByServerRestart()
    }
    fun setOperatingRateHint(v: Boolean) {
        _operatingRateHint.value = v
        prefs.edit().putBoolean(Prefs.KEY_OPERATING_RATE, v).apply()
        _applyByServerRestart()
    }
    fun setScheduledOutputBufferRelease(v: Boolean) {
        _scheduledOutputBufferRelease.value = v
        prefs.edit().putBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, v).apply()
        _applyByServerRestart()
    }
    fun setBenchmarkLog(v: Boolean) {
        _benchmarkLog.value = v
        prefs.edit().putBoolean(Prefs.BENCHMARK_LOG, v).apply()
        _applyByServerRestart()
    }
    fun setSwAlacEnabled(v: Boolean) { _swAlacEnabled.value = v; prefs.edit().putBoolean(Prefs.SW_ALAC_ENABLED, v).apply(); _applyByServerRestart() }
    fun setAlacEnabled(v: Boolean) { _alacEnabled.value = v; prefs.edit().putBoolean(Prefs.ALAC_ENABLED, v).apply(); _applyByServerRestart() }
    fun setAacEnabled(v: Boolean) { _aacEnabled.value = v; prefs.edit().putBoolean(Prefs.AAC_ENABLED, v).apply(); _applyByServerRestart() }
    fun setLosslessOutput(v: Boolean) { _losslessOutput.value = v; prefs.edit().putBoolean(Prefs.LOSSLESS_OUTPUT, v).apply(); _applyByServerRestart() }
    fun setSpatialEnhancement(v: Boolean) {
        _spatialEnhancement.value = v
        prefs.edit().putBoolean(Prefs.SPATIAL_ENHANCEMENT, v).apply()
        service?.setSpatialEnhancementEnabled(v)
    }
    fun setKaraokeEnabled(v: Boolean) {
        _karaokeEnabled.value = v
        prefs.edit().putBoolean(Prefs.KARAOKE_ENABLED, v).apply()
        service?.setKaraokeMode(v, _karaokeVocalLevel.value)
    }
    fun setKaraokeVocalLevel(percent: Int) {
        val value = percent.coerceIn(0, 100)
        _karaokeVocalLevel.value = value
        prefs.edit().putInt(Prefs.KARAOKE_VOCAL_LEVEL, value).apply()
        service?.setKaraokeMode(_karaokeEnabled.value, value)
    }
    fun setAiVocalSeparation(v: Boolean) {
        val enabled = v && aiVocalSeparationSupported
        _aiVocalSeparation.value = enabled
        prefs.edit().putBoolean(Prefs.AI_VOCAL_SEPARATION, enabled).apply()
        service?.setAiVocalSeparationEnabled(enabled)
    }
    fun adjustKaraokeVocalLevel(delta: Int) {
        if (!_karaokeEnabled.value) setKaraokeEnabled(true)
        setKaraokeVocalLevel(_karaokeVocalLevel.value + delta)
    }
    fun setResolution(v: String) { _resolution.value = v; prefs.edit().putString(Prefs.RESOLUTION, v).apply(); _applyByServerRestart() }
    fun setMaxFps(v: Int) { _maxFps.value = v; prefs.edit().putInt(Prefs.MAX_FPS, v).apply(); _applyByServerRestart() }
    fun setOverscanned(v: Boolean) { _overscanned.value = v; prefs.edit().putBoolean(Prefs.OVERSCANNED, v).apply(); _applyByServerRestart() }
    fun setRequirePin(v: Boolean) { _requirePin.value = v; prefs.edit().putBoolean(Prefs.REQUIRE_PIN, v).apply(); _applyByServerRestart() }
    fun setAllowNewConn(v: Boolean) { _allowNewConn.value = v; prefs.edit().putBoolean(Prefs.ALLOW_NEW_CONN, v).apply(); _applyByServerRestart() }
    fun setAudioLatencyMs(v: Int) { _audioLatencyMs.value = v; prefs.edit().putInt(Prefs.AUDIO_LATENCY_MS, v).apply(); _applyByServerRestart() }
    fun setIdlePreview(v: Boolean) { _idlePreview.value = v; prefs.edit().putBoolean(Prefs.IDLE_PREVIEW, v).apply() }
    fun setMotionArtworkEnabled(v: Boolean) {
        _motionArtworkEnabled.value = v
        prefs.edit().putBoolean(Prefs.MOTION_ARTWORK_ENABLED, v).apply()
        if (v) {
            requestAnimatedArtworkIfNeeded(_trackInfo.value)
        } else {
            animatedArtworkJob?.cancel()
            animatedArtworkJob = null
            animatedArtworkKey = ""
            _animatedArtworkUrl.value = null
            _animatedArtworkFullScreenUrl.value = null
            _animatedArtworkCover.value = null
            _animatedArtworkPreview.value = null
        }
    }
    fun setFullscreenMotionArtwork(v: Boolean) {
        _fullscreenMotionArtwork.value = v
        prefs.edit().putBoolean(Prefs.FULLSCREEN_MOTION_ARTWORK, v).apply()
    }
    fun setLyricsVisible(v: Boolean) {
        _lyricsVisible.value = v
        prefs.edit().putBoolean(Prefs.LYRICS_VISIBLE, v).apply()
    }
    fun setAppleMediaUserToken(v: String) {
        val value = v.trim()
        _appleMediaUserToken.value = value
        prefs.edit().putString(Prefs.APPLE_MEDIA_USER_TOKEN, value).apply()
    }
    fun setMusixmatchToken(v: String) {
        val value = v.trim()
        _musixmatchToken.value = value
        prefs.edit().putString(Prefs.MUSIXMATCH_TOKEN, value).apply()
    }
    fun setLyricsExtraMode(mode: LyricsExtraMode) {
        _lyricsExtraMode.value = mode
        prefs.edit().putString(Prefs.LYRICS_EXTRA_MODE, mode.key).apply()
        service?.refreshLyricTranslation()
    }
    fun setLyricsDelayMs(v: Int) {
        val value = v.coerceIn(-Prefs.LYRICS_DELAY_LIMIT_MS, Prefs.LYRICS_DELAY_LIMIT_MS)
        _lyricsDelayMs.value = value
        prefs.edit().putInt(Prefs.LYRICS_DELAY_MS, value).apply()
    }
    fun setLyricsTranslationLanguage(v: String) {
        val value = v.trim().ifBlank { Prefs.DEF_LYRICS_TRANSLATION_LANGUAGE }
        _lyricsTranslationLanguage.value = value
        prefs.edit().putString(Prefs.LYRICS_TRANSLATION_LANGUAGE, value).apply()
        service?.refreshLyricTranslation()
    }
    private fun setAppVolume(percent: Int) {
        val value = percent.coerceIn(0, 100)
        if (value > 0) lastAudibleAppVolume = value
        _appVolume.value = value
        _appVolumeActivity.value++
        prefs.edit().putInt(Prefs.APP_VOLUME, value).apply()
        service?.setAppVolume(value)
    }
    fun setAutoFullscreen(v: Boolean) { _autoFullscreen.value = v; prefs.edit().putBoolean(Prefs.AUTO_FULLSCREEN, v).apply() }
    fun setKeepScreenOn(v: Boolean) { _keepScreenOn.value = v; prefs.edit().putBoolean(Prefs.KEEP_SCREEN_ON, v).apply() }
    fun setAdvertiseVideo(v: Boolean) { _advertiseVideo.value = v; prefs.edit().putBoolean(Prefs.ADVERTISE_VIDEO, v).apply(); _applyByServerRestart() }
    fun setAdvertiseAudio(v: Boolean) { _advertiseAudio.value = v; prefs.edit().putBoolean(Prefs.ADVERTISE_AUDIO, v).apply(); _applyByServerRestart() }
    fun setLaunchOnConnect(v: Boolean) { _launchOnConnect.value = v; prefs.edit().putBoolean(Prefs.LAUNCH_ON_CONNECT, v).apply() }
    fun setDebugEnabled(v: Boolean) { _debugEnabled.value = v; prefs.edit().putBoolean(Prefs.DEBUG_ENABLED, v).apply() }
    fun setDeveloperOptions(v: Boolean) {
        _developerOptions.value = v
        prefs.edit().putBoolean(Prefs.DEVELOPER_OPTIONS, v).apply()
    }
    fun setAudioBufferMultiplier(v: Int) {
        val value = v.coerceIn(4, 8)
        _audioBufferMultiplier.value = value
        prefs.edit().putInt(Prefs.AUDIO_BUFFER_MULTIPLIER, value).apply()
        _applyByServerRestart()
    }

    // service binding
    fun bindService(svc: AirPlayService) {
        service = svc
        svc.setAppVolume(_appVolume.value)
        svc.setSpatialEnhancementEnabled(_spatialEnhancement.value)
        svc.setKaraokeMode(_karaokeEnabled.value, _karaokeVocalLevel.value)
        svc.setAiVocalSeparationEnabled(_aiVocalSeparation.value)
        updateFromService()
    }

    fun unbindService() {
        service = null
        animatedArtworkJob?.cancel()
        animatedArtworkJob = null
        animatedArtworkKey = ""
        _animatedArtworkUrl.value = null
        _animatedArtworkFullScreenUrl.value = null
        _animatedArtworkCover.value = null
        _animatedArtworkPreview.value = null
        queueArtworkJob?.cancel()
        queueArtworkJob = null
        queueArtworkKey = ""
        _previousTrack.value = null
        _nextTrack.value = null
        _upNextTracks.value = emptyList()
    }

    fun startServer() {
        service?.startServer(_serverName.value)
    }

    fun stopServer() {
        service?.stopServer()
    }

    fun onSurfaceAvailable(surface: Surface) {
        service?.setVideoSurface(surface)
    }

    fun onSurfaceDestroyed(surface: Surface) {
        service?.clearVideoSurface(surface)
    }

    fun onVideoPlaybackSurfaceAvailable(surface: Surface) {
        service?.setVideoPlaybackSurface(surface)
    }

    fun onVideoPlaybackSurfaceDestroyed(surface: Surface) {
        service?.clearVideoPlaybackSurface(surface)
    }

    fun toggleVideoPlayPause(showOverlay: Boolean = true) = setVideoPlaying(!_videoPlaying.value, showOverlay)

    fun setVideoPlaying(playing: Boolean, showOverlay: Boolean = true) {
        // pending session: there is no local media yet, nothing to toggle
        if (!_videoPlaybackActive.value) return
        _videoPlaying.value = playing
        _videoActionAt = SystemClock.elapsedRealtime()
        service?.setVideoPlaying(playing)
        if (showOverlay) showVideoOverlay()
    }

    // hold the target until the reported position catches up (hls rebuffer)
    private suspend fun _holdScrubUntilSettled(target: Long) {
        val deadline = SystemClock.elapsedRealtime() + SCRUB_SETTLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(SCRUB_SETTLE_POLL_MS)
            if (abs(_videoPositionMs.value - target) <= SCRUB_SETTLE_EPSILON_MS) break
        }
        _videoScrubPositionMs.value = null
    }

    // drag-seek: live seeks under scrubbing mode, settle-hold on release
    fun startVideoScrub() {
        _scrubJob?.cancel()
        service?.setVideoScrubbing(true)
    }

    fun scrubVideoTo(positionMs: Long) {
        val duration = _videoDurationMs.value
        if (duration <= 0) return
        val target = positionMs.coerceIn(0L, duration)
        _videoScrubPositionMs.value = target
        service?.seekVideoTo(target)
    }

    fun endVideoScrub() {
        service?.setVideoScrubbing(false)
        _scrubJob?.cancel()
        _scrubJob = viewModelScope.launch {
            _holdScrubUntilSettled(_videoScrubPositionMs.value ?: return@launch)
        }
    }

    fun showVideoOverlay() {
        _videoOverlayTick.value++
    }

    fun stopVideoPlayback() {
        service?.stopVideoPlayback()
    }

    fun setVideoSpeed(speed: Float) {
        val value = speed.coerceIn(0.2f, 4f)
        _videoSpeed.value = value
        _videoParamsActionAt = SystemClock.elapsedRealtime()
        service?.setVideoSpeed(value)
    }

    fun setVideoSkipSilence(enabled: Boolean) {
        _videoSkipSilence.value = enabled
        _videoParamsActionAt = SystemClock.elapsedRealtime()
        service?.setVideoSkipSilence(enabled)
    }

    fun seekVideoBy(deltaMs: Long) {
        service?.seekVideoBy(deltaMs)
    }

    fun toggleVideoDownload() {
        val svc = service ?: return
        if (_videoDownloadProgress.value != null) svc.videoDownloader.cancel() else svc.downloadVideo()
        showVideoOverlay()
    }

    fun dismissPin() {
        _pinCode.value = null
    }

    fun showPin(pin: String?) {
        _pinCode.value = pin
    }

    fun updateFromService() {
        service?.let {
            _serverState.value = it.serverState.value
            _connectionCount.value = it.connectionCount.value
            _videoAspect.value = it.videoAspect.value
            _videoResolution.value = it.videoResolution.value
            _audioOnly.value = it.audioOnly.value
            val videoActive = it.videoPlaybackActive.value
            val playSeq = it.videoPlaySeq.value
            if (playSeq != _lastVideoPlaySeq) {
                // fresh /play (incl. back-to-back): drop stale overlay and scrub state
                _lastVideoPlaySeq = playSeq
                _videoPlaying.value = true
                _videoActionAt = 0
                _videoOverlayTick.value = 0
                _videoSpeed.value = 1f
                _videoSkipSilence.value = false
                _scrubJob?.cancel()
                _videoScrubPositionMs.value = null
            }
            _videoPlaybackActive.value = videoActive
            _videoSessionPending.value = it.videoSessionPending()
            _videoDownloadProgress.value = it.videoDownloader.progress.value
            if (videoActive) {
                val info = it.videoPlaybackInfo.value
                _videoPositionMs.value = info.positionMs
                _videoDurationMs.value = info.durationMs
                if (SystemClock.elapsedRealtime() - _videoParamsActionAt > VIDEO_ACTION_SYNC_HOLD_MS) {
                    _videoSpeed.value = info.speed
                    _videoSkipSilence.value = info.skipSilence
                }
                _videoBuffering.value = info.buffering
                _videoPlaybackAspect.value = it.videoPlaybackAspect.value
                _videoPlaybackSize.value = it.videoPlaybackSize.value
                // stream metadata first, dmap second; senders rarely provide either for video
                _videoTitle.value = it.videoTitle.value.ifEmpty { it.trackInfo.value.title }
                _videoLocation.value = it.videoLocation.value
                // stale snapshots must not overwrite a just-made optimistic action
                if (SystemClock.elapsedRealtime() - _videoActionAt > VIDEO_ACTION_SYNC_HOLD_MS) {
                    _videoPlaying.value = info.playing
                }
            } else {
                _videoPositionMs.value = 0
                _videoDurationMs.value = 0
                _videoBuffering.value = false
            }
            _mirroringActive.value = it.mirroringActive.value
            val nextTrack = it.trackInfo.value
            val priorTrack = _trackInfo.value
            if (!priorTrack.sameSongAs(nextTrack) && priorTrack.title.isNotBlank()) {
                localPreviousTrack = priorTrack
            }
            _trackInfo.value = nextTrack
            requestAnimatedArtworkIfNeeded(nextTrack)
            updateQueueTracks(it.dacpController?.queue?.value ?: DacpQueueState(), nextTrack)
            _lyrics.value = it.lyrics.value
            _audioPositionMs.value = if (it.audioOnly.value) it.currentPositionMs() else 0L
            _audioOutputStatus.value = listOf(
                it.audioRenderer.codecLabel,
                it.audioRenderer.outputPathLabel,
            ).filter { label -> label.isNotBlank() }.joinToString(" · ")
            _audioPlaying.value = it.audioOnly.value && it.playing.value
            val serviceAppVolume = it.appVolumePercent()
            if (_appVolume.value != serviceAppVolume) {
                _appVolume.value = serviceAppVolume
                if (serviceAppVolume > 0) lastAudibleAppVolume = serviceAppVolume
            }
            if (_debugEnabled.value) {
                _debugInfo.value = it.collectDebugInfo()
            }
        }
    }

    private fun updateQueueTracks(queue: DacpQueueState, current: TrackInfo) {
        _queueName.value = queue.name
        val previous = queue.previous?.takeUnless { it.sameSongAs(current) } ?: localPreviousTrack
        val existingArtwork = _upNextTracks.value.associateBy { it.identityKey() }
        val rawUpcoming = if (queue.upNext.firstOrNull()?.sameSongAs(current) == true) {
            queue.upNext.drop(1)
        } else {
            queue.upNext
        }
        val upcoming = rawUpcoming.map { queuedTrack ->
            val existing = existingArtwork[queuedTrack.identityKey()]
            if (existing?.coverArt != null) queuedTrack.copy(coverArt = existing.coverArt) else queuedTrack
        }
        val key = buildList {
            add(previous.identityKey())
            addAll(upcoming.map { it.identityKey() })
        }.joinToString("\u0001")
        if (key == queueArtworkKey) return
        queueArtworkKey = key
        queueArtworkJob?.cancel()

        _previousTrack.value = previous
        _upNextTracks.value = upcoming
        _nextTrack.value = upcoming.firstOrNull()
        prefetchQueueArtwork(0)
    }

    /** Loads only the selected card and its neighbours to stay light on 2 GB TVs. */
    fun prefetchQueueArtwork(centerIndex: Int) {
        val key = queueArtworkKey
        if (key.isBlank()) return
        queueArtworkJob?.cancel()
        queueArtworkJob = viewModelScope.launch {
            val previousWithArt = loadQueueArtwork(_previousTrack.value)
            if (queueArtworkKey != key) return@launch
            _previousTrack.value = previousWithArt

            val initial = _upNextTracks.value
            if (initial.isEmpty()) return@launch
            val indices = initial.indices
                .filter { kotlin.math.abs(it - centerIndex) <= QUEUE_ARTWORK_RADIUS }
                .sortedBy { kotlin.math.abs(it - centerIndex) }
            for (index in indices) {
                val before = _upNextTracks.value.getOrNull(index) ?: continue
                val withArtwork = loadQueueArtwork(before) ?: continue
                if (queueArtworkKey != key) return@launch
                val latest = _upNextTracks.value.toMutableList()
                if (latest.getOrNull(index)?.identityKey() != before.identityKey()) continue
                latest[index] = withArtwork
                _upNextTracks.value = latest
                _nextTrack.value = latest.firstOrNull()
            }
        }
    }

    private suspend fun loadQueueArtwork(track: TrackInfo?): TrackInfo? {
        if (track == null || track.coverArt != null || track.title.isBlank() || track.artist.isBlank()) {
            return track
        }
        val artworkUrl = animatedArtworkClient.findStaticArtwork(
            AnimatedArtworkRequest(
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
            )
        ) ?: return track
        return track.copy(coverArt = remoteArtworkLoader.load(artworkUrl, targetEdgePx = 512))
    }

    private fun TrackInfo?.identityKey(): String = this?.let {
        listOf(it.title, it.artist, it.album)
            .joinToString("\u0000") { value -> value.trim().lowercase(Locale.ROOT) }
    }.orEmpty()

    private fun TrackInfo.sameSongAs(other: TrackInfo): Boolean =
        identityKey().isNotEmpty() && identityKey() == other.identityKey()

    private fun requestAnimatedArtworkIfNeeded(track: TrackInfo) {
        if (!_motionArtworkEnabled.value) return
        val key = listOf(track.title, track.artist, track.album)
            .joinToString("\u0000") { it.trim().lowercase(Locale.ROOT) }
        if (key == animatedArtworkKey) return
        animatedArtworkKey = key
        animatedArtworkJob?.cancel()
        _animatedArtworkUrl.value = null
        _animatedArtworkFullScreenUrl.value = null
        _animatedArtworkCover.value = null
        _animatedArtworkPreview.value = null
        if (track.title.isBlank() || track.artist.isBlank()) return

        animatedArtworkJob = viewModelScope.launch {
            val result = animatedArtworkClient.findSquareMotionArtwork(
                AnimatedArtworkRequest(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                )
            )
            if (animatedArtworkKey != key) return@launch
            _animatedArtworkUrl.value = result?.motionUrl
            _animatedArtworkFullScreenUrl.value = result?.fullScreenMotionUrl

            val staticCover = result?.staticArtworkUrl?.let { remoteArtworkLoader.load(it) }
            if (animatedArtworkKey == key) _animatedArtworkCover.value = staticCover

            val preview = result?.motionPreviewUrl?.let {
                remoteArtworkLoader.load(it, targetEdgePx = 960)
            }
            if (animatedArtworkKey == key) _animatedArtworkPreview.value = preview
        }
    }

    private companion object {
        const val APP_VOLUME_STEP = 5
        // post-commit: show the target until within epsilon, give up after the timeout
        const val SCRUB_SETTLE_POLL_MS = 100L
        const val SCRUB_SETTLE_EPSILON_MS = 2_000L
        const val SCRUB_SETTLE_TIMEOUT_MS = 3_000L
        // raise if the play/pause icon flickers back right after a local toggle
        const val VIDEO_ACTION_SYNC_HOLD_MS = 700L
        const val SERVER_RESTART_DEBOUNCE_MS = 500L
        const val QUEUE_ARTWORK_RADIUS = 2
        const val LOG_HISTORY_LIMIT = 2_000
        const val LOG_PERSIST_DEBOUNCE_MS = 900L
    }
}
