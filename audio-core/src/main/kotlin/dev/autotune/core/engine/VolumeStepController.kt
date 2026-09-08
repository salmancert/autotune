package dev.autotune.core.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Maps the engine's continuous gain onto the TV's own volume steps.
 *
 * This is the crude path, and it exists because the good path does not always
 * work. Audio effects attach to the output mix, and on a TV feeding a soundbar
 * or an AV receiver over HDMI the mix may be passed through untouched -
 * `DynamicsProcessing` attaches, reports success, and changes nothing. Driving
 * the system volume works in those setups because it is the same control the
 * remote uses.
 *
 * What it costs: the steps are coarse and unevenly spaced, the change is visible
 * on screen, and it moves a control the viewer also owns. So it is throttled,
 * given a full step of hysteresis, and - most importantly - it yields. When the
 * viewer reaches for the remote, their new setting becomes the baseline that
 * corrections ride on, instead of something to be argued with.
 */
class VolumeStepController(
    private val minIndex: Int,
    private val maxIndex: Int,
    /** Approximate dB per volume step on this device. */
    private val dbPerStep: Float = estimateDbPerStep(maxIndex),
    /** Shortest gap between two volume writes. */
    private val minIntervalMs: Long = 400L,
) {
    init {
        require(maxIndex > minIndex) { "maxIndex ($maxIndex) must exceed minIndex ($minIndex)" }
        require(dbPerStep > 0f) { "dbPerStep must be positive" }
    }

    /** The level the viewer chose; the index that means "no correction". */
    var referenceIndex: Int = minIndex
        private set

    private var lastWrittenIndex = Int.MIN_VALUE
    private var lastChangeMs = Long.MIN_VALUE

    /** Adopts the volume as it stands as the zero-correction baseline. */
    fun attach(currentIndex: Int) {
        referenceIndex = currentIndex.coerceIn(minIndex, maxIndex)
        lastWrittenIndex = referenceIndex
        lastChangeMs = Long.MIN_VALUE
    }

    /** Index this gain corresponds to, before throttling. */
    fun desiredIndex(gainDb: Float): Int =
        (referenceIndex + stepsFor(gainDb)).coerceIn(minIndex, maxIndex)

    /**
     * Returns the index to write, or null to leave the volume alone.
     *
     * [currentIndex] is what the device reports right now, which is how a change
     * made with the remote is noticed.
     */
    fun update(gainDb: Float, currentIndex: Int, nowMs: Long): Int? {
        adoptExternalChange(gainDb, currentIndex)

        val desired = desiredIndex(gainDb)
        if (abs(desired - currentIndex) < 1) return null
        if (lastChangeMs != Long.MIN_VALUE && nowMs - lastChangeMs < minIntervalMs) return null

        lastWrittenIndex = desired
        lastChangeMs = nowMs
        return desired
    }

    /**
     * If the volume is not where this controller last put it, the viewer moved
     * it. Their new level becomes the baseline *including* whatever correction
     * is currently applied - so turning it up during a quiet scene makes it
     * louder and stays louder, rather than being undone on the next update.
     */
    private fun adoptExternalChange(gainDb: Float, currentIndex: Int) {
        if (lastWrittenIndex == Int.MIN_VALUE || currentIndex == lastWrittenIndex) return
        referenceIndex = (currentIndex - stepsFor(gainDb)).coerceIn(minIndex, maxIndex)
        lastWrittenIndex = currentIndex
    }

    /** The index to return to when the stabiliser is bypassed or stopped. */
    fun restoreIndex(): Int = referenceIndex

    private fun stepsFor(gainDb: Float): Int = (gainDb / dbPerStep).roundToInt()

    companion object {
        /**
         * Android's media volume curve spans roughly 40-60 dB over the whole
         * index range and is not linear in dB, so this is an approximation
         * chosen to be conservative: assuming fewer dB per step than reality
         * makes the correction under-shoot rather than overshoot.
         */
        fun estimateDbPerStep(maxIndex: Int): Float = (40f / maxIndex.coerceAtLeast(1)).coerceIn(0.25f, 6f)
    }
}
