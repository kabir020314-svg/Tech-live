package com.example.upload

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ValidatedVideoMetadata(
    val valid: Boolean,
    val fileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val durationSeconds: Long,
    val resolution: String,
    val fps: Long,
    val videoCodec: String,
    val audioCodec: String,
    val errorMessage: String? = null
)

object VideoMetadataValidator {
    const val MAX_UPLOAD_BYTES = 1073741824L // 1 GB

    suspend fun inspectUri(context: Context, uri: Uri, maxSizeMb: Long = 1024L): ValidatedVideoMetadata =
        withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            var fileName = "video_${System.currentTimeMillis()}.mp4"
            var fileSize = 0L

            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                        if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                    }
                }

            val mime = cr.getType(uri) ?: "video/mp4"
            val maxAllowedBytes = maxSizeMb * 1024L * 1024L
            if (fileSize <= 0L) {
                return@withContext ValidatedVideoMetadata(
                    valid = false,
                    fileName = fileName,
                    mimeType = mime,
                    fileSizeBytes = fileSize,
                    durationSeconds = 0L,
                    resolution = "Unknown",
                    fps = 30L,
                    videoCodec = "Unknown",
                    audioCodec = "Unknown",
                    errorMessage = "Selected video file appears empty or unreadable."
                )
            }
            if (fileSize > maxAllowedBytes) {
                return@withContext ValidatedVideoMetadata(
                    valid = false,
                    fileName = fileName,
                    mimeType = mime,
                    fileSizeBytes = fileSize,
                    durationSeconds = 0L,
                    resolution = "Unknown",
                    fps = 30L,
                    videoCodec = "Unknown",
                    audioCodec = "Unknown",
                    errorMessage = "File exceeds maximum ${maxSizeMb} MB limit per video."
                )
            }

            val retriever = MediaMetadataRetriever()
            var durationSec = 0L
            var width = 1920
            var height = 1080
            var fps = 30L
            var videoCodec = "H.264/AVC"
            var audioCodec = "AAC"

            try {
                retriever.setDataSource(context, uri)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                durationSec = (durMs / 1000L).coerceAtLeast(1L)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
                val captureRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                if (captureRate != null && captureRate > 0f) fps = captureRate.toLong().coerceIn(1L, 120L)
            } catch (_: Exception) {
                return@withContext ValidatedVideoMetadata(
                    valid = false,
                    fileName = fileName,
                    mimeType = mime,
                    fileSizeBytes = fileSize,
                    durationSeconds = 0L,
                    resolution = "Unknown",
                    fps = 30L,
                    videoCodec = "Unknown",
                    audioCodec = "Unknown",
                    errorMessage = "Your video could not be processed. File integrity check failed."
                )
            } finally {
                runCatching { retriever.release() }
            }

            val extractor = MediaExtractor()
            runCatching {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val trackMime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (trackMime.startsWith("video/")) {
                        videoCodec = trackMime.removePrefix("video/").uppercase()
                        if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            fps = fmt.getInteger(MediaFormat.KEY_FRAME_RATE).toLong().coerceIn(1L, 120L)
                        }
                    } else if (trackMime.startsWith("audio/")) {
                        audioCodec = trackMime.removePrefix("audio/").uppercase()
                    }
                }
            }
            runCatching { extractor.release() }

            ValidatedVideoMetadata(
                valid = true,
                fileName = fileName,
                mimeType = mime,
                fileSizeBytes = fileSize,
                durationSeconds = durationSec,
                resolution = "${width}x${height}",
                fps = fps,
                videoCodec = videoCodec,
                audioCodec = audioCodec
            )
        }
}
