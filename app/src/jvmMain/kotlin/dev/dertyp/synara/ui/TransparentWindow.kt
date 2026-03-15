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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.*
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import dev.dertyp.synara.Config
import dev.dertyp.synara.LocalTextField
import dev.dertyp.synara.theme.AppTheme
import dev.dertyp.synara.theme.SynaraAppTheme
import dev.dertyp.synara.theme.isAppDark
import dev.dertyp.synara.ui.components.SynaraTextField
import dev.dertyp.synara.ui.components.SynaraTray
import dev.dertyp.synara.ui.components.WindowDraggableArea
import dev.dertyp.synara.utils.OSUtils
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.*
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.system.exitProcess

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

private class GlfwClipboard(private val windowHandle: Long) : Clipboard {
    @OptIn(ExperimentalComposeUiApi::class)
    override suspend fun getClipEntry(): ClipEntry? {
        val text = glfwGetClipboardString(windowHandle) ?: return null
        return ClipEntry(AnnotatedString(text))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = (clipEntry?.nativeClipEntry as? AnnotatedString)?.text
            ?: (clipEntry?.nativeClipEntry as? String)
            ?: ""
        glfwSetClipboardString(windowHandle, text)
    }

    override val nativeClipboard: Any
        get() = windowHandle
}

@Suppress("DEPRECATION")
private class GlfwClipboardManager(private val windowHandle: Long) : ClipboardManager {
    override fun getText(): AnnotatedString? {
        val text = glfwGetClipboardString(windowHandle) ?: return null
        return AnnotatedString(text)
    }

    override fun setText(annotatedString: AnnotatedString) {
        glfwSetClipboardString(windowHandle, annotatedString.text)
    }
}

private fun glfwKeyToComposeKey(key: Int): Key {
    return when (key) {
        GLFW_KEY_A -> Key.A
        GLFW_KEY_B -> Key.B
        GLFW_KEY_C -> Key.C
        GLFW_KEY_D -> Key.D
        GLFW_KEY_E -> Key.E
        GLFW_KEY_F -> Key.F
        GLFW_KEY_G -> Key.G
        GLFW_KEY_H -> Key.H
        GLFW_KEY_I -> Key.I
        GLFW_KEY_J -> Key.J
        GLFW_KEY_K -> Key.K
        GLFW_KEY_L -> Key.L
        GLFW_KEY_M -> Key.M
        GLFW_KEY_N -> Key.N
        GLFW_KEY_O -> Key.O
        GLFW_KEY_P -> Key.P
        GLFW_KEY_Q -> Key.Q
        GLFW_KEY_R -> Key.R
        GLFW_KEY_S -> Key.S
        GLFW_KEY_T -> Key.T
        GLFW_KEY_U -> Key.U
        GLFW_KEY_V -> Key.V
        GLFW_KEY_W -> Key.W
        GLFW_KEY_X -> Key.X
        GLFW_KEY_Y -> Key.Y
        GLFW_KEY_Z -> Key.Z
        GLFW_KEY_0 -> Key.Zero
        GLFW_KEY_1 -> Key.One
        GLFW_KEY_2 -> Key.Two
        GLFW_KEY_3 -> Key.Three
        GLFW_KEY_4 -> Key.Four
        GLFW_KEY_5 -> Key.Five
        GLFW_KEY_6 -> Key.Six
        GLFW_KEY_7 -> Key.Seven
        GLFW_KEY_8 -> Key.Eight
        GLFW_KEY_9 -> Key.Nine
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
        GLFW_KEY_COMMA -> Key.Comma
        GLFW_KEY_PERIOD -> Key.Period
        GLFW_KEY_SLASH -> Key.Slash
        GLFW_KEY_SEMICOLON -> Key.Semicolon
        GLFW_KEY_APOSTROPHE -> Key.Apostrophe
        GLFW_KEY_LEFT_BRACKET -> Key.LeftBracket
        GLFW_KEY_RIGHT_BRACKET -> Key.RightBracket
        GLFW_KEY_BACKSLASH -> Key.Backslash
        GLFW_KEY_GRAVE_ACCENT -> Key.Grave
        GLFW_KEY_MINUS -> Key.Minus
        GLFW_KEY_EQUAL -> Key.Equals
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

    if (OSUtils.isLinux) {
        println("Linux detected. Forcing X11 platform via GLFW hint.")
        glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11)
    }

    if (!glfwInit()) {
        error("Unable to initialize GLFW")
    }

    val showDragHandle =
        System.getProperty("synara.drag.enabled", if (OSUtils.isLinux) "false" else "true").toBoolean()

    glfwDefaultWindowHints()
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
    glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
    glfwWindowHint(GLFW_TRANSPARENT_FRAMEBUFFER, GLFW_TRUE)
    glfwWindowHint(GLFW_DECORATED, GLFW_FALSE)

    val windowHandle = glfwCreateWindow(width, height, title, NULL, NULL)
    if (windowHandle == NULL) {
        error("Failed to create GLFW window")
    }

    val arrowCursor = glfwCreateStandardCursor(GLFW_ARROW_CURSOR)
    val handCursor = glfwCreateStandardCursor(GLFW_HAND_CURSOR)
    val ibeamCursor = glfwCreateStandardCursor(GLFW_IBEAM_CURSOR)

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
    val clipboard = GlfwClipboard(windowHandle)
    val clipboardManager = GlfwClipboardManager(windowHandle)

    var scenePtr: ComposeScene? = null
    val platformContext = object : PlatformContext.Empty() {
        override val windowInfo: WindowInfo = object : WindowInfo {
            override val isWindowFocused: Boolean get() = isWindowFocused
            override val containerSize: IntSize get() = scenePtr?.size ?: IntSize.Zero
        }
        override val textInputService: PlatformTextInputService = textInputService

        override fun setPointerIcon(pointerIcon: PointerIcon) {
            val cursor = when (pointerIcon) {
                PointerIcon.Hand -> handCursor
                PointerIcon.Text -> ibeamCursor
                else -> arrowCursor
            }
            glfwSetCursor(windowHandle, cursor)
        }
    }

    val scene = CanvasLayersComposeScene(
        density = density,
        coroutineContext = Dispatchers.Unconfined,
        platformContext = platformContext,
        invalidate = { glfwPostEmptyEvent() }
    )
    scenePtr = scene

    var isVisible by mutableStateOf(true)
    var isFullscreen by mutableStateOf(false)
    val applicationScope = object : ApplicationScope {
        override fun exitApplication() {
            glfwSetWindowShouldClose(windowHandle, true)
            glfwPostEmptyEvent()
        }
    }

    val windowActions = object : WindowActions {
        override fun toggleFullscreen() {
            isFullscreen = !isFullscreen
        }
        override fun setFullscreen(enabled: Boolean) {
            isFullscreen = enabled
        }
        override val isFullscreen: Boolean get() = isFullscreen
        override fun setCursorVisible(enabled: Boolean) {
            glfwSetInputMode(windowHandle, GLFW_CURSOR, if (enabled) GLFW_CURSOR_NORMAL else GLFW_CURSOR_HIDDEN)
        }
    }

    scene.setContent {
        MaterialTheme(
            colorScheme = colorScheme(),
            shapes = shapes(),
            typography = typography()
        ) {
            val iconStyle by Config.iconStyle.collectAsState()
            @Suppress("DEPRECATION")
            CompositionLocalProvider(
                LocalIconStyle provides iconStyle,
                LocalClipboard provides clipboard,
                LocalClipboardManager provides clipboardManager,
                LocalWindowActions provides windowActions,
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
                with(applicationScope) {
                    SynaraTray(
                        onAction = {
                            isVisible = !isVisible
                            glfwPostEmptyEvent()
                        },
                        onExit = { exitApplication() }
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = windowBackground(),
                    contentColor = onBackground()
                ) {
                    Column {
                        if (showDragHandle && !isFullscreen) {
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

    glfwSetWindowCloseCallback(windowHandle) { _ ->
        if (Config.hideOnClose.value) {
            glfwSetWindowShouldClose(windowHandle, false)
            isVisible = false
            glfwPostEmptyEvent()
        }
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

    glfwSetMouseButtonCallback(windowHandle) { _, button, action, mods ->
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
            },
            keyboardModifiers = PointerKeyboardModifiers(
                isCtrlPressed = (mods and GLFW_MOD_CONTROL) != 0,
                isMetaPressed = (mods and GLFW_MOD_SUPER) != 0,
                isAltPressed = (mods and GLFW_MOD_ALT) != 0,
                isShiftPressed = (mods and GLFW_MOD_SHIFT) != 0,
            )
        )
    }

    glfwSetScrollCallback(windowHandle) { _, xOffset, yOffset ->
        val isShiftPressed = glfwGetKey(windowHandle, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS || 
                             glfwGetKey(windowHandle, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS
        val isCtrlPressed = glfwGetKey(windowHandle, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS ||
                            glfwGetKey(windowHandle, GLFW_KEY_RIGHT_CONTROL) == GLFW_PRESS
        val isAltPressed = glfwGetKey(windowHandle, GLFW_KEY_LEFT_ALT) == GLFW_PRESS ||
                           glfwGetKey(windowHandle, GLFW_KEY_RIGHT_ALT) == GLFW_PRESS
        val isMetaPressed = glfwGetKey(windowHandle, GLFW_KEY_LEFT_SUPER) == GLFW_PRESS ||
                            glfwGetKey(windowHandle, GLFW_KEY_RIGHT_SUPER) == GLFW_PRESS

        val scrollX = if (isShiftPressed) -yOffset else xOffset
        val scrollY = if (isShiftPressed) xOffset else -yOffset

        scene.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = run {
                val x = DoubleArray(1)
                val y = DoubleArray(1)
                glfwGetCursorPos(windowHandle, x, y)
                Offset(x[0].toFloat(), y[0].toFloat())
            },
            scrollDelta = Offset(
                scrollX.toFloat() * 2.5f * density.density,
                scrollY.toFloat() * 2.5f * density.density
            ),
            keyboardModifiers = PointerKeyboardModifiers(
                isCtrlPressed = isCtrlPressed,
                isMetaPressed = isMetaPressed,
                isAltPressed = isAltPressed,
                isShiftPressed = isShiftPressed,
            )
        )
    }

    glfwSetKeyCallback(windowHandle) { _, key, _, action, mods ->
        val type = when (action) {
            GLFW_PRESS, GLFW_REPEAT -> KeyEventType.KeyDown
            else -> KeyEventType.KeyUp
        }
        val composeKey = glfwKeyToComposeKey(key)

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

    var lastVisibleState = true
    var lastFullscreenState = false
    var windowX = 0
    var windowY = 0
    var windowWidth = width
    var windowHeight = height

    while (!glfwWindowShouldClose(windowHandle)) {
        if (isVisible != lastVisibleState) {
            if (isVisible) {
                glfwShowWindow(windowHandle)
                glfwFocusWindow(windowHandle)
            } else {
                glfwHideWindow(windowHandle)
            }
            lastVisibleState = isVisible
        }

        if (isFullscreen != lastFullscreenState) {
            if (isFullscreen) {
                stackPush().use { stack ->
                    val x = stack.mallocInt(1)
                    val y = stack.mallocInt(1)
                    val w = stack.mallocInt(1)
                    val h = stack.mallocInt(1)
                    glfwGetWindowPos(windowHandle, x, y)
                    glfwGetWindowSize(windowHandle, w, h)
                    windowX = x.get(0)
                    windowY = y.get(0)
                    windowWidth = w.get(0)
                    windowHeight = h.get(0)
                }
                val monitor = glfwGetPrimaryMonitor()
                val vidMode = glfwGetVideoMode(monitor)
                if (vidMode != null) {
                    glfwSetWindowMonitor(windowHandle, monitor, 0, 0, vidMode.width(), vidMode.height(), vidMode.refreshRate())
                }
            } else {
                glfwSetWindowMonitor(windowHandle, NULL, windowX, windowY, windowWidth, windowHeight, 0)
            }
            lastFullscreenState = isFullscreen
        }

        if (isVisible) {
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
        }
        glfwPollEvents()
    }

    scene.close()
    skiaContext.close()
    glfwDestroyCursor(arrowCursor)
    glfwDestroyCursor(handCursor)
    glfwDestroyCursor(ibeamCursor)
    glfwFreeCallbacks(windowHandle)
    glfwDestroyWindow(windowHandle)
    glfwTerminate()
    exitProcess(0)
}
