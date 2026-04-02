package dev.dertyp.synara.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.dertyp.core.capitalize
import dev.dertyp.data.Genre

@Composable
fun GenresText(
    genres: List<Genre>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val genresString = remember(genres) {
        genres.joinToString(", ") { it.name.capitalize() }
    }

    if (genresString.isNotEmpty()) {
        Text(
            text = genresString,
            modifier = modifier,
            style = style,
            color = color,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}
