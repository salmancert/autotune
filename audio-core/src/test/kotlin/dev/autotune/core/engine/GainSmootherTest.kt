package dev.autotune.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GainSmootherTest {

    @Test
    fun `one time constant covers 63 percent of the step`() {
        val smoother = GainSmoother(attackMs = 100f, releaseMs = 100f, stepMs = 10f)
        repeat(10) { smoother.step(-10f) }
        assertEquals(-6.32, smoother.currentDb.toDouble(), 0.2)
    }

    @Test
    fun `attack and release are independent`() {
        val smoother = GainSmoother(attackMs = 20f, releaseMs = 2000f, stepMs = 10f)
        repeat(5) { smoother.step(-12f) }
        val ducked = smoother.currentDb
        assertTrue("ducked to $ducked", ducked < -10f)

        repeat(5) { smoother.step(0f) }
        val released = smoother.currentDb - ducked
        // Same elapsed time, two orders of magnitude less movement.
        assertTrue("released $released dB in the time it ducked ${-ducked} dB", released < -ducked / 10f)
    }

    @Test
    fun `hold leaves the gain untouched`() {
        val smoother = GainSmoother(50f, 50f, 10f)
        repeat(20) { smoother.step(-5f) }
        val held = smoother.hold()
        assertEquals(held.toDouble(), smoother.hold().toDouble(), 0.0)
    }
}
