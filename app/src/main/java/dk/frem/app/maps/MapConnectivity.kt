package dk.frem.app.maps

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread

/** One main-thread monitor shared by the SDK and UI, active only while a map is visible. */
internal class MapConnectivity(context: Context, private val changed: (MapConnection) -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val state = MapNetworkState<Network>()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var published = state.connection

    @MainThread
    fun start() {
        if (callback != null) return
        try {
            // Seed before registering. Registration then delivers the current default network,
            // including any change between this snapshot and registration.
            snapshot()
            val observer =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (callback !== this) return
                        state.available(network)
                        publish()
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        if (callback !== this) return
                        state.capabilitiesChanged(
                            network,
                            canLoadMapTiles(capabilities::hasCapability),
                        )
                        publish()
                    }

                    override fun onLost(network: Network) {
                        if (callback !== this) return
                        state.lost(network)
                        publish()
                    }
                }
            callback = observer
            manager.registerDefaultNetworkCallback(observer, handler)
        } catch (_: SecurityException) {
            callback = null
            state.reset()
            publish()
        }
    }

    @MainThread
    fun refresh() {
        if (callback == null) {
            start()
            return
        }
        try {
            snapshot()
        } catch (_: SecurityException) {
            state.reset()
            publish()
        }
    }

    @MainThread
    fun stop() {
        val observer = callback
        // Ignore any already queued callbacks from this registration after stopping/restarting.
        callback = null
        if (observer != null) manager.unregisterNetworkCallback(observer)
        state.reset()
        publish()
    }

    private fun snapshot() {
        val network = manager.activeNetwork
        state.snapshot(
            network,
            network?.let {
                manager.getNetworkCapabilities(it)?.let { capabilities ->
                    canLoadMapTiles(capabilities::hasCapability)
                }
            },
        )
        publish()
    }

    private fun publish() {
        if (published == state.connection) return
        published = state.connection
        changed(published)
    }
}

// VALIDATED describes Android's probe servers, not whether OSM is reachable. Let the actual tile
// request establish that; VPNs, DNS filtering, and ordinary transient validation failures differ.
internal fun canLoadMapTiles(hasCapability: (Int) -> Boolean): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
