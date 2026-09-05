package dev.dertyp.synara.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import dev.dertyp.data.ApiVersion
import dev.dertyp.synara.rpc.RpcServiceManager
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.settings_connection_state_plaintext
import synara.synara.generated.resources.settings_connection_state_plaintext_override
import synara.synara.generated.resources.settings_connection_state_secure
import synara.synara.generated.resources.settings_server_api_version
import synara.synara.generated.resources.settings_server_ui_schema_version
import synara.synara.generated.resources.settings_value_pending

@Composable
fun ServerVersionInfo(rpcServiceManager: RpcServiceManager) {
    val handshake by rpcServiceManager.handshake.collectAsState()
    val sessionSslOverride by rpcServiceManager.sessionSslOverride.collectAsState()

    LaunchedEffect(Unit) {
        if (handshake == null) {
            rpcServiceManager.fetchHandshake()
        }
    }

    val pending = stringResource(Res.string.settings_value_pending)

    SettingsCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(
                    Res.string.settings_server_api_version,
                    handshake?.apiVersion?.toString() ?: pending,
                    ApiVersion.CURRENT.toString()
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Res.string.settings_server_ui_schema_version,
                    handshake?.uiSchemaVersion?.toString() ?: pending
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    when {
                        sessionSslOverride == false -> Res.string.settings_connection_state_plaintext_override
                        handshake?.secure == true -> Res.string.settings_connection_state_secure
                        else -> Res.string.settings_connection_state_plaintext
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
