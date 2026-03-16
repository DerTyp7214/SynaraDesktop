package dev.dertyp.synara.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

interface SynaraDispatchers {
    val default: CoroutineDispatcher get() = Dispatchers.Default
    val io: CoroutineDispatcher get() = Dispatchers.IO
    val main: CoroutineDispatcher get() = Dispatchers.Main
    val database: CoroutineDispatcher get() = Dispatchers.IO
    val images: CoroutineDispatcher get() = Dispatchers.Default
    val worker: CoroutineDispatcher get() = Dispatchers.Default

    fun createNamed(name: String, parallel: Int = 1): CoroutineDispatcher = default
}

expect val AppDispatchers: SynaraDispatchers
