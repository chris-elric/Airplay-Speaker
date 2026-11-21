package io.github.jqssun.airplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.util.UnstableApi
import io.github.jqssun.airplay.MainActivity
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.audio.DacpController
import io.github.jqssun.airplay.audio.DacpPlayer
import io.github.jqssun.airplay.audio.DmapParser
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.artwork.ArtworkDecoder
import io.github.jqssun.airplay.bridge.ClassicRaopBridge
import io.github.jqssun.airplay.bridge.ClassicRaopCallbackHandler
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import io.github.jqssun.airplay.discovery.NsdServiceManager
import io.github.jqssun.airplay.download.VideoDownloader
import io.github.jqssun.airplay.lyrics.AppleMusicLyricsClient
import io.github.jqssun.airplay.lyrics.LyricsSettings
import io.github.jqssun.airplay.lyrics.LyricsRequest
import io.github.jqssun.airplay.lyrics.LyricsState
import io.github.jqssun.airplay.lyrics.LyricsStatus
import io.github.jqssun.airplay.lyrics.withRepairedWordTimings
import io.github.jqssun.airplay.lyrics.withStableDuetAlignment
import io.github.jqssun.airplay.lyrics.wordTimingQualityScore
import io.github.jqssun.airplay.renderer.AirPlayVideoPlayer
import io.github.jqssun.airplay.renderer.AudioRenderer
import io.github.jqssun.airplay.renderer.VideoRenderer
import io.github.jqssun.airplay.viewmodel.DebugInfo
import java.net.NetworkInterface
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class VideoPlaybackInfo(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = true,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val buffering: Boolean = false,
)

internal fun compensateAudioOutputLatency(
    rawPositionMs: Long,
    queuedAudioMs: Long,
): Long = (rawPositionMs - queuedAudioMs.coerceAtLeast(0L)).coerceAtLeast(0L)

internal fun stabilizeReportedProgress(
    predictedPositionMs: Long,
    reportedPositionMs: Long,
): Long {
    val error = reportedPositionMs - predictedPositionMs
    return when {
        abs(error) <= 120L -> predictedPositionMs + error / 5L
        abs(error) <= 600L -> predictedPositionMs + error / 2L
        else -> reportedPositionMs
    }.coerceAtLeast(0L)
}

@androidx.annotation.OptIn(UnstableApi::class)
class AirPlayService : Service(), RaopCallbackHandler, ClassicRaopCallbackHandler {

    private var nativeHandle = 0L
    private var classicHandle = 0L
    @Volatile private var classicConnectionActive = false
    @Volatile private var classicBits = 16
    @Volatile private var classicChannels = 2
    @Volatile private var classicSampleRate = 44100
    private var classicPassword: String? = null
    private var nsdManager: NsdServiceManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    val videoRenderer = VideoRenderer()
    val audioRenderer by lazy { AudioRenderer(applicationContext) }
    val airPlayVideoPlayer by lazy { AirPlayVideoPlayer(this) }
    val videoDownloader by lazy { VideoDownloader(this) }

    // hls urls point at the native httpd, valid only while the session lives
    private val _videoLocation = MutableStateFlow<String?>(null)
    val videoLocation = _videoLocation.asStateFlow()

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState = _serverState.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount = _connectionCount.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect = _videoAspect.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution = _videoResolution.asStateFlow()

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly = _audioOnly.asStateFlow()

    private val _videoPlaybackActive = MutableStateFlow(false)
    val videoPlaybackActive = _videoPlaybackActive.asStateFlow()

    private val _videoPlaybackInfo = MutableStateFlow(VideoPlaybackInfo())
    val videoPlaybackInfo = _videoPlaybackInfo.asStateFlow()

    // bumped per /play so the ui resets transport state on back-to-back plays too
    private val _videoPlaySeq = MutableStateFlow(0L)
    val videoPlaySeq = _videoPlaySeq.asStateFlow()

    private val _videoPlaybackAspect = MutableStateFlow(16f / 9f)
    val videoPlaybackAspect = _videoPlaybackAspect.asStateFlow()

    private val _videoPlaybackSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoPlaybackSize = _videoPlaybackSize.asStateFlow()

    // container/manifest metadata
    private val _videoTitle = MutableStateFlow("")
    val videoTitle = _videoTitle.asStateFlow()

    // recent /playback-info polls with no playback = pending video; polls start ~1s before /play
    @Volatile private var _lastVideoPollAt = 0L
    @Volatile private var _videoPollSuppressed = false

    fun videoSessionPending(): Boolean =
        !_videoPlaybackActive.value && !_audioOnly.value && !_mirroringActive.value &&
            !_videoPollSuppressed &&
            SystemClock.elapsedRealtime() - _lastVideoPollAt < VIDEO_POLL_PENDING_TIMEOUT_MS

    // set once mirroring reports a real size; connectionCount can't tell session kinds apart
    private val _mirroringActive = MutableStateFlow(false)
    val mirroringActive = _mirroringActive.asStateFlow()

    private val _trackInfo = MutableStateFlow(TrackInfo())
    val trackInfo = _trackInfo.asStateFlow()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appleMusicLyricsClient = AppleMusicLyricsClient(
        log = { message -> log(message) },
        settings = {
            val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            LyricsSettings(
                appleMediaUserToken = prefs.getString(Prefs.APPLE_MEDIA_USER_TOKEN, Prefs.DEF_APPLE_MEDIA_USER_TOKEN).orEmpty(),
                musixmatchToken = prefs.getString(Prefs.MUSIXMATCH_TOKEN, Prefs.DEF_MUSIXMATCH_TOKEN).orEmpty(),
                translationEnabled = prefs.getBoolean(Prefs.LYRICS_TRANSLATION, Prefs.DEF_LYRICS_TRANSLATION),
                translationLanguage = prefs.getString(Prefs.LYRICS_TRANSLATION_LANGUAGE, Prefs.DEF_LYRICS_TRANSLATION_LANGUAGE)
                    .orEmpty().ifBlank { Prefs.DEF_LYRICS_TRANSLATION_LANGUAGE },
            )
        },
    )
    private var lastLyricsRequest: LyricsRequest? = null
    private val _lyrics = MutableStateFlow(LyricsState())
    val lyrics = _lyrics.asStateFlow()
    private var lyricsJob: Job? = null
    private var lyricsKey = ""

    private val _positionMs = MutableStateFlow(0L)
    val positionMs = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val _playing = MutableStateFlow(true)
    val playing = _playing.asStateFlow()

    @Volatile private var _progressBaseMs = 0L
    @Volatile private var _progressBaseTime = 0L
    @Volatile private var _lastAudioPacketAtMs = 0L

    fun currentPositionMs(): Long {
        val raw = rawCurrentPositionMs()
        return compensateAudioOutputLatency(
            rawPositionMs = raw,
            queuedAudioMs = audioRenderer.estimatedQueuedAudioMs(),
        ).coerceAtMost(_durationMs.value.coerceAtLeast(0L))
    }

    private fun rawCurrentPositionMs(): Long {
        if (_progressBaseTime == 0L || !_playing.value) return _positionMs.value
        val elapsed = SystemClock.elapsedRealtime() - _progressBaseTime
        return (_progressBaseMs + elapsed).coerceIn(0, _durationMs.value)
    }

    var dacpController: DacpController? = null
        private set
    lateinit var dacpPlayer: DacpPlayer
        private set
    private val _mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var _coverArtBytes: ByteArray? = null
    private var mediaSession: MediaSessionCompat? = null
    private var mediaReceiver: BroadcastReceiver? = null

    var logCallback: ((String) -> Unit)? = null
    var modeCallback: ((Boolean) -> Unit)? = null

    @Volatile private var _lastPin: String? = null
    var pinCallback: ((String?) -> Unit)? = null
        set(value) {
            field = value
            // ui replay only: binding the activity must not mint a new native pin
            value?.invoke(_lastPin)
        }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    inner class LocalBinder : Binder() {
        val service: AirPlayService
            get() = this@AirPlayService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        audioRenderer.setAppVolume(
            prefs.getInt(Prefs.APP_VOLUME, Prefs.DEF_APP_VOLUME)
                .coerceIn(0, 100) / 100f
        )
        audioRenderer.setSpatialEnhancementEnabled(
            prefs.getBoolean(
                Prefs.SPATIAL_ENHANCEMENT,
                Prefs.DEF_SPATIAL_ENHANCEMENT,
            )
        )
        audioRenderer.setKaraokeMode(
            enabled = prefs.getBoolean(
                Prefs.KARAOKE_ENABLED,
                Prefs.DEF_KARAOKE_ENABLED,
            ),
            vocalLevelPercent = prefs.getInt(
                Prefs.KARAOKE_VOCAL_LEVEL,
                Prefs.DEF_KARAOKE_VOCAL_LEVEL,
            ),
        )
        audioRenderer.setAiVocalSeparationEnabled(
            prefs.getBoolean(
                Prefs.AI_VOCAL_SEPARATION,
                Prefs.DEF_AI_VOCAL_SEPARATION,
            )
        )
        createNotificationChannel()
        dacpController = DacpController(this)
        dacpPlayer = DacpPlayer(
            mainLooper,
            dacp = { dacpController },
            snapshot = {
                DacpPlayer.Snapshot(
                    track = _trackInfo.value,
                    artworkData = _coverArtBytes,
                    durationMs = _durationMs.value,
                    playing = _playing.value,
                    active = _audioOnly.value && _connectionCount.value > 0,
                )
            },
            positionMs = ::currentPositionMs,
            setPlaying = ::_setPlaying,
        )
        serviceScope.launch {
            while (true) {
                delay(AUDIO_PAUSE_WATCHDOG_INTERVAL_MS)
                val silenceMs = SystemClock.elapsedRealtime() - _lastAudioPacketAtMs
                if (_audioOnly.value && _playing.value &&
                    _lastAudioPacketAtMs > 0L && silenceMs >= AUDIO_PAUSE_SILENCE_MS
                ) {
                    // A sender pause often stops RAOP packets without a
                    // dedicated pause callback. Freeze the UI transport at
                    // the same point as the receiver's audio stream.
                    _setPlaying(false)
                }
            }
        }
        mediaSession = MediaSessionCompat(this, "AirPlay").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (_videoPlaybackActive.value) {
                        airPlayVideoPlayer.setPlaying(true)
                        return
                    }
                    _setPlaying(true)
                    dacpController?.play()
                }
                override fun onPause() {
                    if (_videoPlaybackActive.value) {
                        airPlayVideoPlayer.setPlaying(false)
                        return
                    }
                    _setPlaying(false)
                    dacpController?.pause()
                }
                override fun onStop() {
                    if (_videoPlaybackActive.value) stopVideoPlayback()
                }
                override fun onFastForward() {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.seekBy(VIDEO_SEEK_STEP_MS)
                }
                override fun onRewind() {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.seekBy(-VIDEO_SEEK_STEP_MS)
                }
                override fun onSeekTo(pos: Long) {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.scrub(pos / 1000f)
                }
                override fun onSkipToNext() {
                    if (!_videoPlaybackActive.value) dacpController?.nextItem()
                }
                override fun onSkipToPrevious() {
                    if (!_videoPlaybackActive.value) dacpController?.prevItem()
                }
            })
        }
        mediaReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PLAY_PAUSE -> togglePlayPause()
                    ACTION_NEXT -> dacpController?.nextItem()
                    ACTION_PREV -> dacpController?.prevItem()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
        }
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        airPlayVideoPlayer.onVideoSize = { width, height, aspect ->
            _videoPlaybackAspect.value = aspect
            _videoPlaybackSize.value = width to height
        }
        airPlayVideoPlayer.onTitle = { _videoTitle.value = it ?: "" }
        airPlayVideoPlayer.onEnded = { _endVideoPlayback("AirPlay Video stopped (player)") }
        airPlayVideoPlayer.onPlaybackInfo = { snapshot ->
            if (nativeHandle != 0L) {
                NativeBridge.nativeUpdatePlaybackInfo(nativeHandle, snapshot.position, snapshot.duration, snapshot.rate, snapshot.ready)
            }
            if (_videoPlaybackActive.value) {
                _updateVideoPlaybackState(snapshot.position, snapshot.rate)
                _videoPlaybackInfo.value = VideoPlaybackInfo(
                    positionMs = (snapshot.position * 1000).toLong(),
                    durationMs = if (snapshot.duration > 0f) (snapshot.duration * 1000).toLong() else 0L,
                    playing = snapshot.playWhenReady,
                    speed = snapshot.speed,
                    skipSilence = snapshot.skipSilence,
                    buffering = snapshot.buffering,
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val runInBackground = prefs.getBoolean(Prefs.RUN_IN_BACKGROUND, Prefs.DEF_RUN_IN_BACKGROUND)
        val shouldStart = intent?.action == ACTION_START_SERVER ||
            (intent == null && runInBackground)
        if (shouldStart && _serverState.value != ServerState.RUNNING) {
            promoteToForeground()
            val name = prefs.getString(Prefs.SERVER_NAME, Prefs.DEF_SERVER_NAME) ?: Prefs.DEF_SERVER_NAME
            startServer(name, ensureServiceStarted = false)
            if (_serverState.value != ServerState.RUNNING) stopSelf(startId)
        }
        return if (runInBackground && _serverState.value == ServerState.RUNNING) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    fun startServer(name: String) {
        startServer(name, ensureServiceStarted = true)
    }

    private fun startServer(name: String, ensureServiceStarted: Boolean) {
        if (_serverState.value == ServerState.RUNNING) return
        val effectiveName = name.ifBlank { Prefs.DEF_SERVER_NAME }

        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:server").apply { acquire() }

        nsdManager = NsdServiceManager(this).apply { acquireMulticastLock() }

        val hwAddr = getHwAddr()
        val keyFile = filesDir.resolve("airplay.pem").absolutePath
        val nohold = prefs.getBoolean(Prefs.ALLOW_NEW_CONN, Prefs.DEF_ALLOW_NEW_CONN)
        val requirePin = prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN)
        val advertiseVideo = prefs.getBoolean(Prefs.ADVERTISE_VIDEO, Prefs.DEF_ADVERTISE_VIDEO)
        val advertiseAudio = prefs.getBoolean(Prefs.ADVERTISE_AUDIO, Prefs.DEF_ADVERTISE_AUDIO)
        val requestedPort = prefs.getInt(Prefs.SERVER_PORT, Prefs.DEF_SERVER_PORT).coerceIn(1, 65535)

        // macOS Music uses the classic RAOP speaker route. UxPlay's Apple TV
        // compatibility route works for system audio and mirroring, but Music
        // rejects it before opening a session. In the default music-only mode,
        // run the dedicated classic receiver instead.
        if (advertiseAudio && !advertiseVideo) {
            audioRenderer.audioBufferMultiplier = prefs.getInt(
                Prefs.AUDIO_BUFFER_MULTIPLIER,
                Prefs.DEF_AUDIO_BUFFER_MULTIPLIER,
            )
            audioRenderer.losslessOutput = prefs.getBoolean(
                Prefs.LOSSLESS_OUTPUT,
                Prefs.DEF_LOSSLESS_OUTPUT,
            )
            val pemKey = resources.openRawResource(R.raw.airport_key)
                .bufferedReader()
                .use { it.readText() }
            classicHandle = ClassicRaopBridge.nativeCreate(this, pemKey)
            if (classicHandle == 0L) {
                log("Classic RAOP init failed")
                _failStart()
                return
            }

            classicPassword = if (requirePin) {
                "%04d".format(java.security.SecureRandom().nextInt(10_000))
            } else {
                null
            }
            val port = ClassicRaopBridge.nativeStart(
                classicHandle,
                requestedPort,
                hwAddr,
                classicPassword,
            )
            if (port < 0) {
                log("Classic RAOP failed to start on port $requestedPort")
                _failStart()
                return
            }

            val raopName = hwAddr.joinToString("") { "%02X".format(it.toInt() and 0xff) } +
                "@$effectiveName"
            nsdManager?.registerRaop(raopName, port, classicSpeakerTxt(requirePin))
            classicPassword?.let(::onDisplayPin)

            _serverState.value = ServerState.RUNNING
            if (ensureServiceStarted) {
                ContextCompat.startForegroundService(this, Intent(this, AirPlayService::class.java))
            }
            promoteToForeground()
            log("Discovery mode: classic RAOP music speaker")
            log("CD-quality receiver started on port $port")
            return
        }

        nativeHandle = NativeBridge.nativeInit(this, hwAddr, effectiveName, keyFile, nohold, requirePin)
        if (nativeHandle == 0L) {
            log("Native init failed")
            _failStart()
            return
        }

        // apply settings from preferences
        val maxFps = prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS)
        val overscanned = prefs.getBoolean(Prefs.OVERSCANNED, Prefs.DEF_OVERSCANNED)
        val audioLatencyMs = prefs.getInt(Prefs.AUDIO_LATENCY_MS, Prefs.DEF_AUDIO_LATENCY_MS)
        val h265 = prefs.getBoolean(Prefs.H265_ENABLED, Prefs.DEF_H265_ENABLED)
        val alac = prefs.getBoolean(Prefs.ALAC_ENABLED, Prefs.DEF_ALAC_ENABLED)
        val aac = prefs.getBoolean(Prefs.AAC_ENABLED, Prefs.DEF_AAC_ENABLED)

        audioRenderer.swAlacEnabled = prefs.getBoolean(Prefs.SW_ALAC_ENABLED, Prefs.DEF_SW_ALAC_ENABLED)
        audioRenderer.audioBufferMultiplier = prefs.getInt(Prefs.AUDIO_BUFFER_MULTIPLIER, Prefs.DEF_AUDIO_BUFFER_MULTIPLIER)
        audioRenderer.losslessOutput = prefs.getBoolean(Prefs.LOSSLESS_OUTPUT, Prefs.DEF_LOSSLESS_OUTPUT)
        audioRenderer.setSpatialEnhancementEnabled(
            prefs.getBoolean(
                Prefs.SPATIAL_ENHANCEMENT,
                Prefs.DEF_SPATIAL_ENHANCEMENT,
            )
        )
        audioRenderer.setKaraokeMode(
            enabled = prefs.getBoolean(
                Prefs.KARAOKE_ENABLED,
                Prefs.DEF_KARAOKE_ENABLED,
            ),
            vocalLevelPercent = prefs.getInt(
                Prefs.KARAOKE_VOCAL_LEVEL,
                Prefs.DEF_KARAOKE_VOCAL_LEVEL,
            ),
        )
        audioRenderer.setAiVocalSeparationEnabled(
            prefs.getBoolean(
                Prefs.AI_VOCAL_SEPARATION,
                Prefs.DEF_AI_VOCAL_SEPARATION,
            )
        )
        videoRenderer.enforceSdr = prefs.getBoolean(Prefs.ENFORCE_SDR, Prefs.DEF_ENFORCE_SDR)
        videoRenderer.keyAllowFrameDrop = prefs.getBoolean(Prefs.KEY_ALLOW_FRAME_DROP, Prefs.DEF_KEY_ALLOW_FRAME_DROP)
        val realtimePriority = prefs.getBoolean(Prefs.KEY_PRIORITY, Prefs.DEF_KEY_PRIORITY)
        videoRenderer.realtimeDecoderPriority = realtimePriority
        videoRenderer.operatingRateHint = prefs.getBoolean(Prefs.KEY_OPERATING_RATE, Prefs.DEF_KEY_OPERATING_RATE)
        videoRenderer.benchmarkLog = prefs.getBoolean(Prefs.BENCHMARK_LOG, Prefs.DEF_BENCHMARK_LOG)
        videoRenderer.benchmarkLogCallback = { msg -> logCallback?.invoke(msg) }
        videoRenderer.scheduledOutputBufferRelease = prefs.getBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, Prefs.DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE)
        audioRenderer.realtimeDecoderPriority = realtimePriority
        NativeBridge.nativeSetH265Enabled(nativeHandle, h265)
        NativeBridge.nativeSetCodecs(nativeHandle, alac, aac)
        NativeBridge.nativeSetHlsEnabled(nativeHandle, advertiseVideo)
        NativeBridge.nativeSetAudioEnabled(nativeHandle, advertiseAudio)
        NativeBridge.nativeSetPlist(nativeHandle, "maxFPS", maxFps)
        NativeBridge.nativeSetPlist(nativeHandle, "overscanned", if (overscanned) 1 else 0)
        if (audioLatencyMs >= 0) {
            NativeBridge.nativeSetPlist(nativeHandle, "audio_delay_micros", audioLatencyMs * 1000)
        }

        // set display params
        val dm = resources.displayMetrics
        val res = prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION)!!
        val (w, h) = if (res != "auto" && res.contains("x")) {
            val parts = res.split("x")
            parts[0].toInt() to parts[1].toInt()
        } else {
            dm.widthPixels to dm.heightPixels
        }
        videoRenderer.setResolution(w, h)
        _videoResolution.value = "${w}x${h}"
        _videoAspect.value = w.toFloat() / h
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, maxFps)

        val port = NativeBridge.nativeStart(nativeHandle, requestedPort)
        if (port < 0) {
            log("Failed to start on port $requestedPort")
            _failStart()
            return
        }

        // register mdns services
        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle) ?: emptyMap()
        val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(nativeHandle) ?: emptyMap()
        val raopName = NativeBridge.nativeGetRaopServiceName(nativeHandle) ?: "AirPlay"
        val resolvedName = NativeBridge.nativeGetServerName(nativeHandle) ?: effectiveName

        if (advertiseAudio) {
            nsdManager?.registerRaop(raopName, port, raopTxt)
        }
        // Music-only builds must appear as a RAOP speaker, not as an Apple TV.
        // Advertising _airplay._tcp without the complete AirPlay 2 remote-control
        // stack makes recent Music clients choose an unsupported connection path.
        if (advertiseVideo) {
            nsdManager?.registerAirplay(resolvedName, port, airplayTxt)
            log("Discovery mode: RAOP speaker + AirPlay display")
        } else {
            log("Discovery mode: RAOP music speaker")
        }

        _serverState.value = ServerState.RUNNING
        if (ensureServiceStarted) {
            ContextCompat.startForegroundService(this, Intent(this, AirPlayService::class.java))
        }
        promoteToForeground()
        log("Server started on port $port")
    }

    private fun classicSpeakerTxt(passwordRequired: Boolean): Map<String, String> = linkedMapOf(
        // This profile follows a classic AirPort/Shairport speaker. Do not add
        // vv/ft/pk here: those select the incompatible Apple TV session path.
        "sf" to "0x4",
        "fv" to "76400.10",
        "am" to "AirPort10,115",
        "vs" to "105.1",
        "tp" to "TCP,UDP",
        "vn" to "65537",
        "md" to "0,1,2",
        "ss" to "16",
        "sr" to "44100",
        "da" to "true",
        "sv" to "false",
        "et" to "0,1",
        "ek" to "1",
        "cn" to "0,1",
        "ch" to "2",
        "txtvers" to "1",
        "pw" to passwordRequired.toString(),
    )

    private fun _failStart() {
        if (classicHandle != 0L) {
            ClassicRaopBridge.nativeDestroy(classicHandle)
            classicHandle = 0L
        }
        classicPassword = null
        if (nativeHandle != 0L) {
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        _serverState.value = ServerState.ERROR
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    fun stopServer() {
        if (classicHandle != 0L) {
            ClassicRaopBridge.nativeStop(classicHandle)
            ClassicRaopBridge.nativeDestroy(classicHandle)
            classicHandle = 0L
        }
        classicConnectionActive = false
        classicPassword = null
        if (nativeHandle != 0L) {
            NativeBridge.nativeStop(nativeHandle)
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        dacpController?.release()
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        videoRenderer.release()
        audioRenderer.release()
        airPlayVideoPlayer.stop()
        _audioOnly.value = false
        _videoPlaybackActive.value = false
        _mirroringActive.value = false
        refreshMediaSessionActivity()
        _videoPlaybackInfo.value = VideoPlaybackInfo()
        _lastVideoPollAt = 0
        _videoPollSuppressed = false
        _coverArtBytes = null
        _trackInfo.value = TrackInfo()
        _resetLyrics()
        _positionMs.value = 0
        _durationMs.value = 0
        _lastAudioPacketAtMs = 0L
        _serverState.value = ServerState.STOPPED
        _connectionCount.value = 0
        _refreshDacpPlayer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
        log("Server stopped")
    }

    fun setVideoSurface(surface: Surface) {
        videoRenderer.setSurface(surface)
    }

    fun clearVideoSurface(surface: Surface) {
        videoRenderer.clearSurface(surface)
    }

    fun setVideoPlaybackSurface(surface: Surface) {
        airPlayVideoPlayer.setSurface(surface)
    }

    fun clearVideoPlaybackSurface(surface: Surface) {
        airPlayVideoPlayer.clearSurface(surface)
    }

    fun setVideoPlaying(playing: Boolean) {
        airPlayVideoPlayer.setPlaying(playing)
    }

    fun seekVideoTo(positionMs: Long) {
        airPlayVideoPlayer.scrub(positionMs / 1000f)
    }

    fun setVideoScrubbing(enabled: Boolean) {
        airPlayVideoPlayer.setScrubbing(enabled)
    }

    fun setVideoSpeed(speed: Float) {
        airPlayVideoPlayer.setSpeed(speed)
    }

    fun setVideoSkipSilence(enabled: Boolean) {
        airPlayVideoPlayer.setSkipSilence(enabled)
    }

    fun seekVideoBy(deltaMs: Long) {
        airPlayVideoPlayer.seekBy(deltaMs)
    }

    fun stopVideoPlayback() = _endVideoPlayback("AirPlay Video stopped (local)")

    fun downloadVideo() {
        _videoLocation.value?.let { videoDownloader.start(it) }
    }

    private fun _endVideoPlayback(message: String) {
        if (!_videoPlaybackActive.value) return
        // lingering polls after a stop must not bounce the UI back to a pending session
        _videoPollSuppressed = true
        _videoPlaybackActive.value = false
        airPlayVideoPlayer.stop()
        refreshMediaSessionActivity()
        log(message)
    }

    override fun onDestroy() {
        stopServer()
        serviceScope.cancel()
        dacpPlayer.release()
        mediaReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        mediaReceiver = null
        dacpController?.release()
        dacpController = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    // ClassicRaopCallbackHandler (called from native threads)

    override fun onClassicAudioStart(bits: Int, channels: Int, sampleRate: Int) {
        classicBits = bits
        classicChannels = channels
        classicSampleRate = sampleRate
        clearPin()
        if (!classicConnectionActive) {
            val firstConnection = _connectionCount.value == 0
            classicConnectionActive = true
            onConnectionInit()
            // The classic password has already been accepted when the audio
            // session starts, so it is now safe to bring the TV UI forward.
            if (firstConnection && requiresPin() && shouldLaunchOnConnect()) {
                launchMainActivity()
            }
        }
        onAudioOnly(true)
        _lastAudioPacketAtMs = SystemClock.elapsedRealtime()
        _playing.value = true
        log("Classic ALAC audio: $bits-bit, $channels ch, $sampleRate Hz")
    }

    override fun onClassicPcm(data: ByteArray) {
        _markAudioPacketReceived()
        audioRenderer.feedPcm(data, classicBits, classicChannels, classicSampleRate)
    }

    override fun onClassicAudioStop() {
        audioRenderer.markSessionEnded()
        if (classicConnectionActive) {
            classicConnectionActive = false
            onConnectionDestroy()
        }
    }

    override fun onClassicFlush() {
        audioRenderer.flushPcm()
        log("Classic RAOP buffer flushed")
    }

    override fun onClassicVolume(volume: Float) = onVolumeChange(volume)

    override fun onClassicMetadata(data: ByteArray) = onMetadata(data)

    override fun onClassicCoverArt(data: ByteArray) = onCoverArt(data)

    override fun onClassicProgress(start: Long, current: Long, end: Long) {
        onProgress(start, current, end)
    }

    override fun onClassicDacp(dacpId: String, activeRemote: String) {
        onDacpId(dacpId, activeRemote)
    }

    override fun onClassicLog(message: String) {
        log("Classic RAOP: $message")
    }

    // RaopCallbackHandler (called from native threads)

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onAudioData(data: ByteArray, ct: Int, ntpTimeNs: Long, seqNum: Int) {
        _markAudioPacketReceived()
        audioRenderer.feedAudio(data, ct, ntpTimeNs)
    }

    override fun onVideoSessionPoll() {
        _lastVideoPollAt = SystemClock.elapsedRealtime()
    }

    override fun onVideoPlay(location: String, startPositionSeconds: Float) {
        _videoLocation.value = location
        _videoPlaySeq.value++
        _videoPollSuppressed = false
        _videoPlaybackInfo.value = VideoPlaybackInfo(positionMs = (startPositionSeconds * 1000).toLong())
        _videoPlaybackAspect.value = 16f / 9f
        _videoPlaybackSize.value = null
        _videoTitle.value = ""
        _videoPlaybackActive.value = true
        airPlayVideoPlayer.play(location, startPositionSeconds)
        // claim media-button routing for keys that arrive as media-session events
        refreshMediaSessionActivity()
        log("AirPlay Video play: $location @ ${startPositionSeconds}s")
    }

    override fun onVideoScrub(positionSeconds: Float) {
        airPlayVideoPlayer.scrub(positionSeconds)
    }

    override fun onVideoRate(rate: Float) {
        airPlayVideoPlayer.setRate(rate)
    }

    override fun onVideoStop() = _endVideoPlayback("AirPlay Video stopped")

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        clearPin()
        if (!usingScreen && !_audioOnly.value) {
            // pure music streaming (not screen mirroring audio)
            onAudioOnly(true)
        }
        if (!usingScreen) _lastAudioPacketAtMs = SystemClock.elapsedRealtime()
        log("Audio format: ct=$ct spf=$spf screen=$usingScreen")
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        clearPin()
        if (w > 0 && h > 0) {
            _videoAspect.value = w / h
            _videoResolution.value = "${w.toInt()}x${h.toInt()}"
            videoRenderer.setResolution(w.toInt(), h.toInt())
            _mirroringActive.value = true
        }
        log("Video size: ${srcW}x${srcH} -> ${w}x${h}")
    }

    override fun onVolumeChange(volume: Float) {
        audioRenderer.setVolume(volume)
    }

    fun setAppVolume(percent: Int) {
        val value = percent.coerceIn(0, 100)
        audioRenderer.setAppVolume(value / 100f)
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(Prefs.APP_VOLUME, value)
            .apply()
    }

    fun appVolumePercent(): Int = (audioRenderer.appVolume * 100f).toInt().coerceIn(0, 100)

    fun setSpatialEnhancementEnabled(enabled: Boolean) {
        audioRenderer.setSpatialEnhancementEnabled(enabled)
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Prefs.SPATIAL_ENHANCEMENT, enabled)
            .apply()
    }

    fun setKaraokeMode(enabled: Boolean, vocalLevelPercent: Int) {
        val vocalLevel = vocalLevelPercent.coerceIn(0, 100)
        audioRenderer.setKaraokeMode(enabled, vocalLevel)
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Prefs.KARAOKE_ENABLED, enabled)
            .putInt(Prefs.KARAOKE_VOCAL_LEVEL, vocalLevel)
            .apply()
    }

    fun setAiVocalSeparationEnabled(enabled: Boolean) {
        audioRenderer.setAiVocalSeparationEnabled(enabled)
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Prefs.AI_VOCAL_SEPARATION, enabled)
            .apply()
    }

    private fun refreshMediaSessionActivity() {
        mediaSession?.isActive = _audioOnly.value || _videoPlaybackActive.value
    }

    override fun onConnectionInit() {
        val firstConnection = _connectionCount.value == 0
        _connectionCount.value++
        log("Client connected (${_connectionCount.value})")
        if (!firstConnection) return
        // conn_init is only a tcp pre-auth signal. pin-required sessions must wait for
        // onDisplayPin, otherwise the server ui can move before the client pin is current
        if (requiresPin()) return
        if (!shouldLaunchOnConnect()) return
        launchMainActivity()
    }

    override fun onConnectionDestroy() {
        _connectionCount.value = (_connectionCount.value - 1).coerceAtLeast(0)
        if (_connectionCount.value == 0) {
            // clients may drop without POST /stop; must run before the poll-state reset
            _endVideoPlayback("AirPlay Video stopped (disconnect)")
            _audioOnly.value = false
            _mirroringActive.value = false
            _lastVideoPollAt = 0
            _videoPollSuppressed = false
            _coverArtBytes = null
            _trackInfo.value = TrackInfo()
            _resetLyrics()
            _positionMs.value = 0
            _durationMs.value = 0
            _lastAudioPacketAtMs = 0L
            refreshMediaSessionActivity()
            audioRenderer.markSessionEnded()
            _refreshDacpPlayer()
        }
        log("Client disconnected (${_connectionCount.value})")
    }

    override fun onConnectionReset(reason: Int) {
        audioRenderer.markSessionEnded()
        log("Connection reset: $reason")
    }

    override fun onDisplayPin(pin: String) {
        // a new pin is the sync point with the client prompt: show every new value immediately
        if (_lastPin == pin) return
        _lastPin = pin
        pinCallback?.invoke(pin)
        _updateMediaNotification()
    }

    override fun onMetadata(data: ByteArray) {
        val map = DmapParser.parse(data)
        val previous = _trackInfo.value
        val incoming = TrackInfo.fromDmap(map)
        val isNewTrack = previous.title != incoming.title ||
            previous.artist != incoming.artist ||
            previous.album != incoming.album
        // Do not show the prior song's sleeve while waiting for this track's
        // artwork callback. A missing sleeve intentionally falls back to the
        // neutral music-note card in the TV surface.
        val info = incoming.copy(coverArt = if (isNewTrack) null else previous.coverArt)
        if (isNewTrack) {
            _coverArtBytes = null
            _resetLyrics()
        }
        _trackInfo.value = info
        if (isNewTrack) {
            // Apple Music usually updates its DACP queue just after the DMAP
            // metadata packet. Ask immediately, then once more after that
            // short hand-off so Previous / Up Next does not show stale rows.
            dacpController?.refreshQueue(info)
            serviceScope.launch {
                delay(900L)
                val current = _trackInfo.value
                if (current.title == info.title &&
                    current.artist == info.artist &&
                    current.album == info.album
                ) {
                    dacpController?.refreshQueue(current)
                }
            }
        }
        if (info.durationMs > 0) _durationMs.value = info.durationMs
        _requestLyricsIfReady()
        _updateMediaMetadata()
        _refreshDacpPlayer()
        log("Track: ${info.artist} - ${info.title}")
    }

    override fun onCoverArt(data: ByteArray) {
        val bmp = ArtworkDecoder.decode(data) ?: run {
            log("Ignored oversized or invalid cover art (${data.size} bytes)")
            return
        }
        // Metadata clients can use compact artwork data. Avoid retaining an
        // additional multi-megabyte original in memory on constrained TVs.
        _coverArtBytes = data.takeIf { it.size <= MAX_RETAINED_COVER_BYTES }
        _trackInfo.value = _trackInfo.value.copy(coverArt = bmp)
        _updateMediaMetadata()
        _refreshDacpPlayer()
    }

    override fun onProgress(start: Long, curr: Long, end: Long) {
        val rate = 44100.0
        val posMs = ((curr - start) / rate * 1000).toLong().coerceAtLeast(0)
        val durMs = ((end - start) / rate * 1000).toLong().coerceAtLeast(0)
        // pause/resume transitions emit degenerate progress; keep the last good value
        if (durMs <= 0) return
        val stablePosition = if (_progressBaseTime == 0L) {
            posMs
        } else {
            stabilizeReportedProgress(rawCurrentPositionMs(), posMs)
        }
        _positionMs.value = stablePosition
        _durationMs.value = durMs
        _progressBaseMs = stablePosition
        _progressBaseTime = SystemClock.elapsedRealtime()
        _requestLyricsIfReady()
        _updatePlaybackState()
        _refreshDacpPlayer()
    }

    override fun onDacpId(dacpId: String, activeRemote: String) {
        dacpController?.update(dacpId, activeRemote)
        log("DACP: $dacpId")
    }

    override fun onAudioOnly(audioOnly: Boolean) {
        val prev = _audioOnly.value
        _audioOnly.value = audioOnly
        refreshMediaSessionActivity()
        _refreshDacpPlayer()
        if (audioOnly && !prev) {
            modeCallback?.invoke(true)
            log("Audio mode")
        } else if (!audioOnly && prev) {
            _coverArtBytes = null
            _trackInfo.value = TrackInfo()
            _resetLyrics()
            _positionMs.value = 0
            _durationMs.value = 0
            modeCallback?.invoke(false)
            log("Mirror mode")
        }
    }

    private fun _updateMediaMetadata() {
        val info = _trackInfo.value
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, info.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, _durationMs.value)
        info.coverArt?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        mediaSession?.setMetadata(builder.build())
        _updateMediaNotification()
    }

    @Synchronized
    private fun _resetLyrics() {
        lyricsJob?.cancel()
        lyricsJob = null
        lyricsKey = ""
        _lyrics.value = LyricsState()
    }

    @Synchronized
    private fun _requestLyricsIfReady() {
        val info = _trackInfo.value
        val duration = _durationMs.value.takeIf { it > 0 } ?: info.durationMs
        if (info.title.isBlank() || info.artist.isBlank() || duration <= 0) return

        val roundedDuration = (duration + 500) / 1000
        val key = listOf(info.title, info.artist, info.album, roundedDuration.toString())
            .joinToString("|") { it.trim().lowercase() }
        if (key == lyricsKey && _lyrics.value.status != LyricsStatus.IDLE) return

        lyricsJob?.cancel()
        lyricsKey = key
        _lyrics.value = LyricsState(LyricsStatus.LOADING)
        log("Lyrics: searching Apple Music for ${info.artist} - ${info.title}")
        // Parsing and timeline repair stay off Main so a large Apple response
        // cannot interrupt playback or remote input on a low-memory TV.
        lyricsJob = serviceScope.launch(Dispatchers.Default) {
            val request = LyricsRequest(
                title = info.title,
                artist = info.artist,
                album = info.album,
                durationMs = duration,
            )
            val appleResult = withTimeoutOrNull(LYRICS_SEARCH_WINDOW_MS) {
                appleMusicLyricsClient.find(request)
            }
            val finalResult = (appleResult ?: LyricsState(LyricsStatus.NOT_FOUND))
                // Apple line-synced lyrics remain line-synced. Do not invent
                // word timings when the source does not publish them.
                .withRepairedWordTimings(trackDurationMs = duration)
                .withStableDuetAlignment()
            log(
                "Lyrics: result ${finalResult.status} " +
                    "(mode=${finalResult.wordSyncMode}, " +
                    "${finalResult.lines.size} timed lines, " +
                    "score=${finalResult.wordTimingQualityScore(duration)})"
            )
            synchronized(this@AirPlayService) {
                if (lyricsKey == key) {
                    _lyrics.value = finalResult
                    lastLyricsRequest = request
                }
            }
            // Translation arrives a moment later so the original lyrics never wait for it.
            if (finalResult.lines.isNotEmpty()) {
                val translated = appleMusicLyricsClient.withTranslation(finalResult, request)
                if (translated !== finalResult) {
                    synchronized(this@AirPlayService) {
                        if (lyricsKey == key) _lyrics.value = translated
                    }
                }
            }
        }
    }

    /** Called when the translation setting changes, so the current song updates immediately. */
    fun refreshLyricTranslation() {
        val request = lastLyricsRequest ?: return
        val key = lyricsKey
        val current = _lyrics.value
        if (current.lines.isEmpty()) return
        serviceScope.launch(Dispatchers.Default) {
            val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(Prefs.LYRICS_TRANSLATION, Prefs.DEF_LYRICS_TRANSLATION)
            val base = if (enabled) current else current.copy(lines = current.lines.map { it.copy(translation = "") })
            val updated = if (enabled) appleMusicLyricsClient.withTranslation(base.copy(lines = base.lines.map { it.copy(translation = "") }), request) else base
            synchronized(this@AirPlayService) {
                if (lyricsKey == key) _lyrics.value = updated
            }
        }
    }

    fun togglePlayPause() {
        val nowPlaying = !_playing.value
        _setPlaying(nowPlaying)
        dacpController?.let { if (nowPlaying) it.play() else it.pause() }
    }

    private fun _setPlaying(playing: Boolean) {
        _playing.value = playing
        _refreshDacpPlayer()
        if (playing) {
            // resume extrapolation from current position
            _progressBaseMs = _positionMs.value
            _progressBaseTime = SystemClock.elapsedRealtime()
        } else {
            // freeze position
            _positionMs.value = currentPositionMs()
            _progressBaseTime = 0
        }
        _updatePlaybackState()
    }

    private fun _markAudioPacketReceived() {
        _lastAudioPacketAtMs = SystemClock.elapsedRealtime()
        if (_audioOnly.value && !_playing.value) {
            _setPlaying(true)
        }
    }

    private fun _updatePlaybackState() {
        // the sender's silent raop audio session must not overwrite video session state
        if (_videoPlaybackActive.value) return
        val isPlaying = _playing.value
        val pbState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val speed = if (isPlaying) 1f else 0f
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(pbState, _positionMs.value, speed, SystemClock.elapsedRealtime())
            .build()
        mediaSession?.setPlaybackState(state)
        _updateMediaNotification()
    }

    // consumers extrapolate position from (position, speed, updateTime): push only discontinuities
    private fun _updateVideoPlaybackState(positionSeconds: Float, rate: Float) {
        val playing = rate > 0f
        val posMs = (positionSeconds * 1000).toLong()
        val now = SystemClock.elapsedRealtime()
        val expectedMs = _lastVideoStatePosMs +
            if (_lastVideoStateRate > 0f) ((now - _lastVideoStateAtMs) * _lastVideoStateRate).toLong() else 0L
        if (rate == _lastVideoStateRate && abs(posMs - expectedMs) < 1000) return
        _lastVideoStateRate = rate
        _lastVideoStatePosMs = posMs
        _lastVideoStateAtMs = now
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                posMs,
                rate,
                now
            )
            .build()
        mediaSession?.setPlaybackState(state)
    }

    private var _lastVideoStateRate = -1f
    private var _lastVideoStatePosMs = 0L
    private var _lastVideoStateAtMs = 0L

    private fun _refreshDacpPlayer() {
        _mainHandler.post { dacpPlayer.refresh() }
    }

    private fun clearPin() {
        _lastPin = null
        pinCallback?.invoke(null)
        _updateMediaNotification()
    }

    fun collectDebugInfo() = DebugInfo(
        videoCodec = videoRenderer.codecName,
        videoRes = _videoResolution.value,
        videoFps = videoRenderer.fps,
        videoBitrate = videoRenderer.bitrateBps,
        videoFrames = videoRenderer.frameCount,
        droppedFrames = videoRenderer.droppedFrames,
        framePacingJitterUs = videoRenderer.framePacingJitterUs,
        audioCodec = listOf(
            audioRenderer.codecLabel,
            audioRenderer.outputPathLabel,
        ).filter { it.isNotBlank() }.joinToString(" · "),
        audioVolume = (audioRenderer.appVolume * 100).toInt(),
        connections = _connectionCount.value,
    )

    // helpers

    private fun getHwAddr(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    val mac = iface.hardwareAddress
                    if (mac != null && mac.size == 6) return mac
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get hardware address", e)
        }
        // fallback: random-ish address
        return byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return _buildMediaNotification()
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        foregroundStarted = true
    }

    private fun requiresPin(): Boolean {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN)
    }

    private fun shouldLaunchOnConnect(): Boolean {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(Prefs.LAUNCH_ON_CONNECT, Prefs.DEF_LAUNCH_ON_CONNECT)
    }

    private fun _buildMediaNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val info = _trackInfo.value
        val isAudio = _audioOnly.value && info.title.isNotEmpty()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (isAudio) {
            builder.setContentTitle(info.title).setContentText(info.artist).setSubText(info.album)
            info.coverArt?.let { builder.setLargeIcon(it) }
            mediaSession?.sessionToken?.let { token ->
                builder.setStyle(
                    MediaNotificationCompat.MediaStyle()
                        .setMediaSession(token)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                // transport action buttons
                builder.addAction(android.R.drawable.ic_media_previous, "Prev", _mediaAction(ACTION_PREV))
                builder.addAction(
                    if (_playing.value) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (_playing.value) "Pause" else "Play",
                    _mediaAction(ACTION_PLAY_PAUSE)
                )
                builder.addAction(android.R.drawable.ic_media_next, "Next", _mediaAction(ACTION_NEXT))
            }
        } else {
            if (_lastPin != null) {
                // passive handoff only: do not launch/reorder the activity during pin auth
                builder.setContentTitle(getString(R.string.notification_pin_title))
                    .setContentText(getString(R.string.notification_pin_text, _lastPin))
            } else {
                builder.setContentTitle(getString(R.string.notification_title))
                    .setContentText(getString(R.string.notification_text))
            }
        }
        return builder.build()
    }

    private fun launchMainActivity() {
        Handler(Looper.getMainLooper()).post {
            val launchIntent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch activity", e)
            }
        }
    }

    private fun _mediaAction(action: String): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun _updateMediaNotification() {
        if (_serverState.value != ServerState.RUNNING) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, _buildMediaNotification())
    }

    enum class ServerState {
        STOPPED,
        RUNNING,
        ERROR
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL_ID = "airplay_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "io.github.jqssun.airplay.PLAY_PAUSE"
        const val ACTION_NEXT = "io.github.jqssun.airplay.NEXT"
        const val ACTION_PREV = "io.github.jqssun.airplay.PREV"
        const val ACTION_START_SERVER = "io.github.jqssun.airplay.START_SERVER"
        // shared with dpad/double-tap seeks
        const val VIDEO_SEEK_STEP_MS = 10_000L
        const val VIDEO_POLL_PENDING_TIMEOUT_MS = 3_000L
        private const val AUDIO_PAUSE_WATCHDOG_INTERVAL_MS = 250L
        private const val AUDIO_PAUSE_SILENCE_MS = 1_800L
        // Must stay above AppleMusicLyricsClient's own lookup timeout (46 s).
        private const val LYRICS_SEARCH_WINDOW_MS = 55_000L
        private const val MAX_RETAINED_COVER_BYTES = 2 * 1024 * 1024
    }
}
