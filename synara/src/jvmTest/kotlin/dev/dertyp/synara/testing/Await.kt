package dev.dertyp.synara.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = runBlocking {
    withTimeout(5.seconds) { first(predicate) }
}

fun <T> Flow<T>.awaitFirst(predicate: (T) -> Boolean): T = runBlocking {
    withTimeout(5.seconds) { first(predicate) }
}
