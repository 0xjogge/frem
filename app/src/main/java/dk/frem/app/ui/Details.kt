package dk.frem.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dk.frem.app.BuildConfig
import dk.frem.app.data.*
import dk.frem.app.performance.FrameDiagnostics
import dk.frem.app.performance.ObserveBenchmarkScroll
import dk.frem.app.performance.ObserveDialogFrames
import java.time.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MetricDetail(metric: Metric, data: AppData, onConnect: () -> Unit) {
    ObserveDialogFrames()
    val listState = rememberLazyListState()
    ObserveBenchmarkScroll("metric_history_list", listState)
    var range by remember {
        mutableStateOf(
            if (BuildConfig.FRAME_DIAGNOSTICS) FrameDiagnostics.initialMetricRange else "W",
        )
    }
    var chosenEnd by remember { mutableStateOf<LocalDate?>(null) }
    val end = chosenEnd ?: data.today
    var selectedIndex by remember(metric, range, end) { mutableStateOf<Int?>(null) }
    val count =
        when (range) {
            "D" -> 1
            "M" -> 30
            "Y" -> 365
            else -> 7
        }
    val start = end.minusDays(count.toLong() - 1)
    val byDate = remember(data.days) { data.days.associateBy { it.date } }
    val waterByDate =
        remember(metric, data.entries) {
            if (metric != Metric.WATER) emptyMap()
            else
                data.entries
                    .asSequence()
                    .filter { it.kind == LogKind.HYDRATION.name }
                    .groupBy { it.date }
                    .mapValues { (_, entries) -> entries.sumOf { it.value } }
        }
    val sleepByDate =
        remember(metric, data.nights) {
            val zone = ZoneId.systemDefault()
            if (metric != Metric.SLEEP) emptyMap()
            else
                data.nights
                    .groupBy { Instant.ofEpochMilli(it.end).atZone(zone).toLocalDate().toString() }
                    .mapValues { (_, nights) -> nights.sumOf { it.asleepMinutes } }
        }
    fun value(date: LocalDate): Double? =
        when (metric) {
            Metric.WATER -> waterByDate[date.toString()] ?: metric.value(byDate[date.toString()])
            Metric.SLEEP -> sleepByDate[date.toString()]
            else -> metric.value(byDate[date.toString()])
        }
    val daily =
        remember(metric, start, count, byDate, waterByDate, sleepByDate) {
            (0 until count).map { start.plusDays(it.toLong()).let { date -> date to value(date) } }
        }
    val chart: List<Pair<String, Double?>> =
        remember(metric, range, end, daily, data.hours, data.today) {
            when {
                range == "D" &&
                    metric == Metric.STEPS &&
                    end == data.today &&
                    data.hours.isNotEmpty() ->
                    (0..23).map { hour ->
                        "%02d:00".format(hour) to
                            data.hours.find { it.hour == hour }?.steps?.toDouble()
                    }
                range == "Y" ->
                    daily
                        .groupBy { YearMonth.from(it.first) }
                        .map { (month, list) ->
                            month.month.name.take(3) + " " + month.year to
                                list
                                    .mapNotNull { it.second }
                                    .takeIf { it.isNotEmpty() }
                                    ?.let {
                                        if (
                                            metric == Metric.HEART ||
                                                metric == Metric.RESTING_HEART ||
                                                metric == Metric.CADENCE
                                        )
                                            it.average()
                                        else it.sum()
                                    }
                        }
                else ->
                    daily.map { (date, v) ->
                        dateText(date, if (range == "W") "EEE" else "d MMM") to v
                    }
            }
        }
    val recorded = remember(daily) { daily.mapNotNull { it.second } }
    val historyRows =
        remember(metric, daily, data.preferences.imperial) {
            daily.asReversed().map { (date, v) ->
                Triple(
                    date,
                    dateLabel(date),
                    if (v == null) "No data"
                    else
                        metric.formatted(v, data.preferences) +
                            if (metric == Metric.SLEEP) "" else " ${metric.unit(data.preferences)}",
                )
            }
        }
    val chartDisplay =
        remember(metric, chart, data.preferences.imperial) {
            chart.map { it.first to metric.displayValue(it.second, data.preferences) }
        }
    val selected = selectedIndex?.let(chart::getOrNull)
    val isAverage =
        metric == Metric.HEART || metric == Metric.RESTING_HEART || metric == Metric.CADENCE
    val summary =
        remember(recorded, isAverage) {
            recorded.takeIf { it.isNotEmpty() }?.let { if (isAverage) it.average() else it.sum() }
        }
    val periodLabel =
        remember(range, start, end) {
            if (range == "D") dateLabel(end) else "${dateText(start, "d MMM")} – ${dateLabel(end)}"
        }
    val sessions =
        remember(metric, data.nights, start, end) {
            if (metric != Metric.SLEEP) emptyList()
            else
                data.nights.filter {
                    Instant.ofEpochMilli(it.end).atZone(ZoneId.systemDefault()).toLocalDate() in
                        start..end
                }
        }
    LazyColumn(
        Modifier.fillMaxWidth()
            .fillMaxHeight(.9f)
            .navigationBarsPadding()
            .testTag("metric_history_list")
            .semantics { testTagsAsResourceId = true },
        state = listState,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "title", contentType = "title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(metric.icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(metric.title, style = MaterialTheme.typography.headlineMedium)
            }
        }
        item(key = "range", contentType = "range") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("D" to "Day", "W" to "Week", "M" to "Month", "Y" to "Year").forEach {
                    (key, title) ->
                    FilterChip(
                        range == key,
                        onClick = { range = key },
                        label = { Text(title) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item(key = "period", contentType = "period") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { chosenEnd = end.minusDays(count.toLong()) }) {
                    Icon(Icons.Rounded.ChevronLeft, "Previous period")
                }
                Text(periodLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(
                    onClick = {
                        chosenEnd = end.plusDays(count.toLong()).takeIf { it < data.today }
                    },
                    enabled = end < data.today,
                ) {
                    Icon(Icons.Rounded.ChevronRight, "Next period")
                }
            }
        }
        item(key = "chart", contentType = "chart") {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (selected != null) selected!!.first
                        else if (isAverage) "Recorded daily average"
                        else if (metric == Metric.ACTIVE_ENERGY || metric == Metric.TOTAL_ENERGY)
                            "Estimated total"
                        else "Recorded total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        metric.formatted(
                            if (selected != null) selected!!.second else summary,
                            data.preferences,
                        ),
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.testTag("metric_detail_value"),
                    )
                    Text(
                        if (metric == Metric.SLEEP) "$count-day period"
                        else metric.unit(data.preferences),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HistoryChart(chartDisplay, onSelect = { selectedIndex = it })
                    Text(
                        "${recorded.size} of $count days have recorded data. Empty days are not counted as zero.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (recorded.isEmpty())
            item(key = "empty", contentType = "info") {
                InfoCard(
                    "No ${metric.title.lowercase()} data in this period",
                    when (metric) {
                        Metric.FLOORS,
                        Metric.ELEVATION ->
                            "Frem imports these measurements from Health Connect. A compatible app or watch must record them."
                        Metric.HEART,
                        Metric.RESTING_HEART ->
                            "Connect a compatible source that measures heart rate. Your phone doesn’t supply these readings automatically."
                        Metric.SLEEP ->
                            "A compatible app or watch must record sleep sessions and share them with Health Connect."
                        Metric.ACTIVE_MINUTES ->
                            "Activity intensity needs a supported Health Connect version and a source that records it."
                        Metric.CADENCE ->
                            "Cadence is steps per minute. It appears when a source shares step-cadence records."
                        else ->
                            "Choose this data category in Health Connect and enable built-in tracking if your Android version supports it."
                    },
                    metric.icon,
                    "Connect data",
                    onConnect,
                )
            }
        if (metric == Metric.ACTIVE_ENERGY || metric == Metric.TOTAL_ENERGY)
            item(key = "energy-info", contentType = "info") {
                Text(
                    if (metric == Metric.ACTIVE_ENERGY)
                        "Active energy estimates calories burned through activity. It does not include resting energy expenditure."
                    else
                        "Total energy is a Health Connect estimate including resting and active expenditure. Health Connect may estimate resting energy even when no activity records exist. Today’s query ends at the current time.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        if (metric == Metric.ACTIVE_MINUTES)
            item(key = "intensity", contentType = "info") {
                val period = data.days.filter { it.localDate in start..end }
                fun total(read: (DaySummary) -> Double?) =
                    period.mapNotNull(read).takeIf { it.isNotEmpty() }?.sum()
                InfoCard(
                    "Activity intensity",
                    "Moderate: ${number(total { it.moderateMinutes })} min\nVigorous: ${number(total { it.vigorousMinutes })} min\nWeighted intensity: ${number(total { it.intensityMinutes?.toDouble() })} min. Vigorous minutes count double in weighted intensity.",
                    Icons.Rounded.Timer,
                )
            }
        if (metric == Metric.HEART && data.day?.heartLatest != null)
            item(key = "latest-heart", contentType = "info") {
                data.day?.let { day ->
                    InfoCard(
                        "Latest recorded reading",
                        "${day.heartLatest} bpm · ${dateText(Instant.ofEpochMilli(day.heartLatestTime!!).atZone(ZoneId.systemDefault()), "d MMM, HH:mm")}",
                        Icons.Rounded.Favorite,
                    )
                }
            }
        item(key = "daily-history", contentType = "section") { SectionTitle("Daily history") }
        items(historyRows, key = { "date-${it.first}" }, contentType = { "daily-value" }) {
            (_, label, text) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (metric == Metric.SLEEP) {
            item(key = "sleep-sessions", contentType = "section") { SectionTitle("Sleep sessions") }
            items(sessions, key = { "night-${it.id}" }, contentType = { "sleep-session" }) { night
                ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${dateLabel(Instant.ofEpochMilli(night.end).atZone(ZoneId.systemDefault()).toLocalDate())} · ${durationLabel(night.asleepMinutes)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${Instant.ofEpochMilli(night.start).atZone(ZoneId.systemDefault()).toLocalTime()} – ${Instant.ofEpochMilli(night.end).atZone(ZoneId.systemDefault()).toLocalTime()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Deep ${durationLabel(night.deepMinutes)} · REM ${durationLabel(night.remMinutes)} · Light ${durationLabel(night.lightMinutes)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDialog(
    kind: LogKind,
    preferences: Preferences,
    existing: LogEntry?,
    onDismiss: () -> Unit,
    onSave: suspend (LocalDate, Double) -> Unit,
) {
    val unit =
        if (!preferences.imperial) kind.unit
        else
            when (kind) {
                LogKind.WEIGHT -> "lb"
                LogKind.HEIGHT -> "in"
                LogKind.HYDRATION -> "fl oz"
                LogKind.BODY_FAT -> "%"
            }
    val initial =
        existing?.value?.let {
            if (!preferences.imperial) it
            else
                when (kind) {
                    LogKind.WEIGHT -> it * 2.2046226
                    LogKind.HEIGHT -> it / 2.54
                    LogKind.HYDRATION -> it / 29.5735296
                    LogKind.BODY_FAT -> it
                }
        }
    var input by remember {
        mutableStateOf(
            initial?.let {
                java.text.NumberFormat.getNumberInstance()
                    .apply {
                        isGroupingUsed = false
                        maximumFractionDigits = 2
                    }
                    .format(it)
            } ?: "",
        )
    }
    var date by remember { mutableStateOf(existing?.localDate ?: LocalDate.now()) }
    var datePicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        icon = {
            Icon(
                if (kind == LogKind.HYDRATION) Icons.Rounded.WaterDrop else Icons.Rounded.EditNote,
                null,
            )
        },
        title = { Text("${if (existing == null) "Log" else "Edit"} ${kind.title.lowercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    label = { Text("${kind.title} ($unit)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { datePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                ) {
                    Icon(Icons.Rounded.CalendarMonth, null)
                    Spacer(Modifier.width(8.dp))
                    Text(dateLabel(date))
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Saved on your phone. Shared with Health Connect when you grant write access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    val parsed = input.trim().replace(',', '.').toDoubleOrNull()
                    if (parsed == null || !parsed.isFinite() || parsed <= 0)
                        error = "Enter a positive number."
                    else {
                        val metric =
                            if (!preferences.imperial) parsed
                            else
                                when (kind) {
                                    LogKind.WEIGHT -> parsed / 2.2046226
                                    LogKind.HEIGHT -> parsed * 2.54
                                    LogKind.HYDRATION -> parsed * 29.5735296
                                    LogKind.BODY_FAT -> parsed
                                }
                        saving = true
                        scope.launch {
                            try {
                                onSave(date, metric)
                                onDismiss()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message
                                saving = false
                            }
                        }
                    }
                },
            ) {
                if (saving)
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        strokeWidth = 2.dp,
                    )
                else Text("Save entry")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
    if (datePicker) {
        val state =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                selectableDates =
                    object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                            Instant.ofEpochMilli(utcTimeMillis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate() <= LocalDate.now()
                    },
            )
        DatePickerDialog(
            onDismissRequest = { datePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let {
                            date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                        datePicker = false
                    },
                ) {
                    Text("Choose date")
                }
            },
            dismissButton = { TextButton(onClick = { datePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state)
        }
    }
}

@Composable
fun NumberPreferenceDialog(
    title: String,
    initial: Int,
    range: IntRange,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var input by remember { mutableStateOf(initial.toString()) }
    val value = input.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                input,
                onValueChange = { input = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Choose a value from ${range.first} to ${range.last}.") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { value?.let(onSave) },
                enabled = value != null && value in range,
            ) {
                Text("Save goal")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderTimeDialog(title: String, minute: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val state =
        rememberTimePickerState(
            initialHour = minute / 60,
            initialMinute = minute % 60,
            is24Hour = true,
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimeInput(state) },
        confirmButton = {
            TextButton(onClick = { onSave(state.hour * 60 + state.minute) }) { Text("Save time") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
