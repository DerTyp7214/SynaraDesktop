package dev.dertyp.synara.ui.components

import androidx.compose.animation.core.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.viewmodels.RefreshTargets
import dev.dertyp.synara.viewmodels.Refreshable
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

@Composable
fun RegisterRefreshTarget(target: Refreshable, refreshTargets: RefreshTargets = koinInject()) {
    DisposableEffect(target, refreshTargets) {
        refreshTargets.register(target)
        onDispose { refreshTargets.unregister(target) }
    }
}

@Composable
fun CurrentRefreshButton(modifier: Modifier = Modifier, refreshTargets: RefreshTargets = koinInject()) {
    val targets by refreshTargets.targets.collectAsState()
    val target = targets.lastOrNull() ?: return
    RefreshButton(target, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshButton(target: Refreshable, modifier: Modifier = Modifier) {
    val isRefreshing by target.isRefreshing.collectAsState()
    val rotation = remember { Animatable(0f) }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            while (true) {
                rotation.animateTo(rotation.value + 360f, tween(durationMillis = 900, easing = LinearEasing))
            }
        } else if (rotation.value % 360f != 0f) {
            rotation.animateTo(rotation.value + (360f - rotation.value % 360f), tween(durationMillis = 300))
            rotation.snapTo(0f)
        } else {
            rotation.snapTo(0f)
        }
    }

    val label = stringResource(Res.string.refresh_list)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(stringResource(Res.string.refresh_list_tooltip)) } },
        state = rememberTooltipState(),
        modifier = modifier
    ) {
        IconButton(onClick = { target.refresh() }, enabled = !isRefreshing) {
            Icon(
                SynaraIcons.Refresh.get(),
                contentDescription = label,
                modifier = Modifier.rotate(rotation.value)
            )
        }
    }
}
