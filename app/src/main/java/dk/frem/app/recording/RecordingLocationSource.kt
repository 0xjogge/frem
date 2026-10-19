package dk.frem.app.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface RecordingLocationEvent {
    data class Fix(val fix: RecordingFix) : RecordingLocationEvent

    data class Availability(val available: Boolean) : RecordingLocationEvent

    /** Complete only after all preceding Fix events have finished processing/persistence. */
    data class Barrier(val completion: CompletableDeferred<Unit>) : RecordingLocationEvent
}

/** A single-use subscription. close() must be thread-safe, idempotent, and release the provider. */
interface RecordingLocationSource {
    fun events(): Flow<RecordingLocationEvent>

    /**
     * Best-effort control-stop flush. Implementations with a buffered tail should emit their fixes,
     * enqueue Barrier, and await its completion. close() must promptly unblock an in-flight flush.
     * The repository calls this outside its mutex and bounds the entire operation to three seconds.
     * Immediate fake sources can retain this no-op default; buffered fakes can use the same
     * barrier.
     */
    suspend fun flush() {}

    fun close()
}

/**
 * Provider failures/timeouts must not prevent a stop; actual caller cancellation still propagates.
 */
internal suspend fun flushRecordingSource(
    source: RecordingLocationSource?,
    timeoutMillis: Long = 3_000,
): Boolean {
    if (source == null) return true
    return try {
        withTimeoutOrNull(timeoutMillis) {
            source.flush()
            true
        } ?: false
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        false
    } catch (_: Exception) {
        false
    }
}

internal object RecordingLocationAccess {
    fun requireAvailable(context: Context) {
        if (
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        )
            throw SecurityException("Allow precise location to record an activity.")
        check(context.getSystemService(LocationManager::class.java).isLocationEnabled) {
            "Turn on location to record an activity."
        }
    }
}

class GmsRecordingLocationSource(
    context: Context,
    private val type: ActivityType = ActivityType.RUNNING,
) : RecordingLocationSource {
    private val context = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(this.context)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private var callback: LocationCallback? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var stream: SendChannel<RecordingLocationEvent>? = null
    private var flushHandler: ((CompletableDeferred<Unit>) -> Unit)? = null
    private var stopOrdering: (() -> Unit)? = null
    private val pendingBarriers = mutableSetOf<CompletableDeferred<Unit>>()
    private val closeSignal = CompletableDeferred<Unit>()
    private val flushLock = Mutex()

    @SuppressLint("MissingPermission")
    override fun events(): Flow<RecordingLocationEvent> = callbackFlow {
        check(started.compareAndSet(false, true)) {
            "A location source can only be collected once."
        }
        val open =
            synchronized(lifecycleLock) {
                if (closed.get()) false
                else {
                    stream = channel
                    true
                }
            }
        if (!open) {
            channel.close()
            return@callbackFlow
        }
        val configurations = Channel<Boolean>(Channel.CONFLATED)
        val ordering =
            object {
                private val lock = Any()
                private val buffer = ReorderBuffer()
                private var timer: Job? = null
                private var timerAtNanos: Long? = null
                private var timerGeneration = 0L

                fun update(action: (ReorderBuffer, Long) -> List<RecordingFix>) {
                    synchronized(lock) {
                        if (closed.get()) return
                        val now = SystemClock.elapsedRealtimeNanos()
                        try {
                            for (fix in action(buffer, now)) {
                                if (!trySend(RecordingLocationEvent.Fix(fix)).isSuccess) {
                                    throw ReorderBuffer.OrderingException(
                                        "Location delivery fell behind. Resume to continue.",
                                    )
                                }
                            }
                            scheduleDrain()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Do not silently bridge a lost/late portion of the route. The service
                            // persists this source failure as an interruption, retaining saved
                            // data.
                            channel.close(e)
                            this@GmsRecordingLocationSource.close()
                        }
                    }
                }

                private fun scheduleDrain() {
                    val deadline = buffer.nextDrainAtNanos()
                    if (deadline == timerAtNanos) return
                    timer?.cancel()
                    timerAtNanos = deadline
                    val generation = ++timerGeneration
                    timer = deadline?.let {
                        this@callbackFlow.launch(start = CoroutineStart.LAZY) {
                            val remaining =
                                (it - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(0)
                            delay(remaining / 1_000_000 + if (remaining % 1_000_000 == 0L) 0 else 1)
                            synchronized(lock) {
                                if (closed.get() || generation != timerGeneration) return@launch
                                timerAtNanos = null
                                timer = null
                                update { buffer, now -> buffer.drain(now) }
                            }
                        }
                    }
                    // Publish the handle before starting: an already-due timer may run inline on
                    // Main.immediate and schedule its successor without the old job overwriting it.
                    timer?.start()
                }

                fun stop() {
                    synchronized(lock) {
                        ++timerGeneration
                        timer?.cancel()
                        timer = null
                        timerAtNanos = null
                        buffer.clear()
                    }
                }

                fun flush(completion: CompletableDeferred<Unit>) {
                    synchronized(lock) {
                        if (closed.get()) {
                            completion.cancel()
                            return
                        }
                        update { buffer, now -> buffer.flush(now) }
                        if (closed.get()) {
                            completion.cancel()
                        } else if (!trySend(RecordingLocationEvent.Barrier(completion)).isSuccess) {
                            channel.close(
                                ReorderBuffer.OrderingException(
                                    "Location flush delivery fell behind.",
                                ),
                            )
                            this@GmsRecordingLocationSource.close()
                        }
                    }
                }
            }
        val listener =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    if (closed.get()) return
                    val fixes =
                        result.locations.map { location ->
                            RecordingFix(
                                recordedAt = location.time,
                                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                                latitude = location.latitude,
                                longitude = location.longitude,
                                accuracyMeters =
                                    if (location.hasAccuracy()) location.accuracy else Float.NaN,
                                altitudeMeters =
                                    if (location.hasAltitude()) location.altitude else null,
                                verticalAccuracyMeters =
                                    if (location.hasVerticalAccuracy())
                                        location.verticalAccuracyMeters
                                    else null,
                                speedMetersPerSecond =
                                    if (location.hasSpeed()) location.speed else null,
                                speedAccuracyMetersPerSecond =
                                    if (location.hasSpeedAccuracy())
                                        location.speedAccuracyMetersPerSecond
                                    else null,
                            )
                        }
                    // Sorting one LocationResult is insufficient: batching explicitly permits
                    // callbacks themselves to arrive out of sample-time order.
                    ordering.update { buffer, now -> buffer.offer(fixes, now) }
                }

                override fun onLocationAvailability(availability: LocationAvailability) {
                    if (!closed.get()) {
                        trySend(
                            RecordingLocationEvent.Availability(availability.isLocationAvailable),
                        )
                    }
                }
            }
        val power = context.getSystemService(PowerManager::class.java)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    // Read current state rather than trusting an already superseded screen event.
                    if (!closed.get()) configurations.trySend(power.isInteractive)
                }
            }
        try {
            RecordingLocationAccess.requireAvailable(context)
            synchronized(lifecycleLock) {
                if (closed.get()) {
                    channel.close()
                    return@callbackFlow
                }
                callback = listener
                flushHandler = ordering::flush
                stopOrdering = ordering::stop
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                screenReceiver = receiver
                configurations.trySend(power.isInteractive)
            }
            val configuring = launch {
                var previous: Boolean? = null
                for (interactive in configurations) {
                    if (closed.get()) break
                    if (previous == interactive) continue
                    RecordingLocationAccess.requireAvailable(context)
                    val policy = RecordingSamplingPolicy.forActivity(type, interactive)
                    ordering.update { buffer, now ->
                        buffer.beforeRequest(policy.maxDelayMillis, now)
                    }
                    if (closed.get()) return@launch
                    if (interactive && previous == false) {
                        // Flush first, but do not assume task completion orders callback delivery.
                        // The buffer quarantines the retired request's window after replacement.
                        client.flushLocations().await()
                    }
                    val request =
                        LocationRequest.Builder(
                                Priority.PRIORITY_HIGH_ACCURACY,
                                policy.intervalMillis,
                            )
                            .setMinUpdateIntervalMillis(policy.intervalMillis)
                            .setMinUpdateDistanceMeters(0f)
                            .setMaxUpdateDelayMillis(policy.maxDelayMillis)
                            .setMaxUpdateAgeMillis(0L)
                            .setWaitForAccurateLocation(true)
                            .build()
                    val registration =
                        synchronized(lifecycleLock) {
                            if (closed.get()) return@launch
                            // GMS replaces the request for this same callback, rather than adding a
                            // subscription. Await each replacement and conflate rapid screen
                            // toggles.
                            client
                                .requestLocationUpdates(request, listener, Looper.getMainLooper())
                                .also { task ->
                                    task.addOnSuccessListener(DIRECT_EXECUTOR) {
                                        // A cancelled await does not cancel the GMS registration
                                        // task.
                                        if (closed.get()) client.removeLocationUpdates(listener)
                                    }
                                }
                        }
                    registration.await()
                    ordering.update { buffer, now -> buffer.requestApplied(now) }
                    previous = interactive
                }
            }
            awaitClose {
                configuring.cancel()
                ordering.stop()
                close()
            }
        } finally {
            configurations.close()
            ordering.stop()
            close()
        }
    }

    override suspend fun flush() {
        flushLock.withLock {
            if (closed.get()) return
            val completion = CompletableDeferred<Unit>()
            val handler =
                synchronized(lifecycleLock) {
                    if (closed.get()) null
                    else flushHandler?.also { pendingBarriers.add(completion) }
                } ?: return
            try {
                coroutineScope {
                    val delivery = async {
                        try {
                            // Leave most of the outer stop deadline for draining known points and
                            // their Room commits, even if Google Play services does not respond.
                            withTimeoutOrNull(750) { client.flushLocations().await() }
                        } catch (e: CancellationException) {
                            currentCoroutineContext().ensureActive()
                        } catch (_: Exception) {
                            // A failed provider flush must not discard the already-delivered tail.
                        }
                        // Deliberately dispatch a main-loop turn, even for a main-thread caller.
                        // Earlier queued LocationCallbacks run before the sorted drain/barrier.
                        // GMS offers no guarantee about callbacks which have not yet been queued.
                        withContext(Dispatchers.Main) { handler(completion) }
                        completion.await()
                    }
                    select<Unit> {
                        closeSignal.onAwait { delivery.cancel() }
                        delivery.onAwait {}
                    }
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (!closed.get()) throw e
            } finally {
                synchronized(lifecycleLock) { pendingBarriers.remove(completion) }
                completion.cancel()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val resources =
            synchronized(lifecycleLock) {
                val resources =
                    Resources(
                        callback,
                        screenReceiver,
                        stream,
                        stopOrdering,
                        pendingBarriers.toList(),
                    )
                flushHandler = null
                stopOrdering = null
                pendingBarriers.clear()
                callback = null
                screenReceiver = null
                stream = null
                resources
            }
        // Complete/cancel outside the lifecycle monitor: coroutine continuations may run inline.
        closeSignal.complete(Unit)
        resources.barriers.forEach { it.cancel() }
        try {
            resources.stopOrdering?.invoke()
        } finally {
            try {
                resources.receiver?.let { context.unregisterReceiver(it) }
            } finally {
                try {
                    resources.callback?.let { client.removeLocationUpdates(it) }
                } finally {
                    resources.stream?.close()
                }
            }
        }
    }

    private data class Resources(
        val callback: LocationCallback?,
        val receiver: BroadcastReceiver?,
        val stream: SendChannel<RecordingLocationEvent>?,
        val stopOrdering: (() -> Unit)?,
        val barriers: List<CompletableDeferred<Unit>>,
    )

    companion object {
        private val DIRECT_EXECUTOR = Executor { it.run() }
    }
}
