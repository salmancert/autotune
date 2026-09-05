package dev.autotune.core.features

import dev.autotune.core.dsp.Fft
import dev.autotune.core.dsp.Windows
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns one windowed frame of mono audio into a [FrameFeatures].
 *
 * Not thread-safe: one instance per analysis thread.
 */
class FrameAnalyzer(private val format: AnalysisFormat = AnalysisFormat.DEFAULT) {

    private val n = format.frameSize
    private val bins = n / 2 + 1
    private val fft = Fft(n)
    private val window = Windows.hann(n)

    private val windowed = FloatArray(n)
    private val real = FloatArray(n)
    private val imag = FloatArray(n)
    private val power = FloatArray(bins)
    private val previousPower = FloatArray(bins)
    private var hasPrevious = false

    // Autocorrelation is taken as the inverse transform of the power spectrum,
    // which is one extra FFT instead of an O(N * lags) time-domain search.
    private val acReal = FloatArray(n)
    private val acImag = FloatArray(n)

    private val binHz = format.sampleRate.toFloat() / n
    private val lowBandEnd = binOf(300f)
    private val dialogueBandStart = lowBandEnd
    private val dialogueBandEnd = binOf(3400f)
    private val highBandStart = binOf(6000f)

    /** Shortest / longest pitch periods considered, covering 70 Hz to 400 Hz. */
    private val minLag = (format.sampleRate / MAX_PITCH_HZ).toInt()
    private val maxLag = minOf((format.sampleRate / MIN_PITCH_HZ).toInt(), n / 2 - 1)

    private fun binOf(hz: Float): Int = (hz / binHz).toInt().coerceIn(0, bins - 1)

    /** Analyses `frame[0, frameSize)` into [out]. */
    fun analyze(frame: FloatArray, out: FrameFeatures) {
        var sumSquares = 0.0
        var crossings = 0
        var previousSample = frame[0]
        for (i in 0 until n) {
            val s = frame[i]
            sumSquares += s.toDouble() * s
            if ((s >= 0f) != (previousSample >= 0f)) crossings++
            previousSample = s
            windowed[i] = s * window[i]
        }

        val rms = sqrt(sumSquares / n).toFloat()
        out.rmsDb = if (rms <= 1e-7f) -120f else (20.0 * log10(rms.toDouble())).toFloat()
        out.zcr = crossings.toFloat() / n

        fft.powerSpectrum(windowed, real, imag, power)

        var total = 0.0
        var weighted = 0.0
        var logSum = 0.0
        var entropySum = 0.0
        var low = 0.0
        var dialogue = 0.0
        var high = 0.0
        var flux = 0.0
        for (bin in 0 until bins) {
            val p = power[bin].toDouble()
            total += p
            weighted += p * bin
            logSum += ln(p + EPSILON)
            when {
                bin < lowBandEnd -> low += p
                bin < dialogueBandEnd -> dialogue += p
                bin >= highBandStart -> high += p
            }
            if (hasPrevious) {
                val delta = power[bin] - previousPower[bin]
                if (delta > 0f) flux += delta.toDouble()
            }
        }

        if (total <= EPSILON) {
            out.centroidLog = 0f
            out.spreadOctaves = 0f
            out.rolloff85 = 0f
            out.flatnessLog = 0f
            out.entropy = 0f
            out.flux = 0f
            out.bandLow = 0f
            out.bandDialogue = 0f
            out.bandHigh = 0f
            out.harmonicity = 0f
            out.pitchLog = 0f
            power.copyInto(previousPower)
            hasPrevious = true
            return
        }

        val centroidBin = weighted / total
        val centroidHz = max(MIN_CENTROID_HZ.toDouble(), centroidBin * binHz)
        out.centroidLog = log2(centroidHz).toFloat()

        var spread = 0.0
        var cumulative = 0.0
        var rolloffBin = bins - 1
        var rolloffFound = false
        for (bin in 0 until bins) {
            val p = power[bin].toDouble()
            val d = bin - centroidBin
            spread += p * d * d
            val share = p / total
            if (share > EPSILON) entropySum -= share * ln(share)
            cumulative += p
            if (!rolloffFound && cumulative >= ROLLOFF_FRACTION * total) {
                rolloffBin = bin
                rolloffFound = true
            }
        }
        val spreadHz = sqrt(spread / total) * binHz
        out.spreadOctaves = log2(1.0 + spreadHz / centroidHz).toFloat()
        out.rolloff85 = rolloffBin.toFloat() / (bins - 1)
        out.entropy = (entropySum / ln(bins.toDouble())).toFloat()

        val geometricMean = kotlin.math.exp(logSum / bins)
        val arithmeticMean = total / bins
        out.flatnessLog = ln((geometricMean + EPSILON) / (arithmeticMean + EPSILON)).toFloat()

        // Flux is normalised by frame energy so it measures spectral *change*,
        // not just loudness: an onset at low volume still reads as an onset.
        out.flux = (flux / (total + EPSILON)).toFloat()

        out.bandLow = (low / total).toFloat()
        out.bandDialogue = (dialogue / total).toFloat()
        out.bandHigh = (high / total).toFloat()

        analyzePitch(out)

        power.copyInto(previousPower)
        hasPrevious = true
    }

    /**
     * Voicing and f0 from the autocorrelation of the frame, obtained as the
     * inverse transform of the (real, even) power spectrum.
     */
    private fun analyzePitch(out: FrameFeatures) {
        for (bin in 0 until bins) {
            acReal[bin] = power[bin]
            acImag[bin] = 0f
            if (bin in 1 until bins - 1) {
                acReal[n - bin] = power[bin]
                acImag[n - bin] = 0f
            }
        }
        fft.forward(acReal, acImag)

        val zeroLag = acReal[0]
        if (zeroLag <= EPSILON.toFloat()) {
            out.harmonicity = 0f
            out.pitchLog = 0f
            return
        }

        var bestLag = -1
        var bestValue = 0f
        for (lag in minLag..maxLag) {
            val v = acReal[lag]
            if (v > bestValue) {
                bestValue = v
                bestLag = lag
            }
        }

        val harmonicity = (bestValue / zeroLag).coerceIn(0f, 1f)
        out.harmonicity = harmonicity
        out.pitchLog = if (bestLag > 0 && harmonicity >= VOICING_THRESHOLD) {
            log2(format.sampleRate.toDouble() / bestLag).toFloat()
        } else {
            0f
        }
    }

    fun reset() {
        java.util.Arrays.fill(previousPower, 0f)
        hasPrevious = false
    }

    companion object {
        const val VOICING_THRESHOLD = 0.3f
        private const val EPSILON = 1e-12
        private const val ROLLOFF_FRACTION = 0.85
        private const val MIN_CENTROID_HZ = 20f
        private const val MIN_PITCH_HZ = 70f
        private const val MAX_PITCH_HZ = 400f

        /** Absolute frame level below which a frame carries no usable information. */
        const val SILENCE_DB = -70f
    }
}
