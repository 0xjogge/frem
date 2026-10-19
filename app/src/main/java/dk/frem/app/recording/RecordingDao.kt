package dk.frem.app.recording

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM activity_sessions ORDER BY start DESC, id")
    fun sessions(): Flow<List<ActivitySession>>

    @Query("SELECT * FROM activity_sessions WHERE activeSlot = 1 LIMIT 1")
    fun active(): Flow<ActivitySession?>

    @Query("SELECT * FROM activity_sessions WHERE activeSlot = 1 LIMIT 1")
    suspend fun activeSession(): ActivitySession?

    @Query("SELECT * FROM activity_sessions WHERE id = :id")
    suspend fun session(id: String): ActivitySession?

    @Query("SELECT * FROM track_points WHERE sessionId = :id ORDER BY sequence")
    fun points(id: String): Flow<List<TrackPoint>>

    @Query("SELECT * FROM track_points WHERE sessionId = :id ORDER BY sequence")
    suspend fun getPoints(id: String): List<TrackPoint>

    @Query("SELECT * FROM track_points WHERE sessionId = :id ORDER BY sequence DESC LIMIT 1")
    suspend fun lastPoint(id: String): TrackPoint?

    @Insert suspend fun insertSession(session: ActivitySession)

    @Update suspend fun updateSession(session: ActivitySession)

    @Insert suspend fun insertPoint(point: TrackPoint)

    @Query("DELETE FROM activity_sessions WHERE id = :id") suspend fun deleteSession(id: String)

    // Never infer time or distance across a process death, reboot, or force stop.
    @Query("UPDATE activity_sessions SET state = 'INTERRUPTED' WHERE activeSlot = 1")
    suspend fun recoverUnfinished()
}
