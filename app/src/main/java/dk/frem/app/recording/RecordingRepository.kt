package dk.frem.app.recording

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import dk.frem.app.data.FremDatabase
import java.time.Instant
import java.time.ZoneId
import java.util.PriorityQueue
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class CaptureRequest(
    val sessionId: String,
    val generation: Long,
    val type: ActivityType,
)

/**
 * Application-scoped local recording storage and command serialization, independent of health sync.
 */
class RecordingRepository(context: Context, private val database: FremDatabase) {
    private val context = context.applicationContext
    private val dao = database.recordingDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val mutableLive = MutableStateFlow(RecordingLiveState())
    private val mutableCapture = MutableStateFlow<CaptureRequest?>(null)
    private val mutableServiceSession = MutableStateFlow<ActivitySession?>(null)
    private var current: ActivitySession? = null
    private var timeline: RecordingTimeline? = null
    private var processor: TrackProcessor? = null
    private var source: RecordingLocationSource? = null
    private var stoppingCapture: CaptureRequest? = null
    private var generation = 0L
    private var host: String? = null
    private var lastCheckpointNanos = 0L
    private var sensorSource: RecordingSensorSource? = null
    private var stepProcessor: StepMetricsProcessor? = null
    private val metricDays = mutableMapOf<String, ActivityDayMetrics>()
    private var metricsDirty = false
    private var lastMetricCheckpointNanos = 0L
    private var sensorWallOrigin = 0L
    private var sensorElapsedOrigin = 0L
    private var pressureProcessor: PressureMetricsProcessor? = null
    private var pressureExpected = false
    private var lastPressureNanos: Long? = null
    private var lastMovingNanos: Long? = null
    private var lastGpsAscent = 0.0
    private var lastGpsDescent = 0.0
    private val pendingPressure =
        PriorityQueue<RecordingSensorEvent.Pressure>(compareBy { it.elapsedNanos })
    val nativeMetrics: Flow<List<ActivityDayMetrics>> = dao.metricDays()

    val sessions: Flow<List<ActivitySession>> = flow {
        awaitReady()
        emitAll(dao.sessions())
    }
    val active: Flow<ActivitySession?> = flow {
        awaitReady()
        emitAll(dao.active())
    }
    val live: StateFlow<RecordingLiveState> = mutableLive.asStateFlow()
    internal val capture: StateFlow<CaptureRequest?> = mutableCapture.asStateFlow()
    internal val serviceSession: StateFlow<ActivitySession?> = mutableServiceSession.asStateFlow()

    @Volatile private var sourceFactory: (() -> RecordingLocationSource)? = null
    @Volatile private var sensorSourceFactory: (() -> RecordingSensorSource)? = null

    suspend fun setSensorSourceFactoryForTesting(factory: (() -> RecordingSensorSource)?) {
        enqueue {
            check(mutableCapture.value == null) { "Pause recording before replacing sensors." }
            sensorSourceFactory = factory
        }
            .await()
    }

    internal fun newSensorSource(request: CaptureRequest): RecordingSensorSource =
        sensorSourceFactory?.invoke() ?: AndroidRecordingSensorSource(context, request.type)

    internal suspend fun registerSensorSource(
        request: CaptureRequest,
        candidate: RecordingSensorSource,
    ): Boolean = lock.withLock {
        if (mutableCapture.value != request || stoppingCapture == request) {
            candidate.close()
            false
        } else {
            sensorSource?.close()
            sensorSource = candidate
            true
        }
    }

    internal suspend fun acceptSensor(request: CaptureRequest, event: RecordingSensorEvent) {
        lock.withLock {
            if (mutableCapture.value != request) return
            when (event) {
                is RecordingSensorEvent.Step -> {
                    val delta =
                        stepProcessor?.accept(
                            event.elapsedNanos,
                            SystemClock.elapsedRealtimeNanos(),
                        ) ?: return
                    val day = metricDay(event.elapsedNanos)
                    metricDays[day.date] = day.copy(steps = (day.steps ?: 0) + 1)
                    if (delta.movingMillis > 0) {
                        val start = wallTime(delta.intervalStartNanos!!)
                        val end = wallTime(delta.elapsedNanos)
                        splitMetricInterval(start, end, ZoneId.of(current!!.zoneId)) {
                            date,
                            duration ->
                            val row =
                                metricDays[date] ?: ActivityDayMetrics(request.sessionId, date)
                            val fraction = duration.toDouble() / (end - start)
                            metricDays[date] =
                                row.copy(
                                    cadenceSteps = row.cadenceSteps + fraction,
                                    cadenceMillis = row.cadenceMillis + duration,
                                    moderateMillis =
                                        row.moderateMillis +
                                            (delta.moderateMillis * fraction).toLong(),
                                    vigorousMillis =
                                        row.vigorousMillis +
                                            (delta.vigorousMillis * fraction).toLong(),
                                )
                        }
                    }
                    metricsDirty = true
                    mutableLive.value =
                        mutableLive.value.copy(
                            cadence = stepProcessor?.cadence(SystemClock.elapsedRealtimeNanos()),
                        )
                }
                is RecordingSensorEvent.Barrier -> {
                    drainPressureLocked(Long.MAX_VALUE)
                    event.completion.complete(Unit)
                }
                is RecordingSensorEvent.Capabilities -> {
                    pressureExpected = event.pressure
                    mutableLive.value =
                        mutableLive.value.copy(
                            sensorMessage =
                                if (request.type.cycling || event.steps) null
                                else
                                    "Step cadence needs physical activity access and a hardware step detector.",
                        )
                }
                is RecordingSensorEvent.Pressure -> {
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (
                        event.elapsedNanos >= sensorElapsedOrigin &&
                            event.elapsedNanos <= now &&
                            now - event.elapsedNanos <= 30_000_000_000L
                    )
                        pendingPressure.add(event)
                }
            }
            // Independent hardware FIFOs may deliver a pressure batch before the matching steps.
            // Hold pressure just beyond the 5 s requested sensor latency, retaining original times.
            drainPressureLocked(SystemClock.elapsedRealtimeNanos() - 6_000_000_000L)
        }
    }

    private fun drainPressureLocked(through: Long) {
        while (pendingPressure.peek()?.let { it.elapsedNanos <= through } == true) {
            val event = pendingPressure.remove()
            val session = current ?: continue
            val walking = stepProcessor?.walkingAt(event.elapsedNanos) == true
            val gpsMoving =
                lastMovingNanos?.let {
                    kotlin.math.abs(event.elapsedNanos - it) <= 10_000_000_000L
                } == true
            if (lastPressureNanos?.let { event.elapsedNanos - it > 10_000_000_000L } == true)
                pressureProcessor = PressureMetricsProcessor(event.elapsedNanos)
            val delta =
                pressureProcessor?.accept(
                    event.elapsedNanos,
                    event.hectopascals,
                    SystemClock.elapsedRealtimeNanos(),
                    walking || gpsMoving,
                ) ?: continue
            pressureExpected = true
            lastPressureNanos = event.elapsedNanos
            recordElevation(
                event.elapsedNanos,
                delta.ascent,
                delta.descent,
                "Barometer",
                delta.ascent.takeIf { walking && !session.type.cycling },
            )
            current =
                session.copy(
                    ascentMeters = (session.ascentMeters ?: 0.0) + delta.ascent,
                    descentMeters = (session.descentMeters ?: 0.0) + delta.descent,
                    elevationSource = combinedElevationSource(session.elevationSource, "Barometer"),
                )
            mutableServiceSession.value = current
        }
    }

    internal suspend fun sensorUnavailable(request: CaptureRequest) = lock.withLock {
        if (mutableCapture.value == request) {
            drainPressureLocked(Long.MAX_VALUE)
            pressureExpected = false
            lastPressureNanos = null
            stepProcessor = StepMetricsProcessor(SystemClock.elapsedRealtimeNanos())
            mutableLive.value =
                mutableLive.value.copy(
                    cadence = null,
                    sensorMessage =
                        "Phone sensors stopped. Saved readings are retained; GPS recording continues.",
                )
        }
    }

    private fun recordElevation(
        elapsed: Long,
        ascent: Double,
        descent: Double,
        source: String,
        floorAscent: Double? = null,
    ) {
        val day = metricDay(elapsed)
        metricDays[day.date] =
            day.copy(
                ascentMeters = (day.ascentMeters ?: 0.0) + ascent,
                descentMeters = (day.descentMeters ?: 0.0) + descent,
                floorAscentMeters =
                    if (floorAscent != null) (day.floorAscentMeters ?: 0.0) + floorAscent
                    else day.floorAscentMeters,
                elevationSource = combinedElevationSource(day.elevationSource, source),
            )
        metricsDirty = true
    }

    private fun combinedElevationSource(previous: String?, source: String): String =
        if (previous == null || previous == source) source else "GPS + barometer"

    private fun preferPressure(now: Long): Boolean =
        pressureExpected && now - (lastPressureNanos ?: sensorElapsedOrigin) <= 10_000_000_000L

    private fun wallTime(elapsed: Long): Long =
        sensorWallOrigin + (elapsed - sensorElapsedOrigin) / 1_000_000

    private fun metricDay(elapsed: Long): ActivityDayMetrics {
        val session = current!!
        val date =
            Instant.ofEpochMilli(wallTime(elapsed))
                .atZone(ZoneId.of(session.zoneId))
                .toLocalDate()
                .toString()
        return metricDays[date] ?: ActivityDayMetrics(session.id, date)
    }

    private suspend fun saveMetricsLocked() {
        if (!metricsDirty) return
        dao.putMetricDays(metricDays.values.toList())
        metricsDirty = false
        lastMetricCheckpointNanos = SystemClock.elapsedRealtimeNanos()
    }

    private val ready = scope.async {
        lock.withLock {
            database.withTransaction {
                dao.recoverUnfinished()
                current = dao.activeSession()
            }
            current?.let {
                timeline = RecordingTimeline(it.durationMillis)
                publish(
                    it,
                    "Recording was interrupted. Your saved track is safe. Resume or finish it.",
                )
            }
        }
    }

    init {
        scope.launch {
            for (command in commands) command()
        }
    }

    /** Waits for process-start recovery; no location service starts during recovery. */
    suspend fun awaitReady() {
        ready.await()
    }

    fun points(id: String): Flow<List<TrackPoint>> = dao.points(id)

    suspend fun getSession(id: String): ActivitySession? {
        awaitReady()
        return dao.session(id)
    }

    suspend fun getPoints(id: String): List<TrackPoint> {
        awaitReady()
        return dao.getPoints(id)
    }

    suspend fun start(type: ActivityType): String = enqueue {
        RecordingLocationAccess.requireAvailable(context)
        check(current == null) { "Finish or discard the current activity before starting another." }
        val now = SystemClock.elapsedRealtimeNanos()
        val session =
            ActivitySession(
                id = UUID.randomUUID().toString(),
                title = type.label,
                type = type,
                start = System.currentTimeMillis(),
                zoneId = ZoneId.systemDefault().id,
            )
        database.withTransaction {
            check(dao.activeSession() == null) { "An activity is already in progress." }
            dao.insertSession(session)
        }
        current = session
        timeline = RecordingTimeline(runningSinceNanos = now)
        beginSegment(session, now)
        try {
            RecordingService.start(context, session.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            interruptLocked("Couldn’t start recording: ${e.message ?: "Please try again."}")
            throw e
        }
        session.id
    }
        .await()

    fun pause() {
        stopCommand(StopControl.PAUSE)
    }

    fun resume() {
        enqueue { resumeLocked() }
    }

    fun finish() {
        stopCommand(StopControl.FINISH)
    }

    fun discard() {
        enqueue { discardLocked() }
    }

    suspend fun pauseAndAwait() {
        stopCommand(StopControl.PAUSE).await()
    }

    suspend fun resumeAndAwait() {
        enqueue { resumeLocked() }.await()
    }

    /**
     * Completes only after the final point/checkpoint transaction and FINISHED state are durable.
     */
    suspend fun finishAndAwait(): ActivitySession? = stopCommand(StopControl.FINISH).await()

    suspend fun discardAndAwait() {
        enqueue { discardLocked() }.await()
    }

    /** A barrier for fire-and-forget controls, useful to UI and instrumentation. */
    suspend fun awaitIdle() {
        enqueue {}.await()
    }

    suspend fun update(id: String, title: String, type: ActivityType) {
        enqueue {
            val cleaned = title.trim()
            require(cleaned.isNotEmpty() && cleaned.length <= 200) {
                "Enter a title of 1–200 characters."
            }
            val session = dao.session(id) ?: return@enqueue
            val updated = session.copy(title = cleaned, type = type)
            dao.updateSession(updated)
            if (current?.id == id) {
                current = updated
                processor?.type = type
                mutableCapture.value
                    ?.takeIf { it.type != type }
                    ?.let {
                        mutableCapture.value = it.copy(generation = ++generation, type = type)
                        source?.close()
                        source = null
                    }
                publish(updated)
            }
        }
            .await()
    }

    suspend fun delete(id: String) {
        enqueue {
            if (current?.id == id) discardLocked() else dao.deleteSession(id)
        }
            .await()
    }

    /**
     * In-process instrumentation seam; there are no mock-location intents or exported components.
     * Set this before start/resume. Each start/resume receives a fresh single-use source. Platform
     * location permissions and foreground-service restrictions still apply.
     */
    suspend fun setLocationSourceFactoryForTesting(factory: () -> RecordingLocationSource) {
        enqueue {
            check(mutableCapture.value == null) {
                "Pause recording before replacing the location source."
            }
            sourceFactory = factory
        }
            .await()
    }

    suspend fun resetLocationSourceFactoryForTesting() {
        enqueue {
            check(mutableCapture.value == null) {
                "Pause recording before replacing the location source."
            }
            sourceFactory = null
        }
            .await()
    }

    internal fun newLocationSource(request: CaptureRequest): RecordingLocationSource =
        sourceFactory?.invoke() ?: GmsRecordingLocationSource(context, request.type)

    internal suspend fun registerSource(
        request: CaptureRequest,
        candidate: RecordingLocationSource,
    ): Boolean {
        awaitReady()
        return lock.withLock {
            if (mutableCapture.value != request || stoppingCapture == request) {
                candidate.close()
                false
            } else {
                source?.close()
                source = candidate
                true
            }
        }
    }

    internal suspend fun accept(request: CaptureRequest, fix: RecordingFix) {
        awaitReady()
        lock.withLock {
            if (mutableCapture.value != request) return
            // Service destruction must not cancel between committing a point and publishing its
            // matching aggregate. This is only a brief local database transaction.
            withContext(NonCancellable) {
                val update =
                    processor?.accept(fix, SystemClock.elapsedRealtimeNanos()) ?: return@withContext
                val previous = current ?: return@withContext
                val gpsAscent =
                    ((update.ascentMeters ?: lastGpsAscent) - lastGpsAscent).coerceAtLeast(0.0)
                val gpsDescent =
                    ((update.descentMeters ?: lastGpsDescent) - lastGpsDescent).coerceAtLeast(0.0)
                lastGpsAscent = update.ascentMeters ?: lastGpsAscent
                lastGpsDescent = update.descentMeters ?: lastGpsDescent
                if (update.distanceMeters > previous.distanceMeters)
                    lastMovingNanos = fix.elapsedRealtimeNanos
                val gpsElevation =
                    !preferPressure(fix.elapsedRealtimeNanos) && update.ascentMeters != null
                if (gpsElevation)
                    recordElevation(fix.elapsedRealtimeNanos, gpsAscent, gpsDescent, "GPS")
                val snapshot =
                    snapshot(previous)
                        .copy(
                            distanceMeters = update.distanceMeters,
                            ascentMeters =
                                if (gpsElevation) (previous.ascentMeters ?: 0.0) + gpsAscent
                                else previous.ascentMeters,
                            descentMeters =
                                if (gpsElevation) (previous.descentMeters ?: 0.0) + gpsDescent
                                else previous.descentMeters,
                            elevationSource =
                                if (gpsElevation)
                                    combinedElevationSource(previous.elevationSource, "GPS")
                                else previous.elevationSource,
                        )
                database.withTransaction {
                    update.point?.let { dao.insertPoint(it) }
                    dao.updateSession(snapshot)
                }
                current = snapshot
                processorLastFixNanos = fix.elapsedRealtimeNanos
                lastCheckpointNanos = SystemClock.elapsedRealtimeNanos()
                mutableLive.value =
                    mutableLive.value.copy(
                        sessionId = snapshot.id,
                        accuracyMeters = fix.accuracyMeters,
                        speedMetersPerSecond = update.speedMetersPerSecond,
                        lastFixAt = fix.recordedAt,
                        durationMillis = snapshot.durationMillis,
                    )
                mutableServiceSession.value = snapshot
            }
        }
    }

    internal suspend fun tick() {
        awaitReady()
        lock.withLock {
            val session = current ?: return
            if (session.state != RecordingState.RECORDING) return
            val now = SystemClock.elapsedRealtimeNanos()
            drainPressureLocked(now - 6_000_000_000L)
            if (now - lastMetricCheckpointNanos >= CHECKPOINT_INTERVAL_NANOS) saveMetricsLocked()
            val duration = timeline?.durationMillis(now) ?: session.durationMillis
            val stale =
                mutableLive.value.lastFixAt == null ||
                    processorLastFixNanos?.let { now - it > TrackProcessor.SIGNAL_GAP_NANOS } ==
                        true
            mutableLive.value =
                mutableLive.value.copy(
                    cadence = stepProcessor?.cadence(now),
                    durationMillis = duration,
                    speedMetersPerSecond =
                        if (stale) null else mutableLive.value.speedMetersPerSecond,
                    message =
                        if (stale) "Waiting for an accurate GPS signal…"
                        else mutableLive.value.message,
                )
            if (now - lastCheckpointNanos >= CHECKPOINT_INTERVAL_NANOS) checkpointLocked()
        }
    }

    private var processorLastFixNanos: Long? = null

    internal suspend fun attachHost(id: String, sessionId: String): Boolean {
        awaitReady()
        return lock.withLock {
            if (
                current?.id != sessionId ||
                    current?.state !in setOf(RecordingState.RECORDING, RecordingState.PAUSED)
            )
                false
            else {
                host = id
                true
            }
        }
    }

    internal fun hostDestroyed(id: String) {
        enqueue {
            if (host == id) {
                host = null
                if (
                    current?.state == RecordingState.RECORDING ||
                        current?.state == RecordingState.PAUSED
                ) {
                    interruptLocked("Recording was interrupted. Your saved track is safe.")
                }
            }
        }
    }

    internal fun interrupt(
        sessionId: String,
        request: CaptureRequest?,
        message: String,
    ): Deferred<Unit> =
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            awaitReady()
            // Source failure can preempt a command's unlocked flush. Waiting behind that command
            // would
            // deadlock a collector which must either acknowledge its barrier or close the source.
            lock.withLock {
                if (current?.id != sessionId) return@withLock
                // A delayed error from a paused/stopped host must not interrupt a newer resume.
                if (
                    request != null && mutableCapture.value == request ||
                        request == null && current?.state != RecordingState.RECORDING
                )
                    interruptLocked(message)
            }
        }

    internal suspend fun notificationCommand(action: String, sessionId: String) {
        when (action) {
            RecordingService.ACTION_PAUSE -> stopCommand(StopControl.PAUSE, sessionId).await()
            RecordingService.ACTION_FINISH -> stopCommand(StopControl.FINISH, sessionId).await()
            RecordingService.ACTION_RESUME -> enqueue {
                    if (current?.id == sessionId) resumeLocked()
                }
                    .await()
        }
    }

    private enum class StopControl {
        PAUSE,
        FINISH,
    }

    private data class StopTarget(
        val sessionId: String,
        val request: CaptureRequest?,
        val source: RecordingLocationSource?,
        val sensor: RecordingSensorSource?,
    )

    private fun stopCommand(
        control: StopControl,
        expectedSessionId: String? = null,
    ): Deferred<ActivitySession?> =
        enqueue(locked = false) {
            val target =
                lock.withLock {
                    val session = current ?: return@withLock null
                    if (expectedSessionId != null && session.id != expectedSessionId)
                        return@withLock null
                    if (control == StopControl.PAUSE && session.state != RecordingState.RECORDING)
                        return@withLock null
                    StopTarget(session.id, mutableCapture.value, source, sensorSource).also {
                        stoppingCapture = it.request
                    }
                } ?: return@enqueue null
            try {
                // Still inside the single command actor, but deliberately outside the repository
                // lock:
                // the service must acquire that lock to commit every Fix preceding the flush
                // barrier.
                flushRecordingSource(target.source)
                target.sensor?.let { withTimeoutOrNull(2_000) { it.flush() } }
                lock.withLock {
                    if (current?.id != target.sessionId) return@withLock null
                    if (current?.state == RecordingState.INTERRUPTED) {
                        // Keep a concurrent source failure interrupted on Pause; Finish may save
                        // it.
                        return@withLock if (control == StopControl.FINISH) finishLocked() else null
                    }
                    if (mutableCapture.value != target.request || source !== target.source)
                        return@withLock null
                    when (control) {
                        StopControl.PAUSE -> {
                            pauseLocked()
                            null
                        }
                        StopControl.FINISH -> finishLocked()
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    lock.withLock {
                        if (stoppingCapture == target.request) stoppingCapture = null
                    }
                }
            }
        }

    private suspend fun pauseLocked() {
        drainPressureLocked(Long.MAX_VALUE)
        val session = current ?: return
        if (session.state != RecordingState.RECORDING) return
        try {
            RecordingLocationAccess.requireAvailable(context)
        } catch (e: SecurityException) {
            interruptLocked(e.message ?: "Location permission was lost.")
            return
        } catch (e: IllegalStateException) {
            interruptLocked(e.message ?: "Location was turned off.")
            return
        }
        stopCapture()
        timeline?.pause(SystemClock.elapsedRealtimeNanos())
        saveState(session, RecordingState.PAUSED)
    }

    private suspend fun resumeLocked() {
        val session = current ?: return
        if (session.state == RecordingState.RECORDING) return
        try {
            RecordingLocationAccess.requireAvailable(context)
        } catch (e: SecurityException) {
            interruptLocked(e.message ?: "Location permission was lost.")
            throw e
        } catch (e: IllegalStateException) {
            interruptLocked(e.message ?: "Location was turned off.")
            throw e
        }
        val now = SystemClock.elapsedRealtimeNanos()
        timeline = RecordingTimeline(session.durationMillis, now)
        val resumed = snapshot(session).copy(state = RecordingState.RECORDING)
        dao.updateSession(resumed)
        current = resumed
        beginSegment(resumed, now)
        try {
            RecordingService.start(context, session.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            interruptLocked("Couldn’t resume recording: ${e.message ?: "Please try again."}")
            throw e
        }
    }

    private suspend fun beginSegment(session: ActivitySession, now: Long) {
        // A delayed onDestroy from the previous service/segment must not interrupt this start.
        host = null
        sensorWallOrigin = System.currentTimeMillis()
        sensorElapsedOrigin = now
        stepProcessor = StepMetricsProcessor(now)
        pendingPressure.clear()
        pressureProcessor = PressureMetricsProcessor(now)
        pressureExpected = false
        lastPressureNanos = null
        lastMovingNanos = null
        lastGpsAscent = session.ascentMeters ?: 0.0
        lastGpsDescent = session.descentMeters ?: 0.0
        metricDays.clear()
        metricDays.putAll(dao.getMetricDays(session.id).associateBy { it.date })
        metricsDirty = false
        lastMetricCheckpointNanos = now
        val last = dao.lastPoint(session.id)
        processor =
            TrackProcessor(
                sessionId = session.id,
                type = session.type,
                startedAtNanos = now,
                sequence = last?.sequence?.plus(1) ?: 0,
                segment = last?.segment?.plus(1) ?: 0,
                initialDistanceMeters = session.distanceMeters,
                initialAscentMeters = session.ascentMeters,
                initialDescentMeters = session.descentMeters,
            )
        lastCheckpointNanos = now
        processorLastFixNanos = null
        mutableLive.value =
            RecordingLiveState(
                sessionId = session.id,
                durationMillis = session.durationMillis,
                message = "Waiting for an accurate GPS signal…",
            )
        mutableServiceSession.value = session
        val request = CaptureRequest(session.id, ++generation, session.type)
        mutableCapture.value = request
        scope.launch {
            delay(15_000)
            enqueue {
                if (mutableCapture.value == request && (source == null || host == null)) {
                    interruptLocked(
                        "The recording service could not start. Your activity is saved; try resuming.",
                    )
                }
            }
        }
    }

    private suspend fun finishLocked(): ActivitySession? {
        drainPressureLocked(Long.MAX_VALUE)
        val session = current ?: return null
        stopCapture()
        saveMetricsLocked()
        timeline?.pause(SystemClock.elapsedRealtimeNanos())
        val finished =
            snapshot(session)
                .copy(
                    state = RecordingState.FINISHED,
                    end = System.currentTimeMillis().coerceAtLeast(session.start),
                    activeSlot = null,
                )
        dao.updateSession(finished)
        clearCurrent()
        return finished
    }

    private suspend fun discardLocked() {
        val session = current ?: return
        stopCapture()
        dao.deleteSession(session.id)
        clearCurrent()
    }

    private fun clearCurrent() {
        current = null
        host = null
        timeline = null
        processor = null
        stepProcessor = null
        metricDays.clear()
        metricsDirty = false
        mutableServiceSession.value = null
        mutableLive.value = RecordingLiveState()
    }

    private suspend fun interruptLocked(message: String) {
        drainPressureLocked(Long.MAX_VALUE)
        val session = current ?: return
        host = null
        stopCapture()
        timeline?.pause(SystemClock.elapsedRealtimeNanos())
        saveState(session, RecordingState.INTERRUPTED, message)
    }

    private fun stopCapture() {
        mutableCapture.value = null
        stoppingCapture = null
        source?.close()
        source = null
        sensorSource?.close()
        sensorSource = null
    }

    private suspend fun saveState(
        session: ActivitySession,
        state: RecordingState,
        message: String? = null,
    ) {
        saveMetricsLocked()
        val saved = snapshot(session).copy(state = state)
        dao.updateSession(saved)
        current = saved
        publish(saved, message)
        mutableLive.value = mutableLive.value.copy(speedMetersPerSecond = null, cadence = null)
    }

    private suspend fun checkpointLocked() {
        val saved = current?.let { snapshot(it) } ?: return
        dao.updateSession(saved)
        current = saved
        lastCheckpointNanos = SystemClock.elapsedRealtimeNanos()
        mutableServiceSession.value = saved
    }

    private fun snapshot(session: ActivitySession): ActivitySession =
        session.copy(
            durationMillis =
                timeline?.durationMillis(SystemClock.elapsedRealtimeNanos())
                    ?: session.durationMillis,
            lastCheckpoint = System.currentTimeMillis(),
        )

    private fun publish(session: ActivitySession, message: String? = null) {
        mutableServiceSession.value = session
        mutableLive.value =
            mutableLive.value.copy(
                sessionId = session.id,
                durationMillis = session.durationMillis,
                message = message,
            )
    }

    private fun <T> enqueue(locked: Boolean = true, action: suspend () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        val sent = commands.trySend {
            try {
                awaitReady()
                result.complete(if (locked) lock.withLock { action() } else action())
            } catch (e: CancellationException) {
                result.cancel(e)
                throw e
            } catch (e: Exception) {
                mutableLive.value =
                    mutableLive.value.copy(message = e.message ?: "Recording could not be saved.")
                result.completeExceptionally(e)
            }
        }
        if (sent.isFailure)
            result.completeExceptionally(IllegalStateException("Recording is unavailable."))
        return result
    }

    companion object {
        private const val CHECKPOINT_INTERVAL_NANOS = 5_000_000_000L
    }
}
