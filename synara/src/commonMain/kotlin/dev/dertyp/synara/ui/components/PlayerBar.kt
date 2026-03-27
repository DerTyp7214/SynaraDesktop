package dev.dertyp.synara.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
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
import dev.dertyp.data.UserSong
import dev.dertyp.synara.animateColorSchemeAsState
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.scrobble.ScrobblerService
import dev.dertyp.synara.theme.isAppDark
import dev.dertyp.synara.theme.rememberCoverScheme
import dev.dertyp.synara.ui.LocalWindowActions
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.menus.SongContextMenu
import dev.dertyp.synara.ui.components.player.*
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.volume

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerBar(
    modifier: Modifier = Modifier,
    height: Dp,
    playerModel: PlayerModel = koinInject(),
    scrobblerService: ScrobblerService = koinInject(),
    globalState: GlobalStateModel = koinInject()
) {
    val isPlayingState by playerModel.isPlaying.collectAsState()
    val isPlaying by produceState(initialValue = isPlayingState, isPlayingState) {
        if (isPlayingState) {
            value = true
        } else {
            delay(50)
            value = false
        }
    }

    val currentSong by playerModel.currentSong.collectAsState()
    val volume by playerModel.volume.collectAsState()
    val currentPositionState by playerModel.currentPosition.collectAsState()
    val duration by playerModel.duration.collectAsState()
    val shuffleMode by playerModel.shuffleMode.collectAsState()
    val repeatMode by playerModel.repeatMode.collectAsState()
    val liveSampleRate by playerModel.sampleRate.collectAsState()
    val liveBitsPerSample by playerModel.bitsPerSample.collectAsState()
    val liveBitRate by playerModel.bitRate.collectAsState()

    val scrobbledFor by scrobblerService.scrobbledFor.collectAsState()
    val triggeredSong by scrobblerService.triggeredSong.collectAsState()

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
            delay(1000)
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
                            playerModel.togglePlayPause()
                            true
                        }

                        Key.F -> {
                            windowActions.toggleFullscreen()
                            true
                        }

                        Key.DirectionLeft -> {
                            playerModel.seekTo(
                                (playerModel.currentPosition.value - 5000).coerceAtLeast(
                                    0
                                )
                            )
                            true
                        }

                        Key.DirectionRight -> {
                            playerModel.seekTo(playerModel.currentPosition.value + 5000)
                            true
                        }

                        Key.N -> {
                            if (event.isShiftPressed) {
                                playerModel.skipNext()
                                true
                            } else false
                        }

                        Key.P -> {
                            if (event.isShiftPressed) {
                                playerModel.skipPrevious()
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
                if (isExpanded && it.keyboardModifiers.isShiftPressed) {
                    val delay = it.changes.first().scrollDelta.y
                    if (delay != 0f) {
                        val direction = if (delay > 0) -1 else 1
                        playerModel.setVolume(
                            (playerModel.volume.value + direction * 0.02f).coerceIn(
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

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedHeight)
                .onGloballyPositioned { parentCoordinates = it },
            color = background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp
        ) {
            val colorScheme by rememberCoverScheme(currentSong?.coverId, isDark = isAppDark())
            val animatedScheme by animateColorSchemeAsState(
                targetColorScheme = if (isExpanded) colorScheme else MaterialTheme.colorScheme,
            )

            MaterialTheme(
                colorScheme = animatedScheme
            ) {
                BlurredCoverBackground(
                    song = currentSong,
                    alpha = blurredAlpha,
                    audioReactive = true,
                    modifier = Modifier.fillMaxSize()
                ) {
                    val sizeResolver = rememberConstraintsSizeResolver()
                    val coverCenter = remember { mutableStateOf(Offset.Unspecified) }

                    val (colorA, colorB) = sort(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiary
                    )

                    ParticleView(
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
                                        liveBitRate = liveBitRate,
                                        liveSampleRate = liveSampleRate,
                                        liveBitsPerSample = liveBitsPerSample,
                                        onToggleExpanded = { globalState.togglePlayerExpanded() },
                                        onArtistClick = { globalState.setPlayerExpanded(false) },
                                        onLikeClick = { playerModel.toggleLike() },
                                        onSecondaryClick = { showSongContextMenu = true },
                                        modifier = Modifier.weight(1f)
                                    )

                                    PlayerControls(
                                        isPlaying = isPlaying,
                                        currentSongExists = currentSong != null,
                                        onSkipPrevious = { playerModel.skipPrevious() },
                                        onTogglePlayPause = { playerModel.togglePlayPause() },
                                        onSkipNext = { playerModel.skipNext() },
                                        modifier = Modifier.weight(1.2f)
                                    )

                                    PlayerActions(
                                        shuffleMode = shuffleMode,
                                        repeatMode = repeatMode,
                                        volume = volume,
                                        currentSongExists = currentSong != null,
                                        isCompact = isCompact,
                                        onToggleShuffle = { playerModel.toggleShuffle() },
                                        onToggleRepeat = { playerModel.toggleRepeat() },
                                        onVolumeChange = { playerModel.setVolume(it) },
                                        modifier = Modifier.weight(1f)
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
                                        playerModel.seekTo(seekPosition)
                                        isSeeking = false
                                        isWaitingForPosition = true
                                    }
                                )
                            }

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

        currentSong?.let { song ->
            SongContextMenu(
                song = song,
                expanded = showSongContextMenu,
                onDismissRequest = { showSongContextMenu = false }
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ExpandedPlayerContent(
    currentSong: UserSong?,
    sizeResolver: ConstraintsSizeResolver,
    coverCenter: MutableState<Offset>,
    parentCoordinates: LayoutCoordinates? = null,
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

                        Spacer(modifier = Modifier.height(32.dp))

                        val (colorA, colorB) = sort(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiary
                        )

                        VisualizerView(
                            modifier = Modifier
                                .fillMaxWidth(visualizerWidthScale)
                                .requiredHeight(80.dp),
                            highlightColor = colorA,
                            color = colorB
                        )
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
                                    LyricsView()
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

                        "queue" -> {
                            QueueView(modifier = Modifier.fillMaxSize())
                        }

                        "lyrics" -> {
                            LyricsView(modifier = Modifier.fillMaxSize())
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
    sizeResolver: ConstraintsSizeResolver
) {
    AnimatedContent(
        targetState = song?.coverId,
        transitionSpec = {
            fadeIn(tween(500)) togetherWith fadeOut(tween(500))
        },
        label = "largeCoverTransition",
        modifier = modifier
    ) { coverId ->
        SynaraImage(
            imageId = coverId,
            modifier = Modifier.fillMaxSize().then(sizeResolver),
            shape = RoundedCornerShape(16.dp),
            fallbackIcon = SynaraIcons.Songs
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VolumeControl(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    isCompact: Boolean
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

    LaunchedEffect(isHovered, isPopupHovered) {
        if (isHovered || isPopupHovered) {
            showPopup = true
        } else {
            delay(150)
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
                    modifier = Modifier.width(100.dp).height(12.dp),
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
