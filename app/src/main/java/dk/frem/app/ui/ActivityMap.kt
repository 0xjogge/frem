package dk.frem.app.ui

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.LocationSearching
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.frem.app.maps.BasemapState
import dk.frem.app.maps.DisplayRoute
import dk.frem.app.maps.MapCache
import dk.frem.app.maps.MapConnection
import dk.frem.app.maps.afterTileOperation
import dk.frem.app.maps.basemapNotice
import dk.frem.app.maps.buildDisplayRoute
import dk.frem.app.maps.shouldRetryBasemap
import dk.frem.app.maps.wrapLongitude
import dk.frem.app.recording.TrackPoint
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Point

/**
 * Interactive OSM standard raster map, driven exclusively by recorded GPS points. Pass a new
 * immutable list when points change. No location engine or GPS request is used here. [live] follows
 * the last recorded fix until the user pans or turns following off; saved routes fit automatically.
 * Supply Modifier.fillMaxSize() for the full-map recording screen.
 */
@Composable
fun ActivityMap(points: List<TrackPoint>, modifier: Modifier = Modifier, live: Boolean = false) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    // Geometry/simplification runs once per input-list change, off the main thread. UI state,
    // map gestures and status recompositions never rebuild the route's GeoJSON.
    val route by
        produceState(DisplayRoute.Empty, points) {
            value = withContext(Dispatchers.Default) { buildDisplayRoute(points) }
        }
    val setup by
        produceState<Result<Unit>?>(null, inspection) {
            if (!inspection) {
                value =
                    try {
                        MapCache.awaitReady(context.applicationContext)
                        Result.success(Unit)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
            }
        }

    Box(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 260.dp)
            .clipToBounds()
            .background(Color(0xFFECEDE5))
            .testTag("activity_map"),
    ) {
        when {
            inspection ->
                MapNotice(
                    "OpenStreetMap · recorded route",
                    false,
                    Modifier.align(Alignment.TopStart),
                )
            setup?.isSuccess == true -> RecordedMap(route, live, Modifier.matchParentSize())
            setup?.isFailure == true ->
                MapNotice(
                    "Map could not start. Your recorded points are still saved.",
                    false,
                    Modifier.align(Alignment.TopStart),
                )
            else -> MapNotice("Starting map…", true, Modifier.align(Alignment.TopStart))
        }
        // Explicit, always-visible attribution replaces the SDK's info-button dialog, whose
        // default parser can also include vendor links. This remains visible offline/on error.
        val uriHandler = LocalUriHandler.current
        Surface(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open OpenStreetMap copyright") {
                        try {
                            uriHandler.openUri(MapCache.COPYRIGHT_URL)
                        } catch (_: IllegalArgumentException) {
                            Toast.makeText(context, MapCache.COPYRIGHT_URL, Toast.LENGTH_LONG)
                                .show()
                        }
                    }
                    .testTag("map_attribution"),
            color = Color.White.copy(alpha = 0.97f),
            contentColor = Color(0xFF202820),
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(
                "© OpenStreetMap contributors",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun RecordedMap(route: DisplayRoute, live: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val saved = rememberSaveable(saver = SavedMapState.Saver) { SavedMapState(Bundle()) }
    val controller = remember(context, saved) { RecordedMapController(context, saved.bundle, live) }
    DisposableEffect(controller, saved) {
        saved.capture = controller::saveState
        val memory =
            object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                override fun onLowMemory() {
                    controller.lowMemory()
                }

                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) controller.lowMemory()
                }
            }
        context.applicationContext.registerComponentCallbacks(memory)
        onDispose {
            saved.bundle = controller.saveState()
            saved.capture = null
            context.applicationContext.unregisterComponentCallbacks(memory)
            controller.destroy()
        }
    }
    DisposableEffect(lifecycle, controller) {
        // Navigation3 caps retained/transitioning entries below RESUMED. STARTED alone
        // would let an outgoing screen continue rendering and requesting tiles.
        val observer = LifecycleEventObserver { _, _ ->
            controller.setResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        controller.setResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            controller.setResumed(false)
        }
    }
    SideEffect { controller.update(route, live) }
    val connection by MapCache.connection.collectAsStateWithLifecycle()
    LaunchedEffect(controller, controller.rendering, connection) {
        controller.connectionChanged(connection)
    }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(
        controller.basemapState,
        controller.rendering,
        connection,
        controller.loadAttempt,
    ) {
        slow = false
        if (
            controller.rendering &&
                connection.canRequest &&
                controller.basemapState == BasemapState.Loading
        ) {
            delay(12_000)
            slow = true
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { controller.view },
            modifier =
                Modifier.matchParentSize().onGloballyPositioned { controller.refreshVisibility() },
            onRelease = { controller.destroy() },
            update = {
                it.contentDescription =
                    "OpenStreetMap. ${route.pointCount} recorded ${if (route.pointCount == 1) "point" else "points"} in ${route.segmentCount} route ${if (route.segmentCount == 1) "segment" else "segments"}. " +
                        if (live) "Blue marker: last recorded fix. Green: start."
                        else "Green: start. Orange: end."
            },
        )
        val basemapNotice = basemapNotice(connection, controller.basemapState, slow)
        val notice =
            when {
                basemapNotice != null -> basemapNotice.message
                route.pointCount == 0 && live ->
                    "No recorded GPS points yet. Start recording to add your route."
                route.pointCount == 0 -> "No recorded GPS points for this activity."
                route.pointCount == 1 -> "One recorded GPS point · no route line yet."
                else -> null
            }
        if (notice != null) {
            MapNotice(
                notice,
                basemapNotice?.loading == true,
                Modifier.align(Alignment.TopStart),
                retry = if (basemapNotice?.retry == true) controller::retry else null,
            )
        }
        if (route.pointCount > 0) {
            Surface(
                modifier =
                    Modifier.align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 66.dp)
                        .widthIn(max = 200.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MapLegend(Color(0xFF257344), "Start")
                    MapLegend(
                        if (live) Color(0xFF195DCA) else Color(0xFFD85A32),
                        if (live) "Last fix" else "End",
                    )
                }
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 66.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(
                onClick = controller::fitRoute,
                enabled = route.pointCount > 0,
                modifier = Modifier.size(48.dp).testTag("map_fit_route"),
            ) {
                Icon(Icons.Rounded.CenterFocusStrong, "Fit entire recorded route")
            }
            FilledTonalIconButton(
                onClick = controller::toggleFollowOrRecenter,
                enabled = route.pointCount > 0,
                modifier =
                    Modifier.size(48.dp)
                        .semantics {
                            stateDescription =
                                if (live && controller.following) "Following last recorded fix"
                                else "Free map movement"
                        }
                        .testTag("map_follow_position"),
            ) {
                Icon(
                    if (live && controller.following) Icons.Rounded.MyLocation
                    else Icons.Rounded.LocationSearching,
                    if (!live) "Recenter on last recorded point"
                    else if (controller.following) "Turn off following"
                    else "Follow last recorded fix",
                )
            }
        }
    }
}

@Composable
private fun MapNotice(
    message: String,
    loading: Boolean,
    modifier: Modifier,
    retry: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.padding(12.dp).fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (retry != null) TextButton(onClick = retry) { Text("Retry") }
        }
    }
}

@Composable
private fun MapLegend(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Captures native camera/UI state at the actual SaveableStateRegistry save, not only disposal. */
private class SavedMapState(var bundle: Bundle) {
    var capture: (() -> Bundle)? = null

    companion object {
        val Saver =
            Saver<SavedMapState, Bundle>(
                save = { it.capture?.invoke() ?: it.bundle },
                restore = { SavedMapState(it) },
            )
    }
}

private const val OSM_SOURCE = "frem-osm"
private const val OSM_LAYER = "frem-basemap"
private const val ROUTE_SOURCE = "frem-recorded-route"
private const val CURRENT_LAYER = "frem-current"

// All style metadata is local. There is no remote TileJSON, sprite, glyph, style or telemetry URL.
private val OSM_STYLE =
    """
    {
      "version": 8,
      "name": "Frem · OpenStreetMap standard",
      "sources": {
        "$OSM_SOURCE": {
          "type": "raster", "tiles": ["${MapCache.TILE_URL}"],
          "tileSize": 256, "minzoom": 0, "maxzoom": 19,
          "attribution": "© <a href='${MapCache.COPYRIGHT_URL}'>OpenStreetMap</a> contributors"
        }
      },
      "layers": [
        {"id": "frem-background", "type": "background", "paint": {"background-color": "#ecede5"}},
        {"id": "$OSM_LAYER", "type": "raster", "source": "$OSM_SOURCE", "paint": {"raster-fade-duration": 0}}
      ]
    }
"""
        .trimIndent()

/** Owns a single native view. All calls and lifecycle changes are on the main thread. */
private class RecordedMapController(context: Context, saved: Bundle, initialLive: Boolean) {
    val view =
        VisibilityMapView(
            context,
            MapLibreMapOptions()
                .textureMode(
                    true,
                ) // Composes/clips correctly with navigation transitions and overlays.
                .foregroundLoadColor(android.graphics.Color.TRANSPARENT)
                .attributionEnabled(false)
                .logoEnabled(false)
                .compassEnabled(false)
                .rotateGesturesEnabled(false)
                .tiltGesturesEnabled(false)
                .minZoomPreference(0.0)
                .maxZoomPreference(19.0)
                .setPrefetchesTiles(false)
                .setPrefetchZoomDelta(0)
                .camera(CameraPosition.Builder().target(LatLng(56.0, 10.0)).zoom(6.0).build()),
        )
    var following by mutableStateOf(saved.getBoolean("frem.following", initialLive))
        private set

    var rendering by mutableStateOf(false)
        private set

    var basemapState by mutableStateOf(BasemapState.Loading)
        private set

    var loadAttempt by mutableLongStateOf(0L)
        private set

    private var networkGeneration = 0L
    private var resumed = false
    private var destroyed = false
    private var destroyedState: Bundle? = null
    private var live = initialLive
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleRequested = false
    private var route = DisplayRoute.Empty
    private var uploadedRoute: DisplayRoute? = null
    private var cameraInitialized = saved.getBoolean("frem.cameraInitialized", false)
    private var autoFit = saved.getBoolean("frem.autoFit", true)
    private var sessionId: String? = saved.getString("frem.session")
    private var cameraRoute: DisplayRoute? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private val visibleRect = Rect()
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        refreshVisibility()
        true
    }
    private val gestureListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
            following = false
            autoFit = false
            if (basemapState == BasemapState.Error) basemapState = BasemapState.Loading
        }
    }

    init {
        view.onCreate(saved)
        view.visibilityChanged = ::refreshVisibility
        view.viewTreeObserver.addOnPreDrawListener(preDraw)
        view.addOnDidFailLoadingMapListener {
            if (!destroyed && rendering) basemapState = BasemapState.Error
        }
        view.addOnTileActionListener { operation, _, _, _, _, _, source ->
            // The SDK delivers map events on the main thread. Never retain tile coordinates.
            if (!destroyed && rendering && source == OSM_SOURCE) {
                basemapState = basemapState.afterTileOperation(operation)
            }
        }
        view.getMapAsync { ready ->
            if (!destroyed) {
                map = ready
                ready.setPrefetchZoomDelta(0)
                ready.uiSettings.apply {
                    isAttributionEnabled = false
                    isLogoEnabled = false
                    isCompassEnabled = false
                }
                ready.addOnCameraMoveStartedListener(gestureListener)
                if (rendering) loadStyle()
            }
        }
    }

    fun setResumed(value: Boolean) {
        resumed = value
        refreshVisibility()
    }

    fun refreshVisibility() {
        if (destroyed) return
        val visible =
            resumed &&
                view.windowAttached &&
                view.aggregatedVisible &&
                view.isAttachedToWindow &&
                view.isShown &&
                view.windowVisibility == View.VISIBLE &&
                view.width > 0 &&
                view.height > 0 &&
                view.getGlobalVisibleRect(visibleRect) &&
                !visibleRect.isEmpty
        if (visible != rendering) {
            rendering = visible
            if (visible) {
                MapCache.startViewing(this)
                networkGeneration = MapCache.connection.value.generation
                view.onStart()
                view.onResume()
                if (style == null) loadStyle()
                else {
                    applyRoute()
                    updateCamera()
                    addRaster()
                }
            } else {
                // Removing the source cancels this map's in-flight tile requests, including
                // when another MapView keeps the SDK's shared FileSource active.
                removeRaster()
                map?.cancelTransitions()
                view.onPause()
                view.onStop()
                MapCache.stopViewing(this)
            }
        }
        if (visible && (lastWidth != view.width || lastHeight != view.height)) {
            lastWidth = view.width
            lastHeight = view.height
            if (!live && autoFit) updateCamera(force = true)
        }
    }

    fun update(value: DisplayRoute, isLive: Boolean) {
        if (destroyed) return
        val modeChanged = live != isLive
        live = isLive
        if (value.sessionId != null && sessionId != value.sessionId) {
            sessionId = value.sessionId
            cameraInitialized = false
            autoFit = true
            following = live
        }
        if (route !== value || modeChanged) {
            route = value
            if (rendering) {
                applyRoute()
                updateCamera(force = modeChanged)
            }
        }
    }

    private fun loadStyle() {
        val currentMap = map ?: return
        if (styleRequested || destroyed || !rendering) return
        styleRequested = true
        // Set the intended viewport before attaching the network source, so opening a saved
        // route does not briefly request tiles around the default Denmark camera first.
        updateCamera()
        currentMap.setStyle(Style.Builder().fromJson(OSM_STYLE)) { loaded ->
            if (!destroyed) {
                style = loaded
                // MapView's default loading foreground can otherwise obscure a perfectly
                // usable local route while raster tiles are unavailable.
                view.foreground = null
                installRouteLayers(loaded)
                if (rendering) {
                    applyRoute()
                    updateCamera()
                } else removeRaster()
            }
        }
    }

    private fun installRouteLayers(style: Style) {
        style.addSource(GeoJsonSource(ROUTE_SOURCE, route.features))
        uploadedRoute = route
        style.addLayer(
            LineLayer("frem-route-casing", ROUTE_SOURCE)
                .withFilter(eq(get("kind"), literal("route")))
                .withProperties(
                    lineColor("#ffffff"),
                    lineWidth(8f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND),
                ),
        )
        style.addLayer(
            LineLayer("frem-route-line", ROUTE_SOURCE)
                .withFilter(eq(get("kind"), literal("route")))
                .withProperties(
                    lineColor("#234d39"),
                    lineWidth(4.5f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND),
                ),
        )
        fun marker(id: String, kind: String, radius: Float, color: String) {
            style.addLayer(
                CircleLayer(id, ROUTE_SOURCE)
                    .withFilter(eq(get("kind"), literal(kind)))
                    .withProperties(
                        circleRadius(radius),
                        circleColor(color),
                        circleStrokeWidth(2f),
                        circleStrokeColor("#ffffff"),
                    ),
            )
        }
        marker("frem-isolated-fix", "isolated", 3f, "#234d39")
        marker("frem-start", "start", 11f, "#257344")
        marker("frem-end", "end", 7f, "#d85a32")
        marker(CURRENT_LAYER, "current", 4f, "#195dca")
        style
            .getLayer(CURRENT_LAYER)
            ?.setProperties(visibility(if (live) Property.VISIBLE else Property.NONE))
    }

    private fun applyRoute() {
        val loaded = style ?: return
        if (uploadedRoute !== route) {
            loaded.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(route.features)
            uploadedRoute = route
        }
        loaded
            .getLayer(CURRENT_LAYER)
            ?.setProperties(visibility(if (live) Property.VISIBLE else Property.NONE))
    }

    private fun removeRaster() {
        style?.let {
            if (it.getLayer(OSM_LAYER) != null) it.removeLayer(OSM_LAYER)
            if (it.getSource(OSM_SOURCE) != null) it.removeSource(OSM_SOURCE)
        }
    }

    private fun addRaster() {
        val loaded = style ?: return
        if (loaded.getSource(OSM_SOURCE) == null) {
            val tiles =
                TileSet("2.2.0", MapCache.TILE_URL).apply {
                    minZoom = 0f
                    maxZoom = 19f
                    attribution = MapCache.ATTRIBUTION
                }
            loaded.addSource(RasterSource(OSM_SOURCE, tiles, 256))
            loaded.addLayerAt(
                RasterLayer(OSM_LAYER, OSM_SOURCE).withProperties(rasterFadeDuration(0f)),
                1,
            )
            basemapState = BasemapState.Loading
        }
    }

    fun retry() {
        if (!rendering || destroyed) return
        MapCache.refreshConnectivity()
        networkGeneration = MapCache.connection.value.generation
        basemapState = BasemapState.Loading
        loadAttempt++
        if (style == null) {
            styleRequested = false
            loadStyle()
        } else {
            // Recreate only the raster source. Preserve the route, camera and controls;
            // native HTTP cache/validators still apply, with no forced re-download.
            removeRaster()
            addRaster()
        }
    }

    fun connectionChanged(connection: MapConnection) {
        if (!rendering || destroyed) return
        if (connection.generation < networkGeneration) return
        val needsRetry = shouldRetryBasemap(networkGeneration, connection, basemapState)
        networkGeneration = connection.generation
        // An in-flight local style needs no restart; the native source resumes with connectivity.
        if (needsRetry && (style != null || basemapState == BasemapState.Error)) retry()
    }

    fun fitRoute() {
        following = false
        autoFit = true
        updateCamera(force = true, forceFit = true)
    }

    fun toggleFollowOrRecenter() {
        if (live && following) {
            following = false
        } else {
            following = live
            autoFit = false
            recenter(zoomIn = true)
        }
    }

    private fun updateCamera(force: Boolean = false, forceFit: Boolean = false) {
        if (!rendering || map == null || route.last == null) return
        if (!force && cameraRoute === route && cameraInitialized) return
        when {
            forceFit -> fit()
            live && following -> recenter(zoomIn = !cameraInitialized)
            !live && (autoFit || !cameraInitialized) -> fit()
            !cameraInitialized -> fit()
        }
        cameraRoute = route
    }

    private fun fit() {
        val bounds = route.bounds ?: return
        val currentMap = map ?: return
        val density = view.resources.displayMetrics.density
        val width = max(40.0, view.width / density.toDouble() - 112.0)
        val height = max(40.0, view.height / density.toDouble() - 192.0)
        val x = bounds.longitudeSpan / 360.0
        val y = bounds.southY - bounds.northY
        // MapLibre's camera world is 512 logical pixels at zoom 0, even for 256px rasters.
        fun zoom(space: Double, span: Double) =
            if (span < 1e-10) 17.0 else ln(space / (512.0 * span)) / ln(2.0)
        val zoom =
            if (route.pointCount == 1) 16.0
            else min(zoom(width, x), zoom(height, y)).coerceIn(0.0, 17.0)
        currentMap.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(bounds.latitude, bounds.longitude))
                    .zoom(zoom)
                    .bearing(0.0)
                    .tilt(0.0)
                    .build(),
            ),
        )
        cameraInitialized = true
    }

    private fun recenter(zoomIn: Boolean) {
        val last: Point = route.last ?: return
        val currentMap = map ?: return
        if (!rendering) return
        val zoom =
            if (zoomIn) max(15.5, currentMap.cameraPosition.zoom)
            else currentMap.cameraPosition.zoom
        // Instant camera changes avoid requesting tiles along an animated flight path.
        currentMap.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(last.latitude(), wrapLongitude(last.longitude())))
                    .zoom(zoom.coerceAtMost(19.0))
                    .bearing(0.0)
                    .tilt(0.0)
                    .build(),
            ),
        )
        cameraInitialized = true
        cameraRoute = route
    }

    fun saveState(): Bundle =
        destroyedState?.let(::Bundle)
            ?: Bundle().also {
                if (!destroyed) view.onSaveInstanceState(it)
                it.putBoolean("frem.following", following)
                it.putBoolean("frem.autoFit", autoFit)
                it.putBoolean("frem.cameraInitialized", cameraInitialized)
                it.putString("frem.session", sessionId)
            }

    fun lowMemory() {
        if (!destroyed) view.onLowMemory()
    }

    fun destroy() {
        if (destroyed) return
        destroyedState = saveState()
        setResumed(false)
        destroyed = true
        view.visibilityChanged = null
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(preDraw)
        map?.removeOnCameraMoveStartedListener(gestureListener)
        view.onDestroy()
        map = null
        style = null
    }
}

private class VisibilityMapView(context: Context, options: MapLibreMapOptions) :
    MapView(context, options) {
    var visibilityChanged: (() -> Unit)? = null
    var windowAttached = false
        private set

    var aggregatedVisible = true
        private set

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        windowAttached = true
        visibilityChanged?.invoke()
    }

    override fun onDetachedFromWindow() {
        windowAttached = false
        visibilityChanged?.invoke()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        visibilityChanged?.invoke()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        visibilityChanged?.invoke()
    }
}
