package dev.dertyp.synara.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.synara.formatDateTime
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.SettingsCard
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.synara.viewmodels.SubsonicCredentialScreenModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

class SubsonicCredentialScreen : Screen {
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<SubsonicCredentialScreenModel>()
        val navigator = LocalNavigator.currentOrThrow
        val snackbarManager = koinInject<SnackbarManager>()
        val clipboard = LocalClipboard.current
        val state by screenModel.state.collectAsState()
        val scope = rememberCoroutineScope()

        var showRegenerateConfirm by remember { mutableStateOf(false) }
        var showRevokeConfirm by remember { mutableStateOf(false) }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(Res.string.subsonic_credential),
                            style = MaterialTheme.typography.headlineMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(SynaraIcons.Back.get(), contentDescription = stringResource(Res.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 580.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (state.isLoading && state.credential == null) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val credential = state.credential
                        if (credential == null) {
                            Text(
                                text = stringResource(Res.string.subsonic_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = { screenModel.regenerate() }) {
                                Text(stringResource(Res.string.subsonic_generate))
                            }
                        } else {
                            SettingsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CredentialRow(
                                        label = stringResource(Res.string.subsonic_username),
                                        value = credential.username,
                                        onCopy = {
                                            scope.launch {
                                                clipboard.setClipEntry(ClipEntry(AnnotatedString(credential.username)))
                                                snackbarManager.showSnackbar(getString(Res.string.copied_to_clipboard))
                                            }
                                        }
                                    )
                                    CredentialRow(
                                        label = stringResource(Res.string.subsonic_password),
                                        value = credential.password,
                                        onCopy = {
                                            scope.launch {
                                                clipboard.setClipEntry(ClipEntry(AnnotatedString(credential.password)))
                                                snackbarManager.showSnackbar(getString(Res.string.copied_to_clipboard))
                                            }
                                        }
                                    )
                                    Text(
                                        text = credential.createdAt.formatDateTime(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showRegenerateConfirm = true }) {
                                    Text(stringResource(Res.string.subsonic_regenerate))
                                }
                                OutlinedButton(
                                    onClick = { showRevokeConfirm = true },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text(stringResource(Res.string.subsonic_revoke))
                                }
                            }
                        }

                        state.error?.let { error ->
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        if (showRegenerateConfirm) {
            AlertDialog(
                onDismissRequest = { showRegenerateConfirm = false },
                title = { Text(stringResource(Res.string.subsonic_regenerate)) },
                text = { Text(stringResource(Res.string.subsonic_regenerate_confirm)) },
                confirmButton = {
                    Button(onClick = {
                        showRegenerateConfirm = false
                        screenModel.regenerate()
                    }) {
                        Text(stringResource(Res.string.subsonic_regenerate))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRegenerateConfirm = false }) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            )
        }

        if (showRevokeConfirm) {
            AlertDialog(
                onDismissRequest = { showRevokeConfirm = false },
                title = { Text(stringResource(Res.string.subsonic_revoke)) },
                text = { Text(stringResource(Res.string.subsonic_revoke_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        showRevokeConfirm = false
                        screenModel.revoke()
                    }) {
                        Text(
                            stringResource(Res.string.subsonic_revoke),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRevokeConfirm = false }) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            )
        }
    }

    @Composable
    private fun CredentialRow(label: String, value: String, onCopy: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SelectionContainer {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
            IconButton(onClick = onCopy) {
                Icon(SynaraIcons.Link.get(), contentDescription = stringResource(Res.string.copy))
            }
        }
    }
}
