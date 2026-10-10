package dev.dertyp.synara.ui.server

import dev.dertyp.randomPlatformUUID
import dev.dertyp.ui.IntakeItem
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiEntityType
import dev.dertyp.ui.UiValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class UiActionConfirmTextTest {

    private val invoke = UiAction.Invoke("c", "a")
    private val intake = UiAction.Intake(listOf(IntakeItem.Text("abc")))

    @Test
    fun nullConfirmTextReturnsTheSameInvoke() {
        assertSame(invoke, invoke.withConfirmText(null))
    }

    @Test
    fun emptyConfirmTextReturnsTheSameInvoke() {
        assertSame(invoke, invoke.withConfirmText(""))
    }

    @Test
    fun blankConfirmTextReturnsTheSameInvoke() {
        assertSame(invoke, invoke.withConfirmText("   "))
    }

    @Test
    fun nullConfirmTextReturnsTheSameIntake() {
        assertSame(intake, intake.withConfirmText(null))
    }

    @Test
    fun blankConfirmTextReturnsTheSameIntake() {
        assertSame(intake, intake.withConfirmText("  "))
    }

    @Test
    fun invokeWithoutOwnConfirmTextReceivesTheText() {
        assertEquals(invoke.copy(confirmText = "Sure?"), invoke.withConfirmText("Sure?"))
    }

    @Test
    fun invokeWithOwnConfirmTextKeepsIt() {
        val own = invoke.copy(confirmText = "Own")

        assertSame(own, own.withConfirmText("Sure?"))
    }

    @Test
    fun intakeWithoutOwnConfirmTextReceivesTheText() {
        assertEquals(intake.copy(confirmText = "Sure?"), intake.withConfirmText("Sure?"))
    }

    @Test
    fun intakeWithOwnConfirmTextKeepsIt() {
        val own = intake.copy(confirmText = "Own")

        assertSame(own, own.withConfirmText("Sure?"))
    }

    @Test
    fun invokeKeepsAllItsOtherFieldsWhenReceivingTheText() {
        val action = UiAction.Invoke("c", "a", params = mapOf("p" to UiValue.of("1")), formId = "form")
        val result = action.withConfirmText("Sure?")

        assertEquals(action.copy(confirmText = "Sure?"), result)
    }

    @Test
    fun otherActionsAreReturnedUnchanged() {
        val others = listOf(
            UiAction.OpenPage("page"),
            UiAction.OpenUrl("https://example.org"),
            UiAction.OpenEntity(UiEntityType.SONG, randomPlatformUUID()),
            UiAction.OpenNative("externalSearch"),
            UiAction.OpenMenu(emptyList()),
            UiAction.Refresh,
            UiAction.DismissKeyboard,
        )

        others.forEach { assertSame(it, it.withConfirmText("Sure?")) }
    }
}
