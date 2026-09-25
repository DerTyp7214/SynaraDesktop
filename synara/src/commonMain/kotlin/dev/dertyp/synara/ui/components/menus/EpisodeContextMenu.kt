package dev.dertyp.synara.ui.components.menus

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastImportState
import dev.dertyp.data.UserCapability
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.podcast.PodcastPlayer
import dev.dertyp.synara.podcast.isInProgress
import dev.dertyp.synara.podcast.isPlayed
import dev.dertyp.synara.screens.podcasts.PodcastShowScreen
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraMenu
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

@Composable
fun EpisodeContextMenu(
    episode: PodcastEpisode,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onGoToShow: (() -> Unit)? = null,
    showGoToShow: Boolean = true,
    onEpisodeChanged: (PodcastEpisode) -> Unit = {},
    podcastPlayer: PodcastPlayer = koinInject(),
    podcastService: IPodcastService = koinInject(),
    globalState: GlobalStateModel = koinInject(),
    snackbarManager: SnackbarManager = koinInject(),
) {
    val navigator = LocalNavigator.current
    val user by globalState.user.collectAsState()
    val scope = rememberCoroutineScope()
    val canEdit = user?.hasCapability(UserCapability.PODCAST_EDIT) == true

    fun runEditorAction(successMessage: StringResource, action: suspend () -> PodcastEpisode) {
        scope.launch {
            try {
                onEpisodeChanged(action())
                snackbarManager.showSnackbar(getString(successMessage))
            } catch (e: Exception) {
                snackbarManager.showSnackbar(getString(Res.string.podcast_action_failed, e.message ?: ""))
            }
        }
    }

    SynaraMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 240.dp)
        ) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = episode.showTitle,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text(stringResource(if (episode.isInProgress) Res.string.podcast_resume else Res.string.podcast_play)) },
            onClick = {
                podcastPlayer.playEpisode(episode)
                onDismissRequest()
            },
            leadingIcon = { Icon(SynaraIcons.Play.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.podcast_play_next)) },
            onClick = {
                podcastPlayer.playNext(episode)
                onDismissRequest()
            },
            leadingIcon = { Icon(SynaraIcons.PlayNext.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.podcast_add_to_queue)) },
            onClick = {
                podcastPlayer.addToQueue(listOf(episode))
                onDismissRequest()
            },
            leadingIcon = { Icon(SynaraIcons.AddToPlaylist.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        HorizontalDivider()

        DropdownMenuItem(
            text = {
                Text(stringResource(if (episode.isPlayed) Res.string.podcast_mark_unplayed else Res.string.podcast_mark_played))
            },
            onClick = {
                podcastPlayer.markPlayed(episode, !episode.isPlayed)
                onDismissRequest()
            },
            leadingIcon = { Icon(SynaraIcons.MarkPlayed.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        if (showGoToShow) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.podcast_go_to_show)) },
                onClick = {
                    if (onGoToShow != null) {
                        onGoToShow()
                    } else {
                        globalState.setPlayerExpanded(false)
                        navigator?.push(PodcastShowScreen(episode.showId))
                    }
                    onDismissRequest()
                },
                leadingIcon = { Icon(SynaraIcons.Podcast.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
        }

        if (canEdit) {
            val canImport = !episode.imported &&
                episode.importState != PodcastImportState.QUEUED &&
                episode.importState != PodcastImportState.IMPORTING
            if (canImport || episode.imported) HorizontalDivider()

            if (canImport) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.podcast_import_to_server)) },
                    onClick = {
                        runEditorAction(Res.string.podcast_import_queued_message) { podcastService.importEpisode(episode.id) }
                        onDismissRequest()
                    },
                    leadingIcon = { Icon(SynaraIcons.ServerStorage.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
                )
            }

            if (episode.imported) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.podcast_remove_stored_audio)) },
                    onClick = {
                        runEditorAction(Res.string.podcast_stored_audio_removed) { podcastService.removeImport(episode.id) }
                        onDismissRequest()
                    },
                    leadingIcon = { Icon(SynaraIcons.Delete.get(), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error
                    )
                )
            }
        }
    }
}
