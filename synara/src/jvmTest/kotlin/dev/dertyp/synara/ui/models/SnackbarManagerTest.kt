package dev.dertyp.synara.ui.models

import androidx.compose.material3.SnackbarDuration
import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnackbarManagerTest {

    private object UnconfinedDispatchers : SynaraDispatchers {
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Test
    fun snackbarsAreShownOneAfterAnotherInOrder() {
        val manager = SnackbarManager(UnconfinedDispatchers)
        manager.showSnackbar("first", duration = SnackbarDuration.Long)
        manager.showSnackbar("second", duration = SnackbarDuration.Long)

        assertEquals("first", manager.snackbarHostState.currentSnackbarData?.visuals?.message)
        manager.snackbarHostState.currentSnackbarData?.dismiss()
        assertEquals("second", manager.snackbarHostState.currentSnackbarData?.visuals?.message)
        manager.snackbarHostState.currentSnackbarData?.dismiss()
        assertNull(manager.snackbarHostState.currentSnackbarData)
    }

    @Test
    fun anIndefiniteSnackbarIsReplacedByTheNextOne() {
        val manager = SnackbarManager(UnconfinedDispatchers)
        manager.showSnackbar("running", duration = SnackbarDuration.Indefinite)
        manager.showSnackbar("result", duration = SnackbarDuration.Long)

        assertEquals("result", manager.snackbarHostState.currentSnackbarData?.visuals?.message)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun snackbarsExpireWhileNothingComposesTheHost() {
        val scheduler = TestCoroutineScheduler()
        val manager = SnackbarManager(
            object : SynaraDispatchers {
                override val main: CoroutineDispatcher = StandardTestDispatcher(scheduler)
            }
        )
        manager.showSnackbar("first")
        manager.showSnackbar("second", duration = SnackbarDuration.Long)
        manager.showSnackbar("running", duration = SnackbarDuration.Indefinite)
        scheduler.runCurrent()
        assertEquals("first", manager.snackbarHostState.currentSnackbarData?.visuals?.message)

        scheduler.advanceTimeBy(5_000)
        scheduler.runCurrent()
        assertEquals("second", manager.snackbarHostState.currentSnackbarData?.visuals?.message)

        scheduler.advanceTimeBy(11_000)
        scheduler.runCurrent()
        assertEquals("running", manager.snackbarHostState.currentSnackbarData?.visuals?.message)

        scheduler.advanceTimeBy(3_600_000)
        scheduler.runCurrent()
        assertEquals("running", manager.snackbarHostState.currentSnackbarData?.visuals?.message)

        manager.showSnackbar("result")
        scheduler.runCurrent()
        assertEquals("result", manager.snackbarHostState.currentSnackbarData?.visuals?.message)

        scheduler.advanceTimeBy(5_000)
        scheduler.runCurrent()
        assertNull(manager.snackbarHostState.currentSnackbarData)
    }
}
