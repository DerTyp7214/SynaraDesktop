package dev.dertyp.synara.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
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
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.ConstraintsSizeResolver
import coil3.compose.rememberConstraintsSizeResolver
import dev.dertyp.core.cleanTitle
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.synara.animateColorSchemeAsState
import dev.dertyp.synara.onSurfaceVariantDistinct
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.scrobble.BaseScrobbler
import dev.dertyp.synara.scrobble.ScrobblerService
import dev.dertyp.synara.theme.isAppDark
import dev.dertyp.synara.theme.rememberCoverScheme
import dev.dertyp.synara.ui.LocalWindowActions
import dev.dertyp.synara.ui.components.menus.SongContextMenu
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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

                        // Player Bar Content (always at the bottom)
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
                                    // Song Info
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AnimatedContent(
                                            targetState = currentSong?.coverId,
                                            transitionSpec = {
                                                fadeIn(tween(500)) togetherWith fadeOut(tween(500))
                                            },
                                            label = "smallCoverTransition"
                                        ) { coverId ->
                                            SynaraImage(
                                                imageId = coverId,
                                                size = 56.dp,
                                                modifier = Modifier.clickable { globalState.togglePlayerExpanded() },
                                                fallbackIcon = Icons.Rounded.MusicNote
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        AnimatedContent(
                                            targetState = currentSong,
                                            transitionSpec = {
                                                fadeIn(tween(400)) togetherWith fadeOut(tween(400))
                                            },
                                            label = "smallSongInfoTransition",
                                            modifier = Modifier.weight(1f, fill = false)
                                        ) { song ->
                                            Column {
                                                Text(
                                                    text = song?.title?.cleanTitle()
                                                        ?: stringResource(
                                                            Res.string.not_playing
                                                        ),
                                                    modifier = Modifier
                                                        .pointerInput(song?.id) {
                                                            detectTapGestures(
                                                                onLongPress = {
                                                                    if (song != null) showSongContextMenu = true
                                                                },
                                                            )
                                                        }
                                                        .onPointerEvent(PointerEventType.Release) {
                                                            if (it.button == PointerButton.Secondary && song != null) {
                                                                showSongContextMenu = true
                                                            }
                                                        }
                                                        .pointerHoverIcon(if (song != null) PointerIcon.Hand else PointerIcon.Default),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                
                                                if (song != null) {
                                                    ArtistsText(
                                                        artists = song.artists,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        onArtistClick = {
                                                            globalState.setPlayerExpanded(false)
                                                        }
                                                    )
                                                }

                                                song?.let { s ->
                                                    val bitRate =
                                                        if (liveBitRate > 0) liveBitRate else s.bitRate
                                                    val sampleRate =
                                                        if (liveSampleRate > 0) liveSampleRate.toLong() else s.sampleRate.toLong()
                                                    val bits =
                                                        if (liveBitsPerSample > 0) liveBitsPerSample else s.bitsPerSample

                                                    if (bitRate > 0 || sampleRate > 0) {
                                                        Text(
                                                            text = buildString {
                                                                if (bitRate > 0) append("$bitRate kbps")
                                                                if (bitRate > 0 && (bits > 0 || sampleRate > 0)) append(
                                                                    " • "
                                                                )
                                                                if (bits > 0) append("$bits bit")
                                                                if (bits > 0 && sampleRate > 0) append(
                                                                    " • "
                                                                )
                                                                if (sampleRate > 0) {
                                                                    if (sampleRate > 1000) {
                                                                        append("${sampleRate / 1000.0} kHz")
                                                                    } else {
                                                                        append("$sampleRate kHz")
                                                                    }
                                                                }
                                                            },
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontSize = 8.sp,
                                                                fontWeight = FontWeight.Bold
                                                            ),
                                                            color = MaterialTheme.colorScheme.primary.copy(
                                                                alpha = 0.7f
                                                            ),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        if (currentSong != null) {
                                            IconButton(
                                                onClick = { playerModel.toggleLike() },
                                                modifier = Modifier.offset(y = (-8).dp)
                                            ) {
                                                Icon(
                                                    if (currentSong?.isFavourite == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                                    contentDescription = stringResource(Res.string.favorite),
                                                    tint = if (currentSong?.isFavourite == true) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                                )
                                            }
                                        }
                                    }

                                    // Controls
                                    Column(
                                        modifier = Modifier.weight(1.2f),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            IconButton(
                                                onClick = { playerModel.skipPrevious() },
                                                enabled = currentSong != null
                                            ) {
                                                Icon(
                                                    Icons.Rounded.SkipPrevious,
                                                    contentDescription = stringResource(Res.string.previous)
                                                )
                                            }

                                            LargeFloatingActionButton(
                                                onClick = { playerModel.togglePlayPause() },
                                                modifier = Modifier.size(56.dp),
                                                shape = MaterialTheme.shapes.medium,
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                elevation = FloatingActionButtonDefaults.elevation(
                                                    0.dp,
                                                    0.dp,
                                                    0.dp,
                                                    0.dp
                                                )
                                            ) {
                                                PlayPauseIcon(
                                                    isPlaying = isPlaying,
                                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }

                                            IconButton(
                                                onClick = { playerModel.skipNext() },
                                                enabled = currentSong != null
                                            ) {
                                                Icon(
                                                    Icons.Rounded.SkipNext,
                                                    contentDescription = stringResource(Res.string.next_song)
                                                )
                                            }
                                        }
                                    }

                                    // Volume and other actions
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = { playerModel.toggleShuffle() },
                                            enabled = currentSong != null
                                        ) {
                                            Icon(
                                                Icons.Rounded.Shuffle,
                                                contentDescription = stringResource(Res.string.shuffle),
                                                tint = if (shuffleMode) MaterialTheme.colorScheme.onSurfaceVariantDistinct() else LocalContentColor.current
                                            )
                                        }
                                        IconButton(
                                            onClick = { playerModel.toggleRepeat() },
                                            enabled = currentSong != null
                                        ) {
                                            Icon(
                                                when (repeatMode) {
                                                    RepeatMode.ONE -> Icons.Rounded.RepeatOne
                                                    else -> Icons.Rounded.Repeat
                                                },
                                                contentDescription = stringResource(Res.string.repeat),
                                                tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariantDistinct() else LocalContentColor.current
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        VolumeControl(
                                            volume = volume,
                                            onVolumeChange = { playerModel.setVolume(it) },
                                            isCompact = isCompact
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        formatDuration(currentPosition.coerceAtMost(duration)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.widthIn(min = 40.dp)
                                    )

                                    Slider(
                                        value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                                        onValueChange = {
                                            isSeeking = true
                                            seekPosition = (it * duration).toLong()
                                        },
                                        onValueChangeFinished = {
                                            playerModel.seekTo(seekPosition)
                                            isSeeking = false
                                            isWaitingForPosition = true
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp)
                                            .height(12.dp),
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary,
                                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        enabled = currentSong != null
                                    )

                                    Text(
                                        formatDuration(duration),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.widthIn(min = 40.dp),
                                        textAlign = TextAlign.End
                                    )
                                }
                            }

                            // Scrobble Indicator in the top right corner
                            currentSong?.let { song ->
                                val durationLeft = BaseScrobbler.requiredDuration(
                                    scrobbledFor.seconds,
                                    song.duration.milliseconds
                                )
                                val scrobbled =
                                    durationLeft.inWholeSeconds <= 0 || triggeredSong?.id == song.id

                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 10.dp, end = 16.dp)
                                ) {
                                    AnimatedContent(
                                        targetState = scrobbled,
                                        label = "scrobbleIndicator"
                                    ) { isScrobbled ->
                                        if (isScrobbled) {
                                            Icon(
                                                Icons.Rounded.CheckCircle,
                                                contentDescription = stringResource(Res.string.song_scrobbled),
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    modifier = Modifier.padding(top = 2.dp),
                                                    text = formatDuration(durationLeft.inWholeMilliseconds),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 10.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                        alpha = 0.6f
                                                    )
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Icon(
                                                    Icons.Rounded.Schedule,
                                                    contentDescription = stringResource(
                                                        Res.string.pending_scrobble
                                                    ),
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                        alpha = 0.6f
                                                    )
                                                )
                                            }
                                        }
                                    }
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
                        Icons.Rounded.KeyboardArrowDown,
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
                                Icons.Rounded.Lyrics,
                                contentDescription = "Lyrics",
                                modifier = Modifier.size(28.dp),
                                tint = if (isLyricsShowing) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
                    }

                    IconButton(onClick = { globalState.toggleQueueExpanded() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = "Queue",
                            modifier = Modifier.size(28.dp),
                            tint = if (isQueueShowing) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                }

                IconButton(onClick = { windowActions.toggleFullscreen() }) {
                    Icon(
                        if (windowActions.isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
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
            fallbackIcon = Icons.Rounded.MusicNote
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VolumeControl(
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
        volume == 0f -> Icons.AutoMirrored.Rounded.VolumeOff
        volume < 0.33f -> Icons.AutoMirrored.Rounded.VolumeMute
        volume < 0.67f -> Icons.AutoMirrored.Rounded.VolumeDown
        else -> Icons.AutoMirrored.Rounded.VolumeUp
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
