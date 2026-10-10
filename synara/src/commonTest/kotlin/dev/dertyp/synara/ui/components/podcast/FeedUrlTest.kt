package dev.dertyp.synara.ui.components.podcast

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedUrlTest {
    private fun urlOfLength(length: Int): String {
        val prefix = "https://a"
        return prefix + "b".repeat(length - prefix.length)
    }

    @Test
    fun httpsUrlWithPathIsValid() {
        assertTrue(isValidFeedUrl("https://a.b/feed"))
    }

    @Test
    fun schemeIsMatchedCaseInsensitively() {
        assertTrue(isValidFeedUrl("HTTP://x.y"))
    }

    @Test
    fun urlWithQueryAndNoPathIsValid() {
        assertTrue(isValidFeedUrl("https://example.org?feed=1"))
    }

    @Test
    fun otherSchemesAreRejected() {
        assertFalse(isValidFeedUrl("ftp://x"))
    }

    @Test
    fun urlWithoutHostIsRejected() {
        assertFalse(isValidFeedUrl("https://"))
    }

    @Test
    fun urlWithSlashRightAfterTheSchemeIsRejected() {
        assertFalse(isValidFeedUrl("https:///x"))
    }

    @Test
    fun urlContainingWhitespaceIsRejected() {
        assertFalse(isValidFeedUrl("https://a b"))
    }

    @Test
    fun urlOfExactlyTheMaximumLengthIsValid() {
        assertTrue(isValidFeedUrl(urlOfLength(2048)))
    }

    @Test
    fun urlOneCharacterOverTheMaximumLengthIsRejected() {
        assertFalse(isValidFeedUrl(urlOfLength(2049)))
    }

    @Test
    fun emptyInputIsRejected() {
        assertFalse(isValidFeedUrl(""))
    }
}
