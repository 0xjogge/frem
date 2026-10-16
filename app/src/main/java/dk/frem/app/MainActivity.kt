package dk.frem.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import dk.frem.app.performance.FrameDiagnostics
import dk.frem.app.ui.FremApp
import dk.frem.app.ui.FremViewModel

class MainActivity : ComponentActivity() {
    private val viewModel by lazy { ViewModelProvider(this)[FremViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (BuildConfig.FRAME_DIAGNOSTICS) FrameDiagnostics.attach(window)
        viewModel.openReminder(intent.getStringExtra("reminder_kind"))
        setContent { FremApp(viewModel) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateNotificationAvailability()
        viewModel.updateCapabilities()
        viewModel.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.openReminder(intent.getStringExtra("reminder_kind"))
    }

    override fun onDestroy() {
        if (BuildConfig.FRAME_DIAGNOSTICS) FrameDiagnostics.detach(window)
        super.onDestroy()
    }
}
