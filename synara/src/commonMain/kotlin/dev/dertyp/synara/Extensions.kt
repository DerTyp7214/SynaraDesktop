package dev.dertyp.synara

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import dev.dertyp.synara.material.Hct
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*
import kotlin.math.min

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

fun FloatArray.takeAverage(n: Int): Float {
    if (n <= 0) return 0f
    var sum = 0f
    val limit = min(n, size)
    for (i in 0 until limit) {
        sum += get(i)
    }
    return if (limit > 0) sum / limit else 0f
}

fun ColorScheme.onSurfaceVariantDistinct(): Color {
    val surfaceLum = surface.luminance()
    val isDark = surfaceLum < 0.5f

    val baseHct = Hct.fromInt(secondary.toArgb())

    val targetChroma = if (baseHct.chroma < 20.0) 48.0 else baseHct.chroma.coerceIn(30.0, 60.0)
    val targetHue = if (baseHct.chroma < 10.0) Hct.fromInt(primary.toArgb()).hue else baseHct.hue

    val targetTone = if (isDark) 70.0 else 40.0

    return Color(Hct.from(targetHue, targetChroma, targetTone).toInt())
}

@Composable
fun Long.formatHumanReadableDuration(): String {
    val totalSeconds = this / 1000
    val days = totalSeconds / 86400
    val hours = (totalSeconds % 86400) / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    val daysText = if (days > 0) pluralStringResource(Res.plurals.duration_days, days.toInt(), days) else null
    val hoursText = if (hours > 0) pluralStringResource(Res.plurals.duration_hours, hours.toInt(), hours) else null
    val minutesText = if (minutes > 0) pluralStringResource(Res.plurals.duration_minutes, minutes.toInt(), minutes) else null
    val secondsText = if (seconds > 0) pluralStringResource(Res.plurals.duration_seconds, seconds.toInt(), seconds) else null

    val parts = listOfNotNull(daysText, hoursText, minutesText, secondsText)

    return when (parts.size) {
        0 -> pluralStringResource(Res.plurals.duration_seconds, 0, 0)
        1 -> parts[0]
        2 -> "${parts[0]} ${stringResource(Res.string.and)} ${parts[1]}"
        else -> {
            val head = parts.dropLast(1).joinToString(", ")
            "$head ${stringResource(Res.string.and)} ${parts.last()}"
        }
    }
}
