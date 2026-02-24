package dev.dertyp.synara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.dertyp.synara.ui.components.WindowDraggableArea
import dev.dertyp.synara.ui.theme.DarkColors
import dev.dertyp.synara.ui.theme.LightColors
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.*
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.NULL
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent

private val dummyComponent = object : Component() {}

private fun glfwModsToAwt(mods: Int): Int {
    var result = 0
    if (mods and GLFW_MOD_SHIFT != 0) {
        result = result or InputEvent.SHIFT_DOWN_MASK
    }
    if (mods and GLFW_MOD_CONTROL != 0) {
        result = result or InputEvent.CTRL_DOWN_MASK
    }
    if (mods and GLFW_MOD_ALT != 0) {
        result = result or InputEvent.ALT_DOWN_MASK
    }
    if (mods and GLFW_MOD_SUPER != 0) {
        result = result or InputEvent.META_DOWN_MASK
    }
    return result
}

private fun glfwKeyToAwt(key: Int): Int = when (key) {
    GLFW_KEY_ENTER -> AwtKeyEvent.VK_ENTER
    GLFW_KEY_BACKSPACE -> AwtKeyEvent.VK_BACK_SPACE
    GLFW_KEY_TAB -> AwtKeyEvent.VK_TAB
    GLFW_KEY_DELETE -> AwtKeyEvent.VK_DELETE
    GLFW_KEY_ESCAPE -> AwtKeyEvent.VK_ESCAPE
    GLFW_KEY_UP -> AwtKeyEvent.VK_UP
    GLFW_KEY_DOWN -> AwtKeyEvent.VK_DOWN
    GLFW_KEY_LEFT -> AwtKeyEvent.VK_LEFT
    GLFW_KEY_RIGHT -> AwtKeyEvent.VK_RIGHT
    GLFW_KEY_HOME -> AwtKeyEvent.VK_HOME
    GLFW_KEY_END -> AwtKeyEvent.VK_END
    GLFW_KEY_PAGE_UP -> AwtKeyEvent.VK_PAGE_UP
    GLFW_KEY_PAGE_DOWN -> AwtKeyEvent.VK_PAGE_DOWN
    else -> key
}

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
fun runTransparentWindow(
    title: String = "Synara",
    width: Int = 1280,
    height: Int = 720,
    minWidth: Int = GLFW_DONT_CARE,
    minHeight: Int = GLFW_DONT_CARE,
    isDarkTheme: @Composable () -> Boolean = { isSystemInDarkTheme() },
    colorScheme: @Composable () -> ColorScheme = { if (isDarkTheme()) DarkColors else LightColors },
    shapes: @Composable () -> Shapes = { MaterialTheme.shapes },
    typography: @Composable () -> Typography = { MaterialTheme.typography },
    windowBackground: @Composable () -> Color = { MaterialTheme.colorScheme.background.copy(alpha = .6f) },
    content: @Composable () -> Unit
) {
    glfwSetErrorCallback { error, description ->
        println("GLFW Error [$error]: ${org.lwjgl.glfw.GLFWErrorCallback.getDescription(description)}")
    }

    val osName = System.getProperty("os.name").lowercase()
    val isLinux = osName.contains("linux")

    if (isLinux) {
        println("Linux detected. Forcing X11 platform via GLFW hint to bypass Wayland issues.")
        glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11)
    }

    if (!glfwInit()) {
        error("Unable to initialize GLFW")
    }

    val forceDrag = System.getProperty("synara.drag.enabled", "false").toBoolean()
    val showDragHandle = !isLinux || forceDrag

    glfwDefaultWindowHints()
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
    glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
    glfwWindowHint(GLFW_TRANSPARENT_FRAMEBUFFER, GLFW_TRUE)
    glfwWindowHint(GLFW_DECORATED, GLFW_FALSE)
    glfwWindowHint(GLFW_ALPHA_BITS, 8)
    glfwWindowHint(GLFW_STENCIL_BITS, 8)

    val windowHandle = glfwCreateWindow(width, height, title, NULL, NULL)
    if (windowHandle == NULL) {
        error("Failed to create GLFW window")
    }

    glfwSetWindowSizeLimits(windowHandle, minWidth, minHeight, GLFW_DONT_CARE, GLFW_DONT_CARE)

    glfwMakeContextCurrent(windowHandle)
    GL.createCapabilities()
    glfwSwapInterval(1)

    println("GL Vendor: ${GL11.glGetString(GL11.GL_VENDOR)}")
    println("GL Renderer: ${GL11.glGetString(GL11.GL_RENDERER)}")
    println("GL Version: ${GL11.glGetString(GL11.GL_VERSION)}")

    val context = try {
        DirectContext.makeGL()
    } catch (e: Exception) {
        error("Failed to create Skia DirectContext: ${e.message}")
    }

    val density = stackPush().use { stack ->
        val x = stack.mallocFloat(1)
        val y = stack.mallocFloat(1)
        glfwGetWindowContentScale(windowHandle, x, y)
        Density(x.get(0))
    }

    val scene = CanvasLayersComposeScene(
        coroutineContext = Dispatchers.Unconfined,
        density = density,
        invalidate = { }
    )

    stackPush().use { stack ->
        val w = stack.mallocInt(1)
        val h = stack.mallocInt(1)
        glfwGetFramebufferSize(windowHandle, w, h)
        scene.size = IntSize(w.get(0), h.get(0))
    }

    scene.setContent {
        MaterialTheme(
            colorScheme = colorScheme(),
            shapes = shapes(),
            typography = typography()
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = windowBackground()
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
                                    glfwSetWindowPos(windowHandle, x.get(0) + dx.toInt(), y.get(0) + dy.toInt())
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

    glfwSetFramebufferSizeCallback(windowHandle) { _, width, height ->
        scene.size = IntSize(width, height)
    }

    glfwSetCursorPosCallback(windowHandle) { _, x, y ->
        scene.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(x.toFloat(), y.toFloat())
        )
    }

    glfwSetMouseButtonCallback(windowHandle) { _, button, action, _ ->
        val eventType = if (action == GLFW_PRESS) PointerEventType.Press else PointerEventType.Release
        scene.sendPointerEvent(
            eventType = eventType,
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
                else -> null
            }
        )
    }

    glfwSetKeyCallback(windowHandle) { _, key, _, action, mods ->
        val awtAction = when (action) {
            GLFW_PRESS -> AwtKeyEvent.KEY_PRESSED
            GLFW_RELEASE -> AwtKeyEvent.KEY_RELEASED
            GLFW_REPEAT -> AwtKeyEvent.KEY_PRESSED
            else -> return@glfwSetKeyCallback
        }
        val awtMods = glfwModsToAwt(mods)
        val awtKey = glfwKeyToAwt(key)
        val awtEvent = AwtKeyEvent(
            dummyComponent,
            awtAction,
            System.currentTimeMillis(),
            awtMods,
            awtKey,
            AwtKeyEvent.CHAR_UNDEFINED
        )
        scene.sendKeyEvent(KeyEvent(awtEvent))
    }

    glfwSetCharCallback(windowHandle) { _, codepoint ->
        val awtEvent = AwtKeyEvent(
            dummyComponent,
            AwtKeyEvent.KEY_TYPED,
            System.currentTimeMillis(),
            0,
            AwtKeyEvent.VK_UNDEFINED,
            codepoint.toChar()
        )
        scene.sendKeyEvent(KeyEvent(awtEvent))
    }

    glfwShowWindow(windowHandle)

    while (!glfwWindowShouldClose(windowHandle)) {
        val widthArray = IntArray(1)
        val heightArray = IntArray(1)
        glfwGetFramebufferSize(windowHandle, widthArray, heightArray)
        val w = widthArray[0]
        val h = heightArray[0]

        if (w > 0 && h > 0) {
            val renderTarget = BackendRenderTarget.makeGL(w, h, 0, 8, 0, FramebufferFormat.GR_GL_RGBA8)
            val surface = Surface.makeFromBackendRenderTarget(context, renderTarget, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB)

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
    context.close()
    glfwFreeCallbacks(windowHandle)
    glfwDestroyWindow(windowHandle)
    glfwTerminate()
}
