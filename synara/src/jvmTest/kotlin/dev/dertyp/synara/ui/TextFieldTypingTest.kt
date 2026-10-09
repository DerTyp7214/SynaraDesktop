package dev.dertyp.synara.ui

import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.ImeAction
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalComposeUiApi::class)
class TextFieldTypingTest {

    private class Harness(singleLine: Boolean = false) : AutoCloseable {
        val text = mutableStateOf("")
        var clipboardText: String? = null
        var searches = 0
        private var frame = 0L
        private val clipboard = TextClipboard(read = { clipboardText }, write = { clipboardText = it })

        private val scene = ImageComposeScene(400, 200) {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                val focus = remember { FocusRequester() }
                OutlinedTextField(
                    value = text.value,
                    onValueChange = { text.value = it },
                    modifier = Modifier.focusRequester(focus),
                    singleLine = singleLine,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searches++ })
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }

        init {
            repeat(3) { frame() }
        }

        fun frame() {
            scene.render(frame++ * FRAME_NANOS)
        }

        fun type(value: String) {
            value.codePoints().forEach {
                scene.sendKeyEvent(typedKeyEvent(it))
                frame()
            }
        }

        fun press(key: Key, ctrl: Boolean = false, shift: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl, isShiftPressed = shift))
            frame()
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl, isShiftPressed = shift))
            frame()
        }

        fun awaitFrames(condition: () -> Boolean) {
            repeat(200) {
                if (condition()) return
                Thread.sleep(10)
                frame()
            }
        }

        override fun close() = scene.close()
    }

    @Test
    fun typedCharactersAppearInOrder() = Harness().use { harness ->
        harness.type("aÄ€ß😀b")

        assertEquals("aÄ€ß😀b", harness.text.value)
    }

    @Test
    fun backspaceRemovesAWholeEmoji() = Harness().use { harness ->
        harness.type("a😀")
        harness.press(Key.Backspace)

        assertEquals("a", harness.text.value)
    }

    @Test
    fun typingReplacesEverythingAfterSelectAll() = Harness().use { harness ->
        harness.type("hello")
        harness.press(Key.A, ctrl = true)
        harness.type("x")

        assertEquals("x", harness.text.value)
    }

    @Test
    fun shiftArrowSelectsAndTypingReplacesTheSelection() = Harness().use { harness ->
        harness.type("hello")
        harness.press(Key.DirectionLeft, shift = true)
        harness.press(Key.DirectionLeft, shift = true)
        harness.type("y")

        assertEquals("hely", harness.text.value)
    }

    @Test
    fun arrowKeysMoveTheCaret() = Harness().use { harness ->
        harness.type("ac")
        harness.press(Key.DirectionLeft)
        harness.type("b")

        assertEquals("abc", harness.text.value)
    }

    @Test
    fun enterRunsTheSearchActionInASingleLineField() = Harness(singleLine = true).use { harness ->
        harness.type("query")
        harness.press(Key.Enter)

        assertEquals(1, harness.searches)
        assertEquals("query", harness.text.value)
    }

    @Test
    fun copyPutsTheSelectionOnTheClipboard() = Harness().use { harness ->
        harness.type("hello")
        harness.press(Key.A, ctrl = true)
        harness.press(Key.C, ctrl = true)
        harness.awaitFrames { harness.clipboardText != null }

        assertEquals("hello", harness.clipboardText)
        assertEquals("hello", harness.text.value)
    }

    @Test
    fun cutRemovesTheSelectionAndPutsItOnTheClipboard() = Harness().use { harness ->
        harness.type("hello")
        harness.press(Key.A, ctrl = true)
        harness.press(Key.X, ctrl = true)
        harness.awaitFrames { harness.clipboardText != null && harness.text.value.isEmpty() }

        assertEquals("hello", harness.clipboardText)
        assertEquals("", harness.text.value)
    }

    @Test
    fun pasteInsertsTheClipboardTextAtTheCaret() = Harness().use { harness ->
        harness.clipboardText = "wor"
        harness.type("hello ld")
        harness.press(Key.DirectionLeft)
        harness.press(Key.DirectionLeft)
        harness.press(Key.V, ctrl = true)
        harness.awaitFrames { harness.text.value != "hello ld" }

        assertEquals("hello world", harness.text.value)
    }

    private companion object {
        const val FRAME_NANOS = 16_000_000L
    }
}
