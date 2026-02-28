package dev.dertyp.synara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.LocalTextField
import dev.dertyp.synara.theme.AppTheme
import dev.dertyp.synara.theme.SynaraAppTheme
import dev.dertyp.synara.theme.isAppDark
import dev.dertyp.synara.ui.components.SynaraTextField
import dev.dertyp.synara.ui.components.WindowDraggableArea
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.*
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.NULL

@OptIn(ExperimentalComposeUiApi::class)
private class GlfwTextInputService : PlatformTextInputService {
    var onEditCommand: ((List<EditCommand>) -> Unit)? = null

    override fun startInput(
        value: TextFieldValue,
        imeOptions: ImeOptions,
        onEditCommand: (List<EditCommand>) -> Unit,
        onImeActionPerformed: (ImeAction) -> Unit
    ) {
        this.onEditCommand = onEditCommand
    }

    override fun stopInput() {
        this.onEditCommand = null
    }

    override fun showSoftwareKeyboard() {}
    override fun hideSoftwareKeyboard() {}
    override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) {}
}

private fun glfwKeyToComposeKey(key: Int, scancode: Int): Key {
    val name = glfwGetKeyName(key, scancode)
    if (name != null && name.length == 1) {
        val char = name[0].uppercaseChar()
        if (char in 'A'..'Z') return Key(char.code.toLong())
        if (char in '0'..'9') return Key(char.code.toLong())
        when (char) {
            ',' -> return Key.Comma
            '.' -> return Key.Period
            '/' -> return Key.Slash
            ';' -> return Key.Semicolon
            '\'' -> return Key.Apostrophe
            '[' -> return Key.LeftBracket
            ']' -> return Key.RightBracket
            '\\' -> return Key.Backslash
            '`' -> return Key.Grave
            '-' -> return Key.Minus
            '=' -> return Key.Equals
        }
    }

    return when (key) {
        GLFW_KEY_ENTER -> Key.Enter
        GLFW_KEY_BACKSPACE -> Key.Backspace
        GLFW_KEY_TAB -> Key.Tab
        GLFW_KEY_DELETE -> Key.Delete
        GLFW_KEY_ESCAPE -> Key.Escape
        GLFW_KEY_SPACE -> Key.Spacebar
        GLFW_KEY_UP -> Key.DirectionUp
        GLFW_KEY_DOWN -> Key.DirectionDown
        GLFW_KEY_LEFT -> Key.DirectionLeft
        GLFW_KEY_RIGHT -> Key.DirectionRight
        GLFW_KEY_HOME -> Key.MoveHome
        GLFW_KEY_END -> Key.MoveEnd
        GLFW_KEY_PAGE_UP -> Key.PageUp
        GLFW_KEY_PAGE_DOWN -> Key.PageDown
        GLFW_KEY_F1 -> Key.F1
        GLFW_KEY_F2 -> Key.F2
        GLFW_KEY_F3 -> Key.F3
        GLFW_KEY_F4 -> Key.F4
        GLFW_KEY_F5 -> Key.F5
        GLFW_KEY_F6 -> Key.F6
        GLFW_KEY_F7 -> Key.F7
        GLFW_KEY_F8 -> Key.F8
        GLFW_KEY_F9 -> Key.F9
        GLFW_KEY_F10 -> Key.F10
        GLFW_KEY_F11 -> Key.F11
        GLFW_KEY_F12 -> Key.F12
        GLFW_KEY_LEFT_SHIFT -> Key.ShiftLeft
        GLFW_KEY_RIGHT_SHIFT -> Key.ShiftRight
        GLFW_KEY_LEFT_CONTROL -> Key.CtrlLeft
        GLFW_KEY_RIGHT_CONTROL -> Key.CtrlRight
        GLFW_KEY_LEFT_ALT -> Key.AltLeft
        GLFW_KEY_RIGHT_ALT -> Key.AltRight
        GLFW_KEY_LEFT_SUPER -> Key.MetaLeft
        GLFW_KEY_RIGHT_SUPER -> Key.MetaRight
        GLFW_KEY_CAPS_LOCK -> Key.CapsLock
        GLFW_KEY_SCROLL_LOCK -> Key.ScrollLock
        GLFW_KEY_NUM_LOCK -> Key.NumLock
        GLFW_KEY_PRINT_SCREEN -> Key.PrintScreen
        GLFW_KEY_PAUSE -> Key.Break
        GLFW_KEY_INSERT -> Key.Insert
        GLFW_KEY_MENU -> Key.Menu
        GLFW_KEY_KP_0 -> Key.NumPad0
        GLFW_KEY_KP_1 -> Key.NumPad1
        GLFW_KEY_KP_2 -> Key.NumPad2
        GLFW_KEY_KP_3 -> Key.NumPad3
        GLFW_KEY_KP_4 -> Key.NumPad4
        GLFW_KEY_KP_5 -> Key.NumPad5
        GLFW_KEY_KP_6 -> Key.NumPad6
        GLFW_KEY_KP_7 -> Key.NumPad7
        GLFW_KEY_KP_8 -> Key.NumPad8
        GLFW_KEY_KP_9 -> Key.NumPad9
        GLFW_KEY_KP_DECIMAL -> Key.NumPadDot
        GLFW_KEY_KP_DIVIDE -> Key.NumPadDivide
        GLFW_KEY_KP_MULTIPLY -> Key.NumPadMultiply
        GLFW_KEY_KP_SUBTRACT -> Key.NumPadSubtract
        GLFW_KEY_KP_ADD -> Key.NumPadAdd
        GLFW_KEY_KP_ENTER -> Key.NumPadEnter
        GLFW_KEY_KP_EQUAL -> Key.NumPadEquals
        else -> Key.Unknown
    }
}

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
fun runTransparentWindow(
    title: String = "Synara",
    width: Int = 1280,
    height: Int = 720,
    minWidth: Int = GLFW_DONT_CARE,
    minHeight: Int = GLFW_DONT_CARE,
    isDarkTheme: @Composable () -> Boolean = { isAppDark() },
    appTheme: @Composable () -> AppTheme = { SynaraAppTheme(isDarkTheme()) },
    colorScheme: @Composable () -> ColorScheme = { appTheme().colorScheme },
    shapes: @Composable () -> Shapes = { appTheme().shapes },
    typography: @Composable () -> Typography = { appTheme().typography },
    windowBackground: @Composable () -> Color = { appTheme().colorScheme.background.copy(alpha = .6f) },
    onBackground: @Composable () -> Color = { appTheme().colorScheme.onBackground },
    content: @Composable () -> Unit
) {
    glfwSetErrorCallback { error, description ->
        println("GLFW Error [$error]: ${org.lwjgl.glfw.GLFWErrorCallback.getDescription(description)}")
    }

    val osName = System.getProperty("os.name").lowercase()
    val isLinux = osName.contains("linux")

    if (isLinux) {
        println("Linux detected. Forcing X11 platform via GLFW hint.")
        glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11)
    }

    if (!glfwInit()) {
        error("Unable to initialize GLFW")
    }

    val showDragHandle =
        System.getProperty("synara.drag.enabled", if (isLinux) "false" else "true").toBoolean()

    glfwDefaultWindowHints()
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
    glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
    glfwWindowHint(GLFW_TRANSPARENT_FRAMEBUFFER, GLFW_TRUE)
    glfwWindowHint(GLFW_DECORATED, GLFW_FALSE)

    val windowHandle = glfwCreateWindow(width, height, title, NULL, NULL)
    if (windowHandle == NULL) {
        error("Failed to create GLFW window")
    }

    glfwSetWindowSizeLimits(windowHandle, minWidth, minHeight, GLFW_DONT_CARE, GLFW_DONT_CARE)

    glfwMakeContextCurrent(windowHandle)
    GL.createCapabilities()
    glfwSwapInterval(1)

    val skiaContext = DirectContext.makeGL()

    val density = stackPush().use { stack ->
        val x = stack.mallocFloat(1)
        val y = stack.mallocFloat(1)
        glfwGetWindowContentScale(windowHandle, x, y)
        Density(x.get(0))
    }

    var isWindowFocused by mutableStateOf(true)
    val textInputService = GlfwTextInputService()

    var scenePtr: ComposeScene? = null
    val platformContext = object : PlatformContext.Empty() {
        override val windowInfo: WindowInfo = object : WindowInfo {
            override val isWindowFocused: Boolean get() = isWindowFocused
            override val containerSize: IntSize get() = scenePtr?.size ?: IntSize.Zero
        }
        override val textInputService: PlatformTextInputService = textInputService
    }

    val scene = CanvasLayersComposeScene(
        density = density,
        coroutineContext = Dispatchers.Unconfined,
        platformContext = platformContext,
        invalidate = { glfwPostEmptyEvent() }
    )
    scenePtr = scene

    scene.setContent {
        MaterialTheme(
            colorScheme = colorScheme(),
            shapes = shapes(),
            typography = typography()
        ) {
            CompositionLocalProvider(
                LocalTextField provides { value, onValueChange, modifier, enabled, readOnly, textStyle, label, placeholder, leadingIcon, trailingIcon, prefix, suffix, supportingText, isError, visualTransformation, keyboardOptions, keyboardActions, singleLine, maxLines, minLines, interactionSource, shape, colors ->
                    SynaraTextField(
                        value = value,
                        onValueChange = { onValueChange(it) },
                        modifier = modifier,
                        enabled = enabled,
                        readOnly = readOnly,
                        textStyle = textStyle,
                        label = label,
                        placeholder = placeholder,
                        leadingIcon = leadingIcon,
                        trailingIcon = trailingIcon,
                        prefix = prefix,
                        suffix = suffix,
                        supportingText = supportingText,
                        isError = isError,
                        visualTransformation = visualTransformation,
                        keyboardOptions = keyboardOptions,
                        keyboardActions = keyboardActions,
                        singleLine = singleLine,
                        maxLines = maxLines,
                        minLines = minLines,
                        interactionSource = interactionSource,
                        shape = shape,
                        colors = colors
                    )
                }
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = windowBackground(),
                    contentColor = onBackground()
                ) {
                    Column {
                        if (showDragHandle) {
                            WindowDraggableArea(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .background(Color.Black.copy(alpha = 0.2f)),
                                onDrag = { dx, dy ->
                                    stackPush().use { stack ->
                                        val x = stack.mallocInt(1)
                                        val y = stack.mallocInt(1)
                                        glfwGetWindowPos(windowHandle, x, y)
                                        glfwSetWindowPos(
                                            windowHandle,
                                            x.get(0) + dx.toInt(),
                                            y.get(0) + dy.toInt()
                                        )
                                    }
                                }
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Drag me", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            content()
                        }
                    }
                }
            }
        }
    }

    glfwSetWindowFocusCallback(windowHandle) { _, focused ->
        isWindowFocused = focused
    }

    glfwSetFramebufferSizeCallback(windowHandle) { _, w, h ->
        scene.size = IntSize(w, h)
    }

    glfwSetCursorPosCallback(windowHandle) { _, x, y ->
        scene.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(x.toFloat(), y.toFloat())
        )
    }

    glfwSetMouseButtonCallback(windowHandle) { _, button, action, _ ->
        scene.sendPointerEvent(
            eventType = if (action == GLFW_PRESS) PointerEventType.Press else PointerEventType.Release,
            position = run {
                val x = DoubleArray(1)
                val y = DoubleArray(1)
                glfwGetCursorPos(windowHandle, x, y)
                Offset(x[0].toFloat(), y[0].toFloat())
            },
            button = when (button) {
                GLFW_MOUSE_BUTTON_LEFT -> PointerButton.Primary
                GLFW_MOUSE_BUTTON_RIGHT -> PointerButton.Secondary
                GLFW_MOUSE_BUTTON_MIDDLE -> PointerButton.Tertiary
                GLFW_MOUSE_BUTTON_4 -> PointerButton.Back
                GLFW_MOUSE_BUTTON_5 -> PointerButton.Forward
                else -> null
            }
        )
    }

    glfwSetScrollCallback(windowHandle) { _, xOffset, yOffset ->
        scene.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = run {
                val x = DoubleArray(1)
                val y = DoubleArray(1)
                glfwGetCursorPos(windowHandle, x, y)
                Offset(x[0].toFloat(), y[0].toFloat())
            },
            scrollDelta = Offset(
                xOffset.toFloat() * 2.5f * density.density,
                -yOffset.toFloat() * 2.5f * density.density
            )
        )
    }

    glfwSetKeyCallback(windowHandle) { _, key, scancode, action, mods ->
        val type = when (action) {
            GLFW_PRESS, GLFW_REPEAT -> KeyEventType.KeyDown
            else -> KeyEventType.KeyUp
        }
        val composeKey = glfwKeyToComposeKey(key, scancode)

        scene.sendKeyEvent(
            KeyEvent(
                key = composeKey,
                type = type,
                isCtrlPressed = (mods and GLFW_MOD_CONTROL) != 0,
                isMetaPressed = (mods and GLFW_MOD_SUPER) != 0,
                isAltPressed = (mods and GLFW_MOD_ALT) != 0,
                isShiftPressed = (mods and GLFW_MOD_SHIFT) != 0,
            )
        )
    }

    glfwSetCharCallback(windowHandle) { _, codepoint ->
        val text = String(Character.toChars(codepoint))
        textInputService.onEditCommand?.invoke(listOf(CommitTextCommand(text, 1)))
    }

    glfwShowWindow(windowHandle)

    stackPush().use { stack ->
        val w = stack.mallocInt(1)
        val h = stack.mallocInt(1)
        glfwGetFramebufferSize(windowHandle, w, h)
        scene.size = IntSize(w.get(0), h.get(0))
    }

    while (!glfwWindowShouldClose(windowHandle)) {
        val size = scene.size
        val w = size?.width ?: 0
        val h = size?.height ?: 0

        if (w > 0 && h > 0) {
            val renderTarget =
                BackendRenderTarget.makeGL(w, h, 0, 8, 0, FramebufferFormat.GR_GL_RGBA8)
            val surface = Surface.makeFromBackendRenderTarget(
                skiaContext,
                renderTarget,
                SurfaceOrigin.BOTTOM_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.sRGB
            )

            surface?.let {
                it.canvas.clear(Color.Transparent.toArgb())
                scene.render(it.canvas.asComposeCanvas(), System.nanoTime())
                it.flushAndSubmit()
                it.close()
            }
            renderTarget.close()
        }

        glfwSwapBuffers(windowHandle)
        glfwPollEvents()
    }

    scene.close()
    skiaContext.close()
    glfwFreeCallbacks(windowHandle)
    glfwDestroyWindow(windowHandle)
    glfwTerminate()
}
