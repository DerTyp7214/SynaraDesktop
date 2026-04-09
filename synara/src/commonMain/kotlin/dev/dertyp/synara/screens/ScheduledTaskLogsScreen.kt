package dev.dertyp.synara.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.dertyp.currentTimeMillis
import dev.dertyp.data.ScheduledTaskLog
import dev.dertyp.data.TaskStatus
import dev.dertyp.services.IScheduledTaskLogService
import dev.dertyp.synara.formatBytes
import dev.dertyp.synara.formatCompactDuration
import dev.dertyp.synara.formatDateTime
import dev.dertyp.synara.ui.SynaraIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import synara.synara.generated.resources.*

private val SuccessGreen = Color(0xFF4CAF50)
private val WarningOrange = Color(0xFFFF9800)

class ScheduledTaskLogsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val taskLogService: IScheduledTaskLogService = koinInject()

        val groupedLogs by remember {
            taskLogService.getGroupedLogsFlow().map { logs ->
                logs.toSortedMap().mapValues { entry ->
                    entry.value.sortedByDescending { it.startTime }
                }
            }
        }.collectAsState(initial = null)

        var expandedGroups by rememberSaveable { mutableStateOf(setOf<String>()) }
        var expandedLogs by rememberSaveable { mutableStateOf(setOf<String>()) }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(Res.string.settings_scheduled_task_logs_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(
                                SynaraIcons.Back.get(),
                                contentDescription = stringResource(Res.string.back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            }
        ) { innerPadding ->
            if (groupedLogs == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    groupedLogs!!.forEach { (group, logs) ->
                        val isExpanded = expandedGroups.contains(group)

                        item(key = group) {
                            val rotation by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "rotation")
                            Surface(
                                onClick = {
                                    expandedGroups = if (isExpanded) expandedGroups - group else expandedGroups + group
                                },
                                color = Color.Transparent,
                                modifier = Modifier.animateItem()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = group,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = pluralStringResource(Res.plurals.settings_scheduled_task_logs_entries, logs.size, logs.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (logs.isNotEmpty()) {
                                            val statusColor = when {
                                                logs.any { it.status == TaskStatus.FAILURE } -> {
                                                    if (logs.all { it.status == TaskStatus.FAILURE }) MaterialTheme.colorScheme.error
                                                    else WarningOrange
                                                }
                                                logs.any { it.status == TaskStatus.RUNNING } -> MaterialTheme.colorScheme.primary
                                                else -> SuccessGreen
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(statusColor, CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(16.dp))
                                        }
                                        Icon(
                                            imageVector = SynaraIcons.ChevronDown.get(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .rotate(rotation),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .animateItem(),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }

                        if (isExpanded) {
                            items(logs, key = { it.id.toString() }) { log ->
                                val logIdString = log.id.toString()
                                TaskLogItem(
                                    log = log,
                                    expanded = expandedLogs.contains(logIdString),
                                    onExpandToggle = {
                                        expandedLogs = if (expandedLogs.contains(logIdString)) {
                                            expandedLogs - logIdString
                                        } else {
                                            expandedLogs + logIdString
                                        }
                                    },
                                    modifier = Modifier.animateItem()
                                )
                                HorizontalDivider(
                                    modifier = Modifier
                                        .padding(horizontal = 32.dp)
                                        .animateItem(),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TaskLogItem(
        log: ScheduledTaskLog,
        expanded: Boolean,
        onExpandToggle: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val statusColor = when (log.status) {
            TaskStatus.SUCCESS -> SuccessGreen
            TaskStatus.FAILURE -> MaterialTheme.colorScheme.error
            TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        }

        Column(
            modifier = modifier
                .fillMaxWidth()
                .clickable { onExpandToggle() }
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = log.taskName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = log.startTime.formatDateTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    color = statusColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (log.status == TaskStatus.RUNNING) {
                            if (log.progress > 0.0) {
                                CircularProgressIndicator(
                                    progress = { (log.progress / 100.0).toFloat() },
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = statusColor
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = statusColor
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = when (log.status) {
                                TaskStatus.SUCCESS -> stringResource(Res.string.task_status_success)
                                TaskStatus.FAILURE -> stringResource(Res.string.task_status_failure)
                                TaskStatus.RUNNING -> stringResource(Res.string.task_status_running)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    if (log.message != null) {
                        Text(
                            text = log.message!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (log.status == TaskStatus.FAILURE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (log.status == TaskStatus.RUNNING && log.progress > 0.0) {
                        val percentage = log.progress.toInt()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(Res.string.settings_scheduled_task_logs_progress),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$percentage%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (log.progress / 100.0).toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .height(4.dp),
                            strokeCap = StrokeCap.Round,
                            color = statusColor,
                            trackColor = statusColor.copy(alpha = 0.2f)
                        )
                    }

                    TaskDurationRow(log = log)

                    if (log.logs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                log.logs.forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    
                    if (log.details != null && log.details!!.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(Res.string.settings_scheduled_task_logs_details),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                log.details!!.forEach { (key, value) ->
                                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                        Text(
                                            text = "$key: ",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = if (key == "size") value.toLongOrNull()?.formatBytes() ?: value else value,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TaskDurationRow(log: ScheduledTaskLog) {
        if (log.status != TaskStatus.RUNNING) {
            InfoRow(
                label = stringResource(Res.string.settings_scheduled_task_logs_duration),
                value = (log.endTime - log.startTime).coerceAtLeast(0L).formatCompactDuration()
            )
            return
        }

        var currentTime by remember { mutableLongStateOf(currentTimeMillis()) }

        LaunchedEffect(log.startTime) {
            while (true) {
                currentTime = currentTimeMillis()
                delay(1000)
            }
        }

        InfoRow(
            label = stringResource(Res.string.settings_scheduled_task_logs_duration),
            value = (currentTime - log.startTime).coerceAtLeast(0L).formatCompactDuration()
        )
    }

    @Composable
    private fun InfoRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
