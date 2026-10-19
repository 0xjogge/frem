package dk.frem.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dk.frem.app.maps.MapCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.MapLibre

class MapConnectivityDeviceTest {
    @Test
    fun visibleMapsShareConnectivityAndLastHiddenMapSuspendsSdkRequests() {
        runBlocking { MapCache.awaitReady(ApplicationProvider.getApplicationContext()) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val first = Any()
            val second = Any()
            try {
                MapCache.startViewing(first)
                MapCache.startViewing(second)
                assertEquals(MapCache.connection.value.canRequest, MapLibre.isConnected())
                MapCache.stopViewing(first)
                assertEquals(MapCache.connection.value.canRequest, MapLibre.isConnected())
                MapCache.refreshConnectivity()
                assertEquals(MapCache.connection.value.canRequest, MapLibre.isConnected())
            } finally {
                MapCache.stopViewing(first)
                MapCache.stopViewing(second)
            }
            assertFalse(MapLibre.isConnected())
        }
    }
}
