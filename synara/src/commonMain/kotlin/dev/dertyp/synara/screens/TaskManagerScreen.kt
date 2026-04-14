package dev.dertyp.synara.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.core.roundToNDecimals
import dev.dertyp.synara.formatBytes
import dev.dertyp.synara.ui.LocalDetachedWindowActions
import dev.dertyp.synara.ui.SynaraIcons
import dev.dertyp.synara.ui.models.MemoryPoolStat
import dev.dertyp.synara.ui.models.PerformanceMonitor
import dev.dertyp.synara.ui.models.PerformanceStats
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.Res
import synara.synara.generated.resources.available_processors
import synara.synara.generated.resources.back
import synara.synara.generated.resources.caches
import synara.synara.generated.resources.cpu
import synara.synara.generated.resources.heap_max
import synara.synara.generated.resources.heap_used
import synara.synara.generated.resources.image_cache
import synara.synara.generated.resources.items_count
import synara.synara.generated.resources.jvm_direct
import synara.synara.generated.resources.jvm_total
import synara.synara.generated.resources.memory
import synara.synara.generated.resources.memory_pools
import synara.synara.generated.resources.native_other
import synara.synara.generated.resources.non_heap_used
import synara.synara.generated.resources.particle_count
import synara.synara.generated.resources.particle_fps
import synara.synara.generated.resources.particle_renderer
import synara.synara.generated.resources.per_core
import synara.synara.generated.resources.physical_memory
import synara.synara.generated.resources.process_load
import synara.synara.generated.resources.song_cache
import synara.synara.generated.resources.song_cache_memory
import synara.synara.generated.resources.system_load
import synara.synara.generated.resources.task_manager
import synara.synara.generated.resources.thread_id
import synara.synara.generated.resources.threads
import synara.synara.generated.resources.total

class TaskManagerScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val detachedActions = LocalDetachedWindowActions.current
        val monitor = koinInject<PerformanceMonitor>()
        val stats by monitor.stats.collectAsState()

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(Res.string.task_manager)) },
                    navigationIcon = {
                        if (navigator != null && (navigator.canPop || detachedActions != null)) {
                            IconButton(onClick = { 
                                if (navigator.canPop) {
                                    navigator.pop()
                                } else {
                                    detachedActions?.close()
                                }
                            }) {
                                Icon(
                                    SynaraIcons.Back.get(),
                                    contentDescription = stringResource(Res.string.back)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SectionTitle(stringResource(Res.string.memory))
                    MemoryInfo(stats)
                }
                item {
                    SectionTitle(stringResource(Res.string.memory_pools))
                }
                items(stats.memoryPools) { pool ->
                    MemoryPoolItem(pool)
                }
                item {
                    SectionTitle(stringResource(Res.string.cpu))
                    CpuInfo(stats)
                }
                item {
                    SectionTitle(stringResource(Res.string.particle_renderer))
                    ParticleInfo(stats)
                }
                item {
                    SectionTitle(stringResource(Res.string.caches))
                    CacheInfo(stats)
                }
                item {
                    SectionTitle("${stringResource(Res.string.threads)} (${stats.activeThreads})")
                }
                items(stats.threadStats) { thread ->
                    ThreadItem(thread)
                }
            }
        }
    }

    @Composable
    private fun SectionTitle(title: String) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    @Composable
    private fun MemoryInfo(stats: PerformanceStats) {
        Card {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                InfoRow(stringResource(Res.string.physical_memory), stats.rssMemory.formatBytes())
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                InfoRow(stringResource(Res.string.jvm_total), stats.totalMemory.formatBytes())
                InfoRow(stringResource(Res.string.jvm_direct), stats.directMemory.formatBytes())
                InfoRow(stringResource(Res.string.native_other), stats.nativeMemory.formatBytes())
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                InfoRow(stringResource(Res.string.heap_used), stats.heapMemory.formatBytes())
                InfoRow(stringResource(Res.string.heap_max), stats.heapMemoryMax.formatBytes())
                InfoRow(stringResource(Res.string.non_heap_used), stats.nonHeapMemory.formatBytes())
                
                LinearProgressIndicator(
                    progress = { if (stats.heapMemoryMax > 0) stats.heapMemory.toFloat() / stats.heapMemoryMax else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }

    @Composable
    private fun CpuInfo(stats: PerformanceStats) {
        Card {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                InfoRow("${stringResource(Res.string.process_load)} (${stringResource(Res.string.total)})", "${(stats.processCpuLoad * 100).roundToNDecimals(1)}%")
                InfoRow("${stringResource(Res.string.process_load)} (${stringResource(Res.string.per_core)})", "${(stats.processCpuLoad * stats.availableProcessors * 100).roundToNDecimals(1)}%")
                InfoRow(stringResource(Res.string.system_load), "${(stats.systemCpuLoad * 100).roundToNDecimals(1)}%")
                InfoRow(stringResource(Res.string.available_processors), stats.availableProcessors.toString())
                
                LinearProgressIndicator(
                    progress = { (stats.processCpuLoad * 10.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }

    @Composable
    private fun ParticleInfo(stats: PerformanceStats) {
        Card {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                InfoRow(stringResource(Res.string.particle_count), stats.particleCount.toString())
                InfoRow(stringResource(Res.string.particle_fps), "${stats.particleFps} / ${stats.maxFps} FPS")

                LinearProgressIndicator(
                    progress = { if (stats.maxFps > 0) stats.particleFps.toFloat() / stats.maxFps else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }

    @Composable
    private fun CacheInfo(stats: PerformanceStats) {
        Card {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                InfoRow(stringResource(Res.string.image_cache), stats.imageCacheSize.formatBytes())
                InfoRow(stringResource(Res.string.song_cache), stringResource(Res.string.items_count, stats.songCacheSize))
                InfoRow(stringResource(Res.string.song_cache_memory), stats.songCacheMemory.formatBytes())
            }
        }
    }

    @Composable
    private fun MemoryPoolItem(pool: MemoryPoolStat) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(pool.name, style = MaterialTheme.typography.bodyMedium)
                        Text(pool.type, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(pool.used.formatBytes(), style = MaterialTheme.typography.bodyMedium)
                }
                if (pool.max > 0) {
                    LinearProgressIndicator(
                        progress = { pool.used.toFloat() / pool.max },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun ThreadItem(thread: dev.dertyp.synara.ui.models.ThreadStat) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(thread.name, style = MaterialTheme.typography.bodyMedium)
                    Text("${stringResource(Res.string.thread_id)}: ${thread.id} | ${thread.state}", style = MaterialTheme.typography.labelSmall)
                }
                if (thread.cpuUsage > 0) {
                    Text("${(thread.cpuUsage * 100).roundToNDecimals(1)}%", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    @Composable
    private fun InfoRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
