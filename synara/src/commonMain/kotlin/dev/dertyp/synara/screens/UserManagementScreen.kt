package dev.dertyp.synara.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.data.User
import dev.dertyp.data.UserCapability
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.RegisterRefreshTarget
import dev.dertyp.synara.ui.components.SettingsCard
import dev.dertyp.synara.ui.components.SynaraImage
import dev.dertyp.synara.viewmodels.UserManagementScreenModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

class UserManagementScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<UserManagementScreenModel>()
        RegisterRefreshTarget(screenModel)
        val navigator = LocalNavigator.currentOrThrow
        val state by screenModel.state.collectAsState()

        var showCreateDialog by remember { mutableStateOf(false) }
        var editUser by remember { mutableStateOf<User?>(null) }

        val lazyListState = rememberLazyListState()

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(Res.string.user_management),
                            style = MaterialTheme.typography.headlineMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(SynaraIcons.Back.get(), contentDescription = stringResource(Res.string.back))
                        }
                    },
                    actions = {
                        Button(
                            onClick = { showCreateDialog = true },
                            modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Icon(
                                SynaraIcons.Add.get(),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.user_create))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.isLoading && state.users.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    state.error?.let { error ->
                        item {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    items(state.users.size, key = { state.users[it].id.toString() }) { index ->
                        val user = state.users[index]
                        UserCard(
                            user = user,
                            onEditCapabilities = { editUser = user }
                        )
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(lazyListState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }

        if (showCreateDialog) {
            CreateUserDialog(
                screenModel = screenModel,
                onDismissRequest = { showCreateDialog = false }
            )
        }

        editUser?.let { user ->
            EditCapabilitiesDialog(
                user = user,
                onSave = { capabilities ->
                    screenModel.setCapabilities(user.id, capabilities)
                    editUser = null
                },
                onDismissRequest = { editUser = null }
            )
        }
    }

    @Composable
    private fun UserCard(user: User, onEditCapabilities: () -> Unit) {
        SettingsCard(onClick = if (user.isAdmin) null else onEditCapabilities) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SynaraImage(
                    imageId = user.profileImageId,
                    size = 48.dp,
                    shape = CircleShape,
                    fallbackIcon = SynaraIcons.Artists
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = user.displayName?.takeIf { it.isNotBlank() } ?: user.username,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (user.isAdmin) {
                            Text(
                                text = stringResource(Res.string.user_is_admin),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.shapes.extraSmall
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = user.username,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!user.isAdmin) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            UserCapability.entries.forEach { capability ->
                                val granted = capability in user.capabilities
                                Text(
                                    text = capability.label(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (granted) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .background(
                                            if (granted) MaterialTheme.colorScheme.secondaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            MaterialTheme.shapes.extraSmall
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
                if (!user.isAdmin) {
                    IconButton(onClick = onEditCapabilities) {
                        Icon(
                            SynaraIcons.Edit.get(),
                            contentDescription = stringResource(Res.string.user_edit_capabilities)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun UserCapability.label(): String = when (this) {
        UserCapability.IMPORT -> stringResource(Res.string.capability_import)
        UserCapability.EDIT -> stringResource(Res.string.capability_edit)
        UserCapability.DELETE -> stringResource(Res.string.capability_delete)
        UserCapability.PODCAST_EDIT -> stringResource(Res.string.capability_podcast_edit)
    }

    @Composable
    private fun EditCapabilitiesDialog(
        user: User,
        onSave: (List<UserCapability>) -> Unit,
        onDismissRequest: () -> Unit
    ) {
        val selected = remember { mutableStateListOf<UserCapability>().apply { addAll(user.capabilities) } }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text("${stringResource(Res.string.user_edit_capabilities)} · ${user.username}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    UserCapability.entries.forEach { capability ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(capability.label(), style = MaterialTheme.typography.bodyLarge)
                            Checkbox(
                                checked = capability in selected,
                                onCheckedChange = { checked ->
                                    if (checked) selected.add(capability) else selected.remove(capability)
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onSave(selected.toList()) }) {
                    Text(stringResource(Res.string.save))
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
    private fun CreateUserDialog(
        screenModel: UserManagementScreenModel,
        onDismissRequest: () -> Unit
    ) {
        val state by screenModel.state.collectAsState()
        val scope = rememberCoroutineScope()

        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var isAdmin by remember { mutableStateOf(false) }
        val capabilities = remember { mutableStateListOf<UserCapability>() }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(Res.string.user_create)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InternalTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(Res.string.user_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    InternalTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(Res.string.user_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(Res.string.user_is_admin), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = isAdmin, onCheckedChange = { isAdmin = it })
                    }
                    if (!isAdmin) {
                        Text(
                            text = stringResource(Res.string.user_capabilities),
                            style = MaterialTheme.typography.labelLarge
                        )
                        UserCapability.entries.forEach { capability ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(capability.label(), style = MaterialTheme.typography.bodyLarge)
                                Checkbox(
                                    checked = capability in capabilities,
                                    onCheckedChange = { checked ->
                                        if (checked) capabilities.add(capability) else capabilities.remove(capability)
                                    }
                                )
                            }
                        }
                    }
                    state.createError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val success = screenModel.createUser(
                                username = username,
                                password = password,
                                isAdmin = isAdmin,
                                capabilities = if (isAdmin) emptyList() else capabilities.toList()
                            )
                            if (success) onDismissRequest()
                        }
                    },
                    enabled = username.isNotBlank() && password.isNotBlank()
                ) {
                    Text(stringResource(Res.string.user_create))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }
}
