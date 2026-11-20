package com.example.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    path: String?
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

fun toFriendlyErrorMessage(error: Throwable?): String {
    val firestoreErr = generateSequence(error) { it?.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
    return when (firestoreErr?.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "You do not have permission for this action. Please verify your account status or plan."
        FirebaseFirestoreException.Code.UNAVAILABLE ->
            "Network unavailable. Your changes will sync once connection is restored."
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
            "Connection timed out. Please check your internet speed and try again."
        FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED ->
            "Service quota reached for today. Please try again later."
        else -> {
            val msg = error?.localizedMessage ?: ""
            when {
                msg.contains("quota", ignoreCase = true) ->
                    "Your daily free streaming limit has been reached."
                msg.contains("YouTube", ignoreCase = true) ->
                    "Unable to connect to YouTube. Please reconnect your account."
                else -> "Something went wrong. Please check your connection and try again."
            }
        }
    }
}
