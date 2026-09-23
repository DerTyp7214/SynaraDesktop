package dev.dertyp.synara.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.ConstraintsSizeResolver
import coil3.compose.rememberConstraintsSizeResolver
import dev.dertyp.PlatformUUID
import dev.dertyp.data.OnlineDevice
import dev.dertyp.data.UserSong
import dev.dertyp.services.IAnimatedImageService
import dev.dertyp.synara.animateColorSchemeAsState
import dev.dertyp.synara.player.*
import dev.dertyp.synara.rpc.PresenceService
import dev.dertyp.synara.scrobble.ScrobblerService
import dev.dertyp.synara.theme.createColorSchemeFromSeeds
import dev.dertyp.synara.theme.isAppDark
import dev.dertyp.synara.theme.rememberCoverScheme
import dev.dertyp.synara.ui.LocalWindowActions
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.menus.SongContextMenu
import dev.dertyp.synara.ui.components.player.*
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.remote_control_controlled_by
import synara.synara.generated.resources.remote_control_controlled_remotely
import synara.synara.generated.resources.remote_control_controlling
import synara.synara.generated.resources.remote_control_pick_device
import synara.synara.generated.resources.remote_control_this_device
import synara.synara.generated.resources.volume
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerBar(
    modifier: Modifier = Modifier,
    height: Dp,
    playerModel: PlayerModel = koinInject(),
    scrobblerService: ScrobblerService = koinInject(),
    globalState: GlobalStateModel = koinInject(),
    remote: RemotePlaybackController = koinInject(),
    presence: PresenceService = koinInject()
) {
    val target by remote.target.collectAsState()
    val controlledBy by presence.controlledBy.collectAsState()
    val controllableDevices by remote.controllableDevices.collectAsState()
    val surface: PlaybackSurface =
        if (target != null) remote else remember(playerModel) { LocalPlaybackSurface(playerModel) }
    val isRemote = target != null

    val isPlayingState by surface.isPlaying.collectAsState()
    val isPlaying by produceState(initialValue = isPlayingState, isPlayingState) {
        if (isPlayingState) {
            value = true
        } else {
            delay(50.milliseconds)
            value = false
        }
    }

    val currentSong by surface.currentSong.collectAsState()
    val volume by surface.volume.collectAsState()
    val currentPositionState by surface.currentPosition.collectAsState()
    val duration by surface.duration.collectAsState()
    val shuffleMode by surface.shuffleMode.collectAsState()
    val repeatMode by surface.repeatMode.collectAsState()
    val liveSampleRate by playerModel.sampleRate.collectAsState()
    val liveBitsPerSample by playerModel.bitsPerSample.collectAsState()
    val liveBitRate by playerModel.bitRate.collectAsState()

    val scrobbledFor by scrobblerService.scrobbledFor.collectAsState()
    val triggeredListen by scrobblerService.triggeredSong.collectAsState()
    val triggeredSong = triggeredListen?.song

    val isExpanded by globalState.isPlayerExpanded.collectAsState()
    val windowActions = LocalWindowActions.current

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(isExpanded) {
        if (!isExpanded && windowActions.isFullscreen) {
            windowActions.setFullscreen(false)
        }
    }

    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableLongStateOf(0L) }
    var isWaitingForPosition by remember { mutableStateOf(false) }

    val currentPosition =
        if (isSeeking || (isWaitingForPosition && currentPositionState <= 0L && duration > 0)) {
            seekPosition
        } else {
            currentPositionState
        }

    LaunchedEffect(currentPositionState) {
        if (currentPositionState > 0) {
            isWaitingForPosition = false
        }
    }

    LaunchedEffect(isWaitingForPosition) {
        if (isWaitingForPosition) {
            delay(1.seconds)
            isWaitingForPosition = false
        }
    }

    LaunchedEffect(currentSong) {
        isWaitingForPosition = false
    }

    var showSongContextMenu by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (isExpanded && event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Spacebar -> {
                            surface.togglePlayPause()
                            true
                        }

                        Key.F -> {
                            windowActions.toggleFullscreen()
                            true
                        }

                        Key.DirectionLeft -> {
                            surface.seekTo(
                                (surface.currentPosition.value - 5000).coerceAtLeast(
                                    0
                                )
                            )
                            true
                        }

                        Key.DirectionRight -> {
                            surface.seekTo(surface.currentPosition.value + 5000)
                            true
                        }

                        Key.N -> {
                            if (event.isShiftPressed) {
                                surface.skipNext()
                                true
                            } else false
                        }

                        Key.P -> {
                            if (event.isShiftPressed) {
                                surface.skipPrevious()
                                true
                            } else false
                        }

                        Key.L -> {
                            if (currentSong?.lyrics?.isNotBlank() == true) {
                                globalState.toggleLyricsExpanded()
                                true
                            } else false
                        }

                        Key.Q -> {
                            globalState.toggleQueueExpanded()
                            true
                        }

                        else -> false
                    }
                } else false
            }
            .onPointerEvent(PointerEventType.Scroll) {
                if (isExpanded && it.keyboardModifiers.isShiftPressed && surface.supportsVolume) {
                    val delay = it.changes.first().scrollDelta.y
                    if (delay != 0f) {
                        val direction = if (delay > 0) -1 else 1
                        surface.setVolume(
                            (surface.volume.value + direction * 0.02f).coerceIn(
                                0f,
                                1f
                            )
                        )
                    }
                }
            }
    ) {
        val totalMaxWidth = maxWidth
        val totalMaxHeight = maxHeight

        val animatedHeight by animateDpAsState(
            targetValue = if (isExpanded) totalMaxHeight else height,
            animationSpec = tween(300),
            label = "playerHeight"
        )

        val background by animateColorAsState(
            targetValue = if (isExpanded) isAppDark().let {
                if (it) Color.Black else Color.White
            }
            else MaterialTheme.colorScheme.surface.copy(alpha = .7f),
            animationSpec = tween(300),
            label = "playerBackground"
        )

        val blurredAlpha by animateFloatAsState(
            targetValue = if (isExpanded) 1f else 0f,
            animationSpec = tween(300),
            label = "blurredAlpha"
        )

        var parentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val videoFrameSeeds = remember(currentSong?.originalUrl) { mutableStateOf<Triple<Int?, Int?, Int?>>(Triple(null, null, null)) }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedHeight)
                .onGloballyPositioned { parentCoordinates = it },
            color = background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp
        ) {
            val isDark = isAppDark()
            val colorScheme by rememberCoverScheme(currentSong?.animatedCoverImageId ?: currentSong?.coverId, isDark = isDark)
            
            val dynamicColorScheme = remember(videoFrameSeeds.value, colorScheme, isDark) {
                if (videoFrameSeeds.value.first != null) {
                    createColorSchemeFromSeeds(videoFrameSeeds.value, isDark)
                } else {
                    colorScheme
                }
            }

            val colorAnimationSpec: TweenSpec<Color> = remember(videoFrameSeeds.value.first == null, currentSong?.id) {
                if (videoFrameSeeds.value.first != null) {
                    tween(150)
                } else {
                    tween(500)
                }
            }

            val animatedScheme by animateColorSchemeAsState(
                targetColorScheme = if (isExpanded) dynamicColorScheme else MaterialTheme.colorScheme,
                animationSpec = colorAnimationSpec
            )

            MaterialTheme(
                colorScheme = animatedScheme
            ) {
                BlurredVideoCoverBackground(
                    song = currentSong,
                    alpha = blurredAlpha,
                    audioReactive = true,
                    modifier = Modifier.fillMaxSize(),
                    onFrame = { videoFrameSeeds.value = it }
                ) {
                    val sizeResolver = rememberConstraintsSizeResolver()
                    val coverCenter = remember { mutableStateOf(Offset.Unspecified) }

                    val (colorA, colorB) = sort(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiary
                    )

                    ParticleViewGpu(
                        modifier = Modifier.fillMaxSize(),
                        color = colorA.copy(alpha = .7f),
                        highlightColor = colorB,
                        centerResolver = sizeResolver,
                        center = coverCenter
                    )

                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = isExpanded && animatedHeight > 200.dp,
                                enter = fadeIn(tween(200, delayMillis = 100)),
                                exit = fadeOut(tween(200)),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                ExpandedPlayerContent(
                                    currentSong = currentSong,
                                    sizeResolver = sizeResolver,
                                    parentCoordinates = parentCoordinates,
                                    coverCenter = coverCenter,
                                    isRemote = isRemote,
                                    position = surface.currentPosition,
                                    onSeek = { surface.seekTo(it) },
                                    onCollapse = {
                                        globalState.setPlayerExpanded(false)
                                    }
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(height)
                        ) {
                            val isCompact = totalMaxWidth < 850.dp

                            Column(
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SongInfoSection(
                                        currentSong = currentSong,
                                        liveBitRate = if (isRemote) 0L else liveBitRate,
                                        liveSampleRate = if (isRemote) 0 else liveSampleRate,
                                        liveBitsPerSample = if (isRemote) 0 else liveBitsPerSample,
                                        onToggleExpanded = { globalState.togglePlayerExpanded() },
                                        onArtistClick = { globalState.setPlayerExpanded(false) },
                                        onLikeClick = { currentSong?.let { playerModel.toggleLike(it) } },
                                        onSecondaryClick = { showSongContextMenu = true },
                                        modifier = Modifier.weight(1f)
                                    )

                                    PlayerControls(
                                        isPlaying = isPlaying,
                                        currentSongExists = currentSong != null,
                                        onSkipPrevious = { surface.skipPrevious() },
                                        onTogglePlayPause = { surface.togglePlayPause() },
                                        onSkipNext = { surface.skipNext() },
                                        modifier = Modifier.weight(1.2f)
                                    )

                                    val deviceMenu: (@Composable () -> Unit)? =
                                        if (controllableDevices.isEmpty()) {
                                            null
                                        } else {
                                            {
                                                DeviceMenuButton(
                                                    devices = controllableDevices,
                                                    target = target,
                                                    onOpen = { presence.refreshOnlineDevices() },
                                                    onSelect = { remote.select(it) }
                                                )
                                            }
                                        }

                                    PlayerActions(
                                        shuffleMode = shuffleMode,
                                        repeatMode = repeatMode,
                                        volume = volume,
                                        currentSongExists = currentSong != null,
                                        isCompact = isCompact,
                                        onToggleShuffle = { surface.toggleShuffle() },
                                        onToggleRepeat = { surface.toggleRepeat() },
                                        onVolumeChange = { surface.setVolume(it) },
                                        modifier = Modifier.weight(1f),
                                        showVolume = surface.supportsVolume,
                                        deviceMenu = deviceMenu
                                    )
                                }

                                PlayerProgressBar(
                                    currentPosition = currentPosition,
                                    duration = duration,
                                    currentSongExists = currentSong != null,
                                    onSeek = {
                                        isSeeking = true
                                        seekPosition = (it * duration).toLong()
                                    },
                                    onSeekFinished = {
                                        surface.seekTo(seekPosition)
                                        isSeeking = false
                                        isWaitingForPosition = true
                                    }
                                )
                            }

                            val controlledDevice = target
                            val controller = controlledBy
                            if (controlledDevice != null) {
                                Text(
                                    text = stringResource(
                                        Res.string.remote_control_controlling,
                                        controlledDevice.deviceName
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 10.dp, end = 16.dp)
                                )
                            } else if (controller != null) {
                                val controllerName = controller.deviceName?.takeIf { it.isNotBlank() }
                                Text(
                                    text = if (controllerName != null) {
                                        stringResource(Res.string.remote_control_controlled_by, controllerName)
                                    } else {
                                        stringResource(Res.string.remote_control_controlled_remotely)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 10.dp, end = 16.dp)
                                )
                            } else {
                                currentSong?.let { song ->
                                    PlayerScrobbleIndicator(
                                        currentSong = song,
                                        scrobbledFor = scrobbledFor,
                                        triggeredSong = triggeredSong,
                                        scrobblerService = scrobblerService,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 10.dp, end = 16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        currentSong?.let { song ->
            SongContextMenu(
                song = song,
                expanded = showSongContextMenu,
                onDismissRequest = { showSongContextMenu = false }
            )
        }
    }
}

@Composable
private fun DeviceMenuButton(
    devices: List<OnlineDevice>,
    target: OnlineDevice?,
    onOpen: () -> Unit,
    onSelect: (PlatformUUID?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = {
                onOpen()
                expanded = true
            }
        ) {
            Icon(
                SynaraIcons.DeviceGeneric.get(),
                contentDescription = stringResource(Res.string.remote_control_pick_device),
                tint = if (target != null) MaterialTheme.colorScheme.primary else LocalContentColor.current
            )
        }

        SynaraMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.remote_control_this_device)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
                trailingIcon = if (target == null) {
                    { Icon(SynaraIcons.Confirm.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
                } else null
            )

            devices.forEach { device ->
                val isSelected = target?.sessionId == device.sessionId

                DropdownMenuItem(
                    text = { Text(device.deviceName) },
                    onClick = {
                        onSelect(device.sessionId)
                        expanded = false
                    },
                    leadingIcon = {
                        Icon(
                            platformIcon(device.platform).get(),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = if (isSelected) {
                        { Icon(SynaraIcons.Confirm.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
                    } else null
                )
            }
        }
    }
}

private fun platformIcon(platform: String): SynaraIcons {
    val value = platform.lowercase()
    return when {
        listOf("linux", "windows", "mac", "desktop").any { it in value } -> SynaraIcons.DeviceDesktop
        listOf("android", "ios", "iphone", "ipad").any { it in value } -> SynaraIcons.DeviceMobile
        else -> SynaraIcons.DeviceGeneric
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ExpandedPlayerContent(
    currentSong: UserSong?,
    sizeResolver: ConstraintsSizeResolver,
    coverCenter: MutableState<Offset>,
    parentCoordinates: LayoutCoordinates? = null,
    isRemote: Boolean,
    position: StateFlow<Long>,
    onSeek: (Long) -> Unit,
    onCollapse: () -> Unit,
    globalState: GlobalStateModel = koinInject()
) {
    val windowActions = LocalWindowActions.current
    val isQueueShowing by globalState.isQueueExpanded.collectAsState()
    val isLyricsShowing by globalState.isLyricsExpanded.collectAsState()

    val sideContentShowing = isQueueShowing || isLyricsShowing

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        val isHorizontal = totalWidth > 800.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            var isTopBarHovered by remember { mutableStateOf(false) }
            val topBarAlpha by animateFloatAsState(
                targetValue = if (isTopBarHovered) 1f else 0.4f,
                label = "topBarAlpha"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onPointerEvent(PointerEventType.Enter) { isTopBarHovered = true }
                    .onPointerEvent(PointerEventType.Exit) { isTopBarHovered = false }
                    .graphicsLayer { alpha = topBarAlpha },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(
                        SynaraIcons.ExpandDown.get(),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (currentSong?.lyrics?.isNotBlank() == true) {
                        IconButton(onClick = { globalState.toggleLyricsExpanded() }) {
                            Icon(
                                SynaraIcons.Lyrics.get(),
                                contentDescription = "Lyrics",
                                modifier = Modifier.size(28.dp),
                                tint = if (isLyricsShowing) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
                    }

                    IconButton(onClick = { globalState.toggleQueueExpanded() }) {
                        Icon(
                            SynaraIcons.Queue.get(),
                            contentDescription = "Queue",
                            modifier = Modifier.size(28.dp),
                            tint = if (isQueueShowing) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                }

                IconButton(onClick = { windowActions.toggleFullscreen() }) {
                    Icon(
                        if (windowActions.isFullscreen) SynaraIcons.FullscreenExit.get() else SynaraIcons.FullscreenEnter.get(),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            if (isHorizontal) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val sideContentWeight by animateFloatAsState(
                        targetValue = if (sideContentShowing) 1f else 0.0001f,
                        animationSpec = tween(500),
                        label = "sideContentWeight"
                    )

                    val visualizerWidthScale by animateFloatAsState(
                        targetValue = if (sideContentShowing) 0.95f else 0.8f,
                        animationSpec = tween(500),
                        label = "visualizerWidthScale"
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.weight(.5f))

                        LargeCover(
                            song = currentSong,
                            sizeResolver = sizeResolver,
                            modifier = Modifier
                                .sizeIn(maxHeight = 400.dp, maxWidth = 400.dp)
                                .aspectRatio(1f)
                                .onGloballyPositioned { coordinates ->
                                    val parent = parentCoordinates ?: return@onGloballyPositioned
                                    val relativePosition =
                                        parent.localPositionOf(coordinates, Offset.Zero)

                                    val localCenter = Offset(
                                        x = coordinates.size.width / 2f,
                                        y = coordinates.size.height / 2f
                                    )

                                    coverCenter.value = relativePosition + localCenter
                                }
                        )

                        if (isRemote) {
                            Spacer(modifier = Modifier.weight(.5f))
                        } else {
                            Spacer(modifier = Modifier.weight(.3f))

                            val (colorA, colorB) = sort(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.tertiary
                            )

                            VisualizerView(
                                modifier = Modifier
                                    .fillMaxWidth(visualizerWidthScale)
                                    .requiredHeight(120.dp),
                                highlightColor = colorA,
                                color = colorB
                            )

                            Spacer(modifier = Modifier.weight(.2f))
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = sideContentShowing,
                        enter = expandHorizontally(tween(500)) + fadeIn(tween(350, 150)),
                        exit = shrinkHorizontally(tween(500)) + fadeOut(tween(350)),
                        modifier = Modifier.weight(sideContentWeight).fillMaxHeight()
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize().padding(start = 24.dp),
                            color = Color.Transparent
                        ) {
                            AnimatedContent(
                                targetState = Pair(isLyricsShowing, isQueueShowing),
                                transitionSpec = {
                                    fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                                },
                                label = "sideContentTransition"
                            ) { (showLyrics, showQueue) ->
                                if (showLyrics) {
                                    LyricsView(
                                        song = currentSong,
                                        position = position,
                                        onSeek = onSeek
                                    )
                                } else if (showQueue) {
                                    QueueView()
                                }
                            }
                        }
                    }
                }
            } else {
                AnimatedContent(
                    targetState = if (isLyricsShowing) "lyrics" else if (isQueueShowing) "queue" else "cover",
                    transitionSpec = {
                        (fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 4 })
                            .togetherWith(fadeOut(tween(500)) + slideOutVertically(tween(500)) { -it / 4 })
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = "expandedContentTransition"
                ) { state ->
                    when (state) {
                        "cover" -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Spacer(modifier = Modifier.weight(1f))

                                LargeCover(
                                    song = currentSong,
                                    sizeResolver = sizeResolver,
                                    modifier = Modifier
                                        .sizeIn(maxHeight = 360.dp, maxWidth = 360.dp)
                                        .aspectRatio(1f)
                                        .onGloballyPositioned { coordinates ->
                                            val parent =
                                                parentCoordinates ?: return@onGloballyPositioned
                                            val relativePosition =
                                                parent.localPositionOf(coordinates, Offset.Zero)

                                            val localCenter = Offset(
                                                x = coordinates.size.width / 2f,
                                                y = coordinates.size.height / 2f
                                            )

                                            coverCenter.value = relativePosition + localCenter
                                        }
                                )

                                if (isRemote) {
                                    Spacer(modifier = Modifier.weight(.9f))
                                } else {
                                    Spacer(modifier = Modifier.weight(.6f))

                                    val (colorA, colorB) = sort(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.tertiary
                                    )

                                    VisualizerView(
                                        modifier = Modifier
                                            .fillMaxWidth(0.9f)
                                            .requiredHeight(120.dp),
                                        highlightColor = colorA,
                                        color = colorB
                                    )

                                    Spacer(modifier = Modifier.weight(.3f))
                                }
                            }
                        }

                        "queue" -> {
                            QueueView(modifier = Modifier.fillMaxSize())
                        }

                        "lyrics" -> {
                            LyricsView(
                                modifier = Modifier.fillMaxSize(),
                                song = currentSong,
                                position = position,
                                onSeek = onSeek
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun sort(a: Color, b: Color): Pair<Color, Color> =
    if (a.luminance() > b.luminance()) a to b else b to a

@Composable
private fun LargeCover(
    song: UserSong?,
    modifier: Modifier = Modifier,
    sizeResolver: ConstraintsSizeResolver,
    animatedImageService: IAnimatedImageService = koinInject()
) {
    AnimatedContent(
        targetState = song,
        transitionSpec = {
            fadeIn(tween(500)) togetherWith fadeOut(tween(500))
        },
        label = "largeCoverTransition",
        modifier = modifier
    ) { currentSong ->
        val animatedCoverId = currentSong?.animatedCoverId
        val staticCoverId = currentSong?.animatedCoverImageId ?: currentSong?.coverId

        var videoLoaded by remember(animatedCoverId) { mutableStateOf(false) }
        val videoAlpha by animateFloatAsState(
            targetValue = if (videoLoaded) 1f else 0f,
            animationSpec = tween(1000),
            label = "videoFade"
        )

        Box(modifier = Modifier.fillMaxSize()) {
            SynaraImage(
                imageId = staticCoverId,
                modifier = Modifier.fillMaxSize().then(sizeResolver),
                shape = RoundedCornerShape(16.dp),
                fallbackIcon = SynaraIcons.Songs
            )

            if (animatedCoverId != null) {
                SynaraVideoPlayer(
                    key = animatedCoverId.toString(),
                    loader = { animatedImageService.getAnimatedImageData(animatedCoverId) },
                    modifier = Modifier
                        .fillMaxSize()
                        .then(sizeResolver)
                        .clip(RoundedCornerShape(16.dp))
                        .graphicsLayer { alpha = videoAlpha },
                    loop = true,
                    onLoaded = { videoLoaded = true },
                    frameIndex = LocalVideoFrameIndex.current
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VolumeControl(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    isCompact: Boolean,
    expandedSliderWidth: Dp = 200.dp
) {
    var isHovered by remember { mutableStateOf(false) }
    var isPopupHovered by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }
    var lastVolume by remember { mutableStateOf(if (volume > 0f) volume else 0.5f) }

    LaunchedEffect(volume) {
        if (volume > 0f) {
            lastVolume = volume
        }
    }

    val density = LocalDensity.current

    val sliderInteraction = remember { MutableInteractionSource() }
    val isDragging by sliderInteraction.collectIsDraggedAsState()
    val sliderWidth by animateDpAsState(
        targetValue = if (isHovered || isDragging) expandedSliderWidth else 100.dp,
        animationSpec = tween(200),
        label = "volumeSliderWidth"
    )

    LaunchedEffect(isHovered, isPopupHovered) {
        if (isHovered || isPopupHovered) {
            showPopup = true
        } else {
            delay(150.milliseconds)
            showPopup = false
        }
    }

    val volumeIcon = when {
        volume == 0f -> SynaraIcons.VolumeOff.get()
        volume < 0.33f -> SynaraIcons.VolumeMute.get()
        volume < 0.67f -> SynaraIcons.VolumeLow.get()
        else -> SynaraIcons.VolumeHigh.get()
    }

    Box(
        modifier = Modifier
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .onPointerEvent(PointerEventType.Scroll) {
                val delta = it.changes.first().scrollDelta.y
                if (delta != 0f) {
                    val direction = if (delta > 0) -1 else 1
                    onVolumeChange((volume + direction * 0.02f).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onVolumeChange(if (volume > 0f) 0f else lastVolume) }) {
                Icon(
                    volumeIcon,
                    contentDescription = stringResource(Res.string.volume),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isCompact) {
                Spacer(modifier = Modifier.width(4.dp))
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    modifier = Modifier.width(sliderWidth).height(12.dp),
                    interactionSource = sliderInteraction,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }

        if (isCompact && showPopup) {
            val popupHeight = 180.dp
            val popupWidth = 48.dp

            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(
                    0,
                    with(density) { -popupHeight.roundToPx() + 4.dp.roundToPx() }),
                properties = PopupProperties(focusable = false)
            ) {
                ElevatedCard(
                    modifier = Modifier
                        .onPointerEvent(PointerEventType.Enter) { isPopupHovered = true }
                        .onPointerEvent(PointerEventType.Exit) { isPopupHovered = false }
                        .onPointerEvent(PointerEventType.Scroll) {
                            val delta = it.changes.first().scrollDelta.y
                            if (delta != 0f) {
                                val direction = if (delta > 0) -1 else 1
                                onVolumeChange((volume + direction * 0.02f).coerceIn(0f, 1f))
                            }
                        }
                        .padding(bottom = 4.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(popupWidth)
                            .height(popupHeight - 8.dp)
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        VerticalSlider(
                            value = volume,
                            onValueChange = onVolumeChange,
                            modifier = Modifier
                                .width(12.dp)
                                .height(popupHeight - 48.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }
        }
    }
}
