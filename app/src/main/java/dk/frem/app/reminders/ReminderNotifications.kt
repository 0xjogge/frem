package dk.frem.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dk.frem.app.MainActivity
import dk.frem.app.R
import dk.frem.app.data.ReminderKind
import dk.frem.app.domain.ReminderMessage

object ReminderNotifications {
    private const val CHANNEL = "frem-daily-reminders"

    fun allowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance !=
                NotificationManager.IMPORTANCE_NONE
    }

    fun post(
        context: Context,
        kind: ReminderKind,
        message: ReminderMessage,
        preview: Boolean = false,
    ): Boolean {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Daily reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Optional weight, water, and step-goal check-ins" },
        )
        if (
            !manager.areNotificationsEnabled() ||
                manager.getNotificationChannel(CHANNEL).importance ==
                    NotificationManager.IMPORTANCE_NONE
        )
            return false
        val intent =
            Intent(context, MainActivity::class.java)
                .putExtra("reminder_kind", kind.name)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
        val pending =
            PendingIntent.getActivity(
                context,
                kind.ordinal,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_frem_foreground)
                .setContentTitle(if (preview) "Frem reminder preview" else message.title)
                .setContentText(message.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
        return try {
            NotificationManagerCompat.from(context)
                .notify(if (preview) 1099 else 1000 + kind.ordinal, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
