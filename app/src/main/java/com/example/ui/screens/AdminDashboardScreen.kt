package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.theme.*
import com.example.ui.util.formatDurationHms
import com.example.ui.viewmodel.AdminMetricsSummary
import com.example.ui.viewmodel.UiState
import java.util.Locale

private enum class AdminTab(val label: String) {
    OVERVIEW("Overview"),
    USERS("Users"),
    STREAMS("Streams & Servers"),
    PAYMENTS("Payments"),
    WITHDRAWALS("Withdrawals"),
    MODERATION("Reports & Logs"),
    SETTINGS("System Limits")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    summary: AdminMetricsSummary,
    settings: SystemSettings,
    usersState: UiState<List<UserProfile>>,
    paymentsState: UiState<List<PaymentRecord>>,
    activeStreamsState: UiState<List<StreamSession>>,
    withdrawalsState: UiState<List<WithdrawalRecord>>,
    reportsState: UiState<List<AbuseReport>>,
    auditLogsState: UiState<List<AuditLog>>,
    onVerifyPayment: (PaymentRecord, Boolean, Boolean) -> Unit,
    onUpdateUser: (String, AccountStatus, SubscriptionPlan) -> Unit,
    onStopStream: (StreamSession) -> Unit,
    onRequestWithdrawal: (Double, WithdrawalMethod, String) -> Unit,
    onUpdateReport: (String, ReportStatus) -> Unit,
    onSaveSystemSettings: (SystemSettings) -> Unit,
    onBack: () -> Unit
) {
    var activeTab by remember { mutableStateOf(AdminTab.OVERVIEW) }

    var showWithdrawalDialog by remember { mutableStateOf(false) }
    var withdrawAmountInput by remember { mutableStateOf("") }
    var withdrawMethod by remember { mutableStateOf(WithdrawalMethod.BKASH) }
    var withdrawDestinationInput by remember { mutableStateOf("") }

    // System Settings Editable State
    var freeHoursText by remember(settings) { mutableStateOf((settings.freeDailyLimitSeconds / 3600L).coerceAtLeast(1L).toString()) }
    var monthlyPriceText by remember(settings) { mutableStateOf(settings.monthlyPriceBdt.toString()) }
    var lifetimePriceText by remember(settings) { mutableStateOf(settings.lifetimePriceBdt.toString()) }
    var maxUploadMbText by remember(settings) { mutableStateOf(settings.maxUploadSizeMb.toString()) }
    var maxCloudHoursText by remember(settings) { mutableStateOf(settings.maxCloudDurationHours.toString()) }
    var maxConcurrentText by remember(settings) { mutableStateOf(settings.maxConcurrentStreams.toString()) }
    var maintenanceMode by remember(settings) { mutableStateOf(settings.maintenanceMode) }
    var announcementText by remember(settings) { mutableStateOf(settings.announcementMessage) }

    if (showWithdrawalDialog) {
        AlertDialog(
            onDismissRequest = { showWithdrawalDialog = false },
            title = { Text("Request Revenue Withdrawal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Verified Available Balance: BDT ${String.format(Locale.US, "%,.0f", summary.availableBalanceBdt)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = HealthEmerald
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WithdrawalMethod.entries.forEach { method ->
                            FilterChip(
                                selected = withdrawMethod == method,
                                onClick = { withdrawMethod = method },
                                label = { Text(method.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = withdrawAmountInput,
                        onValueChange = { withdrawAmountInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("Amount (BDT)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = withdrawDestinationInput,
                        onValueChange = { withdrawDestinationInput = it },
                        label = { Text("Merchant Account / Bank IBAN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = withdrawAmountInput.toDoubleOrNull() ?: 0.0
                        onRequestWithdrawal(amt, withdrawMethod, withdrawDestinationInput)
                        withdrawAmountInput = ""
                        withdrawDestinationInput = ""
                        showWithdrawalDialog = false
                    },
                    enabled = (withdrawAmountInput.toDoubleOrNull() ?: 0.0) > 0.0 && withdrawDestinationInput.length >= 4
                ) { Text("Submit Request") }
            },
            dismissButton = {
                TextButton(onClick = { showWithdrawalDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admin Control Center") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(AdminTab.entries) { tab ->
                    FilterChip(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        label = { Text(tab.label) },
                        modifier = Modifier.testTag("admin_tab_${tab.name}")
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (activeTab) {
                    AdminTab.OVERVIEW -> {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    AdminStatCard("Today Revenue", "BDT ${summary.todayRevenueBdt.toInt()}", ElectricCyan, Modifier.weight(1f))
                                    AdminStatCard("Month Revenue", "BDT ${summary.monthRevenueBdt.toInt()}", BroadcastIndigo, Modifier.weight(1f))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    AdminStatCard("Lifetime Revenue", "BDT ${summary.lifetimeRevenueBdt.toInt()}", HealthEmerald, Modifier.weight(1f))
                                    AdminStatCard("Verified Available", "BDT ${summary.availableBalanceBdt.toInt()}", HealthEmerald, Modifier.weight(1f))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    AdminStatCard("Users / Active", "${summary.totalUsers} / ${summary.activeUsers}", ElectricCyan, Modifier.weight(1f))
                                    AdminStatCard("Monthly / Lifetime", "${summary.monthlySubscribers} / ${summary.lifetimeSubscribers}", WarningAmber, Modifier.weight(1f))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    AdminStatCard("Pending Payments", "${summary.pendingPaymentsCount}", WarningAmber, Modifier.weight(1f))
                                    AdminStatCard("Refunded / Paid", "${summary.refundedPaymentsCount} / ${summary.completedPaymentsCount}", CriticalRed, Modifier.weight(1f))
                                }
                            }
                        }

                        // Revenue Breakdown Chart
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Revenue vs Refunds & Payouts", style = MaterialTheme.typography.titleMedium)
                                    val values = listOf(
                                        summary.lifetimeRevenueBdt.toFloat(),
                                        summary.availableBalanceBdt.toFloat(),
                                        summary.withdrawnTotalBdt.toFloat(),
                                        summary.refundedTotalBdt.toFloat()
                                    )
                                    val colors = listOf(ElectricCyan, HealthEmerald, BroadcastIndigo, CriticalRed)
                                    Canvas(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(100.dp)
                                    ) {
                                        val maxV = values.maxOfOrNull { it }?.coerceAtLeast(100f) ?: 100f
                                        val slot = size.width / values.size
                                        val barW = slot * 0.55f
                                        values.forEachIndexed { idx, v ->
                                            val h = (v / maxV).coerceIn(0.06f, 1f) * size.height
                                            drawRoundRect(
                                                color = colors[idx],
                                                topLeft = Offset(idx * slot + (slot - barW) / 2f, size.height - h),
                                                size = Size(barW, h),
                                                cornerRadius = CornerRadius(6f, 6f)
                                            )
                                        }
                                    }
                                    Text(
                                        "Gross Paid • Available Balance • Withdrawn • Refunded (strictly calculated from verified ledger records)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    AdminTab.USERS -> {
                        when (usersState) {
                            is UiState.Loading -> item { CircularProgressIndicator() }
                            is UiState.Error -> item { Text(usersState.message, color = MaterialTheme.colorScheme.error) }
                            is UiState.Success -> {
                                items(usersState.data, key = { it.userId.ifBlank { it.id } }) { u ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(u.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                                    Text("${u.email} • Role: ${u.role}", style = MaterialTheme.typography.bodySmall)
                                                    Text(
                                                        "Plan: ${u.subscriptionPlan} • Used today: ${formatDurationHms(u.dailyUsedSeconds)}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                AssistChip(onClick = {}, label = { Text(u.accountStatus) })
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(
                                                    onClick = {
                                                        val nextStatus = if (u.parsedAccountStatus == AccountStatus.ACTIVE) AccountStatus.SUSPENDED else AccountStatus.ACTIVE
                                                        onUpdateUser(u.id, nextStatus, u.parsedPlan)
                                                    }
                                                ) {
                                                    Text(if (u.parsedAccountStatus == AccountStatus.ACTIVE) "Suspend" else "Activate")
                                                }
                                                OutlinedButton(
                                                    onClick = {
                                                        val nextPlan = when (u.parsedPlan) {
                                                            SubscriptionPlan.FREE -> SubscriptionPlan.MONTHLY
                                                            SubscriptionPlan.MONTHLY -> SubscriptionPlan.LIFETIME
                                                            SubscriptionPlan.LIFETIME -> SubscriptionPlan.FREE
                                                        }
                                                        onUpdateUser(u.id, u.parsedAccountStatus, nextPlan)
                                                    }
                                                ) {
                                                    Text("Cycle Plan")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AdminTab.STREAMS -> {
                        item {
                            val activeCount = (activeStreamsState as? UiState.Success)?.data?.size ?: 0
                            val healthLabel = when {
                                activeCount > 20 -> "WARNING"
                                else -> "HEALTHY"
                            }
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, HealthEmerald.copy(alpha = 0.4f)),
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
                                        Text("Server & Worker Health", style = MaterialTheme.typography.titleMedium)
                                        Surface(
                                            color = HealthEmerald.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = healthLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = HealthEmerald,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        "Active Streams: $activeCount • Max Concurrent/User: ${settings.maxConcurrentStreams} • Cloud Max: ${settings.maxCloudDurationHours}h",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        when (activeStreamsState) {
                            is UiState.Loading -> item { CircularProgressIndicator() }
                            is UiState.Error -> item { Text(activeStreamsState.message, color = MaterialTheme.colorScheme.error) }
                            is UiState.Success -> {
                                if (activeStreamsState.data.isEmpty()) {
                                    item {
                                        Text("No live or cloud streams running across the platform right now.", style = MaterialTheme.typography.bodySmall)
                                    }
                                } else {
                                    items(activeStreamsState.data, key = { it.id }) { stream ->
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(stream.title, style = MaterialTheme.typography.titleSmall)
                                                    Text(
                                                        "${stream.sourceType} • ${stream.resolution} ${stream.fps}fps • CPU ${stream.workerCpuPercent}% RAM ${stream.workerRamPercent}%",
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                                Button(
                                                    onClick = { onStopStream(stream) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = CriticalRed)
                                                ) { Text("Stop") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AdminTab.PAYMENTS -> {
                        when (paymentsState) {
                            is UiState.Loading -> item { CircularProgressIndicator() }
                            is UiState.Error -> item { Text(paymentsState.message, color = MaterialTheme.colorScheme.error) }
                            is UiState.Success -> {
                                if (paymentsState.data.isEmpty()) {
                                    item { Text("No payments submitted yet.", style = MaterialTheme.typography.bodySmall) }
                                } else {
                                    items(paymentsState.data, key = { it.id }) { pay ->
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(14.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text("${pay.userEmail} • ${pay.plan}", style = MaterialTheme.typography.titleSmall)
                                                        Text(
                                                            "${pay.parsedMethod.label} • TxID: ${pay.transactionId} • BDT ${pay.amount.toInt()}",
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                    }
                                                    AssistChip(onClick = {}, label = { Text(pay.status) })
                                                }
                                                if (pay.parsedStatus == PaymentStatus.PENDING) {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Button(
                                                            onClick = { onVerifyPayment(pay, true, false) },
                                                            colors = ButtonDefaults.buttonColors(containerColor = HealthEmerald),
                                                            modifier = Modifier.testTag("admin_approve_${pay.id}")
                                                        ) { Text("Approve & Activate") }
                                                        OutlinedButton(
                                                            onClick = { onVerifyPayment(pay, false, false) }
                                                        ) { Text("Reject") }
                                                    }
                                                } else if (pay.parsedStatus == PaymentStatus.PAID) {
                                                    OutlinedButton(
                                                        onClick = { onVerifyPayment(pay, false, true) }
                                                    ) { Text("Issue Refund") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AdminTab.WITHDRAWALS -> {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Verified Revenue Payouts", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Available Balance: BDT ${String.format(Locale.US, "%,.0f", summary.availableBalanceBdt)}",
                                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = TelemetryMonoFont),
                                        color = HealthEmerald
                                    )
                                    Text(
                                        "Calculated strictly as Paid Verified (BDT ${summary.lifetimeRevenueBdt.toInt()}) − Refunded (${summary.refundedTotalBdt.toInt()}) − Withdrawn (${summary.withdrawnTotalBdt.toInt()}).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = { showWithdrawalDialog = true },
                                        enabled = summary.availableBalanceBdt > 0.0,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Request Withdrawal")
                                    }
                                }
                            }
                        }

                        when (withdrawalsState) {
                            is UiState.Loading -> item { CircularProgressIndicator() }
                            is UiState.Error -> item { Text(withdrawalsState.message, color = MaterialTheme.colorScheme.error) }
                            is UiState.Success -> {
                                items(withdrawalsState.data, key = { it.id }) { w ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("BDT ${w.amount.toInt()} • ${w.parsedMethod.label}", style = MaterialTheme.typography.titleSmall)
                                                Text("Dest: ${w.destination}", style = MaterialTheme.typography.bodySmall)
                                            }
                                            AssistChip(onClick = {}, label = { Text(w.status) })
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AdminTab.MODERATION -> {
                        item { Text("Abuse Reports", style = MaterialTheme.typography.titleMedium) }
                        val reports = (reportsState as? UiState.Success)?.data.orEmpty()
                        if (reports.isEmpty()) {
                            item { Text("No open abuse reports.", style = MaterialTheme.typography.bodySmall) }
                        } else {
                            items(reports, key = { it.id }) { rep ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("${rep.reason} • ${rep.status}", style = MaterialTheme.typography.titleSmall)
                                        Text(rep.details, style = MaterialTheme.typography.bodySmall)
                                        if (rep.parsedStatus == ReportStatus.OPEN) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(onClick = { onUpdateReport(rep.id, ReportStatus.RESOLVED) }) {
                                                    Text("Resolve")
                                                }
                                                OutlinedButton(onClick = { onUpdateReport(rep.id, ReportStatus.DISMISSED) }) {
                                                    Text("Dismiss")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item { Text("Security & Audit Logs", style = MaterialTheme.typography.titleMedium) }
                        val logs = (auditLogsState as? UiState.Success)?.data.orEmpty()
                        items(logs.take(20), key = { it.id }) { log ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("${log.action} (${log.severity})", style = MaterialTheme.typography.labelLarge)
                                    Text("${log.actorEmail} -> ${log.target}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    AdminTab.SETTINGS -> {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("Dynamic Platform Settings (Live Sync)", style = MaterialTheme.typography.titleMedium)
                                    OutlinedTextField(
                                        value = freeHoursText,
                                        onValueChange = { freeHoursText = it.filter(Char::isDigit) },
                                        label = { Text("Free Daily Streaming Limit (Hours)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = monthlyPriceText,
                                        onValueChange = { monthlyPriceText = it.filter(Char::isDigit) },
                                        label = { Text("Monthly Subscription Price (BDT)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = lifetimePriceText,
                                        onValueChange = { lifetimePriceText = it.filter(Char::isDigit) },
                                        label = { Text("Lifetime Subscription Price (BDT)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = maxUploadMbText,
                                        onValueChange = { maxUploadMbText = it.filter(Char::isDigit) },
                                        label = { Text("Max Video Upload Size (MB)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = maxCloudHoursText,
                                        onValueChange = { maxCloudHoursText = it.filter(Char::isDigit) },
                                        label = { Text("Max Cloud Stream Duration (Hours, up to 168)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = maxConcurrentText,
                                        onValueChange = { maxConcurrentText = it.filter(Char::isDigit) },
                                        label = { Text("Max Concurrent Streams") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = announcementText,
                                        onValueChange = { announcementText = it },
                                        label = { Text("Global Announcement Banner") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Maintenance Mode", style = MaterialTheme.typography.titleSmall)
                                        Switch(checked = maintenanceMode, onCheckedChange = { maintenanceMode = it })
                                    }
                                    Button(
                                        onClick = {
                                            val updated = settings.copy(
                                                freeDailyLimitSeconds = (freeHoursText.toLongOrNull() ?: 3L).coerceIn(1L, 24L) * 3600L,
                                                monthlyPriceBdt = (monthlyPriceText.toLongOrNull() ?: 100L).coerceAtLeast(1L),
                                                lifetimePriceBdt = (lifetimePriceText.toLongOrNull() ?: 1500L).coerceAtLeast(1L),
                                                maxUploadSizeMb = (maxUploadMbText.toLongOrNull() ?: 1024L).coerceIn(50L, 10240L),
                                                maxCloudDurationHours = (maxCloudHoursText.toLongOrNull() ?: 168L).coerceIn(1L, 168L),
                                                maxConcurrentStreams = (maxConcurrentText.toLongOrNull() ?: 3L).coerceIn(1L, 50L),
                                                maintenanceMode = maintenanceMode,
                                                announcementMessage = announcementText
                                            )
                                            onSaveSystemSettings(updated)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp)
                                            .testTag("admin_save_settings_button")
                                    ) {
                                        Text("Publish Live Settings")
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
private fun AdminStatCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = TelemetryMonoFont),
                color = accent,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
