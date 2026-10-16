package dk.frem.app

import android.app.Application
import androidx.room.Room
import dk.frem.app.data.*

class FremApplication : Application() {
    val repository: FremRepository by lazy {
        FremRepository(
            this,
            Room.databaseBuilder(this, FremDatabase::class.java, "frem.db")
                .addMigrations(FremDatabase.MIGRATION_1_2)
                .build(),
            PreferencesStore(this),
        )
    }
}
