package dev.dertyp.synara.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.dertyp.services.IUiService
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiHookEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun rememberUiShareHookDispatcher(
    uiService: IUiService = koinInject(),
): (UiHookEvent) -> Unit {
    val host = rememberUiHost("hook", UiContext())
    val scope = rememberCoroutineScope()

    val dispatch = remember(host, uiService, scope) {
        { event: UiHookEvent ->
            scope.launch {
                val handlers = try {
                    uiService.dispatchHook(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    emptyList()
                }
                when {
                    handlers.size == 1 -> host.dispatch(handlers[0].action.withConfirmText(handlers[0].confirmText))
                    handlers.size > 1 -> host.pendingChoice = DefaultUiHost.PendingChoice(handlers)
                    else -> Unit
                }
            }
            Unit
        }
    }

    UiHostOverlays(host)

    return dispatch
}
