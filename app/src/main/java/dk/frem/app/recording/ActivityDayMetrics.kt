package dk.frem.app.recording

import androidx.room.Entity
import androidx.room.ForeignKey

/** Native measurements are independent of the replaceable Health Connect daily cache. */
@Entity(
    tableName = "activity_day_metrics",
    primaryKeys = ["sessionId", "date"],
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
data class ActivityDayMetrics(
    val sessionId: String,
    val date: String,
    val steps: Long? = null,
    val cadenceSteps: Double = 0.0,
    val cadenceMillis: Long = 0,
    val moderateMillis: Long = 0,
    val vigorousMillis: Long = 0,
    val ascentMeters: Double? = null,
    val descentMeters: Double? = null,
    val floorAscentMeters: Double? = null,
    val elevationSource: String? = null,
) {
    val floors: Double?
        get() = floorAscentMeters?.let { kotlin.math.floor(it / 3.0) }

    val cadence: Double?
        get() = if (cadenceMillis > 0) cadenceSteps * 60_000.0 / cadenceMillis else null

    val activeMinutes: Double?
        get() = if (cadenceMillis > 0) (moderateMillis + vigorousMillis) / 60_000.0 else null
}
