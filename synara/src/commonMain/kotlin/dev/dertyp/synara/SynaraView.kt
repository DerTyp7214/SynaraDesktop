package dev.dertyp.synara

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.screens.HomeScreen
import dev.dertyp.synara.screens.LoginScreen
import dev.dertyp.synara.screens.SetupScreen
import dev.dertyp.synara.ui.models.TrayState
import org.koin.compose.koinInject

@Composable
fun SynaraView() {
    val rpcServiceManager = koinInject<RpcServiceManager>()
    val connectionState by rpcServiceManager.connectionState.collectAsState()
    val language by Config.language.collectAsState()

    LaunchedEffect(language) {
        customAppLocale = language
    }

    val trayState = koinInject<TrayState>()

    LaunchedEffect(Unit) {
        trayState.setBadgeColor(Color.Green)
    }

    AppEnvironment {
        Navigator(CircularLoadingScreen()) { navigator ->
            LaunchedEffect(connectionState) {
                val targetScreen = when (connectionState) {
                    RpcServiceManager.ConnectionState.Loading -> return@LaunchedEffect
                    RpcServiceManager.ConnectionState.SetupRequired -> SetupScreen()
                    RpcServiceManager.ConnectionState.LoginRequired -> LoginScreen()
                    RpcServiceManager.ConnectionState.Authenticated -> HomeScreen()
                }

                if (navigator.lastItem::class != targetScreen::class) {
                    navigator.replaceAll(targetScreen)
                }
            }

            SlideTransition(navigator)
        }
    }
}

private class CircularLoadingScreen : Screen {
    @Composable
    override fun Content() {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
