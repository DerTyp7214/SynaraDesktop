package dev.dertyp.synara.viewmodels

import dev.dertyp.logging.LogTag
import dev.dertyp.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

interface Refreshable {
    val isRefreshing: StateFlow<Boolean>
    fun refresh()
}

class RefreshCoalescer(
    private val scope: CoroutineScope,
    private val context: CoroutineContext = EmptyCoroutineContext,
    private val block: suspend () -> Unit,
) : KoinComponent {
    private val logger: Logger by inject()
    private val logTag = LogTag("refresh")

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun refresh(): Boolean {
        if (!_isRefreshing.compareAndSet(expect = false, update = true)) return false
        val job = scope.launch(context) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(logTag, "Refresh failed", e)
            }
        }
        job.invokeOnCompletion { _isRefreshing.value = false }
        return true
    }
}
