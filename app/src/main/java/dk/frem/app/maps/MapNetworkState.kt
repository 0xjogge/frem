package dk.frem.app.maps

internal enum class MapNetworkAvailability {
    Unknown,
    Available,
    Unavailable,
}

internal data class MapConnection(
    val availability: MapNetworkAvailability = MapNetworkAvailability.Unknown,
    val generation: Long = 0,
) {
    // An unavailable system service/permission must not prevent trying the actual tile server.
    val canRequest: Boolean
        get() = availability != MapNetworkAvailability.Unavailable
}

/** Tracks the default network without querying ConnectivityManager from inside its callbacks. */
internal class MapNetworkState<N : Any> {
    private var network: N? = null
    var connection = MapConnection()
        private set

    fun snapshot(network: N?, internetCapable: Boolean?) {
        if (network == null) {
            this.network = null
            update(MapNetworkAvailability.Unavailable)
        } else {
            available(network)
            if (internetCapable != null) capabilitiesChanged(network, internetCapable)
            else update(MapNetworkAvailability.Unknown)
        }
    }

    fun available(network: N) {
        if (this.network != network) {
            this.network = network
            // onAvailable precedes onCapabilitiesChanged. Do not assume the new connection is
            // offline while waiting for its capabilities, or inherit the old network's verdict.
            update(MapNetworkAvailability.Unknown)
        }
    }

    fun capabilitiesChanged(network: N, internetCapable: Boolean) {
        if (this.network != network) return
        update(
            if (internetCapable) MapNetworkAvailability.Available
            else MapNetworkAvailability.Unavailable,
        )
    }

    fun lost(network: N) {
        // A late onLost for Wi-Fi must not mark its mobile-data replacement offline.
        if (this.network != network) return
        this.network = null
        update(MapNetworkAvailability.Unavailable)
    }

    fun reset() {
        network = null
        update(MapNetworkAvailability.Unknown)
    }

    private fun update(availability: MapNetworkAvailability) {
        val recovered =
            availability == MapNetworkAvailability.Available &&
                connection.availability != MapNetworkAvailability.Available
        connection =
            MapConnection(
                availability,
                connection.generation + if (recovered) 1 else 0,
            )
    }
}
