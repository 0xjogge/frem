package dk.frem.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dk.frem.app.FremApplication
import dk.frem.app.MainActivity
import dk.frem.app.R
import dk.frem.app.data.*
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class StepsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = (context.applicationContext as FremApplication).repository
                updateAll(
                    context,
                    repo.dao.day(LocalDate.now().toString()),
                    repo.preferences.flow.first(),
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun updateAll(context: Context, day: DaySummary?, preferences: Preferences) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StepsWidget::class.java))
            val today = day?.takeIf { it.date == LocalDate.now().toString() }
            val views = RemoteViews(context.packageName, R.layout.steps_widget)
            val format = NumberFormat.getIntegerInstance()
            views.setTextViewText(R.id.widget_steps, today?.steps?.let(format::format) ?: "—")
            views.setProgressBar(
                R.id.widget_progress,
                100,
                (((today?.steps ?: 0) * 100) / preferences.stepGoal).toInt().coerceIn(0, 100),
                false,
            )
            val stamp =
                today?.updatedAt?.let {
                    Instant.ofEpochMilli(it)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                }
            views.setTextViewText(
                R.id.widget_subtitle,
                if (stamp == null) "Open Frem to update"
                else "of ${format.format(preferences.stepGoal)} steps · updated $stamp",
            )
            val intent =
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
