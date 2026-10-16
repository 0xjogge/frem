package dk.frem.app.data

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.room.withTransaction
import dk.frem.app.health.HealthRepository
import dk.frem.app.health.PhoneRecorder
import dk.frem.app.widget.StepsWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class FremRepository(
    val context: Context,
    val database: FremDatabase,
    val preferences: PreferencesStore,
) {
    val dao = database.dao()
    val health = HealthRepository(context)
    val phone = PhoneRecorder(context)
    val hours = MutableStateFlow<List<HourSteps>>(emptyList())
    val sameTimeAverage = MutableStateFlow<Long?>(null)
    val granted = MutableStateFlow<Set<String>>(emptySet())
    val refreshing = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private val lock = Mutex()

    suspend fun save(
        kind: LogKind,
        date: java.time.LocalDate,
        value: Double,
        existing: LogEntry? = null,
    ) = lock.withLock {
        require(value.isFinite() && value > 0) { "Enter a positive value." }
        require(date <= java.time.LocalDate.now()) { "Choose today or an earlier date." }
        require(existing?.editable != false) {
            "Edit imported entries in the app that created them."
        }
        require(
            when (kind) {
                LogKind.WEIGHT -> value <= 1000
                LogKind.HEIGHT -> value <= 300
                LogKind.BODY_FAT -> value <= 100
                LogKind.HYDRATION -> value <= 20000
            },
        ) {
            "That value is outside the supported range."
        }
        val entry =
            existing?.copy(
                date = date.toString(),
                value = value,
                version = existing.version + 1,
                syncState = "pending",
                timestamp = System.currentTimeMillis(),
                zone =
                    if (date != existing.localDate) java.time.ZoneId.systemDefault().id
                    else existing.zone,
            ) ?: LogEntry(kind = kind.name, date = date.toString(), value = value)
        dao.putEntry(entry)
        syncPending()
    }

    suspend fun delete(entry: LogEntry) = lock.withLock {
        require(entry.editable)
        if (entry.healthId == null) dao.removeEntry(entry.id)
        else {
            dao.putEntry(entry.copy(syncState = "deleted", version = entry.version + 1))
            syncPending()
        }
    }

    private suspend fun syncPending() {
        for (entry in dao.localEntries().filter { it.syncState != "synced" }) {
            try {
                val result = health.sync(entry) ?: continue
                if (result == "deleted") dao.removeEntry(entry.id)
                else dao.putEntry(entry.copy(healthId = result, syncState = "synced"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "Saved on your phone. Health Connect sync will retry: ${e.message}"
            }
        }
    }

    suspend fun refresh(background: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                refreshing.value = true
                try {
                    val allowed = health.permissions()
                    granted.value = allowed
                    if (
                        background &&
                            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in allowed
                    )
                        return@withLock false
                    syncPending()
                    if (
                        allowed.any {
                            it.startsWith("android.permission.health.READ_") &&
                                !it.endsWith("BACKGROUND") &&
                                !it.endsWith("HISTORY")
                        }
                    ) {
                        val imported = health.import()
                        database.withTransaction {
                            val existing = dao.allDays().associateBy { it.date }
                            val today = java.time.LocalDate.now().toString()
                            dao.putDays(
                                imported.days.filter { incoming ->
                                    val previous = existing[incoming.date]
                                    incoming.date == today ||
                                        previous == null ||
                                        previous.copy(updatedAt = incoming.updatedAt) != incoming
                                },
                            )
                            if (imported.importedKinds.isNotEmpty()) {
                                val previous =
                                    dao.importedEntries(
                                            imported.importedKinds,
                                            imported.since.toString(),
                                        )
                                        .associateBy { it.id }
                                val current = imported.entries.associateBy { it.id }
                                (previous.keys - current.keys).toList().chunked(500).forEach {
                                    dao.removeEntries(it)
                                }
                                dao.putEntries(imported.entries.filter { previous[it.id] != it })
                            }
                            val since =
                                imported.since
                                    .atStartOfDay(java.time.ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli()
                            if (
                                HealthPermission.getReadPermission(ExerciseSessionRecord::class) in
                                    allowed
                            ) {
                                val previous = dao.importedWorkouts(since).associateBy { it.id }
                                val current = imported.workouts.associateBy { it.id }
                                (previous.keys - current.keys).toList().chunked(500).forEach {
                                    dao.removeWorkoutIds(it)
                                }
                                dao.putWorkouts(imported.workouts.filter { previous[it.id] != it })
                            }
                            if (
                                HealthPermission.getReadPermission(SleepSessionRecord::class) in
                                    allowed
                            ) {
                                val previous = dao.importedNights(since).associateBy { it.id }
                                val current = imported.nights.associateBy { it.id }
                                (previous.keys - current.keys).toList().chunked(500).forEach {
                                    dao.removeNightIds(it)
                                }
                                dao.putNights(imported.nights.filter { previous[it.id] != it })
                            }
                        }
                        hours.value = imported.hours
                        sameTimeAverage.value = imported.sameTimeAverage
                        preferences.flag("connected", true)
                    }
                    // Select a fallback source rather than adding overlapping phone totals.
                    // This also works when body/sleep permissions exist but step access does not.
                    if (
                        phone.allowed &&
                            !background &&
                            (HealthPermission.getReadPermission(StepsRecord::class) !in allowed ||
                                !health.nativeStepsAvailable)
                    ) {
                        val local =
                            phone.read().map { day ->
                                val hc = dao.day(day.date)
                                if (hc == null || hc.source == "Phone recording") day
                                else
                                    hc.copy(
                                        steps =
                                            if (
                                                HealthPermission.getReadPermission(
                                                    StepsRecord::class,
                                                ) !in allowed
                                            )
                                                day.steps
                                            else hc.steps ?: day.steps,
                                        source =
                                            if (hc.steps == null && day.steps != null)
                                                "Phone recording + Health Connect"
                                            else hc.source,
                                    )
                            }
                        dao.putDays(local)
                        if (HealthPermission.getReadPermission(StepsRecord::class) !in allowed) {
                            hours.value = emptyList()
                            sameTimeAverage.value = null
                        }
                    }
                    StepsWidget.updateAll(
                        context,
                        dao.day(java.time.LocalDate.now().toString()),
                        preferences.flow.first(),
                    )
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message.value = "Couldn’t refresh: ${e.message ?: "Please try again."}"
                    false
                } finally {
                    refreshing.value = false
                }
            }
        }
}
