package dk.frem.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dk.frem.app.MainActivity
import dk.frem.app.R
import java.util.Locale

internal object RecordingNotifications {
    const val ID = 2001
    private const val CHANNEL = "frem-activity-recording"

    fun create(
        context: Context,
        session: ActivitySession?,
        live: RecordingLiveState,
        imperial: Boolean = false,
    ): Notification {
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                        CHANNEL,
                        "Activity recording",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply { description = "GPS activity recording status and controls" },
            )
        val open =
            PendingIntent.getActivity(
                context,
                2000,
                Intent(context, MainActivity::class.java)
                    .putExtra("recording", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val recording = session?.state == RecordingState.RECORDING
        val status =
            when (session?.state) {
                RecordingState.PAUSED -> "Paused"
                RecordingState.INTERRUPTED -> "Interrupted"
                else -> "Recording"
            }
        val meters = session?.distanceMeters ?: 0.0
        val distance =
            if (imperial) String.format(Locale.getDefault(), "%.2f mi", meters / 1_609.344)
            else String.format(Locale.getDefault(), "%.2f km", meters / 1_000)
        val content =
            if (recording) "$status · $distance"
            else {
                val seconds = live.durationMillis / 1_000
                val elapsed =
                    String.format(
                        Locale.getDefault(),
                        "%d:%02d:%02d",
                        seconds / 3_600,
                        seconds / 60 % 60,
                        seconds % 60,
                    )
                "$status · $elapsed · $distance"
            }
        val builder =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_frem_foreground)
                .setContentTitle(session?.title ?: "Activity recording")
                .setContentText(content)
                // System UI advances this clock without waking the app to re-post a timer string.
                // Subtract active duration, rather than session.start (which includes paused gaps).
                .setWhen(System.currentTimeMillis() - live.durationMillis)
                .setShowWhen(recording)
                .setUsesChronometer(recording)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (session != null) {
            val paused = session.state != RecordingState.RECORDING
            builder.addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "Resume" else "Pause",
                control(
                    context,
                    session.id,
                    if (paused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE,
                ),
            )
            builder.addAction(
                android.R.drawable.ic_menu_save,
                "Finish",
                control(context, session.id, RecordingService.ACTION_FINISH),
            )
        }
        return builder.build()
    }

    private fun control(context: Context, id: String, action: String): PendingIntent {
        val intent =
            Intent(context, RecordingService::class.java)
                .setAction(action)
                .putExtra(RecordingService.EXTRA_SESSION, id)
                // Identity includes the session: old notifications cannot control a later activity.
                .setData(android.net.Uri.parse("frem-recording://$id/$action"))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action == RecordingService.ACTION_RESUME) {
            PendingIntent.getForegroundService(context, 0, intent, flags)
        } else {
            PendingIntent.getService(context, 0, intent, flags)
        }
    }
}
