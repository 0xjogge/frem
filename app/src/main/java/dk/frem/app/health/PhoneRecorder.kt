package dk.frem.app.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.LocalRecordingClient
import com.google.android.gms.fitness.data.LocalDataSet
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.data.LocalField
import com.google.android.gms.fitness.request.LocalDataReadRequest
import dk.frem.app.data.DaySummary
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await

class PhoneRecorder(private val context: Context) {
    val allowed: Boolean
        get() =
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED

    val distanceAvailable: Boolean
        get() =
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(
                    context,
                    LocalRecordingClient.LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE,
                ) == 0

    suspend fun read(): List<DaySummary> {
        if (
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException(
                "Allow physical activity permission to record steps and distance.",
            )
        }
        check(
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(
                    context,
                    LocalRecordingClient.LOCAL_RECORDING_CLIENT_STEPS_MIN_VERSION_CODE,
                ) == 0,
        ) {
            "Update Google Play services to enable phone tracking."
        }
        val client = FitnessLocal.getLocalRecordingClient(context)
        client.subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA).await()
        val recordDistance = distanceAvailable
        if (recordDistance) client.subscribe(LocalDataType.TYPE_DISTANCE_DELTA).await()
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        // Query local calendar days separately: a fixed 24-hour bucket is wrong across DST.
        return ((HISTORY_DAYS - 1L) downTo 0L).map { ago ->
            val day = today.minusDays(ago)
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val end =
                minOf(
                    day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    System.currentTimeMillis(),
                )
            val response =
                client
                    .readData(
                        LocalDataReadRequest.Builder()
                            .aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
                            .apply {
                                if (recordDistance) aggregate(LocalDataType.TYPE_DISTANCE_DELTA)
                            }
                            .bucketByTime(1, TimeUnit.DAYS)
                            .setTimeRange(start, end, TimeUnit.MILLISECONDS)
                            .build(),
                    )
                    .await()
            val sets = response.buckets.flatMap { it.dataSets } + response.dataSets
            summarizePhoneDay(day, sets)
        }
    }

    companion object {
        const val HISTORY_DAYS = 10
    }
}

internal fun summarizePhoneDay(day: LocalDate, sets: List<LocalDataSet>): DaySummary {
    fun points(type: LocalDataType) =
        sets.filter { it.dataType == type }.flatMap { it.dataPoints }.takeIf { it.isNotEmpty() }

    val distance =
        points(LocalDataType.TYPE_DISTANCE_DELTA)?.sumOf {
            it.getValue(LocalField.FIELD_DISTANCE).asFloat().toDouble()
        }
    return DaySummary(
        day.toString(),
        steps =
            points(LocalDataType.TYPE_STEP_COUNT_DELTA)?.sumOf {
                it.getValue(LocalField.FIELD_STEPS).asInt().toLong()
            },
        distance = distance,
        distanceSource = if (distance != null) "Phone recording" else null,
        source = "Phone recording",
    )
}
