package dk.frem.app.domain

import dk.frem.app.data.DaySummary
import java.time.LocalDate

fun mergePhoneActivity(
    health: DaySummary?,
    phone: DaySummary,
    readSteps: Boolean,
    readDistance: Boolean,
): DaySummary {
    if (health == null || health.source == "Phone recording") return phone
    // Prefer each Health Connect metric independently, including genuine zeroes.
    // A cached value is not an import when its read permission has been revoked.
    val healthSteps = health.steps.takeIf { readSteps }
    val healthDistance = health.distance.takeIf { readDistance }
    val usedPhone =
        (healthSteps == null && phone.steps != null) ||
            (healthDistance == null && phone.distance != null)
    return health.copy(
        steps = healthSteps ?: phone.steps,
        distance = healthDistance ?: phone.distance,
        distanceSource =
            if (healthDistance != null) health.distanceSource
            else phone.distanceSource.takeIf { phone.distance != null },
        source = if (usedPhone) "Phone recording + Health Connect" else health.source,
    )
}

fun retainOlderPhoneDistance(
    health: DaySummary,
    cached: DaySummary?,
    phoneHistoryStart: LocalDate,
): DaySummary {
    // The recorder can no longer re-read these days. Keep only known phone estimates;
    // deleted Health Connect distance must still disappear from the cache.
    if (
        health.distance != null ||
            cached?.distanceSource != "Phone recording" ||
            cached.distance == null ||
            health.localDate >= phoneHistoryStart
    )
        return health
    return health.copy(
        distance = cached.distance,
        distanceSource = cached.distanceSource,
        source = "Phone recording + Health Connect",
    )
}
