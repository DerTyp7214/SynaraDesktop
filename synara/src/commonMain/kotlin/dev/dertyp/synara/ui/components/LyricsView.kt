package dev.dertyp.synara.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.lyrics.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.lyrics_failed
import synara.synara.generated.resources.lyrics_loading
import synara.synara.generated.resources.lyrics_none

@Composable
fun LyricsView(
    lyrics: LyricsState,
    position: StateFlow<Long>,
    isPlaying: StateFlow<Boolean>,
    seekEvents: Flow<Long>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {}
) {
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = lyrics,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { state ->
                when (state) {
                    is LyricsState.Loaded -> state.display
                    else -> state
                }
            },
            label = "lyricsContent",
            modifier = Modifier.fillMaxSize()
        ) { state ->
            when (state) {
                LyricsState.Loading -> LyricsMessage(stringResource(Res.string.lyrics_loading), loading = true)
                LyricsState.Failed -> LyricsMessage(stringResource(Res.string.lyrics_failed))
                LyricsState.Empty -> LyricsMessage(stringResource(Res.string.lyrics_none))
                is LyricsState.Loaded -> {
                    if (state.display.isEmpty) {
                        LyricsMessage(stringResource(Res.string.lyrics_none))
                    } else {
                        SongLyrics(state.display, position, isPlaying, seekEvents, onSeek)
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
        ) {
            header()
        }
    }
}

@Composable
fun LyricsView(
    lines: List<DisplayLine>,
    position: StateFlow<Long>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.headlineSmall.copy(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        textAlign = TextAlign.Center
    ),
    followLabel: String? = null
) {
    TimedLinesView(
        items = remember(lines) { lines.map(LyricsItem::Line) },
        position = rememberReportedPosition(position),
        onSeek = onSeek,
        modifier = modifier,
        textStyle = textStyle,
        followLabel = followLabel
    )
}

@Composable
private fun SongLyrics(
    display: LyricsDisplay,
    position: StateFlow<Long>,
    isPlaying: StateFlow<Boolean>,
    seekEvents: Flow<Long>,
    onSeek: (Long) -> Unit
) {
    val clock = if (display.wordTimed) {
        rememberLyricsPosition(position, isPlaying, seekEvents)
    } else {
        rememberReportedPosition(position)
    }
    TimedLinesView(
        items = display.items,
        position = clock,
        onSeek = onSeek,
        modifier = Modifier,
        textStyle = MaterialTheme.typography.headlineMedium.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            textAlign = TextAlign.Center
        ),
        followLabel = null
    )
}

@Composable
private fun LyricsMessage(text: String, loading: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Color.White.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.height(16.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TimedLinesView(
    items: List<LyricsItem>,
    position: LongState,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
    textStyle: TextStyle,
    followLabel: String?
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember(items) { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }

    var activeIndex by remember(items, position) {
        mutableIntStateOf(Snapshot.withoutReadObservation { activeItemAt(items, position.longValue) })
    }

    LaunchedEffect(items, position) {
        snapshotFlow { activeItemAt(items, position.longValue) }
            .collect { activeIndex = it }
    }

    if (followLabel != null) {
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress && !programmaticScroll }
                .collect { if (it) follow = false }
        }
    }

    LaunchedEffect(activeIndex, follow) {
        if (follow && items.isNotEmpty() && activeIndex >= 0) {
            programmaticScroll = true
            try {
                listState.animateScrollToItem(activeIndex, scrollOffset = -200)
            } finally {
                programmaticScroll = false
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val fadeHeight = 150.dp.toPx()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            fadeHeight / size.height to Color.Black,
                            (size.height - fadeHeight) / size.height to Color.Black,
                            1f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                },
            contentPadding = PaddingValues(vertical = 250.dp, horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            itemsIndexed(
                items = items,
                key = { index, _ -> index }
            ) { index, item ->
                when (item) {
                    is LyricsItem.Line -> LyricsRow(
                        line = item.line,
                        position = position,
                        active = index == activeIndex,
                        style = textStyle,
                        onSeek = onSeek
                    )
                    is LyricsItem.Gap -> LyricsGapRow(
                        gap = item,
                        position = position,
                        active = index == activeIndex,
                        onSeek = onSeek
                    )
                }
            }
        }

        if (followLabel != null) {
            AnimatedVisibility(
                visible = !follow,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
            ) {
                SynaraSmallFab(
                    onClick = {
                        follow = true
                        scope.launch {
                            programmaticScroll = true
                            try {
                                listState.animateScrollToItem(activeIndex, scrollOffset = -200)
                            } finally {
                                programmaticScroll = false
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(
                        SynaraIcons.Transcript.get(),
                        contentDescription = followLabel,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
