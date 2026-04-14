package dev.dertyp.synara.ui.models

import dev.dertyp.synara.player.SongCache
import kotlinx.coroutines.flow.StateFlow

data class ThreadStat(
    val id: Long,
    val name: String,
    val state: String,
    val cpuUsage: Double
)

data class MemoryPoolStat(
    val name: String,
    val type: String,
    val used: Long,
    val max: Long
)

data class PerformanceStats(
    val rssMemory: Long,
    val totalMemory: Long,
    val heapMemory: Long,
    val heapMemoryMax: Long,
    val nonHeapMemory: Long,
    val directMemory: Long,
    val nativeMemory: Long,
    val memoryPools: List<MemoryPoolStat>,
    val processCpuLoad: Double,
    val systemCpuLoad: Double,
    val activeThreads: Int,
    val threadStats: List<ThreadStat>,
    val imageCacheSize: Long,
    val songCacheSize: Int,
    val songCacheMemory: Long,
    val availableProcessors: Int,
    val particleCount: Int = 0,
    val particleFps: Int = 0,
    val maxFps: Int = 60
)

expect class PerformanceMonitor(songCache: SongCache) {
    val stats: StateFlow<PerformanceStats>
    val isObserved: StateFlow<Boolean>

    fun updateParticleStats(count: Int, fps: Int)
    fun updateMaxFps(fps: Int)
}
