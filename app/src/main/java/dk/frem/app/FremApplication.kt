package dk.frem.app

import android.app.Application
import androidx.room.Room
import dk.frem.app.data.*
import dk.frem.app.recording.RecordingRepository

class FremApplication : Application() {
    val database: FremDatabase by lazy {
        Room.databaseBuilder(this, FremDatabase::class.java, "frem.db")
            .addMigrations(
                FremDatabase.MIGRATION_1_2,
                FremDatabase.MIGRATION_2_3,
                FremDatabase.MIGRATION_3_4,
                FremDatabase.MIGRATION_4_5,
                FremDatabase.MIGRATION_5_6,
            )
            .build()
    }

    val recording: RecordingRepository by lazy { RecordingRepository(this, database) }

    val repository: FremRepository by lazy {
        FremRepository(
            this,
            database,
            PreferencesStore(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Recovery runs once per application process, before recording commands are accepted.
        recording
    }
}
