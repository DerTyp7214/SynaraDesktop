package dev.dertyp.synara.theme.icons

import dev.dertyp.synara.ui.FillMode
import dev.dertyp.synara.ui.MapTo
import dev.dertyp.synara.ui.PhosphorPack
import dev.dertyp.synara.ui.SynaraIcons
import icons.PhIcons
import icons.regular.ArrowClockwise
import icons.regular.ArrowLeft
import icons.regular.ArrowSquareOut
import icons.regular.ArrowsClockwise
import icons.regular.ArrowsIn
import icons.regular.ArrowsOut
import icons.regular.ArrowsSplit
import icons.regular.Books
import icons.regular.CaretCircleDown
import icons.regular.CaretCircleUp
import icons.regular.CaretDown
import icons.regular.Check
import icons.regular.CheckCircle
import icons.regular.Circle
import icons.regular.Clock
import icons.regular.ClockAfternoon
import icons.regular.CloudArrowUp
import icons.regular.Desktop
import icons.regular.DeviceMobile
import icons.regular.DeviceTablet
import icons.regular.Disc
import icons.regular.DotsSixVertical
import icons.regular.DotsThreeVertical
import icons.regular.DownloadSimple
import icons.regular.Funnel
import icons.regular.FunnelSimple
import icons.regular.Gear
import icons.regular.GitMerge
import icons.regular.Heart
import icons.regular.House
import icons.regular.Info
import icons.regular.Link
import icons.regular.List
import icons.regular.ListChecks
import icons.regular.ListDashes
import icons.regular.MagnifyingGlass
import icons.regular.Microphone
import icons.regular.MinusCircle
import icons.regular.Moon
import icons.regular.MusicNote
import icons.regular.Pause
import icons.regular.PencilSimple
import icons.regular.Play
import icons.regular.Playlist
import icons.regular.Plus
import icons.regular.PlusSquare
import icons.regular.Repeat
import icons.regular.RepeatOnce
import icons.regular.Shuffle
import icons.regular.SkipBack
import icons.regular.SkipForward
import icons.regular.Sparkle
import icons.regular.SpeakerHigh
import icons.regular.SpeakerLow
import icons.regular.SpeakerNone
import icons.regular.SpeakerX
import icons.regular.Stack
import icons.regular.Sun
import icons.regular.Trash
import icons.regular.User
import icons.regular.WarningCircle
import icons.regular.X

@PhosphorPack
object PhosphorMappings {
    @MapTo(SynaraIcons.Dashboard) val House = PhIcons.Regular.House
    @MapTo(SynaraIcons.Search) val MagnifyingGlass = PhIcons.Regular.MagnifyingGlass
    @MapTo(SynaraIcons.Library) val Books = PhIcons.Regular.Books
    @MapTo(SynaraIcons.Settings) val Gear = PhIcons.Regular.Gear
    @MapTo(SynaraIcons.IsFavorite, fillMode = FillMode.Filled)
    @MapTo(SynaraIcons.IsNotFavorite, fillMode = FillMode.Outlined)
    val Heart = PhIcons.Regular.Heart
    @MapTo(SynaraIcons.Songs) val MusicNote = PhIcons.Regular.MusicNote
    @MapTo(SynaraIcons.Refresh)
    @MapTo(SynaraIcons.Sync)
    val ArrowsClockwise = PhIcons.Regular.ArrowsClockwise
    @MapTo(SynaraIcons.Back) val ArrowLeft = PhIcons.Regular.ArrowLeft
    @MapTo(SynaraIcons.SideMenu) val List = PhIcons.Regular.List
    @MapTo(SynaraIcons.Clear)
    @MapTo(SynaraIcons.Close)
    val X = PhIcons.Regular.X
    @MapTo(SynaraIcons.MoreOptions) val DotsThreeVertical = PhIcons.Regular.DotsThreeVertical
    @MapTo(SynaraIcons.Play) val Play = PhIcons.Regular.Play
    @MapTo(SynaraIcons.Pause) val Pause = PhIcons.Regular.Pause
    @MapTo(SynaraIcons.SkipNext) val SkipForward = PhIcons.Regular.SkipForward
    @MapTo(SynaraIcons.SkipPrevious) val SkipBack = PhIcons.Regular.SkipBack
    @MapTo(SynaraIcons.Shuffle) val Shuffle = PhIcons.Regular.Shuffle
    @MapTo(SynaraIcons.Repeat) val Repeat = PhIcons.Regular.Repeat
    @MapTo(SynaraIcons.RepeatOne) val RepeatOnce = PhIcons.Regular.RepeatOnce
    @MapTo(SynaraIcons.PlayNext) val Playlist = PhIcons.Regular.Playlist
    @MapTo(SynaraIcons.AddToPlaylist) val PlusSquare = PhIcons.Regular.PlusSquare
    @MapTo(SynaraIcons.Albums) val Disc = PhIcons.Regular.Disc
    @MapTo(SynaraIcons.Artists) val User = PhIcons.Regular.User
    @MapTo(SynaraIcons.AlbumVersions) val Stack = PhIcons.Regular.Stack
    @MapTo(SynaraIcons.DeviceGeneric) val DeviceTablet = PhIcons.Regular.DeviceTablet
    @MapTo(SynaraIcons.DeviceMobile) val DeviceMobile = PhIcons.Regular.DeviceMobile
    @MapTo(SynaraIcons.DeviceDesktop) val Desktop = PhIcons.Regular.Desktop
    @MapTo(SynaraIcons.Upload) val CloudArrowUp = PhIcons.Regular.CloudArrowUp
    @MapTo(SynaraIcons.Add) val Plus = PhIcons.Regular.Plus
    @MapTo(SynaraIcons.Delete) val Trash = PhIcons.Regular.Trash
    @MapTo(SynaraIcons.Edit) val PencilSimple = PhIcons.Regular.PencilSimple
    @MapTo(SynaraIcons.DragHandle) val DotsSixVertical = PhIcons.Regular.DotsSixVertical
    @MapTo(SynaraIcons.Info) val Info = PhIcons.Regular.Info
    @MapTo(SynaraIcons.ThemeLight) val Sun = PhIcons.Regular.Sun
    @MapTo(SynaraIcons.ThemeDark) val Moon = PhIcons.Regular.Moon
    @MapTo(SynaraIcons.ChevronDown) val CaretDown = PhIcons.Regular.CaretDown
    @MapTo(SynaraIcons.Filter) val Funnel = PhIcons.Regular.Funnel
    @MapTo(SynaraIcons.FilterOff) val FunnelSimple = PhIcons.Regular.FunnelSimple
    @MapTo(SynaraIcons.VolumeHigh) val SpeakerHigh = PhIcons.Regular.SpeakerHigh
    @MapTo(SynaraIcons.VolumeOff) val SpeakerX = PhIcons.Regular.SpeakerX
    @MapTo(SynaraIcons.VolumeMute) val SpeakerNone = PhIcons.Regular.SpeakerNone
    @MapTo(SynaraIcons.VolumeLow) val SpeakerLow = PhIcons.Regular.SpeakerLow
    @MapTo(SynaraIcons.Success)
    @MapTo(SynaraIcons.CheckCircle)
    val CheckCircle = PhIcons.Regular.CheckCircle
    @MapTo(SynaraIcons.Expiration)
    @MapTo(SynaraIcons.Pending)
    val Clock = PhIcons.Regular.Clock
    @MapTo(SynaraIcons.ExpandDown) val CaretCircleDown = PhIcons.Regular.CaretCircleDown
    @MapTo(SynaraIcons.ExpandUp) val CaretCircleUp = PhIcons.Regular.CaretCircleUp
    @MapTo(SynaraIcons.Lyrics) val Microphone = PhIcons.Regular.Microphone
    @MapTo(SynaraIcons.Queue) val ListDashes = PhIcons.Regular.ListDashes
    @MapTo(SynaraIcons.FullscreenEnter) val ArrowsOut = PhIcons.Regular.ArrowsOut
    @MapTo(SynaraIcons.FullscreenExit) val ArrowsIn = PhIcons.Regular.ArrowsIn
    @MapTo(SynaraIcons.RemoveFromPlaylist) val ListChecks = PhIcons.Regular.ListChecks
    @MapTo(SynaraIcons.RemoveFromQueue) val MinusCircle = PhIcons.Regular.MinusCircle
    @MapTo(SynaraIcons.ArtistMerge) val GitMerge = PhIcons.Regular.GitMerge
    @MapTo(SynaraIcons.ArtistSplit) val ArrowsSplit = PhIcons.Regular.ArrowsSplit
    @MapTo(SynaraIcons.Confirm) val Check = PhIcons.Regular.Check
    @MapTo(SynaraIcons.OpenInNew) val ArrowSquareOut = PhIcons.Regular.ArrowSquareOut
    @MapTo(SynaraIcons.ErrorCircle) val WarningCircle = PhIcons.Regular.WarningCircle
    @MapTo(SynaraIcons.SyncCircle) val ArrowClockwise = PhIcons.Regular.ArrowClockwise
    @MapTo(SynaraIcons.Circle) val Circle = PhIcons.Regular.Circle
    @MapTo(SynaraIcons.History) val ClockAfternoon = PhIcons.Regular.ClockAfternoon
    @MapTo(SynaraIcons.Link) val Link = PhIcons.Regular.Link
    @MapTo(SynaraIcons.Download) val DownloadSimple = PhIcons.Regular.DownloadSimple
    @MapTo(SynaraIcons.Discovery) val Sparkle = PhIcons.Regular.Sparkle
}
