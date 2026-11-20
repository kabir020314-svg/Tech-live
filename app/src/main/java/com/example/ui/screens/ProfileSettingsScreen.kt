package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.BuildConfig
import com.example.data.model.*
import com.example.ui.components.LegalPolicyDialog
import com.example.ui.theme.*
import com.example.ui.util.formatBytesHuman
import com.example.ui.util.formatDurationHms
import com.example.ui.viewmodel.UiState

@Composable
fun ProfileSettingsScreen(
    profileState: UiState<UserProfile?>,
    settings: SystemSettings,
    paymentsState: UiState<List<PaymentRecord>>,
    themeMode: ThemeModePreference,
    lowLatencyMode: Boolean,
    autoReconnectEnabled: Boolean,
    streamNotificationsEnabled: Boolean,
    backendUrlOverride: String,
    storageLimitBytes: Long,
    remainingDailySeconds: Long,
    initialUpgradePlan: SubscriptionPlan? = null,
    onConsumeUpgradePrompt: () -> Unit = {},
    onThemeModeChange: (ThemeModePreference) -> Unit,
    onLowLatencyChange: (Boolean) -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
    onStreamNotificationsChange: (Boolean) -> Unit,
    onSaveBackendUrl: (String) -> Unit,
    onSubmitPayment: (SubscriptionPlan, PaymentMethod, String, String) -> Unit,
    onSubmitReport: (String, String) -> Unit,
    onOpenAdminConsole: () -> Unit,
    onSignOutClick: () -> Unit,
    onDeleteAccountConfirmed: () -> Unit
) {
    val profile = (profileState as? UiState.Success)?.data
    val payments = (paymentsState as? UiState.Success)?.data.orEmpty()

    var selectedPaymentPlan by remember { mutableStateOf<SubscriptionPlan?>(null) }
    var paymentMethod by remember { mutableStateOf(PaymentMethod.BKASH) }
    var transactionIdInput by remember { mutableStateOf("") }
    var paymentNotesInput by remember { mutableStateOf("") }

    var showBackendDialog by remember { mutableStateOf(false) }
    var backendInput by remember(backendUrlOverride) {
        mutableStateOf(backendUrlOverride.ifBlank { BuildConfig.TECHLIVE_API_BASE_URL })
    }

    var activePolicyDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showReportDialog by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("Stream / Playback Issue") }
    var reportDetails by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(initialUpgradePlan) {
        if (initialUpgradePlan != null && initialUpgradePlan != SubscriptionPlan.FREE) {
            selectedPaymentPlan = initialUpgradePlan
            onConsumeUpgradePrompt()
        }
    }

    // Payment Submission Modal
    selectedPaymentPlan?.let { plan ->
        val price = if (plan == SubscriptionPlan.MONTHLY) settings.monthlyPriceBdt else settings.lifetimePriceBdt
        AlertDialog(
            onDismissRequest = { selectedPaymentPlan = null },
            title = { Text("Upgrade to ${plan.name} (BDT $price)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Pay via automated gateway or manual mobile banking (bKash / Nagad / Card). Subscriptions always start in PENDING verification and unlock once verified.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Payment Method", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PaymentMethod.entries.forEach { method ->
                            FilterChip(
                                selected = paymentMethod == method,
                                onClick = { paymentMethod = method },
                                label = { Text(method.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    if (paymentMethod == PaymentMethod.BKASH || paymentMethod == PaymentMethod.NAGAD) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Send BDT $price to Tech Live Merchant via ${paymentMethod.label}, then enter the Transaction ID below.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = transactionIdInput,
                        onValueChange = { transactionIdInput = it },
                        label = { Text("Transaction / Gateway Reference ID") },
                        placeholder = { Text("e.g., BKS94X218Q") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("payment_tx_id_input")
                    )
                    OutlinedTextField(
                        value = paymentNotesInput,
                        onValueChange = { paymentNotesInput = it },
                        label = { Text("Sender Number / Notes (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSubmitPayment(plan, paymentMethod, transactionIdInput, paymentNotesInput)
                        transactionIdInput = ""
                        paymentNotesInput = ""
                        selectedPaymentPlan = null
                    },
                    enabled = transactionIdInput.trim().length >= 4,
                    modifier = Modifier.testTag("submit_payment_button")
                ) {
                    Text("Submit Payment")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPaymentPlan = null }) { Text("Cancel") }
            }
        )
    }

    if (showBackendDialog) {
        AlertDialog(
            onDismissRequest = { showBackendDialog = false },
            title = { Text("Backend Streaming API URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Connect Tech Live to your Node.js + FFmpeg Cloud Worker backend for OAuth & 7-day cloud relays.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = backendInput,
                        onValueChange = { backendInput = it },
                        label = { Text("API Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSaveBackendUrl(backendInput)
                    showBackendDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showBackendDialog = false }) { Text("Cancel") }
            }
        )
    }

    activePolicyDialog?.let { (title, content) ->
        LegalPolicyDialog(title = title, content = content, onDismiss = { activePolicyDialog = null })
    }

    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("Report Abuse or Issue") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = reportReason,
                        onValueChange = { reportReason = it },
                        label = { Text("Subject / Reason") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = reportDetails,
                        onValueChange = { reportDetails = it },
                        label = { Text("Details") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSubmitReport(reportReason, reportDetails)
                        reportDetails = ""
                        showReportDialog = false
                    },
                    enabled = reportDetails.isNotBlank()
                ) { Text("Submit") }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Account Permanently?") },
            text = {
                Text("This removes your Tech Live creator profile, saved destinations, and session access. Active cloud workers will be halted.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteAccountConfirmed()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CriticalRed)
                ) { Text("Delete Account") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // User Profile Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (!profile?.photoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = profile?.photoUrl,
                            contentDescription = "Creator avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.size(60.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile?.displayName ?: "Creator", style = MaterialTheme.typography.titleLarge)
                        Text(
                            profile?.email ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = {},
                                label = { Text("Plan: ${profile?.subscriptionPlan ?: "FREE"}") }
                            )
                            AssistChip(
                                onClick = {},
                                label = { Text(profile?.accountStatus ?: "ACTIVE") }
                            )
                        }
                    }
                }
            }
        }

        // Strict Role-Gated Admin Console Entry (Only visible for authorized Admin/Super Admin)
        if (profile?.isAdminUser == true) {
            item {
                Card(
                    onClick = onOpenAdminConsole,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_admin_console_card")
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("Admin Control Center", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "Revenue, Users, Payment Approvals, Withdrawals & System Limits",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Open Admin Console")
                    }
                }
            }
        }

        // Subscriptions & Plans
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Subscription Plans", style = MaterialTheme.typography.titleMedium)

                val currentPlan = profile?.parsedPlan ?: SubscriptionPlan.FREE
                PlanOptionCard(
                    title = "FREE Plan",
                    priceLabel = "BDT 0 / forever",
                    features = listOf(
                        "${formatDurationHms(settings.freeDailyLimitSeconds)} daily live streaming quota (auto-resets daily)",
                        "Remaining today: ${if (profile?.isPremiumOrAdmin == true) "Unlimited" else formatDurationHms(remainingDailySeconds)}",
                        "Storage: ${formatBytesHuman(storageLimitBytes)} • Camera & Screen Live"
                    ),
                    isCurrent = currentPlan == SubscriptionPlan.FREE,
                    buttonText = "Active Default",
                    onSelect = null
                )

                PlanOptionCard(
                    title = "MONTHLY Plan",
                    priceLabel = "BDT ${settings.monthlyPriceBdt} / month",
                    features = listOf(
                        "Extended / Unlimited daily live streaming",
                        "Prerecorded video uploads up to ${settings.maxUploadSizeMb} MB",
                        "Cloud looping & scheduled streams up to ${settings.maxCloudDurationHours}h (7 days)",
                        "Cloud Storage: ${settings.monthlyStorageLimitMb / 1024} GB"
                    ),
                    isCurrent = currentPlan == SubscriptionPlan.MONTHLY,
                    buttonText = "Subscribe BDT ${settings.monthlyPriceBdt}",
                    onSelect = { selectedPaymentPlan = SubscriptionPlan.MONTHLY },
                    testTag = "plan_monthly_button"
                )

                PlanOptionCard(
                    title = "LIFETIME Plan",
                    priceLabel = "BDT ${settings.lifetimePriceBdt} one-time",
                    features = listOf(
                        "Permanent premium unlock with zero recurring renewals",
                        "7-day continuous cloud workers, multi-destination & looping",
                        "Priority reconnect & stream analytics",
                        "Cloud Storage: ${settings.lifetimeStorageLimitMb / 1024} GB"
                    ),
                    isCurrent = currentPlan == SubscriptionPlan.LIFETIME,
                    buttonText = "Unlock Lifetime BDT ${settings.lifetimePriceBdt}",
                    onSelect = { selectedPaymentPlan = SubscriptionPlan.LIFETIME },
                    testTag = "plan_lifetime_button"
                )
            }
        }

        // Payment History
        item {
            Text("Payment Verification History", style = MaterialTheme.typography.titleMedium)
        }
        if (payments.isEmpty()) {
            item {
                Text(
                    "No payment submissions yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(payments, key = { it.id }) { pay ->
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
                            Text("${pay.plan} • ${pay.parsedMethod.label}", style = MaterialTheme.typography.titleSmall)
                            Text("TxID: ${pay.transactionId} • BDT ${pay.amount.toInt()}", style = MaterialTheme.typography.bodySmall)
                        }
                        Surface(
                            color = when (pay.parsedStatus) {
                                PaymentStatus.PAID -> HealthEmerald.copy(alpha = 0.2f)
                                PaymentStatus.PENDING -> WarningAmber.copy(alpha = 0.2f)
                                else -> CriticalRed.copy(alpha = 0.2f)
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = pay.status,
                                style = MaterialTheme.typography.labelSmall,
                                color = when (pay.parsedStatus) {
                                    PaymentStatus.PAID -> HealthEmerald
                                    PaymentStatus.PENDING -> WarningAmber
                                    else -> CriticalRed
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // App & Streaming Settings
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Studio & App Settings", style = MaterialTheme.typography.titleMedium)

                    Text("Theme Appearance", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeModePreference.entries.forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                label = { Text(mode.name) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Low-Latency Encoder Mode", style = MaterialTheme.typography.titleSmall)
                            Text("Zero B-frames & tuned keyframe interval", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = lowLatencyMode, onCheckedChange = onLowLatencyChange)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatic Reconnect", style = MaterialTheme.typography.titleSmall)
                            Text("Exponential backoff on signal drop", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = autoReconnectEnabled, onCheckedChange = onAutoReconnectChange)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Stream & Quota Notifications", style = MaterialTheme.typography.titleSmall)
                            Text("Alerts for start, drop, quota & payments", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = streamNotificationsEnabled, onCheckedChange = onStreamNotificationsChange)
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBackendDialog = true }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Cloud Worker Backend URL", style = MaterialTheme.typography.titleSmall)
                            Text(
                                backendUrlOverride.ifBlank { BuildConfig.TECHLIVE_API_BASE_URL },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Icon(Icons.Filled.Edit, contentDescription = "Configure Backend URL")
                    }
                }
            }
        }

        // Legal Policies & Moderation
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
                    Text("Legal Policies & Safety", style = MaterialTheme.typography.titleMedium)
                    PolicyRow("Terms of Service") {
                        activePolicyDialog = "Terms of Service" to
                            "Tech Live provides multi-platform live streaming, screen broadcast, and cloud prerecorded scheduling. Users are strictly responsible for complying with YouTube, Facebook, TikTok, Telegram, and copyright laws."
                    }
                    PolicyRow("Privacy Policy") {
                        activePolicyDialog = "Privacy Policy" to
                            "Tech Live authenticates via Google Identity, encrypts RTMP/OAuth tokens on backend servers, stores masked key identifiers only in client views, and collects zero unnecessary storage permissions."
                    }
                    PolicyRow("Refund Policy") {
                        activePolicyDialog = "Refund Policy" to
                            "Verified Monthly and Lifetime subscriptions are eligible for refund review within 7 days of activation for technical service faults via our Admin Verification Console."
                    }
                    PolicyRow("Acceptable Use Policy") {
                        activePolicyDialog = "Acceptable Use Policy" to
                            "Streaming illegal, copyrighted without permission, abusive, or deceptive content will result in immediate stream termination and account suspension."
                    }
                    PolicyRow("Report Stream / Abuse", icon = Icons.Filled.Flag) {
                        showReportDialog = true
                    }
                }
            }
        }

        // Account Actions
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onSignOutClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("sign_out_button")
                ) {
                    Icon(Icons.Filled.Logout, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign Out")
                }

                TextButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("delete_account_button")
                ) {
                    Text("Delete Account & Data", color = CriticalRed)
                }
            }
        }
    }
}

@Composable
private fun PlanOptionCard(
    title: String,
    priceLabel: String,
    features: List<String>,
    isCurrent: Boolean,
    buttonText: String,
    onSelect: (() -> Unit)?,
    testTag: String = ""
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (isCurrent) 2.dp else 1.dp,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(priceLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            features.forEach { feat ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = HealthEmerald, modifier = Modifier.size(16.dp))
                    Text(feat, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (onSelect != null && !isCurrent) {
                Button(
                    onClick = onSelect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (testTag.isNotBlank()) Modifier.testTag(testTag) else Modifier)
                ) {
                    Text(buttonText)
                }
            } else if (isCurrent) {
                AssistChip(onClick = {}, label = { Text("Current Active Plan") })
            }
        }
    }
}

@Composable
private fun PolicyRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Description,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null)
    }
}
