package dev.dertyp.synara.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.db.MigrationBase
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

const val LOCAL_DATA_TOO_OLD_SCREEN_TAG = "localDataTooOldScreen"
const val LOCAL_DATA_TOO_OLD_ARCH_COMMANDS_TAG = "localDataTooOldArchCommands"
const val LOCAL_DATA_TOO_OLD_COPY_TAG = "localDataTooOldCopy"
const val LOCAL_DATA_TOO_OLD_RELEASE_TAG = "localDataTooOldRelease"

val steppingStoneArchCommands: String =
    "[synara]\nServer = ${MigrationBase.STEPPING_STONE_PACMAN_SERVER}\n\nsudo pacman -Syuu"

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LocalDataTooOldScreen(foundVersion: String?) {
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp)
            .testTag(LOCAL_DATA_TOO_OLD_SCREEN_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(Res.string.outdated_data_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = stringResource(Res.string.outdated_data_body, MigrationBase.STEPPING_STONE),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = stringResource(
                Res.string.outdated_data_version,
                foundVersion ?: stringResource(Res.string.outdated_data_version_unknown),
                MigrationBase.VERSION
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = stringResource(Res.string.outdated_data_arch_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(Res.string.outdated_data_arch_steps, MigrationBase.STEPPING_STONE),
            style = MaterialTheme.typography.bodyMedium
        )
        SelectionContainer {
            Text(
                text = steppingStoneArchCommands,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                    .padding(12.dp)
                    .testTag(LOCAL_DATA_TOO_OLD_ARCH_COMMANDS_TAG)
            )
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(AnnotatedString(steppingStoneArchCommands)))
                    copied = true
                }
            },
            modifier = Modifier.testTag(LOCAL_DATA_TOO_OLD_COPY_TAG)
        ) {
            Text(stringResource(if (copied) Res.string.copied_to_clipboard else Res.string.copy))
        }

        Text(
            text = stringResource(Res.string.outdated_data_other_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(Res.string.outdated_data_other_steps, MigrationBase.STEPPING_STONE),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = { runCatching { uriHandler.openUri(MigrationBase.STEPPING_STONE_RELEASE_URL) } },
            modifier = Modifier.testTag(LOCAL_DATA_TOO_OLD_RELEASE_TAG)
        ) {
            Text(stringResource(Res.string.outdated_data_open_release))
        }
    }
}
