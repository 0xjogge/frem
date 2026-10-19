package dk.frem.app

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dk.frem.app.data.*
import dk.frem.app.health.HealthRepository
import dk.frem.app.ui.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class FremDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun logDialogAcceptsDecimalCommaAndConvertsImperialValues() {
        var saved: Double? = null
        compose.setContent {
            FremTheme {
                LogDialog(
                    LogKind.WEIGHT,
                    Preferences(imperial = true),
                    null,
                    {},
                    { _, value -> saved = value },
                )
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("176,37")
        compose.onNodeWithText("Save entry").performClick()
        compose.waitUntil(5000) { saved != null }
        assertEquals(80.0, saved!!, .01)
    }

    @Test
    fun logDialogRejectsInvalidValuesWithoutSaving() {
        var saved = false
        compose.setContent {
            FremTheme {
                LogDialog(LogKind.WEIGHT, Preferences(), null, {}, { _, _ -> saved = true })
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("-1")
        compose.onNodeWithText("Save entry").performClick()
        compose.onNodeWithText("Enter a positive number.").assertIsDisplayed()
        assertFalse(saved)
    }

    @Test
    fun roomPersistsUpdatesAndKeepsDeleteTombstonesOutOfVisibleHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, FremDatabase::class.java).build()
        try {
            val dao = db.dao()
            val entry = LogEntry(kind = "WEIGHT", date = "2026-10-14", value = 80.0)
            dao.putEntry(entry)
            dao.putEntry(entry.copy(value = 81.0, version = 2))
            assertEquals(1, dao.entries().first().size)
            assertEquals(81.0, dao.entries().first().single().value, 0.0)
            dao.putEntry(entry.copy(syncState = "deleted"))
            assertTrue(dao.entries().first().isEmpty())
            assertEquals("deleted", dao.localEntries().single().syncState)
            dao.putDays(listOf(DaySummary("2026-10-14", steps = 0)))
            assertEquals(0L, dao.day("2026-10-14")!!.steps)
            assertNull(dao.day("2026-10-14")!!.distance)
        } finally {
            db.close()
        }
    }

    @Test
    fun healthConnectImportsGrantedMetricsAndWeightUpdatesAreIdempotent() = runBlocking {
        val health = HealthRepository(context)
        assumeTrue(health.available)
        val allowed = health.permissions()
        assumeTrue(HealthPermission.getWritePermission(WeightRecord::class) in allowed)
        assertTrue(health.import().permissions.isNotEmpty())
        val entry =
            LogEntry(
                id = "integration-${UUID.randomUUID()}",
                kind = "WEIGHT",
                date = LocalDate.now().toString(),
                value = 79.1,
            )
        var healthId: String? = null
        try {
            healthId = health.sync(entry)
            assertNotNull(healthId)
            health.sync(entry.copy(value = 79.4, version = 2))
            health.sync(entry.copy(value = 79.4, version = 2))
            val records =
                health
                    .records(
                        WeightRecord::class,
                        TimeRangeFilter.between(
                            LocalDate.now().atStartOfDay(),
                            LocalDate.now().plusDays(1).atStartOfDay(),
                        ),
                    )
                    .filter { it.metadata.clientRecordId == "frem:${entry.id}" }
            assertEquals(1, records.size)
            assertEquals(79.4, records.single().weight.inKilograms, 0.001)
        } finally {
            // Remove the temporary integration record even if an assertion fails.
            health.sync(entry.copy(healthId = healthId, syncState = "deleted"))
        }
        val remaining =
            health
                .records(
                    WeightRecord::class,
                    TimeRangeFilter.between(
                        LocalDate.now().atStartOfDay(),
                        LocalDate.now().plusDays(1).atStartOfDay(),
                    ),
                )
                .filter { it.metadata.clientRecordId == "frem:${entry.id}" }
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun migrationPreservesWeightAndActivityHistory() = runBlocking {
        for (fromVersion in 1..2) verifyHistoryMigration(fromVersion)
    }

    private suspend fun verifyHistoryMigration(fromVersion: Int) {
        val name = "migration-${UUID.randomUUID()}"
        val schemaText =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("dk.frem.app.data.FremDatabase/$fromVersion.json")
                .bufferedReader()
                .use { it.readText() }
        val schema = org.json.JSONObject(schemaText).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).apply {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                execSQL(
                    entity
                        .getString("createSql")
                        .replace("\${TABLE_NAME}", entity.getString("tableName")),
                )
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) execSQL(setup.getString(i))
            execSQL(
                "INSERT INTO entries (id,kind,date,value,zone,version,source,healthId,syncState,timestamp) VALUES ('migration-weight','WEIGHT','2026-10-14',81.2,'Europe/Copenhagen',1,'Frem',NULL,'pending',0)",
            )
            execSQL(
                "INSERT INTO days (date,steps,distance,source,updatedAt) VALUES ('2026-10-14',1234,850.5,'Health Connect',0)",
            )
            version = fromVersion
            close()
        }
        val migrated =
            Room.databaseBuilder(context, FremDatabase::class.java, name)
                .addMigrations(FremDatabase.MIGRATION_1_2, FremDatabase.MIGRATION_2_3)
                .build()
        try {
            assertEquals(81.2, migrated.dao().entry("migration-weight")!!.value, .001)
            val day = migrated.dao().day("2026-10-14")!!
            assertEquals(1234L, day.steps)
            assertEquals(850.5, day.distance!!, .001)
            assertNull(day.moderateMinutes)
            assertNull(day.distanceSource)
            migrated.dao().putDays(listOf(day.copy(distanceSource = "Phone recording")))
            assertEquals("Phone recording", migrated.dao().day(day.date)!!.distanceSource)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
