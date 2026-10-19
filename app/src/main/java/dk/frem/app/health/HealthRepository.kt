package dk.frem.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.*
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.*
import dk.frem.app.data.*
import dk.frem.app.domain.measurementTime
import java.time.*
import kotlin.reflect.KClass

data class HealthImport(
    val days: List<DaySummary>,
    val entries: List<LogEntry>,
    val workouts: List<Workout>,
    val nights: List<SleepNight>,
    val importedKinds: List<String>,
    val since: LocalDate,
    val hours: List<HourSteps>,
    val sameTimeAverage: Long?,
    val permissions: Set<String>,
)

class HealthRepository(private val context: Context) {
    @Volatile private var cachedClient: HealthConnectClient? = null
    val available: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    val client: HealthConnectClient?
        get() =
            if (!available) null
            else cachedClient ?: HealthConnectClient.getOrCreate(context).also { cachedClient = it }

    val groups: Map<String, List<HealthDataType>>
        get() =
            linkedMapOf(
                    "Steps, distance & energy" to
                        listOf(
                            dataType("Steps", StepsRecord::class),
                            dataType("Step cadence", StepsCadenceRecord::class),
                            dataType("Distance", DistanceRecord::class),
                            dataType("Active energy", ActiveCaloriesBurnedRecord::class),
                            dataType("Total energy", TotalCaloriesBurnedRecord::class),
                        ),
                    "Floors & elevation" to
                        listOf(
                            dataType("Floors climbed", FloorsClimbedRecord::class),
                            dataType("Elevation gained", ElevationGainedRecord::class),
                        ),
                    "Weight & body measurements" to
                        listOf(
                            dataType("Weight", WeightRecord::class, share = true),
                            dataType("Height", HeightRecord::class, share = true),
                            dataType("Body fat", BodyFatRecord::class, share = true),
                        ),
                    "Hydration" to
                        listOf(dataType("Water entries", HydrationRecord::class, share = true)),
                    "Sleep" to listOf(dataType("Sleep", SleepSessionRecord::class)),
                    "Heart rate" to
                        listOf(
                            dataType("Heart rate", HeartRateRecord::class),
                            dataType("Resting heart rate", RestingHeartRateRecord::class),
                        ),
                    "Workouts" to listOf(dataType("Workouts", ExerciseSessionRecord::class)),
                )
                .also {
                    if (feature(HealthConnectFeatures.FEATURE_ACTIVITY_INTENSITY))
                        it["Activity intensity"] =
                            listOf(dataType("Activity intensity", ActivityIntensityRecord::class))
                }

    fun feature(id: Int): Boolean =
        client?.features?.getFeatureStatus(id) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    fun backgroundPermission(): Set<String> =
        if (feature(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND))
            setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        else emptySet()

    fun historyPermission(): Set<String> =
        if (feature(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY))
            setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        else emptySet()

    suspend fun permissions(): Set<String> =
        client?.permissionController?.getGrantedPermissions() ?: emptySet()

    private fun dataType(name: String, type: KClass<out Record>, share: Boolean = false) =
        HealthDataType(
            name,
            HealthPermission.getReadPermission(type),
            if (share) HealthPermission.getWritePermission(type) else null,
        )

    suspend fun import(): HealthImport {
        val hc =
            client
                ?: error("Health Connect is unavailable. Install or update it in Android settings.")
        val allowed = permissions()
        fun can(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in allowed
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val since =
            today.minusDays(
                if (HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in allowed) 365 else 29,
            )
        val range = TimeRangeFilter.between(since.atStartOfDay(), now)
        val metrics = mutableSetOf<AggregateMetric<*>>()
        if (can(StepsRecord::class)) metrics += StepsRecord.COUNT_TOTAL
        if (can(DistanceRecord::class)) metrics += DistanceRecord.DISTANCE_TOTAL
        if (can(ActiveCaloriesBurnedRecord::class))
            metrics += ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
        if (can(TotalCaloriesBurnedRecord::class)) metrics += TotalCaloriesBurnedRecord.ENERGY_TOTAL
        if (can(FloorsClimbedRecord::class)) metrics += FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL
        if (can(ElevationGainedRecord::class))
            metrics += ElevationGainedRecord.ELEVATION_GAINED_TOTAL
        if (can(HeartRateRecord::class))
            metrics +=
                setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX)
        if (can(RestingHeartRateRecord::class)) metrics += RestingHeartRateRecord.BPM_AVG
        if (can(SleepSessionRecord::class)) metrics += SleepSessionRecord.SLEEP_DURATION_TOTAL
        if (can(HydrationRecord::class)) metrics += HydrationRecord.VOLUME_TOTAL
        if (can(StepsCadenceRecord::class)) metrics += StepsCadenceRecord.RATE_AVG
        val intensity =
            can(ActivityIntensityRecord::class) &&
                feature(HealthConnectFeatures.FEATURE_ACTIVITY_INTENSITY)
        if (intensity)
            metrics +=
                setOf(
                    ActivityIntensityRecord.DURATION_TOTAL,
                    ActivityIntensityRecord.MODERATE_DURATION_TOTAL,
                    ActivityIntensityRecord.VIGOROUS_DURATION_TOTAL,
                    ActivityIntensityRecord.INTENSITY_MINUTES_TOTAL,
                )
        val rows =
            if (metrics.isEmpty()) emptyList()
            else
                hc.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(metrics, range, Period.ofDays(1)),
                )
        val recordedDays = rows.map { row ->
            val r = row.result
            val distance =
                if (can(DistanceRecord::class)) r[DistanceRecord.DISTANCE_TOTAL]?.inMeters else null
            DaySummary(
                date = row.startTime.toLocalDate().toString(),
                steps = if (can(StepsRecord::class)) r[StepsRecord.COUNT_TOTAL] else null,
                distance = distance,
                distanceSource = if (distance != null) "Health Connect" else null,
                activeCalories =
                    if (can(ActiveCaloriesBurnedRecord::class))
                        r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                    else null,
                totalCalories =
                    if (can(TotalCaloriesBurnedRecord::class))
                        r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                    else null,
                floors =
                    if (can(FloorsClimbedRecord::class)) r[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]
                    else null,
                elevation =
                    if (can(ElevationGainedRecord::class))
                        r[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters
                    else null,
                heartAverage =
                    if (can(HeartRateRecord::class)) r[HeartRateRecord.BPM_AVG]?.toDouble()
                    else null,
                heartMin = if (can(HeartRateRecord::class)) r[HeartRateRecord.BPM_MIN] else null,
                heartMax = if (can(HeartRateRecord::class)) r[HeartRateRecord.BPM_MAX] else null,
                restingHeart =
                    if (can(RestingHeartRateRecord::class)) r[RestingHeartRateRecord.BPM_AVG]
                    else null,
                sleepMinutes =
                    if (can(SleepSessionRecord::class))
                        r[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMillis()?.div(60000.0)
                    else null,
                waterMl =
                    if (can(HydrationRecord::class)) r[HydrationRecord.VOLUME_TOTAL]?.inMilliliters
                    else null,
                activeMinutes =
                    if (intensity)
                        r[ActivityIntensityRecord.DURATION_TOTAL]?.toMillis()?.div(60000.0)
                    else null,
                cadence =
                    if (can(StepsCadenceRecord::class)) r[StepsCadenceRecord.RATE_AVG] else null,
                moderateMinutes =
                    if (intensity)
                        r[ActivityIntensityRecord.MODERATE_DURATION_TOTAL]?.toMillis()?.div(60000.0)
                    else null,
                vigorousMinutes =
                    if (intensity)
                        r[ActivityIntensityRecord.VIGOROUS_DURATION_TOTAL]?.toMillis()?.div(60000.0)
                    else null,
                intensityMinutes =
                    if (intensity) r[ActivityIntensityRecord.INTENSITY_MINUTES_TOTAL] else null,
                source =
                    if (
                        r.dataOrigins.any {
                            it.packageName == "android" ||
                                it.packageName.startsWith("com.android.healthconnect.phone.")
                        }
                    )
                        "Your phone · Health Connect"
                    else "Health Connect",
            )
        }
        // Aggregation omits empty buckets. Replace them explicitly so deleted records
        // do not leave stale totals in the local cache; null still means unknown.
        val byDate = recordedDays.associateBy { it.date }
        val latestHeart =
            if (can(HeartRateRecord::class))
                records(HeartRateRecord::class, TimeRangeFilter.between(today.atStartOfDay(), now))
                    .flatMap { it.samples }
                    .maxByOrNull { it.time }
            else null
        val days =
            generateSequence(since) { it.plusDays(1) }
                .takeWhile { it <= today }
                .map { byDate[it.toString()] ?: DaySummary(it.toString()) }
                .toList()
                .map {
                    if (it.date == today.toString())
                        it.copy(
                            heartLatest = latestHeart?.beatsPerMinute,
                            heartLatestTime = latestHeart?.time?.toEpochMilli(),
                        )
                    else it
                }
        val entries = mutableListOf<LogEntry>()
        val importedKinds = mutableListOf<String>()
        fun entry(
            id: String,
            kind: LogKind,
            time: Instant,
            value: Double,
            origin: String,
            offset: ZoneOffset?,
        ): LogEntry {
            val date = time.atZone(offset ?: ZoneId.systemDefault()).toLocalDate()
            return LogEntry(
                "hc:$id",
                kind.name,
                date.toString(),
                value,
                source = origin,
                healthId = id,
                syncState = "synced",
                timestamp = time.toEpochMilli(),
            )
        }
        if (can(WeightRecord::class)) {
            importedKinds += LogKind.WEIGHT.name
            entries +=
                records(WeightRecord::class, range)
                    .filter { it.metadata.dataOrigin.packageName != context.packageName }
                    .map {
                        entry(
                            it.metadata.id,
                            LogKind.WEIGHT,
                            it.time,
                            it.weight.inKilograms,
                            it.metadata.dataOrigin.packageName,
                            it.zoneOffset,
                        )
                    }
        }
        if (can(HeightRecord::class)) {
            importedKinds += LogKind.HEIGHT.name
            entries +=
                records(HeightRecord::class, range)
                    .filter { it.metadata.dataOrigin.packageName != context.packageName }
                    .map {
                        entry(
                            it.metadata.id,
                            LogKind.HEIGHT,
                            it.time,
                            it.height.inMeters * 100,
                            it.metadata.dataOrigin.packageName,
                            it.zoneOffset,
                        )
                    }
        }
        if (can(BodyFatRecord::class)) {
            importedKinds += LogKind.BODY_FAT.name
            entries +=
                records(BodyFatRecord::class, range)
                    .filter { it.metadata.dataOrigin.packageName != context.packageName }
                    .map {
                        entry(
                            it.metadata.id,
                            LogKind.BODY_FAT,
                            it.time,
                            it.percentage.value,
                            it.metadata.dataOrigin.packageName,
                            it.zoneOffset,
                        )
                    }
        }
        if (can(HydrationRecord::class)) {
            importedKinds += LogKind.HYDRATION.name
            entries +=
                records(HydrationRecord::class, range)
                    .filter { it.metadata.dataOrigin.packageName != context.packageName }
                    .map {
                        entry(
                            it.metadata.id,
                            LogKind.HYDRATION,
                            it.startTime,
                            it.volume.inMilliliters,
                            it.metadata.dataOrigin.packageName,
                            it.startZoneOffset,
                        )
                    }
        }
        val workouts =
            if (can(ExerciseSessionRecord::class))
                records(ExerciseSessionRecord::class, range).map {
                    Workout(
                        it.metadata.id,
                        it.title ?: exerciseName(it.exerciseType),
                        it.exerciseType,
                        it.startTime.toEpochMilli(),
                        it.endTime.toEpochMilli(),
                        it.metadata.dataOrigin.packageName,
                    )
                }
            else emptyList()
        val nights =
            if (can(SleepSessionRecord::class))
                records(SleepSessionRecord::class, range).map { night ->
                    fun stage(type: Int) =
                        night.stages
                            .filter { it.stage == type }
                            .takeIf { it.isNotEmpty() }
                            ?.sumOf {
                                Duration.between(it.startTime, it.endTime).toMillis() / 60000.0
                            }
                    val awake =
                        night.stages
                            .filter {
                                it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE ||
                                    it.stage == SleepSessionRecord.STAGE_TYPE_OUT_OF_BED ||
                                    it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED
                            }
                            .sumOf {
                                Duration.between(it.startTime, it.endTime).toMillis() / 60000.0
                            }
                    SleepNight(
                        night.metadata.id,
                        night.startTime.toEpochMilli(),
                        night.endTime.toEpochMilli(),
                        (Duration.between(night.startTime, night.endTime).toMillis() / 60000.0 -
                                awake)
                            .coerceAtLeast(0.0),
                        stage(SleepSessionRecord.STAGE_TYPE_DEEP),
                        stage(SleepSessionRecord.STAGE_TYPE_REM),
                        stage(SleepSessionRecord.STAGE_TYPE_LIGHT),
                        if (night.stages.isEmpty()) null else awake,
                        night.metadata.dataOrigin.packageName,
                    )
                }
            else emptyList()
        val hours =
            if (can(StepsRecord::class))
                hc.aggregateGroupByDuration(
                        AggregateGroupByDurationRequest(
                            setOf(StepsRecord.COUNT_TOTAL),
                            TimeRangeFilter.between(today.atStartOfDay(), now),
                            Duration.ofHours(1),
                        ),
                    )
                    .map {
                        HourSteps(
                            it.startTime.atZone(ZoneId.systemDefault()).hour,
                            it.result[StepsRecord.COUNT_TOTAL],
                        )
                    }
            else emptyList()
        val sameTime =
            if (can(StepsRecord::class))
                (1L..7L)
                    .map { ago ->
                        hc.aggregate(
                                AggregateRequest(
                                    setOf(StepsRecord.COUNT_TOTAL),
                                    TimeRangeFilter.between(
                                        today.minusDays(ago).atStartOfDay(),
                                        now.minusDays(ago),
                                    ),
                                ),
                            )[StepsRecord.COUNT_TOTAL]
                    }
                    .takeIf { it.all { n -> n != null } }
                    ?.filterNotNull()
                    ?.average()
                    ?.toLong()
            else null
        return HealthImport(
            days,
            entries,
            workouts,
            nights,
            importedKinds,
            since,
            hours,
            sameTime,
            allowed,
        )
    }

    suspend fun <T : Record> records(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val hc = client ?: return emptyList()
        val result = mutableListOf<T>()
        var token: String? = null
        do {
            val page =
                hc.readRecords(ReadRecordsRequest(type, range, pageSize = 1000, pageToken = token))
            result += page.records
            token = page.pageToken
        } while (token != null)
        return result
    }

    fun recordType(kind: LogKind): KClass<out Record> =
        when (kind) {
            LogKind.WEIGHT -> WeightRecord::class
            LogKind.HEIGHT -> HeightRecord::class
            LogKind.BODY_FAT -> BodyFatRecord::class
            LogKind.HYDRATION -> HydrationRecord::class
        }

    suspend fun sync(entry: LogEntry): String? {
        val hc = client ?: return null
        val type = recordType(entry.logKind)
        if (HealthPermission.getWritePermission(type) !in permissions()) return null
        val clientId = "frem:${entry.id}"
        if (entry.syncState == "deleted") {
            hc.deleteRecords(type, emptyList(), listOf(clientId))
            return "deleted"
        }
        // Date-only historical entries use local noon. A morning entry uses its
        // saved instant so syncing never invents a future measurement timestamp.
        val time = measurementTime(entry)
        val metadata =
            Metadata.manualEntry(clientRecordId = clientId, clientRecordVersion = entry.version)
        val record =
            when (entry.logKind) {
                LogKind.WEIGHT ->
                    WeightRecord(
                        time.toInstant(),
                        time.offset,
                        Mass.kilograms(entry.value),
                        metadata,
                    )
                LogKind.HEIGHT ->
                    HeightRecord(
                        time.toInstant(),
                        time.offset,
                        Length.meters(entry.value / 100),
                        metadata,
                    )
                LogKind.BODY_FAT ->
                    BodyFatRecord(time.toInstant(), time.offset, Percentage(entry.value), metadata)
                LogKind.HYDRATION ->
                    HydrationRecord(
                        time.minusSeconds(1).toInstant(),
                        time.minusSeconds(1).offset,
                        time.toInstant(),
                        time.offset,
                        Volume.milliliters(entry.value),
                        metadata,
                    )
            }
        return hc.insertRecords(listOf(record)).recordIdsList.single()
    }

    companion object {
        fun exerciseName(type: Int): String =
            when (type) {
                ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walk"
                ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Run"
                ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Cycling"
                ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Swimming"
                ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Strength training"
                ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
                ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hike"
                else -> "Workout"
            }
    }
}
