package dev.dertyp.synara.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.ui.components.SettingsCard
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContext

@Composable
fun UiSettingsSlot(
    slot: String,
    context: UiContext = UiContext(),
) {
    val slotRenders = rememberSlotRenders(slot, context)
    if (slotRenders.items.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        slotRenders.items.forEach { render ->
            key(render.contributionId) {
                val host = rememberUiHost(render.contributionId, context) { slotRenders.refresh() }
                UiSettingsSlotItem(render.root, host)
                UiHostOverlays(host)
            }
        }
    }
}

@Composable
private fun UiSettingsSlotItem(component: UiComponent, host: UiHost) {
    val entry = component.asEntry()
    if (entry == null) {
        SettingsCard { UiRenderer(component, host, Modifier.fillMaxWidth()) }
        return
    }

    SettingsCard(
        onClick = entry.action?.takeIf { entry.enabled }?.let { action -> { host.dispatch(action) } },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                entry.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            entry.icon?.let { UiIconView(it, size = 20.dp) }
        }
    }
}
