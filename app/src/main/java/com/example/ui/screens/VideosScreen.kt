package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.*
import com.example.streaming.DeviceEncoderCapabilities
import com.example.ui.theme.HealthEmerald
import com.example.ui.theme.TelemetryMonoFont
import com.example.ui.theme.WarningAmber
import com.example.ui.util.availableFpsFor
import com.example.ui.util.availableResolutionsFor
import com.example.ui.util.formatBytesHuman
import com.example.ui.util.formatDurationHms
import com.example.ui.viewmodel.UiState
import com.example.upload.ValidatedVideoMetadata
import com.example.upload.VideoMetadataValidator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideosScreen(
    profile: UserProfile?,
    settings: SystemSettings,
    deviceCaps: DeviceEncoderCapabilities,
    videosState: UiState<List<VideoAsset>>,
    destinationsState: UiState<List<StreamDestination>>,
    storageLimitBytes: Long,
    onUploadValidatedVideo: (String, ValidatedVideoMetadata) -> Unit,
    onResumeUpload: (VideoAsset) -> Unit,
    onRenameVideo: (String, String) -> Unit,
    onDeleteVideo: (VideoAsset) -> Unit,
    onLaunchCloudStream: (
        video: VideoAsset,
        durationHours: Int,
        loopMode: LoopMode,
        orientation: StreamOrientation,
        aspectMode: AspectRatioMode,
        resolution: VideoResolution,
        fps: Int,
        destinations: List<StreamDestination>
    ) -> Unit,
    onUpgradeClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var sortByNewest by remember { mutableStateOf(true) }
    var inspectingUriError by remember { mutableStateOf<String?>(null) }
    var isInspecting by remember { mutableStateOf(false) }

    var renameTarget by remember { mutableStateOf<VideoAsset?>(null) }
    var renameText by remember { mutableStateOf("") }

    var cloudLaunchTarget by remember { mutableStateOf<VideoAsset?>(null) }
    var previewTarget by remember { mutableStateOf<VideoAsset?>(null) }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            isInspecting = true
            inspectingUriError = null
            scope.launch {
                val meta = VideoMetadataValidator.inspectUri(context, uri, settings.maxUploadSizeMb)
                isInspecting = false
                if (!meta.valid) {
                    inspectingUriError = meta.errorMessage
                } else {
                    onUploadValidatedVideo(meta.fileName.substringBeforeLast('.'), meta)
                }
            }
        }
    }

    val destinations = (destinationsState as? UiState.Success)?.data.orEmpty()

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Video") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Video Title") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (renameText.isNotBlank()) {
                        onRenameVideo(target.id, renameText)
                        renameTarget = null
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    previewTarget?.let { v ->
        AlertDialog(
            onDismissRequest = { previewTarget = null },
            title = { Text(v.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("File: ${v.fileName} (${v.mimeType})")
                    Text("Size: ${formatBytesHuman(v.fileSizeBytes)}")
                    Text("Duration: ${formatDurationHms(v.durationSeconds)}")
                    Text("Stream: ${v.resolution} @ ${v.fps} FPS")
                    Text("Codecs: ${v.videoCodec} / ${v.audioCodec}")
                    Text("Stage: ${v.parsedStage.label}")
                }
            },
            confirmButton = {
                TextButton(onClick = { previewTarget = null }) { Text("Close") }
            }
        )
    }

    cloudLaunchTarget?.let { video ->
        var durationHours by remember { mutableIntStateOf(24) }
        var loopMode by remember { mutableStateOf(LoopMode.UNLIMITED) }
        var orientation by remember { mutableStateOf(StreamOrientation.LANDSCAPE_16_9) }
        var aspectMode by remember { mutableStateOf(AspectRatioMode.FIT) }
        var selectedDestIds by remember { mutableStateOf(destinations.take(1).map { it.id }.toSet()) }
        val selectedDests = destinations.filter { selectedDestIds.contains(it.id) }
        val allowedRes = availableResolutionsFor(selectedDests, deviceCaps, settings)
        val allowedFps = availableFpsFor(selectedDests, deviceCaps, settings)
        var resolution by remember { mutableStateOf(allowedRes.lastOrNull() ?: VideoResolution.R_1080P) }
        var fps by remember { mutableIntStateOf(allowedFps.firstOrNull() ?: 30) }

        ModalBottomSheet(onDismissRequest = { cloudLaunchTarget = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Launch Cloud Prerecorded Stream", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Video: ${video.title} • Runs on backend FFmpeg cloud workers even after closing Tech Live.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Continuous Cloud Duration (Max 7 Days)", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(1, 6, 12, 24, 48, 72, 168)) { hrs ->
                        FilterChip(
                            selected = durationHours == hrs,
                            onClick = { durationHours = hrs },
                            label = { Text(if (hrs == 168) "7 Days" else "${hrs}h") }
                        )
                    }
                }

                Text("Server-Side Loop Mode", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LoopMode.entries) { mode ->
                        FilterChip(
                            selected = loopMode == mode,
                            onClick = { loopMode = mode },
                            label = { Text(mode.label) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamOrientation.entries.forEach { o ->
                        FilterChip(
                            selected = orientation == o,
                            onClick = { orientation = o },
                            label = { Text(o.ratioText) }
                        )
                    }
                    AspectRatioMode.entries.take(2).forEach { m ->
                        FilterChip(
                            selected = aspectMode == m,
                            onClick = { aspectMode = m },
                            label = { Text(m.label) }
                        )
                    }
                }

                Text("Resolution & FPS", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(allowedRes) { r ->
                        FilterChip(
                            selected = resolution == r,
                            onClick = { resolution = r },
                            label = { Text(r.label) }
                        )
                    }
                    items(allowedFps) { f ->
                        FilterChip(
                            selected = fps == f,
                            onClick = { fps = f },
                            label = { Text("${f}fps") }
                        )
                    }
                }

                Text("Destinations", style = MaterialTheme.typography.labelMedium)
                if (destinations.isEmpty()) {
                    Text(
                        "Add a destination in the Live -> Destinations tab first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningAmber
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(destinations, key = { it.id }) { d ->
                            FilterChip(
                                selected = selectedDestIds.contains(d.id),
                                onClick = {
                                    selectedDestIds = if (selectedDestIds.contains(d.id)) {
                                        selectedDestIds - d.id
                                    } else {
                                        selectedDestIds + d.id
                                    }
                                },
                                label = { Text(d.name) }
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        onLaunchCloudStream(
                            video,
                            durationHours,
                            loopMode,
                            orientation,
                            aspectMode,
                            resolution,
                            fps,
                            selectedDests
                        )
                        cloudLaunchTarget = null
                    },
                    enabled = selectedDests.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("launch_cloud_stream_confirm")
                ) {
                    Icon(Icons.Filled.RocketLaunch, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Cloud Stream", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Storage Usage Header Card
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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Storage Used", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${formatBytesHuman(usedBytes)} / ${formatBytesHuman(storageLimitBytes)}",
                                style = MaterialTheme.typography.labelLarge.copy(fontFamily = TelemetryMonoFont),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (profile?.isPremiumOrAdmin != true) {
                            FilledTonalButton(onClick = onUpgradeClick) {
                                Text("Unlock 1 GB Uploads")
                            }
                        } else {
                            AssistChip(onClick = {}, label = { Text("Max ${settings.maxUploadSizeMb} MB/video") })
                        }
                    }
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }
        }

        // Upload Action & Search/Sort
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (profile?.isPremiumOrAdmin != true) {
                            onUpgradeClick()
                        } else {
                            videoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("upload_prerecorded_video_button")
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (profile?.isPremiumOrAdmin == true) "Upload Video (MP4/MOV)" else "Upgrade to Upload")
                }

                IconButton(
                    onClick = { sortByNewest = !sortByNewest },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Filled.Sort, contentDescription = "Sort videos")
                }
            }
        }

        if (isInspecting) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Validating codec, FPS, resolution, and file integrity…",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        inspectingUriError?.let { err ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f))) {
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search uploaded videos") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        when (videosState) {
            is UiState.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is UiState.Error -> item {
                Text(videosState.message, color = MaterialTheme.colorScheme.error)
            }
            is UiState.Success -> {
                val filtered = videosState.data
                    .filter { it.title.contains(searchQuery, ignoreCase = true) || it.fileName.contains(searchQuery, ignoreCase = true) }
                    .let { list -> if (sortByNewest) list else list.reversed() }

                if (filtered.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.img_cloud_worker),
                                    contentDescription = "Cloud video transcoding cluster",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(150.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                )
                                Text("No Prerecorded Videos Yet", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Upload MP4 or MOV files up to 1 GB with resumable transfer, server-side looping, and 7-day continuous cloud streaming.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { video ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
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
                                        Text(video.title, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "${formatBytesHuman(video.fileSizeBytes)} • ${formatDurationHms(video.durationSeconds)} • ${video.resolution} ${video.fps}fps",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Surface(
                                        color = if (video.parsedStage == VideoProcessingStage.READY) HealthEmerald.copy(alpha = 0.2f)
                                        else MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = video.parsedStage.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (video.parsedStage == VideoProcessingStage.READY) HealthEmerald
                                            else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                if (video.parsedStage != VideoProcessingStage.READY && video.parsedStage != VideoProcessingStage.FAILED) {
                                    LinearProgressIndicator(
                                        progress = { (video.uploadProgress / 100f).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (video.parsedStage == VideoProcessingStage.PAUSED) {
                                        FilledTonalButton(onClick = { onResumeUpload(video) }) {
                                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Resume Upload")
                                        }
                                    }
                                    if (video.parsedStage == VideoProcessingStage.READY) {
                                        Button(
                                            onClick = { cloudLaunchTarget = video },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Filled.CloudQueue, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Stream in Cloud")
                                        }
                                    }
                                    IconButton(onClick = { previewTarget = video }) {
                                        Icon(Icons.Filled.Info, contentDescription = "Video details")
                                    }
                                    IconButton(onClick = {
                                        renameText = video.title
                                        renameTarget = video
                                    }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Rename video")
                                    }
                                    IconButton(onClick = { onDeleteVideo(video) }) {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete video")
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
