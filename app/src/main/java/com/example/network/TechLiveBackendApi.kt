package com.example.network

import com.example.BuildConfig
import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class OAuthInitResponse(
    val authUrl: String,
    val provider: String,
    val configured: Boolean,
    val message: String
)

@JsonClass(generateAdapter = true)
data class CloudWorkerStartRequest(
    val streamId: String,
    val videoId: String,
    val durationHours: Int,
    val loopMode: String,
    val resolution: String,
    val fps: Int,
    val orientation: String,
    val aspectMode: String,
    val destinationIds: List<String>
)

@JsonClass(generateAdapter = true)
data class CloudWorkerResponse(
    val jobId: String,
    val workerNode: String,
    val status: String
)

@JsonClass(generateAdapter = true)
data class ResumableInitRequest(
    val fileName: String,
    val mimeType: String,
    val fileSizeBytes: Long
)

@JsonClass(generateAdapter = true)
data class ResumableInitResponse(
    val uploadId: String,
    val uploadUrl: String,
    val storageObjectPath: String
)

@JsonClass(generateAdapter = true)
data class ServerHealthSnapshot(
    val health: String,
    val cpuPercent: Int,
    val ramPercent: Int,
    val diskPercent: Int,
    val activeWorkers: Int,
    val activeStreams: Int,
    val failedWorkers: Int,
    val queueSize: Int
)

interface TechLiveBackendApi {
    @GET("oauth/{provider}/url")
    suspend fun getOAuthUrl(
        @Header("Authorization") bearerToken: String,
        @Path("provider") provider: String
    ): Response<OAuthInitResponse>

    @POST("uploads/resumable/init")
    suspend fun initResumableUpload(
        @Header("Authorization") bearerToken: String,
        @Body body: ResumableInitRequest
    ): Response<ResumableInitResponse>

    @PUT
    suspend fun uploadChunk(
        @Url uploadUrl: String,
        @Header("Content-Range") contentRange: String,
        @Body chunk: RequestBody
    ): Response<Unit>

    @POST("cloud-streams/start")
    suspend fun startCloudWorker(
        @Header("Authorization") bearerToken: String,
        @Body body: CloudWorkerStartRequest
    ): Response<CloudWorkerResponse>

    @POST("cloud-streams/{jobId}/stop")
    suspend fun stopCloudWorker(
        @Header("Authorization") bearerToken: String,
        @Path("jobId") jobId: String
    ): Response<Unit>

    @GET("admin/servers/health")
    suspend fun getServerHealth(
        @Header("Authorization") bearerToken: String
    ): Response<ServerHealthSnapshot>

    companion object {
        fun create(baseUrlOverride: String = ""): TechLiveBackendApi {
            val baseUrl = baseUrlOverride.takeIf { it.startsWith("http") }?.let {
                if (it.endsWith("/")) it else "$it/"
            } ?: BuildConfig.TECHLIVE_API_BASE_URL

            val logging = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                else HttpLoggingInterceptor.Level.NONE
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()

            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()
                .create(TechLiveBackendApi::class.java)
        }
    }
}
