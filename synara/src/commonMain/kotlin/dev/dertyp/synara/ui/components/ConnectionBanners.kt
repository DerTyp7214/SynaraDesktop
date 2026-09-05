package dev.dertyp.synara.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.ui.SynaraIcons
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.connection_banner_dismiss
import synara.synara.generated.resources.connection_offline_banner
import synara.synara.generated.resources.connection_retry_secure
import synara.synara.generated.resources.connection_ssl_override_banner

@Composable
fun ConnectionBanners(
    rpcServiceManager: RpcServiceManager,
    modifier: Modifier = Modifier
) {
    val isServerReachable by rpcServiceManager.isServerReachable.collectAsState()
    val sessionSslOverride by rpcServiceManager.sessionSslOverride.collectAsState()
    var sslWarningDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(sessionSslOverride) {
        if (sessionSslOverride == false) {
            sslWarningDismissed = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!isServerReachable) {
            OfflineBanner()
        }
        if (sessionSslOverride == false && !sslWarningDismissed) {
            SslOverrideBanner(
                onRetry = {
                    rpcServiceManager.resetSslSession()
                    rpcServiceManager.retryConnection()
                },
                onDismiss = { sslWarningDismissed = true }
            )
        }
    }
}

@Composable
private fun OfflineBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = SynaraIcons.ErrorCircle.get(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(Res.string.connection_offline_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SslOverrideBanner(
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.connection_ssl_override_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(Res.string.connection_retry_secure))
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = SynaraIcons.Close.get(),
                    contentDescription = stringResource(Res.string.connection_banner_dismiss),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}
