package dev.dertyp.synara.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dertyp.services.IUiService
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiRender
import dev.dertyp.ui.UiSchemaVersion
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.ui_server_load_failed
import synara.synara.generated.resources.ui_server_retry

@Composable
fun isServerUiAvailable(): Boolean {
    val manager = koinInject<RpcServiceManager>()
    val version by manager.uiSchemaVersion.collectAsState()
    return version > UiSchemaVersion.NONE
}

@Composable
fun UiContributionHost(
    contributionId: String,
    context: UiContext = UiContext(),
    modifier: Modifier = Modifier,
    portals: UiPortalRegistry = SynaraUiPortals.default(),
    showToolbar: Boolean = true,
    onRender: (UiRender) -> Unit = {},
) {
    if (!isServerUiAvailable()) return

    val uiService = koinInject<IUiService>()
    var refreshKey by remember(contributionId, context) { mutableIntStateOf(0) }
    val host = rememberUiHost(contributionId, context, portals) { refreshKey++ }

    var render by remember(contributionId, context) { mutableStateOf<UiRender?>(null) }
    var loading by remember(contributionId, context) { mutableStateOf(true) }
    var error by remember(contributionId, context) { mutableStateOf<String?>(null) }
    val currentOnRender by rememberUpdatedState(onRender)

    LaunchedEffect(contributionId, context, refreshKey) {
        loading = true
        error = null
        var revision = -1L
        val apply: (UiRender) -> Unit = {
            if (it.revision >= revision) {
                revision = it.revision
                render = it
                currentOnRender(it)
            }
            loading = false
        }
        try {
            uiService.subscribe(contributionId, context.entityId).collect { apply(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            try {
                apply(uiService.render(contributionId, context))
            } catch (e: CancellationException) {
                throw e
            } catch (fallbackError: Throwable) {
                error = fallbackError.message
                loading = false
            }
        }
    }

    val current = render
    Box(modifier = modifier) {
        when {
            current != null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showToolbar && current.toolbar.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        current.toolbar.forEach { UiRenderer(it, host) }
                    }
                }
                UiRenderer(current.root, host, Modifier.fillMaxWidth())
            }

            loading -> Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }

            else -> Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = error ?: stringResource(Res.string.ui_server_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { refreshKey++ }) {
                    Text(stringResource(Res.string.ui_server_retry))
                }
            }
        }
    }

    UiHostOverlays(host)
}
