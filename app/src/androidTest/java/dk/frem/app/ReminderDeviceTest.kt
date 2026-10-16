package dk.frem.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import dk.frem.app.data.ReminderKind
import dk.frem.app.domain.nextReminderTime
import dk.frem.app.reminders.*
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ReminderDeviceTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun disabledReminderWorkerStaysQuiet() = runBlocking {
        val repository = (context as FremApplication).repository
        assumeTrue(!repository.preferences.flow.first().waterReminder.enabled)
        val request =
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInputData(
                    workDataOf("kind" to "WATER", "date" to java.time.LocalDate.now().toString()),
                )
                .build()
        val manager = WorkManager.getInstance(context)
        manager.enqueue(request).await()
        val result =
            withTimeout(30_000) {
                manager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }
            }
        assertEquals(WorkInfo.State.SUCCEEDED, result!!.state)
    }

    @Test
    fun selectedLocalTimeProducesOneScheduledJobAndDisablingCancelsIt() = runBlocking {
        val repository = (context as FremApplication).repository
        val original = repository.preferences.flow.first().waterReminder
        assumeTrue(!original.enabled)
        val now = ZonedDateTime.now()
        val chosen = now.plusHours(2).let { it.hour * 60 + it.minute }
        val expected = nextReminderTime(now, chosen).toInstant().toEpochMilli()
        val manager = WorkManager.getInstance(context)
        try {
            repository.preferences.reminder(ReminderKind.WATER, enabled = true, minute = chosen)
            ReminderScheduler.update(
                context,
                ReminderKind.WATER,
                repository.preferences.flow.first(),
            )
            val jobs =
                withTimeout(10_000) {
                    manager.getWorkInfosByTagFlow("frem-reminder-WATER").first { list ->
                        list.any { it.state == WorkInfo.State.ENQUEUED }
                    }
                }
            val active = jobs.filter { it.state == WorkInfo.State.ENQUEUED }
            assertEquals(1, active.size)
            assertTrue(kotlin.math.abs(active.single().nextScheduleTimeMillis - expected) < 3000)
        } finally {
            repository.preferences.reminder(
                ReminderKind.WATER,
                enabled = original.enabled,
                minute = original.minuteOfDay,
            )
            ReminderScheduler.update(
                context,
                ReminderKind.WATER,
                repository.preferences.flow.first(),
            )
        }
        assertTrue(
            manager.getWorkInfosByTagFlow("frem-reminder-WATER").first().none {
                it.state == WorkInfo.State.ENQUEUED
            },
        )
    }
}
