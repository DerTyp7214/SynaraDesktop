package dev.dertyp.synara.ui.server

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.dertyp.randomPlatformUUID
import dev.dertyp.ui.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class UiRendererTest {

    private class RecordingUiHost(
        private val answers: (key: String, query: String) -> List<UiOption> = { _, _ -> emptyList() },
    ) : UiHost {
        override val contributionId = "contribution"
        override val context = UiContext(params = mapOf("page" to "1"))
        val searches = mutableListOf<Pair<String, String>>()
        val dispatched = mutableListOf<Pair<UiAction, UiFormBinding?>>()

        override fun dispatch(action: UiAction, form: UiFormBinding?) {
            dispatched.add(action to form)
        }

        override suspend fun search(key: String, query: String): List<UiOption> {
            searches.add(key to query)
            return answers(key, query)
        }

        override fun portal(name: String): UiPortalContent? = null
        override fun message(text: String) = Unit
        override fun refresh() = Unit
    }

    private val submit = UiAction.Invoke(contributionId = "contribution", actionId = "save", formId = "form")

    private fun searchForm(field: UiComponent.SearchField) = UiComponent.Form(
        id = "form",
        children = listOf(field),
        submit = submit,
        submitLabel = "Save",
    )

    private fun renderTest(component: UiComponent, host: UiHost, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            MaterialTheme {
                UiRenderer(component, host)
            }
        }
        block()
    }

    private fun ComposeUiTest.type(key: String, text: String) {
        onNodeWithTag(UiTestTags.searchField(key)).performTextInput(text)
    }

    @Test
    fun aSearchFieldQueriesTheHostAndSubmitsThePickedOptionWithTheForm() {
        val host = RecordingUiHost { _, query ->
            if (query == "abba") listOf(UiOption("artist-1", "ABBA"), UiOption("artist-2", "Abba Tribute")) else emptyList()
        }
        renderTest(searchForm(UiComponent.SearchField(key = "artist", label = "Artist")), host) {
            type("artist", "abba")
            mainClock.advanceTimeBy(UI_SEARCH_DEBOUNCE_MS + 50)
            waitForIdle()

            assertEquals(listOf("artist" to "abba"), host.searches)
            onNodeWithTag(UiTestTags.searchOption("artist-1")).assertExists()
            onNodeWithTag(UiTestTags.searchOption("artist-2")).performClick()
            waitForIdle()

            onNodeWithTag(UiTestTags.searchOption("artist-1")).assertDoesNotExist()
            onNodeWithTag(UiTestTags.formSubmit("form")).performClick()
            waitForIdle()

            val (action, binding) = host.dispatched.single()
            assertEquals(submit, action)
            assertNotNull(binding)
            assertEquals("form", binding.formId)
            assertEquals(UiValue.of("artist-2"), binding.values()["artist"])
        }
    }

    @Test
    fun anUntouchedSearchFieldSubmitsItsCurrentValue() {
        val host = RecordingUiHost()
        val field = UiComponent.SearchField(key = "artist", label = "Artist", value = UiOption("artist-9", "Queen"))
        renderTest(searchForm(field), host) {
            onNodeWithTag(UiTestTags.formSubmit("form")).performClick()
            waitForIdle()

            assertTrue(host.searches.isEmpty())
            assertEquals(UiValue.of("artist-9"), host.dispatched.single().second?.values()?.get("artist"))
        }
    }

    @Test
    fun typingIsDebouncedIntoOneSearchForTheLatestQuery() {
        val host = RecordingUiHost()
        renderTest(searchForm(UiComponent.SearchField(key = "artist", label = "Artist")), host) {
            mainClock.autoAdvance = false
            type("artist", "ab")
            mainClock.advanceTimeBy(100)
            type("artist", "b")
            mainClock.advanceTimeBy(100)
            type("artist", "a")
            mainClock.advanceTimeBy(UI_SEARCH_DEBOUNCE_MS - 50)

            assertTrue(host.searches.isEmpty())

            mainClock.advanceTimeBy(100)

            assertEquals(listOf("artist" to "abba"), host.searches)
        }
    }

    @Test
    fun aQueryShorterThanMinCharsIsNotSearched() {
        val host = RecordingUiHost()
        renderTest(searchForm(UiComponent.SearchField(key = "artist", label = "Artist", minChars = 3)), host) {
            type("artist", "ab")
            mainClock.advanceTimeBy(UI_SEARCH_DEBOUNCE_MS * 4)
            waitForIdle()

            assertTrue(host.searches.isEmpty())
        }
    }

    @Test
    fun aListItemShowsItsImageInsteadOfTheIcon() {
        val image = UiImageRef(imageId = randomPlatformUUID(), blurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj")
        val item = UiComponent.ListItem(title = "t", icon = UiIcon.Named(UiIconName.ARTIST), image = image)
        renderTest(item, RecordingUiHost()) {
            onNodeWithTag(UiTestTags.image(image), useUnmergedTree = true).assertExists()
            onAllNodesWithTag(UiTestTags.ICON, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun aListItemWithoutAnImageShowsTheIcon() {
        val item = UiComponent.ListItem(title = "t", icon = UiIcon.Named(UiIconName.ARTIST))
        renderTest(item, RecordingUiHost()) {
            onAllNodesWithTag(UiTestTags.ICON, useUnmergedTree = true).assertCountEquals(1)
        }
    }

    @Test
    fun aCardHeaderShowsItsImageInsteadOfTheIcon() {
        val image = UiImageRef(imageId = randomPlatformUUID(), rounded = false)
        val card = UiComponent.Card(children = emptyList(), title = "t", icon = UiIcon.Named(UiIconName.ALBUM), image = image)
        renderTest(card, RecordingUiHost()) {
            onNodeWithTag(UiTestTags.image(image), useUnmergedTree = true).assertExists()
            onAllNodesWithTag(UiTestTags.ICON, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun aCardWithOnlyAnImageStillRendersItsHeader() {
        val image = UiImageRef(imageId = randomPlatformUUID())
        renderTest(UiComponent.Card(children = emptyList(), image = image), RecordingUiHost()) {
            onNodeWithTag(UiTestTags.image(image), useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun aSearchOptionShowsItsImageInsteadOfTheIcon() {
        val image = UiImageRef(imageId = randomPlatformUUID())
        val host = RecordingUiHost { _, _ ->
            listOf(
                UiOption("with-image", "A", icon = UiIcon.Named(UiIconName.ARTIST), image = image),
                UiOption("with-icon", "B", icon = UiIcon.Named(UiIconName.ARTIST)),
            )
        }
        renderTest(searchForm(UiComponent.SearchField(key = "artist", label = "Artist")), host) {
            type("artist", "query")
            mainClock.advanceTimeBy(UI_SEARCH_DEBOUNCE_MS + 50)
            waitForIdle()

            onNode(
                hasTestTag(UiTestTags.image(image)) and hasAnyAncestor(hasTestTag(UiTestTags.searchOption("with-image"))),
                useUnmergedTree = true,
            ).assertExists()
            onAllNodes(
                hasTestTag(UiTestTags.ICON) and hasAnyAncestor(hasTestTag(UiTestTags.searchOption("with-image"))),
                useUnmergedTree = true,
            ).assertCountEquals(0)
            onAllNodes(
                hasTestTag(UiTestTags.ICON) and hasAnyAncestor(hasTestTag(UiTestTags.searchOption("with-icon"))),
                useUnmergedTree = true,
            ).assertCountEquals(1)
        }
    }

    @Test
    fun theBlurHashDecodesToTheRequestedPixelCount() {
        val pixels = BlurHash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdnj", 4, 3)
        assertNotNull(pixels)
        assertEquals(12, pixels.size)
        assertNull(BlurHash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdn", 4, 3))
        assertNull(BlurHash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdn\"", 4, 3))
    }
}
