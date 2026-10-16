package dk.frem.app.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
import dk.frem.app.performance.FrameDiagnostics
import dk.frem.app.performance.ObserveBenchmarkScroll
import dk.frem.app.reminders.ReminderNotifications
import dk.frem.app.widget.StepsWidget
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
) {
    val day: DaySummary? by lazy { days.find { it.date == today.toString() } }
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
    val nights by vm.nights.collectAsStateWithLifecycle()
    val hours by vm.hours.collectAsStateWithLifecycle()
    val granted by vm.granted.collectAsStateWithLifecycle()
    val sameTime by vm.sameTimeAverage.collectAsStateWithLifecycle()
    val notificationsAllowed by vm.notificationsAllowed.collectAsStateWithLifecycle()
    val capabilities by vm.healthCapabilities.collectAsStateWithLifecycle()
    val reminderDestination by vm.reminderDestination.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val today = now.toLocalDate()
    val liveData =
        remember(days, entries, workouts, nights, preferences, hours, granted, sameTime, today) {
            AppData(days, entries, workouts, nights, preferences, hours, granted, sameTime, today)
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
    var metric by remember { mutableStateOf<Metric?>(null) }
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
    val health = vm.repository.health
    val permissions =
        rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { allowed ->
            if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in allowed)
                vm.flag("background", true)
            vm.refresh()
        }
    val activityPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (allowed) vm.refresh()
            else
                vm.message.value =
                    "Physical activity access was not granted. You can still use manual logging."
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
                            val csv = CsvExport.create(entries, days, workouts, nights)
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
        if (health.available && set.isNotEmpty()) permissions.launch(set)
        else
            vm.message.value =
                "This Health Connect feature is unavailable on your device. Check for Android system updates."
    }
    fun openHealthSettings() {
        val intent = Intent("android.health.connect.action.HEALTH_CONNECT_SETTINGS")
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=com.google.android.apps.healthdata"),
                    ),
                )
            } catch (_: Exception) {
                vm.message.value = "Open Health Connect from Android settings."
            }
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
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
            bottomBar = {
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
            },
        ) { padding ->
            FremNavDisplay(
                navigation = navigation,
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            ) { destination ->
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                            )
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
                            )
                        FremDestination.PRIVACY -> PrivacyScreen(onDone = navigation::goBack)
                        FremDestination.SETTINGS ->
                            SettingsScreen(
                                preferences,
                                granted,
                                capabilities,
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
                                onPhone = {
                                    activityPermission.launch(
                                        Manifest.permission.ACTIVITY_RECOGNITION,
                                    )
                                },
                                onExport = { export.launch("frem-${data.today}.csv") },
                                onPrivacy = { navigation.navigate(FremDestination.PRIVACY) },
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
            var selected by
                remember(capabilities.groups) { mutableStateOf(capabilities.groups.keys.toSet()) }
            ModalBottomSheet(
                onDismissRequest = { connectionSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                LazyColumn(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text("Connect your health", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Choose what Frem can read. Body and water entries can also sync back to Health Connect.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(
                        capabilities.groups.keys.toList(),
                        key = { it },
                        contentType = { "permission" },
                    ) { name ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                name in selected,
                                onCheckedChange = {
                                    selected = if (it) selected + name else selected - name
                                },
                            )
                            Text(
                                name,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = {
                                connectionSheet = false
                                request(
                                    selected.flatMap { capabilities.groups[it].orEmpty() }.toSet(),
                                )
                            },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Continue to Health Connect")
                        }
                        TextButton(
                            onClick = {
                                connectionSheet = false
                                activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Use phone recording instead")
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
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
                )
            }
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
                ) {
                    onMetric(Metric.FLOORS)
                }
                MetricTile(
                    Metric.ELEVATION,
                    Metric.ELEVATION.value(data.day),
                    data.preferences,
                    Modifier.weight(1f),
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
