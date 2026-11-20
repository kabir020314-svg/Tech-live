package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.ConnectionQuality
import com.example.data.model.StreamStatus
import com.example.data.model.SubscriptionPlan
import com.example.data.model.SystemSettings
import com.example.network.NetworkStatus
import com.example.ui.theme.*
import com.example.ui.util.formatDurationHms
import com.example.ui.viewmodel.ActiveBroadcastUi
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun NetworkStatusBanner(status: NetworkStatus) {
    var showRestored by remember { mutableStateOf(false) }
    var previousStatus by remember { mutableStateOf(status) }

    LaunchedEffect(status) {
        if (previousStatus != NetworkStatus.ONLINE && status == NetworkStatus.ONLINE) {
            showRestored = true
            delay(2500L)
            showRestored = false
        }
        previousStatus = status
    }

    val visible = status != NetworkStatus.ONLINE || showRestored
    AnimatedVisibility(visible = visible) {
        val (bgColor, icon, text) = when {
            showRestored -> Triple(HealthEmerald, Icons.Filled.Wifi, "Connection restored")
            status == NetworkStatus.CONNECTING -> Triple(WarningAmber, Icons.Filled.Sync, "Connecting…")
            else -> Triple(CriticalRed, Icons.Filled.WifiOff, "Offline — changes will sync when restored")
        }
        Surface(
            color = bgColor,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
    }
}

@Composable
fun LiveSignalPill(label: String = "LIVE", isReconnecting: Boolean = false) {
    val infinite = rememberInfiniteTransition(label = "livePulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val bg = if (isReconnecting) WarningAmber else LiveCrimson
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(pulseAlpha)
                    .clip(CircleShape)
                    .background(Color.White)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ActiveBroadcastHealthCard(
    broadcast: ActiveBroadcastUi,
    onStopClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (broadcast.status == StreamStatus.RECONNECTING) WarningAmber else LiveCrimson.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LiveSignalPill(
                        label = if (broadcast.status == StreamStatus.RECONNECTING) "RECONNECTING" else "LIVE",
                        isReconnecting = broadcast.status == StreamStatus.RECONNECTING
                    )
                    Text(
                        text = broadcast.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                }
                Button(
                    onClick = onStopClick,
                    colors = ButtonDefaults.buttonColors(containerColor = LiveCrimson),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("stop_live_button")
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("END", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (broadcast.statusBanner.isNotBlank()) {
                Text(
                    text = broadcast.statusBanner,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (broadcast.status == StreamStatus.RECONNECTING) WarningAmber else MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryMetric("Duration", formatDurationHms(broadcast.durationSeconds))
                TelemetryMetric("Bitrate", String.format(Locale.US, "%.1f Mbps", broadcast.bitrateKbps / 1000.0))
                TelemetryMetric("FPS", "${broadcast.fps}")
                TelemetryMetric("Res", broadcast.resolution)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryMetric("Dropped", "${broadcast.droppedFramesPercent}%")
                TelemetryMetric(
                    "Connection",
                    when (broadcast.connectionQuality) {
                        ConnectionQuality.EXCELLENT -> "Excellent"
                        ConnectionQuality.GOOD -> "Good"
                        ConnectionQuality.FAIR -> "Fair"
                        ConnectionQuality.POOR -> "Poor"
                        ConnectionQuality.DISCONNECTED -> "Lost"
                    }
                )
                TelemetryMetric("Server", "Healthy")
                TelemetryMetric("Data", String.format(Locale.US, "%.1f MB", broadcast.dataTransferredMb))
            }
        }
    }
}

@Composable
fun TelemetryMetric(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = TelemetryMonoFont),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun QuotaReachedDialog(
    settings: SystemSettings,
    onDismiss: () -> Unit,
    onSelectPlan: (SubscriptionPlan) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.HourglassBottom, contentDescription = null, tint = WarningAmber) },
        title = { Text("Daily Free Limit Reached") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "You have used your ${formatDurationHms(settings.freeDailyLimitSeconds)} daily free streaming quota. Your free quota resets automatically at 00:00 UTC.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Upgrade to unlock extended live streaming, 1 GB prerecorded video uploads, looping, scheduling, and up to 7-day continuous cloud streaming:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Monthly Plan — BDT ${settings.monthlyPriceBdt}/mo", style = MaterialTheme.typography.titleSmall)
                        Text("Unlimited live & 7-day cloud streaming, 10 GB storage", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Lifetime Plan — BDT ${settings.lifetimePriceBdt} one-time", style = MaterialTheme.typography.titleSmall)
                        Text("Lifetime premium access & 25 GB cloud storage", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSelectPlan(SubscriptionPlan.LIFETIME) },
                modifier = Modifier.testTag("upgrade_lifetime_from_quota")
            ) {
                Text("Get Lifetime")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSelectPlan(SubscriptionPlan.MONTHLY) }) {
                    Text("Monthly")
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}

@Composable
fun LegalPolicyDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(content, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
