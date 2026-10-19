package dk.frem.app.hearing

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.math.log10
import kotlin.math.pow

@Entity(tableName = "noise_calibrations")
data class NoiseCalibration(
    @PrimaryKey val inputKey: String,
    val offsetDb: Double,
    val referenceDb: Double,
    val calibratedAt: Long,
)

@Entity(tableName = "noise_sessions")
data class NoiseSession(
    @PrimaryKey val id: String,
    val start: Long,
    val end: Long? = null,
    val state: String = "RECORDING",
    val inputKey: String? = null,
    val calibrationOffsetDb: Double? = null,
    val measuredMillis: Long = 0,
    val energyMillis: Double = 0.0,
    val maximumDbfs: Double? = null,
    val unmeasuredMillis: Long = 0,
    val doseFraction: Double = 0.0,
) {
    val rawLeq: Double?
        get() =
            if (measuredMillis > 0 && energyMillis > 0) 10 * log10(energyMillis / measuredMillis)
            else null

    val leqDbA: Double?
        get() = calibrationOffsetDb?.let { offset -> rawLeq?.plus(offset) }

    val maximumDbA: Double?
        get() = calibrationOffsetDb?.let { offset -> maximumDbfs?.plus(offset) }

    val dosePercent: Double?
        get() = calibrationOffsetDb?.let { if (measuredMillis > 0) doseFraction * 100 else null }
}

/** NIOSH-style reference: 85 dB(A) for 8 h, 3 dB exchange. Only measured intervals contribute. */
fun noiseDoseFraction(levelDbA: Double, durationMillis: Long): Double =
    durationMillis / 28_800_000.0 * 2.0.pow((levelDbA - 85) / 3)

data class NoiseLive(
    val session: NoiseSession? = null,
    val currentDbfs: Double? = null,
    val calibrationReady: Boolean = false,
    val message: String? = null,
)
