package dev.dertyp.synara.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dertyp.services.IUiService
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiRender
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject

class SlotRenders internal constructor(
    val items: List<UiRender>,
    val refresh: () -> Unit,
)

@Composable
fun rememberSlotRenders(slot: String, context: UiContext = UiContext()): SlotRenders {
    if (!isServerUiAvailable()) return remember { SlotRenders(emptyList()) {} }

    val uiService = koinInject<IUiService>()
    var refreshKey by remember(slot, context) { mutableIntStateOf(0) }
    var items by remember(slot, context) { mutableStateOf<List<UiRender>>(emptyList()) }

    LaunchedEffect(slot, context, refreshKey) {
        items = try {
            uiService.renderSlot(slot, context).items
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    return SlotRenders(items) { refreshKey++ }
}

@Composable
fun UiSlot(
    slot: String,
    context: UiContext = UiContext(),
    modifier: Modifier = Modifier,
    spacing: Dp = 16.dp,
    portals: UiPortalRegistry = SynaraUiPortals.default(),
) {
    val slotRenders = rememberSlotRenders(slot, context)
    if (slotRenders.items.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        slotRenders.items.forEach { render ->
            key(render.contributionId) {
                UiSlotItem(render, context, portals, slotRenders.refresh)
            }
        }
    }
}

@Composable
private fun UiSlotItem(
    render: UiRender,
    context: UiContext,
    portals: UiPortalRegistry,
    onRefresh: () -> Unit,
) {
    val host = rememberUiHost(render.contributionId, context, portals, onRefresh)
    UiRenderer(render.root, host, Modifier.fillMaxWidth())
    UiHostOverlays(host)
}
