package dev.dertyp.synara.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dertyp.data.UserSong
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.ui.SynaraIcons
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.koinInject

data class LyricLine(
    val time: Long,
    val content: String,
    val label: String? = null
)

@Composable
fun LyricsView(
    modifier: Modifier = Modifier,
    playerModel: PlayerModel = koinInject(),
    song: UserSong? = playerModel.currentSong.collectAsState().value,
    position: StateFlow<Long> = playerModel.currentPosition,
    onSeek: (Long) -> Unit = { playerModel.seekTo(it) }
) {
    val lyrics = song?.lyrics ?: ""
    val parsedLyrics = remember(lyrics) { parseLyrics(lyrics) }

    TimedLinesView(
        lines = parsedLyrics,
        position = position,
        onSeek = onSeek,
        modifier = modifier,
        textStyle = MaterialTheme.typography.headlineMedium.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            textAlign = TextAlign.Center
        ),
        key = { _, line -> line.time.toString() + line.content },
        followLabel = null
    )
}

@Composable
fun LyricsView(
    lines: List<LyricLine>,
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
        lines = lines,
        position = position,
        onSeek = onSeek,
        modifier = modifier,
        textStyle = textStyle,
        key = { index, _ -> index },
        followLabel = followLabel
    )
}

@Composable
private fun TimedLinesView(
    lines: List<LyricLine>,
    position: StateFlow<Long>,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
    textStyle: TextStyle,
    key: (Int, LyricLine) -> Any,
    followLabel: String?
) {
    val currentPosition by position.collectAsState()

    val parsedLyrics = lines
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember(lines) { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }

    val activeIndex = remember(parsedLyrics, currentPosition) {
        parsedLyrics.indexOfLast { it.time <= currentPosition }.coerceAtLeast(0)
    }

    if (followLabel != null) {
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress && !programmaticScroll }
                .collect { if (it) follow = false }
        }
    }

    LaunchedEffect(activeIndex, follow) {
        if (follow && parsedLyrics.isNotEmpty() && activeIndex >= 0) {
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
                items = parsedLyrics,
                key = key
            ) { index, line ->
                val isActive = index == activeIndex
                val color by animateColorAsState(
                    targetValue = if (isActive) Color.White
                    else Color.White.copy(alpha = 0.4f),
                    label = "lyricColor"
                )
                val scale by animateFloatAsState(
                    targetValue = if (isActive) 1.05f else 1f,
                    label = "lyricScale"
                )

                if (line.label != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .scale(scale)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSeek(line.time) }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = line.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = color.copy(alpha = color.alpha * 0.8f),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = line.content,
                            style = textStyle,
                            color = color,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Text(
                        text = line.content,
                        style = textStyle,
                        color = color,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .scale(scale)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSeek(line.time) }
                            .padding(vertical = 12.dp, horizontal = 16.dp)
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

private fun parseLyrics(lyrics: String): List<LyricLine> {
    if (lyrics.isBlank()) return emptyList()

    val lyricLines = mutableListOf<LyricLine>()
    val timeRegex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})]""")

    lyrics.lines().forEach { line ->
        val match = timeRegex.find(line)
        if (match != null) {
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val msStr = match.groupValues[3]
            val ms = msStr.toLong() * (if (msStr.length == 2) 10 else 1)
            val time = min * 60 * 1000 + sec * 1000 + ms
            val content = line.substring(match.range.last + 1).trim()
            if (content.isNotEmpty()) {
                lyricLines.add(LyricLine(time, content))
            }
        } else if (line.isNotBlank() && !line.startsWith("[")) {
            lyricLines.add(LyricLine(0, line.trim()))
        }
    }

    return if (lyricLines.all { it.time == 0L }) {
        lyricLines.mapIndexed { index, line -> line.copy(time = index * 2000L) }
    } else {
        lyricLines.sortedBy { it.time }
    }
}
