package dev.dertyp.synara.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.viewmodels.SetupScreenModel
import dev.dertyp.synara.viewmodels.TestConnectionResult
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

class SetupScreen : Screen {
    @Composable
    override fun Content() {
        val screenModel = getScreenModel<SetupScreenModel>()
        val testResult by screenModel.testConnectionResult.collectAsState()
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current

        var host by remember { mutableStateOf(screenModel.getHost() ?: "localhost") }
        var port by remember { mutableStateOf(screenModel.getPort()?.toString() ?: "8080") }

        LaunchedEffect(Unit) {
            screenModel.resetTestConnectionResult()
            if (host.isNotEmpty() && port.isNotEmpty()) {
                screenModel.testConnection(host, port.toIntOrNull() ?: 8080)
            }
        }

        Scaffold(
            containerColor = Color.Transparent
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                ElevatedCard(
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(Res.string.connect_to_server),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 32.dp)
                        )

                        InternalTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text(stringResource(Res.string.host)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            )
                        )
                        InternalTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text(stringResource(Res.string.port)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    scope.launch {
                                        screenModel.testConnection(host, port.toIntOrNull() ?: 8080)
                                    }
                                }
                            )
                        )

                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val isNarrow = maxWidth < 360.dp
                            if (isNarrow) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    TestButton(
                                        onClick = {
                                            scope.launch {
                                                screenModel.testConnection(host, port.toIntOrNull() ?: 8080)
                                            }
                                        },
                                        isLoading = testResult is TestConnectionResult.Loading,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = {
                                            screenModel.setServer(host, port.toIntOrNull() ?: 8080)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = testResult is TestConnectionResult.Success
                                    ) {
                                        Text(stringResource(Res.string.next))
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    TestButton(
                                        onClick = {
                                            scope.launch {
                                                screenModel.testConnection(host, port.toIntOrNull() ?: 8080)
                                            }
                                        },
                                        isLoading = testResult is TestConnectionResult.Loading,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            screenModel.setServer(host, port.toIntOrNull() ?: 8080)
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = testResult is TestConnectionResult.Success
                                    ) {
                                        Text(stringResource(Res.string.next))
                                    }
                                }
                            }
                        }

                        if (testResult !is TestConnectionResult.Idle) {
                            Spacer(modifier = Modifier.height(24.dp))

                            val (message, color) = when (val result = testResult) {
                                is TestConnectionResult.Success -> result.message to MaterialTheme.colorScheme.primary
                                is TestConnectionResult.Error -> result.message to MaterialTheme.colorScheme.error
                                else -> "" to MaterialTheme.colorScheme.onBackground
                            }

                            if (message.isNotEmpty()) {
                                Text(
                                    text = message,
                                    color = color,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TestButton(
        onClick: () -> Unit,
        isLoading: Boolean,
        modifier: Modifier = Modifier
    ) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current
                )
            } else {
                Text(stringResource(Res.string.test_connection))
            }
        }
    }
}
