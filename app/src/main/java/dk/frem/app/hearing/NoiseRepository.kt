package dk.frem.app.hearing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dk.frem.app.data.FremDatabase
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NoiseRepository(private val context: Context, database: FremDatabase) {
    private val dao = database.noiseDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val ready = scope.async { dao.recover() }
    private val mutableLive = MutableStateFlow(NoiseLive())
    val live: StateFlow<NoiseLive> = mutableLive.asStateFlow()
    val sessions: Flow<List<NoiseSession>> = flow {
        ready.await()
        emitAll(dao.sessions())
    }
    private val calibrationWindow = ArrayDeque<NoiseReading>()
    private var sinceCheckpoint = 0L
    @Volatile private var sourceFactory: (() -> NoiseSource)? = null

    suspend fun start(): String {
        ready.await()
        return lock.withLock {
            check(mutableLive.value.session?.state != "RECORDING") {
                "A noise measurement is already running."
            }
            check(
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            ) {
                "Allow microphone access to measure environmental noise."
            }
            val session = NoiseSession(UUID.randomUUID().toString(), System.currentTimeMillis())
            dao.put(session)
            calibrationWindow.clear()
            sinceCheckpoint = 0
            mutableLive.value = NoiseLive(session, message = "Starting microphone…")
            try {
                NoiseService.start(context, session.id)
            } catch (e: Exception) {
                val interrupted = session.copy(state = "INTERRUPTED", end = session.start)
                dao.put(interrupted)
                mutableLive.value = NoiseLive(interrupted, message = e.message)
                throw e
            }
            session.id
        }
    }

    fun stop() {
        if (mutableLive.value.session?.state == "RECORDING") NoiseService.stop(context)
    }

    internal fun newSource(): NoiseSource =
        sourceFactory?.invoke() ?: MicrophoneNoiseSource(context)

    suspend fun setSourceFactoryForTesting(factory: (() -> NoiseSource)?) = lock.withLock {
        check(mutableLive.value.session?.state != "RECORDING")
        sourceFactory = factory
    }

    internal suspend fun accept(id: String, frame: NoiseFrame, reading: NoiseReading) =
        lock.withLock {
            var session =
                mutableLive.value.session?.takeIf { it.id == id && it.state == "RECORDING" }
                    ?: return@withLock
            if (session.inputKey == null)
                session =
                    session.copy(
                        inputKey = frame.inputKey,
                        calibrationOffsetDb = dao.calibration(frame.inputKey)?.offsetDb,
                    )
            check(session.inputKey == frame.inputKey) {
                "Microphone input changed; calibration must match the input."
            }
            val level = reading.dbfs
            if (level != null) {
                calibrationWindow.addLast(reading)
                while (calibrationWindow.size > 10) calibrationWindow.removeFirst()
                session =
                    session.copy(
                        measuredMillis = session.measuredMillis + reading.durationMillis,
                        energyMillis =
                            session.energyMillis + reading.meanSquare * reading.durationMillis,
                        maximumDbfs = maxOf(session.maximumDbfs ?: level, level),
                        doseFraction =
                            session.doseFraction +
                                (session.calibrationOffsetDb?.let {
                                    noiseDoseFraction(level + it, reading.durationMillis)
                                } ?: 0.0),
                    )
            } else {
                calibrationWindow.clear()
                session =
                    session.copy(
                        unmeasuredMillis = session.unmeasuredMillis + reading.durationMillis,
                    )
            }
            session = session.copy(end = frame.recordedAt)
            sinceCheckpoint += reading.durationMillis
            if (sinceCheckpoint >= 5_000) {
                dao.put(session)
                sinceCheckpoint = 0
            }
            mutableLive.value =
                NoiseLive(
                    session,
                    level,
                    calibrationWindow.sumOf { it.durationMillis } >= 10_000,
                    if (level == null)
                        "Unmeasured interval: warming up, muted input, silence, or clipping."
                    else null,
                )
        }

    suspend fun calibrate(referenceDbA: Double) = lock.withLock {
        require(referenceDbA.isFinite() && referenceDbA in 20.0..120.0) {
            "Enter a reference level between 20 and 120 dB(A)."
        }
        val session = mutableLive.value.session ?: error("Start a measurement first.")
        check(session.state == "RECORDING" && mutableLive.value.calibrationReady) {
            "Measure at least 10 uninterrupted seconds alongside a reference sound-level meter."
        }
        val duration = calibrationWindow.sumOf { it.durationMillis }
        val level =
            10 *
                kotlin.math.log10(
                    calibrationWindow.sumOf { it.meanSquare * it.durationMillis } / duration,
                )
        val offset = referenceDbA - level
        require(offset.isFinite() && kotlin.math.abs(offset) <= 200) {
            "This input cannot be calibrated from that reference."
        }
        dao.putCalibration(
            NoiseCalibration(session.inputKey!!, offset, referenceDbA, System.currentTimeMillis()),
        )
        mutableLive.value =
            mutableLive.value.copy(
                message =
                    "Calibration saved for this microphone input. Finish and start a new measurement to use it.",
            )
    }

    internal suspend fun finish(id: String, interrupted: Boolean, message: String? = null) =
        lock.withLock {
            val session =
                mutableLive.value.session?.takeIf { it.id == id && it.state == "RECORDING" }
                    ?: return@withLock
            val saved =
                session.copy(
                    state = if (interrupted) "INTERRUPTED" else "FINISHED",
                    end = session.end ?: session.start,
                )
            dao.put(saved)
            calibrationWindow.clear()
            mutableLive.value = NoiseLive(saved, message = message)
        }

    suspend fun delete(id: String) {
        ready.await()
        lock.withLock {
            check(
                mutableLive.value.session?.let { it.id == id && it.state == "RECORDING" } != true,
            ) {
                "Finish the measurement before deleting it."
            }
            dao.delete(id)
            if (mutableLive.value.session?.id == id) mutableLive.value = NoiseLive()
        }
    }
}
