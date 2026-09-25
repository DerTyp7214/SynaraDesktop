package dev.dertyp.synara.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.compose.koinInject

@Composable
fun OnboardingHost(coordinator: OnboardingCoordinator = koinInject()) {
    LaunchedEffect(coordinator) {
        coordinator.run()
    }

    val currentStep by coordinator.currentStep.collectAsState()
    when (currentStep) {
        OnboardingStepId.SyncSetup -> SyncSetupStep(onFinish = { coordinator.finish(it) })
        OnboardingStepId.RemoteControl -> RemoteControlStep(onFinish = { coordinator.finish() })
        OnboardingStepId.Features -> FeaturesStep(onFinish = { coordinator.finish() })
        null -> Unit
    }
}
