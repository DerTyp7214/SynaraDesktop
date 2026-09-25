package dev.dertyp.synara.ui.components.podcast

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.podcast.PodcastPlayer
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraSmallFab
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PodcastQueueView(
    modifier: Modifier = Modifier,
    podcastPlayer: PodcastPlayer = koinInject(),
    globalState: GlobalStateModel = koinInject()
) {
    val queue by podcastPlayer.queue.collectAsState()
    val currentIndex by podcastPlayer.currentIndex.collectAsState()
    val isPlaying by podcastPlayer.isPlaying.collectAsState()
    val isQueueExpanded by globalState.isQueueExpanded.collectAsState()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val itemHeightPx = with(LocalDensity.current) { 64.dp.toPx() }
    val uniqueIds = remember(queue) { queue.map { it.id }.toSet().size == queue.size }

    val showScrollToCurrent by remember(currentIndex, queue.size) {
        derivedStateOf {
            if (currentIndex < 0 || currentIndex >= queue.size) false
            else {
                val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                if (visibleItems.isEmpty()) false
                else visibleItems.none { it.index == currentIndex }
            }
        }
    }

    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var draggingOffset by remember { mutableStateOf(0f) }

    LaunchedEffect(isQueueExpanded, currentIndex) {
        if (isQueueExpanded && currentIndex >= 0 && currentIndex < queue.size) {
            val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
            val isVisible = visibleItems.any { it.index == currentIndex }

            if (!isVisible) {
                val firstVisible = lazyListState.firstVisibleItemIndex
                val distance = abs(firstVisible - currentIndex)

                try {
                    if (distance > 15) {
                        lazyListState.scrollToItem(currentIndex)
                    } else {
                        lazyListState.animateScrollToItem(currentIndex)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.podcast_queue_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            if (queue.isNotEmpty()) {
                TextButton(onClick = { podcastPlayer.clearQueue() }) {
                    Icon(
                        SynaraIcons.Clear.get(),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(Res.string.podcast_clear_queue))
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(top = 48.dp)) {
            if (queue.isEmpty()) {
                Text(
                    text = stringResource(Res.string.podcast_queue_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        val fadeHeight = 48.dp.toPx()

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
                state = lazyListState,
                contentPadding = PaddingValues(top = 48.dp, bottom = 48.dp)
            ) {
                itemsIndexed(
                    items = queue,
                    key = { index, episode -> if (uniqueIds) episode.id.toString() else "$index-${episode.id}" }
                ) { index, episode ->
                    val isCurrent = index == currentIndex
                    val isDragging = draggedItemIndex == index
                    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .shadow(elevation)
                            .background(if (isDragging) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f) else Color.Transparent)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isCurrent) {
                                Icon(
                                    imageVector = SynaraIcons.DragHandle.get(),
                                    contentDescription = "Reorder",
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .size(24.dp)
                                        .pointerInput(Unit) {
                                            detectDragGestures(
                                                onDragStart = { draggedItemIndex = index },
                                                onDragEnd = {
                                                    draggedItemIndex = null
                                                    draggingOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggedItemIndex = null
                                                    draggingOffset = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    draggingOffset += dragAmount.y

                                                    val currentIdx = draggedItemIndex ?: return@detectDragGestures
                                                    val targetOffset = (draggingOffset / itemHeightPx).toInt()

                                                    if (targetOffset != 0) {
                                                        val targetIndex = (currentIdx + targetOffset).coerceIn(0, queue.size - 1)
                                                        if (targetIndex != currentIdx) {
                                                            podcastPlayer.moveInQueue(currentIdx, targetIndex)
                                                            draggedItemIndex = targetIndex
                                                            draggingOffset = 0f
                                                        }
                                                    }
                                                }
                                            )
                                        },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Spacer(modifier = Modifier.padding(start = 12.dp).size(24.dp))
                            }

                            EpisodeItem(
                                episode = episode,
                                isCurrent = isCurrent,
                                isPlaying = isCurrent && isPlaying,
                                showShowTitle = true,
                                onClick = { podcastPlayer.playAt(index) },
                                modifier = Modifier.weight(1f),
                                trailingContent = {
                                    IconButton(onClick = { podcastPlayer.removeFromQueue(index) }) {
                                        Icon(
                                            SynaraIcons.RemoveFromQueue.get(),
                                            contentDescription = stringResource(Res.string.remove_from_queue),
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(lazyListState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 48.dp, horizontal = 4.dp)
            )

            AnimatedVisibility(
                visible = showScrollToCurrent,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp)
            ) {
                SynaraSmallFab(
                    onClick = {
                        scope.launch {
                            val firstVisible = lazyListState.firstVisibleItemIndex
                            val distance = abs(firstVisible - currentIndex)
                            if (distance > 100) {
                                lazyListState.scrollToItem(currentIndex)
                            } else {
                                lazyListState.animateScrollToItem(currentIndex)
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(
                        SynaraIcons.Podcast.get(),
                        contentDescription = stringResource(Res.string.podcast_now_playing),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
