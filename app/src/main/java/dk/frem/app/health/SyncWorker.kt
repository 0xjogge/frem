package dk.frem.app.health

import android.content.Context
import androidx.work.*
import dk.frem.app.FremApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as FremApplication).repository
        if (!repository.preferences.flow.first().backgroundSync) return Result.success()
        return if (repository.refresh(background = true)) Result.success()
        else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (enabled)
                manager.enqueueUniquePeriodicWork(
                    "frem-health-sync",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                        .setConstraints(
                            Constraints.Builder().setRequiresBatteryNotLow(true).build(),
                        )
                        .build(),
                )
            else manager.cancelUniqueWork("frem-health-sync")
        }
    }
}
