package dev.dertyp.synara.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dertyp.PlatformUUID
import dev.dertyp.data.PlaylistAccess
import dev.dertyp.data.UserProfile
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.ui.verticalScrollScrim
import dev.dertyp.synara.viewmodels.PlaylistShareModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf
import synara.synara.generated.resources.*

@Composable
private fun rememberPlaylistShareModel(playlistId: PlatformUUID): PlaylistShareModel {
    val koin = getKoin()
    val model = remember(playlistId) { koin.get<PlaylistShareModel> { parametersOf(playlistId) } }
    DisposableEffect(model) {
        onDispose { model.close() }
    }
    return model
}

@Composable
fun PlaylistShareDialog(
    isOpen: Boolean,
    playlistId: PlatformUUID,
    playlistName: String,
    onDismissRequest: () -> Unit
) {
    if (!isOpen) return

    val model = rememberPlaylistShareModel(playlistId)
    LaunchedEffect(model) { model.load() }
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var choosingOwner by remember { mutableStateOf(false) }
    var newOwner by remember { mutableStateOf<UserProfile?>(null) }

    SynaraAlertDialog(
        isOpen = true,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(if (choosingOwner) Res.string.playlist_transfer else Res.string.playlist_share_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            val lazyListState = rememberLazyListState()
            Box(modifier = Modifier.heightIn(max = 480.dp)) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScrollScrim(lazyListState),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!choosingOwner) {
                        item(key = "public") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(SynaraIcons.Globe.get(), contentDescription = null, modifier = Modifier.size(24.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(Res.string.playlist_public),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(Res.string.playlist_public_summary),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = state.isPublic,
                                    onCheckedChange = { model.setPublic(it) },
                                    enabled = !state.isLoading
                                )
                            }
                        }

                        if (state.members.isNotEmpty()) {
                            item(key = "members") { SectionLabel(stringResource(Res.string.playlist_shared_with)) }
                        }

                        items(state.members, key = { "member-${it.profile.id}" }) { member ->
                            UserRow(member.profile) {
                                PlaylistAccess.entries.forEach { access ->
                                    FilterChip(
                                        selected = member.access == access,
                                        onClick = { model.setShare(member.profile.id, access) },
                                        label = { Text(stringResource(access.label())) }
                                    )
                                }
                                IconButton(onClick = { model.removeShare(member.profile.id) }) {
                                    Icon(
                                        SynaraIcons.Delete.get(),
                                        contentDescription = stringResource(Res.string.playlist_remove_share)
                                    )
                                }
                            }
                        }

                        item(key = "add") { SectionLabel(stringResource(Res.string.playlist_add_people)) }
                    } else {
                        item(key = "transfer") { SectionLabel(stringResource(Res.string.playlist_transfer_summary)) }
                    }

                    item(key = "filter") {
                        OutlinedTextField(
                            value = state.filter,
                            onValueChange = { model.setFilter(it) },
                            placeholder = { Text(stringResource(Res.string.playlist_filter_users)) },
                            leadingIcon = { Icon(SynaraIcons.Search.get(), contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    val selectable = if (choosingOwner) state.others else state.candidates

                    if (state.isLoading) {
                        item(key = "loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (selectable.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(Res.string.playlist_no_users),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    items(selectable, key = { "user-${it.id}" }) { profile ->
                        if (choosingOwner) {
                            UserRow(profile, onClick = { newOwner = profile })
                        } else {
                            UserRow(profile, onClick = { model.setShare(profile.id, PlaylistAccess.READ) }) {
                                Icon(
                                    SynaraIcons.Add.get(),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { choosingOwner = !choosingOwner }) {
                Text(stringResource(if (choosingOwner) Res.string.back else Res.string.playlist_transfer))
            }
        }
    )

    newOwner?.let { owner ->
        SynaraAlertDialog(
            isOpen = true,
            onDismissRequest = { newOwner = null },
            title = { Text(stringResource(Res.string.playlist_transfer)) },
            text = {
                Text(stringResource(Res.string.playlist_transfer_confirm, owner.shownName(), playlistName))
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val transferred = model.transferOwnership(owner.id)
                            newOwner = null
                            if (transferred) onDismissRequest()
                        }
                    }
                ) {
                    Text(stringResource(Res.string.playlist_transfer))
                }
            },
            dismissButton = {
                TextButton(onClick = { newOwner = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }
}

@Composable
fun LeavePlaylistDialog(
    isOpen: Boolean,
    playlistId: PlatformUUID,
    playlistName: String,
    onLeft: () -> Unit,
    onDismissRequest: () -> Unit
) {
    if (!isOpen) return

    val model = rememberPlaylistShareModel(playlistId)
    val scope = rememberCoroutineScope()

    SynaraAlertDialog(
        isOpen = true,
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Res.string.playlist_leave)) },
        text = { Text(stringResource(Res.string.playlist_leave_confirm, playlistName)) },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        if (model.leave()) onLeft()
                        onDismissRequest()
                    }
                }
            ) {
                Text(stringResource(Res.string.playlist_leave))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}

private fun PlaylistAccess.label() = when (this) {
    PlaylistAccess.READ -> Res.string.playlist_access_read
    PlaylistAccess.WRITE -> Res.string.playlist_access_write
}

private fun UserProfile.shownName(): String = displayName?.takeIf { it.isNotBlank() } ?: username

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun UserRow(
    profile: UserProfile,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SynaraImage(
                imageId = profile.profileImageId,
                size = 40.dp,
                shape = CircleShape,
                fallbackIcon = SynaraIcons.Artists
            )
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    text = profile.shownName(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = profile.username,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            trailing()
        }
    }
}
