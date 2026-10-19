package dk.frem.app.hearing

import kotlin.math.*

internal data class NoiseReading(
    val meanSquare: Double,
    val durationMillis: Long,
    val valid: Boolean,
) {
    val dbfs: Double?
        get() = if (valid && meanSquare > 0) 10 * log10(meanSquare) else null
}

/**
 * 48 kHz mono PCM. Cascaded bilinear A-weighting sections avoid a poorly conditioned 6th-order
 * filter.
 */
internal class NoiseProcessor {
    private class Section(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun sample(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun power(omega: Double): Double {
            fun square(c0: Double, c1: Double, c2: Double): Double =
                (c0 + c1 * cos(omega) + c2 * cos(2 * omega)).pow(2) +
                    (c1 * sin(omega) + c2 * sin(2 * omega)).pow(2)
            return square(b0, b1, b2) / square(1.0, a1, a2)
        }
    }

    private fun section(f1: Double, f2: Double, highPass: Boolean): Section {
        val c = 2.0 * SAMPLE_RATE
        val w1 = 2 * PI * f1
        val w2 = 2 * PI * f2
        val a0 = (c + w1) * (c + w2)
        val n = if (highPass) c * c else w1 * w2
        return Section(
            n / a0,
            (if (highPass) -2 else 2) * n / a0,
            n / a0,
            2 * (w1 * w2 - c * c) / a0,
            (c - w1) * (c - w2) / a0,
        )
    }

    private val sections =
        listOf(
            section(20.598997, 20.598997, true),
            section(107.65265, 737.86223, true),
            section(12194.217, 12194.217, false),
        )
    private val gain =
        1 /
            sqrt(
                sections.fold(1.0) { power, section ->
                    power * section.power(2 * PI * 1000 / SAMPLE_RATE)
                },
            )
    private var processed = 0L

    fun process(samples: ShortArray, silenced: Boolean = false): NoiseReading {
        var energy = 0.0
        var clipped = false
        var nonzero = false
        for (sample in samples) {
            if (abs(sample.toInt()) >= 32760) clipped = true
            if (sample.toInt() != 0) nonzero = true
            var value = sample / 32768.0
            for (section in sections) value = section.sample(value)
            energy += (value * gain).pow(2)
        }
        val warmed = processed >= SAMPLE_RATE
        processed += samples.size
        val mean = if (samples.isNotEmpty()) energy / samples.size else 0.0
        return NoiseReading(
            mean,
            samples.size * 1000L / SAMPLE_RATE,
            warmed && !silenced && !clipped && nonzero && mean.isFinite() && mean > 0,
        )
    }

    companion object {
        const val SAMPLE_RATE = 48_000
    }
}
