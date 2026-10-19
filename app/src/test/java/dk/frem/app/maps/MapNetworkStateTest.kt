package dk.frem.app.maps

import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import org.junit.Assert.*
import org.junit.Test

class MapNetworkStateTest {
    @Test
    fun internetCapableNetworkDoesNotRequireAndroidValidationToLoadTiles() {
        val capabilities = mutableSetOf(NET_CAPABILITY_INTERNET)
        val state = MapNetworkState<String>()
        state.available("wifi")
        state.capabilitiesChanged("wifi", canLoadMapTiles(capabilities::contains))
        assertEquals(MapNetworkAvailability.Available, state.connection.availability)
        val generation = state.connection.generation

        capabilities.add(NET_CAPABILITY_VALIDATED)
        state.capabilitiesChanged("wifi", canLoadMapTiles(capabilities::contains))
        capabilities.remove(NET_CAPABILITY_VALIDATED)
        state.capabilitiesChanged("wifi", canLoadMapTiles(capabilities::contains))
        assertTrue(state.connection.canRequest)
        assertEquals(generation, state.connection.generation)

        capabilities.remove(NET_CAPABILITY_INTERNET)
        state.capabilitiesChanged("wifi", canLoadMapTiles(capabilities::contains))
        assertFalse(state.connection.canRequest)
    }

    @Test
    fun waitsForCapabilitiesWithoutCallingANewNetworkOffline() {
        val state = MapNetworkState<String>()
        state.snapshot(null, null)
        assertFalse(state.connection.canRequest)

        state.available("wifi")
        assertEquals(MapNetworkAvailability.Unknown, state.connection.availability)
        assertTrue(state.connection.canRequest)

        state.capabilitiesChanged("wifi", internetCapable = true)
        assertEquals(MapConnection(MapNetworkAvailability.Available, 1), state.connection)
    }

    @Test
    fun duplicateAvailabilityAndCapabilitiesDoNotTriggerMoreRecoveryAttempts() {
        val state = MapNetworkState<String>()
        state.snapshot("wifi", internetCapable = true)
        val first = state.connection

        // Registration repeats the initial snapshot; later validation/transport changes can also
        // repeat INTERNET without meaning a new connection has become available.
        state.available("wifi")
        state.capabilitiesChanged("wifi", internetCapable = true)
        state.capabilitiesChanged("wifi", internetCapable = true)
        assertEquals(first, state.connection)
    }

    @Test
    fun wifiToMobileHandoverIgnoresLateCallbacksFromWifi() {
        val state = MapNetworkState<String>()
        state.snapshot("wifi", internetCapable = true)
        state.available("mobile")
        state.capabilitiesChanged("mobile", internetCapable = true)
        val mobile = state.connection

        state.lost("wifi")
        state.capabilitiesChanged("wifi", internetCapable = false)
        assertEquals(MapConnection(MapNetworkAvailability.Available, 2), mobile)
        assertEquals(mobile, state.connection)
    }

    @Test
    fun reconnectingOrRegainingInternetCapabilityCreatesANewRecoveryGeneration() {
        val state = MapNetworkState<String>()
        state.snapshot("wifi", internetCapable = true)
        state.capabilitiesChanged("wifi", internetCapable = false)
        assertFalse(state.connection.canRequest)
        state.capabilitiesChanged("wifi", internetCapable = true)
        assertEquals(2L, state.connection.generation)

        state.lost("wifi")
        assertFalse(state.connection.canRequest)
        state.available("wifi")
        state.capabilitiesChanged("wifi", internetCapable = true)
        assertEquals(MapConnection(MapNetworkAvailability.Available, 3), state.connection)
    }

    @Test
    fun missingCapabilitiesAndUnavailableMonitoringAllowActualRequests() {
        val state = MapNetworkState<String>()
        state.snapshot("wifi", internetCapable = false)
        state.snapshot("wifi", internetCapable = null)
        assertEquals(MapNetworkAvailability.Unknown, state.connection.availability)
        assertTrue(state.connection.canRequest)

        state.snapshot(null, null)
        state.reset()
        assertTrue(state.connection.canRequest)
        state.capabilitiesChanged("wifi", internetCapable = false)
        assertEquals(MapNetworkAvailability.Unknown, state.connection.availability)
    }

    @Test
    fun manualSnapshotRecoversFromAMissedConnectivityEvent() {
        val state = MapNetworkState<String>()
        state.snapshot(null, null)
        state.snapshot("mobile", internetCapable = true)
        assertEquals(MapConnection(MapNetworkAvailability.Available, 1), state.connection)
    }
}
