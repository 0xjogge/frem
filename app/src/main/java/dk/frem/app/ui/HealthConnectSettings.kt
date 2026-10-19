package dk.frem.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dk.frem.app.data.Preferences
import dk.frem.app.health.HealthCapabilities
import dk.frem.app.health.HealthDataType
import dk.frem.app.health.HealthPermissionStatus

internal fun LazyListScope.healthConnectSettings(
    preferences: Preferences,
    granted: Set<String>,
    capabilities: HealthCapabilities,
    permissionStatus: HealthPermissionStatus,
    onBackgroundChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onRequest: (Set<String>) -> Unit,
    onHealthSettings: () -> Unit,
) {
    val ready = capabilities.loaded && permissionStatus == HealthPermissionStatus.READY
    val connected = ready && capabilities.connected(granted)
    item(key = "health-connect", contentType = "section") {
        Column(Modifier.padding(top = 16.dp)) { SectionTitle("Health Connect") }
    }
    item(key = "health-status", contentType = "health-status") {
        HealthConnectionStatus(
            capabilities,
            permissionStatus,
            connected,
            onConnect,
            onHealthSettings,
        )
    }
    if (connected) {
        item(key = "health-access-title", contentType = "section") {
            SectionTitle(
                "Data access",
                "Read brings data into Frem. Share saves your Frem entries to Health Connect.",
            )
        }
        items(
            capabilities.groups.entries.toList(),
            key = { "permission-${it.key}" },
            contentType = { "health-access" },
        ) { (name, types) ->
            HealthDataAccessCard(name, types, granted, onRequest, onHealthSettings)
        }
        item(key = "health-sharing", contentType = "explanation") {
            Text(
                "Frem only shares weight, height, body fat, and water entries you create here, " +
                    "and only with the sharing permissions shown above. Other apps you authorize " +
                    "in Health Connect may read those entries. Frem has no cloud backend.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "health-options", contentType = "section") {
            SectionTitle("Updates & history")
        }
        item(key = "background", contentType = "switch") {
            val supported = capabilities.backgroundPermission.isNotEmpty()
            val allowed = supported && granted.containsAll(capabilities.backgroundPermission)
            SettingsSwitch(
                "Background updates",
                when {
                    !supported -> "Unavailable on this phone"
                    !allowed -> "Off · Allow background access to refresh while Frem is closed"
                    !preferences.backgroundSync -> "Off · Refreshes when you open Frem"
                    else -> "On · Refresh about every 30 minutes when Android permits"
                },
                checked = preferences.backgroundSync && allowed,
                enabled = supported,
            ) { enabled ->
                if (!enabled || allowed) onBackgroundChange(enabled)
                else onRequest(capabilities.backgroundPermission)
            }
        }
        item(key = "history", contentType = "action") {
            val supported = capabilities.historyPermission.isNotEmpty()
            val allowed = supported && granted.containsAll(capabilities.historyPermission)
            SettingsAction(
                "Older history",
                when {
                    !supported -> "Unavailable on this phone"
                    allowed -> "On · Frem can import up to a year of history"
                    else -> "Off · Uses Health Connect’s standard history window"
                },
                Icons.Rounded.History,
                enabled = supported,
            ) {
                if (allowed) onHealthSettings() else onRequest(capabilities.historyPermission)
            }
        }
        item(key = "health-revoke", contentType = "explanation") {
            Text(
                "To turn off access or disconnect, remove Frem’s permissions in Health Connect. " +
                    "This stops future access; previously saved records in Frem and Health " +
                    "Connect remain until deleted separately.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HealthConnectionStatus(
    capabilities: HealthCapabilities,
    permissionStatus: HealthPermissionStatus,
    connected: Boolean,
    onConnect: () -> Unit,
    onHealthSettings: () -> Unit,
) {
    val checking =
        !capabilities.loaded ||
            (capabilities.available && permissionStatus == HealthPermissionStatus.CHECKING)
    val failed = capabilities.available && permissionStatus == HealthPermissionStatus.ERROR
    val title =
        when {
            checking -> "Checking access…"
            !capabilities.available -> "Unavailable on this phone"
            failed -> "Couldn’t check access"
            connected -> "Connected"
            else -> "Not connected"
        }
    Card(
        Modifier.fillMaxWidth().testTag("health_connection_status"),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (connected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        checking -> Icons.Rounded.Sync
                        failed -> Icons.Rounded.ErrorOutline
                        connected -> Icons.Rounded.CheckCircle
                        else -> Icons.Rounded.LinkOff
                    },
                    null,
                )
                Text(
                    title,
                    Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                when {
                    checking -> "Checking Health Connect and your current permissions."
                    !capabilities.available ->
                        "Install or update Health Connect to choose the data Frem can access."
                    failed -> "Open Health Connect to review Frem’s current permissions."
                    connected -> "Frem can access only the data you’ve allowed below."
                    else -> "Connect to choose what Frem can read and share."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!checking) {
                if (capabilities.available && !failed && !connected) {
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Link, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Connect health data")
                    }
                } else {
                    OutlinedButton(onClick = onHealthSettings, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when {
                                connected -> "Change access"
                                failed -> "Open Health Connect"
                                else -> "Set up Health Connect"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthDataAccessCard(
    name: String,
    types: List<HealthDataType>,
    granted: Set<String>,
    onRequest: (Set<String>) -> Unit,
    onHealthSettings: () -> Unit,
) {
    var expanded by rememberSaveable(name) { mutableStateOf(false) }
    val readable = types.filter { it.readPermission in granted }
    val writable = types.filter { it.writePermission != null }
    val shared = writable.filter { it.writePermission in granted }
    Card(
        Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clickable { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    if (expanded) "Hide access details" else "Show access details",
                )
            }
            AccessSummary("Read", readable, types)
            if (writable.isEmpty()) {
                Text(
                    "Share: Never shared by Frem",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AccessSummary("Share", shared, writable)
            }
        }
        if (expanded) {
            HorizontalDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Tap Off to allow access. Tap On to change access in Health Connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                types.forEach { type ->
                    Column {
                        Text(type.name, style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AccessChip(
                                "Read",
                                type.name,
                                type.readPermission,
                                granted,
                                onRequest,
                                onHealthSettings,
                            )
                            type.writePermission?.let { permission ->
                                AccessChip(
                                    "Share",
                                    type.name,
                                    permission,
                                    granted,
                                    onRequest,
                                    onHealthSettings,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessSummary(label: String, allowed: List<HealthDataType>, all: List<HealthDataType>) {
    Text(
        "$label: ${if (allowed.isEmpty()) "Off" else allowed.joinToString { it.name }}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (allowed.isNotEmpty() && allowed.size < all.size) {
        Text(
            "${if (label == "Read") "Not read" else "Not shared"}: " +
                all.filter { it !in allowed }.joinToString { it.name },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccessChip(
    direction: String,
    name: String,
    permission: String,
    granted: Set<String>,
    onRequest: (Set<String>) -> Unit,
    onHealthSettings: () -> Unit,
) {
    val allowed = permission in granted
    val status = if (allowed) "On" else "Off"
    FilterChip(
        selected = allowed,
        onClick = { if (allowed) onHealthSettings() else onRequest(setOf(permission)) },
        label = { Text("$direction: $status") },
        modifier =
            Modifier.semantics {
                contentDescription = "$direction $name"
                stateDescription = status
            },
    )
}
