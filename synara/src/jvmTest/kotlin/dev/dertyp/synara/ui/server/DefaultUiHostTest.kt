package dev.dertyp.synara.ui.server

import cafe.adriel.voyager.core.screen.Screen
import dev.dertyp.logging.Logger
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IUiService
import dev.dertyp.synara.screens.AlbumScreen
import dev.dertyp.synara.screens.ArtistScreen
import dev.dertyp.synara.screens.PlaylistScreen
import dev.dertyp.synara.screens.SearchScreen
import dev.dertyp.synara.ui.models.SnackbarManager
import dev.dertyp.ui.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultUiHostTest {

    private val testScope = TestScope()
    private val uiService = mockk<IUiService>()
    private val snackbar = mockk<SnackbarManager>(relaxed = true)
    private val logger = mockk<Logger>(relaxed = true)
    private val context = UiContext(entityType = UiEntityType.ALBUM, entityId = randomPlatformUUID(), params = mapOf("k" to "v"))

    private val navigated = mutableListOf<Screen>()
    private val events = mutableListOf<String>()
    private val urls = mutableListOf<String>()
    private val queries = mutableListOf<String>()
    private val fieldErrors = mutableListOf<Map<String, String>>()
    private var refreshes = 0

    private fun host(portals: UiPortalRegistry = UiPortalRegistry.Empty) = DefaultUiHost(
        contributionId = "contribution",
        context = context,
        scope = testScope,
        uiService = uiService,
        snackbarManager = snackbar,
        logger = logger,
        portals = portals,
        onRefresh = { refreshes++ },
        onNavigate = {
            navigated.add(it)
            events.add("navigate")
        },
        onOpenUrl = { urls.add(it) },
        onSearchQuery = {
            queries.add(it)
            events.add("query:$it")
        },
    )

    private fun binding(formId: String = "form", values: Map<String, UiValue> = emptyMap()) = UiFormBinding(
        formId = formId,
        values = { values },
        onFieldErrors = { fieldErrors.add(it) },
        onBusy = { events.add("busy:$it") },
    )

    private fun answerInvoke(result: UiInvokeResult = UiInvokeResult(UiInvokeStatus.OK)) {
        coEvery { uiService.invoke(any(), any(), any()) } returns result
    }

    private fun answerIntake(result: UiIntakeResult = UiIntakeResult(UiIntakeStatus.OK)) {
        coEvery { uiService.intake(any(), any()) } returns result
    }

    private fun signalOnSnackbar(): CompletableDeferred<Unit> {
        val shown = CompletableDeferred<Unit>()
        every { snackbar.showSnackbar(any<String>(), any(), any(), any()) } answers {
            shown.complete(Unit)
            Unit
        }
        return shown
    }

    private fun verifyShown(text: String, count: Int = 1) =
        verify(exactly = count) { snackbar.showSnackbar(text, any(), any(), any()) }

    private fun verifyNoSnackbar() =
        verify(exactly = 0) { snackbar.showSnackbar(any<String>(), any(), any(), any()) }

    private fun handler(id: String) = UiHookHandler(
        id = id,
        contributionId = "c-$id",
        source = "server",
        title = "title $id",
        action = UiAction.Refresh,
    )

    @Test
    fun invokeWithoutConfirmTextSendsActionParamsMergedWithFormValuesOverridingEqualKeys() = testScope.runTest {
        answerInvoke()
        val action = UiAction.Invoke(
            contributionId = "c",
            actionId = "a",
            params = mapOf("p" to UiValue.of("1"), "k" to UiValue.of("old")),
            formId = "form",
        )
        host().dispatch(action, binding(values = mapOf("k" to UiValue.of("new"), "f" to UiValue.of(3))))
        runCurrent()

        val expected = UiInvokePayload(
            values = mapOf("p" to UiValue.of("1"), "k" to UiValue.of("new"), "f" to UiValue.of(3)),
            context = context,
        )
        coVerify(exactly = 1) { uiService.invoke("c", "a", expected) }
    }

    @Test
    fun invokeWithBlankConfirmTextIsSentWithoutAskingFirst() = testScope.runTest {
        answerInvoke()
        val host = host()
        host.dispatch(UiAction.Invoke("c", "a", confirmText = "   "))
        runCurrent()

        assertNull(host.pendingConfirm)
        coVerify(exactly = 1) { uiService.invoke("c", "a", any()) }
    }

    @Test
    fun invokeWithConfirmTextSendsNothingUntilConfirmed() = testScope.runTest {
        answerInvoke()
        val host = host()
        host.dispatch(UiAction.Invoke("c", "a", params = mapOf("p" to UiValue.of("1")), confirmText = "Really?"))
        runCurrent()

        assertEquals("Really?", host.pendingConfirm?.text)
        coVerify(exactly = 0) { uiService.invoke(any(), any(), any()) }

        host.pendingConfirm!!.onConfirm()
        runCurrent()

        coVerify(exactly = 1) {
            uiService.invoke("c", "a", UiInvokePayload(values = mapOf("p" to UiValue.of("1")), context = context))
        }
    }

    @Test
    fun invokeIgnoresFormValuesWhenTheActionNamesAnotherForm() = testScope.runTest {
        answerInvoke()
        val action = UiAction.Invoke("c", "a", params = mapOf("p" to UiValue.of("1")), formId = "other")
        host().dispatch(action, binding(formId = "form", values = mapOf("f" to UiValue.of("x"))))
        runCurrent()

        coVerify(exactly = 1) {
            uiService.invoke("c", "a", UiInvokePayload(values = mapOf("p" to UiValue.of("1")), context = context))
        }
    }

    @Test
    fun invokeIgnoresFormValuesWhenTheActionNamesNoForm() = testScope.runTest {
        answerInvoke()
        val action = UiAction.Invoke("c", "a", params = mapOf("p" to UiValue.of("1")))
        host().dispatch(action, binding(values = mapOf("f" to UiValue.of("x"))))
        runCurrent()

        coVerify(exactly = 1) {
            uiService.invoke("c", "a", UiInvokePayload(values = mapOf("p" to UiValue.of("1")), context = context))
        }
    }

    @Test
    fun invokeWithoutAFormSendsOnlyTheActionParams() = testScope.runTest {
        answerInvoke()
        host().dispatch(UiAction.Invoke("c", "a", params = mapOf("p" to UiValue.of("1")), formId = "form"))
        runCurrent()

        coVerify(exactly = 1) {
            uiService.invoke("c", "a", UiInvokePayload(values = mapOf("p" to UiValue.of("1")), context = context))
        }
    }

    @Test
    fun invokeReportsBusyBeforeTheCallAndIdleAfterIt() = testScope.runTest {
        coEvery { uiService.invoke(any(), any(), any()) } coAnswers {
            events.add("invoke")
            UiInvokeResult(UiInvokeStatus.OK)
        }
        host().dispatch(UiAction.Invoke("c", "a"), binding())
        runCurrent()

        assertEquals(listOf("busy:true", "invoke", "busy:false"), events)
    }

    @Test
    fun invokeReportsIdleAfterAFailureToo() = testScope.runTest {
        val shown = signalOnSnackbar()
        coEvery { uiService.invoke(any(), any(), any()) } throws IOException("down")
        host().dispatch(UiAction.Invoke("c", "a"), binding())
        runCurrent()
        shown.await()

        assertEquals(listOf("busy:true", "busy:false"), events)
    }

    @Test
    fun aValidationErrorHandsTheFieldErrorsToTheForm() = testScope.runTest {
        val errors = mapOf("name" to "required", "age" to "too low")
        answerInvoke(UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = errors))
        host().dispatch(UiAction.Invoke("c", "a"), binding())
        runCurrent()

        assertEquals(listOf(errors), fieldErrors)
    }

    @Test
    fun aSuccessfulInvokeClearsTheFieldErrorsOfTheForm() = testScope.runTest {
        answerInvoke(UiInvokeResult(UiInvokeStatus.OK))
        host().dispatch(UiAction.Invoke("c", "a"), binding())
        runCurrent()

        assertEquals(listOf(emptyMap<String, String>()), fieldErrors)
    }

    @Test
    fun invokeWithoutAFormNeedsNoBinding() = testScope.runTest {
        answerInvoke(UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = mapOf("a" to "b")))
        host().dispatch(UiAction.Invoke("c", "a"))
        runCurrent()

        assertTrue(fieldErrors.isEmpty())
        verifyNoSnackbar()
    }

    @Test
    fun invokeResultWithRefreshRefreshes() = testScope.runTest {
        answerInvoke(UiInvokeResult(UiInvokeStatus.OK, refresh = true))
        host().dispatch(UiAction.Invoke("c", "a"))
        runCurrent()

        assertEquals(1, refreshes)
    }

    @Test
    fun invokeResultWithoutRefreshDoesNotRefresh() = testScope.runTest {
        answerInvoke(UiInvokeResult(UiInvokeStatus.OK, refresh = false))
        host().dispatch(UiAction.Invoke("c", "a"))
        runCurrent()

        assertEquals(0, refreshes)
    }

    @Test
    fun invokeResultNextActionIsDispatched() = testScope.runTest {
        answerInvoke(UiInvokeResult(UiInvokeStatus.OK, next = UiAction.OpenUrl("https://example.org")))
        host().dispatch(UiAction.Invoke("c", "a"))
        runCurrent()

        assertEquals(listOf("https://example.org"), urls)
    }

    @Test
    fun invokeResultNextInvokeSendsTheValuesOfTheSameForm() = testScope.runTest {
        coEvery { uiService.invoke("c", "first", any()) } returns UiInvokeResult(
            UiInvokeStatus.OK,
            next = UiAction.Invoke("c", "second", formId = "form"),
        )
        coEvery { uiService.invoke("c", "second", any()) } returns UiInvokeResult(UiInvokeStatus.OK)
        host().dispatch(UiAction.Invoke("c", "first"), binding(values = mapOf("f" to UiValue.of("x"))))
        runCurrent()

        coVerify(exactly = 1) {
            uiService.invoke("c", "second", UiInvokePayload(values = mapOf("f" to UiValue.of("x")), context = context))
        }
    }

    @Test
    fun invokeResultMessageIsShownAsServerSupplied() = testScope.runTest {
        answerInvoke(UiInvokeResult(UiInvokeStatus.OK, message = "saved on the server"))
        host().dispatch(UiAction.Invoke("c", "a"))
        runCurrent()

        verifyShown("saved on the server")
    }

    @Test
    fun invokeResultWithBlankMessageShowsNoSnackbar() = testScope.runTest {
        answerInvoke(UiInvokeResult(UiInvokeStatus.OK, message = "   "))
        host().dispatch(UiAction.Invoke("c", "a"))
        runCurrent()

        verifyNoSnackbar()
    }

    @Test
    fun invokeResultWithoutMessageShowsNoSnackbar() = testScope.runTest {
        answerInvoke(UiInvokeResult(UiInvokeStatus.OK, message = null))
        host().dispatch(UiAction.Invoke("c", "a"))
        runCurrent()

        verifyNoSnackbar()
    }

    @Test
    fun invokeThatThrowsLogsTheErrorAndShowsExactlyOneSnackbar() = testScope.runTest {
        val shown = signalOnSnackbar()
        val failure = IOException("down")
        coEvery { uiService.invoke(any(), any(), any()) } throws failure
        host().dispatch(UiAction.Invoke("c", "a"), binding())
        runCurrent()
        shown.await()

        verify(exactly = 1) { logger.error(ServerUiLogTag, any(), failure, any()) }
        verify(exactly = 1) { snackbar.showSnackbar(any<String>(), any(), any(), any()) }
        assertTrue(fieldErrors.isEmpty())
    }

    @Test
    fun invokeThatIsCancelledPropagatesWithoutSnackbarOrErrorLog() = testScope.runTest {
        coEvery { uiService.invoke(any(), any(), any()) } throws CancellationException("cancelled")
        host().dispatch(UiAction.Invoke("c", "a"), binding())
        runCurrent()

        verifyNoSnackbar()
        verify(exactly = 0) { logger.error(any(), any(), any(), any()) }
        assertEquals(listOf("busy:true", "busy:false"), events)
    }

    @Test
    fun intakeWithoutConfirmTextSubmitsTheItemsWithTheResolver() = testScope.runTest {
        answerIntake()
        val items = listOf(IntakeItem.Text("abc"), IntakeItem.Url("https://example.org"))
        host().dispatch(UiAction.Intake(items, resolverId = "resolver"))
        runCurrent()

        coVerify(exactly = 1) { uiService.intake(items, "resolver") }
    }

    @Test
    fun intakeWithConfirmTextSubmitsNothingUntilConfirmed() = testScope.runTest {
        answerIntake()
        val items = listOf(IntakeItem.Text("abc"))
        val host = host()
        host.dispatch(UiAction.Intake(items, confirmText = "Submit?"))
        runCurrent()

        assertEquals("Submit?", host.pendingConfirm?.text)
        coVerify(exactly = 0) { uiService.intake(any(), any()) }

        host.pendingConfirm!!.onConfirm()
        runCurrent()

        coVerify(exactly = 1) { uiService.intake(items, null) }
    }

    @Test
    fun intakeResultMessageIsShownAsServerSupplied() = testScope.runTest {
        answerIntake(UiIntakeResult(UiIntakeStatus.OK, message = "queued by the server"))
        host().dispatch(UiAction.Intake(listOf(IntakeItem.Text("abc"))))
        runCurrent()

        verifyShown("queued by the server")
    }

    @Test
    fun intakeResultNextActionIsDispatched() = testScope.runTest {
        answerIntake(UiIntakeResult(UiIntakeStatus.OK, next = UiAction.OpenUrl("https://example.org/next")))
        host().dispatch(UiAction.Intake(listOf(IntakeItem.Text("abc"))))
        runCurrent()

        assertEquals(listOf("https://example.org/next"), urls)
    }

    @Test
    fun intakeNeedingAChoiceWithHandlersOffersThem() = testScope.runTest {
        val handlers = listOf(handler("one"), handler("two"))
        answerIntake(UiIntakeResult(UiIntakeStatus.NEEDS_CHOICE, handlers = handlers))
        val host = host()
        host.dispatch(UiAction.Intake(listOf(IntakeItem.Text("abc"))))
        runCurrent()

        assertEquals(handlers, host.pendingChoice?.handlers)
    }

    @Test
    fun intakeNeedingAChoiceWithoutHandlersOffersNothing() = testScope.runTest {
        answerIntake(UiIntakeResult(UiIntakeStatus.NEEDS_CHOICE, handlers = emptyList()))
        val host = host()
        host.dispatch(UiAction.Intake(listOf(IntakeItem.Text("abc"))))
        runCurrent()

        assertNull(host.pendingChoice)
    }

    @Test
    fun intakeThatDoesNotNeedAChoiceIgnoresTheHandlers() = testScope.runTest {
        answerIntake(UiIntakeResult(UiIntakeStatus.OK, handlers = listOf(handler("one"))))
        val host = host()
        host.dispatch(UiAction.Intake(listOf(IntakeItem.Text("abc"))))
        runCurrent()

        assertNull(host.pendingChoice)
    }

    @Test
    fun intakeThatThrowsLogsTheErrorAndShowsExactlyOneSnackbar() = testScope.runTest {
        val shown = signalOnSnackbar()
        val failure = IOException("down")
        coEvery { uiService.intake(any(), any()) } throws failure
        host().dispatch(UiAction.Intake(listOf(IntakeItem.Text("abc"))))
        runCurrent()
        shown.await()

        verify(exactly = 1) { logger.error(ServerUiLogTag, any(), failure, any()) }
        verify(exactly = 1) { snackbar.showSnackbar(any<String>(), any(), any(), any()) }
    }

    @Test
    fun intakeThatIsCancelledPropagatesWithoutSnackbarOrErrorLog() = testScope.runTest {
        coEvery { uiService.intake(any(), any()) } throws CancellationException("cancelled")
        host().dispatch(UiAction.Intake(listOf(IntakeItem.Text("abc"))))
        runCurrent()

        verifyNoSnackbar()
        verify(exactly = 0) { logger.error(any(), any(), any(), any()) }
    }

    @Test
    fun aModalPageIsHeldForTheOverlayInsteadOfNavigating() = testScope.runTest {
        val host = host()
        val action = UiAction.OpenPage("page", mapOf("a" to "b"), modal = true)
        host.dispatch(action)

        assertEquals(action, host.pendingModalPage)
        assertTrue(navigated.isEmpty())
    }

    @Test
    fun aNonModalPageNavigatesToTheServerPageScreen() = testScope.runTest {
        val host = host()
        host.dispatch(UiAction.OpenPage("page", mapOf("a" to "b"), modal = false))

        assertEquals(listOf<Screen>(UiPageScreen("page", mapOf("a" to "b"))), navigated)
        assertNull(host.pendingModalPage)
    }

    @Test
    fun openingAnAlbumNavigatesToTheAlbumScreen() = testScope.runTest {
        host().dispatch(UiAction.OpenEntity(UiEntityType.ALBUM, randomPlatformUUID()))

        assertIs<AlbumScreen>(navigated.single())
    }

    @Test
    fun openingAnArtistNavigatesToTheArtistScreen() = testScope.runTest {
        host().dispatch(UiAction.OpenEntity(UiEntityType.ARTIST, randomPlatformUUID()))

        assertIs<ArtistScreen>(navigated.single())
    }

    @Test
    fun openingAPlaylistNavigatesToTheUserPlaylistScreen() = testScope.runTest {
        val id = randomPlatformUUID()
        host().dispatch(UiAction.OpenEntity(UiEntityType.PLAYLIST, id))

        assertEquals(listOf<Screen>(PlaylistScreen(id, true)), navigated)
    }

    @Test
    fun openingASongHoldsItForTheDetailOverlay() = testScope.runTest {
        val id = randomPlatformUUID()
        val host = host()
        host.dispatch(UiAction.OpenEntity(UiEntityType.SONG, id))

        assertEquals(id, host.pendingSong)
        assertTrue(navigated.isEmpty())
    }

    @Test
    fun openingAUserOnlyLogs() = testScope.runTest {
        val host = host()
        host.dispatch(UiAction.OpenEntity(UiEntityType.USER, randomPlatformUUID()))

        verify(exactly = 1) { logger.info(ServerUiLogTag, any(), any()) }
        assertTrue(navigated.isEmpty())
        assertNull(host.pendingSong)
    }

    @Test
    fun externalSearchWithAQuerySetsTheQueryBeforeNavigating() = testScope.runTest {
        host().dispatch(UiAction.OpenNative(UiPortals.EXTERNAL_SEARCH, mapOf("query" to "abc")))

        assertEquals(listOf("query:abc", "navigate"), events)
        assertIs<SearchScreen>(navigated.single())
    }

    @Test
    fun externalSearchWithoutAQueryClearsTheQueryAndNavigates() = testScope.runTest {
        host().dispatch(UiAction.OpenNative(UiPortals.EXTERNAL_SEARCH))

        assertEquals(listOf(""), queries)
        assertIs<SearchScreen>(navigated.single())
    }

    @Test
    fun theBarcodeScannerPortalOnlyLogs() = testScope.runTest {
        host().dispatch(UiAction.OpenNative(UiPortals.BARCODE_SCANNER))

        verify(exactly = 1) { logger.info(ServerUiLogTag, any(), any()) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun anUnknownNativePortalOnlyLogs() = testScope.runTest {
        host().dispatch(UiAction.OpenNative("somethingElse", mapOf("query" to "abc")))

        verify(exactly = 1) { logger.info(ServerUiLogTag, any(), any()) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun openUrlForwardsTheUrl() = testScope.runTest {
        host().dispatch(UiAction.OpenUrl("https://example.org/page"))

        assertEquals(listOf("https://example.org/page"), urls)
    }

    @Test
    fun refreshActionCallsTheRefreshCallback() = testScope.runTest {
        host().dispatch(UiAction.Refresh)

        assertEquals(1, refreshes)
    }

    @Test
    fun dismissKeyboardDoesNothingInTheHost() = testScope.runTest {
        val host = host()
        host.dispatch(UiAction.DismissKeyboard)

        assertTrue(events.isEmpty())
        assertEquals(0, refreshes)
        assertNull(host.pendingConfirm)
        verifyNoSnackbar()
    }

    @Test
    fun openMenuWithoutAnAnchorOnlyLogs() = testScope.runTest {
        host().dispatch(UiAction.OpenMenu(emptyList()))

        verify(exactly = 1) { logger.info(ServerUiLogTag, any(), any()) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun messageShowsNonBlankTextAndDropsBlankText() = testScope.runTest {
        val host = host()
        host.message("hello there")
        host.message("  ")
        host.message("")

        verifyShown("hello there")
        verify(exactly = 1) { snackbar.showSnackbar(any<String>(), any(), any(), any()) }
    }

    @Test
    fun hostRefreshCallsTheRefreshCallback() = testScope.runTest {
        host().refresh()

        assertEquals(1, refreshes)
    }

    @Test
    fun portalLookupGoesToTheRegistryWithTheName() = testScope.runTest {
        val asked = mutableListOf<String>()
        val host = host(
            UiPortalRegistry {
                asked.add(it)
                null
            }
        )

        assertNull(host.portal("map"))
        assertEquals(listOf("map"), asked)
    }

    @Test
    fun entityIdComesFromTheContext() = testScope.runTest {
        assertEquals(context.entityId, host().entityId)
    }

    @Test
    fun listItemMapsToAnEntry() {
        val icon = UiIcon(UiIconName.MUSIC)
        val action = UiAction.Refresh
        val entry = UiComponent.ListItem(title = "t", subtitle = "s", icon = icon, trailing = "x", action = action).asEntry()

        assertEquals(UiEntry(title = "t", subtitle = "s", icon = icon, action = action), entry)
    }

    @Test
    fun tileMapsToAnEntry() {
        val icon = UiIcon(UiIconName.ALBUM)
        val action = UiAction.OpenUrl("https://example.org")
        val entry = UiComponent.Tile(title = "t", subtitle = "s", icon = icon, action = action).asEntry()

        assertEquals(UiEntry(title = "t", subtitle = "s", icon = icon, action = action), entry)
    }

    @Test
    fun listItemAndTileWithoutActionKeepANullAction() {
        assertNull(UiComponent.ListItem(title = "t").asEntry()?.action)
        assertNull(UiComponent.Tile(title = "t").asEntry()?.action)
    }

    @Test
    fun buttonMapsToAnEntryWithItsLabelAsTitle() {
        val icon = UiIcon(UiIconName.SETTINGS)
        val action = UiAction.Refresh
        val entry = UiComponent.Button(label = "l", action = action, style = UiButtonStyle.PRIMARY, icon = icon).asEntry()

        assertEquals(UiEntry(title = "l", subtitle = null, icon = icon, action = action, enabled = true, destructive = false), entry)
    }

    @Test
    fun destructiveButtonMapsToADestructiveEntry() {
        val entry = UiComponent.Button(label = "l", action = UiAction.Refresh, style = UiButtonStyle.DESTRUCTIVE).asEntry()

        assertTrue(entry!!.destructive)
    }

    @Test
    fun nonDestructiveButtonStylesMapToNonDestructiveEntries() {
        val styles = UiButtonStyle.entries.filter { it != UiButtonStyle.DESTRUCTIVE }
        assertTrue(styles.isNotEmpty())
        styles.forEach { style ->
            val entry = UiComponent.Button(label = "l", action = UiAction.Refresh, style = style).asEntry()
            assertFalse(entry!!.destructive)
        }
    }

    @Test
    fun disabledButtonMapsToADisabledEntry() {
        val entry = UiComponent.Button(label = "l", action = UiAction.Refresh, enabled = false).asEntry()

        assertFalse(entry!!.enabled)
    }

    @Test
    fun componentsWithoutAnActionAreNotEntries() {
        assertNull(UiComponent.Text(text = "t").asEntry())
    }
}
