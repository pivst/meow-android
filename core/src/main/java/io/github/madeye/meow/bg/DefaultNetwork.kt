package io.github.madeye.meow.bg

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.madeye.meow.traffic.TrafficNetwork
import timber.log.Timber

/**
 * Reports the network the engine's sockets go out on each time it changes,
 * until [stop]: the system's default network, which for this app is the VPN
 * itself, so the best one that is not a VPN is tracked instead. A change of
 * the network's capabilities alone is not reported.
 *
 * The registration per API level is shadowsocks-android's
 * `DefaultNetworkListener`, whose quirks it works around.
 */
internal class DefaultNetwork(
    context: Context,
    private var includeLocation: Boolean = false,
    private val onCapabilities: (NetworkCapabilities?) -> Unit = {},
    private val onNetwork: (Network?, NetworkCapabilities?) -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var generation = 0
    private var selected: Network? = null
    private var current: Network? = null

    @Synchronized private fun available(epoch: Int, network: Network) {
        if (epoch != generation) return
        selected = network
        onCapabilities(null)
        if (Build.VERSION.SDK_INT < 26) changed(epoch, network, connectivity.getNetworkCapabilities(network))
    }
    @Synchronized private fun changed(epoch: Int, network: Network, capabilities: NetworkCapabilities?) {
        if (epoch != generation || selected != network) return
        onCapabilities(capabilities)
        if (network == current) return
        current = network
        onNetwork(network, capabilities)
    }
    @Synchronized private fun lost(epoch: Int, network: Network) {
        if (epoch != generation) return
        if (selected == network) {
            selected = null
            onCapabilities(null)
        }
        if (network != current) return
        current = null
        onCapabilities(null)
        onNetwork(null, null)
    }
    private fun newCallback(epoch: Int): ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= 31) locationCallback(epoch) else
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = available(epoch, network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed(epoch, network, caps)
                override fun onLost(network: Network) = lost(epoch, network)
            }

    @TargetApi(31)
    private fun locationCallback(epoch: Int) = object : ConnectivityManager.NetworkCallback(
        if (includeLocation) FLAG_INCLUDE_LOCATION_INFO else 0,
    ) {
        override fun onAvailable(network: Network) = available(epoch, network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed(epoch, network, caps)
        override fun onLost(network: Network) = lost(epoch, network)
    }

    @Synchronized fun locationInfo(enabled: Boolean, refresh: Boolean = false) {
        if (includeLocation == enabled && !refresh) return
        stop()
        includeLocation = enabled
        start()
    }

    // INTERNET and NOT_RESTRICTED on top of the builder's NOT_VPN.
    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .build()

    @Synchronized fun start() {
        val handler = Handler(Looper.getMainLooper())
        val callback = newCallback(++generation).also { this.callback = it }
        try {
            when {
                Build.VERSION.SDK_INT >= 31 -> registerBestMatching(callback, handler)
                // A request, not a listen: it follows the network that wins.
                Build.VERSION.SDK_INT >= 28 -> connectivity.requestNetwork(request, callback, handler)
                Build.VERSION.SDK_INT >= 26 -> connectivity.registerDefaultNetworkCallback(callback, handler)
                else -> connectivity.registerDefaultNetworkCallback(callback)
            }
        } catch (e: RuntimeException) {
            // Too many requests for this uid, or an OEM refusal: the session
            // runs on without hearing of network changes.
            Timber.w(e, "DefaultNetwork: could not track the default network")
        }
    }

    @TargetApi(31)
    private fun registerBestMatching(callback: ConnectivityManager.NetworkCallback, handler: Handler) =
        connectivity.registerBestMatchingNetworkCallback(request, callback, handler)

    @Synchronized fun stop() {
        ++generation
        val callback = callback ?: return
        this.callback = null
        current = null
        selected = null
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Never registered: start() was refused.
            Timber.d(e, "DefaultNetwork: nothing to unregister")
        }
    }
}

/** The transports a logbook entry names a network by, in that order. */
internal fun NetworkCapabilities.transportNames(): List<String> = listOfNotNull(
    "Wi-Fi".takeIf { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
    "cellular".takeIf { hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
    "Ethernet".takeIf { hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
)

internal fun NetworkCapabilities.kindName(): String = transportNames().firstOrNull() ?: "network"

/** A network as logbook entries name it: `Wi-Fi 324`. */
internal fun Network.describe(capabilities: NetworkCapabilities?): String =
    "${capabilities?.kindName() ?: "network"} $this"

/** The network as the traffic ledger labels it ([TrafficNetwork]). */
internal fun NetworkCapabilities.ledgerLabel(): String = when {
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TrafficNetwork.WIFI
    hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TrafficNetwork.CELLULAR
    hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TrafficNetwork.ETHERNET
    else -> TrafficNetwork.OTHER
}
