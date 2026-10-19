package dk.frem.app.maps

import android.content.Context
import android.os.Looper
import androidx.annotation.MainThread
import dk.frem.app.BuildConfig
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.RenderingEngine
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.storage.FileSource

/**
 * The map's bounded, ordinary browsing cache. There are no downloaded/offline regions.
 *
 * MapLibre's native ambient cache is the single disk cache: its HTTP implementation passes
 * Cache-Control, Expires, ETag and Last-Modified through to the native cache, and supplies
 * conditional headers when revalidating. A second OkHttp disk cache would duplicate tiles. Do not
 * invalidate the cache on launch or add no-cache headers to the HTTP client.
 *
 * ActivityMap initializes this lazily, before constructing its first MapView. An application may
 * also call [initialize] from Application.onCreate; no API key is needed for the local style. Tile
 * requests go directly to OSM, revealing the viewer's IP address and viewed map areas.
 */
object MapCache {
    const val MAX_BYTES: Long = 100L * 1024L * 1024L
    const val COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
    const val TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    const val ATTRIBUTION =
        "© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"

    private var initialization: CompletableDeferred<Unit>? = null
    private var httpClient: OkHttpClient? = null
    private val viewers = mutableSetOf<Any>()
    private val clearMutex = Mutex()
    private var clearing = false
    @Volatile private var networkAllowed = false

    /** Main thread, idempotent; the asynchronous cache-size operation finishes in [awaitReady]. */
    @MainThread
    fun initialize(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (initialization != null) return
        val ready = CompletableDeferred<Unit>()
        initialization = ready
        try {
            // The four-argument SDK overload selects the backend before the native library
            // or FileSource is initialized. Keep this explicit with android-sdk-opengl.
            MapLibre.getInstance(
                context.applicationContext,
                null,
                WellKnownTileServer.MapLibre,
                RenderingEngine.Type.OPENGL,
            )
            val userAgent = "Frem/${BuildConfig.VERSION_NAME} (+https://github.com/0xjogge/frem)"
            val client =
                OkHttpClient.Builder()
                    .dispatcher(
                        Dispatcher().apply {
                            maxRequestsPerHost = 4
                            maxRequests = 8
                        },
                    )
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .callTimeout(30, TimeUnit.SECONDS)
                    .followSslRedirects(false)
                    .addInterceptor { chain ->
                        if (!networkAllowed) throw IOException("Map is not visible")
                        val request = chain.request()
                        if (!request.url.isHttps || request.url.host != "tile.openstreetmap.org") {
                            throw IOException("Unexpected map resource host")
                        }
                        chain.proceed(request.newBuilder().header("User-Agent", userAgent).build())
                    }
                    // Check redirects and the visibility gate at the actual network boundary too.
                    .addNetworkInterceptor { chain ->
                        val request = chain.request()
                        if (
                            !networkAllowed ||
                                !request.url.isHttps ||
                                request.url.host != "tile.openstreetmap.org"
                        ) {
                            throw IOException("Map request suspended")
                        }
                        chain.proceed(request.newBuilder().header("User-Agent", userAgent).build())
                    }
                    .build()
            httpClient = client
            HttpRequestUtil.setOkHttpClient(client)
            HttpRequestUtil.setPrintRequestUrlOnFailure(false)
            updateConnectivity()
            OfflineManager.getInstance(context.applicationContext)
                .setMaximumAmbientCacheSize(
                    MAX_BYTES,
                    object : OfflineManager.FileSourceCallback {
                        override fun onSuccess() {
                            ready.complete(Unit)
                        }

                        override fun onError(message: String) {
                            ready.completeExceptionally(
                                IOException("Could not configure map cache: $message"),
                            )
                        }
                    },
                )
        } catch (error: Exception) {
            ready.completeExceptionally(error)
        }
    }

    suspend fun awaitReady(context: Context) =
        withContext(Dispatchers.Main.immediate) {
            initialize(context)
            checkNotNull(initialization).await()
        }

    /** Used only by visible, resumed map views; never by the GPS recorder. */
    @MainThread
    internal fun startViewing(viewer: Any) {
        viewers.add(viewer)
        updateConnectivity()
    }

    @MainThread
    internal fun stopViewing(viewer: Any) {
        viewers.remove(viewer)
        updateConnectivity()
    }

    @MainThread
    private fun updateConnectivity() {
        networkAllowed = viewers.isNotEmpty() && !clearing
        // null restores the SDK's real connectivity checks; false suspends its requests/retries.
        MapLibre.setConnected(if (networkAllowed) null else false)
        if (!networkAllowed) httpClient?.dispatcher?.cancelAll()
    }

    /**
     * Approximate on-disk bytes, including SQLite overhead, without reading route data. Safe to
     * call from Settings before opening any map; initializes the SDK/cache without a MapView,
     * network access or location requests.
     */
    suspend fun sizeBytes(context: Context): Long {
        val app = context.applicationContext
        awaitReady(app)
        val directory =
            withContext(Dispatchers.Main.immediate) {
                // Use the SDK's resolved path, including a persisted custom path or its
                // manifest-selected external files directory, rather than assume filesDir.
                FileSource.getResourcesCachePath(app)
            }
        return withContext(Dispatchers.IO) { ambientCacheSizeBytes(File(directory)) }
    }

    suspend fun sizeDescription(context: Context): String {
        val bytes = sizeBytes(context)
        val size =
            when {
                bytes == 0L -> "Empty"
                bytes < 1024L * 1024L -> "${(bytes + 1023L) / 1024L} KiB"
                else -> String.format(Locale.getDefault(), "%.1f MiB", bytes / (1024.0 * 1024.0))
            }
        return "$size · 100 MiB tile-cache limit"
    }

    /**
     * Asynchronously clears only MapLibre's ambient tiles and packs its database. Recorded tracks
     * are stored elsewhere and are never touched. Throws IOException on failure. An already
     * displayed map can retain decoded tiles until its view is released.
     */
    suspend fun clear(context: Context) = clearMutex.withLock {
        awaitReady(context)
        // The native operation cannot be cancelled. Keep the gate and mutex until its
        // completion even if the settings screen's coroutine is cancelled in the meantime.
        withContext(Dispatchers.Main.immediate + NonCancellable) {
            clearing = true
            updateConnectivity()
            try {
                suspendCancellableCoroutine { continuation ->
                    OfflineManager.getInstance(context.applicationContext)
                        .clearAmbientCache(
                            object : OfflineManager.FileSourceCallback {
                                override fun onSuccess() {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }

                                override fun onError(message: String) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(IOException(message))
                                    }
                                }
                            },
                        )
                }
            } finally {
                clearing = false
                updateConnectivity()
            }
        }
    }
}

/**
 * MapLibre 13.7.1's file_source.hpp uses /mbgl-offline.db for the ambient cache too. SQLite may
 * also use WAL/SHM or a rollback journal. Inspect only those files in the SDK's resolved resource
 * directory; never count unrelated app databases or recursively walk recorded/exported tracks.
 */
internal fun ambientCacheSizeBytes(directory: File): Long =
    listOf(
            "mbgl-offline.db",
            "mbgl-offline.db-wal",
            "mbgl-offline.db-shm",
            "mbgl-offline.db-journal",
        )
        .sumOf { name ->
            val file = File(directory, name)
            if (file.isFile) file.length() else 0L
        }
