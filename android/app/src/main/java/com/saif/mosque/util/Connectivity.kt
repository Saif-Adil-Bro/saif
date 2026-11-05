package com.saif.mosque.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Reports validated internet connectivity, not merely a connected Wi-Fi network. */
fun Context.internetConnectivity(): Flow<Boolean> = callbackFlow {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    fun report() {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        trySend(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = report()
        override fun onLost(network: Network) = report()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = report()
    }
    manager.registerDefaultNetworkCallback(callback)
    report()
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
