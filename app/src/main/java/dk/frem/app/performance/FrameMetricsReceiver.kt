package dk.frem.app.performance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class FrameMetricsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val command = intent.getStringExtra("command") ?: "stop"
        if (command == "fixture") {
            FrameDiagnostics.fixture.value =
                if (intent.getBooleanExtra("enabled", false))
                    FrameDiagnostics.fixture.value ?: BenchmarkFixture.create()
                else null
            result.resultData = "fixture configured"
            result.finish()
            return
        }
        if (command == "scroll") {
            FrameDiagnostics.scroll(
                intent.getStringExtra("list")!!,
                intent.getFloatExtra("velocity", 8000f),
            ) {
                result.resultData = "scrolled"
                result.finish()
            }
            return
        }
        if (command == "navigate") {
            FrameDiagnostics.navigate(intent.getStringExtra("page")!!) {
                result.resultData = "navigated"
                result.finish()
            }
            return
        }
        FrameDiagnostics.command(command, intent.getBooleanExtra("includeFirstDraw", false)) {
            result.resultData = it
            result.finish()
        }
    }
}
