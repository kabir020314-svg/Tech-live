package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.*
import com.example.streaming.DeviceEncoderCapabilities
import com.example.streaming.LiveBroadcastService
import com.example.streaming.RtmpConnectionTester
import com.example.streaming.RtmpTestResult
import com.example.ui.components.ActiveBroadcastHealthCard
import com.example.ui.theme.HealthEmerald
import com.example.ui.theme.LiveCrimson
import com.example.ui.theme.TelemetryMonoFont
import com.example.ui.theme.WarningAmber
import com.example.ui.util.availableFpsFor
import com.example.ui.util.availableResolutionsFor
import com.example.ui.viewmodel.ActiveBroadcastUi
import com.example.ui.viewmodel.UiState

enum class LiveStudioTab(val label: String) {
    CAMERA("Camera Live"),
    SCREEN("Screen Live"),
    DESTINATIONS("Destinations"),
    PRESETS("Presets")
}

@Composable
fun LiveStudioScreen(
    initialTab: LiveStudioTab = LiveStudioTab.CAMERA,
    userId: String,
    profile: UserProfile?,
    settings: SystemSettings,
    deviceCaps: DeviceEncoderCapabilities,
    destinationsState: UiState<List<StreamDestination>>,
    presetsState: UiState<List<StreamPreset>>,
    activeBroadcast: ActiveBroadcastUi?,
    presetToApply: StreamPreset?,
    rtmpTestResult: RtmpTestResult?,
    onConsumePreset: () -> Unit,
    onStartLiveStream: (
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
    ) -> Unit,
    onStopLiveStream: (() -> Unit) -> Unit,
    onSaveDestination: (String?, String, DestinationPlatform, StreamProtocol, String, String, String) -> Unit,
    onDeleteDestination: (String) -> Unit,
    onTestDestination: (String) -> Unit,
    onClearRtmpTest: () -> Unit,
    onInitiateOAuth: (String, (String) -> Unit) -> Unit,
    onSavePreset: (StreamPreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onApplyPreset: (StreamPreset) -> Unit
) {
    val context = LocalContext.current
    var currentTab by remember(initialTab) { mutableStateOf(initialTab) }

    val destinations = (destinationsState as? UiState.Success)?.data.orEmpty()
    val presets = (presetsState as? UiState.Success)?.data.orEmpty()

    // Shared stream studio configuration state
    var streamTitle by remember { mutableStateOf("Tech Live Studio Stream") }
    var streamDesc by remember { mutableStateOf("Low-latency multi-platform broadcast") }
    var orientation by remember { mutableStateOf(StreamOrientation.LANDSCAPE_16_9) }
    var aspectMode by remember { mutableStateOf(AspectRatioMode.FIT) }
    var selectedDestIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var resolution by remember { mutableStateOf(VideoResolution.R_1080P) }
    var fps by remember { mutableIntStateOf(30) }
    var videoBitrateKbps by remember { mutableIntStateOf(4500) }
    var audioBitrateKbps by remember { mutableIntStateOf(128) }
    var thumbnailUri by remember { mutableStateOf<Uri?>(null) }

    // Camera controls
    var useFrontCamera by remember { mutableStateOf(false) }
    var cameraEnabled by remember { mutableStateOf(true) }
    var micEnabled by remember { mutableStateOf(true) }
    var flashEnabled by remember { mutableStateOf(false) }

    // Screen Live controls
    var screenCameraOverlay by remember { mutableStateOf(true) }
    var screenOverlayPosition by remember { mutableStateOf("Bottom-Right") }
    var screenOverlaySize by remember { mutableStateOf("Medium") }
    var screenDeviceAudio by remember { mutableStateOf(true) }
    var screenProjectionGranted by remember { mutableStateOf(false) }

    LaunchedEffect(presetToApply) {
        presetToApply?.let { p ->
            orientation = p.parsedOrientation
            aspectMode = p.parsedAspectMode
            resolution = VideoResolution.fromLabel(p.resolution)
            fps = p.fps.toInt()
            videoBitrateKbps = p.videoBitrateKbps.toInt()
            audioBitrateKbps = p.audioBitrateKbps.toInt()
            micEnabled = p.micEnabled
            cameraEnabled = p.cameraEnabled
            currentTab = LiveStudioTab.CAMERA
            onConsumePreset()
        }
    }

    val selectedDestinations = remember(destinations, selectedDestIds) {
        destinations.filter { selectedDestIds.contains(it.id) }
    }
    val allowedResolutions = remember(selectedDestinations, deviceCaps, settings) {
        availableResolutionsFor(selectedDestinations, deviceCaps, settings)
    }
    val allowedFps = remember(selectedDestinations, deviceCaps, settings) {
        availableFpsFor(selectedDestinations, deviceCaps, settings)
    }

    LaunchedEffect(allowedResolutions, allowedFps) {
        if (resolution !in allowedResolutions) {
            resolution = allowedResolutions.last()
            videoBitrateKbps = resolution.defaultBitrateKbps
        }
        if (fps !in allowedFps) {
            fps = allowedFps.last()
        }
    }

    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            screenProjectionGranted = true
            onStartLiveStream(
                streamTitle,
                streamDesc,
                StreamSourceType.SCREEN,
                orientation,
                aspectMode,
                resolution,
                fps,
                videoBitrateKbps,
                audioBitrateKbps,
                selectedDestinations
            ) { t, m -> LiveBroadcastService.start(context, t, m) }
        }
    }

    val thumbnailPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> thumbnailUri = uri }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = LiveStudioTab.entries.indexOf(currentTab),
            edgePadding = 12.dp
        ) {
            LiveStudioTab.entries.forEach { tab ->
                Tab(
                    selected = currentTab == tab,
                    onClick = { currentTab = tab },
                    text = { Text(tab.label) },
                    modifier = Modifier.testTag("live_tab_${tab.name.lowercase()}")
                )
            }
        }

        if (activeBroadcast != null) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                ActiveBroadcastHealthCard(
                    broadcast = activeBroadcast,
                    onStopClick = { onStopLiveStream { LiveBroadcastService.stop(context) } }
                )
            }
        }

        when (currentTab) {
            LiveStudioTab.CAMERA -> {
                CameraLiveTab(
                    streamTitle = streamTitle,
                    onTitleChange = { streamTitle = it },
                    streamDesc = streamDesc,
                    onDescChange = { streamDesc = it },
                    useFrontCamera = useFrontCamera,
                    onSwitchCamera = { useFrontCamera = !useFrontCamera },
                    cameraEnabled = cameraEnabled,
                    onToggleCamera = { cameraEnabled = !cameraEnabled },
                    micEnabled = micEnabled,
                    onToggleMic = { micEnabled = !micEnabled },
                    flashEnabled = flashEnabled,
                    onToggleFlash = { flashEnabled = !flashEnabled },
                    orientation = orientation,
                    onOrientationChange = { orientation = it },
                    aspectMode = aspectMode,
                    onAspectModeChange = { aspectMode = it },
                    resolution = resolution,
                    allowedResolutions = allowedResolutions,
                    onResolutionChange = {
                        resolution = it
                        videoBitrateKbps = it.defaultBitrateKbps
                    },
                    fps = fps,
                    allowedFps = allowedFps,
                    onFpsChange = { fps = it },
                    videoBitrateKbps = videoBitrateKbps,
                    onVideoBitrateChange = { videoBitrateKbps = it },
                    audioBitrateKbps = audioBitrateKbps,
                    onAudioBitrateChange = { audioBitrateKbps = it },
                    destinations = destinations,
                    selectedDestIds = selectedDestIds,
                    onToggleDest = { id ->
                        selectedDestIds = if (selectedDestIds.contains(id)) selectedDestIds - id else selectedDestIds + id
                    },
                    thumbnailUri = thumbnailUri,
                    onPickThumbnail = {
                        thumbnailPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    isLive = activeBroadcast != null,
                    onStartClick = {
                        onStartLiveStream(
                            streamTitle,
                            streamDesc,
                            StreamSourceType.CAMERA,
                            orientation,
                            aspectMode,
                            resolution,
                            fps,
                            videoBitrateKbps,
                            audioBitrateKbps,
                            selectedDestinations
                        ) { t, m -> LiveBroadcastService.start(context, t, m) }
                    },
                    onStopClick = { onStopLiveStream { LiveBroadcastService.stop(context) } },
                    onOpenDestinations = { currentTab = LiveStudioTab.DESTINATIONS },
                    onSaveAsPreset = { name ->
                        onSavePreset(
                            StreamPreset(
                                userId = userId,
                                name = name,
                                resolution = resolution.label,
                                fps = fps.toLong(),
                                videoBitrateKbps = videoBitrateKbps.toLong(),
                                audioBitrateKbps = audioBitrateKbps.toLong(),
                                orientation = orientation.name,
                                aspectMode = aspectMode.name,
                                destinationNames = selectedDestinations.map { it.name },
                                micEnabled = micEnabled,
                                cameraEnabled = cameraEnabled
                            )
                        )
                    }
                )
            }

            LiveStudioTab.SCREEN -> {
                ScreenLiveTab(
                    streamTitle = streamTitle,
                    onTitleChange = { streamTitle = it },
                    cameraOverlay = screenCameraOverlay,
                    onToggleCameraOverlay = { screenCameraOverlay = !screenCameraOverlay },
                    overlayPosition = screenOverlayPosition,
                    onOverlayPositionChange = { screenOverlayPosition = it },
                    overlaySize = screenOverlaySize,
                    onOverlaySizeChange = { screenOverlaySize = it },
                    micEnabled = micEnabled,
                    onToggleMic = { micEnabled = !micEnabled },
                    deviceAudioEnabled = screenDeviceAudio,
                    onToggleDeviceAudio = { screenDeviceAudio = !screenDeviceAudio },
                    orientation = orientation,
                    onOrientationChange = { orientation = it },
                    resolution = resolution,
                    allowedResolutions = allowedResolutions,
                    onResolutionChange = { resolution = it },
                    fps = fps,
                    allowedFps = allowedFps,
                    onFpsChange = { fps = it },
                    videoBitrateKbps = videoBitrateKbps,
                    onBitrateChange = { videoBitrateKbps = it },
                    destinations = destinations,
                    selectedDestIds = selectedDestIds,
                    onToggleDest = { id ->
                        selectedDestIds = if (selectedDestIds.contains(id)) selectedDestIds - id else selectedDestIds + id
                    },
                    isLive = activeBroadcast != null,
                    projectionGranted = screenProjectionGranted,
                    onStartScreenStream = {
                        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                    },
                    onStopClick = { onStopLiveStream { LiveBroadcastService.stop(context) } },
                    onOpenDestinations = { currentTab = LiveStudioTab.DESTINATIONS }
                )
            }

            LiveStudioTab.DESTINATIONS -> {
                DestinationsTab(
                    destinations = destinations,
                    rtmpTestResult = rtmpTestResult,
                    onSaveDestination = onSaveDestination,
                    onDeleteDestination = onDeleteDestination,
                    onTestDestination = onTestDestination,
                    onClearRtmpTest = onClearRtmpTest,
                    onInitiateOAuth = onInitiateOAuth
                )
            }

            LiveStudioTab.PRESETS -> {
                PresetsTab(
                    userId = userId,
                    presets = presets,
                    destinations = destinations,
                    onSavePreset = onSavePreset,
                    onDeletePreset = onDeletePreset,
                    onApplyPreset = onApplyPreset
                )
            }
        }
    }
}

@Composable
private fun CameraLiveTab(
    streamTitle: String,
    onTitleChange: (String) -> Unit,
    streamDesc: String,
    onDescChange: (String) -> Unit,
    useFrontCamera: Boolean,
    onSwitchCamera: () -> Unit,
    cameraEnabled: Boolean,
    onToggleCamera: () -> Unit,
    micEnabled: Boolean,
    onToggleMic: () -> Unit,
    flashEnabled: Boolean,
    onToggleFlash: () -> Unit,
    orientation: StreamOrientation,
    onOrientationChange: (StreamOrientation) -> Unit,
    aspectMode: AspectRatioMode,
    onAspectModeChange: (AspectRatioMode) -> Unit,
    resolution: VideoResolution,
    allowedResolutions: List<VideoResolution>,
    onResolutionChange: (VideoResolution) -> Unit,
    fps: Int,
    allowedFps: List<Int>,
    onFpsChange: (Int) -> Unit,
    videoBitrateKbps: Int,
    onVideoBitrateChange: (Int) -> Unit,
    audioBitrateKbps: Int,
    onAudioBitrateChange: (Int) -> Unit,
    destinations: List<StreamDestination>,
    selectedDestIds: Set<String>,
    onToggleDest: (String) -> Unit,
    thumbnailUri: Uri?,
    onPickThumbnail: () -> Unit,
    isLive: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onOpenDestinations: () -> Unit,
    onSaveAsPreset: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results[Manifest.permission.CAMERA] == true &&
            results[Manifest.permission.RECORD_AUDIO] == true
    }

    val micLevel by remember(hasPermissions, micEnabled) {
        if (hasPermissions && micEnabled) RtmpConnectionTester.observeMicrophoneLevel(true)
        else kotlinx.coroutines.flow.flowOf(0f)
    }.collectAsState(initial = 0f)

    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    LaunchedEffect(flashEnabled, boundCamera, useFrontCamera) {
        boundCamera?.cameraControl?.enableTorch(flashEnabled && !useFrontCamera && cameraEnabled)
    }

    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text("Save Stream Preset") },
            text = {
                OutlinedTextField(
                    value = presetNameInput,
                    onValueChange = { presetNameInput = it },
                    label = { Text("Preset Name (e.g. Gaming 1080p)") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (presetNameInput.isNotBlank()) {
                        onSaveAsPreset(presetNameInput.trim())
                        presetNameInput = ""
                        showSavePresetDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Real CameraX Preview Card
        item {
            val previewHeight = if (orientation == StreamOrientation.PORTRAIT_9_16) 300.dp else 210.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(previewHeight)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF0A0E17))
            ) {
                if (!hasPermissions) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Camera & Microphone Permission Required", style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val perms = buildList {
                                    add(Manifest.permission.CAMERA)
                                    add(Manifest.permission.RECORD_AUDIO)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }.toTypedArray()
                                permLauncher.launch(perms)
                            },
                            modifier = Modifier.testTag("grant_camera_permissions")
                        ) {
                            Text("Enable Camera & Mic")
                        }
                    }
                } else if (cameraEnabled) {
                    key(useFrontCamera) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                }
                            },
                            update = { previewView ->
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                                cameraProviderFuture.addListener({
                                    runCatching {
                                        val provider = cameraProviderFuture.get()
                                        val preview = Preview.Builder().build().also {
                                            it.surfaceProvider = previewView.surfaceProvider
                                        }
                                        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                                        else CameraSelector.DEFAULT_BACK_CAMERA
                                        provider.unbindAll()
                                        boundCamera = provider.bindToLifecycle(lifecycleOwner, selector, preview)
                                    }
                                }, ContextCompat.getMainExecutor(context))
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.VideocamOff, contentDescription = "Camera disabled", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(42.dp))
                            Spacer(Modifier.height(6.dp))
                            Text("Camera OFF (Audio-only stream)", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }

                // Overlay HUD Controls
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0x99070B14))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(onClick = onSwitchCamera, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch Camera", tint = Color.White)
                        }
                        IconButton(onClick = onToggleCamera, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(
                                if (cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                                contentDescription = "Toggle Camera",
                                tint = if (cameraEnabled) Color.White else LiveCrimson
                            )
                        }
                        IconButton(onClick = onToggleMic, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(
                                if (micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                                contentDescription = "Toggle Microphone",
                                tint = if (micEnabled) Color.White else LiveCrimson
                            )
                        }
                        if (!useFrontCamera) {
                            IconButton(onClick = onToggleFlash, modifier = Modifier.minimumInteractiveComponentSize()) {
                                Icon(
                                    if (flashEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                                    contentDescription = "Toggle Flash",
                                    tint = if (flashEnabled) WarningAmber else Color.White
                                )
                            }
                        }
                    }
                    // Real-time Microphone VU meter
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${resolution.label} ${fps}fps",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                        LinearProgressIndicator(
                            progress = { if (micEnabled) micLevel else 0f },
                            modifier = Modifier
                                .width(52.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = HealthEmerald
                        )
                    }
                }
            }
        }

        // Stream Title, Description, Thumbnail
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = streamTitle,
                        onValueChange = onTitleChange,
                        label = { Text("Stream Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = streamDesc,
                        onValueChange = onDescChange,
                        label = { Text("Description & Tags") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (thumbnailUri != null) "Custom thumbnail attached" else "Optional stream thumbnail",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = onPickThumbnail) {
                            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pick Thumbnail")
                        }
                    }
                }
            }
        }

        // Multi-Destination Selector
        item {
            DestinationSelectorCard(
                destinations = destinations,
                selectedDestIds = selectedDestIds,
                onToggleDest = onToggleDest,
                onOpenDestinations = onOpenDestinations
            )
        }

        // Aspect Ratio, Resolution, Dynamic FPS, Bitrate
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Broadcast Encoding & Aspect", style = MaterialTheme.typography.titleSmall)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StreamOrientation.entries.forEach { item ->
                            FilterChip(
                                selected = orientation == item,
                                onClick = { onOrientationChange(item) },
                                label = { Text("${item.ratioText} ${item.label}") }
                            )
                        }
                    }

                    Text("Aspect Scaling", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(AspectRatioMode.entries) { mode ->
                            FilterChip(
                                selected = aspectMode == mode,
                                onClick = { onAspectModeChange(mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }

                    Text("Resolution (Hardware & Destination Validated)", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(allowedResolutions) { res ->
                            FilterChip(
                                selected = resolution == res,
                                onClick = { onResolutionChange(res) },
                                label = { Text(res.label) }
                            )
                        }
                    }

                    Text("Frame Rate (FPS)", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        allowedFps.forEach { f ->
                            FilterChip(
                                selected = fps == f,
                                onClick = { onFpsChange(f) },
                                label = { Text("$f FPS") }
                            )
                        }
                    }

                    Text(
                        "Video Bitrate: ${videoBitrateKbps} kbps (${String.format(java.util.Locale.US, "%.1f", videoBitrateKbps / 1000.0)} Mbps)",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = videoBitrateKbps.toFloat(),
                        onValueChange = { onVideoBitrateChange(it.toInt()) },
                        valueRange = 600f..25000f
                    )

                    Text("Audio Bitrate: $audioBitrateKbps kbps", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(96, 128, 192, 256).forEach { ab ->
                            FilterChip(
                                selected = audioBitrateKbps == ab,
                                onClick = { onAudioBitrateChange(ab) },
                                label = { Text("${ab}k") }
                            )
                        }
                    }
                }
            }
        }

        // Go Live / Stop + Save Preset
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { showSavePresetDialog = true },
                    modifier = Modifier.height(52.dp)
                ) {
                    Icon(Icons.Filled.BookmarkAdd, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Save Preset")
                }

                Button(
                    onClick = if (isLive) onStopClick else onStartClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLive) MaterialTheme.colorScheme.error else LiveCrimson
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("camera_go_live_button")
                ) {
                    Icon(if (isLive) Icons.Filled.Stop else Icons.Filled.Sensors, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isLive) "STOP STREAM" else "GO LIVE NOW", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ScreenLiveTab(
    streamTitle: String,
    onTitleChange: (String) -> Unit,
    cameraOverlay: Boolean,
    onToggleCameraOverlay: () -> Unit,
    overlayPosition: String,
    onOverlayPositionChange: (String) -> Unit,
    overlaySize: String,
    onOverlaySizeChange: (String) -> Unit,
    micEnabled: Boolean,
    onToggleMic: () -> Unit,
    deviceAudioEnabled: Boolean,
    onToggleDeviceAudio: () -> Unit,
    orientation: StreamOrientation,
    onOrientationChange: (StreamOrientation) -> Unit,
    resolution: VideoResolution,
    allowedResolutions: List<VideoResolution>,
    onResolutionChange: (VideoResolution) -> Unit,
    fps: Int,
    allowedFps: List<Int>,
    onFpsChange: (Int) -> Unit,
    videoBitrateKbps: Int,
    onBitrateChange: (Int) -> Unit,
    destinations: List<StreamDestination>,
    selectedDestIds: Set<String>,
    onToggleDest: (String) -> Unit,
    isLive: Boolean,
    projectionGranted: Boolean,
    onStartScreenStream: () -> Unit,
    onStopClick: () -> Unit,
    onOpenDestinations: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Filled.ScreenShare, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("SCREEN LIVE (MediaProjection API)", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (projectionGranted) "System screen capture permission granted"
                                else "Requests official Android MediaProjection capture consent on start",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedTextField(
                        value = streamTitle,
                        onValueChange = onTitleChange,
                        label = { Text("Screen Broadcast Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Camera Overlay (PiP)", style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = cameraOverlay, onCheckedChange = { onToggleCameraOverlay() })
                    }

                    if (cameraOverlay) {
                        Text("Camera Overlay Position", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf("Top-Left", "Top-Right", "Bottom-Left", "Bottom-Right")) { pos ->
                                FilterChip(
                                    selected = overlayPosition == pos,
                                    onClick = { onOverlayPositionChange(pos) },
                                    label = { Text(pos) }
                                )
                            }
                        }
                        Text("Camera Overlay Size", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Small", "Medium", "Large").forEach { sz ->
                                FilterChip(
                                    selected = overlaySize == sz,
                                    onClick = { onOverlaySizeChange(sz) },
                                    label = { Text(sz) }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Microphone", style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = micEnabled, onCheckedChange = { onToggleMic() })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Device Audio (Internal Sound)", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                    "AudioPlaybackCapture supported on Android 10+"
                                else "Requires Android 10+ for internal audio capture",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = deviceAudioEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                            onCheckedChange = { onToggleDeviceAudio() }
                        )
                    }

                    Text("Orientation", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StreamOrientation.entries.forEach { o ->
                            FilterChip(
                                selected = orientation == o,
                                onClick = { onOrientationChange(o) },
                                label = { Text("${o.label} (${o.ratioText})") }
                            )
                        }
                    }

                    Text("Resolution & FPS", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(allowedResolutions) { res ->
                            FilterChip(
                                selected = resolution == res,
                                onClick = { onResolutionChange(res) },
                                label = { Text(res.label) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        allowedFps.forEach { f ->
                            FilterChip(
                                selected = fps == f,
                                onClick = { onFpsChange(f) },
                                label = { Text("$f FPS") }
                            )
                        }
                    }
                    Text("Bitrate: $videoBitrateKbps kbps", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = videoBitrateKbps.toFloat(),
                        onValueChange = { onBitrateChange(it.toInt()) },
                        valueRange = 1000f..20000f
                    )
                }
            }
        }

        item {
            DestinationSelectorCard(
                destinations = destinations,
                selectedDestIds = selectedDestIds,
                onToggleDest = onToggleDest,
                onOpenDestinations = onOpenDestinations
            )
        }

        item {
            Button(
                onClick = if (isLive) onStopClick else onStartScreenStream,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("start_screen_stream_button")
            ) {
                Icon(if (isLive) Icons.Filled.Stop else Icons.Filled.Cast, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isLive) "STOP SCREEN STREAM" else "START STREAM", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DestinationSelectorCard(
    destinations: List<StreamDestination>,
    selectedDestIds: Set<String>,
    onToggleDest: (String) -> Unit,
    onOpenDestinations: () -> Unit
) {
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Multi-Destination Output", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onOpenDestinations) {
                    Text("+ Add / Manage")
                }
            }
            if (destinations.isEmpty()) {
                Text(
                    "No destinations configured yet. Add YouTube, Facebook, Telegram, or Custom RTMP/RTMPS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                destinations.forEach { dest ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Checkbox(
                                checked = selectedDestIds.contains(dest.id),
                                onCheckedChange = { onToggleDest(dest.id) }
                            )
                            Column {
                                Text(dest.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${dest.parsedPlatform.displayName} • ${dest.protocol} • ${dest.maskedStreamKey}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (!dest.supports60Fps) {
                            AssistChip(onClick = {}, label = { Text("30fps max", style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationsTab(
    destinations: List<StreamDestination>,
    rtmpTestResult: RtmpTestResult?,
    onSaveDestination: (String?, String, DestinationPlatform, StreamProtocol, String, String, String) -> Unit,
    onDeleteDestination: (String) -> Unit,
    onTestDestination: (String) -> Unit,
    onClearRtmpTest: () -> Unit,
    onInitiateOAuth: (String, (String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var editingDest by remember { mutableStateOf<StreamDestination?>(null) }
    var destName by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf(DestinationPlatform.CUSTOM_RTMPS) }
    var protocol by remember { mutableStateOf(StreamProtocol.RTMPS) }
    var rtmpUrl by remember { mutableStateOf("rtmps://") }
    var streamKey by remember { mutableStateOf("") }
    var channelName by remember { mutableStateOf("") }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                showDialog = false
                onClearRtmpTest()
            },
            title = { Text(if (editingDest == null) "Add Destination" else "Edit Destination") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(DestinationPlatform.entries) { p ->
                            FilterChip(
                                selected = platform == p,
                                onClick = {
                                    platform = p
                                    rtmpUrl = p.defaultUrl
                                    protocol = if (p == DestinationPlatform.CUSTOM_RTMP) StreamProtocol.RTMP else StreamProtocol.RTMPS
                                },
                                label = { Text(p.displayName) }
                            )
                        }
                    }
                    if (platform == DestinationPlatform.TIKTOK) {
                        Text(
                            "TikTok Policy Notice: Direct TikTok Live API requires approved creator/developer access. If unavailable for your account, supply an authorized Custom RTMPS ingest URL & Stream Key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = WarningAmber
                        )
                    }
                    OutlinedTextField(
                        value = destName,
                        onValueChange = { destName = it },
                        label = { Text("Destination Name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = rtmpUrl,
                        onValueChange = { rtmpUrl = it },
                        label = { Text("RTMP / RTMPS URL") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = streamKey,
                        onValueChange = { streamKey = it },
                        label = { Text("Stream Key (Masked on Save)") },
                        singleLine = true
                    )
                    OutlinedButton(
                        onClick = { onTestDestination(rtmpUrl) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Test RTMP/RTMPS Handshake")
                    }
                    rtmpTestResult?.let { res ->
                        Text(
                            text = res.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (res.reachable) HealthEmerald else MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (destName.isNotBlank()) {
                        onSaveDestination(
                            editingDest?.id,
                            destName,
                            platform,
                            protocol,
                            rtmpUrl,
                            streamKey,
                            channelName
                        )
                        showDialog = false
                        onClearRtmpTest()
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDialog = false
                    onClearRtmpTest()
                }) { Text("Cancel") }
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
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Official Platform OAuth Integrations", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Connect YouTube or Facebook via official OAuth (never enter your password into third-party forms).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                onInitiateOAuth("youtube") { url ->
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Connect YouTube") }
                        FilledTonalButton(
                            onClick = {
                                onInitiateOAuth("facebook") { url ->
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Connect Facebook") }
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    editingDest = null
                    destName = ""
                    platform = DestinationPlatform.CUSTOM_RTMPS
                    protocol = StreamProtocol.RTMPS
                    rtmpUrl = "rtmps://"
                    streamKey = ""
                    channelName = ""
                    showDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("add_destination_button")
            ) {
                Icon(Icons.Filled.AddLink, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add Custom RTMP / RTMPS / Telegram")
            }
        }

        items(destinations, key = { it.id }) { dest ->
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
                        Text(dest.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${dest.parsedPlatform.displayName} • ${dest.protocol}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "${dest.rtmpUrl} (${dest.maskedStreamKey})",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = TelemetryMonoFont),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Row {
                        IconButton(onClick = { onTestDestination(dest.rtmpUrl) }) {
                            Icon(Icons.Filled.NetworkCheck, contentDescription = "Test RTMP connection")
                        }
                        IconButton(onClick = { onDeleteDestination(dest.id) }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete destination")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetsTab(
    userId: String,
    presets: List<StreamPreset>,
    destinations: List<StreamDestination>,
    onSavePreset: (StreamPreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onApplyPreset: (StreamPreset) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var res by remember { mutableStateOf("1080p") }
    var fps by remember { mutableLongStateOf(60L) }
    var bitrate by remember { mutableLongStateOf(6000L) }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New Stream Preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Preset Name") })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("720p", "1080p", "2160p").forEach { r ->
                            FilterChip(selected = res == r, onClick = { res = r }, label = { Text(r) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(24L, 30L, 60L).forEach { f ->
                            FilterChip(selected = fps == f, onClick = { fps = f }, label = { Text("${f}fps") })
                        }
                    }
                    Text("Video Bitrate: ${bitrate} kbps")
                    Slider(
                        value = bitrate.toFloat(),
                        onValueChange = { bitrate = it.toLong() },
                        valueRange = 1500f..24000f
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) {
                        onSavePreset(
                            StreamPreset(
                                userId = userId,
                                name = name.trim(),
                                resolution = res,
                                fps = fps,
                                videoBitrateKbps = bitrate,
                                destinationNames = destinations.take(1).map { it.name }.ifEmpty { listOf("YouTube Live") }
                            )
                        )
                        name = ""
                        showCreate = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Button(
                onClick = { showCreate = true },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Create Stream Preset")
            }
        }
        items(presets, key = { it.id }) { preset ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(preset.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${preset.resolution} • ${preset.fps} FPS • ${preset.videoBitrateKbps / 1000.0} Mbps • ${preset.parsedOrientation.ratioText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        FilledTonalButton(onClick = { onApplyPreset(preset) }) { Text("Use") }
                        IconButton(onClick = { onDeletePreset(preset.id) }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete preset")
                        }
                    }
                }
            }
        }
    }
}
