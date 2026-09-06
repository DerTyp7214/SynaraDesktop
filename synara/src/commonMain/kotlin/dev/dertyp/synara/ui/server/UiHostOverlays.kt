package dev.dertyp.synara.ui.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.dertyp.data.UserSong
import dev.dertyp.services.ISongService
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.dialogs.SongInfoDialog
import dev.dertyp.synara.ui.components.dialogs.SynaraAlertDialog
import dev.dertyp.synara.ui.components.dialogs.SynaraDialog
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.cancel
import synara.synara.generated.resources.ui_server_confirm
import synara.synara.generated.resources.ui_server_choose_handler

@Composable
fun UiHostOverlays(host: DefaultUiHost) {
    val confirm = host.pendingConfirm
    SynaraAlertDialog(
        isOpen = confirm != null,
        onDismissRequest = { host.pendingConfirm = null },
        title = null,
        text = { Text(confirm?.text.orEmpty()) },
        confirmButton = {
            TextButton(onClick = {
                host.pendingConfirm = null
                confirm?.onConfirm?.invoke()
            }) {
                Text(stringResource(Res.string.ui_server_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { host.pendingConfirm = null }) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )

    val choice = host.pendingChoice
    SynaraAlertDialog(
        isOpen = choice != null,
        onDismissRequest = { host.pendingChoice = null },
        title = { Text(stringResource(Res.string.ui_server_choose_handler)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                choice?.handlers?.forEach { handler ->
                    key(handler.id) {
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable {
                                host.pendingChoice = null
                                host.dispatch(handler.action.withConfirmText(handler.confirmText))
                            },
                            headlineContent = { Text(handler.title) },
                            supportingContent = handler.description?.let { { Text(it) } },
                            leadingContent = handler.icon?.let { { UiIconView(it) } },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { host.pendingChoice = null }) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )

    val modalPage = host.pendingModalPage
    SynaraDialog(
        isOpen = modalPage != null,
        onDismissRequest = { host.pendingModalPage = null },
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 720.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { host.pendingModalPage = null }) {
                        Icon(SynaraIcons.Close.get(), contentDescription = null)
                    }
                }
                Box(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                    modalPage?.let {
                        UiContributionHost(
                            contributionId = it.pageId,
                            context = UiContext(params = it.params),
                        )
                    }
                }
            }
        }
    }

    UiSongDetailOverlay(host)
}

@Composable
private fun UiSongDetailOverlay(host: DefaultUiHost) {
    val songService = koinInject<ISongService>()
    val songId = host.pendingSong
    var song by remember { mutableStateOf<UserSong?>(null) }

    LaunchedEffect(songId) {
        song = null
        val id = songId ?: return@LaunchedEffect
        song = runCatching { songService.byId(id) }.getOrNull()
        if (song == null) host.pendingSong = null
    }

    val loaded = song
    if (loaded != null) {
        SongInfoDialog(
            isOpen = true,
            song = loaded,
            onDismissRequest = { host.pendingSong = null },
        )
    }
}

internal fun UiAction.withConfirmText(confirmText: String?): UiAction {
    if (confirmText.isNullOrBlank()) return this
    return when (this) {
        is UiAction.Invoke -> if (this.confirmText == null) copy(confirmText = confirmText) else this
        is UiAction.Intake -> if (this.confirmText == null) copy(confirmText = confirmText) else this
        else -> this
    }
}
