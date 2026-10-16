package dk.frem.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dk.frem.app.data.*
import kotlin.math.roundToInt

enum class Metric(val title: String, val unit: String, val icon: ImageVector) {
    STEPS("Steps", "steps", Icons.AutoMirrored.Rounded.DirectionsWalk),
    DISTANCE("Distance", "km", Icons.Rounded.Route),
    ACTIVE_ENERGY("Active energy", "kcal", Icons.Rounded.LocalFireDepartment),
    TOTAL_ENERGY("Total energy", "kcal", Icons.Rounded.Bolt),
    FLOORS("Floors climbed", "floors", Icons.Rounded.Stairs),
    ELEVATION("Elevation gained", "m", Icons.Rounded.Landscape),
    ACTIVE_MINUTES("Active minutes", "min", Icons.Rounded.Timer),
    CADENCE("Step cadence", "steps/min", Icons.Rounded.Speed),
    HEART("Heart rate", "bpm", Icons.Rounded.Favorite),
    RESTING_HEART("Resting heart rate", "bpm", Icons.Rounded.MonitorHeart),
    SLEEP("Sleep", "min", Icons.Rounded.Bedtime),
    WATER("Water", "ml", Icons.Rounded.WaterDrop);

    fun value(day: DaySummary?): Double? =
        when (this) {
            STEPS -> day?.steps?.toDouble()
            DISTANCE -> day?.distance?.div(1000)
            ACTIVE_ENERGY -> day?.activeCalories
            TOTAL_ENERGY -> day?.totalCalories
            FLOORS -> day?.floors
            ELEVATION -> day?.elevation
            ACTIVE_MINUTES -> day?.activeMinutes
            CADENCE -> day?.cadence
            HEART -> day?.heartAverage
            RESTING_HEART -> day?.restingHeart?.toDouble()
            SLEEP -> day?.sleepMinutes
            WATER -> day?.waterMl
        }

    fun unit(preferences: Preferences): String =
        if (preferences.imperial)
            when (this) {
                DISTANCE -> "mi"
                ELEVATION -> "ft"
                WATER -> "fl oz"
                else -> unit
            }
        else unit

    fun displayValue(value: Double?, preferences: Preferences): Double? = value?.let {
        if (!preferences.imperial) it
        else
            when (this) {
                DISTANCE -> it / 1.609344
                ELEVATION -> it * 3.28084
                WATER -> it / 29.5735296
                else -> it
            }
    }

    fun formatted(value: Double?, preferences: Preferences): String =
        if (this == SLEEP) durationLabel(value)
        else number(displayValue(value, preferences), if (this == DISTANCE) 1 else 0)
}

@Composable
fun SectionTitle(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun InfoCard(
    title: String,
    description: String,
    icon: ImageVector = Icons.Rounded.Info,
    action: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier =
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action?.let {
                Text(
                    it + "  →",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun MetricTile(
    metric: Metric,
    value: Double?,
    preferences: Preferences,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Icon(
                    metric.icon,
                    null,
                    tint =
                        if (metric == Metric.ACTIVE_ENERGY || metric == Metric.TOTAL_ENERGY)
                            MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
                )
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForward,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                metric.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                metric.formatted(value, preferences),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                subtitle
                    ?: if (value == null) "No data yet"
                    else if (metric == Metric.ACTIVE_ENERGY || metric == Metric.TOTAL_ENERGY)
                        "Estimated · kcal"
                    else metric.unit(preferences),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StepRing(steps: Long?, goal: Int, modifier: Modifier = Modifier) {
    val target = ((steps ?: 0).toFloat() / goal).coerceIn(0f, 1f)
    val progress = animateFloatAsState(target, label = "step progress")
    val color = MaterialTheme.colorScheme.onPrimaryContainer
    val track = color.copy(alpha = .20f)
    Box(
        modifier.semantics {
            contentDescription =
                if (steps == null) "No steps recorded"
                else "$steps of $goal steps, ${(target * 100).roundToInt()} percent"
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val stroke = 17.dp.toPx()
            val diameter = (size.minDimension - stroke).coerceAtLeast(0f)
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            val bounds = Size(diameter, diameter)
            drawArc(
                track,
                135f,
                270f,
                false,
                topLeft,
                bounds,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (progress.value > 0)
                drawArc(
                    color,
                    135f,
                    progress.value * 270f,
                    false,
                    topLeft,
                    bounds,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Rounded.DirectionsWalk,
                null,
                tint = color,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(number(steps), style = MaterialTheme.typography.displayLarge, color = color)
            Text("of ${number(goal)} steps", color = color)
        }
    }
}

@Composable
fun HistoryChart(
    points: List<Pair<String, Double?>>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    line: Boolean = false,
    onSelect: (Int) -> Unit = {},
) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val valid = remember(points) { points.mapNotNull { it.second } }
    if (valid.isEmpty()) {
        Box(
            modifier.fillMaxWidth().height(150.dp).semantics {
                contentDescription = "No recorded values in this period"
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No recorded values in this period",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val max = (valid.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    val min = if (line) (valid.minOrNull() ?: 0.0) * .98 else 0.0
    val range = (max * 1.02 - min).coerceAtLeast(1.0)
    val description =
        if (valid.isEmpty()) "No recorded values"
        else
            "${points.size} periods, ${valid.size} with data. Lowest ${number(valid.min())}, highest ${number(valid.max())}."
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                number(max, if (max < 20) 1 else 0),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (line) "7-day trend" else "Tap to explore",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(
            Modifier.fillMaxWidth()
                .height(155.dp)
                .testTag("history_chart")
                .semantics { contentDescription = description }
                .pointerInput(points) {
                    detectTapGestures { p ->
                        if (points.isNotEmpty())
                            onSelect(
                                (p.x / size.width * points.size).toInt().coerceIn(points.indices),
                            )
                    }
                },
        ) {
            for (i in 0..3) {
                val y = size.height * i / 3
                drawLine(track.copy(alpha = .6f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            val width = size.width / points.size.coerceAtLeast(1)
            var last: Offset? = null
            points.forEachIndexed { i, (_, value) ->
                val x = width * (i + .5f)
                if (value == null) {
                    last = null
                    return@forEachIndexed
                }
                val y =
                    (size.height - ((value - min) / range * (size.height - 10)))
                        .toFloat()
                        .coerceIn(0f, size.height)
                if (line) {
                    val current = Offset(x, y)
                    last?.let { drawLine(color, it, current, 3.dp.toPx(), StrokeCap.Round) }
                    drawCircle(color, 3.dp.toPx(), current)
                    last = current
                } else {
                    val bar = (width * .65f).coerceAtMost(25.dp.toPx())
                    if (value == 0.0)
                        drawCircle(color, 2.dp.toPx(), Offset(x, size.height - 2.dp.toPx()))
                    else
                        drawRoundRect(
                            color,
                            Offset(x - bar / 2, y),
                            Size(bar, size.height - y),
                            CornerRadius(4.dp.toPx()),
                        )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                points.firstOrNull()?.first ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                points.lastOrNull()?.first ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
