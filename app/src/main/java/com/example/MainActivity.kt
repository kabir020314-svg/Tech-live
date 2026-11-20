package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.StreamSourceType
import com.example.data.model.SubscriptionPlan
import com.example.data.model.ThemeModePreference
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.TechLiveRepository
import com.example.network.NetworkConnectivityObserver
import com.example.streaming.LiveBroadcastService
import com.example.streaming.RtmpConnectionTester
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.signOut
import com.example.ui.components.NetworkStatusBanner
import com.example.ui.components.QuotaReachedDialog
import com.example.ui.screens.*
import com.example.ui.theme.TechLiveTheme
import com.example.ui.viewmodel.TechLiveViewModel
import com.example.ui.viewmodel.UiState
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private enum class MainDestination(
    val label: String,
    val icon: ImageVector,
    val testTag: String
) {
    HOME("Home", Icons.Filled.Home, "nav_home"),
    LIVE("Live", Icons.Filled.Videocam, "nav_live"),
    VIDEOS("Videos", Icons.Filled.VideoLibrary, "nav_videos"),
    SCHEDULE("Schedule", Icons.Filled.EventNote, "nav_schedule"),
    ANALYTICS("Analytics", Icons.Filled.BarChart, "nav_analytics"),
    PROFILE("Profile", Icons.Filled.Person, "nav_profile")
}

private fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContext = applicationContext
        val repository = TechLiveRepository(appContext)
        val prefs = UserPreferencesRepository(appContext)
        val networkObserver = NetworkConnectivityObserver(appContext)
        val deviceCapabilities = RtmpConnectionTester.inspectDeviceEncoderCapabilities()

        setContent {
            val currentUser by remember { Firebase.auth.authStateFlow() }
                .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

            val themePref by prefs.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeModePreference.DARK)
            val isDark = when (themePref) {
                ThemeModePreference.DARK -> true
                ThemeModePreference.LIGHT -> false
                ThemeModePreference.SYSTEM -> isSystemInDarkTheme()
            }

            TechLiveTheme(darkTheme = isDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val user = currentUser
                    if (user == null) {
                        AuthScreen(onAuthSuccess = { })
                    } else {
                        key(user.uid) {
                            LaunchedEffect(user.uid) {
                                repository.ensureUserProfile(user)
                            }
                            val factory = remember(user.uid) {
                                object : ViewModelProvider.Factory {
                                    @Suppress("UNCHECKED_CAST")
                                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                        return TechLiveViewModel(
                                            repository = repository,
                                            prefs = prefs,
                                            networkObserver = networkObserver,
                                            currentUserId = user.uid,
                                            deviceCapabilities = deviceCapabilities
                                        ) as T
                                    }
                                }
                            }
                            val vm: TechLiveViewModel = viewModel(key = user.uid, factory = factory)
                            TechLiveAuthenticatedRoot(vm = vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TechLiveAuthenticatedRoot(vm: TechLiveViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    val networkStatus by vm.networkStatus.collectAsStateWithLifecycle()
    val profileState by vm.userProfileState.collectAsStateWithLifecycle()
    val settings by vm.systemSettings.collectAsStateWithLifecycle()
    val destinationsState by vm.destinationsState.collectAsStateWithLifecycle()
    val presetsState by vm.presetsState.collectAsStateWithLifecycle()
    val videosState by vm.videosState.collectAsStateWithLifecycle()
    val streamsState by vm.streamsState.collectAsStateWithLifecycle()
    val schedulesState by vm.schedulesState.collectAsStateWithLifecycle()
    val notificationsState by vm.notificationsState.collectAsStateWithLifecycle()
    val paymentsState by vm.paymentsState.collectAsStateWithLifecycle()

    val activeBroadcast by vm.activeBroadcast.collectAsStateWithLifecycle()
    val snackbarMsg by vm.snackbarMessage.collectAsStateWithLifecycle()
    val quotaPrompt by vm.quotaExceededPrompt.collectAsStateWithLifecycle()
    val rtmpTestResult by vm.rtmpTestResult.collectAsStateWithLifecycle()
    val appliedPreset by vm.selectedPresetToApply.collectAsStateWithLifecycle()

    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val lowLatencyMode by vm.lowLatencyMode.collectAsStateWithLifecycle()
    val autoReconnect by vm.autoReconnectEnabled.collectAsStateWithLifecycle()
    val streamNotifications by vm.streamNotificationsEnabled.collectAsStateWithLifecycle()
    val backendUrlOverride by vm.backendUrlOverride.collectAsStateWithLifecycle()

    val adminSummary by vm.adminMetricsSummary.collectAsStateWithLifecycle()
    val adminUsersState by vm.adminUsersState.collectAsStateWithLifecycle()
    val adminPaymentsState by vm.adminPaymentsState.collectAsStateWithLifecycle()
    val adminStreamsState by vm.adminActiveStreamsState.collectAsStateWithLifecycle()
    val adminWithdrawalsState by vm.adminWithdrawalsState.collectAsStateWithLifecycle()
    val adminReportsState by vm.adminReportsState.collectAsStateWithLifecycle()
    val adminAuditLogsState by vm.adminAuditLogsState.collectAsStateWithLifecycle()

    val profile = (profileState as? UiState.Success)?.data
    val remainingDaily = vm.remainingDailySeconds(profile, settings)
    val storageLimit = vm.storageLimitBytes(profile, settings)

    var selectedTab by remember { mutableStateOf(MainDestination.HOME) }
    var preferredLiveSource by remember { mutableStateOf(StreamSourceType.CAMERA) }
    var showAdminConsole by remember { mutableStateOf(false) }
    var pendingUpgradePlan by remember { mutableStateOf<SubscriptionPlan?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMsg) {
        val msg = snackbarMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.consumeSnackbar()
    }

    if (quotaPrompt) {
        QuotaReachedDialog(
            settings = settings,
            onDismiss = { vm.dismissQuotaDialog() },
            onSelectPlan = { plan ->
                vm.dismissQuotaDialog()
                pendingUpgradePlan = plan
                selectedTab = MainDestination.PROFILE
            }
        )
    }

    if (showAdminConsole && profile?.isAdminUser == true) {
        BackHandler { showAdminConsole = false }
        AdminDashboardScreen(
            summary = adminSummary,
            settings = settings,
            usersState = adminUsersState,
            paymentsState = adminPaymentsState,
            activeStreamsState = adminStreamsState,
            withdrawalsState = adminWithdrawalsState,
            reportsState = adminReportsState,
            auditLogsState = adminAuditLogsState,
            onVerifyPayment = vm::adminVerifyPayment,
            onUpdateUser = vm::adminUpdateUser,
            onStopStream = vm::adminStopStream,
            onRequestWithdrawal = vm::adminRequestWithdrawal,
            onUpdateReport = vm::adminUpdateReport,
            onSaveSystemSettings = vm::adminSaveSystemSettings,
            onBack = { showAdminConsole = false }
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar {
                        MainDestination.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = selectedTab == dest,
                                onClick = { selectedTab = dest },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label, maxLines = 1) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail {
                        MainDestination.entries.forEach { dest ->
                            NavigationRailItem(
                                selected = selectedTab == dest,
                                onClick = { selectedTab = dest },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    NetworkStatusBanner(status = networkStatus)

                    Box(modifier = Modifier.weight(1f)) {
                        when (selectedTab) {
                            MainDestination.HOME -> HomeScreen(
                                profileState = profileState,
                                settings = settings,
                                streamsState = streamsState,
                                schedulesState = schedulesState,
                                presetsState = presetsState,
                                notificationsState = notificationsState,
                                activeBroadcast = activeBroadcast,
                                remainingSeconds = remainingDaily,
                                storageLimitBytes = storageLimit,
                                onStartLiveClick = {
                                    preferredLiveSource = StreamSourceType.CAMERA
                                    selectedTab = MainDestination.LIVE
                                },
                                onScreenLiveClick = {
                                    preferredLiveSource = StreamSourceType.SCREEN
                                    selectedTab = MainDestination.LIVE
                                },
                                onUploadVideoClick = { selectedTab = MainDestination.VIDEOS },
                                onScheduleStreamClick = { selectedTab = MainDestination.SCHEDULE },
                                onUpgradeClick = { selectedTab = MainDestination.PROFILE },
                                onPresetStartClick = { preset ->
                                    vm.applyPreset(preset)
                                    selectedTab = MainDestination.LIVE
                                },
                                onStopBroadcastClick = {
                                    vm.stopLiveStream(
                                        onServiceStopRequested = {
                                            context.startService(
                                                Intent(context, LiveBroadcastService::class.java).apply {
                                                    action = LiveBroadcastService.ACTION_STOP
                                                }
                                            )
                                        }
                                    )
                                },
                                onMarkNotificationRead = vm::markNotificationRead,
                                onOpenAdminConsole = { showAdminConsole = true }
                            )

                            MainDestination.LIVE -> LiveStudioScreen(
                                initialTab = if (preferredLiveSource == StreamSourceType.SCREEN) LiveStudioTab.SCREEN else LiveStudioTab.CAMERA,
                                userId = vm.currentUserId,
                                profile = profile,
                                settings = settings,
                                deviceCaps = vm.deviceCapabilities,
                                destinationsState = destinationsState,
                                presetsState = presetsState,
                                activeBroadcast = activeBroadcast,
                                presetToApply = appliedPreset,
                                rtmpTestResult = rtmpTestResult,
                                onConsumePreset = vm::consumeAppliedPreset,
                                onStartLiveStream = { title, desc, src, orient, aspect, res, fps, vBr, aBr, dests, onServiceStart ->
                                    vm.startLiveStream(
                                        title = title,
                                        description = desc,
                                        sourceType = src,
                                        orientation = orient,
                                        aspectMode = aspect,
                                        resolution = res,
                                        fps = fps,
                                        videoBitrateKbps = vBr,
                                        audioBitrateKbps = aBr,
                                        destinations = dests,
                                        onServiceStartRequested = { sTitle, sMode ->
                                            LiveBroadcastService.start(context, sTitle, sMode)
                                            onServiceStart(sTitle, sMode)
                                        }
                                    )
                                },
                                onStopLiveStream = { onStopped ->
                                    vm.stopLiveStream(
                                        onServiceStopRequested = {
                                            context.startService(
                                                Intent(context, LiveBroadcastService::class.java).apply {
                                                    action = LiveBroadcastService.ACTION_STOP
                                                }
                                            )
                                            onStopped()
                                        }
                                    )
                                },
                                onSaveDestination = vm::saveDestination,
                                onDeleteDestination = vm::deleteDestination,
                                onTestDestination = vm::testDestinationConnection,
                                onClearRtmpTest = vm::clearRtmpTestResult,
                                onInitiateOAuth = { provider, onUrl ->
                                    vm.initiatePlatformOAuth(provider) { authUrl ->
                                        onUrl(authUrl)
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                                        }
                                    }
                                },
                                onSavePreset = vm::savePreset,
                                onDeletePreset = vm::deletePreset,
                                onApplyPreset = vm::applyPreset
                            )

                            MainDestination.VIDEOS -> VideosScreen(
                                profile = profile,
                                settings = settings,
                                deviceCaps = vm.deviceCapabilities,
                                videosState = videosState,
                                destinationsState = destinationsState,
                                storageLimitBytes = storageLimit,
                                onUploadValidatedVideo = { title, meta ->
                                    vm.registerValidatedVideoAndProcess(title, meta)
                                },
                                onResumeUpload = vm::resumeVideoUpload,
                                onRenameVideo = vm::renameVideo,
                                onDeleteVideo = vm::deleteVideo,
                                onLaunchCloudStream = vm::launchCloudVideoStream,
                                onUpgradeClick = { selectedTab = MainDestination.PROFILE }
                            )

                            MainDestination.SCHEDULE -> ScheduleScreen(
                                userId = vm.currentUserId,
                                profile = profile,
                                settings = settings,
                                videosState = videosState,
                                destinationsState = destinationsState,
                                schedulesState = schedulesState,
                                streamsState = streamsState,
                                onSaveSchedule = vm::saveSchedule,
                                onDuplicateSchedule = vm::duplicateSchedule,
                                onCancelSchedule = vm::cancelSchedule,
                                onStopCloudStream = vm::stopCloudStream,
                                onUpgradeClick = { selectedTab = MainDestination.PROFILE }
                            )

                            MainDestination.ANALYTICS -> AnalyticsScreen(
                                profileState = profileState,
                                streamsState = streamsState,
                                videosState = videosState,
                                storageLimitBytes = storageLimit,
                                onStopCloudStream = vm::stopCloudStream
                            )

                            MainDestination.PROFILE -> ProfileSettingsScreen(
                                profileState = profileState,
                                settings = settings,
                                paymentsState = paymentsState,
                                themeMode = themeMode,
                                lowLatencyMode = lowLatencyMode,
                                autoReconnectEnabled = autoReconnect,
                                streamNotificationsEnabled = streamNotifications,
                                backendUrlOverride = backendUrlOverride,
                                storageLimitBytes = storageLimit,
                                remainingDailySeconds = remainingDaily,
                                initialUpgradePlan = pendingUpgradePlan,
                                onConsumeUpgradePrompt = { pendingUpgradePlan = null },
                                onThemeModeChange = vm::setThemeMode,
                                onLowLatencyChange = vm::setLowLatencyMode,
                                onAutoReconnectChange = vm::setAutoReconnect,
                                onStreamNotificationsChange = vm::setStreamNotifications,
                                onSaveBackendUrl = vm::setBackendUrlOverride,
                                onSubmitPayment = vm::submitPayment,
                                onSubmitReport = vm::submitAbuseReport,
                                onOpenAdminConsole = { showAdminConsole = true },
                                onSignOutClick = {
                                    signOut(credentialManager = credentialManager, scope = scope)
                                },
                                onDeleteAccountConfirmed = {
                                    vm.deleteOwnAccount {
                                        signOut(credentialManager = credentialManager, scope = scope)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
