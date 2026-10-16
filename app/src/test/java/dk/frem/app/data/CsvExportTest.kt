package dk.frem.app.data

import org.junit.Assert.*
import org.junit.Test

class CsvExportTest {
    @Test
    fun exportPreservesUnitsAndEscapesSourceNamesWithoutInventingMissingMetrics() {
        val entry =
            LogEntry(kind = "WEIGHT", date = "2026-10-14", value = 80.5, source = "Scale, \"home\"")
        val csv =
            CsvExport.create(
                listOf(entry),
                listOf(DaySummary("2026-10-14", steps = 0, distance = null)),
                emptyList(),
                emptyList(),
            )
        assertTrue(csv.contains("\"80.5\",\"kg\""))
        assertTrue(csv.contains("\"Scale, \"\"home\"\"\""))
        assertTrue(csv.contains("\"steps\",\"2026-10-14\",,,\"0\""))
        assertFalse(csv.contains("\"distance\""))
    }
}
