package dev.dertyp.synara.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.dialogs.SynaraDialog
import dev.dertyp.synara.viewmodels.DownloaderScreenModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.back
import synara.synara.generated.resources.cancel
import synara.synara.generated.resources.downloader_login_instructions
import synara.synara.generated.resources.downloader_login_required
import synara.synara.generated.resources.downloader_logs_label
import synara.synara.generated.resources.downloader_title
import synara.synara.generated.resources.downloader_url_label
import synara.synara.generated.resources.login_button
import synara.synara.generated.resources.menu_download
import synara.synara.generated.resources.sync_favorites

class DownloaderScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<DownloaderScreenModel>()
        val navigator = LocalNavigator.current
        val uriHandler = LocalUriHandler.current
        val scope = rememberCoroutineScope()

        val isAuthorized by screenModel.isAuthorized.collectAsState()
        val syncFavAvailable by screenModel.syncFavAvailable.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val logs by screenModel.logs.collectAsState()
        
        var urlToSubmit by remember { mutableStateOf("") }
        val logListState = rememberLazyListState()
        var showNotAuthorizedDialog by remember { mutableStateOf(true) }

        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) {
                logListState.animateScrollToItem(logs.size - 1)
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        SynaraDialog(
            isOpen = showNotAuthorizedDialog && isAuthorized == false,
            onDismissRequest = { navigator?.pop() }
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.widthIn(max = 400.dp).padding(24.dp)
            ) {
                Column {
                    Text(
                        text = stringResource(Res.string.downloader_login_required),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Text(
                        text = stringResource(Res.string.downloader_login_instructions),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            showNotAuthorizedDialog = false
                            navigator?.pop()
                        }) {
                            Text(stringResource(Res.string.cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        var loggingIn by remember { mutableStateOf(false) }
                        Button(
                            enabled = !loggingIn,
                            onClick = {
                                scope.launch {
                                    loggingIn = true
                                    screenModel.downloadLogin().collect { url ->
                                        uriHandler.openUri(url)
                                    }
                                    loggingIn = false
                                    screenModel.checkAuthorization()
                                }
                            }
                        ) {
                            Text(stringResource(Res.string.login_button))
                        }
                    }
                }
            }
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(Res.string.downloader_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(
                                SynaraIcons.Back.get(),
                                contentDescription = stringResource(Res.string.back)
                            )
                        }
                    },
                    actions = {
                        if (syncFavAvailable) {
                            IconButton(onClick = {
                                scope.launch {
                                    if (screenModel.isSyncAuthorized()) {
                                        screenModel.syncFavorites()
                                    } else {
                                        val url = screenModel.getAuthUrl()
                                        uriHandler.openUri(url)
                                    }
                                }
                            }) {
                                Icon(
                                    SynaraIcons.Sync.get(),
                                    contentDescription = stringResource(Res.string.sync_favorites)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                val submit = {
                    if (urlToSubmit.isNotBlank()) {
                        screenModel.submitUrl(urlToSubmit)
                        urlToSubmit = ""
                    }
                }
                InternalTextField(
                    value = urlToSubmit,
                    onValueChange = { urlToSubmit = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onKeyEvent { event ->
                            if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                                submit()
                                true
                            } else false
                        },
                    label = { Text(stringResource(Res.string.downloader_url_label)) },
                    trailingIcon = {
                        IconButton(onClick = { submit() }) {
                            Icon(
                                SynaraIcons.Link.get(),
                                contentDescription = stringResource(Res.string.menu_download)
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    stringResource(Res.string.downloader_logs_label),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(logs) { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
