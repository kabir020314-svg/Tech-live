package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.*
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TechLiveRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")

    fun todayDateKey(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun <T> Flow<T>.wrapFirestoreFlow(op: OperationType, path: String, uid: String): Flow<T> =
        this.catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val exToLog = firestoreError ?: (error as? Exception)
            if (exToLog != null && auth.currentUser?.uid == uid) {
                handleFirestoreError(exToLog, op, path)
            }
            throw error
        }

    // --- SYSTEM SETTINGS ---
    fun observeSystemSettings(): Flow<SystemSettings> = flow {
        val uid = requireUserId()
        val path = "system_settings/global"
        emitAll(
            db.collection("system_settings").document("global")
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(SystemSettings::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                            ?: SystemSettings()
                    } else {
                        SystemSettings()
                    }
                }
                .wrapFirestoreFlow(OperationType.GET, path, uid)
        )
    }

    suspend fun updateSystemSettings(settings: SystemSettings): Result<Unit> {
        val path = "system_settings/global"
        return try {
            val uid = requireUserId()
            val payload = mapOf(
                "freeDailyLimitSeconds" to settings.freeDailyLimitSeconds.coerceIn(60L, 86400L),
                "monthlyPriceBdt" to settings.monthlyPriceBdt.coerceAtLeast(1L),
                "lifetimePriceBdt" to settings.lifetimePriceBdt.coerceAtLeast(1L),
                "maxUploadSizeMb" to settings.maxUploadSizeMb.coerceIn(10L, 10240L),
                "freeStorageLimitMb" to settings.freeStorageLimitMb.coerceAtLeast(0L),
                "monthlyStorageLimitMb" to settings.monthlyStorageLimitMb.coerceAtLeast(100L),
                "lifetimeStorageLimitMb" to settings.lifetimeStorageLimitMb.coerceAtLeast(100L),
                "maxCloudDurationHours" to settings.maxCloudDurationHours.coerceIn(1L, 168L),
                "maxConcurrentStreams" to settings.maxConcurrentStreams.coerceIn(1L, 50L),
                "allowedResolutions" to settings.allowedResolutions.take(10),
                "allowedFps" to settings.allowedFps.take(6),
                "maintenanceMode" to settings.maintenanceMode,
                "announcementMessage" to settings.announcementMessage.take(500),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("system_settings").document("global").set(payload).await()
            logAuditInternal(uid, auth.currentUser?.email ?: "", "UPDATE_SYSTEM_SETTINGS", "system_settings/global", "INFO")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    // --- USER PROFILE & DAILY QUOTA ---
    suspend fun ensureUserProfile(firebaseUser: FirebaseUser): Result<Unit> {
        val uid = firebaseUser.uid
        val path = "users/$uid"
        return try {
            val docRef = db.collection("users").document(uid)
            val snap = docRef.get().await()
            val today = todayDateKey()
            val isBootstrapAdmin = firebaseUser.email.equals("kabir020314@gmail.com", ignoreCase = true) &&
                firebaseUser.isEmailVerified

            if (!snap.exists()) {
                val payload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "displayName" to (firebaseUser.displayName?.takeIf { it.isNotBlank() } ?: "Tech Live Creator"),
                    "email" to (firebaseUser.email ?: "user@techlive.app"),
                    "role" to if (isBootstrapAdmin) UserRole.SUPER_ADMIN.name else UserRole.USER.name,
                    "subscriptionPlan" to SubscriptionPlan.FREE.name,
                    "subscriptionStatus" to SubscriptionStatus.ACTIVE.name,
                    "accountStatus" to AccountStatus.ACTIVE.name,
                    "dailyUsedSeconds" to 0L,
                    "dailyUsageDate" to today,
                    "storageUsedBytes" to 0L,
                    "termsAccepted" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                firebaseUser.photoUrl?.toString()?.take(600)?.let { payload["photoUrl"] = it }
                docRef.set(payload).await()

                // Seed starter presets & default settings for first-time user experience
                seedStarterPresetsIfEmpty(uid)
                if (isBootstrapAdmin) {
                    ensureAdminDocAndSettings(uid, firebaseUser.email ?: "")
                }
            } else {
                // Automatic daily quota reset if date changed
                val existingDate = snap.getString("dailyUsageDate") ?: ""
                if (existingDate != today) {
                    docRef.update(
                        mapOf(
                            "dailyUsedSeconds" to 0L,
                            "dailyUsageDate" to today,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
                if (isBootstrapAdmin) {
                    ensureAdminDocAndSettings(uid, firebaseUser.email ?: "")
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    private suspend fun ensureAdminDocAndSettings(uid: String, email: String) {
        runCatching {
            val adminRef = db.collection("admins").document(uid)
            if (!adminRef.get().await().exists()) {
                adminRef.set(
                    mapOf(
                        "userId" to uid,
                        "role" to UserRole.SUPER_ADMIN.name,
                        "email" to email,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            val settingsRef = db.collection("system_settings").document("global")
            if (!settingsRef.get().await().exists()) {
                updateSystemSettings(SystemSettings())
            }
        }
    }

    private suspend fun seedStarterPresetsIfEmpty(uid: String) {
        val col = db.collection("users").document(uid).collection("presets")
        val existing = col.limit(1).get().await()
        if (!existing.isEmpty) return
        val starters = listOf(
            StreamPreset(
                userId = uid,
                name = "Gaming 1080p",
                resolution = "1080p",
                fps = 60L,
                videoBitrateKbps = 6000L,
                audioBitrateKbps = 128L,
                orientation = StreamOrientation.LANDSCAPE_16_9.name,
                aspectMode = AspectRatioMode.FIT.name,
                destinationNames = listOf("YouTube Live"),
                micEnabled = true,
                cameraEnabled = true
            ),
            StreamPreset(
                userId = uid,
                name = "YouTube Portrait",
                resolution = "1080p",
                fps = 30L,
                videoBitrateKbps = 4500L,
                audioBitrateKbps = 128L,
                orientation = StreamOrientation.PORTRAIT_9_16.name,
                aspectMode = AspectRatioMode.CROP.name,
                destinationNames = listOf("YouTube Live"),
                micEnabled = true,
                cameraEnabled = true
            ),
            StreamPreset(
                userId = uid,
                name = "Low Bandwidth 720p",
                resolution = "720p",
                fps = 30L,
                videoBitrateKbps = 2500L,
                audioBitrateKbps = 96L,
                orientation = StreamOrientation.LANDSCAPE_16_9.name,
                aspectMode = AspectRatioMode.FIT.name,
                destinationNames = listOf("Custom RTMP"),
                micEnabled = true,
                cameraEnabled = true
            )
        )
        starters.forEach { preset ->
            col.document().set(
                mapOf(
                    "userId" to uid,
                    "name" to preset.name,
                    "resolution" to preset.resolution,
                    "fps" to preset.fps,
                    "videoBitrateKbps" to preset.videoBitrateKbps,
                    "audioBitrateKbps" to preset.audioBitrateKbps,
                    "orientation" to preset.orientation,
                    "aspectMode" to preset.aspectMode,
                    "destinationNames" to preset.destinationNames,
                    "micEnabled" to preset.micEnabled,
                    "cameraEnabled" to preset.cameraEnabled,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        val uid = requireUserId()
        val path = "users/$userId"
        emitAll(
            db.collection("users").document(userId)
                .snapshots()
                .map { snap ->
                    snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .wrapFirestoreFlow(OperationType.GET, path, uid)
        )
    }

    suspend fun recordDailyLiveUsage(userId: String, deltaSeconds: Long): Result<Unit> {
        val path = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user quota write." }
            val docRef = db.collection("users").document(userId)
            val snap = docRef.get().await()
            val today = todayDateKey()
            val storedDate = snap.getString("dailyUsageDate") ?: today
            val currentUsed = if (storedDate == today) (snap.getLong("dailyUsedSeconds") ?: 0L) else 0L
            val nextUsed = (currentUsed + deltaSeconds.coerceAtLeast(0L)).coerceAtMost(86400L)
            docRef.update(
                mapOf(
                    "dailyUsedSeconds" to nextUsed,
                    "dailyUsageDate" to today,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- DESTINATIONS ---
    fun observeDestinations(userId: String): Flow<List<StreamDestination>> = flow {
        val uid = requireUserId()
        val path = "users/$userId/destinations"
        emitAll(
            db.collection("users").document(userId).collection("destinations")
                .snapshots()
                .map { snap ->
                    snap.toObjects(StreamDestination::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .wrapFirestoreFlow(OperationType.LIST, path, uid)
        )
    }

    suspend fun saveDestination(
        userId: String,
        destId: String?,
        name: String,
        platform: DestinationPlatform,
        protocol: StreamProtocol,
        rtmpUrl: String,
        rawStreamKey: String,
        channelName: String,
        connectionStatus: DestinationConnectionStatus,
        supports60Fps: Boolean,
        supports4K: Boolean
    ): Result<Unit> {
        val path = "users/$userId/destinations"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user destination write." }
            require(name.trim().isNotEmpty()) { "Destination name is required" }

            // Mask stream key before storing in Firestore so sensitive keys are never exposed publicly
            val trimmedKey = rawStreamKey.trim()
            val masked = when {
                trimmedKey.isEmpty() -> "OAUTH_MANAGED"
                trimmedKey.length <= 4 -> "••••"
                else -> "••••••••-${trimmedKey.takeLast(4)}"
            }

            val col = db.collection("users").document(userId).collection("destinations")
            if (destId.isNullOrBlank()) {
                val payload = mapOf(
                    "userId" to userId,
                    "name" to name.trim().take(80),
                    "platform" to platform.name,
                    "protocol" to protocol.name,
                    "rtmpUrl" to rtmpUrl.trim().take(300),
                    "maskedStreamKey" to masked.take(120),
                    "channelName" to channelName.trim().take(120),
                    "connectionStatus" to connectionStatus.name,
                    "supports60Fps" to supports60Fps,
                    "supports4K" to supports4K,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                col.document().set(payload).await()
            } else {
                col.document(destId).update(
                    mapOf(
                        "name" to name.trim().take(80),
                        "platform" to platform.name,
                        "protocol" to protocol.name,
                        "rtmpUrl" to rtmpUrl.trim().take(300),
                        "maskedStreamKey" to masked.take(120),
                        "channelName" to channelName.trim().take(120),
                        "connectionStatus" to connectionStatus.name,
                        "supports60Fps" to supports60Fps,
                        "supports4K" to supports4K,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteDestination(userId: String, destId: String): Result<Unit> {
        val path = "users/$userId/destinations/$destId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(userId).collection("destinations").document(destId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- PRESETS / TEMPLATES ---
    fun observePresets(userId: String): Flow<List<StreamPreset>> = flow {
        val uid = requireUserId()
        val path = "users/$userId/presets"
        emitAll(
            db.collection("users").document(userId).collection("presets")
                .snapshots()
                .map { snap ->
                    snap.toObjects(StreamPreset::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .wrapFirestoreFlow(OperationType.LIST, path, uid)
        )
    }

    suspend fun savePreset(userId: String, preset: StreamPreset): Result<Unit> {
        val path = "users/$userId/presets"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val col = db.collection("users").document(userId).collection("presets")
            val payload = mapOf(
                "userId" to userId,
                "name" to preset.name.trim().take(80),
                "resolution" to preset.resolution,
                "fps" to preset.fps,
                "videoBitrateKbps" to preset.videoBitrateKbps.coerceIn(300L, 51000L),
                "audioBitrateKbps" to preset.audioBitrateKbps.coerceIn(64L, 320L),
                "orientation" to preset.orientation,
                "aspectMode" to preset.aspectMode,
                "destinationNames" to preset.destinationNames.take(10),
                "micEnabled" to preset.micEnabled,
                "cameraEnabled" to preset.cameraEnabled,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (preset.id.isBlank()) {
                col.document().set(payload + ("createdAt" to FieldValue.serverTimestamp())).await()
            } else {
                col.document(preset.id).update(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deletePreset(userId: String, presetId: String): Result<Unit> {
        val path = "users/$userId/presets/$presetId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(userId).collection("presets").document(presetId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- VIDEOS & STORAGE ---
    fun observeVideos(userId: String): Flow<List<VideoAsset>> = flow {
        val uid = requireUserId()
        val path = "users/$userId/videos"
        emitAll(
            db.collection("users").document(userId).collection("videos")
                .snapshots()
                .map { snap ->
                    snap.toObjects(VideoAsset::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .wrapFirestoreFlow(OperationType.LIST, path, uid)
        )
    }

    suspend fun createVideoRecord(video: VideoAsset): Result<String> {
        val uid = requireUserId()
        val path = "users/$uid/videos"
        return try {
            val docRef = db.collection("users").document(uid).collection("videos").document()
            docRef.set(
                mapOf(
                    "userId" to uid,
                    "title" to video.title.trim().take(140),
                    "fileName" to video.fileName.trim().take(200),
                    "mimeType" to video.mimeType.take(80),
                    "fileSizeBytes" to video.fileSizeBytes.coerceIn(0L, 1073741824L),
                    "uploadedBytes" to video.uploadedBytes.coerceIn(0L, video.fileSizeBytes.coerceAtLeast(0L)),
                    "durationSeconds" to video.durationSeconds.coerceIn(0L, 86400L),
                    "resolution" to video.resolution.take(30),
                    "fps" to video.fps.coerceIn(1L, 120L),
                    "videoCodec" to video.videoCodec.take(40),
                    "audioCodec" to video.audioCodec.take(40),
                    "processingStage" to video.processingStage,
                    "uploadProgress" to video.uploadProgress.coerceIn(0L, 100L),
                    "storageObjectPath" to video.storageObjectPath.take(400),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateVideoProgress(
        userId: String,
        videoId: String,
        uploadedBytes: Long,
        progress: Long,
        stage: VideoProcessingStage
    ): Result<Unit> {
        val path = "users/$userId/videos/$videoId"
        return try {
            requireUserId()
            db.collection("users").document(userId).collection("videos").document(videoId)
                .update(
                    mapOf(
                        "uploadedBytes" to uploadedBytes.coerceAtLeast(0L),
                        "uploadProgress" to progress.coerceIn(0L, 100L),
                        "processingStage" to stage.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun renameVideo(userId: String, videoId: String, newTitle: String): Result<Unit> {
        val path = "users/$userId/videos/$videoId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            require(newTitle.trim().isNotEmpty()) { "Title cannot be empty" }
            db.collection("users").document(userId).collection("videos").document(videoId)
                .update(
                    mapOf(
                        "title" to newTitle.trim().take(140),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteVideo(userId: String, videoId: String, fileSizeBytes: Long): Result<Unit> {
        val path = "users/$userId/videos/$videoId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(userId).collection("videos").document(videoId).delete().await()
            // Adjust storage usage safely
            val userDoc = db.collection("users").document(userId)
            val currentBytes = userDoc.get().await().getLong("storageUsedBytes") ?: 0L
            userDoc.update(
                mapOf(
                    "storageUsedBytes" to (currentBytes - fileSizeBytes).coerceAtLeast(0L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun addStorageBytes(userId: String, addedBytes: Long): Result<Unit> {
        val path = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val userDoc = db.collection("users").document(userId)
            val currentBytes = userDoc.get().await().getLong("storageUsedBytes") ?: 0L
            userDoc.update(
                mapOf(
                    "storageUsedBytes" to (currentBytes + addedBytes.coerceAtLeast(0L)),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- STREAMS & TELEMETRY ---
    fun observeStreams(userId: String): Flow<List<StreamSession>> = flow {
        val uid = requireUserId()
        val path = "users/$userId/streams"
        emitAll(
            db.collection("users").document(userId).collection("streams")
                .snapshots()
                .map { snap ->
                    snap.toObjects(StreamSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .wrapFirestoreFlow(OperationType.LIST, path, uid)
        )
    }

    suspend fun startStreamSession(session: StreamSession): Result<String> {
        val uid = requireUserId()
        val path = "users/$uid/streams"
        return try {
            val docRef = db.collection("users").document(uid).collection("streams").document()
            val streamId = docRef.id
            val payload = mapOf(
                "userId" to uid,
                "title" to session.title.trim().take(140),
                "description" to session.description.trim().take(500),
                "sourceType" to session.sourceType,
                "videoId" to session.videoId.take(128),
                "orientation" to session.orientation,
                "aspectMode" to session.aspectMode,
                "resolution" to session.resolution,
                "fps" to session.fps,
                "videoBitrateKbps" to session.videoBitrateKbps.coerceIn(300L, 51000L),
                "audioBitrateKbps" to session.audioBitrateKbps.coerceIn(64L, 320L),
                "destinationNames" to session.destinationNames.take(10),
                "status" to session.status,
                "durationSeconds" to 0L,
                "plannedDurationSeconds" to session.plannedDurationSeconds.coerceIn(0L, 604800L),
                "loopMode" to session.loopMode,
                "droppedFramesPercent" to 0.0,
                "connectionQuality" to session.connectionQuality,
                "workerStatus" to session.workerStatus.take(40),
                "workerCpuPercent" to session.workerCpuPercent.coerceIn(0L, 100L),
                "workerRamPercent" to session.workerRamPercent.coerceIn(0L, 100L),
                "reconnectAttempts" to 0L,
                "dataTransferredMb" to 0.0,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            db.collection("active_streams").document(streamId).set(payload).await()
            createNotificationInternal(
                uid,
                "STREAM_STARTED",
                "Stream Started",
                "${session.title} is now broadcasting to ${session.destinationNames.joinToString(", ").ifEmpty { "Configured Destination" }}."
            )
            Result.success(streamId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateStreamTelemetry(
        userId: String,
        streamId: String,
        status: StreamStatus,
        durationSeconds: Long,
        droppedFramesPercent: Double,
        connectionQuality: ConnectionQuality,
        workerStatus: String,
        workerCpuPercent: Long,
        workerRamPercent: Long,
        reconnectAttempts: Long,
        dataTransferredMb: Double
    ): Result<Unit> {
        val path = "users/$userId/streams/$streamId"
        return try {
            requireUserId()
            val updateMap = mapOf(
                "status" to status.name,
                "durationSeconds" to durationSeconds.coerceAtLeast(0L),
                "droppedFramesPercent" to droppedFramesPercent.coerceIn(0.0, 100.0),
                "connectionQuality" to connectionQuality.name,
                "workerStatus" to workerStatus.take(40),
                "workerCpuPercent" to workerCpuPercent.coerceIn(0L, 100L),
                "workerRamPercent" to workerRamPercent.coerceIn(0L, 100L),
                "reconnectAttempts" to reconnectAttempts.coerceAtLeast(0L),
                "dataTransferredMb" to dataTransferredMb.coerceAtLeast(0.0),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("users").document(userId).collection("streams").document(streamId)
                .update(updateMap).await()
            if (status in listOf(StreamStatus.COMPLETED, StreamStatus.STOPPED, StreamStatus.FAILED)) {
                db.collection("active_streams").document(streamId).delete().await()
            } else {
                db.collection("active_streams").document(streamId).update(updateMap).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- SCHEDULES ---
    fun observeSchedules(userId: String): Flow<List<ScheduledStream>> = flow {
        val uid = requireUserId()
        val path = "users/$userId/schedules"
        emitAll(
            db.collection("users").document(userId).collection("schedules")
                .snapshots()
                .map { snap ->
                    snap.toObjects(ScheduledStream::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.scheduledEpochMs }
                }
                .wrapFirestoreFlow(OperationType.LIST, path, uid)
        )
    }

    suspend fun saveSchedule(userId: String, schedule: ScheduledStream): Result<Unit> {
        val path = "users/$userId/schedules"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            val col = db.collection("users").document(userId).collection("schedules")
            val payload = mapOf(
                "userId" to userId,
                "title" to schedule.title.trim().take(140),
                "videoId" to schedule.videoId.take(128),
                "videoTitle" to schedule.videoTitle.trim().take(140),
                "scheduledStartIso" to schedule.scheduledStartIso.take(40),
                "scheduledEpochMs" to schedule.scheduledEpochMs.coerceAtLeast(1L),
                "durationHours" to schedule.durationHours.coerceIn(1L, 168L),
                "loopMode" to schedule.loopMode,
                "recurrence" to schedule.recurrence,
                "destinationNames" to schedule.destinationNames.take(10),
                "resolution" to schedule.resolution,
                "fps" to schedule.fps,
                "orientation" to schedule.orientation,
                "aspectMode" to schedule.aspectMode,
                "status" to schedule.status,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (schedule.id.isBlank()) {
                col.document().set(payload + ("createdAt" to FieldValue.serverTimestamp())).await()
            } else {
                col.document(schedule.id).update(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun cancelSchedule(userId: String, scheduleId: String): Result<Unit> {
        val path = "users/$userId/schedules/$scheduleId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(userId).collection("schedules").document(scheduleId)
                .update(
                    mapOf(
                        "status" to ScheduleStatus.CANCELLED.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- NOTIFICATIONS ---
    fun observeNotifications(userId: String): Flow<List<NotificationItem>> = flow {
        val uid = requireUserId()
        val path = "users/$userId/notifications"
        emitAll(
            db.collection("users").document(userId).collection("notifications")
                .snapshots()
                .map { snap ->
                    snap.toObjects(NotificationItem::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .wrapFirestoreFlow(OperationType.LIST, path, uid)
        )
    }

    suspend fun createNotificationInternal(userId: String, type: String, title: String, message: String) {
        runCatching {
            db.collection("users").document(userId).collection("notifications").document().set(
                mapOf(
                    "userId" to userId,
                    "type" to type.take(50),
                    "title" to title.take(120),
                    "message" to message.take(400),
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    suspend fun markNotificationRead(userId: String, notificationId: String): Result<Unit> {
        val path = "users/$userId/notifications/$notificationId"
        return try {
            requireUserId()
            db.collection("users").document(userId).collection("notifications").document(notificationId)
                .update(mapOf("read" to true, "updatedAt" to FieldValue.serverTimestamp())).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- PAYMENTS ---
    fun observeUserPayments(userId: String): Flow<List<PaymentRecord>> = flow {
        val uid = requireUserId()
        val path = "payments"
        emitAll(
            db.collection("payments")
                .whereEqualTo("userId", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(PaymentRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .wrapFirestoreFlow(OperationType.LIST, path, uid)
        )
    }

    suspend fun submitPayment(
        userId: String,
        userEmail: String,
        transactionId: String,
        amount: Double,
        paymentMethod: PaymentMethod,
        plan: SubscriptionPlan,
        notes: String
    ): Result<Unit> {
        val path = "payments"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            require(transactionId.trim().length >= 4) { "Enter a valid transaction ID (min 4 characters)" }
            require(plan != SubscriptionPlan.FREE) { "Paid plan required" }
            val docRef = db.collection("payments").document()
            docRef.set(
                mapOf(
                    "userId" to userId,
                    "userEmail" to userEmail.take(200),
                    "transactionId" to transactionId.trim().take(120),
                    "amount" to amount,
                    "currency" to "BDT",
                    "paymentMethod" to paymentMethod.name,
                    "plan" to plan.name,
                    "status" to PaymentStatus.PENDING.name,
                    "notes" to notes.trim().take(300),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            createNotificationInternal(
                userId,
                "PAYMENT_PENDING",
                "Payment Submitted",
                "Transaction ${transactionId.trim()} for $plan (BDT ${amount.toInt()}) is pending verification."
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    // --- ADMIN OPERATIONS (Role-Gated in firestore.rules) ---
    fun observeAllUsers(): Flow<List<UserProfile>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("users").snapshots()
                .map { it.toObjects(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .wrapFirestoreFlow(OperationType.LIST, "users", uid)
        )
    }

    fun observeAllPayments(): Flow<List<PaymentRecord>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("payments").snapshots()
                .map { snap ->
                    snap.toObjects(PaymentRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .wrapFirestoreFlow(OperationType.LIST, "payments", uid)
        )
    }

    fun observeAllActiveStreams(): Flow<List<StreamSession>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("active_streams").snapshots()
                .map { it.toObjects(StreamSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .wrapFirestoreFlow(OperationType.LIST, "active_streams", uid)
        )
    }

    fun observeWithdrawals(): Flow<List<WithdrawalRecord>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("withdrawals").snapshots()
                .map { snap ->
                    snap.toObjects(WithdrawalRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .wrapFirestoreFlow(OperationType.LIST, "withdrawals", uid)
        )
    }

    fun observeReports(): Flow<List<AbuseReport>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("reports").snapshots()
                .map { snap ->
                    snap.toObjects(AbuseReport::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .wrapFirestoreFlow(OperationType.LIST, "reports", uid)
        )
    }

    fun observeAuditLogs(): Flow<List<AuditLog>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("audit_logs").snapshots()
                .map { snap ->
                    snap.toObjects(AuditLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .wrapFirestoreFlow(OperationType.LIST, "audit_logs", uid)
        )
    }

    suspend fun adminVerifyPayment(payment: PaymentRecord, approve: Boolean, refund: Boolean = false): Result<Unit> {
        val path = "payments/${payment.id}"
        return try {
            val adminUid = requireUserId()
            val nextStatus = when {
                refund -> PaymentStatus.REFUNDED
                approve -> PaymentStatus.PAID
                else -> PaymentStatus.REJECTED
            }

            db.collection("payments").document(payment.id).update(
                mapOf(
                    "status" to nextStatus.name,
                    "verifiedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            if (approve && !refund) {
                val nowSec = System.currentTimeMillis() / 1000L
                val expiry = if (payment.parsedPlan == SubscriptionPlan.MONTHLY) {
                    Timestamp(nowSec + 30L * 86400L, 0)
                } else null

                val subUpdates = mutableMapOf<String, Any>(
                    "subscriptionPlan" to payment.plan,
                    "subscriptionStatus" to SubscriptionStatus.ACTIVE.name,
                    "subscriptionStartAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (expiry != null) subUpdates["subscriptionExpiryAt"] = expiry

                db.collection("users").document(payment.userId).update(subUpdates).await()
                createNotificationInternal(
                    payment.userId,
                    "PAYMENT_APPROVED",
                    "Subscription Activated",
                    "Your ${payment.plan} plan payment (${payment.transactionId}) was verified and activated."
                )
            } else if (refund) {
                db.collection("users").document(payment.userId).update(
                    mapOf(
                        "subscriptionPlan" to SubscriptionPlan.FREE.name,
                        "subscriptionStatus" to SubscriptionStatus.CANCELLED.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                createNotificationInternal(
                    payment.userId,
                    "PAYMENT_REJECTED",
                    "Payment Rejected",
                    "Transaction ${payment.transactionId} could not be verified. Contact support if needed."
                )
            }

            logAuditInternal(
                adminUid,
                auth.currentUser?.email ?: "",
                "ADMIN_PAYMENT_${nextStatus.name}",
                "payment:${payment.id} user:${payment.userId}",
                "SECURITY"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun adminUpdateUserStatus(
        targetUserId: String,
        accountStatus: AccountStatus,
        subscriptionPlan: SubscriptionPlan
    ): Result<Unit> {
        val path = "users/$targetUserId"
        return try {
            val adminUid = requireUserId()
            db.collection("users").document(targetUserId).update(
                mapOf(
                    "accountStatus" to accountStatus.name,
                    "subscriptionPlan" to subscriptionPlan.name,
                    "subscriptionStatus" to SubscriptionStatus.ACTIVE.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            logAuditInternal(
                adminUid,
                auth.currentUser?.email ?: "",
                "ADMIN_UPDATE_USER",
                "user:$targetUserId status:${accountStatus.name} plan:${subscriptionPlan.name}",
                "SECURITY"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun adminStopActiveStream(stream: StreamSession): Result<Unit> {
        val path = "active_streams/${stream.id}"
        return try {
            val adminUid = requireUserId()
            db.collection("users").document(stream.userId).collection("streams").document(stream.id)
                .update(
                    mapOf(
                        "status" to StreamStatus.STOPPED.name,
                        "workerStatus" to "ADMIN_TERMINATED",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            db.collection("active_streams").document(stream.id).delete().await()
            logAuditInternal(adminUid, auth.currentUser?.email ?: "", "ADMIN_STOP_STREAM", "stream:${stream.id}", "WARNING")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun adminRequestWithdrawal(
        amount: Double,
        method: WithdrawalMethod,
        destination: String
    ): Result<Unit> {
        val path = "withdrawals"
        return try {
            val adminUid = requireUserId()
            require(amount > 0.0 && destination.trim().length >= 4) { "Invalid withdrawal details" }
            db.collection("withdrawals").document().set(
                mapOf(
                    "adminId" to adminUid,
                    "amount" to amount,
                    "currency" to "BDT",
                    "method" to method.name,
                    "destination" to destination.trim().take(160),
                    "status" to WithdrawalStatus.PENDING.name,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            logAuditInternal(adminUid, auth.currentUser?.email ?: "", "ADMIN_REQUEST_WITHDRAWAL", "BDT $amount via ${method.name}", "SECURITY")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun submitAbuseReport(reason: String, details: String, targetStreamId: String = ""): Result<Unit> {
        val path = "reports"
        return try {
            val uid = requireUserId()
            val payload = mutableMapOf<String, Any>(
                "reporterId" to uid,
                "reason" to reason.trim().take(120),
                "details" to details.trim().take(500),
                "status" to ReportStatus.OPEN.name,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (targetStreamId.isNotBlank()) payload["targetStreamId"] = targetStreamId.take(128)
            db.collection("reports").document().set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun adminUpdateReportStatus(reportId: String, status: ReportStatus): Result<Unit> {
        val path = "reports/$reportId"
        return try {
            val adminUid = requireUserId()
            db.collection("reports").document(reportId).update(
                mapOf("status" to status.name, "updatedAt" to FieldValue.serverTimestamp())
            ).await()
            logAuditInternal(adminUid, auth.currentUser?.email ?: "", "ADMIN_REPORT_${status.name}", "report:$reportId", "INFO")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteUserAccountData(userId: String): Result<Unit> {
        val path = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId)
            db.collection("users").document(userId).delete().await()
            auth.currentUser?.delete()?.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    private suspend fun logAuditInternal(
        actorId: String,
        actorEmail: String,
        action: String,
        target: String,
        severity: String
    ) {
        runCatching {
            db.collection("audit_logs").document().set(
                mapOf(
                    "actorId" to actorId,
                    "actorEmail" to actorEmail.take(200),
                    "action" to action.take(100),
                    "target" to target.take(200),
                    "severity" to severity,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }
}
