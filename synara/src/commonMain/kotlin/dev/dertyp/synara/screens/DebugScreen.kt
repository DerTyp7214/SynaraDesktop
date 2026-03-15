package dev.dertyp.synara.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.synara.ui.SynaraIcons
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.back

class DebugScreen : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val colorScheme = MaterialTheme.colorScheme

        val colors = listOf(
            "Primary" to colorScheme.primary,
            "OnPrimary" to colorScheme.onPrimary,
            "PrimaryContainer" to colorScheme.primaryContainer,
            "OnPrimaryContainer" to colorScheme.onPrimaryContainer,
            "InversePrimary" to colorScheme.inversePrimary,
            "Secondary" to colorScheme.secondary,
            "OnSecondary" to colorScheme.onSecondary,
            "SecondaryContainer" to colorScheme.secondaryContainer,
            "OnSecondaryContainer" to colorScheme.onSecondaryContainer,
            "Tertiary" to colorScheme.tertiary,
            "OnTertiary" to colorScheme.onTertiary,
            "TertiaryContainer" to colorScheme.tertiaryContainer,
            "OnTertiaryContainer" to colorScheme.onTertiaryContainer,
            "Background" to colorScheme.background,
            "OnBackground" to colorScheme.onBackground,
            "Surface" to colorScheme.surface,
            "OnSurface" to colorScheme.onSurface,
            "SurfaceVariant" to colorScheme.surfaceVariant,
            "OnSurfaceVariant" to colorScheme.onSurfaceVariant,
            "SurfaceTint" to colorScheme.surfaceTint,
            "InverseSurface" to colorScheme.inverseSurface,
            "InverseOnSurface" to colorScheme.inverseOnSurface,
            "Error" to colorScheme.error,
            "OnError" to colorScheme.onError,
            "ErrorContainer" to colorScheme.errorContainer,
            "OnErrorContainer" to colorScheme.onErrorContainer,
            "Outline" to colorScheme.outline,
            "OutlineVariant" to colorScheme.outlineVariant,
            "Scrim" to colorScheme.scrim
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Debug - Color Scheme") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(SynaraIcons.ArrowBack.get(), contentDescription = stringResource(Res.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = padding,
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(colors) { (name, color) ->
                    ColorItem(name, color)
                }
            }
        }
    }

    @Composable
    private fun ColorItem(name: String, color: Color) {
        Card(
            modifier = Modifier.fillMaxWidth().height(80.dp),
            colors = CardDefaults.cardColors(containerColor = color)
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (color.luminance() > 0.5f) Color.Black else Color.White
                )
            }
        }
    }
}
