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

    @Test
    fun phoneDistanceIsExportedInMetersWithItsOwnSourceAndEstimateLabel() {
        val csv =
            CsvExport.create(
                emptyList(),
                listOf(
                    DaySummary(
                        "2026-10-19",
                        steps = 5000,
                        distance = 2100.5,
                        source = "Phone recording + Health Connect",
                        distanceSource = "Phone recording",
                    ),
                ),
                emptyList(),
                emptyList(),
            )
        assertTrue(csv.contains("\"2100.5\",\"m\",\"Phone recording\",\"estimated\""))
        assertTrue(csv.contains("\"5000\",\"steps\",\"Phone recording + Health Connect\""))
    }
}
