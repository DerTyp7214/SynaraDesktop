package dev.dertyp.synara.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.dertyp.synara.Config
import dev.dertyp.synara.InternalTextField
import dev.dertyp.synara.sync.SecretsLockState
import dev.dertyp.synara.sync.SettingsSyncService
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.components.PassphraseMode
import dev.dertyp.synara.ui.components.SettingsSyncDialogs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*
import kotlin.time.Duration.Companion.seconds

private val SECRETS_STATE_TIMEOUT = 30.seconds

@Composable
fun SyncSetupStep(
    onFinish: (followUp: (suspend () -> Unit)?) -> Unit,
    settingsSync: SettingsSyncService = koinInject()
) {
    var deviceName by remember { mutableStateOf(Config.queueSyncDeviceName.value) }
    var enableQueue by remember { mutableStateOf(true) }
    var enableSettings by remember { mutableStateOf(true) }
    var enableSecrets by remember { mutableStateOf(false) }

    OnboardingDialog(
        icon = SynaraIcons.Sync,
        title = stringResource(Res.string.sync_setup_title),
        message = stringResource(Res.string.sync_setup_description),
        continueLabel = stringResource(Res.string.sync_setup_enable),
        notNowLabel = stringResource(Res.string.sync_setup_not_now),
        canContinue = enableQueue || enableSettings,
        onContinue = {
            val secrets = enableSettings && enableSecrets
            Config.setQueueSyncDeviceName(deviceName.trim())
            Config.setIsQueueSyncEnabled(enableQueue)
            Config.setIsSettingsSyncEnabled(enableSettings)
            Config.setIsSecretsSyncEnabled(secrets)
            onFinish(if (secrets) ({ requestSecretsPassphrase(settingsSync) }) else null)
        },
        onNotNow = { onFinish(null) },
        options = listOf(
            OnboardingOption(
                icon = SynaraIcons.Queue,
                title = stringResource(Res.string.settings_queue_sync_title),
                summary = stringResource(Res.string.settings_queue_sync_summary),
                checked = enableQueue,
                onCheckedChange = { enableQueue = it }
            ),
            OnboardingOption(
                icon = SynaraIcons.Settings,
                title = stringResource(Res.string.settings_settings_sync_title),
                summary = stringResource(Res.string.settings_settings_sync_summary),
                checked = enableSettings,
                onCheckedChange = {
                    enableSettings = it
                    if (!it) enableSecrets = false
                }
            ),
            OnboardingOption(
                icon = SynaraIcons.Key,
                title = stringResource(Res.string.secrets_sync_title),
                summary = stringResource(Res.string.secrets_sync_summary),
                checked = enableSecrets,
                enabled = enableSettings,
                onCheckedChange = { enableSecrets = it }
            )
        )
    ) {
        InternalTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text(stringResource(Res.string.settings_sync_device_name_title)) },
            placeholder = { Text(settingsSync.platformDeviceName) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private suspend fun requestSecretsPassphrase(settingsSync: SettingsSyncService) {
    val state = withTimeoutOrNull(SECRETS_STATE_TIMEOUT) {
        settingsSync.secretsLockState.first {
            it == SecretsLockState.NEEDS_SETUP ||
                it == SecretsLockState.NEEDS_PASSPHRASE ||
                it == SecretsLockState.UNLOCKED
        }
    } ?: return
    val mode = when (state) {
        SecretsLockState.NEEDS_SETUP -> PassphraseMode.Setup
        SecretsLockState.NEEDS_PASSPHRASE -> PassphraseMode.Enter
        else -> return
    }
    SettingsSyncDialogs.requestPassphrase(mode)
    SettingsSyncDialogs.passphraseRequest.first { it == null }
}
