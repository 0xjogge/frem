package dk.frem.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dk.frem.app.data.Preferences
import dk.frem.app.recording.ActivityDayMetrics
import dk.frem.app.recording.ActivitySession
import dk.frem.app.recording.ActivityType
import dk.frem.app.recording.RecordingState
import dk.frem.app.recording.TrackPoint
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Shared sport icon for the recording entry point, history, and navigation. */
val ActivityType.icon: ImageVector
    get() =
        when (this) {
            ActivityType.WALKING -> Icons.AutoMirrored.Rounded.DirectionsWalk
            ActivityType.HIKING -> Icons.Rounded.Hiking
            ActivityType.RUNNING -> Icons.AutoMirrored.Rounded.DirectionsRun
            ActivityType.TRAIL_RUNNING -> Icons.Rounded.Terrain
            ActivityType.CYCLING -> Icons.Rounded.DirectionsBike
            ActivityType.MOUNTAIN_BIKING -> Icons.Rounded.Landscape
            ActivityType.GRAVEL_CYCLING -> Icons.Rounded.Route
            ActivityType.E_BIKE -> Icons.Rounded.ElectricBike
        }

fun activityIcon(type: ActivityType): ImageVector = type.icon

/**
 * Insets and navigation belong to the caller. A back action only leaves this screen; recording
 * actions are always explicit callbacks. The optional initial type overrides the local last choice.
 */
@Composable
fun RecordingScreen(
    session: ActivitySession?,
    points: List<TrackPoint>,
    preferences: Preferences,
    liveDurationMillis: Long?,
    speedMetersPerSecond: Float?,
    accuracyMeters: Float?,
    lastFixAt: Long?,
    statusMessage: String?,
    busy: Boolean,
    onStart: (ActivityType) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    onBack: () -> Unit,
    initialType: ActivityType? = null,
    nativeMetrics: List<ActivityDayMetrics> = emptyList(),
    cadence: Double? = null,
    sensorMessage: String? = null,
) {
    BackHandler(onBack = onBack)
    if (session == null) {
        RecordingPreparation(initialType, busy, statusMessage, onStart, onBack)
        return
    }

    var discardDialog by rememberSaveable(session.id) { mutableStateOf(false) }
    val recording = session.state == RecordingState.RECORDING
    val finished = session.state == RecordingState.FINISHED

    Column(Modifier.fillMaxSize().testTag("recording_screen")) {
        ActivityScreenHeader(
            title = session.type.label,
            subtitle = recordingStateLabel(session.state),
            onBack = onBack,
            backTag = "recording_back",
        )
        if (!statusMessage.isNullOrBlank()) {
            RecordingMessage(
                statusMessage,
                Modifier.padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp)
                    .heightIn(max = 120.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val mapHeight = (maxHeight * .45f).coerceIn(260.dp, 380.dp)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RecordingTotals(session, liveDurationMillis ?: session.durationMillis, preferences)
                // Its identity and inputs do not depend on the elapsed-time ticker.
                key(session.id) {
                    ActivityRoute(
                        points = points,
                        live = !finished,
                        modifier =
                            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(mapHeight),
                    )
                }
                LiveRecordingReadings(
                    session = session,
                    speedMetersPerSecond = speedMetersPerSecond,
                    accuracyMeters = accuracyMeters,
                    lastFixAt = lastFixAt,
                    preferences = preferences,
                )
                if (!session.type.cycling)
                    NativeActivityReadings(
                        nativeMetrics,
                        cadence,
                        Modifier.padding(horizontal = 20.dp),
                    )
                sensorMessage?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ElevationReadings(
                    session,
                    nativeMetrics,
                    preferences,
                    Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    when (session.state) {
                        RecordingState.RECORDING ->
                            "Recording continues offline and with your screen locked. Back leaves this screen without stopping your activity."
                        RecordingState.PAUSED ->
                            "Recording is paused. Resume when you’re ready, or finish to save this activity."
                        RecordingState.INTERRUPTED ->
                            "Recording was interrupted. Your saved route is here. Resume to start a new segment, or finish to save what you have."
                        RecordingState.FINISHED ->
                            "Recording has finished. No more locations are being added."
                    },
                    Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (busy) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().semantics {
                            contentDescription = "Updating activity"
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = if (recording) onPause else onResume,
                        enabled = !busy && !finished,
                        modifier =
                            Modifier.weight(1f)
                                .heightIn(min = 56.dp)
                                .testTag(if (recording) "recording_pause" else "recording_resume"),
                    ) {
                        Icon(if (recording) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (recording) "Pause" else "Resume")
                    }
                    Button(
                        onClick = onFinish,
                        enabled = !busy && !finished,
                        modifier =
                            Modifier.weight(1f).heightIn(min = 56.dp).testTag("recording_finish"),
                    ) {
                        Icon(Icons.Rounded.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Finish")
                    }
                }
                TextButton(
                    onClick = { discardDialog = true },
                    enabled = !busy && !finished,
                    modifier =
                        Modifier.align(Alignment.CenterHorizontally)
                            .heightIn(min = 48.dp)
                            .testTag("recording_discard"),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Discard activity")
                }
            }
        }
    }
    if (discardDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) discardDialog = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Discard this activity?") },
            text = {
                Text(
                    "This deletes the recording and its route. It can’t be undone. To keep your activity, choose Finish instead.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardDialog = false
                        onDiscard()
                    },
                    enabled = !busy,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("recording_discard_confirm"),
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { discardDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("recording_discard_cancel"),
                ) {
                    Text("Keep activity")
                }
            },
        )
    }
}

@Composable
private fun RecordingPreparation(
    initialType: ActivityType?,
    busy: Boolean,
    statusMessage: String?,
    onStart: (ActivityType) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val localPreferences =
        remember(context) {
            context.getSharedPreferences("frem_recording_ui", Context.MODE_PRIVATE)
        }
    val recent =
        remember(localPreferences) {
            localPreferences
                .getString("recent_types", null)
                .orEmpty()
                .split(',')
                .mapNotNull { name -> ActivityType.entries.firstOrNull { it.name == name } }
                .distinct()
                .take(3)
        }
    val lastType =
        remember(localPreferences) {
            ActivityType.entries.firstOrNull {
                it.name == localPreferences.getString("last_type", null)
            }
        }
    var selected by
        rememberSaveable(initialType) {
            mutableStateOf(initialType ?: lastType ?: recent.firstOrNull() ?: ActivityType.WALKING)
        }
    val select: (ActivityType) -> Unit = { type ->
        selected = type
        localPreferences.edit().putString("last_type", type.name).apply()
    }

    Column(Modifier.fillMaxSize().testTag("recording_screen")) {
        ActivityScreenHeader(
            "Record an activity",
            "A little fresh air. A new adventure.",
            onBack,
            "recording_back",
        )
        Column(
            Modifier.weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(selected.icon, null, Modifier.size(32.dp))
                    Text("Go at your own pace.", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Choose your activity, then head out. GPS starts only when you tap Start.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (recent.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recent activities", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recent.forEach { type ->
                            FilterChip(
                                selected = selected == type,
                                onClick = { select(type) },
                                enabled = !busy,
                                label = { Text(type.label) },
                                leadingIcon = { Icon(type.icon, null, Modifier.size(18.dp)) },
                                modifier =
                                    Modifier.heightIn(min = 48.dp)
                                        .testTag("recording_recent_${type.name}"),
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "What’s your adventure?",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                ActivityTypePicker(selected, !busy, ::activityTypeDescription, select)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.OfflineBolt, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Your route records offline, even with your screen locked. Map tiles may need a connection. Location access is requested when you start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!statusMessage.isNullOrBlank()) {
                    RecordingMessage(
                        statusMessage,
                        Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                    )
                }
                Button(
                    onClick = {
                        localPreferences
                            .edit()
                            .putString("last_type", selected.name)
                            .putString(
                                "recent_types",
                                (listOf(selected) + recent).distinct().take(3).joinToString(",") {
                                    it.name
                                },
                            )
                            .apply()
                        onStart(selected)
                    },
                    enabled = !busy,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("recording_start"),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Starting…")
                    } else {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Start ${selected.label.lowercase(Locale.ROOT)}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityTypePicker(
    selected: ActivityType,
    enabled: Boolean,
    description: ((ActivityType) -> String)?,
    onSelect: (ActivityType) -> Unit,
    tagPrefix: String = "recording_type_",
) {
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        val columns = if (maxWidth / LocalDensity.current.fontScale < 280.dp) 1 else 2
        val rows = ActivityType.entries.chunked(columns)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { type ->
                        val checked = selected == type
                        Surface(
                            modifier =
                                Modifier.weight(1f)
                                    .clip(MaterialTheme.shapes.medium)
                                    .selectable(
                                        checked,
                                        enabled = enabled,
                                        role = Role.RadioButton,
                                        onClick = { onSelect(type) },
                                    )
                                    .testTag("$tagPrefix${type.name}"),
                            shape = MaterialTheme.shapes.medium,
                            color =
                                if (checked) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                            contentColor =
                                if (checked) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                        ) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = if (description == null) 76.dp else 110.dp)
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Icon(type.icon, null, Modifier.size(24.dp))
                                    if (checked)
                                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp))
                                }
                                Text(type.label, style = MaterialTheme.typography.labelLarge)
                                description?.let {
                                    Text(it(type), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (row.size == 1 && columns == 2) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun activityTypeDescription(type: ActivityType): String =
    when (type) {
        ActivityType.WALKING -> "Everyday steps outside"
        ActivityType.HIKING -> "Trail walks and hikes"
        ActivityType.RUNNING -> "Roads, paths, your pace"
        ActivityType.TRAIL_RUNNING -> "Runs off the beaten path"
        ActivityType.CYCLING -> "Roads and cycle paths"
        ActivityType.MOUNTAIN_BIKING -> "Off-road rides and trails"
        ActivityType.GRAVEL_CYCLING -> "Mixed surfaces and gravel"
        ActivityType.E_BIKE -> "Electric-assisted rides"
    }

/** This subtree has no duration or GPS-status input, keeping the map stable on timer ticks. */
@Composable
private fun ActivityRoute(points: List<TrackPoint>, live: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.clip(MaterialTheme.shapes.large)) {
        ActivityMap(points = points, modifier = Modifier.fillMaxSize(), live = live)
    }
}

@Composable
private fun RecordingTotals(
    session: ActivitySession,
    durationMillis: Long,
    preferences: Preferences,
) {
    Surface(
        modifier = Modifier.padding(horizontal = 20.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        ActivityMetricPair(
            first =
                ActivityReading(
                    "Distance",
                    activityDistance(session.distanceMeters, preferences),
                    distanceUnit(preferences),
                ),
            second = ActivityReading("Recording time", activityDuration(durationMillis), "h:mm:ss"),
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            prominent = true,
        )
    }
}

@Composable
private fun LiveRecordingReadings(
    session: ActivitySession,
    speedMetersPerSecond: Float?,
    accuracyMeters: Float?,
    lastFixAt: Long?,
    preferences: Preferences,
) {
    val recording = session.state == RecordingState.RECORDING
    val now = recordingClock(recording)
    val fixAge =
        lastFixAt?.takeIf { it > 0 && it <= now + 5_000 }?.let { (now - it).coerceAtLeast(0) }
    val fresh = fixAge != null && fixAge <= 15_000
    val accuracy = accuracyMeters?.takeIf { it.isFinite() && it >= 0f }
    val weak = accuracy != null && accuracy > 50f
    val currentSpeed =
        speedMetersPerSecond?.toDouble()?.takeIf {
            recording && fresh && !weak && it.isFinite() && it >= 0
        }

    Column(
        Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                Icon(
                    Icons.Rounded.Speed,
                    null,
                    Modifier.padding(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (session.type.usesPace) "Current pace" else "Current speed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${activitySpeedOrPace(currentSpeed, session.type, preferences)} ${speedOrPaceUnit(session.type, preferences)}",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
        RecordingGpsStatus(session, recording, fresh, weak, fixAge, accuracy, preferences)
    }
}

@Composable
private fun recordingClock(running: Boolean): Long {
    val owner = LocalLifecycleOwner.current
    val now by
        produceState(System.currentTimeMillis(), running, owner) {
            value = System.currentTimeMillis()
            if (running) {
                owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isActive) {
                        value = System.currentTimeMillis()
                        delay(1_000)
                    }
                }
            }
        }
    return now
}

@Composable
private fun RecordingGpsStatus(
    session: ActivitySession,
    recording: Boolean,
    fresh: Boolean,
    weak: Boolean,
    fixAge: Long?,
    accuracy: Float?,
    preferences: Preferences,
) {
    val title: String
    val description: String
    val icon: ImageVector
    when {
        session.state == RecordingState.PAUSED -> {
            title = "Paused by you"
            description =
                "Time and route recording are paused. Resume to continue in a new segment."
            icon = Icons.Rounded.PauseCircle
        }
        session.state == RecordingState.INTERRUPTED -> {
            title = "Recording interrupted"
            description =
                "Recording is no longer running. Tap Resume to continue; the interruption will remain a gap."
            icon = Icons.Rounded.WarningAmber
        }
        !recording -> {
            title = "Recording finished"
            description = "Location recording has stopped."
            icon = Icons.Rounded.CheckCircle
        }
        fixAge == null -> {
            title = "Waiting for GPS"
            description =
                "Time is recording. Distance and route need a location fix. An open area can help."
            icon = Icons.Rounded.LocationSearching
        }
        !fresh -> {
            title = "GPS signal lost"
            description =
                "Last fix ${fixAgeLabel(fixAge)} ago. Time continues; distance and route may have gaps."
            icon = Icons.Rounded.LocationDisabled
        }
        weak -> {
            title = "Weak GPS signal"
            description =
                "Accuracy about ${accuracyLabel(accuracy, preferences)}. Distance and route may be less precise."
            icon = Icons.Rounded.GpsNotFixed
        }
        else -> {
            title = "GPS fix received"
            description =
                if (accuracy == null) "Recent location received · accuracy unavailable"
                else
                    "Accuracy about ${accuracyLabel(accuracy, preferences)} · recent location received"
            icon = Icons.Rounded.GpsFixed
        }
    }
    val warning = session.state == RecordingState.INTERRUPTED || (recording && (!fresh || weak))
    Surface(
        shape = MaterialTheme.shapes.medium,
        color =
            if (warning) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        contentColor =
            if (warning) MaterialTheme.colorScheme.onTertiaryContainer
            else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Saved activity details use recording duration, never inferred moving time. */
@Composable
fun ActivityDetailScreen(
    session: ActivitySession,
    points: List<TrackPoint>,
    preferences: Preferences,
    onBack: () -> Unit,
    onEdit: (String, ActivityType) -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    nativeMetrics: List<ActivityDayMetrics> = emptyList(),
) {
    BackHandler(onBack = onBack)
    var editDialog by rememberSaveable(session.id) { mutableStateOf(false) }
    var deleteDialog by rememberSaveable(session.id) { mutableStateOf(false) }
    val date = remember(session.start, session.zoneId) { activityDate(session) }
    val elapsed = session.end?.takeIf { it >= session.start }?.minus(session.start)
    val averageSpeed =
        if (
            session.durationMillis > 0 &&
                session.distanceMeters.isFinite() &&
                session.distanceMeters > 0
        )
            session.distanceMeters / (session.durationMillis / 1000.0)
        else null
    val segments = remember(points) { points.asSequence().map { it.segment }.distinct().count() }
    val recordingTime = activityDuration(session.durationMillis)

    Column(Modifier.fillMaxSize().testTag("activity_detail")) {
        ActivityScreenHeader(
            "Your activity",
            "Recorded with Frem",
            onBack,
            "activity_detail_back",
        ) {
            IconButton(
                onClick = { editDialog = true },
                modifier = Modifier.size(48.dp).testTag("activity_edit"),
            ) {
                Icon(Icons.Rounded.Edit, "Edit activity")
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val mapHeight = (maxHeight * .48f).coerceIn(260.dp, 420.dp)
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(
                    Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActivitySportLabel(session.type)
                    Text(
                        session.title.ifBlank { session.type.label },
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        date,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Saved time zone: ${session.zoneId.ifBlank { "—" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ActivityRoute(
                    points,
                    live = false,
                    modifier =
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(mapHeight),
                )
                Column(
                    Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            ActivityMetric(
                                "Distance",
                                activityDistance(session.distanceMeters, preferences),
                                distanceUnit(preferences),
                                prominent = true,
                            )
                            HorizontalDivider(
                                color =
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .16f),
                            )
                            ActivityMetricPair(
                                ActivityReading("Recording time", recordingTime, "h:mm:ss"),
                                ActivityReading(
                                    "Elapsed time",
                                    activityDuration(elapsed),
                                    "start to finish",
                                ),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActivitySummaryTile(
                            if (session.type.usesPace) "Average pace" else "Average speed",
                            activitySpeedOrPace(averageSpeed, session.type, preferences),
                            speedOrPaceUnit(session.type, preferences),
                            Icons.Rounded.Speed,
                            Modifier.weight(1f),
                        )
                        ActivitySummaryTile(
                            "Estimated ascent",
                            session.ascentMeters
                                ?.takeIf { it.isFinite() && it >= 0 }
                                ?.let {
                                    number(if (preferences.imperial) it * 3.280839895 else it)
                                } ?: "—",
                            if (preferences.imperial) "ft" else "m",
                            Icons.Rounded.Landscape,
                            Modifier.weight(1f),
                        )
                    }
                    NativeActivityReadings(nativeMetrics)
                    ElevationReadings(session, nativeMetrics, preferences)
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Route, null, Modifier.size(20.dp))
                                Text(
                                    "About this recording",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Text(
                                "Recording time counts time while recording, including stops unless you paused. Elapsed time includes pauses and interruptions. ${if (session.type.usesPace) "Average pace" else "Average speed"} uses recording time, not detected moving time.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                when {
                                    points.isEmpty() ->
                                        "No location points were saved. Time can still be recorded without a GPS route."
                                    points.size == 1 ->
                                        "Only one location was saved, so there isn’t a route line."
                                    segments > 1 ->
                                        "$segments route segments were saved. Pauses, interruptions, or location gaps can split a route; gaps aren’t joined by a line."
                                    else ->
                                        "The route has one recorded segment. Pauses and GPS gaps, when detected, are kept separate rather than joined by a line."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (session.ascentMeters != null) {
                                Text(
                                    "Elevation gain/loss uses ${session.elevationSource ?: "GPS"}. Pressure is filtered with a 2 m threshold; GPS uses a threshold based on vertical accuracy. Pauses and gaps start new baselines.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Button(
                        onClick = onExport,
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .testTag("activity_export"),
                    ) {
                        Icon(Icons.Rounded.FileDownload, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Export GPX")
                    }
                    Text(
                        "Take your recorded route with you. GPX exports include location points and their timestamps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { deleteDialog = true },
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("activity_delete"),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Delete activity")
                    }
                }
            }
        }
    }
    if (editDialog) {
        EditRecordedActivityDialog(session, onDismiss = { editDialog = false }) { title, type ->
            editDialog = false
            onEdit(title, type)
        }
    }
    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Delete this activity?") },
            text = {
                Text(
                    "“${session.title.ifBlank { session.type.label }}” and its recorded route will be deleted. This can’t be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteDialog = false
                        onDelete()
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("activity_delete_confirm"),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("activity_delete_cancel"),
                ) {
                    Text("Keep activity")
                }
            },
        )
    }
}

@Composable
private fun NativeActivityReadings(
    days: List<ActivityDayMetrics>,
    liveCadence: Double? = null,
    modifier: Modifier = Modifier,
) {
    val movingMillis = days.sumOf { it.cadenceMillis }
    val average =
        if (movingMillis > 0) days.sumOf { it.cadenceSteps } * 60_000 / movingMillis else null
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Phone activity measurements", style = MaterialTheme.typography.titleMedium)
        Text(
            "Moving average: ${number(average)} steps/min · ${number(days.mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.sum())} steps",
            modifier = Modifier.testTag("native_cadence"),
        )
        liveCadence?.let { Text("Current cadence: ${number(it)} steps/min") }
        Text(
            "Estimated active time: ${number(if (movingMillis > 0) days.sumOf { it.moderateMillis + it.vigorousMillis } / 60_000.0 else null, 1)} min",
            modifier = Modifier.testTag("native_active_minutes"),
        )
        Text(
            "Cadence uses detected moving intervals. Moderate/vigorous time is estimated from sustained step rate (100/130 steps/min). Physical activity access and a hardware step detector are needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ElevationReadings(
    session: ActivitySession,
    days: List<ActivityDayMetrics>,
    preferences: Preferences,
    modifier: Modifier = Modifier,
) {
    val factor = if (preferences.imperial) 3.28084 else 1.0
    val unit = if (preferences.imperial) "ft" else "m"
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Elevation gain & loss", style = MaterialTheme.typography.titleMedium)
        Text(
            "↑ ${number(session.ascentMeters?.times(factor))} $unit · ↓ ${number(session.descentMeters?.times(factor))} $unit",
            modifier = Modifier.testTag("native_elevation"),
        )
        Text(
            "${session.elevationSource ?: "Waiting for elevation"} · ${number(days.mapNotNull { it.floors }.takeIf { it.isNotEmpty() }?.sum())} floor equivalents",
            modifier = Modifier.testTag("native_floors"),
        )
        Text(
            "One floor equivalent is 3 m climbed with detected steps. Native floors need a barometer; GPS altitude alone is not used for floors.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EditRecordedActivityDialog(
    session: ActivitySession,
    onDismiss: () -> Unit,
    onSave: (String, ActivityType) -> Unit,
) {
    var title by rememberSaveable(session.id) { mutableStateOf(session.title) }
    var type by rememberSaveable(session.id) { mutableStateOf(session.type) }
    val focus = LocalFocusManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Make it yours") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(200) },
                    label = { Text("Activity title") },
                    singleLine = true,
                    isError = title.isBlank(),
                    supportingText = {
                        Text(
                            if (title.isBlank()) "Add a title to save your changes"
                            else "${title.length}/200",
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().testTag("activity_title_input"),
                )
                Text("Activity type", style = MaterialTheme.typography.titleSmall)
                ActivityTypePicker(
                    type,
                    enabled = true,
                    description = null,
                    onSelect = { type = it },
                    tagPrefix = "activity_edit_type_",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), type) },
                enabled = title.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("activity_edit_confirm"),
            ) {
                Text("Save changes")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("activity_edit_cancel"),
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun RecordActivityHero(
    active: ActivitySession?,
    preferences: Preferences,
    onRecord: () -> Unit,
    onActive: () -> Unit,
) {
    Card(
        onClick = if (active == null) onRecord else onActive,
        modifier = Modifier.fillMaxWidth().testTag("record_activity"),
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .10f),
                ) {
                    Icon(
                        active?.type?.icon ?: Icons.Rounded.Route,
                        null,
                        Modifier.padding(14.dp).size(28.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (active != null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .10f),
                    ) {
                        Text(
                            recordingStateLabel(active.state),
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                } else Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
            }
            Text(
                when (active?.state) {
                    null -> "Take it outside."
                    RecordingState.RECORDING -> "You’re on your way."
                    RecordingState.PAUSED -> "A moment to pause."
                    RecordingState.INTERRUPTED -> "Pick up where you left off."
                    RecordingState.FINISHED -> "Your latest adventure."
                },
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                if (active == null)
                    "Record a walk, run, or ride. Your route, your pace, all in Frem."
                else
                    "${active.type.label} · ${activityDistance(active.distanceMeters, preferences)} ${distanceUnit(preferences)} · ${activityDuration(active.durationMillis)} recorded",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                Modifier.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    if (active == null) Icons.Rounded.PlayCircle
                    else Icons.AutoMirrored.Rounded.ArrowForward,
                    null,
                )
                Text(
                    if (active == null) "Record an activity" else "Return to activity",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
fun RecordedActivityCard(
    session: ActivitySession,
    preferences: Preferences,
    onClick: () -> Unit,
) {
    val date = remember(session.start, session.zoneId) { activityDate(session) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("recorded_activity_${session.id}"),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Icon(session.type.icon, null, Modifier.padding(12.dp).size(24.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        session.title.ifBlank { session.type.label },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        session.type.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    Icons.Rounded.ChevronRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActivityMetricPair(
                ActivityReading(
                    "Distance",
                    activityDistance(session.distanceMeters, preferences),
                    distanceUnit(preferences),
                ),
                ActivityReading(
                    "Recording time",
                    activityDuration(session.durationMillis),
                    "h:mm:ss",
                ),
            )
            Text(
                "Recorded with Frem",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActivityScreenHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    backTag: String,
    actions: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag(backTag)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        actions?.invoke()
    }
}

@Composable
private fun ActivitySportLabel(type: ActivityType) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(type.icon, null, Modifier.size(18.dp))
            Text(type.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ActivityMetricPair(
    first: ActivityReading,
    second: ActivityReading,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val longestValue = maxOf(first.value.length, second.value.length)
        val minimumRowWidth = if (longestValue > 8) 340.dp else 260.dp
        if (maxWidth / LocalDensity.current.fontScale < minimumRowWidth) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ActivityMetric(first.label, first.value, first.unit, prominent = prominent)
                ActivityMetric(second.label, second.value, second.unit, prominent = prominent)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActivityMetric(first.label, first.value, first.unit, Modifier.weight(1f), prominent)
                ActivityMetric(
                    second.label,
                    second.value,
                    second.unit,
                    Modifier.weight(1f),
                    prominent,
                )
            }
        }
    }
}

private data class ActivityReading(val label: String, val value: String, val unit: String)

@Composable
private fun ActivityMetric(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
) {
    Column(
        modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style =
                if (prominent) MaterialTheme.typography.headlineLarge
                else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(unit, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ActivitySummaryTile(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector,
    modifier: Modifier,
) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            ActivityMetric(label, value, unit)
        }
    }
}

@Composable
private fun RecordingMessage(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary)
            Text(
                message,
                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun recordingStateLabel(state: RecordingState): String =
    when (state) {
        RecordingState.RECORDING -> "Recording"
        RecordingState.PAUSED -> "Paused"
        RecordingState.INTERRUPTED -> "Needs your attention"
        RecordingState.FINISHED -> "Finished"
    }

private fun distanceUnit(preferences: Preferences): String =
    if (preferences.imperial) "mi" else "km"

private fun activityDistance(meters: Double, preferences: Preferences): String =
    meters
        .takeIf { it.isFinite() && it >= 0 }
        ?.let {
            number(it / if (preferences.imperial) 1609.344 else 1000.0, 2)
        } ?: "—"

private fun activityDuration(millis: Long?): String {
    if (millis == null || millis < 0) return "—"
    val seconds = millis / 1000
    return String.format(
        Locale.getDefault(),
        "%d:%02d:%02d",
        seconds / 3600,
        seconds / 60 % 60,
        seconds % 60,
    )
}

private fun speedOrPaceUnit(type: ActivityType, preferences: Preferences): String =
    if (type.usesPace) "min/${distanceUnit(preferences)}"
    else if (preferences.imperial) "mph" else "km/h"

private fun activitySpeedOrPace(
    metersPerSecond: Double?,
    type: ActivityType,
    preferences: Preferences,
): String {
    val speed = metersPerSecond?.takeIf { it.isFinite() && it >= 0 } ?: return "—"
    if (!type.usesPace) return number(speed * if (preferences.imperial) 2.236936292 else 3.6, 1)
    // A stationary or missing fix has no finite pace. Round seconds before splitting minutes.
    if (speed <= 0) return "—"
    val paceSeconds =
        ((if (preferences.imperial) 1609.344 else 1000.0) / speed)
            .takeIf { it.isFinite() && it < Long.MAX_VALUE.toDouble() }
            ?.roundToLong() ?: return "—"
    return String.format(Locale.getDefault(), "%d:%02d", paceSeconds / 60, paceSeconds % 60)
}

private fun accuracyLabel(meters: Float?, preferences: Preferences): String =
    meters?.let {
        "${number(if (preferences.imperial) it * 3.280839895 else it.toDouble())} ${if (preferences.imperial) "ft" else "m"}"
    } ?: "—"

private fun fixAgeLabel(millis: Long): String =
    when {
        millis < 60_000 -> "${millis / 1000} sec"
        millis < 3_600_000 -> "${millis / 60_000} min"
        else -> "${millis / 3_600_000} hr"
    }

private fun activityDate(session: ActivitySession): String {
    val zone = runCatching { ZoneId.of(session.zoneId) }.getOrNull() ?: return "—"
    return runCatching {
            val local = Instant.ofEpochMilli(session.start).atZone(zone)
            dateText(local, "EEE d MMM yyyy · HH:mm z")
        }
        .getOrDefault("—")
}
