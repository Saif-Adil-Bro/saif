package com.saif.mosque.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * Reports real-time internet connectivity with debouncing for transient packet drops or cell handovers.
 * Does NOT require NET_CAPABILITY_VALIDATED so standard mobile data (4G/5G/3G) is recognized immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Context.internetConnectivity(): Flow<Boolean> = callbackFlow {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isOnline(): Boolean {
        val active = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val notRestricted = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        val hasTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
        return hasInternet && notRestricted && hasTransport
    }

    fun report() {
        trySend(isOnline())
    }

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = report()
        override fun onLost(network: Network) = report()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = report()
    }

    manager.registerDefaultNetworkCallback(callback)
    report()
    awaitClose { manager.unregisterNetworkCallback(callback) }
}
    .distinctUntilChanged()
    .transformLatest { isConnected ->
        if (isConnected) {
            // Instant recovery when network comes back online
            emit(true)
        } else {
            // Like standard apps, debounce network drops for 3.5 seconds to prevent
            // flicker during cell tower handovers, brief signal fluctuations, or idle mobile data
            delay(3500)
            emit(false)
        }
    }
    .distinctUntilChanged()

