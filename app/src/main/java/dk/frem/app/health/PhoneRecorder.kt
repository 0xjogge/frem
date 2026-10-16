package dk.frem.app.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.LocalRecordingClient
import com.google.android.gms.fitness.data.LocalDataType
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

    suspend fun read(): List<DaySummary> {
        if (
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Allow physical activity permission to count phone steps.")
        }
        check(
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(
                    context,
                    LocalRecordingClient.LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE,
                ) == 0,
        ) {
            "Update Google Play services to enable phone tracking."
        }
        val client = FitnessLocal.getLocalRecordingClient(context)
        client.subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA).await()
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        // Query local calendar days separately: a fixed 24-hour bucket is wrong across DST.
        return (9L downTo 0L).map { ago ->
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
                            .bucketByTime(1, TimeUnit.DAYS)
                            .setTimeRange(start, end, TimeUnit.MILLISECONDS)
                            .build(),
                    )
                    .await()
            val sets = response.buckets.flatMap { it.dataSets } + response.dataSets
            fun value(name: String): Double? =
                sets
                    .filter { it.dataType.name == name }
                    .flatMap { it.dataPoints }
                    .takeIf { it.isNotEmpty() }
                    ?.sumOf { point ->
                        point.dataType.fields.sumOf { field ->
                            point.getValue(field).toString().toDoubleOrNull() ?: 0.0
                        }
                    }
            // Distance and energy remain Health Connect imports. The fallback
            // only supplies steps and does not introduce another estimate source.
            DaySummary(
                day.toString(),
                steps = value("com.google.step_count.delta")?.toLong(),
                source = "Phone recording",
            )
        }
    }
}
