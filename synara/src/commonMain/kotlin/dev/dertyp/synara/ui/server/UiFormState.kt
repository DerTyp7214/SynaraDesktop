package dev.dertyp.synara.ui.server

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dertyp.ui.UiValue

@Stable
class UiFormState(val id: String) {
    val values = mutableStateMapOf<String, UiValue>()
    val errors = mutableStateMapOf<String, String>()
    var busy by mutableStateOf(false)
        internal set

    private val initial = mutableMapOf<String, UiValue>()

    fun seed(key: String, value: UiValue) {
        initial[key] = value
        if (!values.containsKey(key)) values[key] = value
    }

    fun set(key: String, value: UiValue) {
        values[key] = value
        errors.remove(key)
    }

    fun text(key: String): String? = values[key]?.text

    fun number(key: String): Double? = values[key]?.number

    fun flag(key: String): Boolean? = values[key]?.flag

    fun reset() {
        values.clear()
        values.putAll(initial)
        errors.clear()
    }

    fun binding(): UiFormBinding = UiFormBinding(
        formId = id,
        values = { values.toMap() },
        onFieldErrors = { errors.clear(); errors.putAll(it) },
        onBusy = { busy = it },
    )
}

val LocalUiFormState = compositionLocalOf<UiFormState?> { null }
