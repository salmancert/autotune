package dev.autotune.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeStepControllerTest {

    private fun controller(max: Int = 20, dbPerStep: Float = 2f) =
        VolumeStepController(minIndex = 0, maxIndex = max, dbPerStep = dbPerStep, minIntervalMs = 400L)
            .apply { attach(10) }

    @Test
    fun `no correction leaves the volume where the viewer put it`() {
        val controller = controller()
        assertNull(controller.update(gainDb = 0f, currentIndex = 10, nowMs = 1_000))
        assertEquals(10, controller.referenceIndex)
    }

    @Test
    fun `a duck maps onto whole steps`() {
        val controller = controller()
        assertEquals(7, controller.update(gainDb = -6f, currentIndex = 10, nowMs = 1_000))
    }

    @Test
    fun `a boost maps upward and clamps at the maximum`() {
        val controller = controller(max = 12)
        assertEquals(12, controller.update(gainDb = 12f, currentIndex = 10, nowMs = 1_000))
    }

    @Test
    fun `less than a step of gain is not worth moving for`() {
        val controller = controller(dbPerStep = 2f)
        assertNull(controller.update(gainDb = -0.9f, currentIndex = 10, nowMs = 1_000))
    }

    @Test
    fun `writes are throttled`() {
        val controller = controller()
        assertEquals(7, controller.update(gainDb = -6f, currentIndex = 10, nowMs = 1_000))
        // The engine updates every 32 ms; the volume must not follow it that fast.
        assertNull(controller.update(gainDb = -12f, currentIndex = 7, nowMs = 1_100))
        assertEquals(4, controller.update(gainDb = -12f, currentIndex = 7, nowMs = 1_500))
    }

    @Test
    fun `the viewer reaching for the remote wins`() {
        val controller = controller()
        assertEquals(7, controller.update(gainDb = -6f, currentIndex = 10, nowMs = 1_000))

        // They turn it up two steps while the duck is still applied.
        val next = controller.update(gainDb = -6f, currentIndex = 9, nowMs = 2_000)
        assertNull("their change was immediately undone", next)
        // The baseline moved with them, so the same duck now sits two steps higher.
        assertEquals(12, controller.referenceIndex)
        assertEquals(9, controller.desiredIndex(-6f))
    }

    @Test
    fun `after the viewer intervenes corrections continue from their new level`() {
        val controller = controller()
        controller.update(gainDb = -6f, currentIndex = 10, nowMs = 1_000)
        controller.update(gainDb = -6f, currentIndex = 9, nowMs = 2_000)

        // Music ends: back to the new baseline, not the old one.
        assertEquals(12, controller.update(gainDb = 0f, currentIndex = 9, nowMs = 3_000))
    }

    @Test
    fun `bypassing restores the viewers own level`() {
        val controller = controller()
        controller.update(gainDb = -8f, currentIndex = 10, nowMs = 1_000)
        assertEquals(10, controller.restoreIndex())
    }

    @Test
    fun `the step estimate stays sane across the range of devices`() {
        // 15-step phones and TVs, 100-step Android TV boxes, and the silly cases.
        assertTrue(VolumeStepController.estimateDbPerStep(15) in 2f..3f)
        assertTrue(VolumeStepController.estimateDbPerStep(100) in 0.25f..0.5f)
        assertTrue(VolumeStepController.estimateDbPerStep(1) <= 6f)
        assertTrue(VolumeStepController.estimateDbPerStep(0) <= 6f)
    }
}
