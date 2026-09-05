package dev.autotune.core.features

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rolling statistics over the last [frames] analysis frames.
 *
 * The window is ~2 s: long enough to see two or three syllables and at least one
 * musical beat, short enough that the stabiliser reacts to a scene change
 * rather than averaging across it.
 */
class ContextWindow(
    private val format: AnalysisFormat = AnalysisFormat.DEFAULT,
    val frames: Int = (2.0f * format.frameRate).toInt(),
) {
    private val level = FloatArray(frames)
    private val centroid = FloatArray(frames)
    private val spread = FloatArray(frames)
    private val rolloff = FloatArray(frames)
    private val flatness = FloatArray(frames)
    private val flux = FloatArray(frames)
    private val entropy = FloatArray(frames)
    private val zcr = FloatArray(frames)
    private val bandLow = FloatArray(frames)
    private val bandDialogue = FloatArray(frames)
    private val bandHigh = FloatArray(frames)
    private val harmonicity = FloatArray(frames)
    private val pitch = FloatArray(frames)

    private val ordered = FloatArray(frames)

    private var writeIndex = 0
    private var count = 0

    /** True once the window holds enough history for the statistics to mean anything. */
    val isPrimed: Boolean get() = count >= frames / 2

    val fillCount: Int get() = count

    fun add(features: FrameFeatures) {
        val i = writeIndex
        level[i] = features.rmsDb
        centroid[i] = features.centroidLog
        spread[i] = features.spreadOctaves
        rolloff[i] = features.rolloff85
        flatness[i] = features.flatnessLog
        flux[i] = features.flux
        entropy[i] = features.entropy
        zcr[i] = features.zcr
        bandLow[i] = features.bandLow
        bandDialogue[i] = features.bandDialogue
        bandHigh[i] = features.bandHigh
        harmonicity[i] = features.harmonicity
        pitch[i] = features.pitchLog
        writeIndex = (writeIndex + 1) % frames
        if (count < frames) count++
    }

    fun reset() {
        writeIndex = 0
        count = 0
    }

    /** Writes the [FeatureVector.SIZE]-dimensional summary into [out]. */
    fun snapshot(out: FloatArray) {
        require(out.size == FeatureVector.SIZE) { "expected ${FeatureVector.SIZE} features" }
        if (count == 0) {
            java.util.Arrays.fill(out, 0f)
            return
        }

        copyOrdered(level)
        applyDynamicFloor(ordered, count)
        val levelMean = mean(ordered, count)
        out[FeatureVector.LEVEL_MEAN] = levelMean
        out[FeatureVector.LEVEL_STD] = std(ordered, count, levelMean)
        out[FeatureVector.LOW_ENERGY_RATIO] = lowEnergyRatio(ordered, count)
        out[FeatureVector.MODULATION_4HZ] = modulationRatio(ordered, count, 4.0f)
        out[FeatureVector.MODULATION_2HZ] = modulationRatio(ordered, count, 2.0f)
        out[FeatureVector.PULSE_STRENGTH] = pulseStrength(ordered, count)

        out[FeatureVector.CENTROID_MEAN] = meanOf(centroid)
        out[FeatureVector.CENTROID_STD] = stdOf(centroid)
        out[FeatureVector.SPREAD_MEAN] = meanOf(spread)
        out[FeatureVector.ROLLOFF_MEAN] = meanOf(rolloff)
        out[FeatureVector.ROLLOFF_STD] = stdOf(rolloff)
        out[FeatureVector.FLATNESS_MEAN] = meanOf(flatness)
        out[FeatureVector.FLATNESS_STD] = stdOf(flatness)
        out[FeatureVector.FLUX_MEAN] = meanOf(flux)
        out[FeatureVector.FLUX_STD] = stdOf(flux)
        out[FeatureVector.ENTROPY_MEAN] = meanOf(entropy)
        out[FeatureVector.ZCR_MEAN] = meanOf(zcr)
        out[FeatureVector.ZCR_STD] = stdOf(zcr)
        out[FeatureVector.BAND_LOW_MEAN] = meanOf(bandLow)
        out[FeatureVector.BAND_DIALOGUE_MEAN] = meanOf(bandDialogue)
        out[FeatureVector.BAND_HIGH_MEAN] = meanOf(bandHigh)
        out[FeatureVector.HARMONICITY_MEAN] = meanOf(harmonicity)
        out[FeatureVector.HARMONICITY_STD] = stdOf(harmonicity)

        var voiced = 0
        var pitchSum = 0.0
        var pitchSumSq = 0.0
        for (i in 0 until count) {
            val p = pitch[i]
            if (p > 0f) {
                voiced++
                pitchSum += p
                pitchSumSq += p.toDouble() * p
            }
        }
        out[FeatureVector.VOICED_RATIO] = voiced.toFloat() / count
        if (voiced > 0) {
            val m = pitchSum / voiced
            out[FeatureVector.PITCH_MEAN] = m.toFloat()
            out[FeatureVector.PITCH_STD] = sqrt(max(0.0, pitchSumSq / voiced - m * m)).toFloat()
        } else {
            out[FeatureVector.PITCH_MEAN] = 0f
            out[FeatureVector.PITCH_STD] = 0f
        }
    }

    private fun meanOf(source: FloatArray): Float {
        copyOrdered(source)
        return mean(ordered, count)
    }

    private fun stdOf(source: FloatArray): Float {
        copyOrdered(source)
        return std(ordered, count, mean(ordered, count))
    }

    /** Copies the ring buffer into [ordered] oldest-first, which the temporal features need. */
    private fun copyOrdered(source: FloatArray) {
        if (count < frames) {
            source.copyInto(ordered, 0, 0, count)
        } else {
            val tail = frames - writeIndex
            source.copyInto(ordered, 0, writeIndex, frames)
            source.copyInto(ordered, tail, 0, writeIndex)
        }
    }

    /**
     * Floors the level envelope at 60 dB below its own peak.
     *
     * Real silence in a stream is digital: -120 dB frames between scenes, or in
     * the gaps of a clean studio recording. Left alone those dominate the mean
     * and the variance and make an otherwise ordinary line of dialogue look
     * like nothing the model has ever seen. A relative floor keeps the gap -
     * which is genuine evidence of speech - without letting its depth, which is
     * an artefact of the encoder, drive the statistics.
     */
    private fun applyDynamicFloor(values: FloatArray, n: Int) {
        var peak = values[0]
        for (i in 1 until n) if (values[i] > peak) peak = values[i]
        val floor = peak - DYNAMIC_FLOOR_DB
        for (i in 0 until n) if (values[i] < floor) values[i] = floor
    }

    private fun mean(values: FloatArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) sum += values[i]
        return (sum / n).toFloat()
    }

    private fun std(values: FloatArray, n: Int, mean: Float): Float {
        if (n < 2) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val d = values[i] - mean
            sum += d * d
        }
        return sqrt(sum / n).toFloat()
    }

    /**
     * Fraction of frames more than 12 dB below the window mean.
     *
     * Speech is full of gaps - between words, between sentences - so this sits
     * high for dialogue and near zero for a continuous score.
     */
    private fun lowEnergyRatio(levelsDb: FloatArray, n: Int): Float {
        val m = mean(levelsDb, n)
        var below = 0
        for (i in 0 until n) {
            if (levelsDb[i] < m - LOW_ENERGY_MARGIN_DB) below++
        }
        return below.toFloat() / n
    }

    /**
     * Share of the level-envelope's fluctuation that sits at [targetHz].
     *
     * ~4 Hz is the syllabic rate of speech in every language measured; music
     * concentrates its envelope modulation near the beat instead.
     */
    private fun modulationRatio(levelsDb: FloatArray, n: Int, targetHz: Float): Float {
        if (n < 8) return 0f
        val m = mean(levelsDb, n)
        var energy = 0.0
        var re = 0.0
        var im = 0.0
        val omega = 2.0 * PI * targetHz / format.frameRate
        for (i in 0 until n) {
            val x = (levelsDb[i] - m).toDouble()
            energy += x * x
            re += x * cos(omega * i)
            im += x * sin(omega * i)
        }
        if (energy <= 1e-6) return 0f
        val power = (re * re + im * im) / n
        return (power / energy).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Strongest envelope self-similarity at lags of 0.25-1.5 s, i.e. 40-240 BPM.
     * A drum kit scores high here; a conversation does not.
     */
    private fun pulseStrength(levelsDb: FloatArray, n: Int): Float {
        val minLag = (0.25f * format.frameRate).toInt().coerceAtLeast(2)
        val maxLag = (1.5f * format.frameRate).toInt().coerceAtMost(n - 4)
        if (maxLag <= minLag) return 0f

        val m = mean(levelsDb, n)
        var zero = 0.0
        for (i in 0 until n) {
            val d = (levelsDb[i] - m).toDouble()
            zero += d * d
        }
        if (zero <= 1e-6) return 0f

        var best = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until n - lag) {
                sum += (levelsDb[i] - m).toDouble() * (levelsDb[i + lag] - m)
            }
            // Unbiased-ish: compensate for the shrinking overlap at long lags.
            val normalised = sum / zero * (n.toDouble() / (n - lag)).pow(0.5)
            if (normalised > best) best = normalised
        }
        return best.toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val LOW_ENERGY_MARGIN_DB = 12f
        const val DYNAMIC_FLOOR_DB = 60f
    }
}
