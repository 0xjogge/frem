package dk.frem.app.recording

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ActivityType(val label: String, val usesPace: Boolean, val cycling: Boolean) {
    WALKING("Walking", true, false),
    HIKING("Hiking", true, false),
    RUNNING("Running", true, false),
    TRAIL_RUNNING("Trail running", true, false),
    CYCLING("Cycling", false, true),
    MOUNTAIN_BIKING("Mountain biking", false, true),
    GRAVEL_CYCLING("Gravel cycling", false, true),
    E_BIKE("E-bike", false, true),
}

enum class RecordingState {
    RECORDING,
    PAUSED,
    INTERRUPTED,
    FINISHED,
}

/**
 * start/end describe the wall-clock span, including paused/interrupted gaps. durationMillis is
 * checkpointed active recording time only; finishing an interrupted activity does not invent time.
 */
@Entity(tableName = "activity_sessions", indices = [Index(value = ["activeSlot"], unique = true)])
data class ActivitySession(
    @PrimaryKey val id: String,
    val title: String,
    val type: ActivityType,
    val start: Long,
    val end: Long? = null,
    val zoneId: String,
    val state: RecordingState = RecordingState.RECORDING,
    val distanceMeters: Double = 0.0,
    val durationMillis: Long = 0,
    val ascentMeters: Double? = null,
    val lastCheckpoint: Long = start,
    // SQLite permits multiple NULLs in a unique index, but only one unfinished session.
    val activeSlot: Int? = if (state == RecordingState.FINISHED) null else 1,
)

@Entity(
    tableName = "track_points",
    primaryKeys = ["sessionId", "sequence"],
    foreignKeys =
        [
            ForeignKey(
                entity = ActivitySession::class,
                parentColumns = ["id"],
                childColumns = ["sessionId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class TrackPoint(
    val sessionId: String,
    val sequence: Long,
    val segment: Int,
    val recordedAt: Long,
    val elapsedRealtimeNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Float? = null,
)

data class RecordingLiveState(
    val sessionId: String? = null,
    val accuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val lastFixAt: Long? = null,
    val durationMillis: Long = 0,
    val message: String? = null,
    val cadence: Double? = null,
)
