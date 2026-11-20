package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.components.LiveSignalPill
import com.example.ui.theme.*
import com.example.ui.util.formatBytesHuman
import com.example.ui.util.formatDurationHms
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun AnalyticsScreen(
    profileState: UiState<UserProfile?>,
    streamsState: UiState<List<StreamSession>>,
    videosState: UiState<List<VideoAsset>>,
    storageLimitBytes: Long,
    onStopCloudStream: (StreamSession) -> Unit
) {
    var statusFilter by remember { mutableStateOf<String>("ALL") }
    var selectedStream by remember { mutableStateOf<StreamSession?>(null) }

    val profile = (profileState as? UiState.Success)?.data
    val streams = (streamsState as? UiState.Success)?.data.orEmpty()
    val videos = (videosState as? UiState.Success)?.data.orEmpty()

    val totalSeconds = streams.sumOf { it.durationSeconds }
    val totalStreams = streams.size
    val completedCount = streams.count { it.parsedStatus == StreamStatus.COMPLETED || it.parsedStatus == StreamStatus.STOPPED }
    val failedCount = streams.count { it.parsedStatus == StreamStatus.FAILED }
    val activeCount = streams.count { it.parsedStatus == StreamStatus.LIVE || it.parsedStatus == StreamStatus.RUNNING || it.parsedStatus == StreamStatus.RECONNECTING }
    val avgDurationSeconds = if (totalStreams > 0) totalSeconds / totalStreams else 0L
    val totalDataMb = streams.sumOf { it.dataTransferredMb }
    val storageUsed = profile?.storageUsedBytes ?: videos.sumOf { it.fileSizeBytes }

    val filteredStreams = remember(streams, statusFilter) {
        when (statusFilter) {
            "ALL" -> streams
            "ACTIVE" -> streams.filter {
                it.parsedStatus in listOf(StreamStatus.LIVE, StreamStatus.RUNNING, StreamStatus.RECONNECTING)
            }
            else -> streams.filter { it.status.equals(statusFilter, ignoreCase = true) }
        }
    }

    selectedStream?.let { stream ->
        val dateText = stream.createdAt?.toDate()?.let {
            SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US).format(it)
        } ?: "Just now"
        AlertDialog(
            onDismissRequest = { selectedStream = null },
            title = { Text(stream.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Started: $dateText", style = MaterialTheme.typography.bodySmall)
                    Text("Source: ${stream.sourceType} • Loop: ${stream.parsedLoopMode.label}")
                    Text("Platforms: ${stream.destinationNames.joinToString(", ").ifEmpty { "RTMP" }}")
                    Text("Status: ${stream.status} (${stream.workerStatus})")
                    Text("Duration: ${formatDurationHms(stream.durationSeconds)}")
                    Text("Video Config: ${stream.resolution} @ ${stream.fps} FPS • ${stream.videoBitrateKbps} kbps")
                    Text("Audio Bitrate: ${stream.audioBitrateKbps} kbps • ${stream.parsedOrientation.ratioText} (${stream.parsedAspectMode.label})")
                    Text("Dropped Frames: ${stream.droppedFramesPercent}% • Reconnects: ${stream.reconnectAttempts}")
                    Text("Data Transferred: ${String.format(Locale.US, "%.2f MB", stream.dataTransferredMb)}")
                    Text("Worker Health: CPU ${stream.workerCpuPercent}% • RAM ${stream.workerRamPercent}%")
                }
            },
            confirmButton = {
                if (stream.parsedStatus == StreamStatus.RUNNING) {
                    Button(
                        onClick = {
                            onStopCloudStream(stream)
                            selectedStream = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LiveCrimson)
                    ) {
                        Text("Stop Cloud Stream")
                    }
                }
                TextButton(onClick = { selectedStream = null }) {
                    Text("Close")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Stream Analytics & History", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "App-level broadcast duration, throughput, reliability, and session logs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 2x3 KPI Grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AnalyticsKpiCard(
                        label = "Total Streamed",
                        value = String.format(Locale.US, "%.1f hrs", totalSeconds / 3600.0),
                        icon = Icons.Filled.Timer,
                        accent = ElectricCyan,
                        modifier = Modifier.weight(1f)
                    )
                    AnalyticsKpiCard(
                        label = "Total Streams",
                        value = "$totalStreams",
                        icon = Icons.Filled.LiveTv,
                        accent = BroadcastIndigo,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AnalyticsKpiCard(
                        label = "Success / Failed",
                        value = "$completedCount / $failedCount",
                        icon = Icons.Filled.Verified,
                        accent = HealthEmerald,
                        modifier = Modifier.weight(1f)
                    )
                    AnalyticsKpiCard(
                        label = "Avg Duration",
                        value = formatDurationHms(avgDurationSeconds),
                        icon = Icons.Filled.AvTimer,
                        accent = WarningAmber,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AnalyticsKpiCard(
                        label = "Data Transferred",
                        value = String.format(Locale.US, "%.1f MB", totalDataMb),
                        icon = Icons.Filled.DataUsage,
                        accent = ElectricCyan,
                        modifier = Modifier.weight(1f)
                    )
                    AnalyticsKpiCard(
                        label = "Storage Used",
                        value = "${formatBytesHuman(storageUsed)} / ${formatBytesHuman(storageLimitBytes)}",
                        icon = Icons.Filled.Storage,
                        accent = BroadcastIndigo,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Custom Compose Canvas Bar Chart of recent stream durations
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Recent Broadcast Duration & Stability", style = MaterialTheme.typography.titleMedium)
                        Surface(
                            color = if (activeCount > 0) LiveCrimson.copy(alpha = 0.2f) else HealthEmerald.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "$activeCount Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (activeCount > 0) LiveCrimson else HealthEmerald,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    val chartSamples = streams.take(8).reversed()
                    if (chartSamples.isEmpty()) {
                        Text(
                            "Start a camera, screen, or cloud video stream to populate duration and bitrate charts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 18.dp)
                        )
                    } else {
                        val barPrimary = MaterialTheme.colorScheme.primary
                        val barError = CriticalRed
                        val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .padding(top = 6.dp)
                        ) {
                            val maxVal = chartSamples.maxOf { it.durationSeconds.coerceAtLeast(10L) }.toFloat()
                            val count = chartSamples.size.coerceAtLeast(1)
                            val slotWidth = size.width / count
                            val barWidth = (slotWidth * 0.56f).coerceAtLeast(12f)

                            // Baseline & midline
                            drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
                            drawLine(gridColor, Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), strokeWidth = 1f)

                            chartSamples.forEachIndexed { idx, s ->
                                val ratio = (s.durationSeconds.coerceAtLeast(5L) / maxVal).coerceIn(0.08f, 1f)
                                val barH = size.height * ratio
                                val left = idx * slotWidth + (slotWidth - barWidth) / 2f
                                val color = if (s.parsedStatus == StreamStatus.FAILED) barError else barPrimary
                                drawRoundRect(
                                    color = color,
                                    topLeft = Offset(left, size.height - barH),
                                    size = Size(barWidth, barH),
                                    cornerRadius = CornerRadius(6f, 6f)
                                )
                            }
                        }
                        Text(
                            "Last ${chartSamples.size} broadcasts • Tap any session below for bitrate, dropped frames & reconnect logs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Stream History Filters
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Stream History", style = MaterialTheme.typography.titleMedium)
                val filters = listOf("ALL", "ACTIVE", "COMPLETED", "STOPPED", "FAILED")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filters) { f ->
                        FilterChip(
                            selected = statusFilter == f,
                            onClick = { statusFilter = f },
                            label = { Text(f) },
                            modifier = Modifier.testTag("analytics_filter_$f")
                        )
                    }
                }
            }
        }

        when (streamsState) {
            is UiState.Loading -> item { CircularProgressIndicator() }
            is UiState.Error -> item {
                Text(streamsState.message, color = MaterialTheme.colorScheme.error)
            }
            is UiState.Success -> {
                if (filteredStreams.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "No stream sessions matching \"$statusFilter\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(18.dp)
                            )
                        }
                    }
                } else {
                    items(filteredStreams, key = { it.id }) { stream ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedStream = stream }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (stream.parsedStatus in listOf(StreamStatus.LIVE, StreamStatus.RUNNING, StreamStatus.RECONNECTING)) {
                                            LiveSignalPill(stream.status, isReconnecting = stream.parsedStatus == StreamStatus.RECONNECTING)
                                        }
                                        Text(stream.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    }
                                    Text(
                                        "${stream.sourceType} • ${stream.destinationNames.joinToString(", ").ifEmpty { "RTMP" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "${stream.resolution} ${stream.fps}fps • ${formatDurationHms(stream.durationSeconds)} • Drop ${stream.droppedFramesPercent}%",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFont),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = stream.status,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when (stream.parsedStatus) {
                                            StreamStatus.COMPLETED -> HealthEmerald
                                            StreamStatus.FAILED -> CriticalRed
                                            StreamStatus.LIVE, StreamStatus.RUNNING -> LiveCrimson
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    if (stream.parsedStatus == StreamStatus.RUNNING) {
                                        TextButton(
                                            onClick = { onStopCloudStream(stream) },
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Text("Stop", color = LiveCrimson)
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

@Composable
private fun AnalyticsKpiCard(
    label: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = TelemetryMonoFont),
                fontWeight = FontWeight.Bold
            )
        }
    }
}
