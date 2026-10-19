package dk.frem.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dk.frem.app.hearing.*
import java.time.Instant
import java.time.ZoneId

@Composable
fun HearingScreen(
    sessions: List<NoiseSession>,
    live: NoiseLive,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCalibrate: (Double) -> Unit,
    onDelete: (String) -> Unit,
) {
    var reference by rememberSaveable { mutableStateOf("") }
    val running = live.session?.state == "RECORDING"
    val recent = sessions.filter { it.state != "RECORDING" }.take(7).asReversed()
    var selected by remember { mutableStateOf<Int?>(null) }
    LazyColumn(
        Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding().testTag("hearing_list"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            SectionTitle("Environmental noise", "User-started microphone measurements")
            Text(
                "Frem processes sound on your phone and saves numerical summaries, never audio. Keep the built-in microphone uncovered and in the same position as your reference meter during calibration.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            if (running)
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().testTag("noise_finish"),
                ) {
                    Text("Finish measurement")
                }
            else
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().testTag("noise_start"),
                ) {
                    Text("Start measurement")
                }
        }
        live.session?.let { session ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (running) "Current measurement" else "Last measurement",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val current =
                            live.currentDbfs?.let { value ->
                                session.calibrationOffsetDb?.plus(value) ?: value
                            }
                        Text(
                            "${number(current ?: session.leqDbA ?: session.rawLeq, 1)} ${if (session.calibrationOffsetDb != null) "dB(A) · estimated" else "dBFS(A) · uncalibrated"}",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.testTag("noise_level"),
                        )
                        NoiseSummary(session)
                        live.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        if (running)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Calibrate this input", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Measure a steady sound alongside a calibrated A-weighted sound-level meter for at least 10 seconds. Enter the meter’s equivalent level for that same interval. Calibration applies to new sessions and is tied to this microphone configuration.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        reference,
                        { reference = it },
                        label = { Text("Reference level · dB(A)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("noise_reference"),
                    )
                    val value = reference.replace(',', '.').toDoubleOrNull()
                    OutlinedButton(
                        onClick = { value?.let(onCalibrate) },
                        enabled =
                            live.calibrationReady &&
                                value != null &&
                                value.isFinite() &&
                                value in 20.0..120.0,
                        modifier = Modifier.testTag("noise_calibrate"),
                    ) {
                        Text("Save calibration for next session")
                    }
                }
            }
        item {
            InfoCard(
                "Measured exposure",
                "Calibrated sessions show estimated LAeq and dose against an 85 dB(A) / 8 h reference with a 3 dB exchange rate. Only measured intervals count. Muted, clipped, warm-up, and unavailable intervals remain unmeasured. Without calibration Frem shows dBFS(A), with no sound-pressure or dose estimate.",
                Icons.Rounded.Hearing,
            )
        }
        if (recent.isNotEmpty())
            item {
                SectionTitle("Recent calibrated sessions")
                HistoryChart(
                    recent.map {
                        dateText(
                            Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()),
                            "d MMM",
                        ) to it.leqDbA
                    },
                    onSelect = { selected = it },
                )
                selected
                    ?.let { recent.getOrNull(it) }
                    ?.let {
                        Text(
                            "${dateText(Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()), "d MMM, HH:mm")} · ${number(it.leqDbA, 1)} dB(A)",
                        )
                    }
            }
        item { SectionTitle("Noise history") }
        items(sessions.filter { it.state != "RECORDING" }, key = { it.id }) { session ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        dateText(
                            Instant.ofEpochMilli(session.start).atZone(ZoneId.systemDefault()),
                            "d MMM yyyy, HH:mm",
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (session.state == "INTERRUPTED") "Interrupted · saved measurements"
                        else "Finished",
                    )
                    NoiseSummary(session)
                    Text(
                        session.inputKey ?: "No microphone samples",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = { onDelete(session.id) }) { Text("Delete measurement") }
                }
            }
        }
        item {
            InfoCard(
                "Headphone exposure",
                "Measured headphone sound dose needs a supported device or provider interface. Android’s system dose is restricted to privileged apps, and Health Connect does not currently provide it. Environmental microphone measurements do not measure headphone exposure.",
                Icons.Rounded.Headphones,
            )
        }
    }
}

@Composable
private fun NoiseSummary(session: NoiseSession) {
    Text(
        "LAeq: ${number(session.leqDbA, 1)} dB(A) · Maximum 1 s level: ${number(session.maximumDbA, 1)} dB(A)",
    )
    Text(
        "Measured: ${number(session.measuredMillis / 1000)} s · Unmeasured: ${number(session.unmeasuredMillis / 1000)} s",
    )
    Text(
        "Measured-session dose: ${number(session.dosePercent, 2)}%",
        modifier = Modifier.testTag("noise_dose"),
    )
}
