@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.jqssun.airplay.ui

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.RadialGradient as AndroidRadialGradient
import android.graphics.Shader as AndroidShader
import android.view.TextureView
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.lyrics.LyricAlignment
import io.github.jqssun.airplay.lyrics.LyricHighlightGraphemeRange
import io.github.jqssun.airplay.lyrics.LyricLine
import io.github.jqssun.airplay.lyrics.LyricsState
import io.github.jqssun.airplay.lyrics.LyricsStatus
import io.github.jqssun.airplay.lyrics.combinedDisplayText
import io.github.jqssun.airplay.lyrics.lyricWordProgress
import io.github.jqssun.airplay.lyrics.lyricWordsMatchLine
import io.github.jqssun.airplay.lyrics.lyricHighlightGraphemeRange
import io.github.jqssun.airplay.lyrics.lyricHighlightHeadAura
import io.github.jqssun.airplay.lyrics.lyricHighlightGlyphScale
import io.github.jqssun.airplay.lyrics.lyricSustainedWordAura
import io.github.jqssun.airplay.lyrics.progressiveTextSlice
import io.github.jqssun.airplay.lyrics.smoothLyricDisplayPosition
import io.github.jqssun.airplay.lyrics.wordTimedLyricLayoutText
import io.github.jqssun.airplay.renderer.AudioMeterLevels
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

private const val LYRICS_BUTTON_HIDE_DELAY_MS = 5_000L
private const val QUEUE_BROWSE_RETURN_DELAY_MS = 4_000L
private const val LYRIC_CLOCK_SAMPLE_INTERVAL_NS = 80_000_000L
private val AppleLyricEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val AppleLyricRailEasing = CubicBezierEasing(0.2f, 0.72f, 0.2f, 1f)

internal fun lyricsPageVisible(lyricsEnabled: Boolean, queueBrowsing: Boolean): Boolean =
    lyricsEnabled && !queueBrowsing

internal fun centeredTrackTitleViewportWidthPx(
    naturalTitleWidthPx: Int,
    availableWidthPx: Int,
    equalizerAndGapPx: Int,
): Int = minOf(
    naturalTitleWidthPx.coerceAtLeast(0),
    (availableWidthPx - equalizerAndGapPx.coerceAtLeast(0) * 2).coerceAtLeast(0),
)

internal fun smoothAudioMeterDisplay(
    previous: AudioMeterLevels,
    target: AudioMeterLevels,
    frameDeltaMs: Long,
): AudioMeterLevels {
    fun smooth(previousBand: Float, targetBand: Float): Float {
        val timeConstantMs = if (targetBand >= previousBand) 54f else 145f
        val deltaMs = frameDeltaMs.coerceIn(1L, 50L).toFloat()
        val coefficient = 1f -
            exp((-deltaMs / timeConstantMs).toDouble()).toFloat()
        val value = previousBand + (targetBand - previousBand) * coefficient
        return if (value < 0.002f) 0f else value.coerceIn(0f, 1f)
    }
    return AudioMeterLevels(
        low = smooth(previous.low, target.low),
        mid = smooth(previous.mid, target.mid),
        high = smooth(previous.high, target.high),
    )
}

internal fun boundedLyricAuraRadius(
    desiredRadius: Float,
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
): Float {
    val availableRadius = minOf(
        centerX,
        width - centerX,
        centerY,
        height - centerY,
    ).coerceAtLeast(0f)
    return minOf(desiredRadius.coerceAtLeast(0f), availableRadius * 0.92f)
}

internal fun boundedLyricGlyphScale(
    desiredScale: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    width: Float,
    height: Float,
): Float {
    if (desiredScale <= 1f || right <= left || bottom <= top || width <= 0f || height <= 0f) {
        return 1f
    }
    val glyphWidth = right - left
    val glyphHeight = bottom - top
    val horizontalRoom = minOf(left, width - right).coerceAtLeast(0f)
    val verticalRoom = minOf(top, height - bottom).coerceAtLeast(0f)
    val horizontalLimit = 1f + horizontalRoom * 2f / glyphWidth
    val verticalLimit = 1f + verticalRoom * 2f / glyphHeight
    return minOf(desiredScale, horizontalLimit, verticalLimit).coerceAtLeast(1f)
}

/** Dedicated ten-foot Now Playing surface, proportioned around a 1920 x 1080 canvas. */
@Composable
internal fun TvNowPlayingScreen(
    viewModel: MainViewModel,
    onSettings: () -> Unit,
) {
    val track by viewModel.trackInfo.collectAsState()
    val lyrics by viewModel.lyrics.collectAsState()
    val playing by viewModel.audioPlaying.collectAsState()
    val audioOutputStatus by viewModel.audioOutputStatus.collectAsState()
    val appVolume by viewModel.appVolume.collectAsState()
    val appVolumeActivity by viewModel.appVolumeActivity.collectAsState()
    val motionArtworkEnabled by viewModel.motionArtworkEnabled.collectAsState()
    val fullscreenMotionArtwork by viewModel.fullscreenMotionArtwork.collectAsState()
    val karaokeEnabled by viewModel.karaokeEnabled.collectAsState()
    val karaokeVocalLevel by viewModel.karaokeVocalLevel.collectAsState()
    val animatedArtworkUrl by viewModel.animatedArtworkUrl.collectAsState()
    val animatedArtworkFullScreenUrl by viewModel.animatedArtworkFullScreenUrl.collectAsState()
    val animatedArtworkCover by viewModel.animatedArtworkCover.collectAsState()
    val animatedArtworkPreview by viewModel.animatedArtworkPreview.collectAsState()
    val lyricsVisible by viewModel.lyricsVisible.collectAsState()
    val previousTrack by viewModel.previousTrack.collectAsState()
    val upNextTracks by viewModel.upNextTracks.collectAsState()
    val queueName by viewModel.queueName.collectAsState()
    val collectionName = queueName.ifBlank { track.album }
    val audioMeterLevelProvider = remember(viewModel) {
        { viewModel.sampleAudioMeterLevels() }
    }
    val displayArtwork = if (motionArtworkEnabled) animatedArtworkCover ?: track.coverArt else track.coverArt
    // Animated artwork can cut to very different frames. Apple-style ambient
    // colour remains anchored to the canonical still cover so the room does
    // not flash while the cover video plays.
    val backgroundArtwork = track.coverArt ?: animatedArtworkCover
    val extracted = remember(backgroundArtwork) { extractArtworkPalette(backgroundArtwork) }
    val primary by animateColorAsState(extracted.primary, tween(1_400), label = "artPrimary")
    val secondary by animateColorAsState(extracted.secondary, tween(1_400), label = "artSecondary")
    val accent = mix(primary, Color.White, 0.42f)
    val paper = Color(0xFFF9F8FB)
    val muted = paper.copy(alpha = 0.56f)
    val rootFocus = remember { FocusRequester() }
    val lyricsButtonFocus = remember { FocusRequester() }
    var lyricBrowseSignal by remember { mutableIntStateOf(0) }
    var lyricBrowseDirection by remember { mutableIntStateOf(0) }
    var queueSelection by remember { mutableIntStateOf(0) }
    var lyricsButtonFocused by remember { mutableStateOf(false) }
    var lyricsButtonShown by remember { mutableStateOf(true) }
    var lyricsButtonActivity by remember { mutableLongStateOf(0L) }
    var lyricsButtonFocusPending by remember { mutableStateOf(false) }
    var queueBrowseActivity by remember { mutableLongStateOf(0L) }
    var queueAutoReturnToLyrics by remember { mutableStateOf(false) }
    var queueBrowsing by remember { mutableStateOf(false) }
    var appVolumeOverlayShown by remember { mutableStateOf(false) }
    var karaokePanelShown by remember { mutableStateOf(false) }
    var karaokeControlSelection by remember { mutableIntStateOf(0) }
    val showLyricsPage = lyricsPageVisible(lyricsVisible, queueBrowsing)
    // The motion pane is close to square, so prefer Apple's square rendition.
    // A wide rendition remains the fallback when it is the only asset.
    val fullScreenMotionUrl = animatedArtworkUrl ?: animatedArtworkFullScreenUrl
    // The full-screen layout is driven only by the user's option. Without an
    // animated cover it falls back to the sharpest still image available.
    val useFullScreenMotionArtwork = fullscreenMotionArtwork && showLyricsPage
    val fullScreenVideoUrl =
        if (motionArtworkEnabled) fullScreenMotionUrl.orEmpty() else ""
    val minimumQueueSelection = if (previousTrack != null) -1 else 0
    val maximumQueueSelection = upNextTracks.size

    LaunchedEffect(Unit) {
        runCatching { rootFocus.requestFocus() }
    }
    LaunchedEffect(track.title, track.artist, track.album) {
        queueSelection = 0
        karaokePanelShown = false
        runCatching { rootFocus.requestFocus() }
    }
    BackHandler(enabled = karaokePanelShown) {
        karaokePanelShown = false
        runCatching { rootFocus.requestFocus() }
    }
    LaunchedEffect(lyricsVisible) {
        if (!lyricsVisible) {
            queueSelection = 0
            queueAutoReturnToLyrics = false
            queueBrowsing = false
        }
    }
    LaunchedEffect(minimumQueueSelection, maximumQueueSelection) {
        queueSelection = queueSelection.coerceIn(minimumQueueSelection, maximumQueueSelection)
    }
    LaunchedEffect(queueSelection, showLyricsPage) {
        if (!showLyricsPage && queueSelection > 0) {
            viewModel.prefetchQueueArtwork(queueSelection - 1)
        }
    }
    LaunchedEffect(lyricsButtonActivity) {
        delay(LYRICS_BUTTON_HIDE_DELAY_MS)
        lyricsButtonFocusPending = false
        runCatching { rootFocus.requestFocus() }
        lyricsButtonShown = false
    }
    LaunchedEffect(queueBrowseActivity, queueAutoReturnToLyrics) {
        if (!queueAutoReturnToLyrics) return@LaunchedEffect
        delay(QUEUE_BROWSE_RETURN_DELAY_MS)
        queueAutoReturnToLyrics = false
        queueBrowsing = false
        queueSelection = 0
        runCatching { rootFocus.requestFocus() }
    }
    LaunchedEffect(appVolumeActivity) {
        if (appVolumeActivity == 0L) return@LaunchedEffect
        appVolumeOverlayShown = true
        delay(1_500L)
        appVolumeOverlayShown = false
    }
    LaunchedEffect(lyricsButtonShown, lyricsButtonFocusPending) {
        if (lyricsButtonShown && lyricsButtonFocusPending) {
            // AnimatedVisibility must attach the focus target before the
            // request, especially when the first key wakes a hidden button.
            withFrameNanos { }
            runCatching { lyricsButtonFocus.requestFocus() }
            lyricsButtonFocusPending = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lyricsButtonShown = true
                lyricsButtonActivity++
                val keyCode = event.nativeKeyEvent.keyCode
                if (karaokePanelShown) {
                    when (keyCode) {
                        AndroidKeyEvent.KEYCODE_VOLUME_UP,
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            viewModel.adjustKaraokeVocalLevel(5)
                            return@onPreviewKeyEvent true
                        }
                        AndroidKeyEvent.KEYCODE_VOLUME_DOWN,
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            viewModel.adjustKaraokeVocalLevel(-5)
                            return@onPreviewKeyEvent true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                            karaokeControlSelection = 0
                            return@onPreviewKeyEvent true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            karaokeControlSelection = 1
                            return@onPreviewKeyEvent true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                        AndroidKeyEvent.KEYCODE_ENTER,
                        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                        AndroidKeyEvent.KEYCODE_BUTTON_A -> {
                            if (karaokeControlSelection == 0) {
                                viewModel.setKaraokeEnabled(!karaokeEnabled)
                            } else {
                                karaokePanelShown = false
                            }
                            return@onPreviewKeyEvent true
                        }
                        AndroidKeyEvent.KEYCODE_BACK -> {
                            karaokePanelShown = false
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                when (keyCode) {
                    AndroidKeyEvent.KEYCODE_MENU -> {
                        onSettings()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_CAPTIONS -> {
                        queueAutoReturnToLyrics = false
                        queueBrowsing = false
                        viewModel.setLyricsVisible(!lyricsVisible)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        lyricsButtonFocusPending = false
                        runCatching { rootFocus.requestFocus() }
                        queueBrowsing = true
                        queueSelection = (queueSelection + 1).coerceAtMost(maximumQueueSelection)
                        queueAutoReturnToLyrics = true
                        queueBrowseActivity++
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        lyricsButtonFocusPending = false
                        runCatching { rootFocus.requestFocus() }
                        queueBrowsing = true
                        queueSelection = (queueSelection - 1).coerceAtLeast(minimumQueueSelection)
                        queueAutoReturnToLyrics = true
                        queueBrowseActivity++
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        if (showLyricsPage) {
                            lyricBrowseDirection = -1
                            lyricBrowseSignal++
                            true
                        } else {
                            lyricsButtonFocusPending = true
                            true
                        }
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (showLyricsPage) {
                            lyricBrowseDirection = 1
                            lyricBrowseSignal++
                            true
                        } else {
                            runCatching { rootFocus.requestFocus() }
                            true
                        }
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                    AndroidKeyEvent.KEYCODE_BUTTON_A -> {
                        if (showLyricsPage) {
                            if (!karaokeEnabled) viewModel.setKaraokeEnabled(true)
                            karaokePanelShown = true
                            karaokeControlSelection = 0
                            true
                        } else if (!lyricsButtonFocused) {
                            when {
                                queueSelection < 0 -> viewModel.playPreviousQueueTrack()
                                queueSelection > 0 -> viewModel.playQueuedTrack(queueSelection)
                            }
                            queueSelection != 0
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            },
    ) {
        val baseScale = minOf(maxWidth.value / 960f, maxHeight.value / 540f)
        val scale = (baseScale * 0.88f).coerceIn(0.74f, 1.76f)
        val context = LocalContext.current
        val reducedAmbientDetail = remember(context) {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            activityManager?.isLowRamDevice == true ||
                (activityManager?.memoryClass ?: 256) <= 256
        }

        BlendedArtworkBackdrop(
            palette = extracted,
            reducedDetail = reducedAmbientDetail,
        )

        AnimatedVisibility(
            visible = useFullScreenMotionArtwork,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(620, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(340, easing = FastOutSlowInEasing)),
        ) {
            FullScreenMotionArtworkBackdrop(
                artwork = animatedArtworkPreview ?: displayArtwork ?: track.coverArt,
                motionUrl = fullScreenVideoUrl,
                playing = playing,
                primary = primary,
                secondary = secondary,
                modifier = Modifier.fillMaxSize(),
            )
        }

        val queueLayoutProgress by animateFloatAsState(
            targetValue = if (showLyricsPage) 0f else 1f,
            animationSpec = spring(
                dampingRatio = 0.86f,
                stiffness = Spring.StiffnessLow,
            ),
            label = "queueLayoutProgress",
        )
        // Do not keep the classic cover/title tree underneath full-screen
        // motion artwork. Alpha alone can leave a Surface or glass layer
        // visible during transitions on some Android TV renderers.
        if (!useFullScreenMotionArtwork) {
            NoLyricsQueueStage(
                current = track,
                currentArtwork = displayArtwork,
                animatedArtworkUrl = animatedArtworkUrl.takeIf { motionArtworkEnabled },
                previous = previousTrack,
                upNext = upNextTracks,
                selectedOffset = queueSelection,
                sideCardsVisibility = queueLayoutProgress,
                primary = primary,
                secondary = secondary,
                paper = paper,
                muted = muted,
                scale = scale,
                playing = playing,
                artworkMotionEnabled = motionArtworkEnabled,
                audioMeterLevelProvider = audioMeterLevelProvider,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = -340f * scale * (1f - queueLayoutProgress) * density
                    },
            )
        }

        AnimatedVisibility(
            visible = useFullScreenMotionArtwork,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = maxWidth * 0.55f,
                    end = (40f * scale).dp,
                    top = (30f * scale).dp,
                ),
            enter = fadeIn(tween(540, delayMillis = 180)),
            exit = fadeOut(tween(220)),
        ) {
            FullScreenTrackHeader(
                track = track,
                primary = primary,
                paper = paper,
                scale = scale,
                playing = playing,
                audioMeterLevelProvider = audioMeterLevelProvider,
            )
        }

        AnimatedVisibility(
            visible = showLyricsPage,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = animateDpAsState(
                        targetValue = (
                            if (useFullScreenMotionArtwork) {
                                maxWidth * 0.55f
                            } else {
                                (430f * scale).dp
                            }
                            ),
                        animationSpec = tween(460, easing = FastOutSlowInEasing),
                        label = "lyricsMotionArtworkBoundary",
                    ).value,
                    end = ((if (useFullScreenMotionArtwork) 28f else 54f) * scale).dp,
                    top = ((if (useFullScreenMotionArtwork) 118f else 30f) * scale).dp,
                    bottom = ((if (useFullScreenMotionArtwork) 28f else 30f) * scale).dp,
                ),
            enter = fadeIn(tween(420)) + slideInHorizontally(tween(520)) { it / 12 },
            exit = fadeOut(tween(280)) + slideOutHorizontally(tween(380)) { it / 14 },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                DisplaySyncedLyricsStage(
                    lyrics = lyrics,
                    viewModel = viewModel,
                    accent = accent,
                    paper = paper,
                    muted = muted,
                    scale = scale,
                    durationMs = track.durationMs,
                    writerCredit = lyrics.writerCredit.ifBlank { track.composer },
                    playing = playing,
                    browseSignal = lyricBrowseSignal,
                    browseDirection = lyricBrowseDirection,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        AnimatedVisibility(
            visible = !showLyricsPage && lyricsButtonShown,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = (27f * scale).dp, top = (24f * scale).dp),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(280)),
        ) {
            LyricsVisibilityButton(
                visible = lyricsVisible,
                scale = scale,
                paper = paper,
                focusRequester = lyricsButtonFocus,
                onClick = {
                    lyricsButtonShown = true
                    lyricsButtonActivity++
                    queueAutoReturnToLyrics = false
                    queueBrowsing = false
                    viewModel.setLyricsVisible(!lyricsVisible)
                },
                onFocusedChange = { lyricsButtonFocused = it },
            )
        }

        AnimatedVisibility(
            visible = showLyricsPage && lyricsButtonShown && !karaokePanelShown,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = (27f * scale).dp, top = (24f * scale).dp),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220)),
        ) {
            KaraokeQuickBadge(
                enabled = karaokeEnabled,
                paper = paper,
                scale = scale,
            )
        }

        AnimatedVisibility(
            visible = showLyricsPage && karaokePanelShown,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = (30f * scale).dp),
            enter = fadeIn(tween(220)) + slideInHorizontally(tween(300)) { it / 3 },
            exit = fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { it / 3 },
        ) {
            KaraokeControlPanel(
                enabled = karaokeEnabled,
                vocalLevel = karaokeVocalLevel,
                selectedControl = karaokeControlSelection,
                paper = paper,
                accent = accent,
                scale = scale,
            )
        }

        AnimatedVisibility(
            visible = !showLyricsPage && collectionName.isNotBlank(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = (21f * scale).dp),
            enter = fadeIn(tween(420)),
            exit = fadeOut(tween(220)),
        ) {
            NoLyricsCollectionHeading(
                name = collectionName,
                paper = paper,
                scale = scale,
            )
        }

        AnimatedVisibility(
            visible = !showLyricsPage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            enter = fadeIn(tween(420)),
            exit = fadeOut(tween(220)),
        ) {
            NoLyricsPlaybackFooter(
                viewModel = viewModel,
                durationMs = track.durationMs,
                audioOutputStatus = audioOutputStatus,
                playing = playing,
                accent = accent,
                paper = paper,
                muted = muted,
                scale = scale,
            )
        }

        AnimatedVisibility(
            visible = appVolumeOverlayShown,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(220)),
        ) {
            Box(
                modifier = Modifier
                    .liquidGlass(
                        shape = RoundedCornerShape((18f * scale).dp),
                        selected = true,
                        tint = accent,
                        strength = 1.02f,
                    )
                    .padding(
                        horizontal = (21f * scale).dp,
                        vertical = (12f * scale).dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (appVolume == 0) stringResource(R.string.label_app_muted) else stringResource(R.string.label_app_volume, appVolume),
                    color = paper,
                    fontSize = (13f * scale).sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (0.7f * scale).sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun NoLyricsCollectionHeading(
    name: String,
    paper: Color,
    scale: Float,
) {
    Box(
        modifier = Modifier
            .width((420f * scale).dp)
            .liquidGlass(
                shape = RoundedCornerShape((18f * scale).dp),
                tint = paper,
                strength = 0.62f,
            )
            .padding(
                horizontal = (15f * scale).dp,
                vertical = (7f * scale).dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BouncingFadingText(
            text = name,
            color = paper.copy(alpha = 0.94f),
            style = TextStyle(
                fontSize = (15f * scale).sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NoLyricsQueueStage(
    current: TrackInfo,
    currentArtwork: Bitmap?,
    animatedArtworkUrl: String?,
    previous: TrackInfo?,
    upNext: List<TrackInfo>,
    selectedOffset: Int,
    sideCardsVisibility: Float,
    primary: Color,
    secondary: Color,
    paper: Color,
    muted: Color,
    scale: Float,
    playing: Boolean,
    artworkMotionEnabled: Boolean,
    audioMeterLevelProvider: () -> AudioMeterLevels,
    modifier: Modifier = Modifier,
) {
    fun trackAt(offset: Int): TrackInfo? = when {
        offset < 0 -> previous
        offset == 0 -> current.copy(coverArt = currentArtwork)
        else -> upNext.getOrNull(offset - 1)
    }

    val firstOffset = if (previous != null) -1 else 0
    val offsets = remember(firstOffset, upNext.size) {
        (firstOffset..upNext.size).toList()
    }
    val selectedIndex = (selectedOffset - firstOffset).coerceIn(0, offsets.lastIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    LaunchedEffect(selectedIndex, offsets.size) {
        listState.animateScrollToItem(selectedIndex)
    }

    BoxWithConstraints(modifier = modifier) {
        val cardWidth = (300f * scale).dp
        val centrePadding = maxOf(0.dp, (maxWidth - cardWidth) / 2)
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = centrePadding),
            horizontalArrangement = Arrangement.spacedBy((4f * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
            userScrollEnabled = false,
        ) {
            itemsIndexed(
                items = offsets,
                key = { _, offset -> offset },
            ) { _, offset ->
                val selected = offset == selectedOffset
                EqualQueueTrackCard(
                    track = trackAt(offset),
                    selected = selected,
                    isCurrent = offset == 0,
                    visibility = if (selected) 1f else sideCardsVisibility,
                    decorationVisibility = sideCardsVisibility,
                    emergeDirection = (offset - selectedOffset).coerceIn(-1, 1),
                    animatedArtworkUrl = animatedArtworkUrl.takeIf {
                        offset == 0 && artworkMotionEnabled
                    },
                    primary = primary,
                    secondary = secondary,
                    paper = paper,
                    muted = muted,
                    scale = scale,
                    playing = playing,
                    audioMeterLevelProvider = audioMeterLevelProvider,
                )
            }
        }
    }
}

@Composable
private fun EqualQueueTrackCard(
    track: TrackInfo?,
    selected: Boolean,
    isCurrent: Boolean,
    visibility: Float,
    decorationVisibility: Float,
    emergeDirection: Int,
    animatedArtworkUrl: String?,
    primary: Color,
    secondary: Color,
    paper: Color,
    muted: Color,
    scale: Float,
    playing: Boolean,
    audioMeterLevelProvider: () -> AudioMeterLevels,
) {
    val artSize = (292f * scale).dp
    val cardAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.72f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "queueCardAlpha",
    )
    val selectionScale by animateFloatAsState(
        targetValue = if (selected) 1.035f else 0.965f,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "queueCardSelectionScale",
    )
    Column(
        modifier = Modifier
            .width((300f * scale).dp)
            .graphicsLayer {
                alpha = cardAlpha * visibility
                val decoratedScale = 1f +
                    ((selectionScale - 1f) * decorationVisibility)
                val emergeScale = (0.94f + (0.06f * visibility)) * decoratedScale
                scaleX = emergeScale
                scaleY = emergeScale
                translationX =
                    -emergeDirection * 54f * (1f - visibility) * scale * density
                translationY = (
                    (if (selected) 0f else 7f) + 14f * (1f - visibility)
                ) * scale * density
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(artSize)
                .liquidGlass(
                    shape = RoundedCornerShape((15f * scale).dp),
                    focused = selected && decorationVisibility > 0.5f,
                    selected = selected,
                    tint = mix(primary, paper, 0.38f),
                    strength = if (selected) 1.02f else 0.68f,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isCurrent) {
                MotionAlbumArtwork(
                    artwork = track?.coverArt,
                    motionUrl = animatedArtworkUrl,
                    paper = paper,
                    scale = scale,
                    playing = playing,
                )
            } else if (track?.coverArt != null) {
                Image(
                    bitmap = track.coverArt.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = paper.copy(alpha = 0.72f),
                    modifier = Modifier.size((48f * scale).dp),
                )
            }
        }
        Spacer(Modifier.height((12f * scale).dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .thenIf(selected) {
                    liquidGlass(
                        shape = RoundedCornerShape((16f * scale).dp),
                        selected = true,
                        tint = mix(primary, paper, 0.26f),
                        strength = 0.56f,
                    )
                }
                .padding(
                    horizontal = (10f * scale).dp,
                    vertical = (if (selected) 7f else 0f).times(scale).dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (track != null) {
                if (isCurrent) {
                    TitleWithEqualizer(
                        title = track.title,
                        color = paper.copy(alpha = 0.92f),
                        equalizerColor = mix(primary, Color.White, 0.58f),
                        scale = scale,
                        playing = playing,
                        audioMeterLevelProvider = audioMeterLevelProvider,
                        titleFontSize = 18f,
                        titleLineHeight = 22f,
                        titleFontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    BouncingFadingText(
                        text = track.title,
                        color = paper.copy(alpha = 0.92f),
                        style = TextStyle(
                            fontSize = (18f * scale).sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height((2f * scale).dp))
            BouncingFadingText(
                text = track?.artist?.takeIf { it.isNotBlank() } ?: "",
                color = muted.copy(alpha = 0.82f),
                style = TextStyle(
                    fontSize = (12f * scale).sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!track?.album.isNullOrBlank()) {
                Spacer(Modifier.height((1f * scale).dp))
                BouncingFadingText(
                    text = track?.album.orEmpty(),
                    color = muted.copy(alpha = 0.62f),
                    style = TextStyle(
                        fontSize = (9f * scale).sp,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun NoLyricsPlaybackFooter(
    viewModel: MainViewModel,
    durationMs: Long,
    audioOutputStatus: String,
    playing: Boolean,
    accent: Color,
    paper: Color,
    muted: Color,
    scale: Float,
) {
    val sourcePositionMs by viewModel.audioPositionMs.collectAsState()
    val positionMs = rememberDisplaySyncedPosition(
        sourcePositionMs = sourcePositionMs,
        viewModel = viewModel,
        enabled = playing,
    ).coerceIn(0L, durationMs.coerceAtLeast(0L))
    val progress = if (durationMs > 0L) {
        positionMs.toFloat() / durationMs.toFloat()
    } else {
        0f
    }.coerceIn(0f, 1f)
    val status = audioOutputStatus.ifBlank {
        stringResource(R.string.label_alac_lossless_format)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = (31f * scale).dp,
                end = (31f * scale).dp,
                bottom = (18f * scale).dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .liquidGlass(
                        shape = RoundedCornerShape((16f * scale).dp),
                        tint = accent,
                        strength = 0.62f,
                    )
                    .padding(
                        horizontal = (10f * scale).dp,
                        vertical = (5f * scale).dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = accent.copy(alpha = 0.96f),
                    modifier = Modifier.size((13f * scale).dp),
                )
                Spacer(Modifier.width((5f * scale).dp))
                Text(
                    text = status,
                    color = paper.copy(alpha = 0.9f),
                    fontSize = (9f * scale).sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (0.35f * scale).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height((8f * scale).dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((3f * scale).dp)
                .clip(CircleShape)
                .background(paper.copy(alpha = 0.24f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                accent.copy(alpha = 0.82f),
                                paper.copy(alpha = 0.96f),
                            ),
                        ),
                    ),
            )
        }
        Spacer(Modifier.height((5f * scale).dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatVideoTime(positionMs),
                color = paper.copy(alpha = 0.88f),
                fontSize = (9f * scale).sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (durationMs > 0L) {
                    "-${formatVideoTime((durationMs - positionMs).coerceAtLeast(0L))}"
                } else {
                    "--:--"
                },
                color = muted.copy(alpha = 0.82f),
                fontSize = (9f * scale).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LyricsVisibilityButton(
    visible: Boolean,
    scale: Float,
    paper: Color,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onFocusedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val tint by animateColorAsState(
        targetValue = if (focused) paper else paper.copy(alpha = 0.86f),
        animationSpec = tween(180),
        label = "lyricsButtonTint",
    )
    Box(
        modifier = modifier
            .size((43f * scale).dp)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                onFocusedChange(it.isFocused)
            }
            .liquidGlass(
                shape = CircleShape,
                focused = focused,
                selected = visible,
                tint = paper,
                strength = if (focused) 1.05f else 0.74f,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Lyrics,
            contentDescription = stringResource(if (visible) R.string.cd_hide_lyrics else R.string.cd_show_lyrics),
            tint = tint,
            modifier = Modifier.size((21f * scale).dp),
        )
    }
}

@Composable
private fun KaraokeQuickBadge(
    enabled: Boolean,
    paper: Color,
    scale: Float,
) {
    Box(
        modifier = Modifier
            .size((43f * scale).dp)
            .liquidGlass(
                shape = CircleShape,
                selected = enabled,
                tint = paper,
                strength = if (enabled) 0.98f else 0.72f,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = stringResource(R.string.cd_open_karaoke),
            tint = paper.copy(alpha = if (enabled) 1f else 0.88f),
            modifier = Modifier.size((21f * scale).dp),
        )
    }
}

@Composable
private fun KaraokeControlPanel(
    enabled: Boolean,
    vocalLevel: Int,
    selectedControl: Int,
    paper: Color,
    accent: Color,
    scale: Float,
) {
    val effectiveLevel by animateFloatAsState(
        targetValue = if (enabled) vocalLevel / 100f else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "karaokeVocalLevel",
    )
    Column(
        modifier = Modifier
            .width((166f * scale).dp)
            .liquidGlass(
                shape = RoundedCornerShape((22f * scale).dp),
                selected = enabled,
                tint = accent,
                strength = 1.05f,
            )
            .padding(
                horizontal = (14f * scale).dp,
                vertical = (15f * scale).dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.karaoke_title),
            color = paper.copy(alpha = 0.72f),
            fontSize = (8.1f * scale).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (0.9f * scale).sp,
            maxLines = 1,
        )
        Spacer(Modifier.height((10f * scale).dp))
        Box(
            modifier = Modifier
                .width((42f * scale).dp)
                .height((132f * scale).dp)
                .liquidGlass(
                    shape = RoundedCornerShape((18f * scale).dp),
                    tint = accent,
                    strength = 0.78f,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(effectiveLevel.coerceAtLeast(0.055f))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                mix(paper, accent, 0.2f).copy(alpha = 0.9f),
                                paper.copy(alpha = 0.98f),
                            ),
                        ),
                    ),
            )
        }
        Spacer(Modifier.height((8f * scale).dp))
        Text(
            text = if (enabled) stringResource(R.string.karaoke_vocals, vocalLevel) else stringResource(R.string.karaoke_original),
            color = paper.copy(alpha = 0.76f),
            fontSize = (8.2f * scale).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.height((11f * scale).dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy((9f * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KaraokeControlCircle(
                selected = selectedControl == 0,
                active = enabled,
                paper = paper,
                scale = scale,
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = stringResource(if (enabled) R.string.cd_disable_karaoke else R.string.cd_enable_karaoke),
                    tint = paper,
                    modifier = Modifier.size((20f * scale).dp),
                )
            }
            KaraokeControlCircle(
                selected = selectedControl == 1,
                active = true,
                paper = paper,
                scale = scale,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Done",
                    tint = paper,
                    modifier = Modifier.size((20f * scale).dp),
                )
            }
        }
        Spacer(Modifier.height((10f * scale).dp))
        Text(
            text = stringResource(R.string.karaoke_hint),
            color = paper.copy(alpha = 0.54f),
            fontSize = (7.2f * scale).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = (10.5f * scale).sp,
        )
    }
}

@Composable
private fun KaraokeControlCircle(
    selected: Boolean,
    active: Boolean,
    paper: Color,
    scale: Float,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size((39f * scale).dp)
            .liquidGlass(
                shape = CircleShape,
                focused = selected,
                selected = active,
                tint = paper,
                strength = if (selected) 1.02f else 0.68f,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun FullScreenMotionArtworkBackdrop(
    artwork: Bitmap?,
    motionUrl: String,
    playing: Boolean,
    primary: Color,
    secondary: Color,
    modifier: Modifier = Modifier,
) {
    // Blurred once on a tiny bitmap (no per-frame RenderEffect), then simply
    // stretched: the colour field is cheap to draw even on weak TV GPUs.
    val soft = remember(artwork) { artwork?.let { createSoftBlurBitmap(it) } }
    Box(modifier = modifier) {
        // One continuous blurred colour field under cover AND lyrics.
        if (soft != null) {
            Image(
                bitmap = soft.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.96f },
            )
        }
        // Palette tint keeps the lyric side coloured like the cover.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to primary.copy(alpha = 0.16f),
                        1f to secondary.copy(alpha = 0.22f),
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.62f)
                .fillMaxHeight()
                .clipToBounds(),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(),
            ) {
                // Still first: the video never reveals an empty pane.
                artwork?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (motionUrl.isNotBlank()) {
                    LoopingMotionArtwork(
                        url = motionUrl,
                        playing = playing,
                        fullScreen = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // Static pre-blurred cover dissolving over the sharp cover's edge
            // (a cached layer: it never changes while the video plays).
            if (soft != null) {
                Image(
                    bitmap = soft.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier
                        .fillMaxSize()
                        .motionArtworkBlendBand(),
                )
            }
        }
        // Light scrim only on the lyric side so text stays clear (no box).
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Transparent,
                        0.62f to Color.Black.copy(alpha = 0.22f),
                        1f to Color.Black.copy(alpha = 0.34f),
                    )
                )
        )
    }
}

/** Downscales and box-blurs a cover once; drawn stretched it reads as a heavy blur. */
private fun createSoftBlurBitmap(source: Bitmap): Bitmap {
    val safe = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return source
    } else {
        source
    }
    val w = 48
    val h = (safe.height * w / safe.width.coerceAtLeast(1)).coerceIn(8, 96)
    val small = Bitmap.createScaledBitmap(safe, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    var src = px
    var dst = IntArray(w * h)
    repeat(3) {
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                val c = src[yy * w + xx]
                a += c ushr 24; r += (c shr 16) and 255
                g += (c shr 8) and 255; b += c and 255; n++
            }
            dst[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        val t = src; src = dst; dst = t
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(src, 0, w, 0, 0, w, h)
    return out
}

/**
 * Alpha mask for the blurred cover that sits over the movie's right edge:
 * invisible over the sharp video, fully opaque where the video ends (90 %),
 * then fading out into the background colour field.
 */
private fun Modifier.motionArtworkBlendBand(): Modifier =
    graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.70f to Color.Transparent,
                0.80f to Color.White.copy(alpha = 0.55f),
                0.88f to Color.White,
                0.91f to Color.White,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Soft dark edges for the playing movie, drawn as plain gradients on top of
 * it. The previous version masked the video with an offscreen DstIn layer,
 * which re-rendered a full-pane buffer for every video frame.
 */
private fun Modifier.motionArtworkEdgeFade(): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.3f),
            0.07f to Color.Transparent,
            0.9f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.4f),
        ),
    )
}

/**
 * Keeps the centre of motion artwork sharp while progressively revealing the
 * blurred preview and ambient colour field near its perimeter. Two masks are
 * used: a wider one for the blurred still and a tighter one for the movie.
 * This avoids running a second video decoder just to create a soft edge.
 */
private fun Modifier.motionArtworkEdgeFeather(softLayer: Boolean): Modifier =
    graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.White.copy(alpha = if (softLayer) 0.28f else 0.62f),
                (if (softLayer) 0.035f else 0.055f) to Color.White,
                (if (softLayer) 0.72f else 0.76f) to Color.White,
                (if (softLayer) 0.9f else 0.88f) to
                    Color.White.copy(alpha = if (softLayer) 0.42f else 0.5f),
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (softLayer) 0.18f else 0.42f),
                (if (softLayer) 0.055f else 0.075f) to Color.White,
                (if (softLayer) 0.9f else 0.86f) to Color.White,
                1f to Color.White.copy(alpha = if (softLayer) 0.14f else 0.32f),
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun FullScreenTrackHeader(
    track: TrackInfo,
    primary: Color,
    paper: Color,
    scale: Float,
    playing: Boolean,
    audioMeterLevelProvider: () -> AudioMeterLevels,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TitleWithEqualizer(
            title = track.title,
            color = paper,
            equalizerColor = mix(primary, Color.White, 0.62f),
            scale = scale,
            playing = playing,
            audioMeterLevelProvider = audioMeterLevelProvider,
            titleFontSize = 22f,
            titleLineHeight = 27f,
            titleFontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
        )
        if (track.artist.isNotBlank()) {
            Spacer(Modifier.height((4f * scale).dp))
            BouncingFadingText(
                text = track.artist,
                color = paper.copy(alpha = 0.78f),
                style = TextStyle(
                    fontSize = (15f * scale).sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BlendedArtworkBackdrop(
    palette: ArtworkPalette,
    reducedDetail: Boolean,
) {
    val textures = remember(palette, reducedDetail) {
        createAmbientBackdropTextures(palette, reducedDetail)
    }
    val transition = rememberInfiniteTransition(label = "ambientBackdrop")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(30_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ambientPhase",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF08090D)),
    ) {
        // Keep the old colour field alive while the new cover blooms in.
        // Crossfading complete low-resolution textures avoids allocating a
        // fresh bitmap for every intermediate colour-animation frame.
        Crossfade(
            targetState = textures,
            animationSpec = tween(1_650, easing = AppleLyricRailEasing),
            label = "ambientPaletteCrossfade",
        ) { target ->
            AnimatedAmbientTextureLayers(
                textures = target,
                phase = phase,
                reducedDetail = reducedDetail,
            )
        }
        // A neutral centre sheen gives the coloured field the soft depth seen
        // behind Apple Music lyrics without tinting white type.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.055f),
                        0.46f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.1f),
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.075f),
                        0.42f to Color.Transparent,
                        0.72f to Color.Black.copy(alpha = 0.08f),
                        1f to Color.Black.copy(alpha = 0.46f),
                    )
                )
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.14f),
                        0.48f to Color.Black.copy(alpha = 0.012f),
                        1f to Color.Black.copy(alpha = 0.17f),
                    )
                )
        )
    }
}

private data class AmbientBackdropTextures(
    val base: Bitmap,
    val bloom: Bitmap,
)

@Composable
private fun AnimatedAmbientTextureLayers(
    textures: AmbientBackdropTextures,
    phase: androidx.compose.runtime.State<Float>,
    reducedDetail: Boolean,
) {
    val base = remember(textures) { textures.base.asImageBitmap() }
    val bloom = remember(textures) { textures.bloom.asImageBitmap() }
    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = base,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val angle = phase.value * (2f * PI.toFloat())
                    val breathe = 0.034f * sin(angle * 0.71f)
                    scaleX = 1.22f + breathe
                    scaleY = 1.22f + breathe * 0.86f
                    translationX = 52f * cos(angle * 0.87f)
                    translationY = 34f * sin(angle * 0.69f)
                    rotationZ = 0.85f * sin(angle * 0.57f)
                },
        )
        Image(
            bitmap = bloom,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val angle = phase.value * (2f * PI.toFloat()) + PI.toFloat() * 0.73f
                    val breathe = 0.052f * cos(angle * 0.53f)
                    alpha = 0.48f
                    scaleX = 1.42f + breathe
                    scaleY = 1.38f + breathe * 0.9f
                    translationX = -48f * sin(angle * 0.76f)
                    translationY = 42f * cos(angle * 0.63f)
                    rotationZ = -1.55f * cos(angle * 0.49f)
                },
        )
        if (!reducedDetail) {
            // Premium devices receive one additional very faint pass. It
            // creates parallax rather than extra blur; low-RAM televisions
            // retain the same colours and timing with two texture quads.
            Image(
                bitmap = base,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val angle = phase.value * (2f * PI.toFloat()) + PI.toFloat() * 1.31f
                        alpha = 0.13f
                        scaleX = 1.66f
                        scaleY = 1.58f
                        translationX = 36f * cos(angle * 0.41f)
                        translationY = -32f * sin(angle * 0.46f)
                        rotationZ = 2.1f * sin(angle * 0.38f)
                    },
            )
        }
    }
}

private fun createAmbientBackdropTextures(
    palette: ArtworkPalette,
    reducedDetail: Boolean,
): AmbientBackdropTextures {
    val width = if (reducedDetail) 448 else 576
    val height = width * 9 / 16
    return AmbientBackdropTextures(
        base = createAmbientBackdropTexture(
            palette = palette,
            width = width,
            height = height,
            variant = 0,
        ),
        bloom = createAmbientBackdropTexture(
            palette = palette,
            width = width,
            height = height,
            variant = 1,
        ),
    )
}

private fun createAmbientBackdropTexture(
    palette: ArtworkPalette,
    width: Int,
    height: Int,
    variant: Int,
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(
        mix(
            palette.dark,
            Color.Black,
            if (variant == 0) 0.2f else 0.34f,
        ).toArgb()
    )
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)

    fun blob(color: Color, x: Float, y: Float, radius: Float, strength: Float) {
        paint.shader = AndroidRadialGradient(
            x,
            y,
            radius,
            intArrayOf(
                mix(color, Color.White, 0.08f).copy(alpha = strength).toArgb(),
                color.copy(alpha = strength * 0.46f).toArgb(),
                Color.Transparent.toArgb(),
            ),
            floatArrayOf(0f, 0.48f, 1f),
            AndroidShader.TileMode.CLAMP,
        )
        canvas.drawCircle(x, y, radius, paint)
    }

    if (variant == 0) {
        blob(palette.primary, width * 0.12f, height * 0.28f, width * 0.64f, 0.78f)
        blob(palette.secondary, width * 0.92f, height * 0.2f, width * 0.62f, 0.72f)
        blob(palette.tertiary, width * 0.58f, height * 1.02f, width * 0.68f, 0.62f)
        blob(palette.highlight, width * 0.48f, height * 0.22f, width * 0.34f, 0.34f)
        blob(Color.Black, width * 0.48f, height * 0.62f, width * 0.7f, 0.16f)
    } else {
        blob(palette.secondary, width * 0.08f, height * 0.9f, width * 0.66f, 0.72f)
        blob(palette.primary, width * 0.86f, height * 0.7f, width * 0.68f, 0.68f)
        blob(palette.tertiary, width * 0.5f, height * -0.04f, width * 0.58f, 0.56f)
        blob(palette.highlight, width * 0.76f, height * 0.17f, width * 0.3f, 0.3f)
        blob(Color.Black, width * 0.56f, height * 0.52f, width * 0.76f, 0.2f)
    }
    paint.shader = null
    applyAmbientDither(
        bitmap = bitmap,
        seed = palette.primary.toArgb() xor palette.secondary.toArgb() xor (variant * 0x45D9F3B),
    )
    return bitmap
}

private fun applyAmbientDither(bitmap: Bitmap, seed: Int) {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    var state = seed
    pixels.indices.forEach { index ->
        state = state * 1_664_525 + 1_013_904_223
        val delta = ((state ushr 29) and 0x7) - 3
        val pixel = pixels[index]
        val alpha = (pixel ushr 24) and 0xFF
        val red = (((pixel ushr 16) and 0xFF) + delta).coerceIn(0, 255)
        val green = (((pixel ushr 8) and 0xFF) + delta).coerceIn(0, 255)
        val blue = ((pixel and 0xFF) + delta).coerceIn(0, 255)
        pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
}

@Composable
private fun MinimalArtworkColumn(
    artwork: Bitmap?,
    animatedArtworkUrl: String?,
    title: String,
    artist: String,
    album: String,
    primary: Color,
    secondary: Color,
    paper: Color,
    muted: Color,
    scale: Float,
    playing: Boolean,
    artworkMotionEnabled: Boolean,
    audioMeterLevelProvider: () -> AudioMeterLevels = { AudioMeterLevels.Silent },
) {
    val artworkSize = (282f * scale).dp
    val motion = rememberInfiniteTransition(label = "artworkMotion")
    val floatY by motion.animateFloat(
        initialValue = -3.5f,
        targetValue = 3.5f,
        animationSpec = infiniteRepeatable(
            tween(3_800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "artworkFloat",
    )
    val tilt by motion.animateFloat(
        initialValue = -0.55f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            tween(5_600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "artworkTilt",
    )
    val breathe by motion.animateFloat(
        initialValue = 1f,
        targetValue = 1.018f,
        animationSpec = infiniteRepeatable(
            tween(4_600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "artworkBreathe",
    )
    val glow by motion.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.32f,
        animationSpec = infiniteRepeatable(
            tween(3_400, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "artworkGlow",
    )
    val hasMotionVideo = !animatedArtworkUrl.isNullOrBlank()
    val animateStaticArtwork = artworkMotionEnabled && !hasMotionVideo
    Column(
        modifier = Modifier.width((304f * scale).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(artworkSize),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = if (artworkMotionEnabled) 1.07f + (breathe - 1f) else 1.07f
                        scaleY = if (artworkMotionEnabled) 1.07f + (breathe - 1f) else 1.07f
                        alpha = if (artworkMotionEnabled) glow else 0.24f
                    }
                    .background(
                        Brush.radialGradient(
                            listOf(primary, secondary.copy(alpha = 0.55f), Color.Transparent)
                        ),
                        RoundedCornerShape((25f * scale).dp),
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = ((if (animateStaticArtwork) floatY else 0f) * scale).dp.toPx()
                        rotationZ = if (animateStaticArtwork) tilt else 0f
                        rotationY = if (animateStaticArtwork) tilt * 0.7f else 0f
                        scaleX = if (animateStaticArtwork) breathe else 1f
                        scaleY = if (animateStaticArtwork) breathe else 1f
                        cameraDistance = 18f * density
                    }
                    .liquidGlass(
                        shape = RoundedCornerShape((19f * scale).dp),
                        selected = playing,
                        tint = mix(primary, paper, 0.28f),
                        strength = 0.96f,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                MotionAlbumArtwork(
                    artwork = artwork,
                    motionUrl = animatedArtworkUrl,
                    paper = paper,
                    scale = scale,
                    playing = playing,
                )
            }
        }

        Spacer(Modifier.height((17f * scale).dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlass(
                    shape = RoundedCornerShape((18f * scale).dp),
                    selected = playing,
                    tint = mix(primary, paper, 0.22f),
                    strength = 0.58f,
                )
                .padding(
                    horizontal = (11f * scale).dp,
                    vertical = (8f * scale).dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TitleWithEqualizer(
                title = title,
                color = paper,
                equalizerColor = mix(primary, Color.White, 0.58f),
                scale = scale,
                playing = playing,
                audioMeterLevelProvider = audioMeterLevelProvider,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height((3f * scale).dp))
            BouncingFadingText(
                text = artist,
                color = paper.copy(alpha = 0.76f),
                style = TextStyle(
                    fontSize = (14f * scale).sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (album.isNotBlank()) {
                Spacer(Modifier.height((2f * scale).dp))
                BouncingFadingText(
                    text = album,
                    color = muted.copy(alpha = 0.76f),
                    style = TextStyle(
                        fontSize = (11f * scale).sp,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TitleWithEqualizer(
    title: String,
    color: Color,
    equalizerColor: Color,
    scale: Float,
    playing: Boolean,
    audioMeterLevelProvider: () -> AudioMeterLevels,
    titleFontSize: Float = 20f,
    titleLineHeight: Float = 24f,
    titleFontWeight: FontWeight = FontWeight.Bold,
    modifier: Modifier = Modifier,
) {
    val titleStyle = TextStyle(
        fontSize = (titleFontSize * scale).sp,
        lineHeight = (titleLineHeight * scale).sp,
        fontWeight = titleFontWeight,
        textAlign = TextAlign.Center,
    )
    var availableWidthPx by remember(title) { mutableIntStateOf(0) }
    var titleWidthPx by remember(title) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val equalizerWidthPx = with(density) { (15f * scale).dp.roundToPx() }
    val equalizerGapPx = with(density) { (4f * scale).dp.roundToPx() }
    val titleViewportWidthPx = centeredTrackTitleViewportWidthPx(
        naturalTitleWidthPx = titleWidthPx,
        availableWidthPx = availableWidthPx,
        equalizerAndGapPx = equalizerWidthPx + equalizerGapPx,
    )
    val titleViewportWidth = with(density) { titleViewportWidthPx.toDp() }

    Box(
        modifier = modifier.onSizeChanged { availableWidthPx = it.width },
        contentAlignment = Alignment.Center,
    ) {
        // Measure the title independently. The equalizer is a separate sibling
        // so it can sit close to the first glyph without shifting the title's
        // optical centre or joining its marquee animation.
        Text(
            text = title,
            color = Color.Transparent,
            style = titleStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .wrapContentWidth(unbounded = true)
                .onSizeChanged { titleWidthPx = it.width },
        )

        if (titleViewportWidthPx > 0) {
            BouncingFadingText(
                text = title,
                color = color,
                style = titleStyle,
                modifier = Modifier.width(titleViewportWidth),
            )
            MiniNowPlayingEqualizer(
                color = equalizerColor,
                scale = scale,
                playing = playing,
                audioMeterLevelProvider = audioMeterLevelProvider,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        translationX = -(
                            titleViewportWidthPx / 2f +
                                equalizerGapPx +
                                equalizerWidthPx / 2f
                            )
                    },
            )
        }
    }
}

@Composable
private fun BouncingFadingText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    var containerWidthPx by remember(text) { mutableIntStateOf(0) }
    var contentWidthPx by remember(text) { mutableIntStateOf(0) }
    val overflowPx = (contentWidthPx - containerWidthPx).coerceAtLeast(0)
    val travel = remember { Animatable(0f) }
    val pixelsPerSecond = with(LocalDensity.current) { 36.dp.toPx() }
    // The edge fades only change at the two ends of the scroll. Reading the
    // raw animated value in the draw phase would re-rasterise the whole
    // masked layer on every frame (the cause of the choppy marquee).
    val leftEdgeClear by remember {
        derivedStateOf { travel.value <= 1f }
    }
    val rightEdgeClear by remember(overflowPx) {
        derivedStateOf { travel.value >= overflowPx - 1f }
    }

    LaunchedEffect(text, overflowPx) {
        travel.snapTo(0f)
        if (overflowPx <= 1) return@LaunchedEffect
        val durationMs = ((overflowPx / pixelsPerSecond) * 1_000f)
            .roundToInt()
            .coerceIn(1_700, 14_000)
        delay(1_150)
        while (isActive) {
            travel.animateTo(
                targetValue = overflowPx.toFloat(),
                animationSpec = tween(durationMs, easing = LinearEasing),
            )
            delay(850)
            travel.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMs, easing = LinearEasing),
            )
            delay(1_150)
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerWidthPx = it.width }
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                if (overflowPx > 1) {
                    val leftAlpha = if (leftEdgeClear) 1f else 0f
                    val rightAlpha = if (rightEdgeClear) 1f else 0f
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.White.copy(alpha = leftAlpha),
                            0.08f to Color.White,
                            0.92f to Color.White,
                            1f to Color.White.copy(alpha = rightAlpha),
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
        contentAlignment = if (overflowPx > 1) Alignment.CenterStart else Alignment.Center,
    ) {
        val textModifier = Modifier
            .wrapContentWidth(unbounded = true)
            .graphicsLayer { translationX = -travel.value }
        Text(
            text = text,
            color = color,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            modifier = textModifier.onSizeChanged { contentWidthPx = it.width },
        )
    }
}

@Composable
private fun MiniNowPlayingEqualizer(
    color: Color,
    scale: Float,
    playing: Boolean,
    audioMeterLevelProvider: () -> AudioMeterLevels,
    modifier: Modifier = Modifier,
) {
    val latestLevelProvider by rememberUpdatedState(audioMeterLevelProvider)
    var displayedLevels by remember { mutableStateOf(AudioMeterLevels.Silent) }
    LaunchedEffect(playing) {
        if (!playing) {
            displayedLevels = AudioMeterLevels.Silent
            return@LaunchedEffect
        }
        var previousFrameNanos = 0L
        while (isActive) {
            val frameNanos = withFrameNanos { it }
            val frameDeltaMs = if (previousFrameNanos == 0L) {
                16L
            } else {
                (frameNanos - previousFrameNanos) / 1_000_000L
            }
            previousFrameNanos = frameNanos
            displayedLevels = smoothAudioMeterDisplay(
                previous = displayedLevels,
                target = latestLevelProvider(),
                frameDeltaMs = frameDeltaMs,
            )
        }
    }

    Row(
        modifier = modifier
            .width((15f * scale).dp)
            .height((14f * scale).dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(
            displayedLevels.low,
            displayedLevels.mid,
            displayedLevels.high,
        ).forEach { audioLevel ->
            val level = 0.16f + audioLevel * 0.84f
            Box(
                Modifier
                    .width((3f * scale).dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleY = level
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(
                        color = color.copy(alpha = 0.6f + audioLevel * 0.4f),
                        shape = RoundedCornerShape((2f * scale).dp),
                    )
            )
        }
    }
}

@Composable
private fun MotionAlbumArtwork(
    artwork: Bitmap?,
    motionUrl: String?,
    paper: Color,
    scale: Float,
    playing: Boolean,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (artwork != null) {
            Image(
                bitmap = artwork.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_cover_art),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = paper.copy(alpha = 0.82f),
                modifier = Modifier.size((82f * scale).dp),
            )
        }

        if (!motionUrl.isNullOrBlank()) {
            LoopingMotionArtwork(
                url = motionUrl,
                playing = playing,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LoopingMotionArtwork(
    url: String,
    playing: Boolean,
    fullScreen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    var firstFrameRendered by remember(url) { mutableStateOf(false) }
    var playbackFailed by remember(url) { mutableStateOf(false) }
    var videoAspectRatio by remember(url) { mutableStateOf(1f) }
    val videoAlpha by animateFloatAsState(
        targetValue = if (firstFrameRendered && !playbackFailed) 1f else 0f,
        animationSpec = tween(420),
        label = "motionArtworkReveal",
    )
    val player = remember(url, fullScreen) {
        // Keep enough HLS data ready for a 60 fps rendition without allowing
        // decorative motion art to dominate memory on a 2 GB television.
        val compactLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 10_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 750,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .setTargetBufferBytes((if (fullScreen) 18 else 12) * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val compactTrackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    // No frame-rate window: a minimum of 50 fps used to push
                    // the selector toward a lower-resolution 60 fps ladder
                    // rung. The highest-resolution rendition is chosen and
                    // the decoder runs it at its native rate.
                    .setMaxVideoSize(
                        if (fullScreen) 1920 else 1080,
                        if (fullScreen) 1920 else 1080,
                    )
                    .setMaxVideoBitrate(if (fullScreen) 16_000_000 else 8_000_000)
                    // Full-screen square artwork used to be capped at 720 px
                    // high, forcing a visibly soft rendition onto a 1080p
                    // pane. Start with the best supported 1080p-class stream.
                    .setForceHighestSupportedBitrate(fullScreen)
            )
        }
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setAllowedVideoJoiningTimeMs(1_500)
        ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(MotionArtworkCache.mediaSourceFactory(context))
            .setLoadControl(compactLoadControl)
            .setTrackSelector(compactTrackSelector)
            // Keep the app's 60 Hz presentation instead of switching the
            // television into a 24/30 Hz mode for a decorative square video.
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .build()
            .apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            // AspectCorrectMotionTextureView owns the centred crop. Asking
            // MediaCodec to crop as well would resample the frame twice.
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }

    DisposableEffect(player, url) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio =
                        videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackFailed = true
            }
        }
        // Diagnostics + safety net: log the rendition actually decoded and,
        // only if this TV really drops frames (weak decoder), step down to a
        // 720p-class rendition instead of letting the cover crawl at ~10 fps.
        var heavyDropWindows = 0
        var steppedDown = false
        val analytics = object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                android.util.Log.i(
                    "MotionArtwork",
                    "format ${format.width}x${format.height} @${format.frameRate}fps " +
                        "${format.bitrate / 1000} kbps ${format.sampleMimeType}",
                )
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                val perSecond = droppedFrames * 1000f / elapsedMs.coerceAtLeast(1L)
                android.util.Log.w(
                    "MotionArtwork",
                    "dropped $droppedFrames frames in ${elapsedMs}ms (%.1f/s)".format(perSecond),
                )
                if (perSecond > 10f) heavyDropWindows++ else heavyDropWindows = 0
                if (heavyDropWindows >= 3 && !steppedDown) {
                    steppedDown = true
                    (player.trackSelector as? DefaultTrackSelector)?.let { selector ->
                        selector.setParameters(
                            selector.buildUponParameters()
                                .setMaxVideoSize(1280, 1280)
                                .setMaxVideoBitrate(7_000_000)
                                .setForceHighestSupportedBitrate(false)
                        )
                        android.util.Log.w("MotionArtwork", "stepped down to <=1280px")
                    }
                }
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = playing
        onDispose {
            player.removeListener(listener)
            player.removeAnalyticsListener(analytics)
            player.release()
        }
    }

    // Free the decoder from rendering/buffering work as soon as music pauses.
    LaunchedEffect(player, playing) {
        player.playWhenReady = playing
    }

    if (!playbackFailed) {
        androidx.compose.runtime.key(url) {
            AndroidView(
                factory = { viewContext ->
                    AspectCorrectMotionTextureView(viewContext).also { texture ->
                        texture.isOpaque = true
                        player.setVideoTextureView(texture)
                    }
                },
                // Only update the GPU transform. Rebinding the TextureView can
                // reset the decoder surface and produce visible stutters.
                update = { texture ->
                    texture.contentAspectRatio = videoAspectRatio
                },
                modifier = modifier
                    .alpha(videoAlpha)
                    // No per-frame transform: animating the TextureView every
                    // VSYNC forced the whole masked backdrop to be recomposited
                    // on the GPU and dragged the movie to a few fps on TVs.
                    .graphicsLayer {
                        val baseScale = if (fullScreen) 1.004f else 1.01f
                        scaleX = baseScale
                        scaleY = baseScale
                    },
            )
        }
    }
}

/**
 * TextureView stretches its buffer to the view bounds by default. Motion
 * artwork is often square while the TV pane is slightly portrait, so apply a
 * centred crop matrix that preserves the source pixels' true aspect ratio.
 */
private class AspectCorrectMotionTextureView(
    context: android.content.Context,
) : TextureView(context) {
    var contentAspectRatio: Float = 1f
        set(value) {
            val safeValue = value.takeIf { it.isFinite() && it > 0f } ?: 1f
            if (field == safeValue) return
            field = safeValue
            updateContentTransform()
        }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateContentTransform()
    }

    private fun updateContentTransform() {
        if (width <= 0 || height <= 0) return
        val viewAspectRatio = width.toFloat() / height.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (contentAspectRatio > viewAspectRatio) {
            scaleX = contentAspectRatio / viewAspectRatio
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = viewAspectRatio / contentAspectRatio
        }
        setTransform(
            Matrix().apply {
                setScale(scaleX, scaleY, width / 2f, height / 2f)
            }
        )
    }
}

@Composable
private fun DisplaySyncedLyricsStage(
    lyrics: LyricsState,
    viewModel: MainViewModel,
    accent: Color,
    paper: Color,
    muted: Color,
    scale: Float,
    durationMs: Long,
    writerCredit: String,
    playing: Boolean,
    browseSignal: Int,
    browseDirection: Int,
    modifier: Modifier = Modifier,
) {
    // Keep the display-rate clock inside the lyric subtree. Reading it from
    // the root forced album art and the animated background to recompose for
    // every karaoke frame on entry-level TV hardware.
    val sourcePositionMs by viewModel.audioPositionMs.collectAsState()
    val displayPositionMs = rememberDisplaySyncedPosition(
        sourcePositionMs = sourcePositionMs,
        viewModel = viewModel,
        enabled = lyrics.status == LyricsStatus.SYNCED && playing,
    )
    // Positive delay shows each line earlier, compensating audio that runs ahead.
    val lyricsDelayMs by viewModel.lyricsDelayMs.collectAsState()
    AppleStyleLyricsStage(
        lyrics = lyrics,
        positionMs = displayPositionMs + lyricsDelayMs,
        accent = accent,
        paper = paper,
        muted = muted,
        scale = scale,
        durationMs = durationMs,
        writerCredit = writerCredit,
        playing = playing,
        browseSignal = browseSignal,
        browseDirection = browseDirection,
        modifier = modifier,
    )
}

@Composable
private fun AppleStyleLyricsStage(
    lyrics: LyricsState,
    positionMs: Long,
    accent: Color,
    paper: Color,
    muted: Color,
    scale: Float,
    durationMs: Long,
    writerCredit: String,
    playing: Boolean,
    browseSignal: Int,
    browseDirection: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        if (lyrics.status == LyricsStatus.SYNCED || lyrics.status == LyricsStatus.PLAIN) {
            LyricsRailGlow(
                accent = accent,
                paper = paper,
                scale = scale,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        when (lyrics.status) {
            LyricsStatus.SYNCED -> {
                ScrollableSyncedLyrics(
                    lines = lyrics.lines,
                    positionMs = positionMs,
                    accent = accent,
                    paper = paper,
                    scale = scale,
                    durationMs = durationMs,
                    writerCredit = writerCredit,
                    playing = playing,
                    browseSignal = browseSignal,
                    browseDirection = browseDirection,
                )
            }

            LyricsStatus.PLAIN -> {
                PlainLyrics(
                    lyrics = lyrics.plainLyrics,
                    paper = paper,
                    scale = scale,
                    writerCredit = writerCredit,
                    browseSignal = browseSignal,
                    browseDirection = browseDirection,
                )
            }

            LyricsStatus.LOADING -> IOSLyricsLoadingIndicator(paper, scale)
            LyricsStatus.INSTRUMENTAL -> LargeLyricsMessage(
                stringResource(R.string.lyrics_instrumental), paper, scale
            )
            LyricsStatus.NOT_FOUND -> LargeLyricsMessage(
                stringResource(R.string.lyrics_not_found), muted, scale
            )
            LyricsStatus.ERROR -> LargeLyricsMessage(
                stringResource(R.string.lyrics_error), muted, scale
            )
            LyricsStatus.IDLE -> LargeLyricsMessage(
                stringResource(R.string.lyrics_waiting), muted, scale
            )
        }
    }
}

@Composable
private fun LyricsRailGlow(
    accent: Color,
    paper: Color,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "lyricsRailGlow")
    val breath by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "lyricsRailGlowBreath",
    )
    val glow = mix(accent, paper, 0.18f)
    Box(
        modifier = modifier
            .width((390f * scale).dp)
            .height((188f * scale).dp)
            .graphicsLayer {
                alpha = 0.62f + breath * 0.28f
                scaleX = 0.98f + breath * 0.035f
                scaleY = 0.97f + breath * 0.045f
            }
            .background(
                Brush.radialGradient(
                    0f to glow.copy(alpha = 0.25f),
                    0.38f to glow.copy(alpha = 0.13f),
                    0.72f to accent.copy(alpha = 0.045f),
                    1f to Color.Transparent,
                )
            ),
    )
}

@Composable
private fun ScrollableSyncedLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    accent: Color,
    paper: Color,
    scale: Float,
    durationMs: Long,
    writerCredit: String,
    playing: Boolean,
    browseSignal: Int,
    browseDirection: Int,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val timeline = remember(lines, durationMs, writerCredit) {
        buildLyricsTimeline(lines, durationMs, writerCredit)
    }
    // Move the rail a fraction before the next onset, while the karaoke fill
    // continues to use the unmodified audible clock. This avoids a late jump
    // exactly on the first sung syllable.
    val activeTimelineIndex = timeline.indexOfLast {
        it.startMs <= positionMs + LYRIC_RAIL_LOOKAHEAD_MS
    }
    var browsing by remember(lines) { mutableStateOf(false) }
    // Keep all rows mounted so the next lyric can travel into the live
    // position. Rows outside the current/next window only fade visually.
    val visibleTimeline = timeline
    val activeIndex = activeTimelineIndex
    val upcomingLineIndex = visibleTimeline.indices.firstOrNull { index ->
        index > activeIndex && visibleTimeline[index].line != null
    }

    LaunchedEffect(browseSignal) {
        if (browseSignal == 0 || browseDirection == 0) return@LaunchedEffect
        val startsBrowsing = !browsing
        browsing = true
        if (startsBrowsing) {
            // Wait for LazyColumn to receive the complete timeline before
            // positioning at the live row; Up can then reveal past lyrics.
            withFrameNanos { }
            listState.animateLineToCenter(activeTimelineIndex.coerceAtLeast(0))
        }
        listState.animateScrollBy(
            value = with(density) { (browseDirection * 124f * scale).dp.toPx() },
            animationSpec = tween(170, easing = FastOutSlowInEasing),
        )
    }
    LaunchedEffect(browseSignal) {
        if (browseSignal == 0) return@LaunchedEffect
        delay(3_200)
        browsing = false
    }
    LaunchedEffect(activeTimelineIndex, browsing, visibleTimeline, playing) {
        if (playing && !browsing && activeIndex >= 0) {
            listState.animateLineToCenter(activeIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        contentPadding = PaddingValues(
            top = (250f * scale).dp,
            bottom = (154f * scale).dp,
        ),
        verticalArrangement = Arrangement.spacedBy((19f * scale).dp),
    ) {
        itemsIndexed(
            items = visibleTimeline,
            key = { _, entry -> entry.key },
        ) { index, entry ->
            val previousEntry = visibleTimeline.getOrNull(index - 1)
            val followsInstrumentalGap = previousEntry != null &&
                previousEntry.line == null &&
                previousEntry.writerCredit == null
            val edgeBlur = lyricEdgeBlurFactor(
                listState = listState,
                itemIndex = index,
                scale = scale,
                density = density,
            )
            val isOutsideLiveWindow = !browsing && when {
                entry.line == null -> index != activeIndex
                index < activeIndex -> true
                upcomingLineIndex != null && index > upcomingLineIndex -> true
                else -> false
            }
            when {
                entry.line != null -> ScrollableLyricLine(
                    line = entry.line,
                    lineIndex = index,
                    activeIndex = activeIndex,
                    // Only the active row receives the frame clock. Stable
                    // timestamps let Compose skip every inactive lyric row.
                    positionMs = if (index == activeIndex) positionMs else entry.startMs - 1L,
                    accent = accent,
                    paper = paper,
                    scale = scale,
                    browsing = browsing,
                    isUpcoming = !browsing && index == upcomingLineIndex,
                    topSpacing = when {
                        index == 0 -> 0f
                        followsInstrumentalGap -> 20f
                        else -> 42f
                    },
                    edgeBlur = edgeBlur,
                    isOutsideLiveWindow = isOutsideLiveWindow,
                )

                entry.writerCredit != null -> LyricsWriterCredit(
                    writerCredit = entry.writerCredit,
                    paper = paper,
                    scale = scale,
                    edgeBlur = edgeBlur,
                    isOutsideLiveWindow = isOutsideLiveWindow,
                )

                else -> LyricsGapIndicator(
                    lineIndex = index,
                    activeIndex = activeIndex,
                    positionMs = if (index == activeIndex) positionMs else entry.startMs,
                    startMs = entry.startMs,
                    endMs = entry.endMs,
                    paper = paper,
                    scale = scale,
                    browsing = browsing,
                    playing = playing,
                    edgeBlur = edgeBlur,
                    isOutsideLiveWindow = isOutsideLiveWindow,
                )
            }
        }
    }
}

private fun compactLyricsTimeline(
    timeline: List<LyricsTimelineEntry>,
    activeIndex: Int,
): List<LyricsTimelineEntry> {
    if (timeline.isEmpty()) return emptyList()
    if (activeIndex < 0) return timeline.take(2)
    val nextLyricIndex = timeline.indices.firstOrNull { index ->
        index > activeIndex && timeline[index].line != null
    }
    val endExclusive = (nextLyricIndex ?: activeIndex) + 1
    return timeline.subList(activeIndex, endExclusive)
}

private fun lyricEdgeBlurFactor(
    listState: LazyListState,
    itemIndex: Int,
    scale: Float,
    density: androidx.compose.ui.unit.Density,
): Float {
    val layout = listState.layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { it.index == itemIndex } ?: return 0f
    val itemCenter = item.offset + item.size / 2f
    val distanceToEdge = minOf(
        itemCenter - layout.viewportStartOffset,
        layout.viewportEndOffset - itemCenter,
    )
    val edgeZonePx = with(density) { (96f * scale).dp.toPx() }
    return ((edgeZonePx - distanceToEdge) / edgeZonePx).coerceIn(0f, 1f)
}

/**
 * Dot cues are reserved for long instrumental passages: the introduction and
 * breaks between lyric lines. Short breaths and the quiet outro stay clean.
 */
private const val INSTRUMENTAL_GAP_MINIMUM_MS = 7_000L
private const val LYRIC_GAP_VISUAL_DELAY_MS = 520L
private const val LYRIC_RAIL_LOOKAHEAD_MS = 140L
private const val DOTS_FULL_BRIGHTNESS_HOLD_MS = 90L

private data class LyricsTimelineEntry(
    val key: String,
    val startMs: Long,
    val endMs: Long,
    val line: LyricLine? = null,
    val writerCredit: String? = null,
)

private fun buildLyricsTimeline(
    lines: List<LyricLine>,
    durationMs: Long,
    writerCredit: String,
): List<LyricsTimelineEntry> {
    if (lines.isEmpty()) return emptyList()
    val timeline = mutableListOf<LyricsTimelineEntry>()

    fun addGap(startMs: Long, endMs: Long, afterLyric: Boolean = false) {
        val start = (startMs + if (afterLyric) LYRIC_GAP_VISUAL_DELAY_MS else 0L)
            .coerceAtLeast(0L)
        if (endMs <= start) return
        if (endMs - start < INSTRUMENTAL_GAP_MINIMUM_MS) return
        val previous = timeline.lastOrNull()
        if (previous != null && previous.line == null && start <= previous.endMs + 80L) {
            timeline[timeline.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, endMs))
        } else {
            timeline += LyricsTimelineEntry(
                key = "gap-$start",
                startMs = start,
                endMs = endMs,
            )
        }
    }

    val voicedLines = mergeStandaloneParentheticalLines(
        lines.filter { it.combinedDisplayText().isNotBlank() }
    )
    // A long instrumental introduction gets the same progressive cue as an
    // interlude, while the end of a song remains intentionally quiet.
    voicedLines.firstOrNull()?.let { firstLine ->
        addGap(startMs = 0L, endMs = firstLine.timeMs)
    }
    voicedLines.forEachIndexed { index, line ->
        val nextLine = voicedLines.getOrNull(index + 1)

        val lyricEndMs = lyricAudibleEndMs(line)
        timeline += LyricsTimelineEntry(
            key = "line-${line.timeMs}-$index",
            startMs = line.timeMs,
            endMs = lyricEndMs,
            line = line,
        )
        // Keep long instrumental breaks between lyric lines, but not an outro.
        nextLine?.let { addGap(startMs = lyricEndMs, endMs = it.timeMs, afterLyric = true) }
    }
    val lastLyric = timeline.lastOrNull { it.line != null }
    if (lastLyric != null && writerCredit.isNotBlank()) {
        // Keep the credit as the final rail item, so it glides into the live
        // slot after the last sung line instead of appearing as an overlay.
        timeline += LyricsTimelineEntry(
            key = "writer-credit-${lastLyric.endMs}",
            startMs = lastLyric.endMs + 720L,
            endMs = durationMs.coerceAtLeast(lastLyric.endMs + 720L),
            writerCredit = writerCredit.trim(),
        )
    }
    return timeline
}

private fun lyricAudibleEndMs(line: LyricLine): Long {
    if (line.exactWordTiming) {
        val lastWordEnd = (line.words + line.backgroundWords)
            .maxOfOrNull { it.endMs }
            ?: line.timeMs
        // Some providers give a line duration that extends beyond their last
        // word marker. Respect that tail so an instrumental cue cannot cover
        // a visibly unfinished lyric.
        return maxOf(lastWordEnd, line.endMs ?: line.timeMs)
    }
    line.endMs?.takeIf { it > line.timeMs }?.let { return it }
    // Untimed/LRC text should err on the side of retaining the lyric longer;
    // an early gap cue is far more distracting than a slightly later one.
    val estimatedDurationMs = (
        (line.words.size + line.backgroundWords.size).coerceAtLeast(1) * 620L
        )
        .coerceIn(1_900L, 9_000L)
    return line.timeMs + estimatedDurationMs
}

internal enum class LyricAsidePlacement { ABOVE, BELOW }

internal data class LyricDisplayParts(
    val mainText: String,
    val mainWords: List<io.github.jqssun.airplay.lyrics.LyricWord>,
    val asideText: String? = null,
    val asideWords: List<io.github.jqssun.airplay.lyrics.LyricWord> = emptyList(),
    val asidePlacement: LyricAsidePlacement = LyricAsidePlacement.ABOVE,
)

private const val STANDALONE_ASIDE_ATTACH_WINDOW_MS = 1_800L
private val parentheticalLyricPattern = Regex(
    "[（(]([^（）()]*)[）)](?:\\s*([x×]\\s*\\d+))?",
    RegexOption.IGNORE_CASE,
)
private val standaloneParentheticalPattern = Regex(
    "^\\s*[（(].+[）)]\\s*(?:[x×]\\s*\\d+)?\\s*$",
    RegexOption.IGNORE_CASE,
)
private val parentheticalMarkPattern = Regex("[（）()]")

/**
 * Apple lyric data can expose a backing vocal as a separate parenthesized row,
 * for example `（And watches everything）`. Attach a row like that to the lyric
 * immediately before it when their timing touches, or to the next lyric as an
 * above-line aside when it arrives independently.
 */
internal fun mergeStandaloneParentheticalLines(lines: List<LyricLine>): List<LyricLine> {
    if (lines.isEmpty()) return emptyList()
    val merged = mutableListOf<LyricLine>()
    var pendingAbove: LyricLine? = null

    lines.forEach { line ->
        if (standaloneParentheticalPattern.matches(line.text)) {
            val previous = merged.lastOrNull()
            val attachesBelow = previous != null &&
                line.timeMs >= previous.timeMs &&
                line.timeMs <= lyricAudibleEndMs(previous) + STANDALONE_ASIDE_ATTACH_WINDOW_MS
            if (attachesBelow) {
                merged[merged.lastIndex] = mergeLyricDisplayLines(previous, line)
            } else {
                pendingAbove?.let(merged::add)
                pendingAbove = line
            }
        } else {
            val above = pendingAbove
            if (above != null) {
                merged += mergeLyricDisplayLines(above, line)
                pendingAbove = null
            } else {
                merged += line
            }
        }
    }
    pendingAbove?.let(merged::add)
    return merged
}

private fun mergeLyricDisplayLines(first: LyricLine, second: LyricLine): LyricLine {
    val firstIsAside = standaloneParentheticalPattern.matches(first.text)
    val main = if (firstIsAside) second else first
    val backing = if (firstIsAside) first else second
    val cleanedBackingText = backing.text
        .replace(parentheticalMarkPattern, "")
        .trim()
    val backgroundText = listOf(main.backgroundText, cleanedBackingText)
        .filter(String::isNotBlank)
        .joinToString(" ")
    return main.copy(
        timeMs = minOf(first.timeMs, second.timeMs),
        backgroundText = backgroundText,
        backgroundWords = main.backgroundWords + backing.words,
        exactWordTiming = main.exactWordTiming || backing.exactWordTiming,
        asideWordRange = null,
        endMs = maxOf(lyricAudibleEndMs(first), lyricAudibleEndMs(second)),
        alignment = main.alignment,
    )
}

internal fun splitLyricDisplayParts(line: LyricLine): LyricDisplayParts {
    if (line.backgroundText.isNotBlank()) {
        val mainStart = line.words.firstOrNull()?.startMs ?: line.timeMs
        val backgroundStart = line.backgroundWords.firstOrNull()?.startMs ?: line.timeMs
        return LyricDisplayParts(
            mainText = line.text,
            mainWords = line.words,
            asideText = line.backgroundText,
            asideWords = line.backgroundWords,
            asidePlacement = if (
                line.text.isBlank() ||
                backgroundStart + BACKGROUND_VOCAL_LEAD_THRESHOLD_MS < mainStart
            ) {
                LyricAsidePlacement.ABOVE
            } else {
                LyricAsidePlacement.BELOW
            },
        )
    }
    val matches = parentheticalLyricPattern.findAll(line.text).toList()
    if (matches.isEmpty()) return LyricDisplayParts(line.text, line.words)

    val asideText = matches.joinToString(" ") { match ->
        val vocal = match.groupValues[1].trim()
        val repeat = match.groupValues.getOrNull(2)
            .orEmpty()
            .replace(Regex("\\s+"), "")
            .replace('x', '×', ignoreCase = true)
        listOf(vocal, repeat).filter { it.isNotBlank() }.joinToString(" ")
    }
        .replace(Regex("\\s+"), " ")
        .trim()
    val mainText = parentheticalLyricPattern.replace(line.text, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (asideText.isBlank()) return LyricDisplayParts(line.text, line.words)

    var searchStart = 0
    val asideRanges = matches.map { it.range }
    val mainWords = mutableListOf<io.github.jqssun.airplay.lyrics.LyricWord>()
    val asideWords = mutableListOf<io.github.jqssun.airplay.lyrics.LyricWord>()
    line.words.forEachIndexed { wordIndex, word ->
        val matchStart = line.text.indexOf(word.text, startIndex = searchStart, ignoreCase = true)
        val wordStart = if (matchStart >= 0) matchStart else searchStart
        val wordEnd = (wordStart + word.text.length).coerceAtMost(line.text.length)
        searchStart = wordEnd
        val isAside = line.asideWordRange?.contains(wordIndex) ?: asideRanges.any { range ->
            wordStart < range.last + 1 && wordEnd > range.first
        }
        if (isAside) {
            val cleaned = word.text.replace(parentheticalMarkPattern, "")
            if (cleaned.isNotBlank()) asideWords += word.copy(text = cleaned)
        } else {
            mainWords += word
        }
    }

    return LyricDisplayParts(
        mainText = mainText,
        mainWords = mainWords,
        asideText = asideText,
        asideWords = asideWords,
        asidePlacement = if (
            line.text.substring(0, matches.first().range.first).isBlank()
        ) {
            LyricAsidePlacement.ABOVE
        } else {
            LyricAsidePlacement.BELOW
        },
    )
}

private const val BACKGROUND_VOCAL_LEAD_THRESHOLD_MS = 240L

/**
 * Lyric text only (not the surrounding interface). The original 50sp rows were
 * oversized on a TV; this keeps them large enough to read from a sofa.
 */
private const val LyricTextScale = 0.8f

@Composable
private fun ScrollableLyricLine(
    line: LyricLine,
    lineIndex: Int,
    activeIndex: Int,
    positionMs: Long,
    accent: Color,
    paper: Color,
    scale: Float,
    browsing: Boolean,
    isUpcoming: Boolean,
    topSpacing: Float,
    edgeBlur: Float,
    isOutsideLiveWindow: Boolean,
) {
    val displayParts = remember(line) { splitLyricDisplayParts(line) }
    val isActive = lineIndex == activeIndex
    val mainWordByWord = line.exactWordTiming &&
        lyricWordsMatchLine(displayParts.mainText, displayParts.mainWords)
    val asideWordByWord = line.exactWordTiming &&
        displayParts.asideText?.let { aside ->
            lyricWordsMatchLine(aside, displayParts.asideWords)
        } == true
    val mainLayoutText = remember(
        displayParts.mainText,
        displayParts.mainWords,
        mainWordByWord,
    ) {
        if (mainWordByWord) {
            wordTimedLyricLayoutText(displayParts.mainText, displayParts.mainWords)
        } else {
            displayParts.mainText
        }
    }
    val isTrailing = line.alignment == LyricAlignment.TRAILING
    val textAlign = if (isTrailing) TextAlign.End else TextAlign.Start
    val distance = abs(lineIndex - activeIndex)
    val emphasis by animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            isUpcoming -> 0.34f
            browsing -> 0.78f
            distance == 1 -> 0.34f
            distance == 2 -> 0.25f
            else -> 0.18f
        },
        animationSpec = spring(
            dampingRatio = 1f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "lyricLineEmphasis",
    )
    val sizeScale by animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            isUpcoming -> 0.94f
            browsing -> 0.96f
            else -> 0.92f
        },
        animationSpec = spring(
            dampingRatio = 1f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "lyricLineScale",
    )
    val logicalBlur = when {
        isOutsideLiveWindow && !browsing -> 0f
        browsing || isActive -> 0f
        isUpcoming -> 1.45f
        distance == 1 -> 1.1f
        distance == 2 -> 2.8f
        distance == 3 -> 4.8f
        else -> 6.5f
    }
    val blurRadius by animateFloatAsState(
        targetValue = maxOf(logicalBlur, edgeBlur * 3.4f),
        animationSpec = tween(260, easing = AppleLyricRailEasing),
        label = "lyricLineBlur",
    )
    val edgeFade = 1f - edgeBlur * 0.34f
    val liveWindowAlpha by animateFloatAsState(
        targetValue = if (isOutsideLiveWindow) 0f else 1f,
        animationSpec = tween(420, easing = AppleLyricRailEasing),
        label = "lyricLiveWindowAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                // Spacing belongs to the timeline entry, not to its active
                // state. Keeping it immutable prevents the next row from
                // jumping upward by 48 dp at the exact lyric hand-off.
                top = (topSpacing * scale).dp
            )
            .graphicsLayer {
                alpha = emphasis * edgeFade * liveWindowAlpha
                scaleX = sizeScale
                scaleY = sizeScale
                transformOrigin = TransformOrigin(if (isTrailing) 1f else 0f, 0.5f)
            }
            .then(
                if (blurRadius > 0.01f) Modifier.blur((blurRadius * scale).dp) else Modifier
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isTrailing) Alignment.End else Alignment.Start,
        ) {
            if (displayParts.asidePlacement == LyricAsidePlacement.ABOVE) {
                ParentheticalLyric(
                    parts = displayParts,
                    active = isActive,
                    positionMs = positionMs,
                    accent = accent,
                    paper = paper,
                    scale = scale,
                    alignment = line.alignment,
                    wordByWord = asideWordByWord,
                )
            }

            if (displayParts.mainText.isNotBlank()) {
                if (isActive && mainWordByWord) {
                    ProgressiveWordLine(
                        text = displayParts.mainText,
                        words = displayParts.mainWords,
                        positionMs = positionMs,
                        accent = accent,
                        paper = paper,
                        scale = scale,
                        textAlign = textAlign,
                    )
                } else {
                    Text(
                        mainLayoutText,
                        color = paper,
                        // Active and inactive rows share identical measured
                        // typography. Alpha, blur and a paint-only layer carry
                        // the emphasis without changing line wrapping.
                        fontSize = (50f * LyricTextScale * scale).sp,
                        lineHeight = (60f * LyricTextScale * scale).sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = textAlign,
                        style = TextStyle(textDirection = TextDirection.Content),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Romanization first (how it is sung), then the translation (what it means).
            for (extra in listOf(line.romanization, line.translation)) {
                if (extra.isBlank()) continue
                Spacer(Modifier.height((6f * scale).dp))
                Text(
                    extra,
                    color = paper.copy(alpha = if (isActive) 0.62f else 0.42f),
                    fontSize = (26f * LyricTextScale * scale).sp,
                    lineHeight = (33f * LyricTextScale * scale).sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = textAlign,
                    style = TextStyle(textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (displayParts.asidePlacement == LyricAsidePlacement.BELOW) {
                ParentheticalLyric(
                    parts = displayParts,
                    active = isActive,
                    positionMs = positionMs,
                    accent = accent,
                    paper = paper,
                    scale = scale,
                    alignment = line.alignment,
                    wordByWord = asideWordByWord,
                )
            }
        }
    }
}

@Composable
private fun ParentheticalLyric(
    parts: LyricDisplayParts,
    active: Boolean,
    positionMs: Long,
    accent: Color,
    paper: Color,
    scale: Float,
    alignment: LyricAlignment,
    wordByWord: Boolean,
) {
    val aside = parts.asideText ?: return
    val stableAside = remember(aside, parts.asideWords, wordByWord) {
        if (wordByWord) {
            wordTimedLyricLayoutText(aside, parts.asideWords)
        } else {
            aside
        }
    }
    val textAlign = if (alignment == LyricAlignment.TRAILING) TextAlign.End else TextAlign.Start
    if (parts.asidePlacement == LyricAsidePlacement.BELOW) {
        Spacer(Modifier.height((9f * scale).dp))
    }
    if (active && wordByWord) {
        ProgressiveWordLine(
            text = aside,
            words = parts.asideWords,
            positionMs = positionMs,
            accent = accent,
            paper = paper,
            scale = scale,
            fontSize = 27f * LyricTextScale,
            lineHeight = 34f * LyricTextScale,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            textAlign = textAlign,
            activeAlpha = 0.48f,
            futureAlpha = 0.18f,
            auraMultiplier = 0.58f,
        )
    } else {
        Text(
            text = stableAside,
            color = paper.copy(alpha = if (active) 0.48f else 0.35f),
            fontSize = (27f * LyricTextScale * scale).sp,
            lineHeight = (34f * LyricTextScale * scale).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            style = TextStyle(textDirection = TextDirection.Content),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (parts.asidePlacement == LyricAsidePlacement.ABOVE) {
        Spacer(Modifier.height((7f * scale).dp))
    }
}

@Composable
private fun LyricsWriterCredit(
    writerCredit: String,
    paper: Color,
    scale: Float,
    edgeBlur: Float,
    isOutsideLiveWindow: Boolean,
) {
    val visibility by animateFloatAsState(
        targetValue = if (isOutsideLiveWindow) 0f else 1f,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "lyricsWriterCreditVisibility",
    )
    val blurRadius by animateFloatAsState(
        targetValue = edgeBlur * 2.4f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "lyricsWriterCreditBlur",
    )
    val writtenByLabel = stringResource(R.string.lyrics_written_by)
    val styledCredit = remember(writerCredit, writtenByLabel) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Black)) {
                append(writtenByLabel)
            }
            append(" ")
            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                append(writerCredit)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = visibility * (1f - edgeBlur * 0.26f) }
            .then(
                if (blurRadius > 0.01f) Modifier.blur((blurRadius * scale).dp) else Modifier
            ),
    ) {
        // Preserve the open space used by Apple Music between the final lyric
        // and its Composition & Lyrics credit.
        Spacer(Modifier.height((42f * scale).dp))
        Text(
            text = styledCredit,
            color = paper,
            fontSize = (29f * scale).sp,
            lineHeight = (39f * scale).sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LyricsGapIndicator(
    lineIndex: Int,
    activeIndex: Int,
    positionMs: Long,
    startMs: Long,
    endMs: Long,
    paper: Color,
    scale: Float,
    browsing: Boolean,
    playing: Boolean,
    edgeBlur: Float,
    isOutsideLiveWindow: Boolean,
) {
    val isActive = lineIndex == activeIndex
    val distance = abs(lineIndex - activeIndex)
    val emphasis by animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            browsing -> 0.82f
            distance == 1 -> 0.58f
            else -> 0.3f
        },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "lyricsGapEmphasis",
    )
    val logicalBlur = if (browsing || isActive) 0f else if (distance == 1) 1.2f else 3.8f
    val blurRadius by animateFloatAsState(
        targetValue = maxOf(logicalBlur, edgeBlur * 3.2f),
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "lyricsGapBlur",
    )
    val liveWindowAlpha by animateFloatAsState(
        targetValue = if (isOutsideLiveWindow) 0f else 1f,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "lyricsGapLiveWindowAlpha",
    )
    val dotBreath = if (playing && isActive) {
        val transition = rememberInfiniteTransition(label = "lyricsGapDots")
        val breath by transition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.035f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_450, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "lyricsGapDotBreath",
        )
        breath
    } else {
        1f
    }
    val elapsedMs = (positionMs - startMs).coerceAtLeast(0L)
    val gapDurationMs = (endMs - startMs).coerceAtLeast(INSTRUMENTAL_GAP_MINIMUM_MS)
    val entrance = FastOutSlowInEasing.transform((elapsedMs / 420f).coerceIn(0f, 1f))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((64f * scale).dp)
            .graphicsLayer {
                alpha = emphasis * liveWindowAlpha * (1f - edgeBlur * 0.34f)
            }
            .then(
                if (blurRadius > 0.01f) Modifier.blur((blurRadius * scale).dp) else Modifier
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(
            Modifier
                .size((96f * scale).dp, (34f * scale).dp)
        ) {
            repeat(3) { index ->
                // The dots fill in three consecutive stages across the break.
                // All three are fully bright for the final few display frames
                // before the sung line takes over.
                val activation = stagedDotProgress(elapsedMs, gapDurationMs, index)
                val breath = if (playing && isActive && activation > 0.96f) dotBreath else 1f
                // Keep breathing in the radius only. Multiplying alpha by the
                // breath phase made a completed dot look dim at hand-off.
                val alpha = ((0.16f + activation * 0.84f) * entrance)
                    .coerceIn(0f, 1f)
                val radius = size.height * (0.23f + activation * 0.16f) * breath
                drawCircle(
                    color = paper.copy(alpha = alpha),
                    radius = radius,
                    center = androidx.compose.ui.geometry.Offset(
                        x = size.width * (0.14f + index * 0.36f),
                        y = size.height / 2f,
                    ),
                )
            }
        }
    }
}

internal fun stagedDotProgress(elapsedMs: Long, gapDurationMs: Long, index: Int): Float {
    val safeIndex = index.coerceIn(0, 2)
    val duration = gapDurationMs.coerceAtLeast(INSTRUMENTAL_GAP_MINIMUM_MS)
    val fullBrightnessAtMs = (
        duration - LYRIC_RAIL_LOOKAHEAD_MS - DOTS_FULL_BRIGHTNESS_HOLD_MS
    ).coerceAtLeast(1_000L)
    val stageDurationMs = (fullBrightnessAtMs / 3L).coerceAtLeast(1L)
    val startMs = stageDurationMs * safeIndex
    val endMs = if (safeIndex == 2) {
        fullBrightnessAtMs
    } else {
        stageDurationMs * (safeIndex + 1L)
    }
    val progress = (
        (elapsedMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1L)
        ).coerceIn(0f, 1f)
    return FastOutSlowInEasing.transform(progress)
}

/*
private fun stalePlaceholder(
    targetValue: Float,
) = targetValue
*/

/*
    val blurRadius by animateFloatAsState(
        targetValue = if (browsing || isActive) {
            0f
        } else {
            when (distance) {
                1 -> 1.4f
                2 -> 3.6f
                3 -> 6.4f
                else -> 9f
            }
        },
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "lyricLineBlur",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = if (isUpcoming) {
                    (if (hasGapBefore) 20f else 48f) * scale
                } else {
                    0f
                }.dp
            )
            .graphicsLayer {
                alpha = emphasis
                scaleX = sizeScale
                scaleY = sizeScale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .then(
                if (blurRadius > 0.01f) Modifier.blur((blurRadius * scale).dp) else Modifier
            ),
    ) {
        if (isActive) {
            ProgressiveWordLine(
                line = line,
                positionMs = positionMs,
                accent = accent,
                paper = paper,
                scale = scale,
            )
        } else {
            Text(
                line.text.ifBlank { "♪" },
                color = paper,
                fontSize = (46f * LyricTextScale * scale).sp,
                lineHeight = (56f * LyricTextScale * scale).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LyricsGapIndicator(
    lineIndex: Int,
    activeIndex: Int,
    positionMs: Long,
    endMs: Long,
    paper: Color,
    scale: Float,
    browsing: Boolean,
) {
    val isActive = lineIndex == activeIndex
    val distance = abs(lineIndex - activeIndex)
    val emphasis by animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            browsing -> 0.82f
            distance == 1 -> 0.58f
            else -> 0.3f
        },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "lyricsGapEmphasis",
    )
    val blurRadius by animateFloatAsState(
        targetValue = if (browsing || isActive) 0f else if (distance == 1) 1.2f else 3.8f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "lyricsGapBlur",
    )
    val transition = rememberInfiniteTransition(label = "lyricsGapDots")
    val middleDotBreath by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "lyricsGapMiddleDotBreath",
    )
    val reachesNextLyric = isActive && endMs > positionMs && endMs - positionMs <= 300L
    val arrivalFade by animateFloatAsState(
        targetValue = if (reachesNextLyric) 0f else 1f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "lyricsGapArrivalFade",
    )
    // The reference waiting state keeps the first two dots illuminated. The
    // trailing dot fills only as the next lyric approaches, then all dots fade.
    val remainingMs = (endMs - positionMs).coerceAtLeast(0L)
    val trailingDotProgress = FastOutSlowInEasing.transform(
        ((2_400L - remainingMs).toFloat() / 2_400f).coerceIn(0f, 1f)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((46f * scale).dp)
            .graphicsLayer { alpha = emphasis * arrivalFade }
            .then(
                if (blurRadius > 0.01f) Modifier.blur((blurRadius * scale).dp) else Modifier
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.size((57f * scale).dp, (18f * scale).dp)) {
            repeat(3) { index ->
                val alpha = when (index) {
                    0 -> 1f
                    1 -> 0.9f * middleDotBreath
                    else -> 0.24f + trailingDotProgress * 0.76f
                }
                drawCircle(
                    color = paper.copy(alpha = alpha),
                    radius = size.height * 0.36f,
                    center = androidx.compose.ui.geometry.Offset(
                        x = size.width * (0.16f + index * 0.34f),
                        y = size.height / 2f,
                    ),
                )
            }
        }
    }
}

*/

@Composable
private fun PlainLyrics(
    lyrics: String,
    paper: Color,
    scale: Float,
    writerCredit: String,
    browseSignal: Int,
    browseDirection: Int,
) {
    val lines = remember(lyrics) { lyrics.lines() }
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    LaunchedEffect(lyrics) {
        // A new unsynchronised song must always begin at its first lyric,
        // even if the viewer had scrolled deep into the previous song.
        listState.scrollToItem(0)
    }
    LaunchedEffect(browseSignal) {
        if (browseSignal == 0 || browseDirection == 0) return@LaunchedEffect
        listState.animateScrollBy(
            value = with(density) { (browseDirection * 124f * scale).dp.toPx() },
            animationSpec = tween(170, easing = FastOutSlowInEasing),
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        contentPadding = PaddingValues(vertical = (38f * scale).dp),
        verticalArrangement = Arrangement.spacedBy((9f * scale).dp),
    ) {
        itemsIndexed(lines) { _, line ->
            if (line.isBlank()) {
                Spacer(Modifier.height((20f * scale).dp))
            } else {
                Text(
                    text = line,
                    color = paper.copy(alpha = 0.9f),
                    fontSize = (35f * LyricTextScale * scale).sp,
                    lineHeight = (45f * LyricTextScale * scale).sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(textDirection = TextDirection.Content),
                )
            }
        }
        if (writerCredit.isNotBlank()) {
            item(key = "writer-credit") {
                LyricsWriterCredit(
                    writerCredit = writerCredit,
                    paper = paper,
                    scale = scale,
                    edgeBlur = 0f,
                    isOutsideLiveWindow = false,
                )
            }
        }
    }
}

private suspend fun LazyListState.animateLineToCenter(index: Int) {
    // Adjacent lyric rows are already visible. Aligning them to the top first
    // caused two consecutive animations and made the rail appear late.
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        scrollToItem(index)
        withFrameNanos { }
    }
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    val itemCenter = item.offset + (item.size / 2)
    val delta = itemCenter - viewportCenter
    if (abs(delta) > 2) {
        animateScrollBy(
            value = delta.toFloat(),
            // A single bounded glide is easier to follow than a spring whose
            // target moves as LazyColumn measures a newly active lyric.
            animationSpec = tween(
                durationMillis = 520,
                easing = AppleLyricRailEasing,
            ),
        )
    }
}

@Composable
private fun ProgressiveWordLine(
    text: String,
    words: List<io.github.jqssun.airplay.lyrics.LyricWord>,
    positionMs: Long,
    accent: Color,
    paper: Color,
    scale: Float,
    fontSize: Float = 50f * LyricTextScale,
    lineHeight: Float = 60f * LyricTextScale,
    fontWeight: FontWeight = FontWeight.Black,
    maxLines: Int = 3,
    textAlign: TextAlign = TextAlign.Start,
    activeAlpha: Float = 0.98f,
    futureAlpha: Float = 0.34f,
    auraMultiplier: Float = 1f,
) {
    // Only a complete provider timeline enters this renderer. The immutable
    // layout string is shared by every paint layer, so karaoke never changes
    // wrapping as a word becomes active.
    val renderWords = remember(text, words) {
        words.takeIf { lyricWordsMatchLine(text, it) }.orEmpty()
    }
    val renderedText = remember(text, renderWords) {
        wordTimedLyricLayoutText(text, renderWords).ifBlank { "♪" }
    }
    val wordOffsets = remember(renderWords) {
        IntArray(renderWords.size + 1).also { offsets ->
            renderWords.forEachIndexed { index, word ->
                offsets[index + 1] = offsets[index] + word.text.length
            }
        }
    }
    val activeIndex = renderWords.indexOfLast { word ->
        positionMs >= word.startMs && positionMs < word.endMs
    }
    val activeWord = renderWords.getOrNull(activeIndex)
    val activeProgress = activeWord?.let { lyricWordProgress(it, positionMs) } ?: 0f
    val activeDurationMs = activeWord?.let { (it.endMs - it.startMs).coerceAtLeast(1L) } ?: 1L
    val activeSlice = activeWord?.let { progressiveTextSlice(it.text, activeProgress) }
    val activeBlend = activeSlice?.let {
        AppleLyricEasing.transform(it.blendFraction)
    } ?: 0f
    val activeLocalProgress = if (activeDurationMs < 360L) {
        AppleLyricEasing.transform(activeProgress)
    } else {
        activeBlend
    }
    val shortSyllable = activeDurationMs < 360L
    val activeSustainedAura = lyricSustainedWordAura(activeProgress, activeDurationMs)
    val activeGlyphScale = lyricHighlightGlyphScale(activeLocalProgress, activeDurationMs)
    val activeAuraStrength = (
        lyricHighlightHeadAura(
            graphemeProgress = activeLocalProgress,
            wordProgress = activeProgress,
            durationMs = activeDurationMs,
        ) * auraMultiplier
        ).coerceIn(0f, 1f)
    val density = LocalDensity.current
    val durationAuraWeight = ((activeDurationMs - 650L).toFloat() / 1_850f)
        .coerceIn(0f, 1f)
    val auraBlurPx = with(density) {
        ((5.8f + durationAuraWeight * 3.2f) * scale).dp.toPx()
    }
    val activeWordStart = wordOffsets.getOrElse(activeIndex) { 0 }
    val activeWordEnd = wordOffsets.getOrElse(activeIndex + 1) { activeWordStart }
    val visibleActiveWordEnd = activeWord?.text
        ?.indexOfLast { !it.isWhitespace() }
        ?.takeIf { it >= 0 }
        ?.let { activeWordStart + it + 1 }
        ?: activeWordEnd
    val activeGraphemeRange = activeWord?.let { word ->
        if (shortSyllable) {
            LyricHighlightGraphemeRange(
                start = activeWordStart,
                endExclusive = visibleActiveWordEnd,
            ).takeIf { it.endExclusive > it.start }
        } else {
            lyricHighlightGraphemeRange(
                wordText = word.text,
                wordStartOffset = activeWordStart,
                progress = activeProgress,
            )
        }
    }
    val activeWordRange = activeWord?.let {
        LyricHighlightGraphemeRange(
            start = activeWordStart,
            endExclusive = visibleActiveWordEnd,
        ).takeIf { range -> range.endExclusive > range.start }
    }
    val firstFutureIndex = renderWords.indexOfFirst { positionMs < it.startMs }
        .takeIf { it >= 0 }
        ?: renderWords.size
    val solidEnd = when {
        activeIndex < 0 -> wordOffsets.getOrElse(firstFutureIndex) { renderedText.length }
        shortSyllable -> activeWordStart
        else -> (activeWordStart + (activeSlice?.solidEnd ?: 0))
    }.coerceIn(0, renderedText.length)
    val headMix = if (shortSyllable) {
        AppleLyricEasing.transform(activeProgress)
    } else {
        activeBlend
    }.coerceIn(0f, 1f)

    var textLayout by remember(renderedText, fontSize, lineHeight, scale, textAlign) {
        mutableStateOf<TextLayoutResult?>(null)
    }
    val activeColor = remember(paper, activeAlpha) { paper.copy(alpha = activeAlpha) }
    val futureColor = remember(paper, futureAlpha) { paper.copy(alpha = futureAlpha) }
    val baseLayer = remember(
        renderedText,
        solidEnd,
        activeColor,
        futureColor,
    ) {
        buildAnnotatedString {
            append(renderedText)
            if (length > 0) {
                addStyle(SpanStyle(color = futureColor), 0, length)
                if (solidEnd > 0) {
                    addStyle(SpanStyle(color = activeColor), 0, solidEnd)
                }
            }
        }
    }
    val headLayer = remember(
        renderedText,
        activeGraphemeRange,
        activeColor,
        accent,
        auraBlurPx,
    ) {
        buildAnnotatedString {
            append(renderedText)
            if (length > 0) addStyle(SpanStyle(color = Color.Transparent), 0, length)
            activeGraphemeRange?.takeIf {
                it.start >= 0 && it.endExclusive <= length && it.endExclusive > it.start
            }?.let { range ->
                addStyle(
                    SpanStyle(
                        color = activeColor,
                        shadow = Shadow(
                            color = mix(accent, Color.White, 0.84f).copy(alpha = 0.48f),
                            offset = androidx.compose.ui.geometry.Offset.Zero,
                            blurRadius = auraBlurPx * 0.72f,
                        ),
                    ),
                    range.start,
                    range.endExclusive,
                )
            }
        }
    }
    val auraLayer = remember(
        renderedText,
        activeWordRange,
        accent,
        auraBlurPx,
    ) {
        buildAnnotatedString {
            append(renderedText)
            if (length > 0) addStyle(SpanStyle(color = Color.Transparent), 0, length)
            activeWordRange?.takeIf {
                it.start >= 0 && it.endExclusive <= length && it.endExclusive > it.start
            }?.let { range ->
                addStyle(
                    SpanStyle(
                        color = Color.Transparent,
                        shadow = Shadow(
                            color = mix(accent, Color.White, 0.9f).copy(alpha = 0.72f),
                            offset = androidx.compose.ui.geometry.Offset.Zero,
                            blurRadius = auraBlurPx,
                        ),
                    ),
                    range.start,
                    range.endExclusive,
                )
            }
        }
    }
    val auraLayerAlpha = (
        activeAuraStrength * (0.34f + activeSustainedAura * 0.32f)
        ).coerceIn(0f, 0.66f)

    val layerTextStyle = TextStyle(
        paper,
        fontSize = (fontSize * scale).sp,
        lineHeight = (lineHeight * scale).sp,
        fontWeight = fontWeight,
        textAlign = textAlign,
        textDirection = TextDirection.Content,
    )

    Box(Modifier.fillMaxWidth()) {
        Text(
            text = baseLayer,
            style = layerTextStyle,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                val current = textLayout
                if (
                    current == null ||
                    current.size != result.size ||
                    current.lineCount != result.lineCount
                ) {
                    textLayout = result
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (activeGraphemeRange != null && headMix > 0.001f) {
            Text(
                text = headLayer,
                style = layerTextStyle,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = headMix }
                    .scaledLyricGlyphLayer(
                        textLayout = textLayout,
                        range = activeGraphemeRange,
                        desiredScale = activeGlyphScale,
                    ),
            )
        }
        if (activeWordRange != null && auraLayerAlpha > 0.001f) {
            Text(
                text = auraLayer,
                style = layerTextStyle,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = auraLayerAlpha }
                    .scaledLyricGlyphLayer(
                        textLayout = textLayout,
                        range = activeWordRange,
                        desiredScale = 1f + (activeGlyphScale - 1f) * 0.34f,
                    ),
            )
        }
    }
}

private fun Modifier.scaledLyricGlyphLayer(
    textLayout: TextLayoutResult?,
    range: LyricHighlightGraphemeRange?,
    desiredScale: Float,
): Modifier = drawWithContent {
    val contentScope = this
    val layout = textLayout
    if (
        layout == null ||
        range == null ||
        range.start < 0 ||
        range.endExclusive > layout.layoutInput.text.length ||
        range.endExclusive <= range.start ||
        desiredScale <= 1.001f
    ) {
        drawContent()
        return@drawWithContent
    }

    val glyphPath = layout.getPathForRange(range.start, range.endExclusive)
    val bounds = glyphPath.getBounds()
    val safeScale = boundedLyricGlyphScale(
        desiredScale = desiredScale,
        left = bounds.left,
        top = bounds.top,
        right = bounds.right,
        bottom = bounds.bottom,
        width = size.width,
        height = size.height,
    )
    if (bounds.width <= 0f || bounds.height <= 0f || safeScale <= 1.001f) {
        drawContent()
        return@drawWithContent
    }

    withTransform({
        this.scale(
            scaleX = safeScale,
            scaleY = safeScale,
            pivot = bounds.center,
        )
    }) {
        contentScope.drawContent()
    }
}

@Composable
private fun IOSLyricsLoadingIndicator(
    color: Color,
    scale: Float,
) {
    val transition = rememberInfiniteTransition(label = "iosLyricsSpinner")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "iosLyricsSpinnerPhase",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size((34f * scale).dp)) {
            val pivot = center
            repeat(12) { index ->
                val distance = (phase - index + 12f) % 12f
                val alpha = 0.15f + (1f - distance / 12f) * 0.85f
                rotate(index * 30f, pivot = pivot) {
                    drawLine(
                        color = color.copy(alpha = alpha),
                        start = androidx.compose.ui.geometry.Offset(pivot.x, size.height * 0.12f),
                        end = androidx.compose.ui.geometry.Offset(pivot.x, size.height * 0.31f),
                        strokeWidth = size.width * 0.09f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberDisplaySyncedPosition(
    sourcePositionMs: Long,
    viewModel: MainViewModel,
    enabled: Boolean,
): Long {
    val latestFallback by rememberUpdatedState(sourcePositionMs)
    var renderedPosition by remember { mutableLongStateOf(sourcePositionMs) }

    LaunchedEffect(viewModel, enabled) {
        if (!enabled) {
            renderedPosition = latestFallback
            return@LaunchedEffect
        }
        var lastFrameNanos = 0L
        var lastSampleNanos = 0L
        var sampledPositionMs = latestFallback
        while (isActive) {
            withFrameNanos { frameNanos ->
                if (
                    lastSampleNanos == 0L ||
                    frameNanos - lastSampleNanos >= LYRIC_CLOCK_SAMPLE_INTERVAL_NS
                ) {
                    sampledPositionMs = viewModel.sampleAudioPositionMs()
                        .takeIf { it >= 0L }
                        ?: latestFallback
                    lastSampleNanos = frameNanos
                }
                if (lastFrameNanos == 0L) {
                    renderedPosition = sampledPositionMs
                } else {
                    val frameDeltaMs = (frameNanos - lastFrameNanos) / 1_000_000L
                    val extrapolatedSample = sampledPositionMs +
                        ((frameNanos - lastSampleNanos) / 1_000_000L)
                    renderedPosition = smoothLyricDisplayPosition(
                        previousMs = renderedPosition,
                        sampledMs = extrapolatedSample,
                        frameDeltaMs = frameDeltaMs,
                    )
                }
                lastFrameNanos = frameNanos
            }
        }
    }
    return renderedPosition
}

@Composable
private fun LargeLyricsMessage(
    text: String,
    color: Color,
    scale: Float,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = color,
            fontSize = (31f * scale).sp,
            lineHeight = (38f * scale).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
