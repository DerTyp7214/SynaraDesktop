package dev.dertyp.synara.viewmodels

import androidx.compose.ui.input.key.Key
import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RefreshTest {
    private class CountingTarget : Refreshable {
        override val isRefreshing: StateFlow<Boolean> = MutableStateFlow(false)
        var count = 0
        override fun refresh() {
            count++
        }
    }

    private class RecordingLogger : Logger {
        val errors = mutableListOf<Throwable?>()

        override fun info(tag: LogTag, message: String, data: Any?) = Unit
        override fun warning(tag: LogTag, message: String, data: Any?) = Unit
        override fun error(tag: LogTag, message: String, throwable: Throwable?, data: Any?) {
            errors += throwable
        }
        override fun crash(tag: LogTag, message: String, throwable: Throwable?, data: Any?) = Unit
        override suspend fun infoSuspended(tag: LogTag, message: String, data: Any?) = Unit
        override suspend fun warningSuspended(tag: LogTag, message: String, data: Any?) = Unit
        override suspend fun errorSuspended(tag: LogTag, message: String, throwable: Throwable?, data: Any?) = Unit
        override suspend fun crashSuspended(tag: LogTag, message: String, throwable: Throwable?, data: Any?) = Unit
    }

    private val logger = RecordingLogger()

    @BeforeTest
    fun startKoinWithLogger() {
        stopKoin()
        startKoin { modules(module { single<Logger> { this@RefreshTest.logger } }) }
    }

    @AfterTest
    fun stopKoinAgain() {
        stopKoin()
    }

    @Test
    fun concurrentRefreshesCoalesceIntoOneRun() {
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val coalescer = RefreshCoalescer(CoroutineScope(Dispatchers.Unconfined)) {
            runs++
            gate.await()
        }

        assertTrue(coalescer.refresh())
        assertTrue(coalescer.isRefreshing.value)
        assertFalse(coalescer.refresh())
        assertFalse(coalescer.refresh())
        assertEquals(1, runs)

        gate.complete(Unit)
        assertFalse(coalescer.isRefreshing.value)

        assertTrue(coalescer.refresh())
        assertEquals(2, runs)
        assertFalse(coalescer.isRefreshing.value)
    }

    @Test
    fun failedRefreshReleasesTheGate() {
        var runs = 0
        val coalescer = RefreshCoalescer(CoroutineScope(Dispatchers.Unconfined)) {
            runs++
            error("boom")
        }

        assertTrue(coalescer.refresh())
        assertFalse(coalescer.isRefreshing.value)
        assertTrue(coalescer.refresh())
        assertEquals(2, runs)
    }

    @Test
    fun aFailingRefreshIsLoggedAndClearsTheFlag() {
        val failure = IllegalStateException("boom")
        val coalescer = RefreshCoalescer(CoroutineScope(Dispatchers.Unconfined)) { throw failure }

        assertTrue(coalescer.refresh())

        assertEquals(listOf<Throwable?>(failure), logger.errors)
        assertFalse(coalescer.isRefreshing.value)
    }

    @Test
    fun aCancelledRefreshIsNotLogged() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val coalescer = RefreshCoalescer(scope) { awaitCancellation() }

        assertTrue(coalescer.refresh())
        scope.cancel()

        assertTrue(logger.errors.isEmpty())
        assertFalse(coalescer.isRefreshing.value)
    }

    @Test
    fun cancelledScopeReleasesTheGate() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val coalescer = RefreshCoalescer(scope) { awaitCancellation() }

        assertTrue(coalescer.refresh())
        assertTrue(coalescer.isRefreshing.value)
        scope.cancel()
        assertFalse(coalescer.isRefreshing.value)
    }

    @Test
    fun latestRegisteredTargetIsCurrent() {
        val targets = RefreshTargets()
        val first = CountingTarget()
        val second = CountingTarget()

        assertNull(targets.current)
        assertFalse(targets.refreshCurrent())

        targets.register(first)
        targets.register(second)
        assertSame(second, targets.current)

        targets.unregister(second)
        assertSame(first, targets.current)
        assertTrue(targets.refreshCurrent())
        assertEquals(1, first.count)
        assertEquals(0, second.count)

        targets.register(second)
        targets.register(first)
        assertEquals(listOf<Refreshable>(second, first), targets.targets.value)

        targets.unregister(first)
        targets.unregister(second)
        assertNull(targets.current)
    }

    @Test
    fun refreshShortcutMatchesF5AndCtrlR() {
        assertTrue(isRefreshShortcut(Key.F5, isCtrlPressed = false, isMetaPressed = false, isAltPressed = false, isShiftPressed = false))
        assertTrue(isRefreshShortcut(Key.R, isCtrlPressed = true, isMetaPressed = false, isAltPressed = false, isShiftPressed = false))
        assertTrue(isRefreshShortcut(Key.R, isCtrlPressed = false, isMetaPressed = true, isAltPressed = false, isShiftPressed = false))

        assertFalse(isRefreshShortcut(Key.R, isCtrlPressed = false, isMetaPressed = false, isAltPressed = false, isShiftPressed = false))
        assertFalse(isRefreshShortcut(Key.R, isCtrlPressed = true, isMetaPressed = false, isAltPressed = false, isShiftPressed = true))
        assertFalse(isRefreshShortcut(Key.R, isCtrlPressed = true, isMetaPressed = false, isAltPressed = true, isShiftPressed = false))
        assertFalse(isRefreshShortcut(Key.F5, isCtrlPressed = true, isMetaPressed = false, isAltPressed = false, isShiftPressed = false))
        assertFalse(isRefreshShortcut(Key.F, isCtrlPressed = true, isMetaPressed = false, isAltPressed = false, isShiftPressed = false))
    }
}
