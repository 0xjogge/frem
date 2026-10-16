package dk.frem.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dk.frem.app.ui.FremTheme
import dk.frem.app.ui.PrivacyScreen

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FremTheme {
                PrivacyScreen(onDone = ::finish, includeBottomInset = true)
            }
        }
    }
}
