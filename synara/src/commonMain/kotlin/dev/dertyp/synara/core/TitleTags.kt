package dev.dertyp.synara.core

import androidx.compose.runtime.Composable
import dev.dertyp.core.withTitleTags
import dev.dertyp.data.BaseSong
import dev.dertyp.data.Song
import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.data.UserSong
import dev.dertyp.synara.Config
import dev.dertyp.synara.ui.SynaraIcons
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

val List<TitleTag>.displayTags: List<TitleTag>
    get() = filter { it.kind != TitleTagKind.FEAT }

val BaseSong.displayTitle: String
    get() = title.withTitleTags(tags.displayTags)

fun BaseSong.textTitle(showTags: Boolean = Config.showTitleTagsInText.value): String =
    if (showTags) displayTitle else title

fun UserSong.toSong(): Song = Song(
    id = id,
    title = title,
    artists = artists,
    album = album,
    duration = duration,
    explicit = explicit,
    releaseDate = releaseDate,
    lyrics = lyrics,
    path = path,
    originalUrl = originalUrl,
    trackNumber = trackNumber,
    discNumber = discNumber,
    copyright = copyright,
    audio = audio,
    atmos = atmos,
    coverId = coverId,
    blurHash = blurHash,
    musicBrainzId = musicBrainzId,
    isrc = isrc,
    genres = genres,
    animatedCoverId = animatedCoverId,
    animatedCoverImageId = animatedCoverImageId,
    animatedCoverBlurHash = animatedCoverBlurHash,
    audioStartMs = audioStartMs,
    tags = tags,
)

@Composable
fun TitleTagKind.localizedName(): String = when (this) {
    TitleTagKind.FEAT -> stringResource(Res.string.title_tag_kind_feat)
    TitleTagKind.PROD -> stringResource(Res.string.title_tag_kind_prod)
    TitleTagKind.REMIX -> stringResource(Res.string.title_tag_kind_remix)
    TitleTagKind.MIX -> stringResource(Res.string.title_tag_kind_mix)
    TitleTagKind.LIVE -> stringResource(Res.string.title_tag_kind_live)
    TitleTagKind.COVER -> stringResource(Res.string.title_tag_kind_cover)
    TitleTagKind.ACOUSTIC -> stringResource(Res.string.title_tag_kind_acoustic)
    TitleTagKind.INSTRUMENTAL -> stringResource(Res.string.title_tag_kind_instrumental)
    TitleTagKind.EDIT -> stringResource(Res.string.title_tag_kind_edit)
    TitleTagKind.VERSION -> stringResource(Res.string.title_tag_kind_version)
    TitleTagKind.REMASTER -> stringResource(Res.string.title_tag_kind_remaster)
    TitleTagKind.DEMO -> stringResource(Res.string.title_tag_kind_demo)
}

val TitleTagKind.icon: SynaraIcons
    get() = when (this) {
        TitleTagKind.FEAT -> SynaraIcons.TagFeat
        TitleTagKind.PROD -> SynaraIcons.TagProd
        TitleTagKind.REMIX -> SynaraIcons.TagRemix
        TitleTagKind.MIX -> SynaraIcons.TagMix
        TitleTagKind.LIVE -> SynaraIcons.TagLive
        TitleTagKind.COVER -> SynaraIcons.TagCover
        TitleTagKind.ACOUSTIC -> SynaraIcons.TagAcoustic
        TitleTagKind.INSTRUMENTAL -> SynaraIcons.TagInstrumental
        TitleTagKind.EDIT -> SynaraIcons.TagEdit
        TitleTagKind.VERSION -> SynaraIcons.TagVersion
        TitleTagKind.REMASTER -> SynaraIcons.TagRemaster
        TitleTagKind.DEMO -> SynaraIcons.TagDemo
    }
