package dev.dertyp.synara

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.dertyp.synara.ui.theme.SynaraTheme
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.*
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.NULL

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
fun main() {
    glfwSetErrorCallback { error, description ->
        println("GLFW Error [$error]: ${org.lwjgl.glfw.GLFWErrorCallback.getDescription(description)}")
    }

    if (!glfwInit()) {
        error("Unable to initialize GLFW")
    }

    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
    glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
    glfwWindowHint(GLFW_TRANSPARENT_FRAMEBUFFER, GLFW_TRUE)
    glfwWindowHint(GLFW_DECORATED, GLFW_FALSE)
    
    // Skia often requires a modern OpenGL context
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
    glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)

    val windowHandle = glfwCreateWindow(1280, 720, "Synara", NULL, NULL)
    if (windowHandle == NULL) {
        error("Failed to create the GLFW window")
    }

    glfwMakeContextCurrent(windowHandle)
    GL.createCapabilities()
    glfwSwapInterval(1)

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

    // Initial size
    stackPush().use { stack ->
        val w = stack.mallocInt(1)
        val h = stack.mallocInt(1)
        glfwGetFramebufferSize(windowHandle, w, h)
        scene.size = IntSize(w.get(0), h.get(0))
    }

    scene.setContent {
        SynaraTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.7f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("Welcome to Synara", style = MaterialTheme.typography.displayMedium)
                }
            }
        }
    }

    // Input Handling
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

    glfwSetScrollCallback(windowHandle) { _, xoffset, yoffset ->
        scene.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = run {
                val x = DoubleArray(1)
                val y = DoubleArray(1)
                glfwGetCursorPos(windowHandle, x, y)
                Offset(x[0].toFloat(), y[0].toFloat())
            },
            scrollDelta = Offset(xoffset.toFloat(), yoffset.toFloat())
        )
    }
    
    glfwSetFramebufferSizeCallback(windowHandle) { _, width, height ->
        scene.size = IntSize(width, height)
    }

    glfwShowWindow(windowHandle)

    while (!glfwWindowShouldClose(windowHandle)) {
        val widthArray = IntArray(1)
        val heightArray = IntArray(1)
        glfwGetFramebufferSize(windowHandle, widthArray, heightArray)
        val width = widthArray[0]
        val height = heightArray[0]

        if (width > 0 && height > 0) {
            val renderTarget = BackendRenderTarget.makeGL(width, height, 0, 8, 0, FramebufferFormat.GR_GL_SRGB8_ALPHA8)
            val surface = Surface.makeFromBackendRenderTarget(context, renderTarget, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB)

            surface?.let {
                it.canvas.clear(Color.TRANSPARENT)
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
