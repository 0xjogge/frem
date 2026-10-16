package dk.frem.app.ui

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/** Mutable formatters are cached per thread and locale, never shared across dispatchers. */
private class UiFormatters(val locale: Locale) {
    val numbers = HashMap<Int, NumberFormat>()
    val dates = HashMap<String, DateTimeFormatter>()
}

private val cachedFormatters = ThreadLocal<UiFormatters>()

private fun formatters(): UiFormatters {
    val locale = Locale.getDefault()
    return cachedFormatters.get()?.takeIf { it.locale == locale }
        ?: UiFormatters(locale).also(cachedFormatters::set)
}

fun number(value: Number?, decimals: Int = 0): String {
    if (value == null) return "—"
    val cache = formatters()
    return cache.numbers
        .getOrPut(decimals) {
            NumberFormat.getNumberInstance(cache.locale).apply {
                minimumFractionDigits = decimals
                maximumFractionDigits = decimals
            }
        }
        .format(value)
}

fun dateText(date: TemporalAccessor, pattern: String): String {
    val cache = formatters()
    return cache.dates
        .getOrPut(pattern) { DateTimeFormatter.ofPattern(pattern, cache.locale) }
        .format(date)
}

fun dateLabel(date: LocalDate): String = dateText(date, "d MMM yyyy")

fun durationLabel(minutes: Double?): String =
    minutes?.let { "${it.toInt() / 60}h ${it.toInt() % 60}m" } ?: "—"
