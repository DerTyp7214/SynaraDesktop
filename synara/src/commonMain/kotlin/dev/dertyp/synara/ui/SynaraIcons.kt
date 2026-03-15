package dev.dertyp.synara.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.*
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.*
import com.composables.icons.materialsymbols.outlinedfilled.*
import com.composables.icons.materialsymbols.rounded.*
import com.composables.icons.materialsymbols.roundedfilled.*
import com.composables.icons.materialsymbols.sharp.*
import com.composables.icons.materialsymbols.sharpfilled.*
import org.jetbrains.compose.resources.StringResource
import synara.synara.generated.resources.*

enum class IconPackType(val label: StringResource) {
    MaterialSymbols(Res.string.icon_pack_material_symbols),
    Lucide(Res.string.icon_pack_lucide);

    fun getPack(): SynaraIconPack {
        return when (this) {
            MaterialSymbols -> MaterialSymbolsIconPack
            Lucide -> LucideIconPack
        }
    }
}

interface SynaraIconStyle {
    val label: StringResource
    val id: String
}

enum class MaterialSymbolStyle(override val id: String, override val label: StringResource) : SynaraIconStyle {
    Rounded("rounded", Res.string.icon_style_rounded),
    Outlined("outlined", Res.string.icon_style_outlined),
    Sharp("sharp", Res.string.icon_style_sharp)
}

object LucideStyle : SynaraIconStyle {
    override val label: StringResource = Res.string.icon_style_default
    override val id: String = "default"
}

val LocalIconPack = compositionLocalOf<SynaraIconPack> { MaterialSymbolsIconPack }
val LocalIconStyle = compositionLocalOf<SynaraIconStyle> { MaterialSymbolStyle.Rounded }
val LocalIconFilled = compositionLocalOf { false }

enum class SynaraIcons {
    Dashboard, Search, Library, Settings, IsFavorite, IsNotFavorite, Songs, Refresh, Back, SideMenu, Clear, MoreOptions, 
    Play, Pause, SkipNext, SkipPrevious, Shuffle, Repeat, RepeatOne, PlayNext, AddToPlaylist, Albums, Artists, 
    AlbumVersions, Expiration, DeviceGeneric, DeviceMobile, DeviceDesktop, Upload, Add, Delete, DragHandle, Info, ThemeLight, 
    ThemeDark, ChevronDown, Filter, FilterOff, VolumeHigh, VolumeOff, VolumeMute, VolumeLow, Success, 
    Pending, ExpandDown, Lyrics, Queue, FullscreenEnter, FullscreenExit, RemoveFromPlaylist, RemoveFromQueue, 
    ArtistMerge, ArtistSplit, Close, Confirm;

    @Composable
    fun get(filled: Boolean = LocalIconFilled.current): ImageVector {
        return LocalIconPack.current.get(this, LocalIconStyle.current, filled)
    }
}

abstract class SynaraIconPack {
    abstract val name: String
    abstract val type: IconPackType
    
    @Composable
    abstract fun get(id: SynaraIcons, style: SynaraIconStyle, filled: Boolean): ImageVector
    
    abstract val styles: List<SynaraIconStyle>
    abstract val hasFilledOption: Boolean

    fun getStyle(id: String): SynaraIconStyle {
        return styles.find { it.id == id } ?: styles.first()
    }
}

object MaterialSymbolsIconPack : SynaraIconPack() {
    override val name: String = "Material Symbols"
    override val type: IconPackType = IconPackType.MaterialSymbols
    override val styles: List<SynaraIconStyle> = MaterialSymbolStyle.entries
    override val hasFilledOption: Boolean = true

    @Composable
    override fun get(id: SynaraIcons, style: SynaraIconStyle, filled: Boolean): ImageVector {
        val s = style as? MaterialSymbolStyle ?: MaterialSymbolStyle.Rounded
        return when (id) {
            SynaraIcons.Dashboard -> resolve(MaterialSymbols.Rounded.Home, MaterialSymbols.RoundedFilled.Home, MaterialSymbols.Outlined.Home, MaterialSymbols.OutlinedFilled.Home, MaterialSymbols.Sharp.Home, MaterialSymbols.SharpFilled.Home, filled, s)
            SynaraIcons.Search -> resolve(MaterialSymbols.Rounded.Search, MaterialSymbols.RoundedFilled.Search, MaterialSymbols.Outlined.Search, MaterialSymbols.OutlinedFilled.Search, MaterialSymbols.Sharp.Search, MaterialSymbols.SharpFilled.Search, filled, s)
            SynaraIcons.Library -> resolve(MaterialSymbols.Rounded.Library_music, MaterialSymbols.RoundedFilled.Library_music, MaterialSymbols.Outlined.Library_music, MaterialSymbols.OutlinedFilled.Library_music, MaterialSymbols.Sharp.Library_music, MaterialSymbols.SharpFilled.Library_music, filled, s)
            SynaraIcons.Settings -> resolve(MaterialSymbols.Rounded.Settings, MaterialSymbols.RoundedFilled.Settings, MaterialSymbols.Outlined.Settings, MaterialSymbols.OutlinedFilled.Settings, MaterialSymbols.Sharp.Settings, MaterialSymbols.SharpFilled.Settings, filled, s)
            SynaraIcons.IsFavorite -> resolve(MaterialSymbols.RoundedFilled.Favorite, MaterialSymbols.RoundedFilled.Favorite, MaterialSymbols.OutlinedFilled.Favorite, MaterialSymbols.OutlinedFilled.Favorite, MaterialSymbols.SharpFilled.Favorite, MaterialSymbols.SharpFilled.Favorite, true, s)
            SynaraIcons.IsNotFavorite -> resolve(MaterialSymbols.Rounded.Favorite, MaterialSymbols.Rounded.Favorite, MaterialSymbols.Outlined.Favorite, MaterialSymbols.Outlined.Favorite, MaterialSymbols.Sharp.Favorite, MaterialSymbols.Sharp.Favorite, false, s)
            SynaraIcons.Songs -> resolve(MaterialSymbols.Rounded.Music_note, MaterialSymbols.RoundedFilled.Music_note, MaterialSymbols.Outlined.Music_note, MaterialSymbols.OutlinedFilled.Music_note, MaterialSymbols.Sharp.Music_note, MaterialSymbols.SharpFilled.Music_note, filled, s)
            SynaraIcons.Refresh -> resolve(MaterialSymbols.Rounded.Refresh, MaterialSymbols.RoundedFilled.Refresh, MaterialSymbols.Outlined.Refresh, MaterialSymbols.OutlinedFilled.Refresh, MaterialSymbols.Sharp.Refresh, MaterialSymbols.SharpFilled.Refresh, filled, s)
            SynaraIcons.Back -> resolve(MaterialSymbols.Rounded.Arrow_back, MaterialSymbols.RoundedFilled.Arrow_back, MaterialSymbols.Outlined.Arrow_back, MaterialSymbols.OutlinedFilled.Arrow_back, MaterialSymbols.Sharp.Arrow_back, MaterialSymbols.SharpFilled.Arrow_back, filled, s)
            SynaraIcons.SideMenu -> resolve(MaterialSymbols.Rounded.Menu, MaterialSymbols.RoundedFilled.Menu, MaterialSymbols.Outlined.Menu, MaterialSymbols.OutlinedFilled.Menu, MaterialSymbols.Sharp.Menu, MaterialSymbols.SharpFilled.Menu, filled, s)
            SynaraIcons.Clear -> resolve(MaterialSymbols.Rounded.Close, MaterialSymbols.RoundedFilled.Close, MaterialSymbols.Outlined.Close, MaterialSymbols.OutlinedFilled.Close, MaterialSymbols.Sharp.Close, MaterialSymbols.SharpFilled.Close, filled, s)
            SynaraIcons.MoreOptions -> resolve(MaterialSymbols.Rounded.More_vert, MaterialSymbols.RoundedFilled.More_vert, MaterialSymbols.Outlined.More_vert, MaterialSymbols.OutlinedFilled.More_vert, MaterialSymbols.Sharp.More_vert, MaterialSymbols.SharpFilled.More_vert, filled, s)
            SynaraIcons.Play -> resolve(MaterialSymbols.Rounded.Play_arrow, MaterialSymbols.RoundedFilled.Play_arrow, MaterialSymbols.Outlined.Play_arrow, MaterialSymbols.OutlinedFilled.Play_arrow, MaterialSymbols.Sharp.Play_arrow, MaterialSymbols.SharpFilled.Play_arrow, filled, s)
            SynaraIcons.Pause -> resolve(MaterialSymbols.Rounded.Pause, MaterialSymbols.RoundedFilled.Pause, MaterialSymbols.Outlined.Pause, MaterialSymbols.OutlinedFilled.Pause, MaterialSymbols.Sharp.Pause, MaterialSymbols.SharpFilled.Pause, filled, s)
            SynaraIcons.SkipNext -> resolve(MaterialSymbols.Rounded.Skip_next, MaterialSymbols.RoundedFilled.Skip_next, MaterialSymbols.Outlined.Skip_next, MaterialSymbols.OutlinedFilled.Skip_next, MaterialSymbols.Sharp.Skip_next, MaterialSymbols.SharpFilled.Skip_next, filled, s)
            SynaraIcons.SkipPrevious -> resolve(MaterialSymbols.Rounded.Skip_previous, MaterialSymbols.RoundedFilled.Skip_previous, MaterialSymbols.Outlined.Skip_previous, MaterialSymbols.OutlinedFilled.Skip_previous, MaterialSymbols.Sharp.Skip_previous, MaterialSymbols.SharpFilled.Skip_previous, filled, s)
            SynaraIcons.Shuffle -> resolve(MaterialSymbols.Rounded.Shuffle, MaterialSymbols.RoundedFilled.Shuffle, MaterialSymbols.Outlined.Shuffle, MaterialSymbols.OutlinedFilled.Shuffle, MaterialSymbols.Sharp.Shuffle, MaterialSymbols.SharpFilled.Shuffle, filled, s)
            SynaraIcons.Repeat -> resolve(MaterialSymbols.Rounded.Repeat, MaterialSymbols.RoundedFilled.Repeat, MaterialSymbols.Outlined.Repeat, MaterialSymbols.OutlinedFilled.Repeat, MaterialSymbols.Sharp.Repeat, MaterialSymbols.SharpFilled.Repeat, filled, s)
            SynaraIcons.RepeatOne -> resolve(MaterialSymbols.Rounded.Repeat_one, MaterialSymbols.RoundedFilled.Repeat_one, MaterialSymbols.Outlined.Repeat_one, MaterialSymbols.OutlinedFilled.Repeat_one, MaterialSymbols.Sharp.Repeat_one, MaterialSymbols.SharpFilled.Repeat_one, filled, s)
            SynaraIcons.PlayNext -> resolve(MaterialSymbols.Rounded.Playlist_play, MaterialSymbols.RoundedFilled.Playlist_play, MaterialSymbols.Outlined.Playlist_play, MaterialSymbols.OutlinedFilled.Playlist_play, MaterialSymbols.Sharp.Playlist_play, MaterialSymbols.SharpFilled.Playlist_play, filled, s)
            SynaraIcons.AddToPlaylist -> resolve(MaterialSymbols.Rounded.Playlist_add, MaterialSymbols.RoundedFilled.Playlist_add, MaterialSymbols.Outlined.Playlist_add, MaterialSymbols.OutlinedFilled.Playlist_add, MaterialSymbols.Sharp.Playlist_add, MaterialSymbols.SharpFilled.Playlist_add, filled, s)
            SynaraIcons.Albums -> resolve(MaterialSymbols.Rounded.Album, MaterialSymbols.RoundedFilled.Album, MaterialSymbols.Outlined.Album, MaterialSymbols.OutlinedFilled.Album, MaterialSymbols.Sharp.Album, MaterialSymbols.SharpFilled.Album, filled, s)
            SynaraIcons.Artists -> resolve(MaterialSymbols.Rounded.Person, MaterialSymbols.RoundedFilled.Person, MaterialSymbols.Outlined.Person, MaterialSymbols.OutlinedFilled.Person, MaterialSymbols.Sharp.Person, MaterialSymbols.SharpFilled.Person, filled, s)
            SynaraIcons.AlbumVersions -> resolve(MaterialSymbols.Rounded.Layers, MaterialSymbols.RoundedFilled.Layers, MaterialSymbols.Outlined.Layers, MaterialSymbols.OutlinedFilled.Layers, MaterialSymbols.Sharp.Layers, MaterialSymbols.SharpFilled.Layers, filled, s)
            SynaraIcons.Expiration -> resolve(MaterialSymbols.Rounded.Timer, MaterialSymbols.RoundedFilled.Timer, MaterialSymbols.Outlined.Timer, MaterialSymbols.OutlinedFilled.Timer, MaterialSymbols.Sharp.Timer, MaterialSymbols.SharpFilled.Timer, filled, s)
            SynaraIcons.DeviceGeneric -> resolve(MaterialSymbols.Rounded.Devices, MaterialSymbols.RoundedFilled.Devices, MaterialSymbols.Outlined.Devices, MaterialSymbols.OutlinedFilled.Devices, MaterialSymbols.Sharp.Devices, MaterialSymbols.SharpFilled.Devices, filled, s)
            SynaraIcons.DeviceMobile -> resolve(MaterialSymbols.Rounded.Smartphone, MaterialSymbols.RoundedFilled.Smartphone, MaterialSymbols.Outlined.Smartphone, MaterialSymbols.OutlinedFilled.Smartphone, MaterialSymbols.Sharp.Smartphone, MaterialSymbols.SharpFilled.Smartphone, filled, s)
            SynaraIcons.DeviceDesktop -> resolve(MaterialSymbols.Rounded.Desktop_windows, MaterialSymbols.RoundedFilled.Desktop_windows, MaterialSymbols.Outlined.Desktop_windows, MaterialSymbols.OutlinedFilled.Desktop_windows, MaterialSymbols.Sharp.Desktop_windows, MaterialSymbols.SharpFilled.Desktop_windows, filled, s)
            SynaraIcons.Upload -> resolve(MaterialSymbols.Rounded.Cloud_upload, MaterialSymbols.RoundedFilled.Cloud_upload, MaterialSymbols.Outlined.Cloud_upload, MaterialSymbols.OutlinedFilled.Cloud_upload, MaterialSymbols.Sharp.Cloud_upload, MaterialSymbols.SharpFilled.Cloud_upload, filled, s)
            SynaraIcons.Add -> resolve(MaterialSymbols.Rounded.Add, MaterialSymbols.RoundedFilled.Add, MaterialSymbols.Outlined.Add, MaterialSymbols.OutlinedFilled.Add, MaterialSymbols.Sharp.Add, MaterialSymbols.SharpFilled.Add, filled, s)
            SynaraIcons.Delete -> resolve(MaterialSymbols.Rounded.Delete, MaterialSymbols.RoundedFilled.Delete, MaterialSymbols.Outlined.Delete, MaterialSymbols.OutlinedFilled.Delete, MaterialSymbols.Sharp.Delete, MaterialSymbols.SharpFilled.Delete, filled, s)
            SynaraIcons.DragHandle -> resolve(MaterialSymbols.Rounded.Drag_handle, MaterialSymbols.RoundedFilled.Drag_handle, MaterialSymbols.Outlined.Drag_handle, MaterialSymbols.OutlinedFilled.Drag_handle, MaterialSymbols.Sharp.Drag_handle, MaterialSymbols.SharpFilled.Drag_handle, filled, s)
            SynaraIcons.Info -> resolve(MaterialSymbols.Rounded.Info, MaterialSymbols.RoundedFilled.Info, MaterialSymbols.Outlined.Info, MaterialSymbols.OutlinedFilled.Info, MaterialSymbols.Sharp.Info, MaterialSymbols.SharpFilled.Info, filled, s)
            SynaraIcons.ThemeLight -> resolve(MaterialSymbols.Rounded.Light_mode, MaterialSymbols.RoundedFilled.Light_mode, MaterialSymbols.Outlined.Light_mode, MaterialSymbols.OutlinedFilled.Light_mode, MaterialSymbols.Sharp.Light_mode, MaterialSymbols.SharpFilled.Light_mode, filled, s)
            SynaraIcons.ThemeDark -> resolve(MaterialSymbols.Rounded.Dark_mode, MaterialSymbols.RoundedFilled.Dark_mode, MaterialSymbols.Outlined.Dark_mode, MaterialSymbols.OutlinedFilled.Dark_mode, MaterialSymbols.Sharp.Dark_mode, MaterialSymbols.SharpFilled.Dark_mode, filled, s)
            SynaraIcons.ChevronDown -> resolve(MaterialSymbols.Rounded.Arrow_drop_down, MaterialSymbols.RoundedFilled.Arrow_drop_down, MaterialSymbols.Outlined.Arrow_drop_down, MaterialSymbols.OutlinedFilled.Arrow_drop_down, MaterialSymbols.Sharp.Arrow_drop_down, MaterialSymbols.SharpFilled.Arrow_drop_down, filled, s)
            SynaraIcons.Filter -> resolve(MaterialSymbols.Rounded.Filter_list, MaterialSymbols.RoundedFilled.Filter_list, MaterialSymbols.Outlined.Filter_list, MaterialSymbols.OutlinedFilled.Filter_list, MaterialSymbols.Sharp.Filter_list, MaterialSymbols.SharpFilled.Filter_list, filled, s)
            SynaraIcons.FilterOff -> resolve(MaterialSymbols.Rounded.Filter_list_off, MaterialSymbols.RoundedFilled.Filter_list_off, MaterialSymbols.Outlined.Filter_list_off, MaterialSymbols.OutlinedFilled.Filter_list_off, MaterialSymbols.Sharp.Filter_list_off, MaterialSymbols.SharpFilled.Filter_list_off, filled, s)
            SynaraIcons.VolumeHigh -> resolve(MaterialSymbols.Rounded.Volume_up, MaterialSymbols.RoundedFilled.Volume_up, MaterialSymbols.Outlined.Volume_up, MaterialSymbols.OutlinedFilled.Volume_up, MaterialSymbols.Sharp.Volume_up, MaterialSymbols.SharpFilled.Volume_up, filled, s)
            SynaraIcons.VolumeOff -> resolve(MaterialSymbols.Rounded.Volume_off, MaterialSymbols.RoundedFilled.Volume_off, MaterialSymbols.Outlined.Volume_off, MaterialSymbols.OutlinedFilled.Volume_off, MaterialSymbols.Sharp.Volume_off, MaterialSymbols.SharpFilled.Volume_off, filled, s)
            SynaraIcons.VolumeMute -> resolve(MaterialSymbols.Rounded.Volume_mute, MaterialSymbols.RoundedFilled.Volume_mute, MaterialSymbols.Outlined.Volume_mute, MaterialSymbols.OutlinedFilled.Volume_mute, MaterialSymbols.Sharp.Volume_mute, MaterialSymbols.SharpFilled.Volume_mute, filled, s)
            SynaraIcons.VolumeLow -> resolve(MaterialSymbols.Rounded.Volume_down, MaterialSymbols.RoundedFilled.Volume_down, MaterialSymbols.Outlined.Volume_down, MaterialSymbols.OutlinedFilled.Volume_down, MaterialSymbols.Sharp.Volume_down, MaterialSymbols.SharpFilled.Volume_down, filled, s)
            SynaraIcons.Success -> resolve(MaterialSymbols.Rounded.Check_circle, MaterialSymbols.RoundedFilled.Check_circle, MaterialSymbols.Outlined.Check_circle, MaterialSymbols.OutlinedFilled.Check_circle, MaterialSymbols.Sharp.Check_circle, MaterialSymbols.SharpFilled.Check_circle, filled, s)
            SynaraIcons.Pending -> resolve(MaterialSymbols.Rounded.Schedule, MaterialSymbols.RoundedFilled.Schedule, MaterialSymbols.Outlined.Schedule, MaterialSymbols.OutlinedFilled.Schedule, MaterialSymbols.Sharp.Schedule, MaterialSymbols.SharpFilled.Schedule, filled, s)
            SynaraIcons.ExpandDown -> resolve(MaterialSymbols.Rounded.Keyboard_arrow_down, MaterialSymbols.RoundedFilled.Keyboard_arrow_down, MaterialSymbols.Outlined.Keyboard_arrow_down, MaterialSymbols.OutlinedFilled.Keyboard_arrow_down, MaterialSymbols.Sharp.Keyboard_arrow_down, MaterialSymbols.SharpFilled.Keyboard_arrow_down, filled, s)
            SynaraIcons.Lyrics -> resolve(MaterialSymbols.Rounded.Lyrics, MaterialSymbols.RoundedFilled.Lyrics, MaterialSymbols.Outlined.Lyrics, MaterialSymbols.OutlinedFilled.Lyrics, MaterialSymbols.Sharp.Lyrics, MaterialSymbols.SharpFilled.Lyrics, filled, s)
            SynaraIcons.Queue -> resolve(MaterialSymbols.Rounded.Queue_music, MaterialSymbols.RoundedFilled.Queue_music, MaterialSymbols.Outlined.Queue_music, MaterialSymbols.OutlinedFilled.Queue_music, MaterialSymbols.Sharp.Queue_music, MaterialSymbols.SharpFilled.Queue_music, filled, s)
            SynaraIcons.FullscreenEnter -> resolve(MaterialSymbols.Rounded.Fullscreen, MaterialSymbols.RoundedFilled.Fullscreen, MaterialSymbols.Outlined.Fullscreen, MaterialSymbols.OutlinedFilled.Fullscreen, MaterialSymbols.Sharp.Fullscreen, MaterialSymbols.SharpFilled.Fullscreen, filled, s)
            SynaraIcons.FullscreenExit -> resolve(MaterialSymbols.Rounded.Fullscreen_exit, MaterialSymbols.RoundedFilled.Fullscreen_exit, MaterialSymbols.Outlined.Fullscreen_exit, MaterialSymbols.OutlinedFilled.Fullscreen_exit, MaterialSymbols.Sharp.Fullscreen_exit, MaterialSymbols.SharpFilled.Fullscreen_exit, filled, s)
            SynaraIcons.RemoveFromPlaylist -> resolve(MaterialSymbols.Rounded.Playlist_remove, MaterialSymbols.RoundedFilled.Playlist_remove, MaterialSymbols.Outlined.Playlist_remove, MaterialSymbols.OutlinedFilled.Playlist_remove, MaterialSymbols.Sharp.Playlist_remove, MaterialSymbols.SharpFilled.Playlist_remove, filled, s)
            SynaraIcons.RemoveFromQueue -> resolve(MaterialSymbols.Rounded.Do_not_disturb_on, MaterialSymbols.RoundedFilled.Do_not_disturb_on, MaterialSymbols.Outlined.Do_not_disturb_on, MaterialSymbols.OutlinedFilled.Do_not_disturb_on, MaterialSymbols.Sharp.Do_not_disturb_on, MaterialSymbols.SharpFilled.Do_not_disturb_on, filled, s)
            SynaraIcons.ArtistMerge -> resolve(MaterialSymbols.Rounded.Merge, MaterialSymbols.RoundedFilled.Merge, MaterialSymbols.Outlined.Merge, MaterialSymbols.OutlinedFilled.Merge, MaterialSymbols.Sharp.Merge, MaterialSymbols.SharpFilled.Merge, filled, s)
            SynaraIcons.ArtistSplit -> resolve(MaterialSymbols.Rounded.Call_split, MaterialSymbols.RoundedFilled.Call_split, MaterialSymbols.Outlined.Call_split, MaterialSymbols.OutlinedFilled.Call_split, MaterialSymbols.Sharp.Call_split, MaterialSymbols.SharpFilled.Call_split, filled, s)
            SynaraIcons.Close -> resolve(MaterialSymbols.Rounded.Close, MaterialSymbols.RoundedFilled.Close, MaterialSymbols.Outlined.Close, MaterialSymbols.OutlinedFilled.Close, MaterialSymbols.Sharp.Close, MaterialSymbols.SharpFilled.Close, filled, s)
            SynaraIcons.Confirm -> resolve(MaterialSymbols.Rounded.Check, MaterialSymbols.RoundedFilled.Check, MaterialSymbols.Outlined.Check, MaterialSymbols.OutlinedFilled.Check, MaterialSymbols.Sharp.Check, MaterialSymbols.SharpFilled.Check, filled, s)
        }
    }

    private fun resolve(
        rounded: ImageVector, roundedFilled: ImageVector,
        outlined: ImageVector, outlinedFilled: ImageVector,
        sharp: ImageVector, sharpFilled: ImageVector,
        filled: Boolean, style: MaterialSymbolStyle
    ): ImageVector {
        return when (style) {
            MaterialSymbolStyle.Rounded -> if (filled) roundedFilled else rounded
            MaterialSymbolStyle.Outlined -> if (filled) outlinedFilled else outlined
            MaterialSymbolStyle.Sharp -> if (filled) sharpFilled else sharp
        }
    }
}

object LucideIconPack : SynaraIconPack() {
    override val name: String = "Lucide"
    override val type: IconPackType = IconPackType.Lucide
    override val styles: List<SynaraIconStyle> = listOf(LucideStyle)
    override val hasFilledOption: Boolean = false

    @Composable
    override fun get(id: SynaraIcons, style: SynaraIconStyle, filled: Boolean): ImageVector {
        return when (id) {
            SynaraIcons.Dashboard -> Lucide.House
            SynaraIcons.Search -> Lucide.Search
            SynaraIcons.Library -> Lucide.Library
            SynaraIcons.Settings -> Lucide.Settings
            SynaraIcons.IsFavorite -> Lucide.HeartOff
            SynaraIcons.IsNotFavorite -> Lucide.Heart
            SynaraIcons.Songs -> Lucide.Music
            SynaraIcons.Refresh -> Lucide.RefreshCw
            SynaraIcons.Back -> Lucide.ArrowLeft
            SynaraIcons.SideMenu -> Lucide.Menu
            SynaraIcons.Clear -> Lucide.X
            SynaraIcons.MoreOptions -> Lucide.EllipsisVertical
            SynaraIcons.Play -> Lucide.Play
            SynaraIcons.Pause -> Lucide.Pause
            SynaraIcons.SkipNext -> Lucide.SkipForward
            SynaraIcons.SkipPrevious -> Lucide.SkipBack
            SynaraIcons.Shuffle -> Lucide.Shuffle
            SynaraIcons.Repeat -> Lucide.Repeat
            SynaraIcons.RepeatOne -> Lucide.Repeat1
            SynaraIcons.PlayNext -> Lucide.ListVideo
            SynaraIcons.AddToPlaylist -> Lucide.ListPlus
            SynaraIcons.Albums -> Lucide.Disc
            SynaraIcons.Artists -> Lucide.User
            SynaraIcons.AlbumVersions -> Lucide.Layers
            SynaraIcons.Expiration -> Lucide.Timer
            SynaraIcons.DeviceGeneric -> Lucide.Tablet
            SynaraIcons.DeviceMobile -> Lucide.Smartphone
            SynaraIcons.DeviceDesktop -> Lucide.Monitor
            SynaraIcons.Upload -> Lucide.CloudUpload
            SynaraIcons.Add -> Lucide.Plus
            SynaraIcons.Delete -> Lucide.Trash
            SynaraIcons.DragHandle -> Lucide.GripVertical
            SynaraIcons.Info -> Lucide.Info
            SynaraIcons.ThemeLight -> Lucide.Sun
            SynaraIcons.ThemeDark -> Lucide.Moon
            SynaraIcons.ChevronDown -> Lucide.ChevronDown
            SynaraIcons.Filter -> Lucide.ListFilterPlus
            SynaraIcons.FilterOff -> Lucide.ListFilter
            SynaraIcons.VolumeHigh -> Lucide.Volume2
            SynaraIcons.VolumeOff -> Lucide.VolumeX
            SynaraIcons.VolumeMute -> Lucide.Volume
            SynaraIcons.VolumeLow -> Lucide.Volume1
            SynaraIcons.Success -> Lucide.CircleCheck
            SynaraIcons.Pending -> Lucide.Clock
            SynaraIcons.ExpandDown -> Lucide.ChevronDown
            SynaraIcons.Lyrics -> Lucide.Mic
            SynaraIcons.Queue -> Lucide.ListMusic
            SynaraIcons.FullscreenEnter -> Lucide.Maximize
            SynaraIcons.FullscreenExit -> Lucide.Minimize
            SynaraIcons.RemoveFromPlaylist -> Lucide.ListX
            SynaraIcons.RemoveFromQueue -> Lucide.CircleMinus
            SynaraIcons.ArtistMerge -> Lucide.GitMerge
            SynaraIcons.ArtistSplit -> Lucide.Split
            SynaraIcons.Close -> Lucide.X
            SynaraIcons.Confirm -> Lucide.Check
        }
    }
}
