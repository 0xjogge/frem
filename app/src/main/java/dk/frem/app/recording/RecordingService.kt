package dk.frem.app.recording

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dk.frem.app.FremApplication
import dk.frem.app.data.PreferencesStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Android lifetime owner; recording requires no Activity, Compose, or app-lifetime wake lock.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hostId = UUID.randomUUID().toString()
    private val incoming = Channel<Pair<Intent, Int>>(Channel.UNLIMITED)
    private lateinit var repository: RecordingRepository
    private var observing = false
    private var foreground = false
    private var latestStartId = 0
    private var imperial = false
    private val notificationGate = RecordingNotificationGate()
    private var lastNotificationContent: NotificationContent? = null

    override fun onCreate() {
        super.onCreate()
        repository = (application as FremApplication).recording
        scope.launch {
            try {
                PreferencesStore(this@RecordingService)
                    .flow
                    .map { it.imperial }
                    .distinctUntilChanged()
                    .collect {
                        imperial = it
                        if (foreground) updateNotification()
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the most recently read units if preferences are temporarily unavailable.
            }
        }
        scope.launch {
            for ((intent, startId) in incoming) {
                try {
                    handle(intent, startId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val id = intent.getStringExtra(EXTRA_SESSION)
                    if (repository.serviceSession.value?.id == id) {
                        interruptSafely(
                            repository.capture.value,
                            e.message ?: "Location recording stopped.",
                            id,
                        )
                    }
                    stopIfIdle(startId)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (
            intent == null ||
                intent.action !in ACTIONS ||
                intent.getStringExtra(EXTRA_SESSION) == null
        ) {
            stopIfIdle(startId)
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_START || intent.action == ACTION_RESUME) {
            // Promotion is synchronous: never wait for Room or the location provider's first fix.
            try {
                RecordingLocationAccess.requireAvailable(this)
                if (!foreground) {
                    val session = repository.serviceSession.value
                    ServiceCompat.startForeground(
                        this,
                        RecordingNotifications.ID,
                        RecordingNotifications.create(
                            this,
                            session,
                            repository.live.value,
                            imperial,
                        ),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                    )
                    foreground = true
                    lastNotificationContent = session?.let { notificationContent(it) }
                    notificationGate.posted(
                        session?.id,
                        session?.state,
                        SystemClock.elapsedRealtime(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val id = intent.getStringExtra(EXTRA_SESSION)!!
                repository.interrupt(
                    id,
                    repository.capture.value?.takeIf { it.sessionId == id },
                    e.message ?: "Location permission is required.",
                )
                // Stop promptly if Android forbids promotion; the application-scoped command
                // persists interruption even after this service's coroutine scope is cancelled.
                if (!foreground || repository.serviceSession.value?.id == id) {
                    stopNow(startId)
                }
                return START_NOT_STICKY
            }
        }
        incoming.trySend(intent to startId)
        // Process recreation recovers durable data as INTERRUPTED, never silently resumes GPS.
        return START_NOT_STICKY
    }

    private suspend fun handle(intent: Intent, startId: Int) {
        repository.awaitReady()
        val id = intent.getStringExtra(EXTRA_SESSION) ?: return
        if (repository.serviceSession.value?.id != id) {
            stopIfIdle(startId)
            return
        }
        if (intent.action != ACTION_START) repository.notificationCommand(intent.action!!, id)
        val session = repository.serviceSession.value
        if (session == null || session.state == RecordingState.INTERRUPTED) {
            stopIfIdle(startId)
            return
        }
        if (repository.attachHost(hostId, id) && foreground) observe()
    }

    private fun observe() {
        if (observing) return
        observing = true
        scope.launch {
            repository.capture.collectLatest { request ->
                if (request == null) return@collectLatest
                var source: RecordingLocationSource? = null
                try {
                    RecordingLocationAccess.requireAvailable(this@RecordingService)
                    val candidate = repository.newLocationSource(request)
                    source = candidate
                    if (!repository.registerSource(request, candidate)) return@collectLatest
                    candidate.events().collect { event ->
                        when (event) {
                            is RecordingLocationEvent.Fix -> repository.accept(request, event.fix)
                            is RecordingLocationEvent.Barrier -> event.completion.complete(Unit)
                            is RecordingLocationEvent.Availability -> {
                                if (!event.available)
                                    RecordingLocationAccess.requireAvailable(this@RecordingService)
                            }
                        }
                    }
                    interruptSafely(
                        request,
                        "The location source stopped. Resume to continue recording.",
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    interruptSafely(
                        request,
                        e.message ?: "Location recording stopped. Your saved track is safe.",
                    )
                } finally {
                    source?.close()
                }
            }
        }
        scope.launch {
            repository.serviceSession
                .map { it?.let { session -> session.id to session.state } }
                .distinctUntilChanged()
                .collect { key ->
                    if (key == null || key.second == RecordingState.INTERRUPTED)
                        stopIfIdle(latestStartId)
                    else if (foreground) updateNotification()
                }
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                val request = repository.capture.value
                val session = repository.serviceSession.value
                try {
                    val state = session?.state
                    if (state == RecordingState.RECORDING || state == RecordingState.PAUSED) {
                        RecordingLocationAccess.requireAvailable(this@RecordingService)
                    }
                    repository.tick()
                    if (foreground) updateNotification()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    interruptSafely(
                        request,
                        e.message ?: "Recording stopped. Your saved track is safe.",
                        session?.id,
                    )
                }
            }
        }
    }

    private fun updateNotification() {
        val session = repository.serviceSession.value ?: return
        if (session.state == RecordingState.INTERRUPTED || session.state == RecordingState.FINISHED)
            return
        val now = SystemClock.elapsedRealtime()
        if (!notificationGate.due(session.id, session.state, now)) return
        val content = notificationContent(session)
        // A running chronometer needs no application update; paused notifications are also
        // unchanged unless their metadata or units change.
        if (content == lastNotificationContent) return
        // Android allows a location FGS when notification permission is denied.
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        try {
            getSystemService(NotificationManager::class.java)
                .notify(
                    RecordingNotifications.ID,
                    RecordingNotifications.create(
                        this,
                        session,
                        repository.live.value,
                        imperial,
                    ),
                )
            notificationGate.posted(session.id, session.state, now)
            lastNotificationContent = content
        } catch (_: SecurityException) {
            // Notification permission can change after the check above; GPS may continue.
        }
    }

    private fun notificationContent(session: ActivitySession) =
        NotificationContent(
            session.id,
            session.state,
            session.title,
            session.distanceMeters,
            imperial,
            if (session.state == RecordingState.RECORDING) null else session.durationMillis,
        )

    private data class NotificationContent(
        val sessionId: String,
        val state: RecordingState,
        val title: String,
        val distanceMeters: Double,
        val imperial: Boolean,
        val stoppedDurationMillis: Long?,
    )

    private suspend fun interruptSafely(
        request: CaptureRequest?,
        message: String,
        sessionId: String? = request?.sessionId,
    ) {
        if (sessionId == null) return
        try {
            repository.interrupt(sessionId, request, message).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // If storage itself fails, keep the last committed checkpoint for startup recovery.
            // The repository closes the source before attempting the interruption write.
            stopAfterStorageFailure()
        }
    }

    private fun stopAfterStorageFailure() {
        stopNow(latestStartId)
    }

    private fun stopNow(startId: Int) {
        if (startId != latestStartId) return
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelfResult(startId)
    }

    private fun stopIfIdle(startId: Int) {
        val state = repository.serviceSession.value?.state
        if (state == RecordingState.RECORDING || state == RecordingState.PAUSED) return
        // Do not remove the foreground notification belonging to a newer start command.
        if (startId != latestStartId) return
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelfResult(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        incoming.close()
        repository.hostDestroyed(hostId)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val ACTION_START = "dk.frem.app.recording.START"
        internal const val ACTION_PAUSE = "dk.frem.app.recording.PAUSE"
        internal const val ACTION_RESUME = "dk.frem.app.recording.RESUME"
        internal const val ACTION_FINISH = "dk.frem.app.recording.FINISH"
        internal const val EXTRA_SESSION = "session_id"
        private val ACTIONS = setOf(ACTION_START, ACTION_PAUSE, ACTION_RESUME, ACTION_FINISH)

        internal fun start(context: Context, sessionId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SESSION, sessionId),
            )
        }
    }
}
