package dk.frem.app.hearing

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dk.frem.app.FremApplication
import dk.frem.app.MainActivity
import kotlinx.coroutines.*

class NoiseService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var capture: Job? = null
    private var source: NoiseSource? = null
    private var stopping = false
    private var latestStartId = 0
    private val repository: NoiseRepository
        get() = (application as FremApplication).noise

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == STOP) {
            stopping = true
            source?.close()
            capture?.cancel()
            if (capture == null) stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("session")
        if (
            intent?.action != START ||
                id == null ||
                repository.live.value.session?.id != id ||
                repository.live.value.session?.state != "RECORDING"
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (capture != null) return START_NOT_STICKY
        stopping = false
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    "frem-noise",
                    "Environmental noise",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            val open =
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val finish =
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, NoiseService::class.java).setAction(STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val notification =
                Notification.Builder(this, "frem-noise")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Measuring environmental noise")
                    .setContentText("Microphone active · audio is not saved")
                    .setContentIntent(open)
                    .setOngoing(true)
                    .setUsesChronometer(true)
                    .setWhen(System.currentTimeMillis())
                    .addAction(Notification.Action.Builder(null, "Finish", finish).build())
                    .build()
            ServiceCompat.startForeground(
                this,
                7203,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            scope.launch {
                repository.finish(id, true, e.message)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        capture = scope.launch {
            var failure: String? = null
            try {
                val processor = NoiseProcessor()
                val input = repository.newSource()
                source = input
                input.frames().collect { frame ->
                    val reading =
                        withContext(Dispatchers.Default) {
                            processor.process(frame.samples, frame.silenced)
                        }
                    repository.accept(id, frame, reading)
                }
                if (!stopping)
                    failure = "Microphone input stopped. Saved measurements are retained."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: "Microphone input stopped."
            } finally {
                val interrupted = !stopping
                source?.close()
                source = null
                capture = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(latestStartId)
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.finish(id, interrupted, failure)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        source?.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val START = "dk.frem.app.NOISE_START"
        private const val STOP = "dk.frem.app.NOISE_STOP"

        fun start(context: Context, id: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NoiseService::class.java).setAction(START).putExtra("session", id),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NoiseService::class.java).setAction(STOP))
        }
    }
}
