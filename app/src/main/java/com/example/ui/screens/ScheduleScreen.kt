package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.components.LiveSignalPill
import com.example.ui.theme.HealthEmerald
import com.example.ui.theme.TelemetryMonoFont
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScheduleScreen(
    userId: String,
    profile: UserProfile?,
    settings: SystemSettings,
    videosState: UiState<List<VideoAsset>>,
    destinationsState: UiState<List<StreamDestination>>,
    schedulesState: UiState<List<ScheduledStream>>,
    streamsState: UiState<List<StreamSession>>,
    onSaveSchedule: (ScheduledStream) -> Unit,
    onDuplicateSchedule: (ScheduledStream) -> Unit,
    onCancelSchedule: (String) -> Unit,
    onStopCloudStream: (StreamSession) -> Unit,
    onUpgradeClick: () -> Unit
) {
    val videos = (videosState as? UiState.Success)?.data.orEmpty()
        .filter { it.parsedStage == VideoProcessingStage.READY }
    val destinations = (destinationsState as? UiState.Success)?.data.orEmpty()
    val runningCloudStreams = (streamsState as? UiState.Success)?.data.orEmpty()
        .filter { it.parsedSourceType == StreamSourceType.CLOUD_VIDEO && (it.parsedStatus == StreamStatus.RUNNING || it.parsedStatus == StreamStatus.RECONNECTING) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<ScheduledStream?>(null) }

    if (showCreateDialog) {
        var title by remember { mutableStateOf(editingSchedule?.title ?: "24/7 Cloud Stream") }
        var selectedVideo by remember {
            mutableStateOf(videos.firstOrNull { it.id == editingSchedule?.videoId } ?: videos.firstOrNull())
        }
        var startIso by remember {
            val defaultIso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(Date(System.currentTimeMillis() + 3600_000L))
            mutableStateOf(editingSchedule?.scheduledStartIso ?: defaultIso)
        }
        var durationHours by remember { mutableIntStateOf(editingSchedule?.durationHours?.toInt() ?: 168) }
        var loopMode by remember { mutableStateOf(editingSchedule?.parsedLoopMode ?: LoopMode.UNLIMITED) }
        var recurrence by remember { mutableStateOf(editingSchedule?.parsedRecurrence ?: ScheduleRecurrence.ONCE) }
        var selectedDestNames by remember {
            mutableStateOf(
                editingSchedule?.destinationNames?.toSet()
                    ?: destinations.take(1).map { it.name }.toSet()
            )
        }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(if (editingSchedule == null) "Schedule Cloud Stream" else "Edit Schedule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Stream Title") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = startIso,
                        onValueChange = { startIso = it },
                        label = { Text("Start (YYYY-MM-DD HH:mm)") },
                        singleLine = true
                    )

                    Text("Prerecorded Video", style = MaterialTheme.typography.labelMedium)
                    if (videos.isEmpty()) {
                        Text(
                            "Upload and validate a video in the Videos tab first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(videos, key = { it.id }) { v ->
                                FilterChip(
                                    selected = selectedVideo?.id == v.id,
                                    onClick = { selectedVideo = v },
                                    label = { Text(v.title) }
                                )
                            }
                        }
                    }

                    Text("Duration (Up to 7 Days)", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf(1, 6, 12, 24, 48, 72, 168)) { hrs ->
                            FilterChip(
                                selected = durationHours == hrs,
                                onClick = { durationHours = hrs },
                                label = { Text(if (hrs == 168) "7 Days" else "${hrs}h") }
                            )
                        }
                    }

                    Text("Loop Video", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(LoopMode.entries) { m ->
                            FilterChip(
                                selected = loopMode == m,
                                onClick = { loopMode = m },
                                label = { Text(m.label) }
                            )
                        }
                    }

                    Text("Recurrence", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ScheduleRecurrence.entries.forEach { r ->
                            FilterChip(
                                selected = recurrence == r,
                                onClick = { recurrence = r },
                                label = { Text(r.label) }
                            )
                        }
                    }

                    if (destinations.isNotEmpty()) {
                        Text("Destinations", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(destinations, key = { it.id }) { d ->
                                FilterChip(
                                    selected = selectedDestNames.contains(d.name),
                                    onClick = {
                                        selectedDestNames = if (selectedDestNames.contains(d.name)) {
                                            selectedDestNames - d.name
                                        } else {
                                            selectedDestNames + d.name
                                        }
                                    },
                                    label = { Text(d.name) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedVideo != null && title.isNotBlank(),
                    onClick = {
                        val v = selectedVideo ?: return@Button
                        onSaveSchedule(
                            ScheduledStream(
                                id = editingSchedule?.id ?: "",
                                userId = userId,
                                title = title.trim(),
                                videoId = v.id,
                                videoTitle = v.title,
                                scheduledStartIso = startIso,
                                scheduledEpochMs = System.currentTimeMillis() + 3600_000L,
                                durationHours = durationHours.toLong().coerceAtMost(settings.maxCloudDurationHours),
                                loopMode = loopMode.name,
                                recurrence = recurrence.name,
                                destinationNames = selectedDestNames.toList().ifEmpty { listOf("YouTube Live") },
                                status = ScheduleStatus.SCHEDULED.name
                            )
                        )
                        showCreateDialog = false
                    }
                ) { Text("Save Schedule") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Button(
                onClick = {
                    if (profile?.isPremiumOrAdmin != true) {
                        onUpgradeClick()
                    } else {
                        editingSchedule = null
                        showCreateDialog = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("create_schedule_button")
            ) {
                Icon(Icons.Filled.EventAvailable, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (profile?.isPremiumOrAdmin == true) "SCHEDULE STREAM (UP TO 7 DAYS)"
                    else "Upgrade to Schedule Cloud Streams",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Running Cloud Workers Section
        if (runningCloudStreams.isNotEmpty()) {
            item {
                Text("Active Cloud Streaming Workers", style = MaterialTheme.typography.titleMedium)
            }
            items(runningCloudStreams, key = { it.id }) { stream ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, HealthEmerald.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LiveSignalPill(label = "CLOUD RUNNING")
                                Text(stream.title, style = MaterialTheme.typography.titleSmall)
                            }
                            OutlinedButton(onClick = { onStopCloudStream(stream) }) {
                                Text("Stop")
                            }
                        }
                        Text(
                            "Worker: ${stream.workerStatus} • CPU ${stream.workerCpuPercent}% • RAM ${stream.workerRamPercent}% • Loop: ${stream.parsedLoopMode.label}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFont),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Continues streaming even if the Android app is closed. Auto-reconnect enabled on worker crash.",
                            style = MaterialTheme.typography.bodySmall,
                            color = HealthEmerald
                        )
                    }
                }
            }
        }

        item {
            Text("Scheduled Streams", style = MaterialTheme.typography.titleMedium)
        }

        when (schedulesState) {
            is UiState.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is UiState.Error -> item {
                Text(schedulesState.message, color = MaterialTheme.colorScheme.error)
            }
            is UiState.Success -> {
                if (schedulesState.data.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("No Scheduled Streams", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Schedule one-time or recurring cloud broadcasts with server-side looping up to 7 continuous days.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(schedulesState.data, key = { it.id }) { sched ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(sched.title, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Video: ${sched.videoTitle}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    AssistChip(onClick = {}, label = { Text(sched.status) })
                                }
                                Text(
                                    "Start: ${sched.scheduledStartIso} • ${sched.durationHours}h • ${sched.parsedLoopMode.label} • ${sched.parsedRecurrence.label}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFont),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Destinations: ${sched.destinationNames.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { onDuplicateSchedule(sched) }) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Duplicate")
                                    }
                                    if (sched.parsedStatus == ScheduleStatus.SCHEDULED) {
                                        TextButton(onClick = {
                                            editingSchedule = sched
                                            showCreateDialog = true
                                        }) { Text("Edit") }
                                        TextButton(onClick = { onCancelSchedule(sched.id) }) {
                                            Text("Cancel", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
