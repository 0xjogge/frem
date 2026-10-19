package dk.frem.app.maps

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MapCacheFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun countsOnlyTheResolvedAmbientDatabaseAndSqliteSidecars() {
        val directory = temporary.newFolder("resolved-maplibre-resources")
        File(directory, "mbgl-offline.db").writeBytes(ByteArray(100))
        File(directory, "mbgl-offline.db-wal").writeBytes(ByteArray(20))
        File(directory, "mbgl-offline.db-shm").writeBytes(ByteArray(30))
        File(directory, "mbgl-offline.db-journal").writeBytes(ByteArray(40))
        File(directory, "frem.db").writeBytes(ByteArray(400))
        File(directory, "tracks.gpx").writeBytes(ByteArray(500))
        val nested = File(directory, "other").apply { mkdir() }
        File(nested, "mbgl-offline.db").writeBytes(ByteArray(600))

        assertEquals(190L, ambientCacheSizeBytes(directory))
        assertEquals(400L, File(directory, "frem.db").length())
        assertEquals(500L, File(directory, "tracks.gpx").length())
    }

    @Test
    fun missingCacheAndDirectoriesWithDatabaseNamesAreNotCounted() {
        val directory = temporary.newFolder()
        File(directory, "mbgl-offline.db-wal").mkdir()
        assertEquals(0L, ambientCacheSizeBytes(directory))
        assertEquals(0L, ambientCacheSizeBytes(File(directory, "not-created")))
    }
}
