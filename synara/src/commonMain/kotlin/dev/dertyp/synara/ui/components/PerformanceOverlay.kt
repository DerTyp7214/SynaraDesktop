package dev.dertyp.synara.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.dertyp.core.roundToNDecimals
import dev.dertyp.synara.formatBytes
import dev.dertyp.synara.ui.models.PerformanceMonitor
import dev.dertyp.synara.viewmodels.GlobalStateModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.app
import synara.synara.generated.resources.particle_count
import synara.synara.generated.resources.particle_fps
import synara.synara.generated.resources.per_core
import synara.synara.generated.resources.ram
import synara.synara.generated.resources.system

@Composable
fun PerformanceOverlay() {
    val monitor = koinInject<PerformanceMonitor>()
    val globalStateModel = koinInject<GlobalStateModel>()
    val stats by monitor.stats.collectAsState()
    val isPlayerExpanded by globalStateModel.isPlayerExpanded.collectAsState()

    Box(
        modifier = Modifier
            .padding(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .padding(8.dp)
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${stringResource(Res.string.app)} (${stringResource(Res.string.per_core)}): ${(stats.processCpuLoad * stats.availableProcessors * 100).roundToNDecimals(1)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "${stringResource(Res.string.system)}: ${(stats.systemCpuLoad * 100).roundToNDecimals(1)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                "${stringResource(Res.string.ram)}: ${stats.rssMemory.formatBytes()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
            if (isPlayerExpanded) {
                Text(
                    "${stringResource(Res.string.particle_count)}: ${stats.particleCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${stringResource(Res.string.particle_fps)}: ${stats.particleFps} / ${stats.maxFps}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
