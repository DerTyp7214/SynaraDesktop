package dev.dertyp.synara.testing

import dev.dertyp.synara.utils.SynaraDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

object UnconfinedDispatchers : SynaraDispatchers {
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val database: CoroutineDispatcher = Dispatchers.Unconfined
    override val images: CoroutineDispatcher = Dispatchers.Unconfined
    override val worker: CoroutineDispatcher = Dispatchers.Unconfined

    override fun createNamed(name: String, parallel: Int): CoroutineDispatcher = Dispatchers.Unconfined
}

class TestDispatchers(private val dispatcher: CoroutineDispatcher) : SynaraDispatchers {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
    override val database: CoroutineDispatcher = dispatcher
    override val images: CoroutineDispatcher = dispatcher
    override val worker: CoroutineDispatcher = dispatcher

    override fun createNamed(name: String, parallel: Int): CoroutineDispatcher = dispatcher
}
