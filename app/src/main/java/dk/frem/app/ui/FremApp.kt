package dk.frem.app.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dk.frem.app.BuildConfig
import dk.frem.app.data.*
import dk.frem.app.domain.Insights
import dk.frem.app.domain.ReminderMessage
import dk.frem.app.domain.recordedActivityDays
import dk.frem.app.maps.MapCache
import dk.frem.app.performance.FrameDiagnostics
import dk.frem.app.performance.ObserveBenchmarkScroll
import dk.frem.app.recording.*
import dk.frem.app.reminders.ReminderNotifications
import dk.frem.app.widget.StepsWidget
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppData(
    val days: List<DaySummary>,
    val entries: List<LogEntry>,
    val workouts: List<Workout>,
    val nights: List<SleepNight>,
    val preferences: Preferences,
    val hours: List<HourSteps>,
    val granted: Set<String>,
    val sameTimeAverage: Long?,
    val today: LocalDate,
    val recordedActivities: List<ActivitySession> = emptyList(),
    val nativeMetrics: List<ActivityDayMetrics> = emptyList(),
) {
    val recordedDays by lazy { recordedActivityDays(days, nativeMetrics) }
    val activityDays: List<DaySummary>
        get() = recordedDays.days

    val day: DaySummary? by lazy { activityDays.find { it.date == today.toString() } }

    fun nativeSource(metric: Metric, date: LocalDate): String? =
        recordedDays.nativeSources[date.toString()]?.get(
            when (metric) {
                Metric.CADENCE -> "cadence"
                Metric.ACTIVE_MINUTES -> "active_minutes"
                Metric.FLOORS -> "floors"
                Metric.ELEVATION -> "elevation"
                Metric.DESCENT -> "elevation_lost"
                else -> ""
            },
        )

    val latestWeight: LogEntry? by lazy { entries.firstOrNull { it.kind == LogKind.WEIGHT.name } }
    val waterToday: Double? by lazy {
        entries
            .filter { it.kind == LogKind.HYDRATION.name && it.date == today.toString() }
            .takeIf { it.isNotEmpty() }
            ?.sumOf { it.value } ?: day?.waterMl
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FremApp(vm: FremViewModel) {
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val recordedActivities by vm.recordedActivities.collectAsStateWithLifecycle()
    val activeRecording by vm.activeRecording.collectAsStateWithLifecycle()
    val recordingLive by vm.recordingLive.collectAsStateWithLifecycle()
    val nativeMetrics by vm.nativeMetrics.collectAsStateWithLifecycle()
    val noiseSessions by vm.noiseSessions.collectAsStateWithLifecycle()
    val noiseLive by vm.noiseLive.collectAsStateWithLifecycle()
    val recordingBusy by vm.recordingBusy.collectAsStateWithLifecycle()
    val recordingDestination by vm.recordingDestination.collectAsStateWithLifecycle()
    val recordingMessage by vm.recordingMessage.collectAsStateWithLifecycle()
    val nights by vm.nights.collectAsStateWithLifecycle()
    val hours by vm.hours.collectAsStateWithLifecycle()
    val granted by vm.granted.collectAsStateWithLifecycle()
    val permissionStatus by vm.permissionStatus.collectAsStateWithLifecycle()
    val sameTime by vm.sameTimeAverage.collectAsStateWithLifecycle()
    val notificationsAllowed by vm.notificationsAllowed.collectAsStateWithLifecycle()
    val capabilities by vm.healthCapabilities.collectAsStateWithLifecycle()
    val reminderDestination by vm.reminderDestination.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val today = now.toLocalDate()
    val liveData =
        remember(
            days,
            entries,
            workouts,
            nights,
            preferences,
            hours,
            granted,
            sameTime,
            today,
            recordedActivities,
            nativeMetrics,
        ) {
            AppData(
                days,
                entries,
                workouts,
                nights,
                preferences,
                hours,
                granted,
                sameTime,
                today,
                recordedActivities,
                nativeMetrics,
            )
        }
    val fixture =
        if (BuildConfig.FRAME_DIAGNOSTICS)
            FrameDiagnostics.fixture.collectAsStateWithLifecycle().value
        else null
    val data =
        remember(liveData, fixture) {
            fixture?.copy(preferences = preferences, granted = granted) ?: liveData
        }
    val navigation = rememberFremNavigation()
    var selectedActivityId by rememberSaveable { mutableStateOf<String?>(null) }
    var recordingSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val immersive =
        navigation.current in setOf(FremDestination.RECORDING, FremDestination.ACTIVITY_DETAIL)
    val routeId =
        when (navigation.current) {
            FremDestination.RECORDING -> activeRecording?.id ?: recordingSessionId
            FremDestination.ACTIVITY_DETAIL -> selectedActivityId
            else -> null
        }
    val routePoints by
        remember(routeId) {
                routeId?.let(vm.recording::points) ?: flowOf(emptyList())
            }
            .collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedActivity = recordedActivities.firstOrNull { it.id == selectedActivityId }
    fun openRecorder() {
        recordingSessionId = activeRecording?.id
        vm.recordingMessage.value = null
        navigation.navigate(FremDestination.ACTIVITY)
        navigation.navigate(FremDestination.RECORDING)
    }
    fun openActivity(id: String) {
        selectedActivityId = id
        recordingSessionId = null
        navigation.navigate(FremDestination.ACTIVITY)
        navigation.navigate(FremDestination.ACTIVITY_DETAIL)
    }
    var metric by remember { mutableStateOf<Metric?>(null) }
    var hearingSheet by rememberSaveable { mutableStateOf(false) }
    var logging by remember { mutableStateOf<LogKind?>(null) }
    var editing by remember { mutableStateOf<LogEntry?>(null) }
    var connectionSheet by remember { mutableStateOf(false) }
    var deleteEntry by remember { mutableStateOf<LogEntry?>(null) }
    if (BuildConfig.FRAME_DIAGNOSTICS)
        DisposableEffect(navigation) {
            val action: (String) -> Unit = { page ->
                metric = null
                connectionSheet = false
                logging = null
                deleteEntry = null
                if (page == "METRIC_YEAR") {
                    FrameDiagnostics.initialMetricRange = "Y"
                    navigation.navigate(FremDestination.ACTIVITY)
                    metric = Metric.STEPS
                } else navigation.navigate(FremDestination.valueOf(page))
            }
            FrameDiagnostics.navigationAction = action
            onDispose {
                if (FrameDiagnostics.navigationAction === action)
                    FrameDiagnostics.navigationAction = null
            }
        }
    var pendingReminder by remember { mutableStateOf<ReminderKind?>(null) }
    var pendingPreview by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val noisePermissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            )
                vm.noiseAction { vm.noise.start() }
            else vm.message.value = "Allow microphone access to measure environmental noise."
        }
    fun startNoise() {
        if (
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
            vm.noiseAction { vm.noise.start() }
        else
            noisePermissions.launch(
                if (Build.VERSION.SDK_INT >= 33)
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                else arrayOf(Manifest.permission.RECORD_AUDIO),
            )
    }
    var pendingActivityType by rememberSaveable { mutableStateOf<ActivityType?>(null) }
    var pendingRecordingResume by rememberSaveable { mutableStateOf(false) }
    var recordingPermissionHelp by rememberSaveable { mutableStateOf(false) }
    var recordingLocationHelp by rememberSaveable { mutableStateOf(false) }
    var mapCacheDialog by remember { mutableStateOf(false) }
    var mapCacheDescription by remember { mutableStateOf("Checking map cache…") }
    var clearingMapCache by remember { mutableStateOf(false) }
    var gpxActivityId by rememberSaveable { mutableStateOf<String?>(null) }
    fun completeRecordingRequest() {
        val type = pendingActivityType ?: return
        if (
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            recordingPermissionHelp = true
            return
        }
        if (!context.getSystemService(LocationManager::class.java).isLocationEnabled) {
            recordingLocationHelp = true
            return
        }
        val resume = pendingRecordingResume
        pendingActivityType = null
        pendingRecordingResume = false
        vm.recordingAction {
            if (resume) vm.recording.resumeAndAwait()
            else recordingSessionId = vm.recording.start(type)
        }
    }
    val recordingSettings =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            completeRecordingRequest()
        }
    val recordingPermissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            completeRecordingRequest()
        }
    fun requestRecording(type: ActivityType, resume: Boolean = false) {
        pendingActivityType = type
        pendingRecordingResume = resume
        val required = mutableListOf<String>()
        if (
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            required += Manifest.permission.ACCESS_COARSE_LOCATION
            required += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (
            !type.cycling &&
                context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            required += Manifest.permission.ACTIVITY_RECOGNITION
        }
        val permissionPreferences =
            context.getSharedPreferences("frem_recording_ui", android.content.Context.MODE_PRIVATE)
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED &&
                !permissionPreferences.getBoolean("asked_notifications", false)
        ) {
            permissionPreferences.edit().putBoolean("asked_notifications", true).apply()
            required += Manifest.permission.POST_NOTIFICATIONS
        }
        if (required.isEmpty()) completeRecordingRequest()
        else recordingPermissions.launch(required.toTypedArray())
    }
    val exportGpx =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/gpx+xml"),
        ) { uri ->
            val id = gpxActivityId
            gpxActivityId = null
            if (uri != null && id != null)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val session =
                                vm.recording.getSession(id)
                                    ?: error("This activity no longer exists.")
                            val gpx = GpxExport.create(session, vm.recording.getPoints(id))
                            val output =
                                context.contentResolver.openOutputStream(uri)
                                    ?: error("Couldn’t open the export location.")
                            output.bufferedWriter().use { it.write(gpx) }
                        }
                        snackbar.showSnackbar("Your activity was exported as GPX.")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        snackbar.showSnackbar("Export failed: ${e.message}")
                    }
                }
        }
    val health = vm.repository.health
    var requestingBackground by rememberSaveable { mutableStateOf(false) }
    val permissions =
        rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { allowed ->
            if (
                requestingBackground &&
                    HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in allowed
            )
                vm.flag("background", true)
            requestingBackground = false
            vm.refresh()
        }
    val activityPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (allowed) vm.refresh()
            else
                vm.message.value =
                    "Physical activity access was not granted. You can still use manual logging."
        }
    fun enablePhoneRecording() {
        if (vm.repository.phone.allowed) vm.refresh()
        else activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
    }
    fun previewReminder() {
        if (
            !ReminderNotifications.post(
                context,
                ReminderKind.WATER,
                ReminderMessage(
                    "Frem reminder preview",
                    "This is a preview. Tap to open a water check-in.",
                ),
                preview = true,
            )
        )
            vm.message.value = "Enable daily reminders in Android notification settings."
    }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            vm.updateNotificationAvailability()
            if (allowed) {
                pendingReminder?.let { vm.reminder(it, enabled = true) }
                if (pendingPreview) previewReminder()
            } else vm.message.value = "Notifications weren’t allowed. Reminders stay off."
            pendingReminder = null
            pendingPreview = false
        }
    fun requestNotifications(kind: ReminderKind? = null, preview: Boolean = false) {
        if (ReminderNotifications.allowed(context)) {
            kind?.let { vm.reminder(it, enabled = true) }
            if (preview) previewReminder()
        } else if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            pendingReminder = kind
            pendingPreview = preview
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
            vm.message.value =
                "Enable Frem’s daily reminders in Android settings, then turn on the reminder."
        }
    }
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri
            ->
            if (uri != null)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val csv =
                                CsvExport.create(
                                    entries,
                                    days,
                                    workouts,
                                    nights,
                                    recordedActivities,
                                    nativeMetrics,
                                    noiseSessions,
                                )
                            val output =
                                context.contentResolver.openOutputStream(uri)
                                    ?: error("Couldn’t open the export location.")
                            output.bufferedWriter().use { it.write(csv) }
                        }
                        snackbar.showSnackbar("Your health data was exported.")
                    } catch (e: Exception) {
                        snackbar.showSnackbar("Export failed: ${e.message}")
                    }
                }
        }
    fun request(set: Set<String>) {
        if (health.available && set.isNotEmpty()) {
            requestingBackground = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in set
            permissions.launch(set)
        } else
            vm.message.value =
                "This Health Connect feature is unavailable on your device. Check for Android system updates."
    }
    fun openHealthSettings() {
        val intents =
            listOf(
                Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS")
                    .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName),
                Intent("android.health.connect.action.HEALTH_CONNECT_SETTINGS"),
                Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
                    .setPackage("com.google.android.apps.healthdata"),
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.google.android.apps.healthdata"),
                ),
            )
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // Older Android versions use the Health Connect app instead of system settings.
            }
        }
        vm.message.value = "Open Health Connect from Android settings."
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }
    LaunchedEffect(recordingDestination) {
        if (recordingDestination) {
            openRecorder()
            vm.recordingDestination.value = false
        }
    }
    LaunchedEffect(activeRecording?.id) {
        activeRecording?.id?.let { recordingSessionId = it }
    }
    LaunchedEffect(recordingSessionId, recordedActivities, navigation.current) {
        if (navigation.current == FremDestination.RECORDING) {
            recordedActivities
                .firstOrNull { it.id == recordingSessionId && it.state == RecordingState.FINISHED }
                ?.let { openActivity(it.id) }
        }
    }
    LaunchedEffect(reminderDestination) {
        reminderDestination?.let {
            when (it) {
                ReminderKind.WEIGHT -> {
                    navigation.navigate(FremDestination.BODY)
                    logging = LogKind.WEIGHT
                }
                ReminderKind.WATER -> {
                    navigation.navigate(FremDestination.HEALTH)
                    logging = LogKind.HYDRATION
                }
                ReminderKind.STEPS -> {
                    navigation.navigate(FremDestination.TODAY)
                    metric = Metric.STEPS
                }
            }
            vm.reminderDestination.value = null
        }
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            now = LocalDateTime.now()
            while (true) {
                delay(60_000)
                now = LocalDateTime.now()
                vm.refresh()
            }
        }
    }

    FremTheme(preferences) {
        Scaffold(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (
                    activeRecording != null &&
                        !immersive &&
                        navigation.current != FremDestination.ACTIVITY
                ) {
                    ExtendedFloatingActionButton(
                        onClick = ::openRecorder,
                        icon = { Icon(activeRecording!!.type.icon, null) },
                        text = { Text("Return to ${activeRecording!!.type.label.lowercase()}") },
                        modifier = Modifier.testTag("active_recording"),
                    )
                }
            },
            bottomBar = {
                if (!immersive) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        val selected = navigation.backStack.last { it.topLevel }
                        val tabs =
                            listOf(
                                FremDestination.TODAY to Icons.Rounded.WbSunny,
                                FremDestination.ACTIVITY to Icons.Rounded.BarChart,
                                FremDestination.BODY to Icons.Rounded.MonitorWeight,
                                FremDestination.HEALTH to Icons.Rounded.FavoriteBorder,
                                FremDestination.SETTINGS to Icons.Rounded.Tune,
                            )
                        tabs.forEach { (destination, icon) ->
                            NavigationBarItem(
                                selected = selected == destination,
                                onClick = { navigation.navigate(destination) },
                                icon = { Icon(icon, null) },
                                label = { Text(destination.label) },
                                modifier = Modifier.testTag("navigation_${destination.name}"),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            FremNavDisplay(
                navigation = navigation,
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            ) { destination ->
                Surface(
                    Modifier.fillMaxSize()
                        .then(
                            if (immersive) Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                            else Modifier,
                        ),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (destination) {
                        FremDestination.TODAY ->
                            TodayScreen(
                                data,
                                onMetric = { metric = it },
                                onConnect = { connectionSheet = true },
                                onLog = { logging = it },
                                onBody = { navigation.navigate(FremDestination.BODY) },
                            )
                        FremDestination.ACTIVITY ->
                            ActivityScreen(
                                data,
                                onMetric = { metric = it },
                                onConnect = { connectionSheet = true },
                                activeRecording = activeRecording,
                                onRecord = ::openRecorder,
                                onActivity = ::openActivity,
                            )
                        FremDestination.RECORDING ->
                            RecordingScreen(
                                session = activeRecording,
                                points = routePoints,
                                preferences = preferences,
                                liveDurationMillis =
                                    recordingLive.durationMillis.takeIf {
                                        recordingLive.sessionId == activeRecording?.id
                                    },
                                speedMetersPerSecond = recordingLive.speedMetersPerSecond,
                                accuracyMeters = recordingLive.accuracyMeters,
                                lastFixAt = recordingLive.lastFixAt,
                                statusMessage = recordingMessage ?: recordingLive.message,
                                nativeMetrics =
                                    nativeMetrics.filter { it.sessionId == activeRecording?.id },
                                cadence = recordingLive.cadence,
                                sensorMessage = recordingLive.sensorMessage,
                                busy = recordingBusy || pendingActivityType != null,
                                onStart = { requestRecording(it) },
                                onPause = { vm.recordingAction { vm.recording.pauseAndAwait() } },
                                onResume = {
                                    activeRecording?.let {
                                        requestRecording(it.type, resume = true)
                                    }
                                },
                                onFinish = {
                                    vm.recordingAction {
                                        vm.recording.finishAndAwait()?.let { openActivity(it.id) }
                                    }
                                },
                                onDiscard = {
                                    vm.recordingAction {
                                        vm.recording.discardAndAwait()
                                        recordingSessionId = null
                                        navigation.navigate(FremDestination.ACTIVITY)
                                    }
                                },
                                onBack = navigation::goBack,
                            )
                        FremDestination.ACTIVITY_DETAIL -> {
                            if (selectedActivity != null) {
                                ActivityDetailScreen(
                                    session = selectedActivity,
                                    points = routePoints,
                                    preferences = preferences,
                                    nativeMetrics =
                                        nativeMetrics.filter {
                                            it.sessionId == selectedActivity.id
                                        },
                                    onBack = navigation::goBack,
                                    onEdit = { title, type ->
                                        vm.recordingAction {
                                            vm.recording.update(selectedActivity.id, title, type)
                                        }
                                    },
                                    onDelete = {
                                        vm.recordingAction {
                                            vm.recording.delete(selectedActivity.id)
                                            selectedActivityId = null
                                            navigation.navigate(FremDestination.ACTIVITY)
                                        }
                                    },
                                    onExport = {
                                        gpxActivityId = selectedActivity.id
                                        exportGpx.launch(
                                            "frem-${selectedActivity.type.name.lowercase()}-${java.time.Instant.ofEpochMilli(selectedActivity.start).atZone(java.time.ZoneId.of(selectedActivity.zoneId)).toLocalDate()}.gpx",
                                        )
                                    },
                                )
                            } else {
                                Column(
                                    Modifier.padding(24.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Text(
                                        "Activity details",
                                        style = MaterialTheme.typography.headlineLarge,
                                    )
                                    Text("This activity is loading or is no longer available.")
                                    TextButton(onClick = navigation::goBack) {
                                        Text("Back to activities")
                                    }
                                }
                            }
                        }
                        FremDestination.BODY ->
                            BodyScreen(
                                data,
                                onLog = { logging = it },
                                onEdit = {
                                    editing = it
                                    logging = it.logKind
                                },
                                onDelete = { deleteEntry = it },
                            )
                        FremDestination.HEALTH ->
                            HealthScreen(
                                data,
                                onMetric = { metric = it },
                                onConnect = { connectionSheet = true },
                                onLog = { logging = it },
                                onEdit = {
                                    editing = it
                                    logging = it.logKind
                                },
                                onDelete = { deleteEntry = it },
                                onHearing = { hearingSheet = true },
                            )
                        FremDestination.PRIVACY -> PrivacyScreen(onDone = navigation::goBack)
                        FremDestination.SETTINGS ->
                            SettingsScreen(
                                preferences,
                                granted,
                                capabilities,
                                permissionStatus,
                                vm,
                                notificationsAllowed = notificationsAllowed,
                                onReminder = { kind, enabled ->
                                    if (enabled) requestNotifications(kind)
                                    else vm.reminder(kind, enabled = false)
                                },
                                onReminderPreview = { requestNotifications(preview = true) },
                                onConnect = { connectionSheet = true },
                                onRequest = ::request,
                                onHealthSettings = ::openHealthSettings,
                                onPhone = ::enablePhoneRecording,
                                onExport = { export.launch("frem-${data.today}.csv") },
                                onPrivacy = { navigation.navigate(FremDestination.PRIVACY) },
                                onMapCache = {
                                    mapCacheDialog = true
                                    scope.launch {
                                        mapCacheDescription =
                                            runCatching { MapCache.sizeDescription(context) }
                                                .getOrDefault("Map cache unavailable")
                                    }
                                },
                                onWidget = {
                                    val manager = AppWidgetManager.getInstance(context)
                                    if (manager.isRequestPinAppWidgetSupported)
                                        manager.requestPinAppWidget(
                                            ComponentName(context, StepsWidget::class.java),
                                            null,
                                            null,
                                        )
                                    else
                                        vm.message.value =
                                            "Long-press your home screen, choose Widgets, and find Frem."
                                },
                            )
                    }
                }
            }
        }
        if (connectionSheet) {
            HealthConnectionSheet(
                capabilities,
                granted,
                onDismiss = { connectionSheet = false },
                onRequest = ::request,
                onHealthSettings = ::openHealthSettings,
                onPhone = ::enablePhoneRecording,
            )
        }
        if (recordingPermissionHelp || recordingLocationHelp) {
            val permission = recordingPermissionHelp
            AlertDialog(
                onDismissRequest = {
                    recordingPermissionHelp = false
                    recordingLocationHelp = false
                    pendingActivityType = null
                },
                icon = { Icon(Icons.Rounded.LocationOn, null) },
                title = { Text(if (permission) "Allow precise location" else "Turn on location") },
                text = {
                    Text(
                        if (permission)
                            "Accurate routes need precise location. In Android settings, allow location while using Frem and enable Precise location. Your other health features work without it."
                        else
                            "Turn on your phone’s location service to record distance and your route.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            recordingPermissionHelp = false
                            recordingLocationHelp = false
                            recordingSettings.launch(
                                if (permission)
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                else Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                            )
                        },
                    ) {
                        Text("Open settings")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            recordingPermissionHelp = false
                            recordingLocationHelp = false
                            pendingActivityType = null
                        },
                    ) {
                        Text("Not now")
                    }
                },
            )
        }
        if (mapCacheDialog) {
            AlertDialog(
                onDismissRequest = { if (!clearingMapCache) mapCacheDialog = false },
                icon = { Icon(Icons.Rounded.Map, null) },
                title = { Text("Map cache") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(mapCacheDescription)
                        Text(
                            "Previously viewed OpenStreetMap tiles are cached on this phone. Clearing them frees storage without deleting recorded activities or routes.",
                        )
                        if (clearingMapCache) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !clearingMapCache,
                        onClick = {
                            clearingMapCache = true
                            scope.launch {
                                try {
                                    MapCache.clear(context)
                                    mapCacheDescription = MapCache.sizeDescription(context)
                                    snackbar.showSnackbar("Map cache cleared.")
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    snackbar.showSnackbar("Couldn’t clear map cache: ${e.message}")
                                } finally {
                                    clearingMapCache = false
                                }
                            }
                        },
                    ) {
                        Text("Clear map cache")
                    }
                },
                dismissButton = {
                    TextButton(enabled = !clearingMapCache, onClick = { mapCacheDialog = false }) {
                        Text("Done")
                    }
                },
            )
        }
        metric?.let { selected ->
            ModalBottomSheet(
                onDismissRequest = { metric = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                MetricDetail(
                    selected,
                    data,
                    onConnect = {
                        metric = null
                        connectionSheet = true
                    },
                    onPhone = ::enablePhoneRecording,
                    onRecord = {
                        metric = null
                        openRecorder()
                    },
                )
            }
        }
        if (hearingSheet)
            ModalBottomSheet(
                onDismissRequest = { hearingSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                HearingScreen(
                    noiseSessions,
                    noiseLive,
                    ::startNoise,
                    { vm.noise.stop() },
                    { reference -> vm.noiseAction { vm.noise.calibrate(reference) } },
                    { id -> vm.noiseAction { vm.noise.delete(id) } },
                )
            }
        logging?.let { kind ->
            LogDialog(
                kind,
                preferences,
                editing,
                onDismiss = {
                    logging = null
                    editing = null
                },
                onSave = { date, value ->
                    check(fixture == null) {
                        "Logging is disabled while performance fixture data is active."
                    }
                    vm.save(kind, date, value, editing)
                },
            )
        }
        deleteEntry?.let { entry ->
            AlertDialog(
                onDismissRequest = { deleteEntry = null },
                title = { Text("Delete this ${entry.logKind.title.lowercase()} entry?") },
                text = {
                    Text(
                        "${dateLabel(entry.localDate)} · ${number(entry.value, 1)} ${entry.logKind.unit}. Its Health Connect copy will also be deleted when write access is available.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.delete(entry)
                            deleteEntry = null
                        },
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
fun TodayScreen(
    data: AppData,
    onMetric: (Metric) -> Unit,
    onConnect: () -> Unit,
    onLog: (LogKind) -> Unit,
    onBody: () -> Unit,
) {
    val listState = rememberLazyListState()
    ObserveBenchmarkScroll("today_list", listState)
    val highlights =
        remember(
            data.days,
            data.entries,
            data.preferences.stepGoal,
            data.today,
            data.sameTimeAverage,
        ) {
            Insights.highlights(
                data.days,
                data.entries,
                data.preferences.stepGoal,
                data.today,
                data.sameTimeAverage,
            )
        }
    LazyColumn(
        state = listState,
        modifier = Modifier.testTag("today_list"),
        contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "steps", contentType = "steps") {
            Card(
                onClick = { onMetric(Metric.STEPS) },
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("KEEP MOVING FORWARD", style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Rounded.NorthEast, null, Modifier.size(20.dp))
                    }
                    StepRing(
                        data.day?.steps,
                        data.preferences.stepGoal,
                        Modifier.sizeIn(maxWidth = 300.dp).fillMaxWidth().aspectRatio(1f),
                    )
                    Text(
                        if ((data.day?.steps ?: 0) >= data.preferences.stepGoal)
                            "Goal reached. That’s a step forward."
                        else if (data.day?.steps == null) "Your next step starts here."
                        else
                            "${number((data.preferences.stepGoal - (data.day?.steps ?: 0)).coerceAtLeast(0))} steps to your daily goal.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (data.granted.isEmpty() && data.day?.source != "Phone recording")
            item(key = "connect", contentType = "info") {
                InfoCard(
                    "A little movement goes a long way",
                    "Connect Health Connect to start counting phone steps and bring your health data together.",
                    Icons.Rounded.HealthAndSafety,
                    "Connect your health",
                    onConnect,
                )
            }
        item(key = "distance-energy", contentType = "metrics") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    Metric.DISTANCE,
                    Metric.DISTANCE.value(data.day),
                    data.preferences,
                    Modifier.weight(1f),
                    if (data.day?.distanceSource == "Phone recording")
                        "Estimated · ${Metric.DISTANCE.unit(data.preferences)}"
                    else null,
                ) {
                    onMetric(Metric.DISTANCE)
                }
                MetricTile(
                    Metric.ACTIVE_ENERGY,
                    Metric.ACTIVE_ENERGY.value(data.day),
                    data.preferences,
                    Modifier.weight(1f),
                    "Estimated · kcal",
                ) {
                    onMetric(Metric.ACTIVE_ENERGY)
                }
            }
        }
        item(key = "highlights", contentType = "section") {
            SectionTitle("Highlights", "Small patterns. Meaningful progress.")
        }
        if (highlights.isEmpty())
            item(key = "empty-highlights", contentType = "info") {
                InfoCard(
                    "Your story is taking shape",
                    "Highlights appear as you build a history. Weekly comparisons need 14 complete days; shorter patterns can appear sooner.",
                    Icons.Rounded.AutoAwesome,
                )
            }
        items(
            highlights,
            key = { "highlight-${it.metric}-${it.title}" },
            contentType = { "highlight" },
        ) { h ->
            InfoCard(
                h.title,
                h.detail,
                Icons.Rounded.AutoAwesome,
                "Explore ${h.metric.lowercase()}",
            ) {
                if (h.metric == "Weight") onBody()
                else onMetric(if (h.metric == "Distance") Metric.DISTANCE else Metric.STEPS)
            }
        }
        item(key = "check-in", contentType = "section") { SectionTitle("A quick check-in") }
        item(key = "weight", contentType = "weight") {
            Card(
                onClick = onBody,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
            ) {
                Row(
                    Modifier.padding(20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.MonitorWeight,
                        null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text("Weight", style = MaterialTheme.typography.titleMedium)
                        Text(
                            data.latestWeight?.let { dateLabel(it.localDate) }
                                ?: "A simple daily entry",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        data.latestWeight?.let {
                            number(
                                if (data.preferences.imperial) it.value * 2.2046226 else it.value,
                                1,
                            ) + if (data.preferences.imperial) " lb" else " kg"
                        } ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
        item(key = "logging", contentType = "buttons") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { onLog(LogKind.WEIGHT) }, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Log weight")
                }
                FilledTonalButton(onClick = { onLog(LogKind.HYDRATION) }, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.WaterDrop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Log water")
                }
            }
        }
        item(key = "floors-elevation", contentType = "metrics") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    Metric.FLOORS,
                    Metric.FLOORS.value(data.day),
                    data.preferences,
                    Modifier.weight(1f),
                    data.nativeSource(Metric.FLOORS, data.today)?.let {
                        "Recorded activities · floors"
                    },
                ) {
                    onMetric(Metric.FLOORS)
                }
                MetricTile(
                    Metric.ELEVATION,
                    Metric.ELEVATION.value(data.day),
                    data.preferences,
                    Modifier.weight(1f),
                    data.nativeSource(Metric.ELEVATION, data.today)?.let {
                        "Recorded activities · ${Metric.ELEVATION.unit(data.preferences)}"
                    },
                ) {
                    onMetric(Metric.ELEVATION)
                }
            }
        }
        item(key = "source", contentType = "footer") {
            Text(
                data.day?.let {
                    "${it.source} · last refreshed " +
                        dateText(
                            java.time.Instant.ofEpochMilli(it.updatedAt)
                                .atZone(java.time.ZoneId.systemDefault()),
                            "HH:mm",
                        )
                } ?: "Your data stays on your phone.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
