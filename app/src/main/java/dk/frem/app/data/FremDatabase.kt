package dk.frem.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FremDao {
    @Query("SELECT * FROM entries WHERE syncState != 'deleted' ORDER BY date DESC, timestamp DESC")
    fun entries(): Flow<List<LogEntry>>

    @Query("SELECT * FROM entries WHERE source = 'Frem'") suspend fun localEntries(): List<LogEntry>

    @Query("SELECT * FROM entries WHERE id = :id") suspend fun entry(id: String): LogEntry?

    @Upsert suspend fun putEntry(entry: LogEntry)

    @Upsert suspend fun putEntries(entries: List<LogEntry>)

    @Query("DELETE FROM entries WHERE id = :id") suspend fun removeEntry(id: String)

    @Query("DELETE FROM entries WHERE source != 'Frem' AND kind IN (:kinds) AND date >= :since")
    suspend fun removeImportedEntries(kinds: List<String>, since: String)

    @Query("SELECT * FROM entries WHERE source != 'Frem' AND kind IN (:kinds) AND date >= :since")
    suspend fun importedEntries(kinds: List<String>, since: String): List<LogEntry>

    @Query("DELETE FROM entries WHERE id IN (:ids)") suspend fun removeEntries(ids: List<String>)

    @Query("SELECT * FROM days ORDER BY date DESC") fun days(): Flow<List<DaySummary>>

    @Query("SELECT * FROM days ORDER BY date DESC") suspend fun allDays(): List<DaySummary>

    @Query("SELECT * FROM days WHERE date = :date") suspend fun day(date: String): DaySummary?

    @Upsert suspend fun putDays(days: List<DaySummary>)

    @Query("SELECT * FROM workouts ORDER BY start DESC") fun workouts(): Flow<List<Workout>>

    @Query("SELECT * FROM workouts WHERE end >= :since")
    suspend fun importedWorkouts(since: Long): List<Workout>

    @Query("DELETE FROM workouts WHERE id IN (:ids)")
    suspend fun removeWorkoutIds(ids: List<String>)

    @Query("DELETE FROM workouts WHERE end >= :since") suspend fun removeWorkouts(since: Long)

    @Upsert suspend fun putWorkouts(workouts: List<Workout>)

    @Query("SELECT * FROM sleep ORDER BY end DESC") fun nights(): Flow<List<SleepNight>>

    @Query("SELECT * FROM sleep WHERE end >= :since")
    suspend fun importedNights(since: Long): List<SleepNight>

    @Query("DELETE FROM sleep WHERE id IN (:ids)") suspend fun removeNightIds(ids: List<String>)

    @Query("DELETE FROM sleep WHERE end >= :since") suspend fun removeSleep(since: Long)

    @Upsert suspend fun putNights(nights: List<SleepNight>)
}

@Database(
    entities = [LogEntry::class, DaySummary::class, Workout::class, SleepNight::class],
    version = 2,
    exportSchema = true,
)
abstract class FremDatabase : RoomDatabase() {
    abstract fun dao(): FremDao

    companion object {
        val MIGRATION_1_2 =
            object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE days ADD COLUMN moderateMinutes REAL")
                    db.execSQL("ALTER TABLE days ADD COLUMN vigorousMinutes REAL")
                    db.execSQL("ALTER TABLE days ADD COLUMN intensityMinutes INTEGER")
                    db.execSQL("ALTER TABLE days ADD COLUMN heartLatest INTEGER")
                    db.execSQL("ALTER TABLE days ADD COLUMN heartLatestTime INTEGER")
                }
            }
    }
}
