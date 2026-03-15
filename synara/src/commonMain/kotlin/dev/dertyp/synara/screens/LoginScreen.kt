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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.viewmodels.LoginResult
import dev.dertyp.synara.viewmodels.LoginScreenModel
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

class LoginScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = getScreenModel<LoginScreenModel>()
        val loginResult by screenModel.loginResult.collectAsState()
        val focusManager = LocalFocusManager.current

        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            screenModel.reset()
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
                        Box(modifier = Modifier.fillMaxWidth()) {
                            IconButton(
                                onClick = { screenModel.clearServer() },
                                modifier = Modifier.align(Alignment.TopStart)
                            ) {
                                Icon(
                                    imageVector = SynaraIcons.ArrowBack.get(),
                                    contentDescription = stringResource(Res.string.back)
                                )
                            }
                        }

                        Text(
                            text = stringResource(Res.string.login),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 32.dp)
                        )

                        InternalTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(stringResource(Res.string.username)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            )
                        )
                        InternalTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(Res.string.password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = { screenModel.login(username, password) }
                            )
                        )

                        Button(
                            onClick = { screenModel.login(username, password) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = loginResult !is LoginResult.Loading
                        ) {
                            if (loginResult is LoginResult.Loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = LocalContentColor.current
                                )
                            } else {
                                Text(stringResource(Res.string.login))
                            }
                        }

                        if (loginResult is LoginResult.Error) {
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = (loginResult as LoginResult.Error).message,
                                color = MaterialTheme.colorScheme.error,
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
