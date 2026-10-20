package dk.frem.app

import android.app.Application
import androidx.room.Room
import dk.frem.app.data.*
import dk.frem.app.hearing.NoiseRepository
import dk.frem.app.recording.RecordingRepository

class FremApplication : Application() {
    val database: FremDatabase by lazy {
        Room.databaseBuilder(this, FremDatabase::class.java, "frem.db")
            .addMigrations(*FremDatabase.migrations)
            .build()
    }

    val recording: RecordingRepository by lazy { RecordingRepository(this, database) }
    val noise: NoiseRepository by lazy { NoiseRepository(this, database) }

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
