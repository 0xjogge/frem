package dk.frem.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.frem.app.FremApplication
import dk.frem.app.data.*
import dk.frem.app.health.HealthCapabilities
import dk.frem.app.health.SyncWorker
import dk.frem.app.reminders.ReminderNotifications
import dk.frem.app.reminders.ReminderScheduler
import dk.frem.app.widget.StepsWidget
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class FremViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as FremApplication).repository
    val entries =
        repository.dao
            .entries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val days =
        repository.dao
            .days()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val workouts =
        repository.dao
            .workouts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val nights =
        repository.dao
            .nights()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val preferences =
        repository.preferences.flow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            Preferences(),
        )
    val refreshing = repository.refreshing
    val message = repository.message
    val granted = repository.granted
    val hours = repository.hours
    val sameTimeAverage = repository.sameTimeAverage
    val notificationsAllowed = MutableStateFlow(ReminderNotifications.allowed(application))
    val reminderDestination = MutableStateFlow<ReminderKind?>(null)
    val healthCapabilities = MutableStateFlow(HealthCapabilities())

    init {
        updateCapabilities()
    }

    fun updateCapabilities() {
        viewModelScope.launch(Dispatchers.IO) {
            healthCapabilities.value =
                HealthCapabilities(repository.health.available, repository.health.groups)
        }
    }

    fun refresh() {
        if (!refreshing.value) viewModelScope.launch { repository.refresh() }
    }

    suspend fun save(kind: LogKind, date: LocalDate, value: Double, existing: LogEntry?) {
        repository.save(kind, date, value, existing)
    }

    fun delete(entry: LogEntry) = action { repository.delete(entry) }

    fun number(key: String, value: Int) = action {
        repository.preferences.number(key, value)
        StepsWidget.updateAll(
            getApplication(),
            repository.dao.day(LocalDate.now().toString()),
            repository.preferences.flow.first(),
        )
    }

    fun text(key: String, value: String) = action { repository.preferences.text(key, value) }

    fun flag(key: String, value: Boolean) = action {
        repository.preferences.flag(key, value)
        if (key == "background") SyncWorker.schedule(getApplication(), value)
    }

    fun reminder(kind: ReminderKind, enabled: Boolean? = null, minute: Int? = null) = action {
        repository.preferences.reminder(kind, enabled, minute)
        ReminderScheduler.update(getApplication(), kind, repository.preferences.flow.first())
    }

    fun updateNotificationAvailability() {
        notificationsAllowed.value = ReminderNotifications.allowed(getApplication())
    }

    fun openReminder(kind: String?) {
        reminderDestination.value = kind?.let {
            runCatching { ReminderKind.valueOf(it) }.getOrNull()
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = e.message
            }
        }
    }
}
