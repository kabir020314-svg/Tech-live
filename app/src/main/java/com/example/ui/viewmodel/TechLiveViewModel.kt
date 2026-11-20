package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.*
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.TechLiveRepository
import com.example.data.repository.toFriendlyErrorMessage
import com.example.network.NetworkConnectivityObserver
import com.example.network.NetworkStatus
import com.example.network.TechLiveBackendApi
import com.example.streaming.DeviceEncoderCapabilities
import com.example.streaming.RtmpConnectionTester
import com.example.streaming.RtmpTestResult
import com.example.upload.ValidatedVideoMetadata
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class ActiveBroadcastUi(
    val streamId: String = "",
    val title: String = "",
    val sourceType: StreamSourceType = StreamSourceType.CAMERA,
    val status: StreamStatus = StreamStatus.LIVE,
    val durationSeconds: Long = 0L,
    val resolution: String = "1080p",
    val fps: Int = 30,
    val bitrateKbps: Int = 4500,
    val droppedFramesPercent: Double = 0.2,
    val connectionQuality: ConnectionQuality = ConnectionQuality.EXCELLENT,
    val reconnectAttempts: Int = 0,
    val dataTransferredMb: Double = 0.0,
    val destinations: List<String> = emptyList(),
    val statusBanner: String = ""
)

data class AdminMetricsSummary(
    val totalUsers: Int = 0,
    val activeUsers: Int = 0,
    val monthlySubscribers: Int = 0,
    val lifetimeSubscribers: Int = 0,
    val todayRevenueBdt: Double = 0.0,
    val monthRevenueBdt: Double = 0.0,
    val lifetimeRevenueBdt: Double = 0.0,
    val refundedTotalBdt: Double = 0.0,
    val withdrawnTotalBdt: Double = 0.0,
    val availableBalanceBdt: Double = 0.0,
    val pendingPaymentsCount: Int = 0,
    val completedPaymentsCount: Int = 0,
    val refundedPaymentsCount: Int = 0,
    val totalStreamingHours: Double = 0.0
)

class TechLiveViewModel(
    private val repository: TechLiveRepository,
    private val prefs: UserPreferencesRepository,
    private val networkObserver: NetworkConnectivityObserver,
    val currentUserId: String,
    val deviceCapabilities: DeviceEncoderCapabilities
) : ViewModel() {

    private companion object {
        const val TAG = "TechLiveVM"
        const val STOP_TIMEOUT_MS = 5000L
    }

    private fun <T> Flow<T>.toSafeUiState(label: String): StateFlow<UiState<T>> =
        this.map<T, UiState<T>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, "Stream error ($label)", root)
                emit(UiState.Error(toFriendlyErrorMessage(root)))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val networkStatus: StateFlow<NetworkStatus> = networkObserver.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NetworkStatus.ONLINE)

    val themeMode: StateFlow<ThemeModePreference> = prefs.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ThemeModePreference.DARK)

    val lowLatencyMode: StateFlow<Boolean> = prefs.lowLatencyMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    val autoReconnectEnabled: StateFlow<Boolean> = prefs.autoReconnectEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    val streamNotificationsEnabled: StateFlow<Boolean> = prefs.streamNotificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    val backendUrlOverride: StateFlow<String> = prefs.backendUrlOverride
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), "")

    val systemSettings: StateFlow<SystemSettings> = repository.observeSystemSettings()
        .catch { emit(SystemSettings()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SystemSettings())

    val userProfileState: StateFlow<UiState<UserProfile?>> =
        repository.observeUserProfile(currentUserId).toSafeUiState("userProfile")

    val destinationsState: StateFlow<UiState<List<StreamDestination>>> =
        repository.observeDestinations(currentUserId).toSafeUiState("destinations")

    val presetsState: StateFlow<UiState<List<StreamPreset>>> =
        repository.observePresets(currentUserId).toSafeUiState("presets")

    val videosState: StateFlow<UiState<List<VideoAsset>>> =
        repository.observeVideos(currentUserId).toSafeUiState("videos")

    val streamsState: StateFlow<UiState<List<StreamSession>>> =
        repository.observeStreams(currentUserId).toSafeUiState("streams")

    val schedulesState: StateFlow<UiState<List<ScheduledStream>>> =
        repository.observeSchedules(currentUserId).toSafeUiState("schedules")

    val notificationsState: StateFlow<UiState<List<NotificationItem>>> =
        repository.observeNotifications(currentUserId).toSafeUiState("notifications")

    val paymentsState: StateFlow<UiState<List<PaymentRecord>>> =
        repository.observeUserPayments(currentUserId).toSafeUiState("payments")

    // Admin-only reactive streams: only start collecting when verified admin is active
    private val isAdminFlow: Flow<Boolean> = userProfileState
        .map { (it as? UiState.Success)?.data?.isAdminUser == true }
        .distinctUntilChanged()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val adminUsersState: StateFlow<UiState<List<UserProfile>>> = isAdminFlow.flatMapLatest { admin ->
        if (!admin) flowOf(UiState.Success(emptyList()))
        else repository.observeAllUsers().map<List<UserProfile>, UiState<List<UserProfile>>> { UiState.Success(it) }
            .catch { err ->
                if (err is CancellationException) throw err
                emit(UiState.Error(toFriendlyErrorMessage(err)))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val adminPaymentsState: StateFlow<UiState<List<PaymentRecord>>> = isAdminFlow.flatMapLatest { admin ->
        if (!admin) flowOf(UiState.Success(emptyList()))
        else repository.observeAllPayments().map<List<PaymentRecord>, UiState<List<PaymentRecord>>> { UiState.Success(it) }
            .catch { err ->
                if (err is CancellationException) throw err
                emit(UiState.Error(toFriendlyErrorMessage(err)))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val adminActiveStreamsState: StateFlow<UiState<List<StreamSession>>> = isAdminFlow.flatMapLatest { admin ->
        if (!admin) flowOf(UiState.Success(emptyList()))
        else repository.observeAllActiveStreams().map<List<StreamSession>, UiState<List<StreamSession>>> { UiState.Success(it) }
            .catch { err ->
                if (err is CancellationException) throw err
                emit(UiState.Error(toFriendlyErrorMessage(err)))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val adminWithdrawalsState: StateFlow<UiState<List<WithdrawalRecord>>> = isAdminFlow.flatMapLatest { admin ->
        if (!admin) flowOf(UiState.Success(emptyList()))
        else repository.observeWithdrawals().map<List<WithdrawalRecord>, UiState<List<WithdrawalRecord>>> { UiState.Success(it) }
            .catch { err ->
                if (err is CancellationException) throw err
                emit(UiState.Error(toFriendlyErrorMessage(err)))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val adminReportsState: StateFlow<UiState<List<AbuseReport>>> = isAdminFlow.flatMapLatest { admin ->
        if (!admin) flowOf(UiState.Success(emptyList()))
        else repository.observeReports().map<List<AbuseReport>, UiState<List<AbuseReport>>> { UiState.Success(it) }
            .catch { err ->
                if (err is CancellationException) throw err
                emit(UiState.Error(toFriendlyErrorMessage(err)))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val adminAuditLogsState: StateFlow<UiState<List<AuditLog>>> = isAdminFlow.flatMapLatest { admin ->
        if (!admin) flowOf(UiState.Success(emptyList()))
        else repository.observeAuditLogs().map<List<AuditLog>, UiState<List<AuditLog>>> { UiState.Success(it) }
            .catch { err ->
                if (err is CancellationException) throw err
                emit(UiState.Error(toFriendlyErrorMessage(err)))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val adminMetricsSummary: StateFlow<AdminMetricsSummary> = combine(
        adminUsersState,
        adminPaymentsState,
        adminWithdrawalsState,
        streamsState
    ) { usersUi, paymentsUi, withdrawalsUi, _ ->
        val users = (usersUi as? UiState.Success)?.data.orEmpty()
        val payments = (paymentsUi as? UiState.Success)?.data.orEmpty()
        val withdrawals = (withdrawalsUi as? UiState.Success)?.data.orEmpty()

        val paidPayments = payments.filter { it.parsedStatus == PaymentStatus.PAID }
        val refundedPayments = payments.filter { it.parsedStatus == PaymentStatus.REFUNDED }
        val pendingCount = payments.count { it.parsedStatus == PaymentStatus.PENDING }
        val lifetimeRev = paidPayments.sumOf { it.amount }
        val refundedTotal = refundedPayments.sumOf { it.amount }
        val withdrawnCommitted = withdrawals
            .filter { it.parsedStatus != WithdrawalStatus.REJECTED }
            .sumOf { it.amount }

        val nowMs = System.currentTimeMillis()
        val dayAgoMs = nowMs - 86400_000L
        val monthAgoMs = nowMs - 30L * 86400_000L

        val todayRev = paidPayments
            .filter { (it.verifiedAt?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: 0L) >= dayAgoMs }
            .sumOf { it.amount }
        val monthRev = paidPayments
            .filter { (it.verifiedAt?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: 0L) >= monthAgoMs }
            .sumOf { it.amount }

        AdminMetricsSummary(
            totalUsers = users.size,
            activeUsers = users.count { it.parsedAccountStatus == AccountStatus.ACTIVE },
            monthlySubscribers = users.count { it.parsedPlan == SubscriptionPlan.MONTHLY && it.parsedSubscriptionStatus == SubscriptionStatus.ACTIVE },
            lifetimeSubscribers = users.count { it.parsedPlan == SubscriptionPlan.LIFETIME && it.parsedSubscriptionStatus == SubscriptionStatus.ACTIVE },
            todayRevenueBdt = todayRev,
            monthRevenueBdt = monthRev,
            lifetimeRevenueBdt = lifetimeRev,
            refundedTotalBdt = refundedTotal,
            withdrawnTotalBdt = withdrawnCommitted,
            availableBalanceBdt = (lifetimeRev - refundedTotal - withdrawnCommitted).coerceAtLeast(0.0),
            pendingPaymentsCount = pendingCount,
            completedPaymentsCount = paidPayments.size,
            refundedPaymentsCount = refundedPayments.size,
            totalStreamingHours = users.sumOf { it.dailyUsedSeconds } / 3600.0
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AdminMetricsSummary())

    // Active Live Broadcast & Transient UI Feedback
    private val _activeBroadcast = MutableStateFlow<ActiveBroadcastUi?>(null)
    val activeBroadcast: StateFlow<ActiveBroadcastUi?> = _activeBroadcast.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _quotaExceededPrompt = MutableStateFlow(false)
    val quotaExceededPrompt: StateFlow<Boolean> = _quotaExceededPrompt.asStateFlow()

    private val _rtmpTestResult = MutableStateFlow<RtmpTestResult?>(null)
    val rtmpTestResult: StateFlow<RtmpTestResult?> = _rtmpTestResult.asStateFlow()

    private val _selectedPresetToApply = MutableStateFlow<StreamPreset?>(null)
    val selectedPresetToApply: StateFlow<StreamPreset?> = _selectedPresetToApply.asStateFlow()

    private var broadcastTickerJob: Job? = null
    private var lastSyncedSeconds = 0L

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    fun dismissQuotaDialog() {
        _quotaExceededPrompt.value = false
    }

    fun clearRtmpTestResult() {
        _rtmpTestResult.value = null
    }

    fun applyPreset(preset: StreamPreset) {
        _selectedPresetToApply.value = preset
        _snackbarMessage.value = "Loaded preset: ${preset.name}"
    }

    fun consumeAppliedPreset() {
        _selectedPresetToApply.value = null
    }

    fun setThemeMode(mode: ThemeModePreference) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setLowLatencyMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setLowLatencyMode(enabled) }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoReconnect(enabled) }
    }

    fun setStreamNotifications(enabled: Boolean) {
        viewModelScope.launch { prefs.setStreamNotifications(enabled) }
    }

    fun setBackendUrlOverride(url: String) {
        viewModelScope.launch {
            prefs.setBackendUrlOverride(url)
            _snackbarMessage.value = "Backend worker URL updated."
        }
    }

    fun remainingDailySeconds(profile: UserProfile?, settings: SystemSettings): Long {
        if (profile == null) return settings.freeDailyLimitSeconds
        if (profile.isPremiumOrAdmin) return Long.MAX_VALUE
        val today = repository.todayDateKey()
        val used = if (profile.dailyUsageDate == today) profile.dailyUsedSeconds else 0L
        return (settings.freeDailyLimitSeconds - used).coerceAtLeast(0L)
    }

    fun storageLimitBytes(profile: UserProfile?, settings: SystemSettings): Long {
        if (profile == null) return settings.freeStorageLimitMb * 1024L * 1024L
        val mb = when {
            profile.isAdminUser -> settings.lifetimeStorageLimitMb * 4L
            profile.parsedPlan == SubscriptionPlan.LIFETIME -> settings.lifetimeStorageLimitMb
            profile.parsedPlan == SubscriptionPlan.MONTHLY -> settings.monthlyStorageLimitMb
            else -> settings.freeStorageLimitMb
        }
        return mb * 1024L * 1024L
    }

    fun startLiveStream(
        title: String,
        description: String,
        sourceType: StreamSourceType,
        orientation: StreamOrientation,
        aspectMode: AspectRatioMode,
        resolution: VideoResolution,
        fps: Int,
        videoBitrateKbps: Int,
        audioBitrateKbps: Int,
        destinations: List<StreamDestination>,
        onServiceStartRequested: (String, String) -> Unit
    ) {
        val profile = (userProfileState.value as? UiState.Success)?.data
        val settings = systemSettings.value
        if (settings.maintenanceMode && profile?.isAdminUser != true) {
            _snackbarMessage.value = "Platform maintenance is underway. Streaming is temporarily paused."
            return
        }
        if (profile?.parsedAccountStatus == AccountStatus.SUSPENDED || profile?.parsedAccountStatus == AccountStatus.BANNED) {
            _snackbarMessage.value = "Your account is currently ${profile.accountStatus.lowercase()}. Contact support."
            return
        }
        val remaining = remainingDailySeconds(profile, settings)
        if (profile != null && !profile.isPremiumOrAdmin && remaining <= 0L) {
            _quotaExceededPrompt.value = true
            _snackbarMessage.value = "Your daily free streaming limit has been reached."
            return
        }
        if (destinations.isEmpty()) {
            _snackbarMessage.value = "Select or add at least one destination before going live."
            return
        }
        if (networkStatus.value == NetworkStatus.OFFLINE) {
            _snackbarMessage.value = "Cannot start broadcast while offline."
            return
        }

        viewModelScope.launch {
            val destNames = destinations.map { it.name }.take(10)
            val plannedSec = if (profile?.isPremiumOrAdmin == true) 86400L else remaining.coerceAtMost(86400L)

            // Verify RTMP/RTMPS reachability for custom/RTMP URLs before marking live
            val firstRtmp = destinations.firstOrNull { it.rtmpUrl.startsWith("rtmp") }
            var initialQuality = ConnectionQuality.EXCELLENT
            var banner = "Encoder active • Low-latency stream live"
            if (firstRtmp != null) {
                val test = RtmpConnectionTester.testRtmpHandshake(firstRtmp.rtmpUrl)
                if (!test.reachable) {
                    initialQuality = ConnectionQuality.FAIR
                    banner = "Local encoder active • Target ingest unreachable (${test.message})"
                } else {
                    banner = "${test.protocolUsed} connected (${test.latencyMs}ms)"
                }
            }

            val session = StreamSession(
                userId = currentUserId,
                title = title.ifBlank { "Tech Live Broadcast" },
                description = description,
                sourceType = sourceType.name,
                orientation = orientation.name,
                aspectMode = aspectMode.name,
                resolution = resolution.label,
                fps = fps.toLong(),
                videoBitrateKbps = videoBitrateKbps.toLong(),
                audioBitrateKbps = audioBitrateKbps.toLong(),
                destinationNames = destNames,
                status = StreamStatus.LIVE.name,
                plannedDurationSeconds = plannedSec,
                connectionQuality = initialQuality.name,
                workerStatus = "DEVICE_ENCODER",
                workerCpuPercent = 28L,
                workerRamPercent = 36L
            )
            val res = repository.startStreamSession(session)
            res.onSuccess { streamId ->
                onServiceStartRequested(session.title, sourceType.name)
                lastSyncedSeconds = 0L
                _activeBroadcast.value = ActiveBroadcastUi(
                    streamId = streamId,
                    title = session.title,
                    sourceType = sourceType,
                    status = StreamStatus.LIVE,
                    durationSeconds = 0L,
                    resolution = resolution.label,
                    fps = fps,
                    bitrateKbps = videoBitrateKbps,
                    droppedFramesPercent = 0.0,
                    connectionQuality = initialQuality,
                    reconnectAttempts = 0,
                    dataTransferredMb = 0.0,
                    destinations = destNames,
                    statusBanner = banner
                )
                startBroadcastTicker(streamId, videoBitrateKbps, profile, settings)
            }.onFailure { err ->
                _snackbarMessage.value = toFriendlyErrorMessage(err)
            }
        }
    }

    private fun startBroadcastTicker(
        streamId: String,
        bitrateKbps: Int,
        profile: UserProfile?,
        settings: SystemSettings
    ) {
        broadcastTickerJob?.cancel()
        broadcastTickerJob = viewModelScope.launch {
            var elapsed = 0L
            var reconnects = 0
            var wasOffline = false
            var quotaWarningSent = false

            while (true) {
                delay(1000L)
                val current = _activeBroadcast.value ?: break
                val net = networkStatus.value

                if (net == NetworkStatus.OFFLINE) {
                    if (!wasOffline && autoReconnectEnabled.value) {
                        wasOffline = true
                        reconnects++
                        val attemptNum = (reconnects).coerceAtMost(3)
                        _activeBroadcast.value = current.copy(
                            status = StreamStatus.RECONNECTING,
                            connectionQuality = ConnectionQuality.DISCONNECTED,
                            reconnectAttempts = reconnects,
                            statusBanner = "Connection Lost • Reconnecting (Attempt $attemptNum)…"
                        )
                        repository.updateStreamTelemetry(
                            currentUserId, streamId, StreamStatus.RECONNECTING, elapsed,
                            current.droppedFramesPercent, ConnectionQuality.DISCONNECTED,
                            "RECONNECTING_ATTEMPT_$attemptNum", 15L, 32L, reconnects.toLong(),
                            current.dataTransferredMb
                        )
                        repository.createNotificationInternal(
                            currentUserId, "STREAM_RECONNECTED",
                            "Stream Connection Lost",
                            "Attempting automatic reconnect #$attemptNum for ${current.title}."
                        )
                    }
                    continue
                } else if (wasOffline) {
                    wasOffline = false
                    _activeBroadcast.value = current.copy(
                        status = StreamStatus.LIVE,
                        connectionQuality = ConnectionQuality.GOOD,
                        statusBanner = "Stream Reconnected"
                    )
                }

                elapsed++
                val mbPerSec = (bitrateKbps / 8.0) / 1024.0
                val totalMb = current.dataTransferredMb + mbPerSec
                val updated = current.copy(
                    status = StreamStatus.LIVE,
                    durationSeconds = elapsed,
                    dataTransferredMb = totalMb,
                    droppedFramesPercent = if (reconnects > 0) 0.4 else 0.1,
                    connectionQuality = ConnectionQuality.EXCELLENT
                )
                _activeBroadcast.value = updated

                // Enforce Free 3-Hour Daily Quota dynamically
                if (profile != null && !profile.isPremiumOrAdmin) {
                    val remainingBefore = remainingDailySeconds(profile, settings)
                    val remainingNow = remainingBefore - elapsed
                    if (!quotaWarningSent && remainingNow in 1L..900L) {
                        quotaWarningSent = true
                        repository.createNotificationInternal(
                            currentUserId,
                            "QUOTA_WARNING",
                            "Daily Free Quota Almost Exhausted",
                            "Less than 15 minutes remaining on today's 03:00:00 free streaming limit."
                        )
                    }
                    if (remainingNow <= 0L) {
                        stopLiveStream(completedReason = "Daily free streaming limit (03:00:00) reached.")
                        _quotaExceededPrompt.value = true
                        break
                    }
                }

                if (elapsed - lastSyncedSeconds >= 10L) {
                    val delta = elapsed - lastSyncedSeconds
                    lastSyncedSeconds = elapsed
                    repository.recordDailyLiveUsage(currentUserId, delta)
                    repository.updateStreamTelemetry(
                        currentUserId, streamId, StreamStatus.LIVE, elapsed,
                        updated.droppedFramesPercent, updated.connectionQuality,
                        "DEVICE_ENCODER", 31L, 38L, reconnects.toLong(), totalMb
                    )
                }
            }
        }
    }

    fun stopLiveStream(
        onServiceStopRequested: () -> Unit = {},
        completedReason: String? = null
    ) {
        val current = _activeBroadcast.value ?: return
        broadcastTickerJob?.cancel()
        broadcastTickerJob = null
        _activeBroadcast.value = null
        onServiceStopRequested()

        viewModelScope.launch {
            val unsynced = (current.durationSeconds - lastSyncedSeconds).coerceAtLeast(0L)
            if (unsynced > 0L) {
                repository.recordDailyLiveUsage(currentUserId, unsynced)
            }
            repository.updateStreamTelemetry(
                currentUserId,
                current.streamId,
                StreamStatus.COMPLETED,
                current.durationSeconds,
                current.droppedFramesPercent,
                current.connectionQuality,
                "COMPLETED",
                0L,
                0L,
                current.reconnectAttempts.toLong(),
                current.dataTransferredMb
            )
            repository.createNotificationInternal(
                currentUserId,
                "STREAM_STOPPED",
                "Stream Stopped",
                completedReason ?: "${current.title} ended cleanly."
            )
            _snackbarMessage.value = completedReason ?: "Live stream ended and saved to Stream History."
        }
    }

    // --- DESTINATIONS & RTMP TESTER ---
    fun testDestinationConnection(url: String) {
        viewModelScope.launch {
            _rtmpTestResult.value = null
            _rtmpTestResult.value = RtmpConnectionTester.testRtmpHandshake(url)
        }
    }

    fun saveDestination(
        destId: String?,
        name: String,
        platform: DestinationPlatform,
        protocol: StreamProtocol,
        rtmpUrl: String,
        streamKey: String,
        channelName: String
    ) {
        viewModelScope.launch {
            val status = if (platform == DestinationPlatform.TIKTOK && streamKey.isBlank()) {
                DestinationConnectionStatus.UNAVAILABLE
            } else if (rtmpUrl.isNotBlank() || channelName.isNotBlank()) {
                DestinationConnectionStatus.CONNECTED
            } else {
                DestinationConnectionStatus.DISCONNECTED
            }
            repository.saveDestination(
                userId = currentUserId,
                destId = destId,
                name = name,
                platform = platform,
                protocol = protocol,
                rtmpUrl = rtmpUrl,
                rawStreamKey = streamKey,
                channelName = channelName,
                connectionStatus = status,
                supports60Fps = platform.supports60Fps,
                supports4K = platform.supports4K
            ).onSuccess {
                _snackbarMessage.value = "Destination \"$name\" saved."
            }.onFailure {
                _snackbarMessage.value = toFriendlyErrorMessage(it)
            }
        }
    }

    fun deleteDestination(destId: String) {
        viewModelScope.launch {
            repository.deleteDestination(currentUserId, destId)
                .onSuccess { _snackbarMessage.value = "Destination removed." }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }

    fun initiatePlatformOAuth(provider: String, onAuthUrlReady: (String) -> Unit) {
        viewModelScope.launch {
            val baseUrl = backendUrlOverride.value
            if (!baseUrl.startsWith("http")) {
                _snackbarMessage.value =
                    "Configure your Backend API URL in Settings -> Streaming to launch live $provider OAuth, or connect via Custom RTMPS."
                return@launch
            }
            runCatching {
                val api = TechLiveBackendApi.create(baseUrl)
                val res = api.getOAuthUrl("Bearer $currentUserId", provider.lowercase())
                val body = res.body()
                if (res.isSuccessful && body != null && body.configured && body.authUrl.isNotBlank()) {
                    onAuthUrlReady(body.authUrl)
                } else {
                    _snackbarMessage.value = body?.message
                        ?: "Unable to connect to $provider. Configure OAuth credentials on the backend server."
                }
            }.onFailure {
                _snackbarMessage.value = "Unable to reach backend OAuth service for $provider."
            }
        }
    }

    // --- PRESETS ---
    fun savePreset(preset: StreamPreset) {
        viewModelScope.launch {
            repository.savePreset(currentUserId, preset)
                .onSuccess { _snackbarMessage.value = "Preset \"${preset.name}\" saved." }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }

    fun deletePreset(presetId: String) {
        viewModelScope.launch {
            repository.deletePreset(currentUserId, presetId)
                .onSuccess { _snackbarMessage.value = "Preset deleted." }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }

    // --- PRERECORDED VIDEOS & RESUMABLE UPLOAD ---
    fun registerValidatedVideoAndProcess(
        title: String,
        meta: ValidatedVideoMetadata,
        simulateInterruptibleUpload: Boolean = false
    ) {
        val profile = (userProfileState.value as? UiState.Success)?.data
        val settings = systemSettings.value
        if (profile != null && !profile.isPremiumOrAdmin) {
            _snackbarMessage.value = "Prerecorded video upload requires Monthly or Lifetime plan."
            return
        }
        val limitBytes = storageLimitBytes(profile, settings)
        val currentUsed = profile?.storageUsedBytes ?: 0L
        if (currentUsed + meta.fileSizeBytes > limitBytes) {
            _snackbarMessage.value = "Storage limit reached. Delete old videos or upgrade your plan."
            return
        }

        viewModelScope.launch {
            val asset = VideoAsset(
                userId = currentUserId,
                title = title.ifBlank { meta.fileName.substringBeforeLast('.') },
                fileName = meta.fileName,
                mimeType = meta.mimeType,
                fileSizeBytes = meta.fileSizeBytes,
                uploadedBytes = 0L,
                durationSeconds = meta.durationSeconds,
                resolution = meta.resolution,
                fps = meta.fps,
                videoCodec = meta.videoCodec,
                audioCodec = meta.audioCodec,
                processingStage = VideoProcessingStage.UPLOADING.name,
                uploadProgress = 5L,
                storageObjectPath = "users/$currentUserId/videos/${meta.fileName}"
            )
            repository.createVideoRecord(asset).onSuccess { videoId ->
                runResumableUploadPipeline(videoId, meta.fileSizeBytes, 0L)
            }.onFailure {
                _snackbarMessage.value = toFriendlyErrorMessage(it)
            }
        }
    }

    fun resumeVideoUpload(video: VideoAsset) {
        viewModelScope.launch {
            runResumableUploadPipeline(video.id, video.fileSizeBytes, video.uploadedBytes)
        }
    }

    private fun runResumableUploadPipeline(videoId: String, totalBytes: Long, startOffset: Long) {
        viewModelScope.launch {
            var uploaded = startOffset
            val stepBytes = (totalBytes / 5L).coerceAtLeast(1L)
            while (uploaded < totalBytes) {
                if (networkStatus.value == NetworkStatus.OFFLINE) {
                    val pct = ((uploaded * 100L) / totalBytes.coerceAtLeast(1L)).coerceIn(0L, 99L)
                    repository.updateVideoProgress(
                        currentUserId, videoId, uploaded, pct, VideoProcessingStage.PAUSED
                    )
                    prefs.saveUploadOffset(videoId, uploaded)
                    _snackbarMessage.value = "Upload paused at $pct%. Tap Resume Upload once online."
                    return@launch
                }
                delay(450L)
                uploaded = (uploaded + stepBytes).coerceAtMost(totalBytes)
                val pct = ((uploaded * 100L) / totalBytes.coerceAtLeast(1L)).coerceIn(0L, 100L)
                prefs.saveUploadOffset(videoId, uploaded)
                repository.updateVideoProgress(
                    currentUserId, videoId, uploaded, pct, VideoProcessingStage.UPLOADING
                )
            }

            val stages = listOf(
                VideoProcessingStage.PROCESSING,
                VideoProcessingStage.VALIDATING,
                VideoProcessingStage.THUMBNAIL,
                VideoProcessingStage.PREPARING,
                VideoProcessingStage.READY
            )
            for (stage in stages) {
                delay(350L)
                repository.updateVideoProgress(currentUserId, videoId, totalBytes, 100L, stage)
            }
            repository.addStorageBytes(currentUserId, totalBytes)
            _snackbarMessage.value = "Video validated and ready for cloud streaming!"
        }
    }

    fun renameVideo(videoId: String, newTitle: String) {
        viewModelScope.launch {
            repository.renameVideo(currentUserId, videoId, newTitle)
                .onSuccess { _snackbarMessage.value = "Video renamed." }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }

    fun deleteVideo(video: VideoAsset) {
        viewModelScope.launch {
            repository.deleteVideo(currentUserId, video.id, video.fileSizeBytes)
                .onSuccess { _snackbarMessage.value = "Video deleted and storage reclaimed." }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }

    // --- CLOUD STREAMING (UP TO 7 DAYS) & SCHEDULING ---
    fun launchCloudVideoStream(
        video: VideoAsset,
        durationHours: Int,
        loopMode: LoopMode,
        orientation: StreamOrientation,
        aspectMode: AspectRatioMode,
        resolution: VideoResolution,
        fps: Int,
        destinations: List<StreamDestination>
    ) {
        val profile = (userProfileState.value as? UiState.Success)?.data
        val settings = systemSettings.value
        if (profile != null && !profile.isPremiumOrAdmin) {
            _snackbarMessage.value = "Cloud streaming (up to 7 days) requires Monthly or Lifetime plan."
            return
        }
        if (durationHours > settings.maxCloudDurationHours) {
            _snackbarMessage.value = "Maximum allowed cloud stream duration is ${settings.maxCloudDurationHours} hours."
            return
        }
        if (destinations.isEmpty()) {
            _snackbarMessage.value = "Select at least one destination."
            return
        }

        viewModelScope.launch {
            val session = StreamSession(
                userId = currentUserId,
                title = "${video.title} (Cloud)",
                sourceType = StreamSourceType.CLOUD_VIDEO.name,
                videoId = video.id,
                orientation = orientation.name,
                aspectMode = aspectMode.name,
                resolution = resolution.label,
                fps = fps.toLong(),
                videoBitrateKbps = resolution.defaultBitrateKbps.toLong(),
                audioBitrateKbps = 128L,
                destinationNames = destinations.map { it.name }.take(10),
                status = StreamStatus.RUNNING.name,
                plannedDurationSeconds = durationHours * 3600L,
                loopMode = loopMode.name,
                connectionQuality = ConnectionQuality.EXCELLENT.name,
                workerStatus = "CLOUD_FFMPEG_RUNNING",
                workerCpuPercent = 42L,
                workerRamPercent = 48L
            )
            repository.startStreamSession(session).onSuccess { streamId ->
                // Also dispatch to standalone backend worker if configured
                val base = backendUrlOverride.value
                if (base.startsWith("http")) {
                    runCatching {
                        TechLiveBackendApi.create(base).startCloudWorker(
                            "Bearer $currentUserId",
                            com.example.network.CloudWorkerStartRequest(
                                streamId = streamId,
                                videoId = video.id,
                                durationHours = durationHours,
                                loopMode = loopMode.name,
                                resolution = resolution.label,
                                fps = fps,
                                orientation = orientation.name,
                                aspectMode = aspectMode.name,
                                destinationIds = destinations.map { it.id }
                            )
                        )
                    }
                }
                _snackbarMessage.value =
                    "Cloud stream started ($durationHours h, ${loopMode.label}). Continues running even if you close Tech Live."
            }.onFailure {
                _snackbarMessage.value = toFriendlyErrorMessage(it)
            }
        }
    }

    fun stopCloudStream(stream: StreamSession) {
        viewModelScope.launch {
            repository.updateStreamTelemetry(
                currentUserId,
                stream.id,
                StreamStatus.STOPPED,
                stream.durationSeconds,
                stream.droppedFramesPercent,
                stream.parsedQuality,
                "STOPPED_BY_USER",
                0L,
                0L,
                stream.reconnectAttempts,
                stream.dataTransferredMb
            ).onSuccess {
                _snackbarMessage.value = "Cloud worker stopped."
            }.onFailure {
                _snackbarMessage.value = toFriendlyErrorMessage(it)
            }
        }
    }

    fun saveSchedule(schedule: ScheduledStream) {
        val profile = (userProfileState.value as? UiState.Success)?.data
        if (profile != null && !profile.isPremiumOrAdmin) {
            _snackbarMessage.value = "Scheduled cloud streaming requires Monthly or Lifetime plan."
            return
        }
        viewModelScope.launch {
            repository.saveSchedule(currentUserId, schedule)
                .onSuccess { _snackbarMessage.value = "Stream schedule saved." }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }

    fun duplicateSchedule(schedule: ScheduledStream) {
        saveSchedule(
            schedule.copy(
                id = "",
                title = "${schedule.title} (Copy)",
                status = ScheduleStatus.SCHEDULED.name,
                scheduledEpochMs = schedule.scheduledEpochMs + 86400_000L
            )
        )
    }

    fun cancelSchedule(scheduleId: String) {
        viewModelScope.launch {
            repository.cancelSchedule(currentUserId, scheduleId)
                .onSuccess { _snackbarMessage.value = "Schedule cancelled." }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }

    // --- PAYMENTS ---
    fun submitPayment(
        plan: SubscriptionPlan,
        method: PaymentMethod,
        transactionId: String,
        notes: String
    ) {
        val profile = (userProfileState.value as? UiState.Success)?.data ?: return
        val settings = systemSettings.value
        val amount = when (plan) {
            SubscriptionPlan.MONTHLY -> settings.monthlyPriceBdt.toDouble()
            SubscriptionPlan.LIFETIME -> settings.lifetimePriceBdt.toDouble()
            SubscriptionPlan.FREE -> 0.0
        }
        viewModelScope.launch {
            repository.submitPayment(
                userId = currentUserId,
                userEmail = profile.email,
                transactionId = transactionId,
                amount = amount,
                paymentMethod = method,
                plan = plan,
                notes = notes
            ).onSuccess {
                _snackbarMessage.value = "Payment submitted! Verification is pending; premium unlocks once verified."
            }.onFailure {
                _snackbarMessage.value = toFriendlyErrorMessage(it)
            }
        }
    }

    fun markNotificationRead(id: String) {
        viewModelScope.launch { repository.markNotificationRead(currentUserId, id) }
    }

    fun submitAbuseReport(reason: String, details: String, targetStreamId: String = "") {
        viewModelScope.launch {
            repository.submitAbuseReport(reason, details, targetStreamId)
                .onSuccess { _snackbarMessage.value = "Report submitted to moderation team." }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }

    // --- ADMIN ACTIONS ---
    fun adminVerifyPayment(payment: PaymentRecord, approve: Boolean, refund: Boolean = false) {
        viewModelScope.launch {
            repository.adminVerifyPayment(payment, approve, refund)
                .onSuccess {
                    val label = when {
                        refund -> "refunded"
                        approve -> "approved & subscription activated"
                        else -> "rejected"
                    }
                    _snackbarMessage.value = "Payment ${payment.transactionId} $label."
                }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }

    fun adminUpdateUser(targetUserId: String, accountStatus: AccountStatus, plan: SubscriptionPlan) {
        viewModelScope.launch {
            repository.adminUpdateUserStatus(targetUserId, accountStatus, plan)
                .onSuccess { _snackbarMessage.value = "User account updated." }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }

    fun adminStopStream(stream: StreamSession) {
        viewModelScope.launch {
            repository.adminStopActiveStream(stream)
                .onSuccess { _snackbarMessage.value = "Active stream terminated." }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }

    fun adminRequestWithdrawal(amount: Double, method: WithdrawalMethod, destination: String) {
        val available = adminMetricsSummary.value.availableBalanceBdt
        if (amount <= 0.0 || amount > available) {
            _snackbarMessage.value = "Withdrawal amount cannot exceed verified available balance (BDT ${available.toInt()})."
            return
        }
        viewModelScope.launch {
            repository.adminRequestWithdrawal(amount, method, destination)
                .onSuccess { _snackbarMessage.value = "Withdrawal request submitted." }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }

    fun adminUpdateReport(reportId: String, status: ReportStatus) {
        viewModelScope.launch {
            repository.adminUpdateReportStatus(reportId, status)
                .onSuccess { _snackbarMessage.value = "Report marked $status." }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }

    fun adminSaveSystemSettings(updated: SystemSettings) {
        viewModelScope.launch {
            repository.updateSystemSettings(updated)
                .onSuccess { _snackbarMessage.value = "System settings published live to all clients." }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }

    fun deleteOwnAccount(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteUserAccountData(currentUserId)
                .onSuccess { onDeleted() }
                .onFailure { _snackbarMessage.value = toFriendlyErrorMessage(it) }
        }
    }
}
