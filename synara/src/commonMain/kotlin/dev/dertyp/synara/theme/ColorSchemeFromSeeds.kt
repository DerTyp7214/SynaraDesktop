package dev.dertyp.synara.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import dev.dertyp.synara.material.ColorSchemeBuilder


fun createColorSchemeFromSeeds(seeds: Triple<Int?, Int?, Int?>, isDark: Boolean): ColorScheme {
    val (primary, secondary, tertiary) = seeds
    val colors = ColorSchemeBuilder(
        seedColor = primary ?: 0,
        isDark = isDark,
        secondarySeedColor = secondary,
        tertiarySeedColor = tertiary
    ).build()

    fun get(key: ColorSchemeBuilder.Key): Color = Color(colors[key]!!)

    return ColorScheme(
        primary = get(ColorSchemeBuilder.Key.Primary),
        onPrimary = get(ColorSchemeBuilder.Key.OnPrimary),
        primaryContainer = get(ColorSchemeBuilder.Key.PrimaryContainer),
        onPrimaryContainer = get(ColorSchemeBuilder.Key.OnPrimaryContainer),
        inversePrimary = get(ColorSchemeBuilder.Key.InversePrimary),
        secondary = get(ColorSchemeBuilder.Key.Secondary),
        onSecondary = get(ColorSchemeBuilder.Key.OnSecondary),
        secondaryContainer = get(ColorSchemeBuilder.Key.SecondaryContainer),
        onSecondaryContainer = get(ColorSchemeBuilder.Key.OnSecondaryContainer),
        tertiary = get(ColorSchemeBuilder.Key.Tertiary),
        onTertiary = get(ColorSchemeBuilder.Key.OnTertiary),
        tertiaryContainer = get(ColorSchemeBuilder.Key.TertiaryContainer),
        onTertiaryContainer = get(ColorSchemeBuilder.Key.OnTertiaryContainer),
        background = get(ColorSchemeBuilder.Key.Background),
        onBackground = get(ColorSchemeBuilder.Key.OnBackground),
        surface = get(ColorSchemeBuilder.Key.Surface),
        onSurface = get(ColorSchemeBuilder.Key.OnSurface),
        surfaceVariant = get(ColorSchemeBuilder.Key.SurfaceVariant),
        onSurfaceVariant = get(ColorSchemeBuilder.Key.OnSurfaceVariant),
        surfaceTint = get(ColorSchemeBuilder.Key.SurfaceTint),
        inverseSurface = get(ColorSchemeBuilder.Key.InverseSurface),
        inverseOnSurface = get(ColorSchemeBuilder.Key.InverseOnSurface),
        error = get(ColorSchemeBuilder.Key.Error),
        onError = get(ColorSchemeBuilder.Key.OnError),
        errorContainer = get(ColorSchemeBuilder.Key.ErrorContainer),
        onErrorContainer = get(ColorSchemeBuilder.Key.OnErrorContainer),
        outline = get(ColorSchemeBuilder.Key.Outline),
        outlineVariant = get(ColorSchemeBuilder.Key.OutlineVariant),
        scrim = get(ColorSchemeBuilder.Key.Scrim),
        surfaceBright = get(ColorSchemeBuilder.Key.SurfaceBright),
        surfaceDim = get(ColorSchemeBuilder.Key.SurfaceDim),
        surfaceContainer = get(ColorSchemeBuilder.Key.SurfaceContainer),
        surfaceContainerHigh = get(ColorSchemeBuilder.Key.SurfaceContainerHigh),
        surfaceContainerHighest = get(ColorSchemeBuilder.Key.SurfaceContainerHighest),
        surfaceContainerLow = get(ColorSchemeBuilder.Key.SurfaceContainerLow),
        surfaceContainerLowest = get(ColorSchemeBuilder.Key.SurfaceContainerLowest),
        primaryFixed = get(ColorSchemeBuilder.Key.PrimaryFixed),
        primaryFixedDim = get(ColorSchemeBuilder.Key.PrimaryFixedDim),
        onPrimaryFixed = get(ColorSchemeBuilder.Key.OnPrimaryFixed),
        onPrimaryFixedVariant = get(ColorSchemeBuilder.Key.OnPrimaryFixedVariant),
        secondaryFixed = get(ColorSchemeBuilder.Key.SecondaryFixed),
        secondaryFixedDim = get(ColorSchemeBuilder.Key.SecondaryFixedDim),
        onSecondaryFixed = get(ColorSchemeBuilder.Key.OnSecondaryFixed),
        onSecondaryFixedVariant = get(ColorSchemeBuilder.Key.OnSecondaryFixedVariant),
        tertiaryFixed = get(ColorSchemeBuilder.Key.TertiaryFixed),
        tertiaryFixedDim = get(ColorSchemeBuilder.Key.TertiaryFixedDim),
        onTertiaryFixed = get(ColorSchemeBuilder.Key.OnTertiaryFixed),
        onTertiaryFixedVariant = get(ColorSchemeBuilder.Key.OnTertiaryFixedVariant),
    )
}
