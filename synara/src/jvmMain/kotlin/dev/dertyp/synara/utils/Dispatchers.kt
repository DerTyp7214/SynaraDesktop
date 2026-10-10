package dev.dertyp.synara.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

private class NamedThreadFactory(private val prefix: String) : ThreadFactory {
    private val threadCount = AtomicInteger(1)
    override fun newThread(runnable: Runnable): Thread {
        return Thread(runnable, "$prefix-${threadCount.getAndIncrement()}")
    }
}

object UiDispatcher : CoroutineDispatcher() {
    @Volatile
    var delegate: CoroutineDispatcher = Dispatchers.Main

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = delegate.isDispatchNeeded(context)

    override fun dispatch(context: CoroutineContext, block: Runnable) = delegate.dispatch(context, block)
}

actual val AppDispatchers: SynaraDispatchers = object : SynaraDispatchers {
    override val io: CoroutineDispatcher =
        Executors.newCachedThreadPool(NamedThreadFactory("Synara-IO")).asCoroutineDispatcher()

    override val main: CoroutineDispatcher = UiDispatcher

    override val database: CoroutineDispatcher =
        Executors.newFixedThreadPool(3, NamedThreadFactory("Synara-DB")).asCoroutineDispatcher()

    override val images: CoroutineDispatcher =
        Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceAtLeast(2),
            NamedThreadFactory("Synara-Image")
        ).asCoroutineDispatcher()

    override val worker: CoroutineDispatcher =
        Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceAtLeast(2),
            NamedThreadFactory("Synara-Worker")
        ).asCoroutineDispatcher()

    override fun createNamed(name: String, parallel: Int): CoroutineDispatcher {
        return if (parallel == 1) {
            Executors.newSingleThreadExecutor(NamedThreadFactory(name)).asCoroutineDispatcher()
        } else {
            Executors.newFixedThreadPool(parallel, NamedThreadFactory(name)).asCoroutineDispatcher()
        }
    }
}
