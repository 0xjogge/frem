package dk.frem.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dk.frem.app.performance.ObserveBenchmarkScroll

@Composable
fun PrivacyScreen(onDone: () -> Unit, includeBottomInset: Boolean = false) {
    val scrollState = rememberScrollState()
    ObserveBenchmarkScroll("privacy_list", scrollState)
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .testTag("privacy_list")
                .verticalScroll(scrollState)
                .padding(pageContentPadding(includeBottomInset)),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Your health, on your phone", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Frem privacy & Health Connect permissions",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Frem reads the health categories you choose to show activity history, charts, goals, and highlights. This can include steps, distance, energy, floors, elevation, body measurements, hydration, sleep, heart rate, workouts, and activity intensity.",
            )
            Text(
                "Weight, height, body fat, and water entries you create in Frem are stored locally. When you grant write access, Frem shares these entries with Health Connect and synchronizes your edits and deletions. Imported entries remain controlled by the app that created them.",
            )
            Text(
                "Your data is stored in Frem’s private on-device database. Frem has no account, advertising, analytics, or cloud backend. CSV files are only written to a location you explicitly choose. Android app-data backup is disabled.",
            )
            Text(
                "With physical activity access, phone recording uses Google Play services to collect steps and estimated distance on your phone. Frem uses these as a fallback when Health Connect has no reading for that metric. Phone recording starts collecting after it is enabled and does not require location access.",
            )
            Text(
                "GPS activity recording is separate from daily phone recording. When you start a walk, hike, run, or ride, Frem requests precise location and records your route, distance, and recording time in its private on-device database. An Android foreground service continues recording with the screen locked. Location collection stops when you pause, finish, or discard the activity. Frem does not automatically detect or record outings.",
            )
            Text(
                "Activity maps use OpenStreetMap tiles loaded directly from tile.openstreetmap.org. Map requests reveal your connection information and the map areas you view to OpenStreetMap’s servers. Frem does not upload your recorded route to a map service. Previously viewed map tiles are cached separately, up to 100 MiB, and can be cleared in Settings without deleting your activities. Uncached map areas require a connection; GPS recording and saving work offline.",
            )
            Text(
                "Your recorded activities are stored locally and are not automatically shared with Health Connect. CSV exports include activity summaries without route coordinates. Export GPX from an activity’s details to explicitly export its precise coordinates and timestamps. Deleting an activity removes its saved route. After an interruption, Frem preserves the last saved progress and asks you to resume or finish; it does not fill in missing locations.",
            )
            Text(
                "Daily reminders are optional and scheduled on your phone. They use your saved entries and goals to skip completed check-ins, and can use permitted background reads for fresh activity data. Notification access is requested when you enable a reminder, send a preview, or start activity recording. You can turn each reminder off or manage notifications in Android settings.",
            )
            Text(
                "Background access is optional and allows periodic updates while Frem is closed. History access is optional and allows reading older records. You can change or revoke any permission in Health Connect settings. Previously cached history remains on your phone; uninstalling Frem removes its local data. Records already shared to Health Connect are managed separately there.",
            )
            Button(onClick = onDone) { Text("Done") }
        }
    }
}
