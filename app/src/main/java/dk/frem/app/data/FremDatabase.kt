package dk.frem.app.data

import androidx.room.*
import dk.frem.app.recording.ActivityDayMetrics
import dk.frem.app.recording.ActivitySession
import dk.frem.app.recording.RecordingDao
import dk.frem.app.recording.TrackPoint
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
    entities =
        [
            LogEntry::class,
            DaySummary::class,
            Workout::class,
            SleepNight::class,
            ActivitySession::class,
            TrackPoint::class,
            ActivityDayMetrics::class,
        ],
    version = 5,
    exportSchema = true,
)
abstract class FremDatabase : RoomDatabase() {
    abstract fun dao(): FremDao

    abstract fun recordingDao(): RecordingDao

    companion object {
        val MIGRATION_4_5 =
            object : androidx.room.migration.Migration(4, 5) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS activity_day_metrics (
                                            sessionId TEXT NOT NULL, date TEXT NOT NULL, steps INTEGER,
                                            cadenceSteps REAL NOT NULL, cadenceMillis INTEGER NOT NULL,
                                            moderateMillis INTEGER NOT NULL, vigorousMillis INTEGER NOT NULL,
                                            PRIMARY KEY(sessionId, date), FOREIGN KEY(sessionId) REFERENCES activity_sessions(id)
                                            ON UPDATE NO ACTION ON DELETE CASCADE)
                        """
                            .trimIndent(),
                    )
                }
            }

        val MIGRATION_3_4 =
            object : androidx.room.migration.Migration(3, 4) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS activity_sessions (
                            id TEXT NOT NULL PRIMARY KEY,
                            title TEXT NOT NULL,
                            type TEXT NOT NULL,
                            start INTEGER NOT NULL,
                            end INTEGER,
                            zoneId TEXT NOT NULL,
                            state TEXT NOT NULL,
                            distanceMeters REAL NOT NULL,
                            durationMillis INTEGER NOT NULL,
                            ascentMeters REAL,
                            lastCheckpoint INTEGER NOT NULL,
                            activeSlot INTEGER
                        )
                        """
                            .trimIndent(),
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_activity_sessions_activeSlot " +
                            "ON activity_sessions (activeSlot)",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS track_points (
                            sessionId TEXT NOT NULL,
                            sequence INTEGER NOT NULL,
                            segment INTEGER NOT NULL,
                            recordedAt INTEGER NOT NULL,
                            elapsedRealtimeNanos INTEGER NOT NULL,
                            latitude REAL NOT NULL,
                            longitude REAL NOT NULL,
                            accuracyMeters REAL NOT NULL,
                            altitudeMeters REAL,
                            speedMetersPerSecond REAL,
                            PRIMARY KEY (sessionId, sequence),
                            FOREIGN KEY (sessionId) REFERENCES activity_sessions (id)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """
                            .trimIndent(),
                    )
                }
            }

        val MIGRATION_2_3 =
            object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE days ADD COLUMN distanceSource TEXT")
                }
            }

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
