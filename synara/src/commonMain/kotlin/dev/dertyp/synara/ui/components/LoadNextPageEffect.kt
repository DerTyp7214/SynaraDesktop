package dev.dertyp.synara.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

@Composable
fun LoadNextPageEffect(listState: LazyListState, threshold: Int, onLoadNextPage: () -> Unit) {
    val itemCountNearEnd by remember(listState, threshold) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            itemCountIfNearEnd(layoutInfo.totalItemsCount, layoutInfo.visibleItemsInfo.lastOrNull()?.index, threshold)
        }
    }
    LaunchedEffect(itemCountNearEnd) {
        if (itemCountNearEnd != null) onLoadNextPage()
    }
}

@Composable
fun LoadNextPageEffect(gridState: LazyGridState, threshold: Int, onLoadNextPage: () -> Unit) {
    val itemCountNearEnd by remember(gridState, threshold) {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            itemCountIfNearEnd(layoutInfo.totalItemsCount, layoutInfo.visibleItemsInfo.lastOrNull()?.index, threshold)
        }
    }
    LaunchedEffect(itemCountNearEnd) {
        if (itemCountNearEnd != null) onLoadNextPage()
    }
}

private fun itemCountIfNearEnd(totalItems: Int, lastVisibleIndex: Int?, threshold: Int): Int? =
    totalItems.takeIf { lastVisibleIndex != null && lastVisibleIndex + 1 > totalItems - threshold }
