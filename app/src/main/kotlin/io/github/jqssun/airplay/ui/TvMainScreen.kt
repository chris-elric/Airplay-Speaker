package io.github.jqssun.airplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import io.github.jqssun.airplay.lyrics.LyricsExtraMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.BuildConfig
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.viewmodel.MainViewModel

private enum class TvDestination(val labelRes: Int, val icon: ImageVector) {
    HOME(R.string.tv_nav_home, Icons.Default.Home),
    SETTINGS(R.string.tv_nav_settings, Icons.Default.Settings),
    DIAGNOSTICS(R.string.tv_nav_diagnostics, Icons.AutoMirrored.Filled.Article),
}

@Composable
internal fun TvMainScreen(
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    showAudioMode: Boolean,
) {
    var destination by remember { mutableStateOf(TvDestination.HOME) }
    val state by viewModel.serverState.collectAsState()
    val serverName by viewModel.serverName.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val mirroring by viewModel.mirroringActive.collectAsState()
    val immersiveNowPlaying = showAudioMode && state == ServerState.RUNNING &&
        connections > 0 && destination == TvDestination.HOME

    BackHandler(enabled = destination != TvDestination.HOME) {
        destination = TvDestination.HOME
    }

    if (immersiveNowPlaying) {
        TvNowPlayingScreen(
            viewModel = viewModel,
            onSettings = { destination = TvDestination.SETTINGS },
        )
        return
    }

    if (destination == TvDestination.SETTINGS) {
        TvSettingsScreen(
            viewModel = viewModel,
            onBack = { destination = TvDestination.HOME },
        )
        return
    }

    if (mirroring && state == ServerState.RUNNING && connections > 0) {
        TvMirroringScreen(
            video = video,
            onFullscreen = onFullscreen,
            onPip = onPip,
            onSettings = { destination = TvDestination.SETTINGS },
        )
        return
    }

    TvIdleScreen(
        state = state,
        serverName = serverName,
        onSettings = { destination = TvDestination.SETTINGS },
    )
}

@Composable
private fun TvIdleScreen(
    state: ServerState,
    serverName: String,
    onSettings: () -> Unit,
) {
    val idleFocus = remember { FocusRequester() }
    val ready = state == ServerState.RUNNING
    val foreground = if (ready) Color(0xFFF2F3F5) else Color(0xFFFFA1A1)

    LaunchedEffect(Unit) {
        runCatching { idleFocus.requestFocus() }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(idleFocus)
            .focusable(),
    ) {
        val scale = minOf(maxWidth.value / 960f, maxHeight.value / 540f)
            .coerceIn(0.78f, 1.8f)

        LiquidGlassAmbientBackdrop(
            modifier = Modifier.fillMaxSize(),
            accent = if (ready) Color(0xFF72D7FF) else Color(0xFFFF8F9C),
        )

        IconButton(
            onClick = onSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = (20f * scale).dp, end = (24f * scale).dp)
                .size((43f * scale).dp)
                .liquidGlass(
                    shape = CircleShape,
                    tint = foreground,
                    strength = 0.72f,
                )
                .dpadFocus(CircleShape),
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = stringResource(R.string.tv_nav_settings),
                tint = Color(0xFFD7DDE2).copy(alpha = 0.74f),
                modifier = Modifier.size((20f * scale).dp),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = (118f * scale).dp),
            horizontalArrangement = Arrangement.spacedBy((76f * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.width((300f * scale).dp)) {
                Text(
                    text = "AirPlay",
                    color = foreground,
                    fontSize = (42f * scale).sp,
                    lineHeight = (46f * scale).sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height((13f * scale).dp))
                Text(
                    text = "Wirelessly share music from your\niPhone, iPad, or Mac.",
                    color = foreground.copy(alpha = 0.74f),
                    fontSize = (15f * scale).sp,
                    lineHeight = (18f * scale).sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height((22f * scale).dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy((22f * scale).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AirPlayGlyph(
                        color = foreground.copy(alpha = 0.9f),
                        modifier = Modifier.size((45f * scale).dp),
                    )
                    Icon(
                        imageVector = Icons.Default.Cast,
                        contentDescription = null,
                        tint = foreground.copy(alpha = 0.9f),
                        modifier = Modifier.size((43f * scale).dp),
                    )
                }
            }

            Column(modifier = Modifier.width((360f * scale).dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(
                            shape = RoundedCornerShape((16f * scale).dp),
                            selected = ready,
                            tint = if (ready) Color(0xFF8EE6FF) else Color(0xFFFF9BA6),
                            strength = if (ready) 0.94f else 0.74f,
                        )
                        .padding(
                            horizontal = (20f * scale).dp,
                            vertical = (16f * scale).dp,
                        ),
                ) {
                    Text(
                        text = if (ready) "CHOOSE THIS DEVICE" else "RECEIVER OFFLINE",
                        color = foreground.copy(alpha = 0.86f),
                        fontSize = (11f * scale).sp,
                        lineHeight = (14f * scale).sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (0.3f * scale).sp,
                    )
                    Spacer(Modifier.height((2f * scale).dp))
                    Text(
                        text = serverName.ifBlank { "Airplay Speaker" },
                        color = foreground,
                        fontSize = (21f * scale).sp,
                        lineHeight = (24f * scale).sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height((13f * scale).dp))
                Text(
                    text = if (ready) {
                        "Make sure your Mac or iOS device is connected to the same Wi-Fi network as this device.\nFor more information visit apple.com/airplay"
                    } else {
                        "Open Settings to start the AirPlay receiver, then choose this device from Music or Control Center."
                    },
                    color = foreground.copy(alpha = 0.64f),
                    fontSize = (10.5f * scale).sp,
                    lineHeight = (13.5f * scale).sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun AirPlayGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.055f
        val screenTop = size.height * 0.08f
        val screenHeight = size.height * 0.58f
        drawRoundRect(
            color = color,
            topLeft = Offset(strokeWidth / 2f, screenTop),
            size = Size(size.width - strokeWidth, screenHeight),
            cornerRadius = CornerRadius(size.minDimension * 0.07f),
            style = Stroke(width = strokeWidth),
        )
        val triangle = Path().apply {
            moveTo(size.width * 0.5f, size.height * 0.59f)
            lineTo(size.width * 0.27f, size.height * 0.91f)
            lineTo(size.width * 0.73f, size.height * 0.91f)
            close()
        }
        drawPath(triangle, color)
    }
}

@Composable
private fun TvMirroringScreen(
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        video()
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(onClick = onPip, modifier = Modifier.dpadFocus(CircleShape)) {
                Icon(painterResource(R.drawable.ic_pip), null, tint = Color.White)
            }
            IconButton(onClick = onFullscreen, modifier = Modifier.dpadFocus(CircleShape)) {
                Icon(Icons.Default.Fullscreen, null, tint = Color.White)
            }
            IconButton(onClick = onSettings, modifier = Modifier.dpadFocus(CircleShape)) {
                Icon(Icons.Default.Settings, stringResource(R.string.tv_nav_settings), tint = Color.White)
            }
        }
    }
}

@Composable
private fun TvSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { backFocus.requestFocus() }
    }
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        LiquidGlassAmbientBackdrop(
            modifier = Modifier.fillMaxSize(),
            accent = Color(0xFF78C9FF),
        )
        TvSettingsPanel(
            viewModel = viewModel,
            modifier = Modifier.padding(start = 58.dp),
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 22.dp, top = 25.dp)
                .size(50.dp)
                .focusRequester(backFocus)
                .liquidGlass(
                    shape = CircleShape,
                    tint = Color(0xFFB5E6FF),
                    strength = 0.8f,
                )
                .dpadFocus(CircleShape),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.tv_back),
                tint = Color(0xFFF2F3F5),
            )
        }
    }
}

@Composable
private fun TvNavigationRail(
    selected: TvDestination,
    onSelected: (TvDestination) -> Unit,
    state: ServerState,
    connections: Int,
    homeFocus: FocusRequester,
) {
    Column(
        modifier = Modifier
            .width(116.dp)
            .fillMaxHeight()
            .liquidGlass(
                shape = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp),
                tint = Color(0xFF83CFFF),
                strength = 0.82f,
            )
            .padding(horizontal = 13.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .liquidGlass(
                    shape = RoundedCornerShape(15.dp),
                    tint = Color(0xFFBCE9FF),
                    strength = 0.72f,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.airplay_speaker_icon),
                contentDescription = stringResource(R.string.app_name),
                tint = Color.Unspecified,
                modifier = Modifier.size(38.dp),
            )
        }

        Spacer(Modifier.height(30.dp))

        TvNavItem(
            destination = TvDestination.HOME,
            selected = selected == TvDestination.HOME,
            onClick = { onSelected(TvDestination.HOME) },
            modifier = Modifier.focusRequester(homeFocus),
        )
        Spacer(Modifier.height(8.dp))
        TvNavItem(
            destination = TvDestination.SETTINGS,
            selected = selected == TvDestination.SETTINGS,
            onClick = { onSelected(TvDestination.SETTINGS) },
        )
        Spacer(Modifier.height(8.dp))
        TvNavItem(
            destination = TvDestination.DIAGNOSTICS,
            selected = selected == TvDestination.DIAGNOSTICS,
            onClick = { onSelected(TvDestination.DIAGNOSTICS) },
        )

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier
                .liquidGlass(
                    shape = RoundedCornerShape(18.dp),
                    selected = state == ServerState.RUNNING,
                    tint = if (state == ServerState.RUNNING) {
                        Color(0xFF9EF2D0)
                    } else {
                        Color(0xFFFF8A8A)
                    },
                    strength = 0.66f,
                )
                .padding(horizontal = 10.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (state == ServerState.RUNNING) Color(0xFF9EF2D0) else Color(0xFFFF8A8A))
            )
            Spacer(Modifier.height(7.dp))
            Text(
                if (connections > 0) "$connections ONLINE"
                else if (state == ServerState.RUNNING) "READY" else "OFFLINE",
                color = Color(0xFFA9AAB4),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "v${BuildConfig.VERSION_NAME}",
            color = Color(0xFF6E7082),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun TvNavItem(
    destination: TvDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.035f else 1f, label = "tvNavScale")
    val contentColor = if (focused || selected) Color(0xFFF2F3F5) else Color(0xFF858791)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .liquidGlass(
                shape = RoundedCornerShape(20.dp),
                focused = focused,
                selected = selected,
                tint = if (selected) Color(0xFF9EF2D0) else Color(0xFF91D7FF),
                strength = if (focused || selected) 0.82f else 0.54f,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(vertical = 9.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            destination.icon,
            contentDescription = stringResource(destination.labelRes),
            tint = contentColor,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(destination.labelRes),
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun TvReceiverHome(
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
) {
    val state by viewModel.serverState.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val serverName by viewModel.serverName.collectAsState()
    val mirroring by viewModel.mirroringActive.collectAsState()
    val mint = Color(0xFF9EF2D0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 38.dp, vertical = 30.dp)
    ) {
        Text(
            stringResource(R.string.tv_home_title),
            color = Color(0xFFF6F4FF),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            stringResource(R.string.tv_home_subtitle),
            color = Color(0xFF9A9BAD),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (mirroring && state == ServerState.RUNNING) {
                Box(
                    modifier = Modifier
                        .weight(1.6f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    video()
                    Row(
                        modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = onPip, modifier = Modifier.dpadFocus(CircleShape)) {
                            Icon(painterResource(R.drawable.ic_pip), null, tint = Color.White)
                        }
                        IconButton(onClick = onFullscreen, modifier = Modifier.dpadFocus(CircleShape)) {
                            Icon(Icons.Default.Fullscreen, null, tint = Color.White)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1.6f)
                        .fillMaxHeight()
                        .liquidGlass(
                            shape = RoundedCornerShape(24.dp),
                            selected = state == ServerState.RUNNING,
                            tint = mint,
                            strength = 0.92f,
                        )
                        .padding(30.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(13.dp)
                                .clip(CircleShape)
                                .background(if (state == ServerState.RUNNING) mint else Color(0xFFFF8A8A))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (state == ServerState.RUNNING) stringResource(R.string.tv_listening)
                            else stringResource(R.string.tv_receiver_stopped),
                            color = if (state == ServerState.RUNNING) mint else Color(0xFFFFA0A0),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Icon(
                        if (connections > 0) Icons.Default.CastConnected else Icons.Default.Cast,
                        null,
                        tint = mint,
                        modifier = Modifier.size(62.dp),
                    )
                    Spacer(Modifier.height(15.dp))
                    Text(
                        serverName,
                        color = Color(0xFFF8F7FF),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        if (connections > 0) stringResource(R.string.tv_connected_waiting)
                        else stringResource(R.string.tv_ready_description),
                        color = Color(0xFFB1B1C0),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvFeatureChip("ALAC LOSSLESS")
                        TvFeatureChip("16-BIT / 44.1 kHz")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            if (state == ServerState.RUNNING) viewModel.stopServer()
                            else viewModel.startServer()
                        },
                        modifier = Modifier.height(58.dp).dpadFocus(RoundedCornerShape(18.dp)),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state == ServerState.RUNNING) Color(0xFF35384A) else mint,
                            contentColor = if (state == ServerState.RUNNING) Color.White else Color(0xFF0A1512),
                        ),
                        contentPadding = PaddingValues(horizontal = 25.dp),
                    ) {
                        Icon(if (state == ServerState.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(9.dp))
                        Text(
                            if (state == ServerState.RUNNING) stringResource(R.string.btn_stop)
                            else stringResource(R.string.btn_start),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .liquidGlass(
                        shape = RoundedCornerShape(24.dp),
                        tint = Color(0xFF8ACDFF),
                        strength = 0.86f,
                    )
                    .padding(26.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = mint, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.tv_quick_connect),
                        color = Color(0xFFF6F4FF),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(20.dp))
                TvConnectStep("1", stringResource(R.string.tv_connect_step_1))
                Spacer(Modifier.height(14.dp))
                TvConnectStep("2", stringResource(R.string.tv_connect_step_2, serverName))
                Spacer(Modifier.height(14.dp))
                TvConnectStep("3", stringResource(R.string.tv_connect_step_3))
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.tv_speaker_mode_note),
                    color = mint,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun TvFeatureChip(text: String) {
    Text(
        text,
        color = Color(0xFFDAD8E8),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .liquidGlass(
                shape = RoundedCornerShape(50),
                tint = Color(0xFF9EF2D0),
                strength = 0.56f,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun TvConnectStep(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .liquidGlass(
                    shape = CircleShape,
                    tint = Color(0xFF9EF2D0),
                    strength = 0.62f,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, color = Color(0xFF9EF2D0), fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(15.dp))
        Text(
            text,
            color = Color(0xFFD5D4DE),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TvSettingsPanel(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val serverState by viewModel.serverState.collectAsState()
    val runInBackground by viewModel.runInBackground.collectAsState()
    val bootAutoStart by viewModel.bootAutoStart.collectAsState()
    val autoStart by viewModel.autoStart.collectAsState()
    val alac by viewModel.alacEnabled.collectAsState()
    val swAlac by viewModel.swAlacEnabled.collectAsState()
    val losslessOutput by viewModel.losslessOutput.collectAsState()
    val spatialEnhancement by viewModel.spatialEnhancement.collectAsState()
    val karaokeEnabled by viewModel.karaokeEnabled.collectAsState()
    val aiVocalSeparation by viewModel.aiVocalSeparation.collectAsState()
    val allowNew by viewModel.allowNewConn.collectAsState()
    val requirePin by viewModel.requirePin.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val advertiseVideo by viewModel.advertiseVideo.collectAsState()
    val motionArtworkEnabled by viewModel.motionArtworkEnabled.collectAsState()
    val fullscreenMotionArtwork by viewModel.fullscreenMotionArtwork.collectAsState()
    val lyricsExtraMode by viewModel.lyricsExtraMode.collectAsState()
    val lyricsDelayMs by viewModel.lyricsDelayMs.collectAsState()
    val translationLanguage by viewModel.lyricsTranslationLanguage.collectAsState()
    val appleToken by viewModel.appleMediaUserToken.collectAsState()
    val musixmatchToken by viewModel.musixmatchToken.collectAsState()
    var tokenDialog by remember { mutableStateOf<String?>(null) }
    val languages = listOf("es", "en", "pt", "fr", "de", "it", "ja", "ko")

    tokenDialog?.let { which ->
        val isApple = which == "apple"
        TvTokenDialog(
            title = stringResource(
                if (isApple) R.string.setting_apple_token else R.string.setting_musixmatch_token
            ),
            hint = stringResource(
                if (isApple) R.string.setting_apple_token_desc else R.string.setting_musixmatch_token_desc
            ),
            initial = if (isApple) appleToken else musixmatchToken,
            onSave = {
                if (isApple) viewModel.setAppleMediaUserToken(it) else viewModel.setMusixmatchToken(it)
                tokenDialog = null
            },
            onDismiss = { tokenDialog = null },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 38.dp, vertical = 30.dp)
    ) {
        Text(
            stringResource(R.string.tv_settings_title),
            color = Color(0xFFF6F4FF),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            stringResource(R.string.tv_settings_subtitle),
            color = Color(0xFF9A9BAD),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(24.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 30.dp),
        ) {
            item {
                TvSettingCard(
                    stringResource(R.string.setting_airplay_receiver),
                    stringResource(R.string.setting_airplay_receiver_desc),
                    serverState == ServerState.RUNNING,
                ) { enabled ->
                    if (enabled) viewModel.startServer() else viewModel.stopServer()
                }
            }
            item {
                TvSettingCard(
                    stringResource(R.string.setting_advertise_video),
                    stringResource(R.string.setting_advertise_video_desc),
                    advertiseVideo,
                    viewModel::setAdvertiseVideo,
                )
            }
            item {
                TvSettingCard(
                    stringResource(R.string.setting_motion_artwork),
                    stringResource(R.string.setting_motion_artwork_desc),
                    motionArtworkEnabled,
                    viewModel::setMotionArtworkEnabled,
                )
            }
            item {
                TvSettingCard(
                    title = stringResource(R.string.setting_fullscreen_motion_artwork),
                    description = stringResource(R.string.setting_fullscreen_motion_artwork_desc),
                    checked = fullscreenMotionArtwork,
                    onCheckedChange = viewModel::setFullscreenMotionArtwork,
                    enabled = motionArtworkEnabled,
                )
            }
            item {
                TvValueCard(
                    title = stringResource(R.string.setting_lyrics_extra),
                    description = stringResource(R.string.setting_lyrics_extra_desc),
                    value = stringResource(
                        when (lyricsExtraMode) {
                            LyricsExtraMode.OFF -> R.string.lyrics_extra_off
                            LyricsExtraMode.TRANSLATION -> R.string.lyrics_extra_translation
                            LyricsExtraMode.ROMANIZATION -> R.string.lyrics_extra_romanization
                            LyricsExtraMode.BOTH -> R.string.lyrics_extra_both
                        }
                    ),
                    highlighted = lyricsExtraMode != LyricsExtraMode.OFF,
                    onClick = { viewModel.setLyricsExtraMode(lyricsExtraMode.next()) },
                )
            }
            item {
                TvValueCard(
                    title = stringResource(R.string.setting_lyrics_delay),
                    description = stringResource(R.string.setting_lyrics_delay_desc),
                    value = formatLyricDelay(lyricsDelayMs),
                    highlighted = lyricsDelayMs != 0,
                    onClick = {
                        // OK cycles coarse presets; LEFT/RIGHT fine-tune by 50 ms.
                        val presets = listOf(0, 250, 500, 750, 1000, 1500, 2000, -250, -500, -1000)
                        val next = presets.indexOf(lyricsDelayMs).let { i ->
                            if (i < 0) 0 else presets[(i + 1) % presets.size]
                        }
                        viewModel.setLyricsDelayMs(next)
                    },
                    onAdjust = { direction -> viewModel.setLyricsDelayMs(lyricsDelayMs + direction * 50) },
                )
            }
            item {
                TvValueCard(
                    title = stringResource(R.string.setting_lyrics_translation_language),
                    description = stringResource(R.string.setting_lyrics_translation_language_desc),
                    value = translationLanguage.uppercase(),
                    onClick = {
                        val next = languages.indexOf(translationLanguage).let { i ->
                            languages[(i + 1) % languages.size]
                        }
                        viewModel.setLyricsTranslationLanguage(next)
                    },
                )
            }
            item {
                TvValueCard(
                    title = stringResource(R.string.setting_apple_token),
                    description = stringResource(R.string.setting_apple_token_desc),
                    value = stringResource(
                        if (appleToken.isBlank()) R.string.lyrics_token_not_set else R.string.lyrics_token_set
                    ),
                    highlighted = appleToken.isNotBlank(),
                    onClick = { tokenDialog = "apple" },
                )
            }
            item {
                TvValueCard(
                    title = stringResource(R.string.setting_musixmatch_token),
                    description = stringResource(R.string.setting_musixmatch_token_desc),
                    value = stringResource(
                        if (musixmatchToken.isBlank()) R.string.lyrics_token_not_set else R.string.lyrics_token_set
                    ),
                    highlighted = musixmatchToken.isNotBlank(),
                    onClick = { tokenDialog = "musixmatch" },
                )
            }
            item {
                TvSettingCard(
                    stringResource(R.string.setting_run_in_background),
                    stringResource(R.string.setting_run_in_background_desc),
                    runInBackground,
                    viewModel::setRunInBackground,
                )
            }
            item {
                TvSettingCard(
                    stringResource(R.string.setting_boot_auto_start),
                    stringResource(R.string.setting_boot_auto_start_desc),
                    bootAutoStart,
                    viewModel::setBootAutoStart,
                )
            }
            item {
                TvSettingCard(
                    stringResource(R.string.setting_auto_start),
                    stringResource(R.string.setting_auto_start_desc),
                    autoStart,
                    viewModel::setAutoStart,
                )
            }
            item {
                TvSettingCard(
                    stringResource(R.string.setting_alac),
                    stringResource(R.string.setting_alac_desc),
                    alac,
                    viewModel::setAlacEnabled,
                )
            }
            item {
                TvSettingCard(
                    stringResource(R.string.setting_lossless_output),
                    stringResource(R.string.setting_lossless_output_desc),
                    losslessOutput,
                    viewModel::setLosslessOutput,
                )
            }
            item {
                TvSettingCard(
                    stringResource(R.string.setting_spatial_enhancement),
                    stringResource(R.string.setting_spatial_enhancement_desc),
                    spatialEnhancement,
                    viewModel::setSpatialEnhancement,
                )
            }
            item {
                TvSettingCard(
                    stringResource(R.string.setting_karaoke_mode),
                    stringResource(R.string.setting_karaoke_mode_desc),
                    karaokeEnabled,
                    viewModel::setKaraokeEnabled,
                )
            }
            item {
                TvSettingCard(
                    title = stringResource(R.string.setting_ai_vocal_separation),
                    description = stringResource(
                        if (viewModel.aiVocalSeparationSupported) {
                            R.string.setting_ai_vocal_separation_desc
                        } else {
                            R.string.setting_ai_vocal_separation_unsupported_desc
                        }
                    ),
                    checked = aiVocalSeparation,
                    onCheckedChange = viewModel::setAiVocalSeparation,
                    enabled = viewModel.aiVocalSeparationSupported,
                )
            }
            item {
                TvSettingCard(
                    stringResource(R.string.setting_sw_alac),
                    stringResource(R.string.setting_sw_alac_desc),
                    swAlac,
                    viewModel::setSwAlacEnabled,
                )
            }
            item {
                TvSettingCard(
                    stringResource(R.string.setting_allow_new_conn),
                    stringResource(R.string.setting_allow_new_conn_desc),
                    allowNew,
                    viewModel::setAllowNewConn,
                )
            }
            item {
                TvSettingCard(
                    stringResource(R.string.setting_require_pin),
                    stringResource(R.string.setting_require_pin_desc),
                    requirePin,
                    viewModel::setRequirePin,
                )
            }
            item {
                TvSettingCard(
                    stringResource(R.string.setting_keep_screen_on),
                    stringResource(R.string.setting_keep_screen_on_desc),
                    keepScreenOn,
                    viewModel::setKeepScreenOn,
                )
            }
        }
    }
}

@Composable
private fun TvValueCard(
    title: String,
    description: String,
    value: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    /** When set, LEFT/RIGHT call this with -1/+1 instead of moving focus. */
    onAdjust: ((Int) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.025f else 1f, label = "tvValueScale")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(102.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .liquidGlass(
                shape = RoundedCornerShape(20.dp),
                focused = focused,
                selected = highlighted,
                tint = if (highlighted) Color(0xFF9EF2D0) else Color(0xFF85CCFF),
                strength = 0.84f,
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val adjust = onAdjust
                if (adjust == null || event.type != KeyEventType.KeyDown) {
                    false
                } else when (event.key) {
                    Key.DirectionLeft -> { adjust(-1); true }
                    Key.DirectionRight -> { adjust(1); true }
                    else -> false
                }
            }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color(0xFFF6F4FF),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                description,
                color = Color(0xFF9697A8),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(18.dp))
        Text(
            value,
            color = if (highlighted) Color(0xFF9EF2D0) else Color(0xFFF6F4FF),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** "+0.50 s" / "-1.00 s" / "0 s". */
internal fun formatLyricDelay(ms: Int): String = when {
    ms == 0 -> "0 s"
    else -> String.format(java.util.Locale.US, "%+.2f s", ms / 1000f)
}

@Composable
private fun TvTokenDialog(
    title: String,
    hint: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.btn_save_token)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel_token)) }
        },
    )
}

@Composable
private fun TvSettingCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    TvSettingCard(
        title = title,
        description = description,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = true,
    )
}

@Composable
private fun TvSettingCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.025f else 1f, label = "tvSettingScale")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(102.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .liquidGlass(
                shape = RoundedCornerShape(20.dp),
                focused = focused,
                selected = checked,
                tint = when {
                    !enabled -> Color(0xFF7B8490)
                    checked -> Color(0xFF9EF2D0)
                    else -> Color(0xFF85CCFF)
                },
                strength = if (enabled) 0.84f else 0.54f,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, role = Role.Switch) {
                onCheckedChange(!checked)
            }
            .focusable(enabled)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color(0xFFF6F4FF),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                description,
                color = Color(0xFF9697A8),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(18.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

@Composable
private fun TvDiagnosticsPanel(viewModel: MainViewModel) {
    val logs by viewModel.logs.collectAsState()
    val visibleLogs = logs.takeLast(300)
    val listState = rememberLazyListState()

    LaunchedEffect(visibleLogs.size) {
        if (visibleLogs.isNotEmpty()) listState.scrollToItem(visibleLogs.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 38.dp, vertical = 30.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    stringResource(R.string.tv_diagnostics_title),
                    color = Color(0xFFF6F4FF),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    stringResource(R.string.tv_diagnostics_subtitle),
                    color = Color(0xFF9A9BAD),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = viewModel::exportLogs,
                    modifier = Modifier.height(52.dp).dpadFocus(RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(stringResource(R.string.btn_export), fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = viewModel::clearLogs,
                    modifier = Modifier.height(52.dp).dpadFocus(RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF313547)),
                ) {
                    Text(stringResource(R.string.btn_clear), fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .liquidGlass(
                    shape = RoundedCornerShape(22.dp),
                    tint = Color(0xFF89D3FF),
                    strength = 0.78f,
                )
                .padding(22.dp)
        ) {
            if (visibleLogs.isEmpty()) {
                Text(
                    stringResource(R.string.tv_no_diagnostics),
                    color = Color(0xFF8D8E9E),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(visibleLogs) { line ->
                        Text(
                            line,
                            color = Color(0xFFBFC2CE),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
    }
}
