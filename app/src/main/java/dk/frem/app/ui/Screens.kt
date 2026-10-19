package dk.frem.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dk.frem.app.data.*
import dk.frem.app.domain.Insights
import dk.frem.app.health.HealthCapabilities
import dk.frem.app.performance.ObserveBenchmarkScroll
import java.time.*

@Composable
fun ActivityScreen(data: AppData, onMetric: (Metric) -> Unit, onConnect: () -> Unit) {
    val listState = rememberLazyListState()
    ObserveBenchmarkScroll("activity_list", listState)
    val records =
        remember(data.days, data.preferences.stepGoal, data.today) {
            Insights.records(data.days, data.preferences.stepGoal, data.today)
        }
    val recent =
        remember(data.days, data.today) {
            val byDate = data.days.associateBy { it.date }
            (6L downTo 0L).map { ago ->
                data.today.minusDays(ago).let { date ->
                    date.dayOfWeek.name.take(3) to byDate[date.toString()]?.steps?.toDouble()
                }
            }
        }
    val metrics = remember {
        listOf(
                Metric.STEPS,
                Metric.DISTANCE,
                Metric.ACTIVE_ENERGY,
                Metric.TOTAL_ENERGY,
                Metric.FLOORS,
                Metric.ELEVATION,
                Metric.ACTIVE_MINUTES,
                Metric.CADENCE,
            )
            .chunked(2)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.testTag("activity_list"),
        contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "title", contentType = "title") {
            Text("Every step counts.", style = MaterialTheme.typography.headlineLarge)
        }
        item(key = "week", contentType = "chart") {
            Card(
                onClick = { onMetric(Metric.STEPS) },
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionTitle(
                        "This week",
                        recent
                            .mapNotNull { it.second }
                            .takeIf { it.isNotEmpty() }
                            ?.let { "${number(it.sum())} recorded steps" }
                            ?: "No steps recorded yet",
                    )
                    HistoryChart(recent)
                }
            }
        }
        item(key = "records", contentType = "statistics") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    "Goal streak",
                    "${records.streak}",
                    "days",
                    Icons.Rounded.Whatshot,
                    Modifier.weight(1f),
                )
                StatCard(
                    "Daily average",
                    number(records.average),
                    "last 7 complete days",
                    Icons.AutoMirrored.Rounded.TrendingUp,
                    Modifier.weight(1f),
                )
            }
        }
        item(key = "best", contentType = "info") {
            InfoCard(
                "Your personal best",
                records.bestDay?.let {
                    "${number(it.steps)} steps on ${dateLabel(it.localDate)}. Complete days only."
                } ?: "Your most active complete day will appear here as steps are recorded.",
                Icons.Rounded.EmojiEvents,
            )
        }
        item(key = "explore", contentType = "section") { SectionTitle("Explore your activity") }
        items(metrics, key = { "metrics-${it.first().name}" }, contentType = { "metrics" }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { metric ->
                    MetricTile(
                        metric,
                        metric.value(data.day),
                        data.preferences,
                        Modifier.weight(1f),
                    ) {
                        onMetric(metric)
                    }
                }
            }
        }
        item(key = "workouts", contentType = "section") {
            SectionTitle("Workouts", "Sessions shared with Health Connect")
        }
        if (data.workouts.isEmpty())
            item(key = "connect-workouts", contentType = "info") {
                InfoCard(
                    "Make room for movement",
                    "Recorded walks, runs, gym sessions, and other workouts will appear when an app or watch shares them with Health Connect.",
                    Icons.Rounded.FitnessCenter,
                    "Connect workouts",
                    onConnect,
                )
            }
        else {
            val cutoff =
                data.today
                    .minusDays(6)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            item(key = "workout-summary", contentType = "info") {
                InfoCard(
                    "Your week in workouts",
                    "${data.workouts.count { it.start >= cutoff }} sessions · ${data.workouts.filter { it.start >= cutoff }.sumOf { it.minutes }} minutes recorded.",
                    Icons.Rounded.FitnessCenter,
                )
            }
            items(data.workouts, key = { "workout-${it.id}" }, contentType = { "workout" }) {
                workout ->
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(workout.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            remember(workout) {
                                "${workout.minutes} min · ${dateText(Instant.ofEpochMilli(workout.start).atZone(ZoneId.systemDefault()), "d MMM, HH:mm")}"
                            },
                        )
                        Text(
                            workout.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun BodyScreen(
    data: AppData,
    onLog: (LogKind) -> Unit,
    onEdit: (LogEntry) -> Unit,
    onDelete: (LogEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    ObserveBenchmarkScroll("body_list", listState)
    var historyKind by rememberSaveable { mutableStateOf(LogKind.WEIGHT) }
    var range by rememberSaveable { mutableIntStateOf(30) }
    val weights =
        remember(data.entries, data.today, range) {
            data.entries.filter {
                it.logKind == LogKind.WEIGHT && it.localDate >= data.today.minusDays(range.toLong())
            }
        }
    val trend = remember(weights) { Insights.weightTrend(weights) }
    val trendChart =
        remember(trend, data.preferences.imperial) {
            trend.map { (date, value) ->
                dateText(date, "d MMM") to
                    if (data.preferences.imperial) value * 2.2046226 else value
            }
        }
    val visible =
        remember(data.entries, historyKind) { data.entries.filter { it.logKind == historyKind } }
    val latest = data.latestWeight
    val height =
        remember(data.entries) { data.entries.firstOrNull { it.logKind == LogKind.HEIGHT } }
    val fat = remember(data.entries) { data.entries.firstOrNull { it.logKind == LogKind.BODY_FAT } }
    val bmi =
        if (latest != null && height != null)
            latest.value / ((height.value / 100) * (height.value / 100))
        else null
    LazyColumn(
        state = listState,
        modifier = Modifier.testTag("body_list"),
        contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "title", contentType = "section") {
            SectionTitle("Your body, over time", "A check-in, not a judgement.") {
                FilledTonalIconButton(onClick = { onLog(LogKind.WEIGHT) }) {
                    Icon(Icons.Rounded.Add, "Log weight")
                }
            }
        }
        item(key = "weight", contentType = "weight") {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Rounded.MonitorWeight,
                        null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text("Latest weight", style = MaterialTheme.typography.titleMedium)
                    Text(
                        number(
                            latest?.value?.let {
                                if (data.preferences.imperial) it * 2.2046226 else it
                            },
                            1,
                        ) + if (data.preferences.imperial) " lb" else " kg",
                        style = MaterialTheme.typography.displayMedium,
                    )
                    Text(
                        latest?.let {
                            "${dateLabel(it.localDate)} · ${if (it.editable) "Logged in Frem" else it.source}"
                        } ?: "Start with a date and a value.",
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    if (weights.size >= 2)
                        Text(
                            "${number((weights.first().value - weights.last().value) * if (data.preferences.imperial) 2.2046226 else 1.0, 1)} ${if (data.preferences.imperial) "lb" else "kg"} change across your selected period",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                }
            }
        }
        item(key = "trend", contentType = "chart") {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionTitle("Weight trend", "Average of recorded values within 7 days")
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(30 to "Month", 90 to "3 months", 365 to "Year", 36500 to "All")
                            .forEach { (n, label) ->
                                FilterChip(
                                    range == n,
                                    onClick = { range = n },
                                    label = { Text(label) },
                                )
                            }
                    }
                    if (trend.isEmpty())
                        Text(
                            "Log your weight to build a trend. Unrecorded days are left empty.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    else
                        HistoryChart(
                            trendChart,
                            color = MaterialTheme.colorScheme.tertiary,
                            line = true,
                        )
                }
            }
        }
        item(key = "measurements", contentType = "measurements") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BodyMeasurementCard(
                    "Height",
                    height?.value?.let {
                        number(if (data.preferences.imperial) it / 2.54 else it, 1) +
                            if (data.preferences.imperial) " in" else " cm"
                    } ?: "—",
                    Modifier.weight(1f),
                ) {
                    onLog(LogKind.HEIGHT)
                }
                BodyMeasurementCard("Body fat", number(fat?.value, 1) + " %", Modifier.weight(1f)) {
                    onLog(LogKind.BODY_FAT)
                }
            }
        }
        if (bmi != null)
            item(key = "bmi", contentType = "info") {
                InfoCard(
                    "Body mass index",
                    "${number(bmi, 1)} · calculated from your latest recorded height and weight.",
                    Icons.Rounded.Straighten,
                )
            }
        item(key = "entries", contentType = "section") { SectionTitle("Your entries") }
        item(key = "filter", contentType = "filter") {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogKind.entries.forEach { kind ->
                    FilterChip(
                        historyKind == kind,
                        onClick = { historyKind = kind },
                        label = { Text(kind.title) },
                    )
                }
            }
        }
        item(key = "log", contentType = "button") {
            OutlinedButton(onClick = { onLog(historyKind) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Log ${historyKind.title.lowercase()}")
            }
        }
        if (visible.isEmpty())
            item(key = "empty", contentType = "empty") {
                Text(
                    "No ${historyKind.title.lowercase()} entries yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        items(visible, key = { it.id }, contentType = { "entry" }) { entry ->
            EntryRow(entry, data.preferences, onEdit, onDelete)
        }
    }
}

@Composable
fun BodyMeasurementCard(title: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick,
        modifier,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(
                "Log entry  +",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun EntryRow(
    entry: LogEntry,
    preferences: Preferences,
    onEdit: (LogEntry) -> Unit,
    onDelete: (LogEntry) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val valueLabel =
        remember(entry.kind, entry.value, preferences.imperial) {
            val value =
                if (!preferences.imperial) entry.value
                else
                    when (entry.logKind) {
                        LogKind.WEIGHT -> entry.value * 2.2046226
                        LogKind.HEIGHT -> entry.value / 2.54
                        LogKind.HYDRATION -> entry.value / 29.5735296
                        LogKind.BODY_FAT -> entry.value
                    }
            val unit =
                if (!preferences.imperial) entry.logKind.unit
                else
                    when (entry.logKind) {
                        LogKind.WEIGHT -> "lb"
                        LogKind.HEIGHT -> "in"
                        LogKind.HYDRATION -> "fl oz"
                        LogKind.BODY_FAT -> "%"
                    }
            "${number(value, if (entry.logKind == LogKind.HYDRATION && !preferences.imperial) 0 else 1)} $unit"
        }
    val date = remember(entry.date) { dateLabel(entry.localDate) }
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(valueLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    date,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (!entry.editable) "${entry.source} · imported"
                    else if (entry.syncState == "synced") "Frem · synced"
                    else "Frem · saved locally",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.editable)
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            "Options for ${entry.logKind.title} on ${entry.date}",
                        )
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                menu = false
                                onEdit(entry)
                            },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menu = false
                                onDelete(entry)
                            },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                        )
                    }
                }
        }
    }
}

@Composable
fun HealthScreen(
    data: AppData,
    onMetric: (Metric) -> Unit,
    onConnect: () -> Unit,
    onLog: (LogKind) -> Unit,
    onEdit: (LogEntry) -> Unit,
    onDelete: (LogEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    ObserveBenchmarkScroll("health_list", listState)
    val night = data.nights.firstOrNull()
    val waterEntries =
        remember(data.entries, data.today) {
            data.entries.filter { it.logKind == LogKind.HYDRATION && it.localDate == data.today }
        }
    LazyColumn(
        state = listState,
        modifier = Modifier.testTag("health_list"),
        contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "title", contentType = "title") {
            Text("The bigger picture.", style = MaterialTheme.typography.headlineLarge)
        }
        item(key = "hydration", contentType = "section") {
            SectionTitle("Hydration", "Little sips add up.") {
                FilledTonalIconButton(onClick = { onLog(LogKind.HYDRATION) }) {
                    Icon(Icons.Rounded.Add, "Log water")
                }
            }
        }
        item(key = "water", contentType = "water") {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.WaterDrop,
                            null,
                            tint = MaterialTheme.colorScheme.healthAccent,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${Metric.WATER.formatted(data.waterToday, data.preferences)} ${Metric.WATER.unit(data.preferences)}",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                    LinearProgressIndicator(
                        progress = {
                            ((data.waterToday ?: 0.0) / data.preferences.waterGoal)
                                .toFloat()
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.healthAccent,
                    )
                    Text(
                        "Daily goal: ${Metric.WATER.formatted(data.preferences.waterGoal.toDouble(), data.preferences)} ${Metric.WATER.unit(data.preferences)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onLog(LogKind.HYDRATION) },
                            Modifier.weight(1f),
                        ) {
                            Text("Log water")
                        }
                        TextButton(onClick = { onMetric(Metric.WATER) }, Modifier.weight(1f)) {
                            Text("View history")
                        }
                    }
                }
            }
        }
        items(waterEntries, key = { it.id }, contentType = { "entry" }) {
            EntryRow(it, data.preferences, onEdit, onDelete)
        }
        item(key = "rest", contentType = "section") { SectionTitle("Rest & recovery") }
        if (night == null)
            item(key = "connect-sleep", contentType = "info") {
                InfoCard(
                    "Sleep belongs here too",
                    "Connect a compatible sleep app or watch to see sleep duration, bedtime, wake time, and available stages.",
                    Icons.Rounded.Bedtime,
                    "Connect sleep",
                    onConnect,
                )
            }
        else {
            item(key = "sleep", contentType = "sleep") {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Bedtime,
                            null,
                            tint = MaterialTheme.colorScheme.healthAccent,
                        )
                        Text("Last recorded sleep", style = MaterialTheme.typography.titleMedium)
                        Text(
                            durationLabel(night.asleepMinutes),
                            style = MaterialTheme.typography.headlineLarge,
                        )
                        Text(
                            remember(night) {
                                "${dateText(Instant.ofEpochMilli(night.start).atZone(ZoneId.systemDefault()), "d MMM, HH:mm")} → ${dateText(Instant.ofEpochMilli(night.end).atZone(ZoneId.systemDefault()), "HH:mm")}"
                            },
                        )
                        Text(
                            "Deep ${durationLabel(night.deepMinutes)} · REM ${durationLabel(night.remMinutes)}\nLight ${durationLabel(night.lightMinutes)} · Awake ${durationLabel(night.awakeMinutes)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            night.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { onMetric(Metric.SLEEP) }) {
                            Text("View sleep history")
                        }
                    }
                }
            }
            if (data.nights.size >= 3)
                item(key = "sleep-rhythm", contentType = "info") {
                    val rhythm =
                        remember(data.nights) {
                            val last = data.nights.take(7)
                            val starts = last.map {
                                Instant.ofEpochMilli(it.start)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalTime()
                                    .let { time ->
                                        (time.hour * 60 + time.minute).let {
                                            if (it < 12 * 60) it + 1440 else it
                                        }
                                    }
                            }
                            "Average sleep: ${durationLabel(last.map { it.asleepMinutes }.average())} across ${last.size} recorded sessions. Bedtimes varied by ${starts.max() - starts.min()} minutes."
                        }
                    InfoCard("Your recent sleep rhythm", rhythm, Icons.Rounded.NightsStay)
                }
        }
        item(key = "heart", contentType = "metrics") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    Metric.HEART,
                    Metric.HEART.value(data.day),
                    data.preferences,
                    Modifier.weight(1f),
                    data.day?.heartMin?.let { "Avg · $it–${data.day?.heartMax} bpm today" },
                ) {
                    onMetric(Metric.HEART)
                }
                MetricTile(
                    Metric.RESTING_HEART,
                    Metric.RESTING_HEART.value(data.day),
                    data.preferences,
                    Modifier.weight(1f),
                ) {
                    onMetric(Metric.RESTING_HEART)
                }
            }
        }
        item(key = "sources", contentType = "info") {
            InfoCard(
                "A home for your health data",
                "Heart rate and sleep need a source such as a watch or another app. Frem shows recorded data with its source; a missing reading stays empty.",
                Icons.Rounded.HealthAndSafety,
                "Choose data sources",
                onConnect,
            )
        }
        item(key = "hearing", contentType = "info") {
            InfoCard(
                "Hearing exposure",
                "Android does not expose measured headphone sound dose to regular apps. Frem won’t turn a volume setting into an exposure reading.",
                Icons.Rounded.Headphones,
            )
        }
    }
}

@Composable
fun SettingsScreen(
    preferences: Preferences,
    granted: Set<String>,
    capabilities: HealthCapabilities,
    vm: FremViewModel,
    notificationsAllowed: Boolean,
    onReminder: (ReminderKind, Boolean) -> Unit,
    onReminderPreview: () -> Unit,
    onConnect: () -> Unit,
    onRequest: (Set<String>) -> Unit,
    onHealthSettings: () -> Unit,
    onPhone: () -> Unit,
    onExport: () -> Unit,
    onPrivacy: () -> Unit,
    onWidget: () -> Unit,
) {
    val listState = rememberLazyListState()
    ObserveBenchmarkScroll("settings_list", listState)
    var goalEditor by remember { mutableStateOf<String?>(null) }
    var reminderTime by remember { mutableStateOf<ReminderKind?>(null) }
    LazyColumn(
        state = listState,
        modifier = Modifier.testTag("settings_list"),
        contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "title", contentType = "title") {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.testTag("settings_title"),
            )
        }
        item(key = "goals", contentType = "section") { SectionTitle("Goals & units") }
        item(key = "step-goal", contentType = "action") {
            SettingsAction(
                "Daily step goal",
                "${number(preferences.stepGoal)} steps",
                Icons.Rounded.Flag,
            ) {
                goalEditor = "step_goal"
            }
        }
        item(key = "water-goal", contentType = "action") {
            SettingsAction(
                "Daily water goal",
                "${number(preferences.waterGoal)} ml",
                Icons.Rounded.WaterDrop,
            ) {
                goalEditor = "water_goal"
            }
        }
        item(key = "units", contentType = "switch") {
            SettingsSwitch(
                "Imperial units",
                "Weight in lb, distance in mi, water in fl oz",
                preferences.imperial,
            ) {
                vm.flag("imperial", it)
            }
        }
        item(key = "appearance", contentType = "appearance") {
            Column(
                Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("Appearance")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system", "light", "dark").forEach { mode ->
                        FilterChip(
                            preferences.theme == mode,
                            onClick = { vm.text("theme", mode) },
                            label = { Text(mode.replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
            }
        }
        item(key = "dynamic", contentType = "switch") {
            SettingsSwitch(
                "Android dynamic color",
                "Use colors from your wallpaper",
                preferences.dynamicColor,
            ) {
                vm.flag("dynamic", it)
            }
        }
        item(key = "reminders", contentType = "section") {
            SettingsSectionTitle(
                "Gentle reminders",
                if (notificationsAllowed) "Daily check-ins at your chosen times"
                else "Off until you allow notifications",
            )
        }
        items(
            ReminderKind.entries,
            key = { "reminder-${it.name}" },
            contentType = { "reminder" },
        ) { kind ->
            val setting = preferences.reminder(kind)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsSwitch(
                    kind.title,
                    when (kind) {
                        ReminderKind.WEIGHT -> "Only if you haven’t logged weight today"
                        ReminderKind.WATER -> "Only while below your daily water goal"
                        ReminderKind.STEPS -> "Only while below your step goal"
                    },
                    setting.enabled,
                ) {
                    onReminder(kind, it)
                }
                TextButton(onClick = { reminderTime = kind }) {
                    Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Scheduled for %02d:%02d"
                            .format(setting.minuteOfDay / 60, setting.minuteOfDay % 60),
                    )
                }
            }
        }
        item(key = "reminder-preview", contentType = "button") {
            OutlinedButton(onClick = onReminderPreview, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.NotificationsNone, null)
                Spacer(Modifier.width(8.dp))
                Text("Send a reminder preview")
            }
        }
        item(key = "health-connect", contentType = "section") {
            SettingsSectionTitle(
                "Health Connect",
                if (capabilities.available) "Available on this phone"
                else "Not available on this phone",
            )
        }
        item(key = "connect", contentType = "button") {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Link, null)
                Spacer(Modifier.width(8.dp))
                Text("Connect health data")
            }
        }
        items(
            capabilities.groups.entries.toList(),
            key = { "permission-${it.key}" },
            contentType = { "action" },
        ) { (name, permissions) ->
            val count = permissions.count { it in granted }
            SettingsAction(
                name,
                if (count == 0) "Not connected"
                else "$count of ${permissions.size} permissions granted",
                Icons.Rounded.HealthAndSafety,
            ) {
                if (count == permissions.size) onHealthSettings() else onRequest(permissions)
            }
        }
        item(key = "background", contentType = "switch") {
            SettingsSwitch(
                "Background updates",
                "Refresh about every 30 minutes when Android permits",
                preferences.backgroundSync,
            ) { enabled ->
                if (!enabled) vm.flag("background", false)
                else onRequest(vm.repository.health.backgroundPermission())
            }
        }
        item(key = "history", contentType = "action") {
            SettingsAction(
                "Read older history",
                "Allow access beyond the default 30-day window",
                Icons.Rounded.History,
            ) {
                onRequest(vm.repository.health.historyPermission())
            }
        }
        item(key = "manage", contentType = "action") {
            SettingsAction(
                "Manage Health Connect",
                "Permissions, sources, and stored records",
                Icons.Rounded.Settings,
            ) {
                onHealthSettings()
            }
        }
        item(key = "phone", contentType = "action") {
            SettingsAction(
                "Phone steps & distance",
                "Record steps and estimated distance with Google Play services",
                Icons.AutoMirrored.Rounded.DirectionsWalk,
            ) {
                onPhone()
            }
        }
        item(key = "data", contentType = "section") {
            SettingsSectionTitle("Your data & shortcuts")
        }
        item(key = "widget", contentType = "action") {
            SettingsAction(
                "Add step widget",
                "Your goal at a glance on the home screen",
                Icons.Rounded.Widgets,
            ) {
                onWidget()
            }
        }
        item(key = "export", contentType = "action") {
            SettingsAction(
                "Export CSV",
                "Entries, daily summaries, workouts, and sleep",
                Icons.Rounded.FileDownload,
            ) {
                onExport()
            }
        }
        item(key = "privacy", contentType = "action") {
            SettingsAction(
                "Privacy & permissions",
                "How Frem stores and shares your data",
                Icons.Rounded.PrivacyTip,
            ) {
                onPrivacy()
            }
        }
        item(key = "version", contentType = "footer") {
            Text(
                "Frem 0.1 · Forward, one day at a time.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    goalEditor?.let { key ->
        NumberPreferenceDialog(
            if (key == "step_goal") "Daily step goal" else "Daily water goal (ml)",
            if (key == "step_goal") preferences.stepGoal else preferences.waterGoal,
            if (key == "step_goal") 100..250000 else 100..20000,
            onDismiss = { goalEditor = null },
        ) {
            vm.number(key, it)
            goalEditor = null
        }
    }
    reminderTime?.let { kind ->
        ReminderTimeDialog(
            kind.title,
            preferences.reminder(kind).minuteOfDay,
            onDismiss = { reminderTime = null },
        ) {
            vm.reminder(kind, minute = it)
            reminderTime = null
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 16.dp)) { SectionTitle(title, subtitle) }
}

@Composable
fun SettingsAction(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick,
        Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(Icons.Rounded.ChevronRight, null)
        }
    }
}

@Composable
fun SettingsSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Switch(
            checked,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}
