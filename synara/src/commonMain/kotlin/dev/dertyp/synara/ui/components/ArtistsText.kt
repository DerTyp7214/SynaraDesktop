package dev.dertyp.synara.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.PlatformUUID
import dev.dertyp.data.ArtistCredit
import dev.dertyp.synara.screens.ArtistScreen

fun ArtistCredit.creditedDisplayName(): String = creditedName?.takeIf { it.isNotBlank() } ?: name

fun List<ArtistCredit>.creditSeparator(index: Int): String? =
    if (index < lastIndex) this[index].joinPhrase?.takeIf { it.isNotEmpty() } ?: ", " else null

fun List<ArtistCredit>.joinArtists(): String = sortedBy { it.name }.joinToString(", ") { it.name }

fun List<ArtistCredit>.creditText(): String = buildString {
    this@creditText.forEachIndexed { index, artist ->
        append(artist.creditedDisplayName())
        this@creditText.creditSeparator(index)?.let { append(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ArtistsText(
    artists: List<ArtistCredit>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onArtistClick: (() -> Unit)? = null
) {
    val navigator = LocalNavigator.current

    if (maxLines == 1) {
        var hoveredArtistId by remember { mutableStateOf<String?>(null) }
        val onSurfaceColor = MaterialTheme.colorScheme.onSurface

        val annotatedString = remember(artists, color, hoveredArtistId, onSurfaceColor) {
            buildAnnotatedString {
                artists.forEachIndexed { index, artist ->
                    val isHovered = artist.id.toString() == hoveredArtistId
                    pushStringAnnotation("artistId", artist.id.toString())
                    withStyle(
                        style.toSpanStyle().copy(
                            color = if (isHovered) onSurfaceColor else color,
                            textDecoration = if (isHovered) TextDecoration.Underline else TextDecoration.None,
                            background = if (isHovered) onSurfaceColor.copy(alpha = 0.1f) else Color.Transparent
                        )
                    ) {
                        append(artist.creditedDisplayName())
                    }
                    pop()
                    artists.creditSeparator(index)?.let { append(it) }
                }
            }
        }

        var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

        Text(
            text = annotatedString,
            modifier = modifier
                .onPointerEvent(PointerEventType.Move) { event ->
                    val offset = event.changes.first().position
                    layoutResult?.let { result ->
                        val position = result.getOffsetForPosition(offset)
                        val annotation = annotatedString.getStringAnnotations("artistId", position, position)
                            .firstOrNull()
                        hoveredArtistId = annotation?.item
                    }
                }
                .onPointerEvent(PointerEventType.Exit) {
                    hoveredArtistId = null
                }
                .pointerInput(annotatedString) {
                    detectTapGestures { offset ->
                        layoutResult?.let { result ->
                            val position = result.getOffsetForPosition(offset)
                            annotatedString.getStringAnnotations("artistId", position, position)
                                .firstOrNull()?.let { annotation ->
                                    onArtistClick?.invoke()
                                    navigator?.push(ArtistScreen(PlatformUUID.fromString(annotation.item)))
                                }
                        }
                    }
                }
                .pointerHoverIcon(if (hoveredArtistId != null) PointerIcon.Hand else PointerIcon.Default),
            style = style,
            maxLines = 1,
            overflow = overflow,
            onTextLayout = { layoutResult = it }
        )
    } else {
        FlowRow(
            modifier = modifier,
            maxItemsInEachRow = Int.MAX_VALUE
        ) {
            artists.forEachIndexed { index, artist ->
                var isHovered by remember { mutableStateOf(false) }

                Text(
                    text = artist.creditedDisplayName(),
                    style = style.copy(
                        textDecoration = if (isHovered) TextDecoration.Underline else TextDecoration.None
                    ),
                    color = if (isHovered) MaterialTheme.colorScheme.primary else color,
                    maxLines = 1,
                    modifier = Modifier
                        .background(
                            if (isHovered) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent,
                            shape = MaterialTheme.shapes.extraSmall
                        )
                        .onPointerEvent(PointerEventType.Enter) { isHovered = true }
                        .onPointerEvent(PointerEventType.Exit) { isHovered = false }
                        .pointerInput(artist.id) {
                            detectTapGestures {
                                onArtistClick?.invoke()
                                navigator?.push(ArtistScreen(artist.id))
                            }
                        }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 2.dp)
                )

                artists.creditSeparator(index)?.let { separator ->
                    Text(
                        text = separator,
                        style = style,
                        color = color
                    )
                }
            }
        }
    }
}
