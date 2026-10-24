package dev.dertyp.synara.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.dertyp.synara.AppEnvironment
import dev.dertyp.synara.Config
import dev.dertyp.synara.customAppLocale
import dev.dertyp.synara.db.DatabaseNotAtBaseException
import dev.dertyp.synara.screens.LocalDataTooOldScreen
import dev.dertyp.synara.theme.SynaraTheme
import org.jetbrains.compose.resources.painterResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.icon
import kotlin.system.exitProcess

fun showLocalDataTooOld(refusal: DatabaseNotAtBaseException): Nothing {
    application {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(width = 760.dp, height = 680.dp),
            title = "Synara",
            icon = painterResource(Res.drawable.icon)
        ) {
            val language by Config.language.collectAsState()
            LaunchedEffect(language) {
                customAppLocale = language
            }
            AppEnvironment {
                SynaraTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        LocalDataTooOldScreen(refusal.foundVersion)
                    }
                }
            }
        }
    }
    exitProcess(0)
}
