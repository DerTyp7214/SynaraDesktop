package dev.dertyp.synara.ui.server

import dev.dertyp.ui.UiValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiFormStateTest {

    private val form = UiFormState("form")

    @Test
    fun seedFillsAMissingKey() {
        form.seed("name", UiValue.of("a"))

        assertEquals(UiValue.of("a"), form.values["name"])
    }

    @Test
    fun seedKeepsAnExistingValue() {
        form.set("name", UiValue.of("typed"))
        form.seed("name", UiValue.of("seeded"))

        assertEquals(UiValue.of("typed"), form.values["name"])
    }

    @Test
    fun seedRecordsTheInitialValueEvenWhenTheKeyAlreadyHasOne() {
        form.set("name", UiValue.of("typed"))
        form.seed("name", UiValue.of("seeded"))
        form.reset()

        assertEquals(UiValue.of("seeded"), form.values["name"])
    }

    @Test
    fun resetRestoresSeededValuesAndDropsOtherKeys() {
        form.seed("name", UiValue.of("a"))
        form.seed("count", UiValue.of(1))
        form.set("name", UiValue.of("changed"))
        form.set("extra", UiValue.of(true))
        form.reset()

        assertEquals(mapOf("name" to UiValue.of("a"), "count" to UiValue.of(1)), form.values.toMap())
    }

    @Test
    fun resetClearsTheErrors() {
        form.seed("name", UiValue.of("a"))
        form.errors["name"] = "required"
        form.reset()

        assertTrue(form.errors.isEmpty())
    }

    @Test
    fun resetOfAnUnseededFormEmptiesIt() {
        form.set("name", UiValue.of("a"))
        form.reset()

        assertTrue(form.values.isEmpty())
    }

    @Test
    fun setStoresTheValue() {
        form.set("name", UiValue.of("a"))

        assertEquals(UiValue.of("a"), form.values["name"])
    }

    @Test
    fun setClearsTheErrorOfThatKeyOnly() {
        form.errors["name"] = "required"
        form.errors["age"] = "too low"
        form.set("name", UiValue.of("a"))

        assertEquals(mapOf("age" to "too low"), form.errors.toMap())
    }

    @Test
    fun textReadsTheTextOfTheValue() {
        form.set("name", UiValue.of("a"))

        assertEquals("a", form.text("name"))
    }

    @Test
    fun numberReadsTheNumberOfTheValue() {
        form.set("count", UiValue.of(2.5))

        assertEquals(2.5, form.number("count"))
    }

    @Test
    fun flagReadsTheFlagOfTheValue() {
        form.set("on", UiValue.of(true))

        assertEquals(true, form.flag("on"))
    }

    @Test
    fun readersReturnNullForTheWrongKindOfValue() {
        form.set("name", UiValue.of("a"))
        form.set("count", UiValue.of(2))
        form.set("on", UiValue.of(false))

        assertNull(form.number("name"))
        assertNull(form.flag("name"))
        assertNull(form.text("count"))
        assertNull(form.flag("count"))
        assertNull(form.text("on"))
        assertNull(form.number("on"))
    }

    @Test
    fun readersReturnNullForAMissingKey() {
        assertNull(form.text("missing"))
        assertNull(form.number("missing"))
        assertNull(form.flag("missing"))
    }

    @Test
    fun bindingCarriesTheFormId() {
        assertEquals("form", form.binding().formId)
    }

    @Test
    fun bindingValuesAreASnapshotCopy() {
        form.set("name", UiValue.of("a"))
        val binding = form.binding()
        val snapshot = binding.values()
        form.set("name", UiValue.of("b"))
        form.set("other", UiValue.of("c"))

        assertEquals(mapOf("name" to UiValue.of("a")), snapshot)
        assertEquals(mapOf("name" to UiValue.of("b"), "other" to UiValue.of("c")), binding.values())
    }

    @Test
    fun bindingFieldErrorsReplaceTheErrors() {
        form.errors["name"] = "old"
        form.errors["age"] = "old"
        form.binding().onFieldErrors(mapOf("name" to "new"))

        assertEquals(mapOf("name" to "new"), form.errors.toMap())
    }

    @Test
    fun bindingEmptyFieldErrorsClearTheErrors() {
        form.errors["name"] = "old"
        form.binding().onFieldErrors(emptyMap())

        assertTrue(form.errors.isEmpty())
    }

    @Test
    fun bindingBusySetsAndClearsTheBusyFlag() {
        val binding = form.binding()
        assertFalse(form.busy)

        binding.onBusy(true)
        assertTrue(form.busy)

        binding.onBusy(false)
        assertFalse(form.busy)
    }
}
