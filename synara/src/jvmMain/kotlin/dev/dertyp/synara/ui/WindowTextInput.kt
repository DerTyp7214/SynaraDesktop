package dev.dertyp.synara.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.text.AnnotatedString
import java.awt.Component
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent as AwtKeyEvent

private val typedEventSource = object : Component() {}

@OptIn(InternalComposeUiApi::class)
fun typedKeyEvent(codepoint: Int): KeyEvent = KeyEvent(
    key = Key.Unknown,
    type = KeyEventType.Unknown,
    codePoint = codepoint,
    nativeEvent = AwtKeyEvent(
        typedEventSource,
        AwtKeyEvent.KEY_TYPED,
        System.currentTimeMillis(),
        0,
        AwtKeyEvent.VK_UNDEFINED,
        Character.toChars(codepoint)[0]
    )
)

@OptIn(ExperimentalComposeUiApi::class)
class TextClipboard(
    private val read: () -> String?,
    private val write: (String) -> Unit
) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = read()?.let { ClipEntry(StringSelection(it)) }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val transferable = clipEntry?.asAwtTransferable
        val text = when (val native = clipEntry?.nativeClipEntry) {
            is AnnotatedString -> native.text
            is String -> native
            else -> transferable?.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }
                ?.getTransferData(DataFlavor.stringFlavor) as? String
        }
        write(text ?: "")
    }

    override val nativeClipboard: Any
        get() = this
}
