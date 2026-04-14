package dev.dertyp.synara.ui.models

import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.sun.management.OperatingSystemMXBean
import dev.dertyp.synara.player.SongCache
import dev.dertyp.synara.utils.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds

actual class PerformanceMonitor actual constructor(private val songCache: SongCache) {
    private val scope = CoroutineScope(AppDispatchers.worker + SupervisorJob())
    private val memoryMXBean = ManagementFactory.getMemoryMXBean()
    private val osMXBean = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
    private val threadMXBean = ManagementFactory.getThreadMXBean()
    private val bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
    private val memoryPools = ManagementFactory.getMemoryPoolMXBeans()

    private var currentParticleCount = 0
    private var currentParticleFps = 0
    private var currentMaxFps = 60

    private val _isObserved = MutableStateFlow(false)
    actual val isObserved: StateFlow<Boolean> = _isObserved.asStateFlow()

    actual fun updateParticleStats(count: Int, fps: Int) {
        currentParticleCount = count
        currentParticleFps = fps
    }

    actual fun updateMaxFps(fps: Int) {
        if (fps > 0) currentMaxFps = fps
    }

    private fun getResidentSetSize(): Long {
        try {
            val statusFile = File("/proc/self/status")
            if (statusFile.exists()) {
                val rssLine = statusFile.readLines().find { it.startsWith("VmRSS:") }
                if (rssLine != null) {
                    val value = rssLine.substringAfter("VmRSS:").substringBefore("kB").trim().toLong()
                    return value * 1024L
                }
            }
        } catch (_: Exception) { }
        return memoryMXBean.heapMemoryUsage.used + memoryMXBean.nonHeapMemoryUsage.used
    }

    actual val stats: StateFlow<PerformanceStats> = flow {
        _isObserved.value = true
        try {
            if (threadMXBean.isThreadCpuTimeSupported && !threadMXBean.isThreadCpuTimeEnabled) {
                threadMXBean.isThreadCpuTimeEnabled = true
            }

            val threadCpuTimes = mutableMapOf<Long, Long>()
            var lastWallTime = System.nanoTime()

            while (true) {
                delay(1.seconds)
                val currentWallTime = System.nanoTime()
                val wallDiff = (currentWallTime - lastWallTime).toDouble()
                lastWallTime = currentWallTime

                if (wallDiff <= 0) continue

                val heapUsage = memoryMXBean.heapMemoryUsage
                val nonHeapUsage = memoryMXBean.nonHeapMemoryUsage
                
                val threadIds = threadMXBean.allThreadIds.toList()
                var totalProcessCpuTimeDiff = 0.0
                
                val allThreadStats = threadIds.map { id ->
                    val info = threadMXBean.getThreadInfo(id)
                    val currentCpuTime = threadMXBean.getThreadCpuTime(id)
                    
                    if (info == null || currentCpuTime == -1L) return@map null
                    
                    val lastTime = threadCpuTimes[id]
                    threadCpuTimes[id] = currentCpuTime
                    
                    val cpuUsage = if (lastTime != null && lastTime != -1L) {
                        val diff = (currentCpuTime - lastTime).toDouble()
                        if (diff > 0) {
                            totalProcessCpuTimeDiff += diff
                            diff / wallDiff
                        } else 0.0
                    } else 0.0
                    
                    ThreadStat(
                        id = id,
                        name = info.threadName,
                        state = info.threadState.name,
                        cpuUsage = cpuUsage
                    )
                }.filterNotNull()

                val currentIdSet = threadIds.toSet()
                threadCpuTimes.keys.retainAll { it in currentIdSet }

                val threadStats = allThreadStats.sortedByDescending { it.cpuUsage }.take(20)

                val availableProcessors = Runtime.getRuntime().availableProcessors()
                val osProcessLoad = osMXBean.processCpuLoad
                val processCpuLoad = if (osProcessLoad >= 0) osProcessLoad else (totalProcessCpuTimeDiff / wallDiff / availableProcessors)
                
                val systemCpuLoad = osMXBean.cpuLoad.coerceIn(0.0, 1.0)

                val imageLoader = SingletonImageLoader.get(PlatformContext.INSTANCE)
                val imageCacheSize = imageLoader.memoryCache?.size ?: 0L

                val rss = getResidentSetSize()
                val jvmTotal = heapUsage.used + nonHeapUsage.used
                val direct = bufferPools.sumOf { it.memoryUsed }
                
                val poolStats = memoryPools.map { pool ->
                    MemoryPoolStat(
                        name = pool.name,
                        type = pool.type.name,
                        used = pool.usage.used,
                        max = pool.usage.max
                    )
                }

                val stats = PerformanceStats(
                    rssMemory = rss,
                    totalMemory = jvmTotal,
                    heapMemory = heapUsage.used,
                    heapMemoryMax = heapUsage.max,
                    nonHeapMemory = nonHeapUsage.used,
                    directMemory = direct,
                    nativeMemory = (rss - jvmTotal - direct).coerceAtLeast(0L),
                    memoryPools = poolStats,
                    processCpuLoad = processCpuLoad,
                    systemCpuLoad = systemCpuLoad,
                    activeThreads = threadMXBean.threadCount,
                    threadStats = threadStats,
                    imageCacheSize = imageCacheSize,
                    songCacheSize = songCache.size,
                    songCacheMemory = songCache.getEstimatedMemoryUsage(),
                    availableProcessors = availableProcessors,
                    particleCount = currentParticleCount,
                    particleFps = currentParticleFps,
                    maxFps = currentMaxFps
                )
                emit(stats)
            }
        } finally {
            _isObserved.value = false
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PerformanceStats(0, 0, 0, 0, 0, 0, 0, emptyList(), 0.0, 0.0, 0, emptyList(), 0, 0, 0L, Runtime.getRuntime().availableProcessors())
    )
}
