package dev.dertyp.synara.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.automirrored.twotone.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.twotone.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

enum class IconStyle {
    Rounded, Filled, Outlined, TwoTone
}

val LocalIconStyle = compositionLocalOf { IconStyle.Rounded }

data class SynaraIcon(
    private val rounded: ImageVector,
    private val filled: ImageVector,
    private val outlined: ImageVector,
    private val twoTone: ImageVector
) {
    @Composable
    fun get(): ImageVector {
        return when (LocalIconStyle.current) {
            IconStyle.Rounded -> rounded
            IconStyle.Filled -> filled
            IconStyle.Outlined -> outlined
            IconStyle.TwoTone -> twoTone
        }
    }
}

@Suppress("unused")
object SynaraIcons {
    val Home = SynaraIcon(Icons.Rounded.Home, Icons.Filled.Home, Icons.Outlined.Home, Icons.TwoTone.Home)
    val Search = SynaraIcon(Icons.Rounded.Search, Icons.Filled.Search, Icons.Outlined.Search, Icons.TwoTone.Search)
    val Library = SynaraIcon(Icons.Rounded.LibraryMusic, Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic, Icons.TwoTone.LibraryMusic)
    val Settings = SynaraIcon(Icons.Rounded.Settings, Icons.Filled.Settings, Icons.Outlined.Settings, Icons.TwoTone.Settings)
    val Favorite = SynaraIcon(Icons.Rounded.Favorite, Icons.Filled.Favorite, Icons.Outlined.Favorite, Icons.TwoTone.Favorite)
    val FavoriteBorder = SynaraIcon(Icons.Rounded.FavoriteBorder, Icons.Filled.FavoriteBorder, Icons.Outlined.FavoriteBorder, Icons.TwoTone.FavoriteBorder)
    val MusicNote = SynaraIcon(Icons.Rounded.MusicNote, Icons.Filled.MusicNote, Icons.Outlined.MusicNote, Icons.TwoTone.MusicNote)
    val Refresh = SynaraIcon(Icons.Rounded.Refresh, Icons.Filled.Refresh, Icons.Outlined.Refresh, Icons.TwoTone.Refresh)
    val ArrowBack = SynaraIcon(Icons.AutoMirrored.Rounded.ArrowBack, Icons.AutoMirrored.Filled.ArrowBack, Icons.AutoMirrored.Outlined.ArrowBack, Icons.AutoMirrored.TwoTone.ArrowBack)
    val Menu = SynaraIcon(Icons.Rounded.Menu, Icons.Filled.Menu, Icons.Outlined.Menu, Icons.TwoTone.Menu)
    val Clear = SynaraIcon(Icons.Rounded.Clear, Icons.Filled.Clear, Icons.Outlined.Clear, Icons.TwoTone.Clear)
    val MoreVert = SynaraIcon(Icons.Rounded.MoreVert, Icons.Filled.MoreVert, Icons.Outlined.MoreVert, Icons.TwoTone.MoreVert)
    val PlayArrow = SynaraIcon(Icons.Rounded.PlayArrow, Icons.Filled.PlayArrow, Icons.Outlined.PlayArrow, Icons.TwoTone.PlayArrow)
    val Pause = SynaraIcon(Icons.Rounded.Pause, Icons.Filled.Pause, Icons.Outlined.Pause, Icons.TwoTone.Pause)
    val SkipNext = SynaraIcon(Icons.Rounded.SkipNext, Icons.Filled.SkipNext, Icons.Outlined.SkipNext, Icons.TwoTone.SkipNext)
    val SkipPrevious = SynaraIcon(Icons.Rounded.SkipPrevious, Icons.Filled.SkipPrevious, Icons.Outlined.SkipPrevious, Icons.TwoTone.SkipPrevious)
    val Shuffle = SynaraIcon(Icons.Rounded.Shuffle, Icons.Filled.Shuffle, Icons.Outlined.Shuffle, Icons.TwoTone.Shuffle)
    val Repeat = SynaraIcon(Icons.Rounded.Repeat, Icons.Filled.Repeat, Icons.Outlined.Repeat, Icons.TwoTone.Repeat)
    val RepeatOne = SynaraIcon(Icons.Rounded.RepeatOne, Icons.Filled.RepeatOne, Icons.Outlined.RepeatOne, Icons.TwoTone.RepeatOne)
    val PlaylistPlay = SynaraIcon(Icons.AutoMirrored.Rounded.PlaylistPlay, Icons.AutoMirrored.Filled.PlaylistPlay, Icons.AutoMirrored.Outlined.PlaylistPlay, Icons.AutoMirrored.TwoTone.PlaylistPlay)
    val PlaylistAdd = SynaraIcon(Icons.AutoMirrored.Rounded.PlaylistAdd, Icons.AutoMirrored.Filled.PlaylistAdd, Icons.AutoMirrored.Outlined.PlaylistAdd, Icons.AutoMirrored.TwoTone.PlaylistAdd)
    val Album = SynaraIcon(Icons.Rounded.Album, Icons.Filled.Album, Icons.Outlined.Album, Icons.TwoTone.Album)
    val Person = SynaraIcon(Icons.Rounded.Person, Icons.Filled.Person, Icons.Outlined.Person, Icons.TwoTone.Person)
    val Layers = SynaraIcon(Icons.Rounded.Layers, Icons.Filled.Layers, Icons.Outlined.Layers, Icons.TwoTone.Layers)
    val Timer = SynaraIcon(Icons.Rounded.Timer, Icons.Filled.Timer, Icons.Outlined.Timer, Icons.TwoTone.Timer)
    val Devices = SynaraIcon(Icons.Rounded.Devices, Icons.Filled.Devices, Icons.Outlined.Devices, Icons.TwoTone.Devices)
    val Smartphone = SynaraIcon(Icons.Rounded.Smartphone, Icons.Filled.Smartphone, Icons.Outlined.Smartphone, Icons.TwoTone.Smartphone)
    val DesktopWindows = SynaraIcon(Icons.Rounded.DesktopWindows, Icons.Filled.DesktopWindows, Icons.Outlined.DesktopWindows, Icons.TwoTone.DesktopWindows)
    val CloudUpload = SynaraIcon(Icons.Rounded.CloudUpload, Icons.Filled.CloudUpload, Icons.Outlined.CloudUpload, Icons.TwoTone.CloudUpload)
    val Add = SynaraIcon(Icons.Rounded.Add, Icons.Filled.Add, Icons.Outlined.Add, Icons.TwoTone.Add)
    val Delete = SynaraIcon(Icons.Rounded.Delete, Icons.Filled.Delete, Icons.Outlined.Delete, Icons.TwoTone.Delete)
    val DragHandle = SynaraIcon(Icons.Rounded.DragHandle, Icons.Filled.DragHandle, Icons.Outlined.DragHandle, Icons.TwoTone.DragHandle)
    val Info = SynaraIcon(Icons.Rounded.Info, Icons.Filled.Info, Icons.Outlined.Info, Icons.TwoTone.Info)
    val LightMode = SynaraIcon(Icons.Rounded.LightMode, Icons.Filled.LightMode, Icons.Outlined.LightMode, Icons.TwoTone.LightMode)
    val DarkMode = SynaraIcon(Icons.Rounded.DarkMode, Icons.Filled.DarkMode, Icons.Outlined.DarkMode, Icons.TwoTone.DarkMode)
    val ArrowDropDown = SynaraIcon(Icons.Rounded.ArrowDropDown, Icons.Filled.ArrowDropDown, Icons.Outlined.ArrowDropDown, Icons.TwoTone.ArrowDropDown)
    val FilterList = SynaraIcon(Icons.Rounded.FilterList, Icons.Filled.FilterList, Icons.Outlined.FilterList, Icons.TwoTone.FilterList)
    val FilterListOff = SynaraIcon(Icons.Rounded.FilterListOff, Icons.Filled.FilterListOff, Icons.Outlined.FilterListOff, Icons.TwoTone.FilterListOff)
    val VolumeUp = SynaraIcon(Icons.AutoMirrored.Rounded.VolumeUp, Icons.AutoMirrored.Filled.VolumeUp, Icons.AutoMirrored.Outlined.VolumeUp, Icons.AutoMirrored.TwoTone.VolumeUp)
    val VolumeOff = SynaraIcon(Icons.AutoMirrored.Rounded.VolumeOff, Icons.AutoMirrored.Filled.VolumeOff, Icons.AutoMirrored.Outlined.VolumeOff, Icons.AutoMirrored.TwoTone.VolumeOff)
    val VolumeMute = SynaraIcon(Icons.AutoMirrored.Rounded.VolumeMute, Icons.AutoMirrored.Filled.VolumeMute, Icons.AutoMirrored.Outlined.VolumeMute, Icons.AutoMirrored.TwoTone.VolumeMute)
    val VolumeDown = SynaraIcon(Icons.AutoMirrored.Rounded.VolumeDown, Icons.AutoMirrored.Filled.VolumeDown, Icons.AutoMirrored.Outlined.VolumeDown, Icons.AutoMirrored.TwoTone.VolumeDown)
    val CheckCircle = SynaraIcon(Icons.Rounded.CheckCircle, Icons.Filled.CheckCircle, Icons.Outlined.CheckCircle, Icons.TwoTone.CheckCircle)
    val Schedule = SynaraIcon(Icons.Rounded.Schedule, Icons.Filled.Schedule, Icons.Outlined.Schedule, Icons.TwoTone.Schedule)
    val KeyboardArrowDown = SynaraIcon(Icons.Rounded.KeyboardArrowDown, Icons.Filled.KeyboardArrowDown, Icons.Outlined.KeyboardArrowDown, Icons.TwoTone.KeyboardArrowDown)
    val Lyrics = SynaraIcon(Icons.Rounded.Lyrics, Icons.Filled.Lyrics, Icons.Outlined.Lyrics, Icons.TwoTone.Lyrics)
    val QueueMusic = SynaraIcon(Icons.AutoMirrored.Rounded.QueueMusic, Icons.AutoMirrored.Filled.QueueMusic, Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.TwoTone.QueueMusic)
    val Fullscreen = SynaraIcon(Icons.Rounded.Fullscreen, Icons.Filled.Fullscreen, Icons.Outlined.Fullscreen, Icons.TwoTone.Fullscreen)
    val FullscreenExit = SynaraIcon(Icons.Rounded.FullscreenExit, Icons.Filled.FullscreenExit, Icons.Outlined.FullscreenExit, Icons.TwoTone.FullscreenExit)
    val PlaylistRemove = SynaraIcon(Icons.Rounded.PlaylistRemove, Icons.Filled.PlaylistRemove, Icons.Outlined.PlaylistRemove, Icons.TwoTone.PlaylistRemove)
    val RemoveCircleOutline = SynaraIcon(Icons.Rounded.RemoveCircleOutline, Icons.Filled.RemoveCircleOutline, Icons.Outlined.RemoveCircleOutline, Icons.TwoTone.RemoveCircleOutline)
    val Merge = SynaraIcon(Icons.Rounded.Merge, Icons.Filled.Merge, Icons.Outlined.Merge, Icons.TwoTone.Merge)
    val CallSplit = SynaraIcon(Icons.AutoMirrored.Rounded.CallSplit, Icons.AutoMirrored.Filled.CallSplit, Icons.AutoMirrored.Outlined.CallSplit, Icons.AutoMirrored.TwoTone.CallSplit)
    val Close = SynaraIcon(Icons.Rounded.Close, Icons.Filled.Close, Icons.Outlined.Close, Icons.TwoTone.Close)
}
