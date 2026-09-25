package dev.dertyp.synara.onboarding

import androidx.compose.runtime.*
import dev.dertyp.synara.Config
import dev.dertyp.synara.ui.SynaraIcons
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

class FeatureOption(
    val key: String,
    val icon: SynaraIcons,
    val title: StringResource,
    val summary: StringResource,
    val isEnabled: StateFlow<Boolean>,
    val setEnabled: (Boolean) -> Unit
)

fun onboardingFeatureOptions(): List<FeatureOption> = listOf(
    FeatureOption(
        key = "podcasts",
        icon = SynaraIcons.Podcast,
        title = Res.string.onboarding_podcasts_title,
        summary = Res.string.onboarding_podcasts_summary,
        isEnabled = Config.isPodcastsEnabled,
        setEnabled = Config::setPodcastsEnabled
    )
)

@Composable
fun FeaturesStep(onFinish: () -> Unit) {
    val features = remember { onboardingFeatureOptions() }
    val selection = remember { mutableStateMapOf<String, Boolean>().apply { features.forEach { put(it.key, it.isEnabled.value) } } }

    OnboardingDialog(
        icon = SynaraIcons.Onboarding,
        title = stringResource(Res.string.onboarding_features_title),
        message = stringResource(Res.string.onboarding_features_description),
        onContinue = {
            features.forEach { feature ->
                val enabled = selection[feature.key] ?: return@forEach
                if (enabled != feature.isEnabled.value) feature.setEnabled(enabled)
            }
            onFinish()
        },
        onNotNow = onFinish,
        options = features.map { feature ->
            OnboardingOption(
                icon = feature.icon,
                title = stringResource(feature.title),
                summary = stringResource(feature.summary),
                checked = selection[feature.key] ?: false,
                onCheckedChange = { selection[feature.key] = it }
            )
        }
    )
}
