package dev.dertyp.synara.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dertyp.synara.player.PlayerModel
import org.koin.compose.koinInject

data class LyricLine(
    val time: Long,
    val content: String
)

@Composable
fun LyricsView(
    modifier: Modifier = Modifier,
    playerModel: PlayerModel = koinInject()
) {
    val currentSong by playerModel.currentSong.collectAsState()
    val currentPosition by playerModel.currentPosition.collectAsState()
    
    val lyrics = currentSong?.lyrics ?: ""
    val parsedLyrics = remember(lyrics) { parseLyrics(lyrics) }
    val listState = rememberLazyListState()

    val activeIndex = remember(parsedLyrics, currentPosition) {
        parsedLyrics.indexOfLast { it.time <= currentPosition }.coerceAtLeast(0)
    }

    LaunchedEffect(activeIndex) {
        if (parsedLyrics.isNotEmpty() && activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex, scrollOffset = -200)
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
                key = { _, line -> line.time.toString() + line.content }
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

                Text(
                    text = line.content,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        lineHeight = 40.sp,
                        textAlign = TextAlign.Start
                    ),
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .scale(scale)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { playerModel.seekTo(line.time) }
                        .padding(vertical = 12.dp, horizontal = 16.dp)
                )
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
