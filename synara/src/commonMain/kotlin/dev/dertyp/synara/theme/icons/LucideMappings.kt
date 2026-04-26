package dev.dertyp.synara.theme.icons

import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CircleMinus
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.CloudUpload
import com.composables.icons.lucide.Disc
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.GitMerge
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.HeartOff
import com.composables.icons.lucide.History
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Library
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.ListFilter
import com.composables.icons.lucide.ListFilterPlus
import com.composables.icons.lucide.ListMusic
import com.composables.icons.lucide.ListPlus
import com.composables.icons.lucide.ListVideo
import com.composables.icons.lucide.ListX
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Minimize
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Music
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Repeat
import com.composables.icons.lucide.Repeat1
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Shuffle
import com.composables.icons.lucide.SkipBack
import com.composables.icons.lucide.SkipForward
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Split
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.Tablet
import com.composables.icons.lucide.Trash
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Volume
import com.composables.icons.lucide.Volume1
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import com.composables.icons.lucide.X
import dev.dertyp.synara.ui.LucidePack
import dev.dertyp.synara.ui.MapTo
import dev.dertyp.synara.ui.SynaraIcons

@LucidePack
object LucideMappings {
    @MapTo(SynaraIcons.Dashboard) val House = Lucide.House
    @MapTo(SynaraIcons.Search) val Search = Lucide.Search
    @MapTo(SynaraIcons.Library) val Library = Lucide.Library
    @MapTo(SynaraIcons.Settings) val Settings = Lucide.Settings
    @MapTo(SynaraIcons.IsFavorite) val HeartOff = Lucide.HeartOff
    @MapTo(SynaraIcons.IsNotFavorite) val Heart = Lucide.Heart
    @MapTo(SynaraIcons.Songs) val Music = Lucide.Music
    @MapTo(SynaraIcons.Refresh)
    @MapTo(SynaraIcons.SyncCircle)
    @MapTo(SynaraIcons.Sync)
    val RefreshCw = Lucide.RefreshCw
    @MapTo(SynaraIcons.Back) val ArrowLeft = Lucide.ArrowLeft
    @MapTo(SynaraIcons.SideMenu) val Menu = Lucide.Menu
    @MapTo(SynaraIcons.Close)
    @MapTo(SynaraIcons.Clear)
    val X = Lucide.X
    @MapTo(SynaraIcons.MoreOptions) val EllipsisVertical = Lucide.EllipsisVertical
    @MapTo(SynaraIcons.Play) val Play = Lucide.Play
    @MapTo(SynaraIcons.Pause) val Pause = Lucide.Pause
    @MapTo(SynaraIcons.SkipNext) val SkipForward = Lucide.SkipForward
    @MapTo(SynaraIcons.SkipPrevious) val SkipBack = Lucide.SkipBack
    @MapTo(SynaraIcons.Shuffle) val Shuffle = Lucide.Shuffle
    @MapTo(SynaraIcons.Repeat) val Repeat = Lucide.Repeat
    @MapTo(SynaraIcons.RepeatOne) val Repeat1 = Lucide.Repeat1
    @MapTo(SynaraIcons.PlayNext) val ListVideo = Lucide.ListVideo
    @MapTo(SynaraIcons.AddToPlaylist) val ListPlus = Lucide.ListPlus
    @MapTo(SynaraIcons.Albums) val Disc = Lucide.Disc
    @MapTo(SynaraIcons.Artists) val User = Lucide.User
    @MapTo(SynaraIcons.AlbumVersions) val Layers = Lucide.Layers
    @MapTo(SynaraIcons.DeviceGeneric) val Tablet = Lucide.Tablet
    @MapTo(SynaraIcons.DeviceMobile) val Smartphone = Lucide.Smartphone
    @MapTo(SynaraIcons.DeviceDesktop) val Monitor = Lucide.Monitor
    @MapTo(SynaraIcons.Upload) val CloudUpload = Lucide.CloudUpload
    @MapTo(SynaraIcons.Add) val Plus = Lucide.Plus
    @MapTo(SynaraIcons.Delete) val Trash = Lucide.Trash
    @MapTo(SynaraIcons.Edit) val Pencil = Lucide.Pencil
    @MapTo(SynaraIcons.DragHandle) val GripVertical = Lucide.GripVertical
    @MapTo(SynaraIcons.Info) val Info = Lucide.Info
    @MapTo(SynaraIcons.ThemeLight) val Sun = Lucide.Sun
    @MapTo(SynaraIcons.ThemeDark) val Moon = Lucide.Moon
    @MapTo(SynaraIcons.ChevronDown)
    @MapTo(SynaraIcons.ExpandDown)
    val ChevronDown = Lucide.ChevronDown
    @MapTo(SynaraIcons.ExpandUp) val ChevronUp = Lucide.ChevronUp
    @MapTo(SynaraIcons.Filter) val ListFilterPlus = Lucide.ListFilterPlus
    @MapTo(SynaraIcons.FilterOff) val ListFilter = Lucide.ListFilter
    @MapTo(SynaraIcons.VolumeHigh) val Volume2 = Lucide.Volume2
    @MapTo(SynaraIcons.VolumeOff) val VolumeX = Lucide.VolumeX
    @MapTo(SynaraIcons.VolumeMute) val Volume = Lucide.Volume
    @MapTo(SynaraIcons.VolumeLow) val Volume1 = Lucide.Volume1
    @MapTo(SynaraIcons.Success)
    @MapTo(SynaraIcons.CheckCircle)
    val CircleCheck = Lucide.CircleCheck
    @MapTo(SynaraIcons.Expiration)
    @MapTo(SynaraIcons.Pending)
    val Clock = Lucide.Clock
    @MapTo(SynaraIcons.Lyrics) val Mic = Lucide.Mic
    @MapTo(SynaraIcons.Queue) val ListMusic = Lucide.ListMusic
    @MapTo(SynaraIcons.FullscreenEnter) val Maximize = Lucide.Maximize
    @MapTo(SynaraIcons.FullscreenExit) val Minimize = Lucide.Minimize
    @MapTo(SynaraIcons.RemoveFromPlaylist) val ListX = Lucide.ListX
    @MapTo(SynaraIcons.RemoveFromQueue) val CircleMinus = Lucide.CircleMinus
    @MapTo(SynaraIcons.ArtistMerge) val GitMerge = Lucide.GitMerge
    @MapTo(SynaraIcons.ArtistSplit) val Split = Lucide.Split
    @MapTo(SynaraIcons.Confirm) val Check = Lucide.Check
    @MapTo(SynaraIcons.OpenInNew) val ExternalLink = Lucide.ExternalLink
    @MapTo(SynaraIcons.ErrorCircle) val CircleAlert = Lucide.CircleAlert
    @MapTo(SynaraIcons.Circle) val Circle = Lucide.Circle
    @MapTo(SynaraIcons.History) val History = Lucide.History
    @MapTo(SynaraIcons.Link) val Link = Lucide.Link
    @MapTo(SynaraIcons.Download) val Download = Lucide.Download
    @MapTo(SynaraIcons.Discovery) val Sparkles = Lucide.Sparkles
}
