package dk.frem.app.reminders

import android.content.Context
import androidx.work.*
import dk.frem.app.FremApplication
import dk.frem.app.data.*
import dk.frem.app.domain.reminderMessage
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first

class ReminderWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind =
            inputData.getString("kind")?.let {
                runCatching { ReminderKind.valueOf(it) }.getOrNull()
            } ?: return Result.failure()
        val repository = (applicationContext as FremApplication).repository
        try {
            val preferences = repository.preferences.flow.first()
            val now = ZonedDateTime.now()
            if (
                !preferences.reminder(kind).enabled ||
                    inputData.getString("date") != now.toLocalDate().toString()
            )
                return Result.success()
            if (preferences.backgroundSync) repository.refresh(background = true)
            val checkedAt = ZonedDateTime.now()
            if (inputData.getString("date") != checkedAt.toLocalDate().toString())
                return Result.success()
            val latestPreferences = repository.preferences.flow.first()
            val entries = repository.dao.entries().first()
            val message =
                reminderMessage(
                    kind,
                    entries,
                    repository.dao.day(checkedAt.toLocalDate().toString()),
                    latestPreferences,
                    checkedAt,
                )
            if (message != null && ReminderNotifications.post(applicationContext, kind, message))
                repository.preferences.reminderSent(kind, checkedAt.toLocalDate().toString())
            return Result.success()
        } finally {
            ReminderScheduler.enqueueNext(
                applicationContext,
                kind,
                repository.preferences.flow.first(),
            )
        }
    }
}
