package dk.frem.app.performance

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import dk.frem.app.BuildConfig
import dk.frem.app.ui.AppData
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Direct platform measurements for the benchmark build; R8 removes this from release. */
object FrameDiagnostics {
    private val handler by lazy {
        Handler(HandlerThread("FremFrameMetrics").apply { start() }.looper)
    }
    private val windows = WeakHashMap<Window, Window.OnFrameMetricsAvailableListener>()
    private var recording = false
    private var includeFirstDraw = false
    private var firstDrawCount = 0
    private val samples = Array(11) { LongArray(32768) }
    private var count = 0
    private var dropped = 0
    private val scrollActions = HashMap<String, (Float, () -> Unit) -> Unit>()
    var navigationAction: ((String) -> Unit)? = null
    var initialMetricRange = "W"
    val fixture = MutableStateFlow<AppData?>(null)

    fun registerScroll(name: String, action: (Float, () -> Unit) -> Unit) {
        scrollActions[name] = action
    }

    fun unregisterScroll(name: String) {
        scrollActions.remove(name)
    }

    fun scroll(name: String, velocity: Float, done: () -> Unit) {
        Handler(android.os.Looper.getMainLooper()).post {
            scrollActions[name]?.invoke(velocity, done) ?: done()
        }
    }

    fun navigate(name: String, done: () -> Unit) {
        Handler(android.os.Looper.getMainLooper()).post {
            navigationAction?.invoke(name)
            done()
        }
    }

    fun attach(window: Window) {
        if (!BuildConfig.FRAME_DIAGNOSTICS || windows.containsKey(window)) return
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropCount ->
            if (
                recording &&
                    (includeFirstDraw || metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L)
            ) {
                val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
                if (deadline > 0) {
                    if (count < samples[0].size) {
                        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) != 0L) firstDrawCount++
                        samples[0][count] = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                        samples[1][count] = deadline
                        samples[2][count] = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                        samples[3][count] = metrics.getMetric(FrameMetrics.DRAW_DURATION)
                        samples[4][count] = metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)
                        samples[5][count] = metrics.getMetric(FrameMetrics.SYNC_DURATION)
                        samples[6][count] = metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
                        samples[7][count] = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
                        samples[8][count] = metrics.getMetric(FrameMetrics.GPU_DURATION)
                        samples[9][count] = metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
                        samples[10][count] = metrics.getMetric(FrameMetrics.ANIMATION_DURATION)
                        count++
                    } else dropped++
                    dropped += dropCount
                }
            }
        }
        windows[window] = listener
        window.addOnFrameMetricsAvailableListener(listener, handler)
    }

    fun detach(window: Window) {
        windows.remove(window)?.let(window::removeOnFrameMetricsAvailableListener)
    }

    fun command(command: String, captureFirstDraw: Boolean = false, done: (String) -> Unit) {
        handler.post {
            if (command == "start") {
                count = 0
                dropped = 0
                firstDrawCount = 0
                includeFirstDraw = captureFirstDraw
                recording = true
                done("started")
            } else {
                recording = false
                fun quantile(index: Int, percent: Double): Double {
                    val values = samples[index].copyOf(count).sorted()
                    return if (values.isEmpty()) 0.0
                    else values[((values.size - 1) * percent).toInt()] / 1_000_000.0
                }
                val overruns =
                    (0 until count).map { (samples[0][it] - samples[1][it]) / 1_000_000.0 }.sorted()
                val report =
                    JSONObject().apply {
                        put("frames", count)
                        put("missedDeadlines", overruns.count { it > 0 })
                        put("droppedCallbacks", dropped)
                        put("includesFirstDraw", includeFirstDraw)
                        put("firstDrawFrames", firstDrawCount)
                        put("p50Ms", quantile(0, .50))
                        put("p95Ms", quantile(0, .95))
                        put("p99Ms", quantile(0, .99))
                        put("maxMs", quantile(0, 1.0))
                        put(
                            "deadlineMinMs",
                            samples[1].take(count).minOrNull()?.div(1_000_000.0) ?: 0.0,
                        )
                        put("maxOverrunMs", overruns.lastOrNull() ?: 0.0)
                        put("p95LayoutMs", quantile(2, .95))
                        put("p95DrawMs", quantile(3, .95))
                        put("p95DelayMs", quantile(4, .95))
                        put(
                            "overrunFrames",
                            org.json.JSONArray(
                                (0 until count)
                                    .filter { samples[0][it] > samples[1][it] }
                                    .map { i ->
                                        JSONObject().apply {
                                            put("totalMs", samples[0][i] / 1_000_000.0)
                                            put("layoutMs", samples[2][i] / 1_000_000.0)
                                            put("drawMs", samples[3][i] / 1_000_000.0)
                                            put("delayMs", samples[4][i] / 1_000_000.0)
                                            put("syncMs", samples[5][i] / 1_000_000.0)
                                            put("commandsMs", samples[6][i] / 1_000_000.0)
                                            put("swapMs", samples[7][i] / 1_000_000.0)
                                            put("gpuMs", samples[8][i] / 1_000_000.0)
                                            put("inputMs", samples[9][i] / 1_000_000.0)
                                            put("animationMs", samples[10][i] / 1_000_000.0)
                                        }
                                    },
                            ),
                        )
                    }
                done(report.toString())
            }
        }
    }
}

@Composable
fun ObserveDialogFrames() {
    if (!BuildConfig.FRAME_DIAGNOSTICS) return
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.let(FrameDiagnostics::attach)
        onDispose { window?.let(FrameDiagnostics::detach) }
    }
}

@Composable
fun ObserveBenchmarkScroll(name: String, state: ScrollableState) {
    if (!BuildConfig.FRAME_DIAGNOSTICS) return
    val scope = rememberCoroutineScope()
    val fling = ScrollableDefaults.flingBehavior()
    val currentFling = rememberUpdatedState(fling)
    DisposableEffect(name, state) {
        FrameDiagnostics.registerScroll(name) { velocity, done ->
            scope.launch {
                try {
                    state.scroll { with(currentFling.value) { performFling(velocity) } }
                } finally {
                    done()
                }
            }
        }
        onDispose { FrameDiagnostics.unregisterScroll(name) }
    }
}
