package dev.dertyp.synara.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.dertyp.data.UserSong
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueView(
    modifier: Modifier = Modifier,
    playerModel: PlayerModel = koinInject(),
    globalState: GlobalStateModel = koinInject()
) {
    val queue by playerModel.queue.collectAsState()
    val currentIndex by playerModel.currentIndex.collectAsState()
    val isQueueExpanded by globalState.isQueueExpanded.collectAsState()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

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
                
                if (distance > 15) {
                    lazyListState.scrollToItem(currentIndex)
                } else {
                    lazyListState.animateScrollToItem(currentIndex)
                }
            }
        }
    }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            val layoutInfo = lazyListState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) null
            else visibleItems.first().index..visibleItems.last().index
        }.collectLatest { range ->
            playerModel.setRequestedWindow(range)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
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
                key = { _, entry -> entry.queueId }
            ) { index, entry ->
                val isCurrent = index == currentIndex
                val isDragging = draggedItemIndex == index
                val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)

                val songState = remember(entry) { mutableStateOf<UserSong?>(null) }
                LaunchedEffect(entry) {
                    songState.value = playerModel.resolveSong(entry)
                }

                val song = songState.value
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .shadow(elevation)
                        .background(if (isDragging) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f) else Color.Transparent)
                ) {
                    if (song != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isCurrent) {
                                Icon(
                                    imageVector = Icons.Rounded.DragHandle,
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
                                                    val itemHeight = 60f
                                                    val targetOffset = (draggingOffset / itemHeight).toInt()
                                                    
                                                    if (targetOffset != 0) {
                                                        val targetIndex = (currentIdx + targetOffset).coerceIn(0, queue.size - 1)
                                                        if (targetIndex != currentIdx) {
                                                            playerModel.moveQueueItem(currentIdx, targetIndex)
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
                            
                            SongItem(
                                song = song,
                                isCurrent = isCurrent,
                                showCover = true,
                                onClick = { playerModel.playEntry(entry) },
                                isInQueue = true,
                                onRemoveFromQueue = { playerModel.removeFromQueue(entry) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
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
            SmallFloatingActionButton(
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
                    Icons.Rounded.MusicNote,
                    contentDescription = "Scroll to current song",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}