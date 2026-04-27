package dev.dertyp.synara

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.dertyp.currentTimeMillis
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.screens.HomeScreen
import dev.dertyp.synara.screens.LoginScreen
import dev.dertyp.synara.screens.SetupScreen
import dev.dertyp.synara.screens.TaskManagerScreen
import dev.dertyp.synara.theme.SynaraTheme
import dev.dertyp.synara.ui.DetachedWindow
import dev.dertyp.synara.ui.LocalWindowActions
import dev.dertyp.synara.ui.components.LocalHazeState
import dev.dertyp.synara.ui.components.PerformanceOverlay
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SynaraView() {
    val rpcServiceManager = koinInject<RpcServiceManager>()
    val globalState = koinInject<GlobalStateModel>()
    val connectionState by rpcServiceManager.connectionState.collectAsState()
    val isAnyDialogOpen by globalState.isAnyDialogOpen.collectAsState()
    val isAnyOverlayOpen by globalState.isAnyOverlayOpen.collectAsState()
    val language by Config.language.collectAsState()
    val windowActions = LocalWindowActions.current

    val blur by animateDpAsState(
        targetValue = if (isAnyDialogOpen) 24.dp else 0.dp,
        label = "Blur"
    )

    var lastPointerMoveTime by remember { mutableLongStateOf(currentTimeMillis()) }

    LaunchedEffect(lastPointerMoveTime, windowActions.isFullscreen, isAnyOverlayOpen) {
        if (windowActions.isFullscreen && !isAnyOverlayOpen) {
            windowActions.setCursorVisible(true)
            delay(3.seconds)
            windowActions.setCursorVisible(false)
        } else {
            windowActions.setCursorVisible(true)
        }
    }

    LaunchedEffect(language) {
        customAppLocale = language
    }

    val hazeState = remember { HazeState() }
    val showPerformanceOverlay by Config.showPerformanceOverlay.collectAsState()
    val showTaskManagerWindow by globalState.showTaskManagerWindow.collectAsState()

    DetachedWindow(
        isOpen = showTaskManagerWindow,
        onCloseRequest = { globalState.setShowTaskManagerWindow(false) },
        title = "Synara Task Manager"
    ) {
        AppEnvironment {
            SynaraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Navigator(TaskManagerScreen())
                }
            }
        }
    }

    AppEnvironment {
        CompositionLocalProvider(LocalHazeState provides hazeState) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    .onPointerEvent(PointerEventType.Move) {
                        if (windowActions.isFullscreen) {
                            lastPointerMoveTime = currentTimeMillis()
                        }
                    }
            ) {
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

                    SlideTransition(
                        navigator = navigator,
                        modifier = Modifier.blur(blur)
                    )
                }

                if (showPerformanceOverlay) {
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        PerformanceOverlay()
                    }
                }
            }
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
