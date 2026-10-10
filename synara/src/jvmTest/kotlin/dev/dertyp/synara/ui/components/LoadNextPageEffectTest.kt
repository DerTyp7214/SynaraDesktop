package dev.dertyp.synara.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LoadNextPageEffectTest {

    private class Harness(itemCount: Int, private val pageSize: Int) {
        val itemCount = mutableIntStateOf(itemCount)
        val revision = mutableIntStateOf(0)
        val requests = mutableListOf<Int>()

        fun request() {
            requests += itemCount.intValue
            itemCount.intValue += pageSize
        }
    }

    private fun listTest(itemCount: Int, pageSize: Int = 0, block: ComposeUiTest.(Harness) -> Unit) = runComposeUiTest {
        val harness = Harness(itemCount, pageSize)
        setContent {
            val listState = rememberLazyListState()
            LoadNextPageEffect(listState, threshold = 3) { harness.request() }
            LazyColumn(state = listState, modifier = Modifier.height((210 + harness.revision.intValue).dp)) {
                items(harness.itemCount.intValue) { Box(Modifier.fillMaxWidth().height(20.dp)) }
            }
        }
        waitForIdle()
        block(harness)
    }

    private fun gridTest(itemCount: Int, block: ComposeUiTest.(Harness) -> Unit) = runComposeUiTest {
        val harness = Harness(itemCount, pageSize = 0)
        setContent {
            val gridState = rememberLazyGridState()
            LoadNextPageEffect(gridState, threshold = 3) { harness.request() }
            LazyVerticalGrid(columns = GridCells.Fixed(2), state = gridState, modifier = Modifier.height(210.dp)) {
                items(harness.itemCount.intValue) { Box(Modifier.fillMaxWidth().height(20.dp)) }
            }
        }
        waitForIdle()
        block(harness)
    }

    private fun ComposeUiTest.scrollToTheEnd(harness: Harness) {
        onNode(hasScrollToIndexAction()).performScrollToIndex(harness.itemCount.intValue - 1)
        waitForIdle()
    }

    @Test
    fun nothingIsRequestedWhileTheEndOfTheMeasuredListIsOutOfReach() = listTest(itemCount = 100) { harness ->
        assertEquals(emptyList(), harness.requests)
    }

    @Test
    fun nothingIsRequestedWhileTheEndOfTheMeasuredGridIsOutOfReach() = gridTest(itemCount = 100) { harness ->
        assertEquals(emptyList(), harness.requests)
    }

    @Test
    fun reachingTheEndOfTheListRequestsTheNextPageOnce() = listTest(itemCount = 100) { harness ->
        scrollToTheEnd(harness)

        assertEquals(listOf(100), harness.requests)
    }

    @Test
    fun aReloadThatShrinksTheListWhileTheEndIsVisibleRequestsTheNextPage() = listTest(itemCount = 100) { harness ->
        scrollToTheEnd(harness)
        harness.requests.clear()

        harness.itemCount.intValue = 30
        waitForIdle()

        assertEquals(listOf(30), harness.requests)
    }

    @Test
    fun aReloadThatShrinksTheGridWhileTheEndIsVisibleRequestsTheNextPage() = gridTest(itemCount = 100) { harness ->
        scrollToTheEnd(harness)
        assertEquals(listOf(100), harness.requests)
        harness.requests.clear()

        harness.itemCount.intValue = 30
        waitForIdle()

        assertEquals(listOf(30), harness.requests)
    }

    @Test
    fun aFirstPageThatDoesNotFillTheViewportIsFollowedByFurtherPagesUntilTheEndIsOutOfReach() =
        listTest(itemCount = 4, pageSize = 4) { harness ->
            assertEquals(listOf(4, 8, 12), harness.requests)
            assertEquals(16, harness.itemCount.intValue)

            harness.revision.intValue++
            waitForIdle()

            assertEquals(listOf(4, 8, 12), harness.requests)
        }

    @Test
    fun aRequestThatAddsNothingIsNotRepeated() = listTest(itemCount = 100) { harness ->
        scrollToTheEnd(harness)
        assertEquals(listOf(100), harness.requests)

        harness.revision.intValue++
        waitForIdle()

        assertEquals(listOf(100), harness.requests)
    }
}
