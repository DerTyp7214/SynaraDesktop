package dev.dertyp.synara

import androidx.compose.runtime.*

var customAppLocale by mutableStateOf<String?>(null)

expect object LocalAppLocale {
    val current: String @Composable get
    @Composable infix fun provides(value: String?): ProvidedValue<*>
}

@Composable
fun AppEnvironment(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAppLocale provides customAppLocale,
    ) {
        content()
    }
}
