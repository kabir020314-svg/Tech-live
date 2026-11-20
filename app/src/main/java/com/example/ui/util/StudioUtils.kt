package com.example.ui.util

import com.example.data.model.DestinationPlatform
import com.example.data.model.StreamDestination
import com.example.data.model.SystemSettings
import com.example.data.model.VideoResolution
import com.example.streaming.DeviceEncoderCapabilities
import java.util.Locale

fun formatDurationHms(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val hrs = safe / 3600L
    val mins = (safe % 3600L) / 60L
    val secs = safe % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
}

fun formatBytesHuman(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        safe >= gb -> String.format(Locale.US, "%.2f GB", safe / gb)
        safe >= mb -> String.format(Locale.US, "%.1f MB", safe / mb)
        safe >= kb -> String.format(Locale.US, "%.0f KB", safe / kb)
        else -> "$safe B"
    }
}

fun availableResolutionsFor(
    selectedDestinations: List<StreamDestination>,
    deviceCaps: DeviceEncoderCapabilities,
    settings: SystemSettings
): List<VideoResolution> {
    val destAllows4K = if (selectedDestinations.isEmpty()) true else selectedDestinations.all { it.supports4K }
    val destAllows1440 = if (selectedDestinations.isEmpty()) true else selectedDestinations.all {
        it.parsedPlatform != DestinationPlatform.TIKTOK && it.parsedPlatform != DestinationPlatform.TELEGRAM
    }
    return VideoResolution.entries.filter { res ->
        val inAdminAllowed = settings.allowedResolutions.contains(res.label)
        val deviceOk = when (res) {
            VideoResolution.R_2160P -> deviceCaps.supports4K
            else -> true
        }
        val destOk = when (res) {
            VideoResolution.R_2160P -> destAllows4K
            VideoResolution.R_1440P -> destAllows1440
            else -> true
        }
        inAdminAllowed && deviceOk && destOk
    }.ifEmpty { listOf(VideoResolution.R_720P, VideoResolution.R_1080P) }
}

fun availableFpsFor(
    selectedDestinations: List<StreamDestination>,
    deviceCaps: DeviceEncoderCapabilities,
    settings: SystemSettings
): List<Int> {
    val destAllows60 = if (selectedDestinations.isEmpty()) true else selectedDestinations.all { it.supports60Fps }
    return settings.allowedFps.map { it.toInt() }.filter { fps ->
        if (fps >= 60) deviceCaps.supports60Fps && destAllows60 else true
    }.ifEmpty { listOf(30) }
}
