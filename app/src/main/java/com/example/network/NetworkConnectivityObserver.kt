package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class NetworkStatus {
    ONLINE,
    CONNECTING,
    OFFLINE
}

class NetworkConnectivityObserver(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun observe(): Flow<NetworkStatus> = callbackFlow {
        val active = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(active)
        val isOnlineNow = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        trySend(if (isOnlineNow) NetworkStatus.ONLINE else NetworkStatus.OFFLINE)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(NetworkStatus.CONNECTING)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                trySend(if (validated) NetworkStatus.ONLINE else NetworkStatus.CONNECTING)
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                trySend(NetworkStatus.CONNECTING)
            }

            override fun onLost(network: Network) {
                trySend(NetworkStatus.OFFLINE)
            }

            override fun onUnavailable() {
                trySend(NetworkStatus.OFFLINE)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
        }.onFailure {
            trySend(NetworkStatus.ONLINE)
        }

        awaitClose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()
}
