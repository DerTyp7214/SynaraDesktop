package dev.dertyp.synara.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import coil3.size.SizeResolver
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.ui.models.PerformanceMonitor
import dev.dertyp.synara.viewmodels.GlobalStateModel
import org.koin.compose.koinInject

@Composable
expect fun ParticleViewGpu(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    highlightColor: Color = MaterialTheme.colorScheme.onSurface,
    center: State<Offset> = mutableStateOf(Offset.Unspecified),
    emit: State<Boolean> = mutableStateOf(true),
    centerResolver: SizeResolver? = null,
    playerModel: PlayerModel = koinInject(),
    globalStateModel: GlobalStateModel = koinInject(),
    performanceMonitor: PerformanceMonitor = koinInject(),
)
