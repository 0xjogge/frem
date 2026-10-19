package dk.frem.app.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull

sealed interface RecordingSensorEvent {
    data class Step(val elapsedNanos: Long) : RecordingSensorEvent

    data class Barrier(val completion: CompletableDeferred<Unit>) : RecordingSensorEvent
}

interface RecordingSensorSource {
    fun events(): Flow<RecordingSensorEvent>

    suspend fun flush() {}

    fun close()
}

internal class AndroidRecordingSensorSource(
    private val context: Context,
    private val type: ActivityType,
) : RecordingSensorSource {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: SensorEventListener2? = null
    private var closeFlow: (() -> Unit)? = null
    private var barrier: CompletableDeferred<Unit>? = null
    private var sendBarrier: (() -> Unit)? = null
    private var closed = false

    override fun events(): Flow<RecordingSensorEvent> = callbackFlow {
        if (closed) {
            close()
            return@callbackFlow
        }
        val candidate =
            object : SensorEventListener2 {
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR && event.values[0] == 1f) {
                        if (!trySend(RecordingSensorEvent.Step(event.timestamp)).isSuccess)
                            close(IllegalStateException("Step delivery fell behind."))
                    }
                }

                override fun onFlushCompleted(sensor: Sensor?) {
                    sendBarrier?.invoke()
                }
            }
        listener = candidate
        closeFlow = { close() }
        sendBarrier = {
            barrier?.let { completion ->
                barrier = null
                if (!trySend(RecordingSensorEvent.Barrier(completion)).isSuccess)
                    completion.complete(Unit)
            }
        }
        try {
            if (
                !type.cycling &&
                    context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
                        PackageManager.PERMISSION_GRANTED
            ) {
                manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
                    manager.registerListener(
                        candidate,
                        it,
                        SensorManager.SENSOR_DELAY_NORMAL,
                        5_000_000,
                        handler,
                    )
                }
            }
            awaitClose {
                manager.unregisterListener(candidate)
                listener = null
                barrier?.complete(Unit)
                barrier = null
            }
        } finally {
            manager.unregisterListener(candidate)
            closeFlow = null
            sendBarrier = null
        }
    }

    override suspend fun flush() {
        val completion = CompletableDeferred<Unit>()
        handler.post {
            barrier = completion
            val active = listener
            if (active == null || !manager.flush(active))
                sendBarrier?.invoke() ?: completion.complete(Unit)
        }
        withTimeoutOrNull(1_500) { completion.await() }
    }

    override fun close() {
        closed = true
        closeFlow?.invoke()
    }
}
