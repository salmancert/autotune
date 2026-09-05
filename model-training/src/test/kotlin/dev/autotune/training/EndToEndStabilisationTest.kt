package dev.autotune.training

import dev.autotune.core.engine.StabilizerConfig
import dev.autotune.core.engine.StabilizerEngine
import dev.autotune.core.features.AnalysisFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The user's actual complaint, reproduced and measured.
 *
 * A scene of quiet dialogue, a loud music cue, then dialogue again - the pattern
 * that has people reaching for the volume control twice a minute. The test
 * asserts that the gap between the two shrinks by a useful amount and that the
 * dialogue itself comes up rather than everything simply being turned down.
 */
class EndToEndStabilisationTest {

    private val format = AnalysisFormat.DEFAULT
    private val sampleRate = format.sampleRate

    private class Segment(val samples: FloatArray, val label: String)

    private fun buildScene(): List<Segment> {
        val corpus = SyntheticCorpus()
        val random = Random(20260101)
        fun speech(seconds: Int, dbfs: Float): FloatArray =
            corpus.speech(random, sampleRate * seconds).also { SignalTools.normalizeTo(it, dbfs) }
        fun music(seconds: Int, dbfs: Float): FloatArray =
            corpus.music(random, sampleRate * seconds).also { SignalTools.normalizeTo(it, dbfs) }

        return listOf(
            // Quiet, mumbled dialogue.
            Segment(speech(6, -34f), "dialogue"),
            // The stinger between scenes: 20 dB louder.
            Segment(music(5, -14f), "music"),
            Segment(speech(6, -34f), "dialogue"),
        )
    }

    @Test
    fun `a loud cue between quiet scenes is levelled`() {
        val engine = StabilizerEngine(StabilizerConfig(strength = 1f), format)
        val measured = mutableMapOf<String, MutableList<Float>>()
        val uncorrected = mutableMapOf<String, MutableList<Float>>()

        for (segment in buildScene()) {
            val hop = format.hopSize
            var offset = 0
            var index = 0
            while (offset + hop <= segment.samples.size) {
                val state = engine.process(segment.samples.copyOfRange(offset, offset + hop), hop)
                index++
                // Skip the first second of each segment: that is the transition,
                // and the point of the release curve is that it is not instant.
                if (index > format.frameRate && state.hasSignal) {
                    measured.getOrPut(segment.label) { mutableListOf() } += state.momentaryLufs + state.gainDb
                    uncorrected.getOrPut(segment.label) { mutableListOf() } += state.momentaryLufs
                }
                offset += hop
            }
        }

        val dialogueBefore = median(uncorrected.getValue("dialogue"))
        val musicBefore = median(uncorrected.getValue("music"))
        val dialogueAfter = median(measured.getValue("dialogue"))
        val musicAfter = median(measured.getValue("music"))

        val gapBefore = musicBefore - dialogueBefore
        val gapAfter = musicAfter - dialogueAfter
        println(
            "dialogue %.1f -> %.1f LUFS, music %.1f -> %.1f LUFS, gap %.1f -> %.1f dB"
                .format(dialogueBefore, dialogueAfter, musicBefore, musicAfter, gapBefore, gapAfter),
        )

        assertTrue("the scene should start with a large gap, was $gapBefore dB", gapBefore > 15f)
        assertTrue("gap only narrowed to $gapAfter dB from $gapBefore dB", gapAfter < gapBefore - 12f)
        assertTrue("dialogue was not lifted: $dialogueBefore -> $dialogueAfter", dialogueAfter > dialogueBefore + 6f)
        assertTrue("music was not pulled down: $musicBefore -> $musicAfter", musicAfter < musicBefore - 4f)
        assertTrue("corrected music should not tower over dialogue", musicAfter < dialogueAfter + 6f)
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
