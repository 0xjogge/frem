package dk.frem.app.health

data class HealthDataType(
    val name: String,
    val readPermission: String,
    val writePermission: String? = null,
) {
    val permissions: Set<String>
        get() = setOfNotNull(readPermission, writePermission)
}

enum class HealthPermissionStatus {
    CHECKING,
    READY,
    ERROR,
}

data class HealthCapabilities(
    val available: Boolean = false,
    val groups: Map<String, List<HealthDataType>> = emptyMap(),
    val backgroundPermission: Set<String> = emptySet(),
    val historyPermission: Set<String> = emptySet(),
    val loaded: Boolean = false,
) {
    val dataTypes: List<HealthDataType>
        get() = groups.values.flatten()

    fun connected(granted: Set<String>): Boolean =
        available && dataTypes.any { type -> type.permissions.any { it in granted } }
}
