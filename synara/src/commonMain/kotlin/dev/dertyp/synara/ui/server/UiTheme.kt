package dev.dertyp.synara.ui.server

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dertyp.ui.UiAlign
import dev.dertyp.ui.UiEmphasis
import dev.dertyp.ui.UiSpacing
import dev.dertyp.ui.UiTextStyle
import dev.dertyp.ui.UiTone

fun UiSpacing.toDp(): Dp = when (this) {
    UiSpacing.NONE -> 0.dp
    UiSpacing.SMALL -> 4.dp
    UiSpacing.MEDIUM -> 12.dp
    UiSpacing.LARGE -> 24.dp
}

@Composable
@ReadOnlyComposable
fun UiTone.contentColor(): Color = when (this) {
    UiTone.DEFAULT -> MaterialTheme.colorScheme.onSurface
    UiTone.PRIMARY -> MaterialTheme.colorScheme.primary
    UiTone.SUCCESS -> MaterialTheme.colorScheme.tertiary
    UiTone.WARNING -> MaterialTheme.colorScheme.secondary
    UiTone.ERROR -> MaterialTheme.colorScheme.error
    UiTone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
@ReadOnlyComposable
fun UiTone.containerColor(): Color = when (this) {
    UiTone.DEFAULT -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    UiTone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
    UiTone.SUCCESS -> MaterialTheme.colorScheme.tertiaryContainer
    UiTone.WARNING -> MaterialTheme.colorScheme.secondaryContainer
    UiTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    UiTone.MUTED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
}

@Composable
@ReadOnlyComposable
fun UiTone.onContainerColor(): Color = when (this) {
    UiTone.DEFAULT -> MaterialTheme.colorScheme.onSurfaceVariant
    UiTone.PRIMARY -> MaterialTheme.colorScheme.onPrimaryContainer
    UiTone.SUCCESS -> MaterialTheme.colorScheme.onTertiaryContainer
    UiTone.WARNING -> MaterialTheme.colorScheme.onSecondaryContainer
    UiTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    UiTone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
@ReadOnlyComposable
fun UiTextStyle.toTextStyle(): TextStyle = when (this) {
    UiTextStyle.TITLE -> MaterialTheme.typography.headlineSmall
    UiTextStyle.SUBTITLE -> MaterialTheme.typography.titleMedium
    UiTextStyle.BODY -> MaterialTheme.typography.bodyMedium
    UiTextStyle.CAPTION -> MaterialTheme.typography.bodySmall
    UiTextStyle.CODE -> MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
}

fun UiEmphasis.alpha(): Float = when (this) {
    UiEmphasis.LOW -> 0.6f
    UiEmphasis.MEDIUM -> 1f
    UiEmphasis.HIGH -> 1f
}

fun UiAlign.toHorizontal(): Alignment.Horizontal = when (this) {
    UiAlign.START -> Alignment.Start
    UiAlign.CENTER -> Alignment.CenterHorizontally
    UiAlign.END -> Alignment.End
    UiAlign.STRETCH -> Alignment.Start
    UiAlign.SPACE_BETWEEN -> Alignment.Start
}

fun UiAlign.toVertical(): Alignment.Vertical = when (this) {
    UiAlign.START -> Alignment.Top
    UiAlign.CENTER -> Alignment.CenterVertically
    UiAlign.END -> Alignment.Bottom
    UiAlign.STRETCH -> Alignment.CenterVertically
    UiAlign.SPACE_BETWEEN -> Alignment.CenterVertically
}
