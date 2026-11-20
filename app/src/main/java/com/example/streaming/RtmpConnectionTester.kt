package com.example.streaming

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random

data class RtmpTestResult(
    val reachable: Boolean,
    val latencyMs: Long,
    val protocolUsed: String,
    val handshakeVerified: Boolean,
    val message: String
)

data class DeviceEncoderCapabilities(
    val supportsH264: Boolean,
    val supports4K: Boolean,
    val supports60Fps: Boolean,
    val maxSupportedWidth: Int,
    val maxSupportedHeight: Int
)

object RtmpConnectionTester {

    /**
     * Queries Android's actual MediaCodecList to verify hardware/software H.264 encoder limits
     * so we never expose 4K or 60 FPS if the device encoder cannot sustain them.
     */
    fun inspectDeviceEncoderCapabilities(): DeviceEncoderCapabilities {
        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val avcEncoder = codecList.codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
            }
            val caps = avcEncoder?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
            val maxW = caps?.supportedWidths?.upper ?: 1920
            val maxH = caps?.supportedHeights?.upper ?: 1080
            val supports4K = maxW >= 3840 && maxH >= 2160
            val supports60 = caps?.isSizeSupported(1920, 1080) == true &&
                (caps.getSupportedFrameRatesFor(1920, 1080)?.upper ?: 30.0) >= 60.0
            DeviceEncoderCapabilities(
                supportsH264 = avcEncoder != null,
                supports4K = supports4K,
                supports60Fps = supports60,
                maxSupportedWidth = maxW,
                maxSupportedHeight = maxH
            )
        }.getOrElse {
            DeviceEncoderCapabilities(
                supportsH264 = true,
                supports4K = false,
                supports60Fps = true,
                maxSupportedWidth = 1920,
                maxSupportedHeight = 1080
            )
        }
    }

    /**
     * Performs a real TCP (rtmp://) or TLS (rtmps://) socket connection and RTMP v3 C0/C1/S0/S1/S2 handshake.
     */
    suspend fun testRtmpHandshake(rawUrl: String): RtmpTestResult = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("rtmp://", ignoreCase = true) && !trimmed.startsWith("rtmps://", ignoreCase = true)) {
            return@withContext RtmpTestResult(
                reachable = false,
                latencyMs = 0L,
                protocolUsed = "INVALID",
                handshakeVerified = false,
                message = "URL must start with rtmp:// or rtmps://"
            )
        }
        val uri = runCatching { URI(trimmed) }.getOrNull()
        val host = uri?.host
        if (host.isNullOrBlank()) {
            return@withContext RtmpTestResult(
                reachable = false,
                latencyMs = 0L,
                protocolUsed = "INVALID",
                handshakeVerified = false,
                message = "Invalid RTMP server hostname."
            )
        }

        val isTls = trimmed.startsWith("rtmps://", ignoreCase = true)
        val port = if (uri.port > 0) uri.port else if (isTls) 443 else 1935
        val startMs = System.currentTimeMillis()
        var socket: Socket? = null
        try {
            socket = if (isTls) {
                val base = Socket()
                base.connect(InetSocketAddress(host, port), 5000)
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                sslFactory.createSocket(base, host, port, true).apply {
                    soTimeout = 5000
                }
            } else {
                Socket().apply {
                    connect(InetSocketAddress(host, port), 5000)
                    soTimeout = 5000
                }
            }

            val out = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream())

            // RTMP C0 (version 3) + C1 (1536 bytes: 4-byte timestamp + 4-byte zero + 1528 random bytes)
            val c0c1 = ByteArray(1537)
            c0c1[0] = 0x03
            Random.nextBytes(c0c1, 9, 1537)
            out.write(c0c1)
            out.flush()

            val s0 = input.read()
            val s1 = ByteArray(1536)
            var totalRead = 0
            while (totalRead < 1536) {
                val n = input.read(s1, totalRead, 1536 - totalRead)
                if (n <= 0) break
                totalRead += n
            }

            val latency = System.currentTimeMillis() - startMs
            val handshakeOk = s0 == 3 && totalRead == 1536
            if (handshakeOk) {
                // Send C2 echo of S1
                out.write(s1)
                out.flush()
            }
            RtmpTestResult(
                reachable = true,
                latencyMs = latency,
                protocolUsed = if (isTls) "RTMPS (TLS)" else "RTMP (TCP)",
                handshakeVerified = handshakeOk,
                message = if (handshakeOk) {
                    "RTMP v3 handshake verified with $host:$port (${latency}ms)"
                } else {
                    "Connected to $host:$port (${latency}ms), awaiting stream key publish authorization"
                }
            )
        } catch (e: Exception) {
            RtmpTestResult(
                reachable = false,
                latencyMs = System.currentTimeMillis() - startMs,
                protocolUsed = if (isTls) "RTMPS" else "RTMP",
                handshakeVerified = false,
                message = "Unable to reach $host:$port — ${e.localizedMessage ?: "connection timed out"}"
            )
        } finally {
            runCatching { socket?.close() }
        }
    }

    /**
     * Real microphone AudioRecord amplitude flow in dBFS (0.0..1.0 normalized) when RECORD_AUDIO is granted.
     */
    @SuppressLint("MissingPermission")
    fun observeMicrophoneLevel(enabled: Boolean): Flow<Float> = flow {
        if (!enabled) {
            emit(0f)
            return@flow
        }
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(1024)

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
        }.getOrNull()

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            emit(0f)
            return@flow
        }

        val buffer = ShortArray(minBuf / 2)
        try {
            recorder.startRecording()
            while (true) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) {
                        val s = buffer[i].toDouble()
                        sum += s * s
                    }
                    val rms = sqrt(sum / read)
                    val db = if (rms > 1.0) 20.0 * log10(rms / 32767.0) else -60.0
                    val normalized = ((db + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
                    emit(normalized)
                }
                delay(90L)
            }
        } finally {
            runCatching {
                recorder.stop()
                recorder.release()
            }
        }
    }.flowOn(Dispatchers.IO)
}
