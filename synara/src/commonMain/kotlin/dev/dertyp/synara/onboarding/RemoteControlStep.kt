package dev.dertyp.synara.onboarding

import androidx.compose.runtime.*
import dev.dertyp.synara.Config
import dev.dertyp.synara.ui.SynaraIcons
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

@Composable
fun RemoteControlStep(onFinish: () -> Unit) {
    var enabled by remember { mutableStateOf(Config.isRemoteControlEnabled.value) }

    OnboardingDialog(
        icon = SynaraIcons.RemoteControl,
        title = stringResource(Res.string.onboarding_remote_control_title),
        message = stringResource(Res.string.onboarding_remote_control_description),
        onContinue = {
            if (enabled != Config.isRemoteControlEnabled.value) Config.setIsRemoteControlEnabled(enabled)
            onFinish()
        },
        onNotNow = onFinish,
        options = listOf(
            OnboardingOption(
                icon = SynaraIcons.RemoteControl,
                title = stringResource(Res.string.settings_remote_control_title),
                summary = stringResource(Res.string.settings_remote_control_summary),
                checked = enabled,
                onCheckedChange = { enabled = it }
            )
        )
    )
}
