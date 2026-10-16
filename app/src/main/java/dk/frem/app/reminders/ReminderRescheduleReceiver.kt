package dk.frem.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dk.frem.app.FremApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = (context.applicationContext as FremApplication).repository
                ReminderScheduler.updateAll(context, repository.preferences.flow.first())
            } finally {
                pending.finish()
            }
        }
    }
}
