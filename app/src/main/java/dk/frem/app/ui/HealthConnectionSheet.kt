package dk.frem.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dk.frem.app.health.HealthCapabilities

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HealthConnectionSheet(
    capabilities: HealthCapabilities,
    granted: Set<String>,
    onDismiss: () -> Unit,
    onRequest: (Set<String>) -> Unit,
    onHealthSettings: () -> Unit,
    onPhone: () -> Unit,
) {
    val types = capabilities.dataTypes
    val connected = capabilities.connected(granted)
    var selected by
        remember(capabilities.groups, granted) {
            mutableStateOf(
                if (connected) types.flatMap { it.permissions }.filter { it in granted }.toSet()
                else types.map { it.readPermission }.toSet(),
            )
        }
    val requested = selected - granted
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth()
                .testTag("health_connection_choices")
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Connect your health", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Choose what Frem can read and which entries it can share. " +
                        "You’ll review these permissions in Health Connect.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (capabilities.available) {
                item { SectionTitle("Read into Frem") }
                items(
                    capabilities.groups.entries.toList(),
                    key = { "read-${it.key}" },
                    contentType = { "permission" },
                ) { (name, group) ->
                    val permissions = group.map { it.readPermission }.toSet()
                    val allSelected = selected.containsAll(permissions)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TriStateCheckbox(
                            state =
                                when {
                                    allSelected -> ToggleableState.On
                                    permissions.none { it in selected } -> ToggleableState.Off
                                    else -> ToggleableState.Indeterminate
                                },
                            onClick = {
                                selected =
                                    if (allSelected) selected - (permissions - granted)
                                    else selected + permissions
                            },
                            enabled = !granted.containsAll(permissions),
                            modifier = Modifier.semantics { contentDescription = "Read $name" },
                        )
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                item {
                    SectionTitle(
                        "Share from Frem",
                        "Optional. Save entries you create here to Health Connect, where other " +
                            "apps you authorize may read them. Sharing also syncs your edits and deletions.",
                    )
                }
                items(
                    types.filter { it.writePermission != null },
                    key = { "share-${it.writePermission}" },
                    contentType = { "permission" },
                ) { type ->
                    val permission = requireNotNull(type.writePermission)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = permission in selected,
                            onCheckedChange = {
                                selected = if (it) selected + permission else selected - permission
                            },
                            enabled = permission !in granted,
                            modifier =
                                Modifier.testTag("health_share_${type.name}").semantics {
                                    contentDescription = "Share ${type.name}"
                                },
                        )
                        Text(
                            type.name,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (connected) {
                    item {
                        TextButton(onClick = onHealthSettings) {
                            Text("Turn off existing access in Health Connect")
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        onDismiss()
                        if (capabilities.available) onRequest(requested) else onHealthSettings()
                    },
                    enabled =
                        capabilities.loaded && (!capabilities.available || requested.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (capabilities.available) "Continue to Health Connect"
                        else "Set up Health Connect",
                    )
                }
                TextButton(
                    onClick = {
                        onDismiss()
                        onPhone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Enable phone steps & distance")
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
