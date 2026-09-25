package dev.dertyp.synara.onboarding

import dev.dertyp.synara.Config
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Duration.Companion.milliseconds

enum class OnboardingStepId { SyncSetup, RemoteControl, Features }

class OnboardingStep(
    val id: OnboardingStepId,
    val isShown: () -> Boolean,
    val markShown: () -> Unit,
    val shouldShow: () -> Boolean = { true }
)

class OnboardingCoordinator(
    private val rpcServiceManager: RpcServiceManager,
    private val globalState: GlobalStateModel
) {
    private val steps = listOf(
        OnboardingStep(
            id = OnboardingStepId.SyncSetup,
            isShown = { Config.syncSetupShown.value },
            markShown = { Config.setSyncSetupShown(true) },
            shouldShow = { !Config.isQueueSyncEnabled.value && !Config.isSettingsSyncEnabled.value }
        ),
        OnboardingStep(
            id = OnboardingStepId.RemoteControl,
            isShown = { Config.remoteControlOptInShown.value },
            markShown = { Config.setRemoteControlOptInShown(true) }
        ),
        OnboardingStep(
            id = OnboardingStepId.Features,
            isShown = { Config.podcastOptInShown.value },
            markShown = { Config.setPodcastOptInShown(true) },
            shouldShow = { onboardingFeatureOptions().any { !it.isEnabled.value } }
        )
    )

    private val _currentStep = MutableStateFlow<OnboardingStepId?>(null)
    val currentStep: StateFlow<OnboardingStepId?> = _currentStep.asStateFlow()

    private val _isChangelogVisible = MutableStateFlow(false)
    val isChangelogVisible: StateFlow<Boolean> = _isChangelogVisible.asStateFlow()

    private val followUps = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val runMutex = Mutex()

    private val isReady = combine(
        rpcServiceManager.connectionState,
        _isChangelogVisible,
        _currentStep,
        globalState.openDialogsCount,
        globalState.openMenusCount
    ) { connection, changelog, step, dialogs, menus ->
        connection == RpcServiceManager.ConnectionState.Authenticated &&
            !changelog && step == null && dialogs == 0 && menus == 0
    }

    fun setChangelogVisible(visible: Boolean) {
        _isChangelogVisible.value = visible
    }

    fun finish(followUp: (suspend () -> Unit)? = null) {
        if (followUp != null) followUps.trySend(followUp)
        _currentStep.value = null
    }

    suspend fun run() {
        if (!runMutex.tryLock()) return
        try {
            runFollowUps()
            for (step in steps) {
                if (step.isShown()) continue
                if (!step.shouldShow()) {
                    step.markShown()
                    continue
                }
                awaitReady()
                if (step.isShown()) continue
                step.markShown()
                if (!step.shouldShow()) continue
                _currentStep.value = step.id
                _currentStep.first { it == null }
                runFollowUps()
            }
        } finally {
            _currentStep.value = null
            runMutex.unlock()
        }
    }

    private suspend fun runFollowUps() {
        while (true) {
            val followUp = followUps.tryReceive().getOrNull() ?: return
            awaitReady()
            followUp()
        }
    }

    private suspend fun awaitReady() {
        while (true) {
            isReady.first { it }
            delay(STEP_GAP)
            if (isReady.first()) return
        }
    }

    private companion object {
        val STEP_GAP = 350.milliseconds
    }
}
