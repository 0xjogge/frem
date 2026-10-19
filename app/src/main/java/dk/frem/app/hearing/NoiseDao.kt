package dk.frem.app.hearing

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoiseDao {
    @Query("SELECT * FROM noise_sessions ORDER BY start DESC")
    fun sessions(): Flow<List<NoiseSession>>

    @Query("SELECT * FROM noise_sessions WHERE id = :id")
    suspend fun session(id: String): NoiseSession?

    @Query("SELECT * FROM noise_calibrations WHERE inputKey = :key")
    suspend fun calibration(key: String): NoiseCalibration?

    @Upsert suspend fun put(session: NoiseSession)

    @Upsert suspend fun putCalibration(calibration: NoiseCalibration)

    @Query(
        "UPDATE noise_sessions SET state = 'INTERRUPTED', end = COALESCE(end, start) WHERE state = 'RECORDING'",
    )
    suspend fun recover()

    @Query("DELETE FROM noise_sessions WHERE id = :id") suspend fun delete(id: String)

    @Query("DELETE FROM noise_calibrations WHERE inputKey = :key")
    suspend fun deleteCalibration(key: String)
}
