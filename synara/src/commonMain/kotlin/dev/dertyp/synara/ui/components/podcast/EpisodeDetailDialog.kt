package dev.dertyp.synara.ui.components.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastEpisodeType
import dev.dertyp.data.PodcastTranscript
import dev.dertyp.data.UserCapability
import dev.dertyp.formatDate
import dev.dertyp.platformDateFromEpochMilliseconds
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.formatCompactDuration
import dev.dertyp.synara.player.ActivePlayer
import dev.dertyp.synara.player.PlayerSwitcher
import dev.dertyp.synara.podcast.*
import dev.dertyp.synara.screens.podcasts.PodcastShowScreen
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.components.dialogs.SynaraDialog
import dev.dertyp.synara.ui.components.formatDuration
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EpisodeDetailDialog(
    episode: PodcastEpisode,
    onDismissRequest: () -> Unit,
    onGoToShow: (() -> Unit)? = null,
    showGoToShow: Boolean = true,
    podcastPlayer: PodcastPlayer = koinInject(),
    podcastService: IPodcastService = koinInject(),
    progressStore: PodcastProgressStore = koinInject(),
    globalState: GlobalStateModel = koinInject(),
    playerSwitcher: PlayerSwitcher = koinInject(),
) {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    val user by globalState.user.collectAsState()
    val canEdit = user?.hasCapability(UserCapability.PODCAST_EDIT) == true

    var current by remember(episode) { mutableStateOf(episode.withProgress(progressStore.latest.value)) }
    var transcripts by remember(episode.id) { mutableStateOf<List<PodcastTranscript>>(emptyList()) }

    val currentEpisode by podcastPlayer.currentEpisode.collectAsState()
    val isPlaying by podcastPlayer.isPlaying.collectAsState()
    val isCurrent = currentEpisode?.id == current.id

    LaunchedEffect(episode.id) {
        progressStore.updates.collect { progress ->
            if (progress.episodeId == current.id) {
                current = current.withProgress(progress)
            }
        }
    }

    LaunchedEffect(episode.id, episode.hasTranscript) {
        if (!episode.hasTranscript) return@LaunchedEffect
        transcripts = try {
            podcastService.getTranscripts(episode.id).filter { it.available }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    val notes = remember(current.description) { htmlToPlainText(current.description) }
    val date = remember(current.publishedAt) { platformDateFromEpochMilliseconds(current.publishedAt).formatDate() }
    val durationText = current.knownDurationMs?.formatCompactDuration(includeSeconds = false)
    val meta = if (durationText != null) stringResource(Res.string.podcast_date_duration, date, durationText) else date
    val numbering = when {
        current.seasonNumber != null && current.episodeNumber != null ->
            stringResource(Res.string.podcast_season_episode, current.seasonNumber ?: 0, current.episodeNumber ?: 0)
        current.episodeNumber != null -> stringResource(Res.string.podcast_episode_number, current.episodeNumber ?: 0)
        else -> null
    }

    fun goToShow() {
        onDismissRequest()
        if (onGoToShow != null) {
            onGoToShow()
        } else {
            globalState.setPlayerExpanded(false)
            navigator?.push(PodcastShowScreen(current.showId))
        }
    }

    SynaraDialog(isOpen = true, onDismissRequest = onDismissRequest) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.width(620.dp).heightIn(max = 760.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    SynaraImage(
                        imageId = current.artworkId,
                        size = 128.dp,
                        shape = MaterialTheme.shapes.medium,
                        fallbackIcon = SynaraIcons.Podcast
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = current.showTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (showGoToShow) {
                                Modifier
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable { goToShow() }
                            } else Modifier
                        )
                        Text(
                            text = current.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = listOfNotNull(numbering, meta).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            when (current.episodeType) {
                                PodcastEpisodeType.TRAILER -> PodcastBadge(stringResource(Res.string.podcast_episode_trailer))
                                PodcastEpisodeType.BONUS -> PodcastBadge(stringResource(Res.string.podcast_episode_bonus))
                                PodcastEpisodeType.FULL -> Unit
                            }
                            if (current.explicit) PodcastBadge(stringResource(Res.string.podcast_explicit), error = true)
                            if (current.isPlayed) PodcastBadge(stringResource(Res.string.podcast_played))
                            if (canEdit) ImportStateBadge(current.importState)
                        }
                        if (current.isInProgress) {
                            LinearProgressIndicator(
                                progress = { current.progressFraction },
                                modifier = Modifier.padding(top = 4.dp).widthIn(max = 200.dp).fillMaxWidth().height(4.dp),
                                drawStopIndicator = {}
                            )
                        }
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(SynaraIcons.Close.get(), contentDescription = stringResource(Res.string.cancel))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (isCurrent) podcastPlayer.togglePlayPause() else podcastPlayer.playEpisode(current)
                        }
                    ) {
                        val playingNow = isCurrent && isPlaying
                        Icon(
                            if (playingNow) SynaraIcons.Pause.get() else SynaraIcons.Play.get(),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when {
                                playingNow -> stringResource(Res.string.podcast_now_playing)
                                isCurrent -> stringResource(Res.string.podcast_resume)
                                current.isInProgress -> stringResource(Res.string.podcast_resume_at, formatDuration(current.positionMs))
                                else -> stringResource(Res.string.podcast_play)
                            }
                        )
                    }

                    OutlinedButton(onClick = { podcastPlayer.markPlayed(current, !current.isPlayed) }) {
                        Icon(SynaraIcons.MarkPlayed.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(if (current.isPlayed) Res.string.podcast_mark_unplayed else Res.string.podcast_mark_played)
                        )
                    }

                    val link = current.link?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    if (link != null) {
                        TextButton(onClick = { runCatching { uriHandler.openUri(link) } }) {
                            Icon(SynaraIcons.OpenInNew.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.podcast_open_website))
                        }
                    }
                }

                if (transcripts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(Res.string.podcast_transcripts),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        transcripts.forEach { transcript ->
                            AssistChip(
                                onClick = {
                                    if (!isCurrent) podcastPlayer.playEpisode(current)
                                    playerSwitcher.select(ActivePlayer.PODCAST)
                                    globalState.setLyricsExpanded(true)
                                    onDismissRequest()
                                },
                                label = {
                                    Text(
                                        listOfNotNull(
                                            transcript.language?.takeIf { it.isNotBlank() }
                                                ?: stringResource(Res.string.podcast_transcript_language_unknown),
                                            transcriptFormatLabel(transcript.type)
                                        ).joinToString(" · ")
                                    )
                                },
                                leadingIcon = {
                                    Icon(SynaraIcons.Transcript.get(), contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(Res.string.podcast_show_notes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (notes.isBlank()) {
                    Text(
                        text = stringResource(Res.string.podcast_no_show_notes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    SelectionContainer(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun transcriptFormatLabel(type: String): String? {
    val lower = type.lowercase()
    return when {
        "json" in lower -> "JSON"
        "vtt" in lower -> "VTT"
        "srt" in lower || "subrip" in lower -> "SRT"
        "html" in lower -> "HTML"
        else -> null
    }
}
