package dev.dertyp.synara.onboarding

import dev.dertyp.synara.Config
import dev.dertyp.synara.rpc.RpcServiceManager
import dev.dertyp.synara.rpc.RpcServiceManager.ConnectionState
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import dev.dertyp.synara.viewmodels.GlobalStateModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingCoordinatorTest {

    private val connection = MutableStateFlow(ConnectionState.Loading)
    private val dialogs = MutableStateFlow(0)
    private val menus = MutableStateFlow(0)

    private lateinit var coordinator: OnboardingCoordinator

    @BeforeTest
    fun setUp() {
        startTestKoin()
        configure()
        val rpcServiceManager = mockk<RpcServiceManager> { every { connectionState } returns connection }
        val globalState = mockk<GlobalStateModel> {
            every { openDialogsCount } returns dialogs
            every { openMenusCount } returns menus
        }
        coordinator = OnboardingCoordinator(rpcServiceManager, globalState)
    }

    @AfterTest
    fun tearDown() {
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    private fun configure(
        syncSetupShown: Boolean = false,
        remoteControlShown: Boolean = false,
        featuresShown: Boolean = false,
        queueSync: Boolean = false,
        settingsSync: Boolean = false,
        podcasts: Boolean = false
    ) {
        Config.setIsRemoteControlEnabled(false)
        Config.setIsQueueSyncEnabled(queueSync)
        Config.setIsSettingsSyncEnabled(settingsSync)
        Config.setPodcastsEnabled(podcasts)
        Config.setSyncSetupShown(syncSetupShown)
        Config.setRemoteControlOptInShown(remoteControlShown)
        Config.setPodcastOptInShown(featuresShown)
    }

    private fun TestScope.startRun(): Job = backgroundScope.launch { coordinator.run() }

    private fun TestScope.recordSteps(): List<OnboardingStepId?> {
        val seen = mutableListOf<OnboardingStepId?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.currentStep.collect { seen += it }
        }
        return seen
    }

    private fun TestScope.passGap() {
        advanceTimeBy(350)
        runCurrent()
    }

    @Test
    fun nothingIsShownAndTheRunEndsWhenEveryStepWasShownBefore() = runTest {
        configure(syncSetupShown = true, remoteControlShown = true, featuresShown = true)
        connection.value = ConnectionState.Authenticated
        val seen = recordSteps()

        val job = startRun()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(job.isCompleted)
        assertTrue(seen.all { it == null })
        assertNull(coordinator.currentStep.value)
    }

    @Test
    fun theSyncSetupIsMarkedShownWithoutDisplayWhenQueueSyncIsAlreadyEnabled() = runTest {
        configure(remoteControlShown = true, featuresShown = true, queueSync = true)
        val seen = recordSteps()

        val job = startRun()
        runCurrent()

        assertTrue(job.isCompleted)
        assertTrue(Config.syncSetupShown.value)
        assertTrue(seen.all { it == null })
    }

    @Test
    fun theSyncSetupIsMarkedShownWithoutDisplayWhenSettingsSyncIsAlreadyEnabled() = runTest {
        configure(remoteControlShown = true, featuresShown = true, settingsSync = true)
        val seen = recordSteps()

        val job = startRun()
        runCurrent()

        assertTrue(job.isCompleted)
        assertTrue(Config.syncSetupShown.value)
        assertTrue(seen.all { it == null })
    }

    @Test
    fun theFirstStepAppearsOnlyAfterAuthenticationPlusTheGap() = runTest {
        startRun()
        runCurrent()
        assertNull(coordinator.currentStep.value)

        connection.value = ConnectionState.LoginRequired
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(coordinator.currentStep.value)

        connection.value = ConnectionState.Authenticated
        runCurrent()
        assertNull(coordinator.currentStep.value)

        advanceTimeBy(349)
        runCurrent()
        assertNull(coordinator.currentStep.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(OnboardingStepId.SyncSetup, coordinator.currentStep.value)
    }

    @Test
    fun anOpenDialogDelaysTheStepUntilItIsClosed() = runTest {
        connection.value = ConnectionState.Authenticated
        dialogs.value = 1

        startRun()
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(coordinator.currentStep.value)

        dialogs.value = 0
        runCurrent()
        assertNull(coordinator.currentStep.value)
        passGap()
        assertEquals(OnboardingStepId.SyncSetup, coordinator.currentStep.value)
    }

    @Test
    fun anOpenMenuDelaysTheStepUntilItIsClosed() = runTest {
        connection.value = ConnectionState.Authenticated
        menus.value = 1

        startRun()
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(coordinator.currentStep.value)

        menus.value = 0
        runCurrent()
        assertNull(coordinator.currentStep.value)
        passGap()
        assertEquals(OnboardingStepId.SyncSetup, coordinator.currentStep.value)
    }

    @Test
    fun aVisibleChangelogDelaysTheStepUntilItIsHidden() = runTest {
        connection.value = ConnectionState.Authenticated
        coordinator.setChangelogVisible(true)
        assertTrue(coordinator.isChangelogVisible.value)

        startRun()
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(coordinator.currentStep.value)

        coordinator.setChangelogVisible(false)
        runCurrent()
        assertNull(coordinator.currentStep.value)
        passGap()
        assertEquals(OnboardingStepId.SyncSetup, coordinator.currentStep.value)
    }

    @Test
    fun aDialogOpenedDuringTheGapRestartsTheWait() = runTest {
        connection.value = ConnectionState.Authenticated

        startRun()
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        dialogs.value = 1
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(coordinator.currentStep.value)

        dialogs.value = 0
        runCurrent()
        advanceTimeBy(349)
        runCurrent()
        assertNull(coordinator.currentStep.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(OnboardingStepId.SyncSetup, coordinator.currentStep.value)
    }

    @Test
    fun finishingAStepClearsItAndTheNextUnshownStepsFollowInOrder() = runTest {
        connection.value = ConnectionState.Authenticated
        val seen = recordSteps()

        val job = startRun()
        runCurrent()
        passGap()
        assertEquals(OnboardingStepId.SyncSetup, coordinator.currentStep.value)

        coordinator.finish()
        runCurrent()
        assertNull(coordinator.currentStep.value)
        advanceTimeBy(349)
        runCurrent()
        assertNull(coordinator.currentStep.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(OnboardingStepId.RemoteControl, coordinator.currentStep.value)

        coordinator.finish()
        runCurrent()
        assertNull(coordinator.currentStep.value)
        passGap()
        assertEquals(OnboardingStepId.Features, coordinator.currentStep.value)
        assertFalse(job.isCompleted)

        coordinator.finish()
        runCurrent()
        assertNull(coordinator.currentStep.value)
        assertTrue(job.isCompleted)
        assertEquals(
            listOf(OnboardingStepId.SyncSetup, OnboardingStepId.RemoteControl, OnboardingStepId.Features),
            seen.filterNotNull()
        )
    }

    @Test
    fun stepsShownBeforeAreSkipped() = runTest {
        configure(syncSetupShown = true, featuresShown = true)
        connection.value = ConnectionState.Authenticated
        val seen = recordSteps()

        val job = startRun()
        runCurrent()
        passGap()
        assertEquals(OnboardingStepId.RemoteControl, coordinator.currentStep.value)

        coordinator.finish()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals(listOf(OnboardingStepId.RemoteControl), seen.filterNotNull())
    }

    @Test
    fun theShownFlagIsSetBeforeTheStepAppears() = runTest {
        connection.value = ConnectionState.Authenticated
        val flagsAtAppearance = mutableMapOf<OnboardingStepId, Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.currentStep.collect { step ->
                val shown = when (step) {
                    OnboardingStepId.SyncSetup -> Config.syncSetupShown.value
                    OnboardingStepId.RemoteControl -> Config.remoteControlOptInShown.value
                    OnboardingStepId.Features -> Config.podcastOptInShown.value
                    null -> null
                }
                if (step != null && shown != null) flagsAtAppearance[step] = shown
            }
        }

        startRun()
        runCurrent()
        assertFalse(Config.syncSetupShown.value)
        passGap()
        assertFalse(Config.remoteControlOptInShown.value)
        coordinator.finish()
        runCurrent()
        passGap()
        assertFalse(Config.podcastOptInShown.value)
        coordinator.finish()
        runCurrent()
        passGap()

        assertEquals(
            mapOf(
                OnboardingStepId.SyncSetup to true,
                OnboardingStepId.RemoteControl to true,
                OnboardingStepId.Features to true
            ),
            flagsAtAppearance
        )
    }

    @Test
    fun aFollowUpRunsOnceAfterTheStepClosedAndTheAppIsReadyAgain() = runTest {
        configure(featuresShown = true)
        connection.value = ConnectionState.Authenticated
        var followUps = 0

        val job = startRun()
        runCurrent()
        passGap()
        assertEquals(OnboardingStepId.SyncSetup, coordinator.currentStep.value)

        dialogs.value = 1
        coordinator.finish { followUps++ }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, followUps)
        assertNull(coordinator.currentStep.value)

        dialogs.value = 0
        runCurrent()
        advanceTimeBy(349)
        runCurrent()
        assertEquals(0, followUps)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, followUps)
        assertNull(coordinator.currentStep.value)

        passGap()
        assertEquals(OnboardingStepId.RemoteControl, coordinator.currentStep.value)

        coordinator.finish()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(1, followUps)
    }

    @Test
    fun aSecondRunWhileOneIsActiveReturnsAtOnce() = runTest {
        val first = startRun()
        runCurrent()

        val second = startRun()
        runCurrent()

        assertTrue(second.isCompleted)
        assertFalse(first.isCompleted)

        connection.value = ConnectionState.Authenticated
        runCurrent()
        passGap()
        assertEquals(OnboardingStepId.SyncSetup, coordinator.currentStep.value)
        assertFalse(first.isCompleted)
    }

    @Test
    fun cancellingTheRunClearsTheStepAndAllowsANewRun() = runTest {
        connection.value = ConnectionState.Authenticated
        val first = startRun()
        runCurrent()
        passGap()
        assertEquals(OnboardingStepId.SyncSetup, coordinator.currentStep.value)

        first.cancel()
        runCurrent()
        assertTrue(first.isCompleted)
        assertNull(coordinator.currentStep.value)

        val second = startRun()
        runCurrent()
        passGap()
        assertEquals(OnboardingStepId.RemoteControl, coordinator.currentStep.value)
        assertFalse(second.isCompleted)
    }

    @Test
    fun theFeaturesStepIsSkippedAndMarkedShownWhenPodcastsAreAlreadyEnabled() = runTest {
        configure(syncSetupShown = true, remoteControlShown = true, podcasts = true)
        connection.value = ConnectionState.Authenticated
        val seen = recordSteps()

        val job = startRun()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(job.isCompleted)
        assertTrue(Config.podcastOptInShown.value)
        assertTrue(seen.all { it == null })
    }

    @Test
    fun aStepThatBecameUnnecessaryWhileWaitingIsMarkedShownWithoutDisplay() = runTest {
        configure(featuresShown = true)
        val seen = recordSteps()

        startRun()
        runCurrent()
        Config.setIsQueueSyncEnabled(true)
        connection.value = ConnectionState.Authenticated
        runCurrent()
        passGap()

        assertTrue(Config.syncSetupShown.value)
        assertNull(coordinator.currentStep.value)

        passGap()
        assertEquals(OnboardingStepId.RemoteControl, coordinator.currentStep.value)
        assertEquals(listOf(OnboardingStepId.RemoteControl), seen.filterNotNull())
    }
}
