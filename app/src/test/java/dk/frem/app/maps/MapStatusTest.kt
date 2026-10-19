package dk.frem.app.maps

import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.tile.TileOperation

class MapStatusTest {
    @Test
    fun successfulTileLoadsClearErrorsWithoutWaitingForAManualRetry() {
        for (success in listOf(TileOperation.LoadFromNetwork, TileOperation.LoadFromCache)) {
            var state = BasemapState.Loading.afterTileOperation(TileOperation.Error)
            assertTrue(basemapNotice(MapConnection(), state, slow = false)!!.retry)

            state = state.afterTileOperation(TileOperation.RequestedFromNetwork)
            assertEquals(BasemapState.Error, state)
            state = state.afterTileOperation(success)
            assertEquals(BasemapState.Ready, state)
            assertNull(basemapNotice(MapConnection(), state, slow = false))
            // A timeout coroutine may still be clearing its flag on this frame.
            assertNull(basemapNotice(MapConnection(), state, slow = true))
        }
    }

    @Test
    fun offlineAndServerFailureHaveDistinctMessagesButBothAllowRetry() {
        val offline =
            basemapNotice(
                MapConnection(MapNetworkAvailability.Unavailable),
                BasemapState.Error,
                slow = false,
            )!!
        val serverFailure =
            basemapNotice(
                MapConnection(MapNetworkAvailability.Available),
                BasemapState.Error,
                slow = false,
            )!!
        assertTrue(offline.message.startsWith("Offline"))
        assertFalse(serverFailure.message.contains("Offline"))
        assertTrue(offline.retry)
        assertTrue(serverFailure.retry)
        assertFalse(offline.loading)
        assertFalse(serverFailure.loading)
    }

    @Test
    fun unknownConnectivityAllowsLoadingAndSlowLoadsExposeRetry() {
        val connection = MapConnection()
        val loading = basemapNotice(connection, BasemapState.Loading, slow = false)!!
        assertFalse(loading.message.contains("Offline"))
        assertTrue(loading.loading)
        assertFalse(loading.retry)

        val slow = basemapNotice(connection, BasemapState.Loading, slow = true)!!
        assertFalse(slow.loading)
        assertTrue(slow.retry)
    }

    @Test
    fun reconnectAndNetworkHandoverRetryStalledMapsOncePerGeneration() {
        val network = MapNetworkState<String>()
        network.snapshot("wifi", internetCapable = true)
        val previous = network.connection.generation
        network.lost("wifi")
        assertFalse(shouldRetryBasemap(previous, network.connection, BasemapState.Error))
        network.available("mobile")
        assertFalse(shouldRetryBasemap(previous, network.connection, BasemapState.Error))
        network.capabilitiesChanged("mobile", internetCapable = true)
        assertTrue(shouldRetryBasemap(previous, network.connection, BasemapState.Error))
        assertTrue(shouldRetryBasemap(previous, network.connection, BasemapState.Loading))
        assertFalse(shouldRetryBasemap(previous, network.connection, BasemapState.Ready))
        assertFalse(
            shouldRetryBasemap(
                network.connection.generation,
                network.connection,
                BasemapState.Error,
            ),
        )
    }
}
