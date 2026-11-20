package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

inline fun <reified T : Enum<T>> safeEnumValue(raw: String?, default: T): T {
    if (raw.isNullOrBlank()) return default
    return enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
}

enum class UserRole { USER, ADMIN, SUPER_ADMIN }
enum class SubscriptionPlan { FREE, MONTHLY, LIFETIME }
enum class SubscriptionStatus { ACTIVE, EXPIRED, CANCELLED }
enum class AccountStatus { ACTIVE, SUSPENDED, BANNED }

enum class DestinationPlatform(
    val displayName: String,
    val defaultUrl: String,
    val supports60Fps: Boolean,
    val supports4K: Boolean,
    val maxBitrateKbps: Int
) {
    YOUTUBE("YouTube Live", "rtmps://a.rtmp.youtube.com/live2", true, true, 51000),
    FACEBOOK("Facebook Live", "rtmps://live-api-s.facebook.com:443/rtmp/", true, false, 9000),
    TIKTOK("TikTok Live", "rtmps://push.tiktokv.com/live/", false, false, 6000),
    TELEGRAM("Telegram Stream", "rtmps://dc4-1.rtmp.t.me/s/", false, false, 4500),
    CUSTOM_RTMP("Custom RTMP", "rtmp://", true, true, 51000),
    CUSTOM_RTMPS("Custom RTMPS", "rtmps://", true, true, 51000)
}

enum class StreamProtocol { RTMP, RTMPS, OAUTH_API }
enum class DestinationConnectionStatus { CONNECTED, DISCONNECTED, UNAVAILABLE }
enum class StreamOrientation(val label: String, val ratioText: String) {
    LANDSCAPE_16_9("Landscape", "16:9"),
    PORTRAIT_9_16("Portrait", "9:16")
}
enum class AspectRatioMode(val label: String) {
    FIT("Fit"),
    CROP("Crop"),
    STRETCH("Stretch"),
    BLUR_BG("Blur Background")
}
enum class VideoResolution(val label: String, val width: Int, val height: Int, val defaultBitrateKbps: Int) {
    R_360P("360p", 640, 360, 800),
    R_480P("480p", 854, 480, 1500),
    R_720P("720p", 1280, 720, 3500),
    R_1080P("1080p", 1920, 1080, 6000),
    R_1440P("1440p", 2560, 1440, 12000),
    R_2160P("2160p", 3840, 2160, 24000);

    companion object {
        fun fromLabel(label: String): VideoResolution =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: R_1080P
    }
}

enum class StreamSourceType { CAMERA, SCREEN, CLOUD_VIDEO }
enum class StreamStatus { LIVE, RECONNECTING, RUNNING, COMPLETED, STOPPED, FAILED, SCHEDULED }
enum class ConnectionQuality { EXCELLENT, GOOD, FAIR, POOR, DISCONNECTED }
enum class LoopMode(val label: String, val loopCount: Int) {
    ONCE("Play Once", 1),
    LOOP_2("Loop 2 times", 2),
    LOOP_5("Loop 5 times", 5),
    LOOP_10("Loop 10 times", 10),
    UNLIMITED("Unlimited Loop", -1)
}
enum class ScheduleRecurrence(val label: String) {
    ONCE("One-time"),
    DAILY("Daily"),
    WEEKLY("Weekly")
}
enum class ScheduleStatus { SCHEDULED, RUNNING, COMPLETED, CANCELLED, FAILED }
enum class VideoProcessingStage(val label: String) {
    UPLOADING("Uploading…"),
    PAUSED("Upload Paused (Resumable)"),
    PROCESSING("Processing…"),
    VALIDATING("Validating Video…"),
    THUMBNAIL("Generating Thumbnail…"),
    PREPARING("Preparing Stream…"),
    READY("Ready to stream"),
    FAILED("Processing Failed")
}
enum class PaymentMethod(val label: String) {
    BKASH("bKash"),
    NAGAD("Nagad"),
    CARD("Card Payment"),
    GATEWAY("Auto Gateway")
}
enum class PaymentStatus { PENDING, PAID, FAILED, REJECTED, REFUNDED }
enum class WithdrawalMethod(val label: String) {
    BKASH("bKash Merchant"),
    NAGAD("Nagad Merchant"),
    BANK_TRANSFER("Bank Transfer")
}
enum class WithdrawalStatus { PENDING, PROCESSING, COMPLETED, REJECTED }
enum class ReportStatus { OPEN, REVIEWED, RESOLVED, DISMISSED }
enum class ThemeModePreference { SYSTEM, DARK, LIGHT }

@Keep
@IgnoreExtraProperties
data class SystemSettings(
    @DocumentId val id: String = "global",
    val freeDailyLimitSeconds: Long = 10800L, // 3 hours
    val monthlyPriceBdt: Long = 100L,
    val lifetimePriceBdt: Long = 1500L,
    val maxUploadSizeMb: Long = 1024L, // 1 GB
    val freeStorageLimitMb: Long = 1024L, // 1 GB
    val monthlyStorageLimitMb: Long = 10240L, // 10 GB
    val lifetimeStorageLimitMb: Long = 25600L, // 25 GB
    val maxCloudDurationHours: Long = 168L, // 7 days
    val maxConcurrentStreams: Long = 3L,
    val allowedResolutions: List<String> = listOf("360p", "480p", "720p", "1080p", "1440p", "2160p"),
    val allowedFps: List<Long> = listOf(24L, 30L, 60L),
    val maintenanceMode: Boolean = false,
    val announcementMessage: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val role: String = UserRole.USER.name,
    val subscriptionPlan: String = SubscriptionPlan.FREE.name,
    val subscriptionStatus: String = SubscriptionStatus.ACTIVE.name,
    val accountStatus: String = AccountStatus.ACTIVE.name,
    val dailyUsedSeconds: Long = 0L,
    val dailyUsageDate: String = "",
    val storageUsedBytes: Long = 0L,
    val termsAccepted: Boolean = true,
    val subscriptionStartAt: Timestamp? = null,
    val subscriptionExpiryAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedRole: UserRole get() = safeEnumValue(role, UserRole.USER)
    val parsedPlan: SubscriptionPlan get() = safeEnumValue(subscriptionPlan, SubscriptionPlan.FREE)
    val parsedSubscriptionStatus: SubscriptionStatus get() = safeEnumValue(subscriptionStatus, SubscriptionStatus.ACTIVE)
    val parsedAccountStatus: AccountStatus get() = safeEnumValue(accountStatus, AccountStatus.ACTIVE)
    val isAdminUser: Boolean
        get() = parsedRole == UserRole.ADMIN ||
            parsedRole == UserRole.SUPER_ADMIN ||
            email.equals("kabir020314@gmail.com", ignoreCase = true)
    val isPremiumOrAdmin: Boolean
        get() = isAdminUser ||
            (parsedPlan != SubscriptionPlan.FREE && parsedSubscriptionStatus == SubscriptionStatus.ACTIVE)
}

@Keep
@IgnoreExtraProperties
data class StreamDestination(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val platform: String = DestinationPlatform.YOUTUBE.name,
    val protocol: String = StreamProtocol.RTMPS.name,
    val rtmpUrl: String = "",
    val maskedStreamKey: String = "",
    val channelName: String = "",
    val connectionStatus: String = DestinationConnectionStatus.DISCONNECTED.name,
    val supports60Fps: Boolean = true,
    val supports4K: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedPlatform: DestinationPlatform get() = safeEnumValue(platform, DestinationPlatform.CUSTOM_RTMP)
    val parsedProtocol: StreamProtocol get() = safeEnumValue(protocol, StreamProtocol.RTMPS)
    val parsedStatus: DestinationConnectionStatus get() = safeEnumValue(connectionStatus, DestinationConnectionStatus.DISCONNECTED)
}

@Keep
@IgnoreExtraProperties
data class StreamPreset(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val resolution: String = "1080p",
    val fps: Long = 60L,
    val videoBitrateKbps: Long = 6000L,
    val audioBitrateKbps: Long = 128L,
    val orientation: String = StreamOrientation.LANDSCAPE_16_9.name,
    val aspectMode: String = AspectRatioMode.FIT.name,
    val destinationNames: List<String> = emptyList(),
    val micEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedOrientation: StreamOrientation get() = safeEnumValue(orientation, StreamOrientation.LANDSCAPE_16_9)
    val parsedAspectMode: AspectRatioMode get() = safeEnumValue(aspectMode, AspectRatioMode.FIT)
}

@Keep
@IgnoreExtraProperties
data class VideoAsset(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val fileName: String = "",
    val mimeType: String = "video/mp4",
    val fileSizeBytes: Long = 0L,
    val uploadedBytes: Long = 0L,
    val durationSeconds: Long = 0L,
    val resolution: String = "1920x1080",
    val fps: Long = 30L,
    val videoCodec: String = "H.264/AVC",
    val audioCodec: String = "AAC",
    val processingStage: String = VideoProcessingStage.UPLOADING.name,
    val uploadProgress: Long = 0L,
    val storageObjectPath: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedStage: VideoProcessingStage get() = safeEnumValue(processingStage, VideoProcessingStage.UPLOADING)
}

@Keep
@IgnoreExtraProperties
data class StreamSession(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val description: String = "",
    val sourceType: String = StreamSourceType.CAMERA.name,
    val videoId: String = "",
    val orientation: String = StreamOrientation.LANDSCAPE_16_9.name,
    val aspectMode: String = AspectRatioMode.FIT.name,
    val resolution: String = "1080p",
    val fps: Long = 30L,
    val videoBitrateKbps: Long = 4500L,
    val audioBitrateKbps: Long = 128L,
    val destinationNames: List<String> = emptyList(),
    val status: String = StreamStatus.LIVE.name,
    val durationSeconds: Long = 0L,
    val plannedDurationSeconds: Long = 10800L,
    val loopMode: String = LoopMode.ONCE.name,
    val droppedFramesPercent: Double = 0.0,
    val connectionQuality: String = ConnectionQuality.EXCELLENT.name,
    val workerStatus: String = "ACTIVE",
    val workerCpuPercent: Long = 0L,
    val workerRamPercent: Long = 0L,
    val reconnectAttempts: Long = 0L,
    val dataTransferredMb: Double = 0.0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedSourceType: StreamSourceType get() = safeEnumValue(sourceType, StreamSourceType.CAMERA)
    val parsedStatus: StreamStatus get() = safeEnumValue(status, StreamStatus.LIVE)
    val parsedQuality: ConnectionQuality get() = safeEnumValue(connectionQuality, ConnectionQuality.EXCELLENT)
    val parsedOrientation: StreamOrientation get() = safeEnumValue(orientation, StreamOrientation.LANDSCAPE_16_9)
    val parsedAspectMode: AspectRatioMode get() = safeEnumValue(aspectMode, AspectRatioMode.FIT)
    val parsedLoopMode: LoopMode get() = safeEnumValue(loopMode, LoopMode.ONCE)
}

@Keep
@IgnoreExtraProperties
data class ScheduledStream(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val videoId: String = "",
    val videoTitle: String = "",
    val scheduledStartIso: String = "",
    val scheduledEpochMs: Long = 0L,
    val durationHours: Long = 1L,
    val loopMode: String = LoopMode.ONCE.name,
    val recurrence: String = ScheduleRecurrence.ONCE.name,
    val destinationNames: List<String> = emptyList(),
    val resolution: String = "1080p",
    val fps: Long = 30L,
    val orientation: String = StreamOrientation.LANDSCAPE_16_9.name,
    val aspectMode: String = AspectRatioMode.FIT.name,
    val status: String = ScheduleStatus.SCHEDULED.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedStatus: ScheduleStatus get() = safeEnumValue(status, ScheduleStatus.SCHEDULED)
    val parsedLoopMode: LoopMode get() = safeEnumValue(loopMode, LoopMode.ONCE)
    val parsedRecurrence: ScheduleRecurrence get() = safeEnumValue(recurrence, ScheduleRecurrence.ONCE)
}

@Keep
@IgnoreExtraProperties
data class NotificationItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val type: String = "INFO",
    val title: String = "",
    val message: String = "",
    val read: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PaymentRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val userEmail: String = "",
    val transactionId: String = "",
    val amount: Double = 0.0,
    val currency: String = "BDT",
    val paymentMethod: String = PaymentMethod.BKASH.name,
    val plan: String = SubscriptionPlan.MONTHLY.name,
    val status: String = PaymentStatus.PENDING.name,
    val notes: String = "",
    val verifiedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedMethod: PaymentMethod get() = safeEnumValue(paymentMethod, PaymentMethod.BKASH)
    val parsedPlan: SubscriptionPlan get() = safeEnumValue(plan, SubscriptionPlan.MONTHLY)
    val parsedStatus: PaymentStatus get() = safeEnumValue(status, PaymentStatus.PENDING)
}

@Keep
@IgnoreExtraProperties
data class WithdrawalRecord(
    @DocumentId val id: String = "",
    val adminId: String = "",
    val amount: Double = 0.0,
    val currency: String = "BDT",
    val method: String = WithdrawalMethod.BKASH.name,
    val destination: String = "",
    val status: String = WithdrawalStatus.PENDING.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedStatus: WithdrawalStatus get() = safeEnumValue(status, WithdrawalStatus.PENDING)
    val parsedMethod: WithdrawalMethod get() = safeEnumValue(method, WithdrawalMethod.BKASH)
}

@Keep
@IgnoreExtraProperties
data class AbuseReport(
    @DocumentId val id: String = "",
    val reporterId: String = "",
    val targetStreamId: String = "",
    val reason: String = "",
    val details: String = "",
    val status: String = ReportStatus.OPEN.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedStatus: ReportStatus get() = safeEnumValue(status, ReportStatus.OPEN)
}

@Keep
@IgnoreExtraProperties
data class AuditLog(
    @DocumentId val id: String = "",
    val actorId: String = "",
    val actorEmail: String = "",
    val action: String = "",
    val target: String = "",
    val severity: String = "INFO",
    @ServerTimestamp val createdAt: Timestamp? = null
)
