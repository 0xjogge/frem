package dk.frem.app.reminders

import android.content.Context
import androidx.work.*
import dk.frem.app.data.*
import dk.frem.app.domain.nextReminderTime
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    private fun tag(kind: ReminderKind) = "frem-reminder-${kind.name}"

    suspend fun update(context: Context, kind: ReminderKind, preferences: Preferences) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tag(kind)).await()
        enqueueNext(context, kind, preferences)
    }

    suspend fun updateAll(context: Context, preferences: Preferences) {
        ReminderKind.entries.forEach { update(context, it, preferences) }
    }

    fun enqueueNext(
        context: Context,
        kind: ReminderKind,
        preferences: Preferences,
        now: ZonedDateTime = ZonedDateTime.now(),
    ) {
        val setting = preferences.reminder(kind)
        if (!setting.enabled) return
        val next = nextReminderTime(now, setting.minuteOfDay)
        val request =
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInputData(
                    workDataOf("kind" to kind.name, "date" to next.toLocalDate().toString()),
                )
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .addTag(tag(kind))
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "${tag(kind)}-${next.toLocalDate()}",
                ExistingWorkPolicy.KEEP,
                request,
            )
    }
}
