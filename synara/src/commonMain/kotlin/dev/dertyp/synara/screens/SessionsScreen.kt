@file:OptIn(ExperimentalMaterial3Api::class)

package dev.dertyp.synara.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.data.Session
import dev.dertyp.synara.formatDateTime
import dev.dertyp.synara.ui.components.dialogs.SynaraAlertDialog
import dev.dertyp.synara.viewmodels.SessionsScreenModel
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

class SessionsScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = getScreenModel<SessionsScreenModel>()
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.current

        var sessionToDelete by remember { mutableStateOf<Session?>(null) }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    title = { Text(stringResource(Res.string.sessions)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (state.isLoading && state.sessions.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    val (ownSessions, otherSessions) = state.sessions.partition { it.id == screenModel.currentSessionId }
                    val (activeSessions, inactiveSessions) = otherSessions.partition { it.isActive }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        if (ownSessions.isNotEmpty()) {
                            item {
                                SectionHeader(stringResource(Res.string.own_session))
                            }
                            items(ownSessions, key = { it.id.toString() }) { session ->
                                SessionItem(
                                    session = session,
                                    isCurrent = true,
                                    onTransfer = { screenModel.transferQueue(session.id) },
                                    onDelete = { sessionToDelete = session }
                                )
                            }
                        }

                        if (activeSessions.isNotEmpty()) {
                            item {
                                SectionHeader(stringResource(Res.string.active_sessions))
                            }
                            items(activeSessions, key = { it.id.toString() }) { session ->
                                SessionItem(
                                    session = session,
                                    isCurrent = false,
                                    onTransfer = { screenModel.transferQueue(session.id) },
                                    onDelete = { sessionToDelete = session }
                                )
                            }
                        }

                        if (inactiveSessions.isNotEmpty()) {
                            item {
                                SectionHeader(stringResource(Res.string.inactive_sessions))
                            }
                            items(inactiveSessions, key = { it.id.toString() }) { session ->
                                SessionItem(
                                    session = session,
                                    isCurrent = false,
                                    onTransfer = { screenModel.transferQueue(session.id) },
                                    onDelete = { sessionToDelete = session }
                                )
                            }
                        }
                    }
                }
            }

            SynaraAlertDialog(
                isOpen = sessionToDelete != null,
                onDismissRequest = { sessionToDelete = null },
                title = { Text(stringResource(Res.string.deactivate_session)) },
                text = { Text(stringResource(Res.string.deactivate_session_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            sessionToDelete?.let { screenModel.deactivateSession(it.id) }
                            sessionToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(Res.string.deactivate_session))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { sessionToDelete = null }) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            )
        }
    }

    @Composable
    private fun SectionHeader(title: String) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }

    @Composable
    private fun SessionItem(
        session: Session,
        isCurrent: Boolean,
        onTransfer: () -> Unit,
        onDelete: () -> Unit
    ) {
        val isMobile = session.userAgent.contains("Android", ignoreCase = true) ||
                session.userAgent.contains("iPhone", ignoreCase = true)
        val isDesktop = session.userAgent.contains("Electron", ignoreCase = true) ||
                session.userAgent.contains("Windows", ignoreCase = true) ||
                session.userAgent.contains("Macintosh", ignoreCase = true) ||
                (session.userAgent.contains("Linux", ignoreCase = true) && !session.userAgent.contains("Android", ignoreCase = true))

        ListItem(
            modifier = Modifier.fillMaxWidth(),
            headlineContent = {
                Text(
                    text = session.userAgent,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (session.isActive) MaterialTheme.colorScheme.onSurface 
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            },
            supportingContent = {
                Column {
                    Text(
                        text = stringResource(Res.string.last_active, session.lastActive.formatDateTime()),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (session.isActive) MaterialTheme.colorScheme.onSurfaceVariant 
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                    Text(
                        text = session.ipAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (session.isActive) MaterialTheme.colorScheme.onSurfaceVariant 
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
            },
            leadingContent = {
                Icon(
                    imageVector = when {
                        isDesktop -> Icons.Rounded.DesktopWindows
                        isMobile -> Icons.Rounded.Smartphone
                        else -> Icons.Rounded.Devices
                    },
                    contentDescription = null,
                    tint = if (session.isActive) MaterialTheme.colorScheme.primary 
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            },
            trailingContent = {
                if (isCurrent) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = stringResource(Res.string.own_session),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else if (session.isActive) {
                    if (isMobile) {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(Res.string.more_options))
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.transfer_queue)) },
                                    onClick = {
                                        expanded = false
                                        onTransfer()
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.CloudUpload, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.deactivate_session)) },
                                    onClick = {
                                        expanded = false
                                        onDelete()
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) }
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(Res.string.deactivate_session))
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
