package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.*
import com.example.ui.components.ActiveBroadcastHealthCard
import com.example.ui.components.LiveSignalPill
import com.example.ui.theme.*
import com.example.ui.util.formatBytesHuman
import com.example.ui.util.formatDurationHms
import com.example.ui.viewmodel.ActiveBroadcastUi
import com.example.ui.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    profileState: UiState<UserProfile?>,
    settings: SystemSettings,
    streamsState: UiState<List<StreamSession>>,
    schedulesState: UiState<List<ScheduledStream>>,
    presetsState: UiState<List<StreamPreset>>,
    notificationsState: UiState<List<NotificationItem>>,
    activeBroadcast: ActiveBroadcastUi?,
    remainingSeconds: Long,
    storageLimitBytes: Long,
    onStartLiveClick: () -> Unit,
    onScreenLiveClick: () -> Unit,
    onUploadVideoClick: () -> Unit,
    onScheduleStreamClick: () -> Unit,
    onUpgradeClick: () -> Unit,
    onPresetStartClick: (StreamPreset) -> Unit,
    onStopBroadcastClick: () -> Unit,
    onMarkNotificationRead: (String) -> Unit,
    onOpenAdminConsole: () -> Unit
) {
    var showNotificationsSheet by remember { mutableStateOf(false) }
    var selectedStreamDetail by remember { mutableStateOf<StreamSession?>(null) }

    val profile = (profileState as? UiState.Success)?.data
    val notifications = (notificationsState as? UiState.Success)?.data.orEmpty()
    val unreadCount = notifications.count { !it.read }

    if (showNotificationsSheet) {
        ModalBottomSheet(onDismissRequest = { showNotificationsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Notifications", style = MaterialTheme.typography.headlineSmall)
                if (notifications.isEmpty()) {
                    Text(
                        "You are all caught up. Stream alerts, payment updates, and quota warnings will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(notifications, key = { it.id }) { notif ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (notif.read) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onMarkNotificationRead(notif.id) }
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(notif.title, style = MaterialTheme.typography.titleSmall)
                                        if (!notif.read) {
                                            Badge { Text("NEW") }
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(notif.message, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    selectedStreamDetail?.let { detail ->
        AlertDialog(
            onDismissRequest = { selectedStreamDetail = null },
            title = { Text(detail.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Platform(s): ${detail.destinationNames.joinToString(", ").ifEmpty { "Custom" }}")
                    Text("Status: ${detail.status} (${detail.workerStatus})")
                    Text("Quality: ${detail.resolution} @ ${detail.fps} FPS • ${detail.videoBitrateKbps} kbps")
                    Text("Orientation: ${detail.parsedOrientation.ratioText} (${detail.parsedAspectMode.label})")
                    Text("Duration: ${formatDurationHms(detail.durationSeconds)}")
                    Text("Dropped Frames: ${detail.droppedFramesPercent}% • Reconnects: ${detail.reconnectAttempts}")
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedStreamDetail = null }) { Text("Close") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Row: Welcome + Avatar + Notifications + Admin shortcut
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (!profile?.photoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = profile?.photoUrl,
                            contentDescription = "User profile avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Column {
                        Text(
                            text = "Welcome, ${profile?.displayName ?: "Creator"}",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val planName = profile?.subscriptionPlan ?: "FREE"
                            Surface(
                                color = if (planName == "FREE") MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "PLAN: $planName",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (planName == "FREE") MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            if (profile?.isAdminUser == true) {
                                AssistChip(
                                    onClick = onOpenAdminConsole,
                                    label = { Text("Admin Console", style = MaterialTheme.typography.labelSmall) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.AdminPanelSettings,
                                            contentDescription = "Open Admin Console",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    modifier = Modifier.testTag("admin_console_chip")
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = { showNotificationsSheet = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("notifications_button")
                ) {
                    BadgedBox(
                        badge = {
                            if (unreadCount > 0) {
                                Badge { Text("$unreadCount") }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Notifications, contentDescription = "Notifications")
                    }
                }
            }
        }

        // System Maintenance / Announcement Banner
        if (settings.maintenanceMode || settings.announcementMessage.isNotBlank()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (settings.maintenanceMode) CriticalRed.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (settings.maintenanceMode) Icons.Filled.Build else Icons.Filled.Campaign,
                            contentDescription = null,
                            tint = if (settings.maintenanceMode) CriticalRed else MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = if (settings.maintenanceMode) {
                                "Maintenance Mode Active: New streams are temporarily paused."
                            } else settings.announcementMessage,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Active Broadcast Live Telemetry Card
        if (activeBroadcast != null) {
            item {
                ActiveBroadcastHealthCard(
                    broadcast = activeBroadcast,
                    onStopClick = onStopBroadcastClick
                )
            }
        }

        // Daily Free Quota & Plan Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box {
                    Image(
                        painter = painterResource(id = R.drawable.img_studio_hero),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 0.14f,
                        modifier = Modifier.matchParentSize()
                    )
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Current Plan",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = profile?.subscriptionPlan ?: "FREE",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (profile?.isPremiumOrAdmin != true) {
                                FilledTonalButton(
                                    onClick = onUpgradeClick,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("upgrade_plan_button")
                                ) {
                                    Icon(Icons.Filled.WorkspacePremium, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Upgrade")
                                }
                            } else {
                                Surface(
                                    color = HealthEmerald.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "UNLIMITED QUOTA",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = HealthEmerald,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        if (profile?.isPremiumOrAdmin != true) {
                            val limit = settings.freeDailyLimitSeconds.coerceAtLeast(1L)
                            val used = (limit - remainingSeconds).coerceIn(0L, limit)
                            val fraction = (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Today's Live Usage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = "${formatDurationHms(used)} / ${formatDurationHms(limit)}",
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = TelemetryMonoFont)
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Remaining Time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = formatDurationHms(remainingSeconds),
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = TelemetryMonoFont),
                                        color = if (remainingSeconds <= 900L) WarningAmber else HealthEmerald
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = if (remainingSeconds <= 0L) CriticalRed else MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Free daily quota resets automatically every day (00:00 UTC).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "Extended live streaming, 1 GB prerecorded uploads, looping, scheduling & 7-day cloud workers unlocked.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Primary 2x2 Action Grid: START LIVE, SCREEN LIVE, UPLOAD VIDEO, SCHEDULE STREAM
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DashboardActionTile(
                        title = "START LIVE",
                        subtitle = "Camera Broadcast",
                        icon = Icons.Filled.Videocam,
                        accent = LiveCrimson,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dashboard_start_live"),
                        onClick = onStartLiveClick
                    )
                    DashboardActionTile(
                        title = "SCREEN LIVE",
                        subtitle = "Display + Cam Overlay",
                        icon = Icons.Filled.ScreenShare,
                        accent = ElectricCyan,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dashboard_screen_live"),
                        onClick = onScreenLiveClick
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DashboardActionTile(
                        title = "UPLOAD VIDEO",
                        subtitle = "Up to 1 GB Resumable",
                        icon = Icons.Filled.CloudUpload,
                        accent = BroadcastIndigo,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dashboard_upload_video"),
                        onClick = onUploadVideoClick
                    )
                    DashboardActionTile(
                        title = "SCHEDULE STREAM",
                        subtitle = "Up to 7-Day Cloud",
                        icon = Icons.Filled.EventAvailable,
                        accent = HealthEmerald,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dashboard_schedule_stream"),
                        onClick = onScheduleStreamClick
                    )
                }
            }
        }

        // One-Tap Stream Templates
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("One-Tap Stream Templates", style = MaterialTheme.typography.titleMedium)
                when (presetsState) {
                    is UiState.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    is UiState.Error -> Text(presetsState.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    is UiState.Success -> {
                        if (presetsState.data.isEmpty()) {
                            Text(
                                "Save stream presets in the Live tab for one-tap launch.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(presetsState.data, key = { it.id }) { preset ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .width(210.dp)
                                            .clickable { onPresetStartClick(preset) }
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(preset.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                                Icon(Icons.Filled.PlayArrow, contentDescription = "Start template", tint = MaterialTheme.colorScheme.primary)
                                            }
                                            Text(
                                                "${preset.resolution} • ${preset.fps} FPS • ${preset.parsedOrientation.ratioText}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                "${preset.videoBitrateKbps / 1000.0} Mbps • ${preset.destinationNames.firstOrNull() ?: "Multi"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
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

        // Storage & Subscription Snapshot
        item {
            val usedBytes = profile?.storageUsedBytes ?: 0L
            val ratio = (usedBytes.toFloat() / storageLimitBytes.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Cloud Storage Used", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${formatBytesHuman(usedBytes)} / ${formatBytesHuman(storageLimitBytes)}",
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = TelemetryMonoFont)
                        )
                    }
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            }
        }

        // Upcoming Scheduled Streams
        item {
            Text("Upcoming Cloud Streams", style = MaterialTheme.typography.titleMedium)
        }
        when (schedulesState) {
            is UiState.Loading -> item { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
            is UiState.Error -> item {
                Text(schedulesState.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            is UiState.Success -> {
                val upcoming = schedulesState.data.filter { it.parsedStatus == ScheduleStatus.SCHEDULED }.take(3)
                if (upcoming.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "No upcoming scheduled cloud streams. Tap Schedule Stream to automate 24/7 or 7-day broadcasts.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(upcoming, key = { it.id }) { sched ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(sched.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${sched.scheduledStartIso} • ${sched.durationHours}h • ${sched.parsedLoopMode.label}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                AssistChip(onClick = onScheduleStreamClick, label = { Text(sched.status) })
                            }
                        }
                    }
                }
            }
        }

        // Recent Streams
        item {
            Text("Recent Streams", style = MaterialTheme.typography.titleMedium)
        }
        when (streamsState) {
            is UiState.Loading -> item { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
            is UiState.Error -> item {
                Text(streamsState.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            is UiState.Success -> {
                val recent = streamsState.data.take(5)
                if (recent.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Filled.LiveTv, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "No broadcasts recorded yet. Tap START LIVE or SCREEN LIVE to go live!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(recent, key = { it.id }) { stream ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedStreamDetail = stream }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (stream.parsedStatus == StreamStatus.LIVE || stream.parsedStatus == StreamStatus.RUNNING) {
                                            LiveSignalPill(stream.status)
                                        }
                                        Text(stream.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    }
                                    Text(
                                        "${stream.destinationNames.joinToString(", ").ifEmpty { "RTMP" }} • ${stream.resolution} ${stream.fps}fps • ${formatDurationHms(stream.durationSeconds)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
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
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .heightIn(min = 98.dp)
            .minimumInteractiveComponentSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(accent.copy(alpha = 0.14f), Color.Transparent)
                    )
                )
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = accent.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(20.dp))
                    }
                }
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
