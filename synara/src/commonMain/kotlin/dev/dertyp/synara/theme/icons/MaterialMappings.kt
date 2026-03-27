package dev.dertyp.synara.theme.icons

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import dev.dertyp.synara.ui.FillMode
import dev.dertyp.synara.ui.MapTo
import dev.dertyp.synara.ui.MaterialPack
import dev.dertyp.synara.ui.SynaraIcons

@MaterialPack
object MaterialMappings {
    @MapTo(SynaraIcons.Dashboard) val Home = MaterialSymbols.Rounded.Home
    @MapTo(SynaraIcons.Search) val Search = MaterialSymbols.Rounded.Search
    @MapTo(SynaraIcons.Library) val Library_music = MaterialSymbols.Rounded.Library_music
    @MapTo(SynaraIcons.Settings) val Settings = MaterialSymbols.Rounded.Settings
    @MapTo(SynaraIcons.IsFavorite, fillMode = FillMode.Filled)
    @MapTo(SynaraIcons.IsNotFavorite, fillMode = FillMode.Outlined)
    val Favorite = MaterialSymbols.Rounded.Favorite
    @MapTo(SynaraIcons.Songs) val Music_note = MaterialSymbols.Rounded.Music_note
    @MapTo(SynaraIcons.Refresh) val Refresh = MaterialSymbols.Rounded.Refresh
    @MapTo(SynaraIcons.Back) val Arrow_back = MaterialSymbols.Rounded.Arrow_back
    @MapTo(SynaraIcons.SideMenu) val Menu = MaterialSymbols.Rounded.Menu
    @MapTo(SynaraIcons.Clear)
    @MapTo(SynaraIcons.Close)
    val Close = MaterialSymbols.Rounded.Close
    @MapTo(SynaraIcons.MoreOptions) val More_vert = MaterialSymbols.Rounded.More_vert
    @MapTo(SynaraIcons.Play) val Play_arrow = MaterialSymbols.Rounded.Play_arrow
    @MapTo(SynaraIcons.Pause) val Pause = MaterialSymbols.Rounded.Pause
    @MapTo(SynaraIcons.SkipNext) val Skip_next = MaterialSymbols.Rounded.Skip_next
    @MapTo(SynaraIcons.SkipPrevious) val Skip_previous = MaterialSymbols.Rounded.Skip_previous
    @MapTo(SynaraIcons.Shuffle) val Shuffle = MaterialSymbols.Rounded.Shuffle
    @MapTo(SynaraIcons.Repeat) val Repeat = MaterialSymbols.Rounded.Repeat
    @MapTo(SynaraIcons.RepeatOne) val Repeat_one = MaterialSymbols.Rounded.Repeat_one
    @MapTo(SynaraIcons.PlayNext) val Playlist_play = MaterialSymbols.Rounded.Playlist_play
    @MapTo(SynaraIcons.AddToPlaylist) val Playlist_add = MaterialSymbols.Rounded.Playlist_add
    @MapTo(SynaraIcons.Albums) val Album = MaterialSymbols.Rounded.Album
    @MapTo(SynaraIcons.Artists) val Person = MaterialSymbols.Rounded.Person
    @MapTo(SynaraIcons.AlbumVersions) val Layers = MaterialSymbols.Rounded.Layers
    @MapTo(SynaraIcons.DeviceGeneric) val Devices = MaterialSymbols.Rounded.Devices
    @MapTo(SynaraIcons.DeviceMobile) val Smartphone = MaterialSymbols.Rounded.Smartphone
    @MapTo(SynaraIcons.DeviceDesktop) val Desktop_windows = MaterialSymbols.Rounded.Desktop_windows
    @MapTo(SynaraIcons.Upload) val Cloud_upload = MaterialSymbols.Rounded.Cloud_upload
    @MapTo(SynaraIcons.Add) val Add = MaterialSymbols.Rounded.Add
    @MapTo(SynaraIcons.Delete) val Delete = MaterialSymbols.Rounded.Delete
    @MapTo(SynaraIcons.DragHandle) val Drag_handle = MaterialSymbols.Rounded.Drag_handle
    @MapTo(SynaraIcons.Info) val Info = MaterialSymbols.Rounded.Info
    @MapTo(SynaraIcons.ThemeLight) val Light_mode = MaterialSymbols.Rounded.Light_mode
    @MapTo(SynaraIcons.ThemeDark) val Dark_mode = MaterialSymbols.Rounded.Dark_mode
    @MapTo(SynaraIcons.ChevronDown) val Arrow_drop_down = MaterialSymbols.Rounded.Arrow_drop_down
    @MapTo(SynaraIcons.Filter) val Filter_list = MaterialSymbols.Rounded.Filter_list
    @MapTo(SynaraIcons.FilterOff) val Filter_list_off = MaterialSymbols.Rounded.Filter_list_off
    @MapTo(SynaraIcons.VolumeHigh) val Volume_up = MaterialSymbols.Rounded.Volume_up
    @MapTo(SynaraIcons.VolumeOff) val Volume_off = MaterialSymbols.Rounded.Volume_off
    @MapTo(SynaraIcons.VolumeMute) val Volume_mute = MaterialSymbols.Rounded.Volume_mute
    @MapTo(SynaraIcons.VolumeLow) val Volume_down = MaterialSymbols.Rounded.Volume_down
    @MapTo(SynaraIcons.Success)
    @MapTo(SynaraIcons.CheckCircle)
    val Check_circle = MaterialSymbols.Rounded.Check_circle
    @MapTo(SynaraIcons.Expiration)
    @MapTo(SynaraIcons.Pending)
    val Schedule = MaterialSymbols.Rounded.Schedule
    @MapTo(SynaraIcons.ExpandDown) val Keyboard_arrow_down = MaterialSymbols.Rounded.Keyboard_arrow_down
    @MapTo(SynaraIcons.Lyrics) val Lyrics = MaterialSymbols.Rounded.Lyrics
    @MapTo(SynaraIcons.Queue) val Queue_music = MaterialSymbols.Rounded.Queue_music
    @MapTo(SynaraIcons.FullscreenEnter) val Fullscreen = MaterialSymbols.Rounded.Fullscreen
    @MapTo(SynaraIcons.FullscreenExit) val Fullscreen_exit = MaterialSymbols.Rounded.Fullscreen_exit
    @MapTo(SynaraIcons.RemoveFromPlaylist) val Playlist_remove = MaterialSymbols.Rounded.Playlist_remove
    @MapTo(SynaraIcons.RemoveFromQueue) val Do_not_disturb_on = MaterialSymbols.Rounded.Do_not_disturb_on
    @MapTo(SynaraIcons.ArtistMerge) val Merge = MaterialSymbols.Rounded.Merge
    @MapTo(SynaraIcons.ArtistSplit) val Call_split = MaterialSymbols.Rounded.Call_split
    @MapTo(SynaraIcons.Confirm) val Check = MaterialSymbols.Rounded.Check
    @MapTo(SynaraIcons.OpenInNew) val Open_in_new = MaterialSymbols.Rounded.Open_in_new
    @MapTo(SynaraIcons.ErrorCircle) val Error_circle_rounded = MaterialSymbols.Rounded.Error_circle_rounded
    @MapTo(SynaraIcons.SyncCircle) val Change_circle = MaterialSymbols.Rounded.Change_circle
    @MapTo(SynaraIcons.Circle) val Circle = MaterialSymbols.Rounded.Circle
    @MapTo(SynaraIcons.History) val History = MaterialSymbols.Rounded.History
}
