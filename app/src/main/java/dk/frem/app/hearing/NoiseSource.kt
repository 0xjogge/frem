package dk.frem.app.hearing

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

data class NoiseFrame(
    val samples: ShortArray,
    val inputKey: String,
    val recordedAt: Long,
    val silenced: Boolean = false,
)

interface NoiseSource {
    fun frames(): Flow<NoiseFrame>

    fun close()
}

internal class MicrophoneNoiseSource(private val context: Context) : NoiseSource {
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var closed = false
    @Volatile private var silenced = false

    @SuppressLint("MissingPermission")
    override fun frames(): Flow<NoiseFrame> = flow {
        val manager = context.getSystemService(AudioManager::class.java)
        val source =
            if (
                manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) ==
                    "true"
            )
                MediaRecorder.AudioSource.UNPROCESSED
            else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val format =
            AudioFormat.Builder()
                .setSampleRate(NoiseProcessor.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
        val minimum =
            AudioRecord.getMinBufferSize(
                NoiseProcessor.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        check(minimum > 0) { "48 kHz microphone input is unavailable." }
        val record =
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, NoiseProcessor.SAMPLE_RATE * 4))
                .build()
        recorder = record
        val callback =
            object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(
                    configs: MutableList<AudioRecordingConfiguration>,
                ) {
                    silenced = configs.any { it.isClientSilenced }
                }
            }
        try {
            check(!closed && record.state == AudioRecord.STATE_INITIALIZED) {
                "Microphone is unavailable."
            }
            val builtIn =
                manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                }
            check(builtIn != null && record.setPreferredDevice(builtIn)) {
                "Built-in microphone is unavailable."
            }
            record.registerAudioRecordingCallback(context.mainExecutor, callback)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone could not start."
            }
            var input: String? = null
            while (currentCoroutineContext().isActive && !closed) {
                val samples = ShortArray(NoiseProcessor.SAMPLE_RATE)
                var offset = 0
                while (offset < samples.size) {
                    currentCoroutineContext().ensureActive()
                    val count =
                        record.read(
                            samples,
                            offset,
                            samples.size - offset,
                            AudioRecord.READ_BLOCKING,
                        )
                    check(count > 0) { "Microphone input stopped." }
                    offset += count
                }
                val device = record.routedDevice ?: error("Microphone route is unavailable.")
                check(device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    "Microphone route changed. Start a new measurement."
                }
                val key =
                    "${Build.MANUFACTURER}/${Build.MODEL};source=$source;48000;input=${device.id}/${device.productName}"
                check(input == null || input == key) {
                    "Microphone configuration changed. Start a new measurement."
                }
                input = key
                emit(
                    NoiseFrame(
                        samples,
                        key,
                        System.currentTimeMillis(),
                        silenced || manager.isMicrophoneMute,
                    ),
                )
            }
        } finally {
            record.unregisterAudioRecordingCallback(callback)
            runCatching { record.stop() }
            record.release()
            recorder = null
        }
    }
        .flowOn(Dispatchers.IO)

    override fun close() {
        closed = true
        runCatching { recorder?.stop() }
    }
}
