package dev.dertyp.synara.ui.server

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.ui.UiIcon
import dev.dertyp.ui.UiIconName

object UiIcons {
    fun synaraIcon(name: UiIconName): SynaraIcons = when (name) {
        UiIconName.SETTINGS -> SynaraIcons.Settings
        UiIconName.MUSIC -> SynaraIcons.Songs
        UiIconName.ALBUM -> SynaraIcons.Albums
        UiIconName.ARTIST -> SynaraIcons.Artists
        UiIconName.PLAYLIST -> SynaraIcons.Queue
        UiIconName.IMAGE -> SynaraIcons.Albums
        UiIconName.STORAGE -> SynaraIcons.Collections
        UiIconName.STATS -> SynaraIcons.Stats
        UiIconName.TASK -> SynaraIcons.Expiration
        UiIconName.PLAY -> SynaraIcons.Play
        UiIconName.PAUSE -> SynaraIcons.Pause
        UiIconName.DOWNLOAD -> SynaraIcons.Download
        UiIconName.IMPORT -> SynaraIcons.Upload
        UiIconName.QUEUE -> SynaraIcons.Queue
        UiIconName.PLUG -> SynaraIcons.Link
        UiIconName.LOGIN -> SynaraIcons.Users
        UiIconName.HEART -> SynaraIcons.IsNotFavorite
        UiIconName.SYNC -> SynaraIcons.Sync
        UiIconName.SEARCH -> SynaraIcons.Search
        UiIconName.KEY -> SynaraIcons.Key
        UiIconName.DATABASE -> SynaraIcons.AlbumVersions
        UiIconName.WARNING -> SynaraIcons.ErrorCircle
        UiIconName.ERROR -> SynaraIcons.ErrorCircle
        UiIconName.INFO -> SynaraIcons.Info
        UiIconName.USER -> SynaraIcons.Artists
        UiIconName.CHECK -> SynaraIcons.Confirm
        UiIconName.CLOSE -> SynaraIcons.Close
        UiIconName.LINK -> SynaraIcons.Link
        UiIconName.FILE -> SynaraIcons.Library
        UiIconName.MORE -> SynaraIcons.MoreOptions
        UiIconName.BARCODE -> SynaraIcons.Filter
    }

    @Composable
    fun vector(name: UiIconName): ImageVector = synaraIcon(name).get()
}

@Composable
fun UiIconView(
    icon: UiIcon,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
) {
    when (icon) {
        is UiIcon.Named -> Icon(
            imageVector = UiIcons.vector(icon.name),
            contentDescription = null,
            modifier = modifier.size(size),
            tint = tint,
        )

        is UiIcon.Url -> AsyncImage(
            model = icon.url,
            contentDescription = null,
            modifier = modifier.size(size).clip(MaterialTheme.shapes.extraSmall),
            contentScale = ContentScale.Crop,
        )

        is UiIcon.Image -> SynaraImage(
            imageId = icon.imageId,
            size = size,
            modifier = modifier,
            shape = MaterialTheme.shapes.extraSmall,
        )
    }
}
