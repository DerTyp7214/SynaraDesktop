package dev.dertyp.synara

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

@Composable
fun animateColorSchemeAsState(
    targetColorScheme: ColorScheme,
    animationSpec: AnimationSpec<Color> = tween(500)
): State<ColorScheme> {
    val primary by animateColorAsState(targetColorScheme.primary, animationSpec)
    val onPrimary by animateColorAsState(targetColorScheme.onPrimary, animationSpec)
    val primaryContainer by animateColorAsState(targetColorScheme.primaryContainer, animationSpec)
    val onPrimaryContainer by animateColorAsState(targetColorScheme.onPrimaryContainer, animationSpec)
    val inversePrimary by animateColorAsState(targetColorScheme.inversePrimary, animationSpec)
    val secondary by animateColorAsState(targetColorScheme.secondary, animationSpec)
    val onSecondary by animateColorAsState(targetColorScheme.onSecondary, animationSpec)
    val secondaryContainer by animateColorAsState(targetColorScheme.secondaryContainer, animationSpec)
    val onSecondaryContainer by animateColorAsState(targetColorScheme.onSecondaryContainer, animationSpec)
    val tertiary by animateColorAsState(targetColorScheme.tertiary, animationSpec)
    val onTertiary by animateColorAsState(targetColorScheme.onTertiary, animationSpec)
    val tertiaryContainer by animateColorAsState(targetColorScheme.tertiaryContainer, animationSpec)
    val onTertiaryContainer by animateColorAsState(targetColorScheme.onTertiaryContainer, animationSpec)
    val background by animateColorAsState(targetColorScheme.background, animationSpec)
    val onBackground by animateColorAsState(targetColorScheme.onBackground, animationSpec)
    val surface by animateColorAsState(targetColorScheme.surface, animationSpec)
    val onSurface by animateColorAsState(targetColorScheme.onSurface, animationSpec)
    val surfaceVariant by animateColorAsState(targetColorScheme.surfaceVariant, animationSpec)
    val onSurfaceVariant by animateColorAsState(targetColorScheme.onSurfaceVariant, animationSpec)
    val surfaceTint by animateColorAsState(targetColorScheme.surfaceTint, animationSpec)
    val inverseSurface by animateColorAsState(targetColorScheme.inverseSurface, animationSpec)
    val inverseOnSurface by animateColorAsState(targetColorScheme.inverseOnSurface, animationSpec)
    val error by animateColorAsState(targetColorScheme.error, animationSpec)
    val onError by animateColorAsState(targetColorScheme.onError, animationSpec)
    val errorContainer by animateColorAsState(targetColorScheme.errorContainer, animationSpec)
    val onErrorContainer by animateColorAsState(targetColorScheme.onErrorContainer, animationSpec)
    val outline by animateColorAsState(targetColorScheme.outline, animationSpec)
    val outlineVariant by animateColorAsState(targetColorScheme.outlineVariant, animationSpec)
    val scrim by animateColorAsState(targetColorScheme.scrim, animationSpec)
    val surfaceBright by animateColorAsState(targetColorScheme.surfaceBright, animationSpec)
    val surfaceDim by animateColorAsState(targetColorScheme.surfaceDim, animationSpec)
    val surfaceContainer by animateColorAsState(targetColorScheme.surfaceContainer, animationSpec)
    val surfaceContainerHigh by animateColorAsState(targetColorScheme.surfaceContainerHigh, animationSpec)
    val surfaceContainerHighest by animateColorAsState(targetColorScheme.surfaceContainerHighest, animationSpec)
    val surfaceContainerLow by animateColorAsState(targetColorScheme.surfaceContainerLow, animationSpec)
    val surfaceContainerLowest by animateColorAsState(targetColorScheme.surfaceContainerLowest, animationSpec)

    return remember(targetColorScheme) {
        derivedStateOf {
            targetColorScheme.copy(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                inversePrimary = inversePrimary,
                secondary = secondary,
                onSecondary = onSecondary,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = tertiary,
                onTertiary = onTertiary,
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = onTertiaryContainer,
                background = background,
                onBackground = onBackground,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = onSurfaceVariant,
                surfaceTint = surfaceTint,
                inverseSurface = inverseSurface,
                inverseOnSurface = inverseOnSurface,
                error = error,
                onError = onError,
                errorContainer = errorContainer,
                onErrorContainer = onErrorContainer,
                outline = outline,
                outlineVariant = outlineVariant,
                scrim = scrim,
                surfaceBright = surfaceBright,
                surfaceDim = surfaceDim,
                surfaceContainer = surfaceContainer,
                surfaceContainerHigh = surfaceContainerHigh,
                surfaceContainerHighest = surfaceContainerHighest,
                surfaceContainerLow = surfaceContainerLow,
                surfaceContainerLowest = surfaceContainerLowest,
            )
        }
    }
}