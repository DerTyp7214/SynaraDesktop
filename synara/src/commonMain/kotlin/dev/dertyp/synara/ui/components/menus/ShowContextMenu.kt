package dev.dertyp.synara.ui.components.menus

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.data.PodcastShow
import dev.dertyp.data.PodcastSource
import dev.dertyp.data.UserCapability
import dev.dertyp.services.IPodcastService
import dev.dertyp.synara.podcast.PodcastPlayer
import dev.dertyp.synara.podcast.PodcastProgressStore
import dev.dertyp.synara.podcast.continuationQueue
import dev.dertyp.synara.podcast.resolveContinuation
import dev.dertyp.synara.podcast.withProgress
import dev.dertyp.synara.screens.podcasts.PodcastShowScreen
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraMenu
import dev.dertyp.synara.ui.components.dialogs.SynaraAlertDialog
import dev.dertyp.synara.ui.components.podcast.ShowStorageDialog
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

@Composable
fun ShowContextMenu(
    show: PodcastShow,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    showOpen: Boolean = true,
    onShowChanged: (PodcastShow) -> Unit = {},
    onDeleted: () -> Unit = {},
    onRefresh: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    podcastService: IPodcastService = koinInject(),
    podcastPlayer: PodcastPlayer = koinInject(),
    progressStore: PodcastProgressStore = koinInject(),
    globalState: GlobalStateModel = koinInject(),
    snackbarManager: SnackbarManager = koinInject(),
) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val user by globalState.user.collectAsState()
    val canEdit = user?.hasCapability(UserCapability.PODCAST_EDIT) == true
    val canDelete = user?.isAdmin == true && show.source == PodcastSource.FEED

    var confirmUnsubscribe by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showStorage by remember { mutableStateOf(false) }

    fun launchAction(failure: StringResource = Res.string.podcast_action_failed, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbarManager.showSnackbar(getString(failure, e.message ?: ""))
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
                text = show.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary
            )
            show.author?.takeIf { it.isNotBlank() }?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider()

        if (showOpen) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.podcast_go_to_show)) },
                onClick = {
                    navigator?.push(PodcastShowScreen(show.id))
                    onDismissRequest()
                },
                leadingIcon = { Icon(SynaraIcons.Podcast.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
        }

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.podcast_play)) },
            onClick = {
                onDismissRequest()
                if (onPlay != null) {
                    onPlay()
                } else {
                    launchAction {
                        val target = podcastService.resolveContinuation(show.id)
                        if (target == null) {
                            podcastPlayer.playShow(show.id)
                        } else {
                            val queue = podcastService.continuationQueue(target.episode)
                            podcastPlayer.playEpisodes(queue.withProgress(progressStore.latest.value), 0)
                        }
                    }
                }
            },
            leadingIcon = { Icon(SynaraIcons.Play.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        if (show.subscribed) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.podcast_unsubscribe)) },
                onClick = {
                    confirmUnsubscribe = true
                    onDismissRequest()
                },
                leadingIcon = { Icon(SynaraIcons.Close.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
        } else {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.podcast_subscribe)) },
                onClick = {
                    onDismissRequest()
                    launchAction(failure = Res.string.podcast_subscribe_failed) {
                        onShowChanged(podcastService.subscribeToShow(show.id))
                        snackbarManager.showSnackbar(getString(Res.string.podcast_subscribed))
                    }
                },
                leadingIcon = { Icon(SynaraIcons.Add.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
        }

        DropdownMenuItem(
            text = { Text(stringResource(Res.string.podcast_refresh)) },
            onClick = {
                onDismissRequest()
                if (onRefresh != null) {
                    onRefresh()
                } else {
                    scope.launch {
                        try {
                            onShowChanged(podcastService.refreshShow(show.id))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            snackbarManager.showSnackbar(getString(Res.string.podcast_refresh_failed))
                        }
                    }
                }
            },
            leadingIcon = { Icon(SynaraIcons.Refresh.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        if (canEdit || canDelete) HorizontalDivider()

        if (canEdit) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.podcast_server_storage)) },
                onClick = {
                    showStorage = true
                    onDismissRequest()
                },
                leadingIcon = { Icon(SynaraIcons.ServerStorage.get(), contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
        }

        if (canDelete) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.podcast_delete_show)) },
                onClick = {
                    confirmDelete = true
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

    UnsubscribeConfirmDialog(
        isOpen = confirmUnsubscribe,
        show = show,
        onDismissRequest = { confirmUnsubscribe = false },
        onConfirm = {
            confirmUnsubscribe = false
            launchAction {
                podcastService.unsubscribe(show.id)
                onShowChanged(podcastService.getShow(show.id) ?: show.copy(subscribed = false))
            }
        }
    )

    DeleteShowConfirmDialog(
        isOpen = confirmDelete,
        show = show,
        onDismissRequest = { confirmDelete = false },
        onConfirm = {
            confirmDelete = false
            scope.launch {
                try {
                    podcastService.deleteShow(show.id)
                    onDeleted()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    snackbarManager.showSnackbar(getString(Res.string.podcast_delete_show_failed, e.message ?: ""))
                }
            }
        }
    )

    if (showStorage) {
        ShowStorageDialog(
            show = show,
            onDismissRequest = { showStorage = false },
            onSave = { settings -> onShowChanged(podcastService.updateShowSettings(show.id, settings)) },
            onSaved = {
                showStorage = false
                scope.launch { snackbarManager.showSnackbar(getString(Res.string.podcast_storage_saved)) }
            },
            onFailed = { message -> scope.launch { snackbarManager.showSnackbar(getString(Res.string.podcast_storage_failed, message)) } }
        )
    }
}

@Composable
fun UnsubscribeConfirmDialog(
    isOpen: Boolean,
    show: PodcastShow,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    SynaraAlertDialog(
        isOpen = isOpen,
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Res.string.podcast_unsubscribe_confirm_title, show.title)) },
        text = { Text(stringResource(Res.string.podcast_unsubscribe_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.podcast_unsubscribe), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}

@Composable
fun DeleteShowConfirmDialog(
    isOpen: Boolean,
    show: PodcastShow,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    SynaraAlertDialog(
        isOpen = isOpen,
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Res.string.podcast_delete_show_confirm_title, show.title)) },
        text = { Text(stringResource(Res.string.podcast_delete_show_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}
