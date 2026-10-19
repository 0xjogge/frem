package dk.frem.app.maps

import org.maplibre.android.tile.TileOperation

internal enum class BasemapState {
    Loading,
    Ready,
    Error,
}

internal fun BasemapState.afterTileOperation(operation: TileOperation): BasemapState =
    when (operation) {
        TileOperation.Error -> BasemapState.Error
        TileOperation.LoadFromCache,
        TileOperation.LoadFromNetwork -> BasemapState.Ready
        else -> this
    }

internal data class BasemapNotice(
    val message: String,
    val loading: Boolean = false,
    val retry: Boolean = false,
)

internal fun basemapNotice(
    connection: MapConnection,
    state: BasemapState,
    slow: Boolean,
): BasemapNotice? =
    when {
        !connection.canRequest ->
            BasemapNotice(
                "Offline · showing cached map tiles where available. Your route is stored on this device.",
                retry = true,
            )
        state == BasemapState.Error || (state == BasemapState.Loading && slow) ->
            BasemapNotice(
                "Map tiles could not load · your recorded route is still shown.",
                retry = true,
            )
        state == BasemapState.Loading -> BasemapNotice("Loading map tiles…", loading = true)
        else -> null
    }

internal fun shouldRetryBasemap(
    previousGeneration: Long,
    connection: MapConnection,
    state: BasemapState,
): Boolean =
    connection.availability == MapNetworkAvailability.Available &&
        connection.generation > previousGeneration &&
        state != BasemapState.Ready
