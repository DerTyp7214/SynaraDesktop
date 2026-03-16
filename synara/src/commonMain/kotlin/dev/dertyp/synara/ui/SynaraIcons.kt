package dev.dertyp.synara.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.StringResource
import synara.synara.generated.resources.*

enum class IconPackType(val label: StringResource) {
    MaterialSymbols(Res.string.icon_pack_material_symbols),
    Lucide(Res.string.icon_pack_lucide),
    Phosphor(Res.string.icon_pack_phosphor);

    fun getPack(): SynaraIconPack {
        return when (this) {
            MaterialSymbols -> MaterialSymbolsIconPack
            Lucide -> LucideIconPack
            Phosphor -> PhosphorIconPack
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

enum class PhosphorIconStyle(override val id: String, override val label: StringResource) : SynaraIconStyle {
    Thin("thin", Res.string.icon_style_thin),
    Light("light", Res.string.icon_style_light),
    Regular("regular", Res.string.icon_style_regular),
    Bold("bold", Res.string.icon_style_bold),
    Filled("filled", Res.string.icon_style_filled),
    Duotone("duotone", Res.string.icon_style_duotone)
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
    ArtistMerge, ArtistSplit, Close, Confirm, OpenInNew;

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
        return generatedGet(id, s, filled)
    }

    internal fun resolve(
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

internal expect fun MaterialSymbolsIconPack.generatedGet(id: SynaraIcons, style: MaterialSymbolStyle, filled: Boolean): ImageVector

object LucideIconPack : SynaraIconPack() {
    override val name: String = "Lucide"
    override val type: IconPackType = IconPackType.Lucide
    override val styles: List<SynaraIconStyle> = listOf(LucideStyle)
    override val hasFilledOption: Boolean = false

    @Composable
    override fun get(id: SynaraIcons, style: SynaraIconStyle, filled: Boolean): ImageVector {
        return generatedGet(id)
    }
}

internal expect fun LucideIconPack.generatedGet(id: SynaraIcons): ImageVector

object PhosphorIconPack : SynaraIconPack() {
    override val name: String = "Phosphor"
    override val type: IconPackType = IconPackType.Phosphor
    override val styles: List<SynaraIconStyle> = PhosphorIconStyle.entries
    override val hasFilledOption: Boolean = false

    @Composable
    override fun get(id: SynaraIcons, style: SynaraIconStyle, filled: Boolean): ImageVector {
        val s = style as? PhosphorIconStyle ?: PhosphorIconStyle.Regular
        return generatedGet(id, s, filled)
    }

    internal fun resolve(
        thin: ImageVector, light: ImageVector, regular: ImageVector,
        bold: ImageVector, fill: ImageVector, duotone: ImageVector,
        filled: Boolean, style: PhosphorIconStyle
    ): ImageVector {
        if (filled) return fill
        return when (style) {
            PhosphorIconStyle.Thin -> thin
            PhosphorIconStyle.Light -> light
            PhosphorIconStyle.Regular -> regular
            PhosphorIconStyle.Bold -> bold
            PhosphorIconStyle.Filled -> fill
            PhosphorIconStyle.Duotone -> duotone
        }
    }
}

internal expect fun PhosphorIconPack.generatedGet(id: SynaraIcons, style: PhosphorIconStyle, filled: Boolean): ImageVector
