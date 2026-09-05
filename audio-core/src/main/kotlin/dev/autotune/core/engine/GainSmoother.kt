package dev.autotune.core.engine

import kotlin.math.exp

/**
 * One-pole gain smoother with separate attack and release times.
 *
 * "Attack" here means moving *down* (pulling a loud passage back) and "release"
 * means moving *up* (letting it return, or lifting quiet dialogue). Ducking has
 * to be quick or the first second of the stinger still blasts; coming back has
 * to be slow or the recovery itself becomes audible as pumping.
 */
class GainSmoother(
    attackMs: Float,
    releaseMs: Float,
    private val stepMs: Float,
) {
    private var attackCoefficient = coefficient(attackMs, stepMs)
    private var releaseCoefficient = coefficient(releaseMs, stepMs)

    var currentDb: Float = 0f
        private set

    fun setTimes(attackMs: Float, releaseMs: Float) {
        attackCoefficient = coefficient(attackMs, stepMs)
        releaseCoefficient = coefficient(releaseMs, stepMs)
    }

    /** Advances one step toward [targetDb] and returns the new gain. */
    fun step(targetDb: Float): Float {
        val coefficient = if (targetDb < currentDb) attackCoefficient else releaseCoefficient
        currentDb += coefficient * (targetDb - currentDb)
        return currentDb
    }

    /** Leaves the gain where it is, e.g. while the input is silent. */
    fun hold(): Float = currentDb

    fun reset(db: Float = 0f) {
        currentDb = db
    }

    private companion object {
        /**
         * Standard one-pole coefficient: the gain covers ~63% of the remaining
         * distance in one time constant.
         */
        fun coefficient(timeMs: Float, stepMs: Float): Float {
            if (timeMs <= 0f) return 1f
            return (1.0 - exp(-stepMs.toDouble() / timeMs)).toFloat().coerceIn(0f, 1f)
        }
    }
}
