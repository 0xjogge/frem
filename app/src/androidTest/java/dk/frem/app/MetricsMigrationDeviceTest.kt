package dk.frem.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dk.frem.app.data.FremDatabase
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MetricsMigrationDeviceTest {
    @Test
    fun allHistoricalSchemasUpgradeWithoutLosingDaysOrRecordings() {
        val context: Context = ApplicationProvider.getApplicationContext()
        for (version in 1..7) {
            val name = "metrics-upgrade-$version-${UUID.randomUUID()}"
            val schema =
                InstrumentationRegistry.getInstrumentation()
                    .context
                    .assets
                    .open("dk.frem.app.data.FremDatabase/$version.json")
                    .bufferedReader()
                    .use { JSONObject(it.readText()).getJSONObject("database") }
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.let { indices ->
                        for (index in 0 until indices.length()) db.execSQL(
                            indices
                                .getJSONObject(index)
                                .getString("createSql")
                                .replace("\${TABLE_NAME}", table),
                        )
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                db.execSQL(
                    "INSERT INTO days (date, source, updatedAt) VALUES ('2026-10-19', 'Health Connect', 123)",
                )
                if (version >= 4)
                    db.execSQL(
                        """
                        INSERT INTO activity_sessions
                                            (id, title, type, start, end, zoneId, state, distanceMeters, durationMillis, lastCheckpoint, activeSlot)
                                            VALUES ('saved', 'Saved walk', 'WALKING', 0, 1000, 'UTC', 'FINISHED', 10.0, 1000, 1000, NULL)
                        """
                            .trimIndent(),
                    )
                if (version >= 5)
                    db.execSQL(
                        """
                        INSERT INTO activity_day_metrics
                                            (sessionId, date, steps, cadenceSteps, cadenceMillis, moderateMillis, vigorousMillis)
                                            VALUES ('saved', '2026-10-19', 120, 120.0, 60000, 30000, 0)
                        """
                            .trimIndent(),
                    )
                db.version = version
            }
            val migrated =
                Room.databaseBuilder(context, FremDatabase::class.java, name)
                    .addMigrations(*FremDatabase.migrations)
                    .build()
            try {
                // Opening runs Room's generated full schema validation after the actual migration.
                val db = migrated.openHelper.writableDatabase
                db.query("SELECT updatedAt, elevationLost FROM days WHERE date = '2026-10-19'")
                    .use {
                        assertTrue(it.moveToFirst())
                        assertEquals(123L, it.getLong(0))
                        assertTrue(it.isNull(1))
                    }
                if (version >= 4)
                    db.query(
                            "SELECT title, descentMeters FROM activity_sessions WHERE id = 'saved'",
                        )
                        .use {
                            assertTrue(it.moveToFirst())
                            assertEquals("Saved walk", it.getString(0))
                            assertTrue(it.isNull(1))
                        }
                if (version >= 5)
                    db.query("SELECT steps FROM activity_day_metrics WHERE sessionId = 'saved'")
                        .use {
                            assertTrue(it.moveToFirst())
                            assertEquals(120L, it.getLong(0))
                        }
            } finally {
                migrated.close()
                context.deleteDatabase(name)
            }
        }
    }
}
