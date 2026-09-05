package dev.autotune.training.data

import dev.autotune.core.ml.AudioClass
import dev.autotune.core.ml.MlpAudioClassifier
import dev.autotune.training.SignalTools
import dev.autotune.training.SyntheticCorpus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class LabelPipelineTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sampleRate = 16_000

    @Test
    fun `a manifest survives a write and read round trip`() {
        val manifest = File(folder.root, "labels.csv")
        val audio = File(folder.root, "ary/episode.wav")
        WavIo.writeMono(audio, FloatArray(sampleRate), sampleRate)

        val entries = listOf(
            LabelEntry(audio, 0f, 12.5f, AudioClass.SPEECH, "ary", 0.97f),
            LabelEntry(audio, 12.5f, 30f, AudioClass.MUSIC, "ary", 0.62f),
        )
        LabelManifest.write(manifest, entries)
        val restored = LabelManifest.read(manifest)

        assertEquals(2, restored.size)
        assertEquals(AudioClass.SPEECH, restored[0].label)
        assertEquals(12.5f, restored[0].end, 1e-3f)
        assertEquals("ary", restored[1].source)
        assertEquals(0.62f, restored[1].confidence, 1e-3f)
        // Paths are stored relative to the manifest but resolve back to the file.
        assertEquals(audio.canonicalPath, restored[0].file.canonicalPath)
    }

    @Test
    fun `comments blank lines and a header row are ignored`() {
        val manifest = File(folder.root, "labels.csv")
        manifest.writeText(
            """
            # file, start, end, label, source
            file, start, end, label, source

            episode.wav, 0, 10, dialogue, humtv   # an alias for speech

            episode.wav, 10, , ost, humtv
            """.trimIndent(),
        )
        val entries = LabelManifest.read(manifest)
        assertEquals(2, entries.size)
        assertEquals(AudioClass.SPEECH, entries[0].label)
        assertEquals(AudioClass.MUSIC, entries[1].label)
        // A blank end means "to the end of the file".
        assertEquals(Float.MAX_VALUE, entries[1].end, 0f)
    }

    @Test
    fun `an unknown label is rejected rather than silently dropped`() {
        val manifest = File(folder.root, "labels.csv")
        manifest.writeText("episode.wav, 0, 10, dialouge, ary\n")
        val error = runCatching { LabelManifest.read(manifest) }.exceptionOrNull()
        assertTrue("expected a failure", error != null)
        assertTrue(error!!.message!!.contains("dialouge"))
    }

    @Test
    fun `the directory layout is read with the folder above as the source`() {
        val speech = File(folder.root, "ary/speech/scene1.wav")
        val music = File(folder.root, "humtv/music/titles.wav")
        WavIo.writeMono(speech, FloatArray(sampleRate), sampleRate)
        WavIo.writeMono(music, FloatArray(sampleRate), sampleRate)

        val entries = LabelManifest.fromDirectoryLayout(folder.root).sortedBy { it.source }
        assertEquals(2, entries.size)
        assertEquals("ary", entries[0].source)
        assertEquals(AudioClass.SPEECH, entries[0].label)
        assertEquals("humtv", entries[1].source)
        assertEquals(AudioClass.MUSIC, entries[1].label)
    }

    @Test
    fun `segments are cut at the right offsets and short ones are skipped`() {
        val corpus = SyntheticCorpus()
        val random = Random(7)
        // 4 s of speech, then 4 s of music, then 1 s of speech that is too short
        // to be usable.
        val speech = corpus.speech(random, sampleRate * 4).also { SignalTools.normalizeTo(it, -20f) }
        val music = corpus.music(random, sampleRate * 4).also { SignalTools.normalizeTo(it, -20f) }
        val tail = corpus.speech(random, sampleRate).also { SignalTools.normalizeTo(it, -20f) }
        val joined = speech + music + tail

        val audio = File(folder.root, "ary/mixed.wav")
        WavIo.writeMono(audio, joined, sampleRate)

        val skipped = mutableListOf<String>()
        val clips = RealCorpus.load(
            listOf(
                LabelEntry(audio, 0f, 4f, AudioClass.SPEECH, "ary"),
                LabelEntry(audio, 4f, 8f, AudioClass.MUSIC, "ary"),
                LabelEntry(audio, 8f, 9f, AudioClass.SPEECH, "ary"),
            ),
            minSegmentSeconds = 2.5f,
            onSkip = { skipped += it },
        ).toList()

        assertEquals(2, clips.size)
        assertEquals(1, skipped.size)
        assertEquals(sampleRate * 4, clips[0].samples.size)
        // Every segment from one file shares a group, so a split cannot leak.
        assertEquals(clips[0].groupId, clips[1].groupId)
        assertEquals("ary", clips[0].source)
        // The cut landed where it was asked to.
        for (i in 0 until 1000) {
            assertEquals(music[i].toDouble(), clips[1].samples[i].toDouble(), 1e-4)
        }
    }

    @Test
    fun `the auto labeler finds the structure of a scene`() {
        val corpus = SyntheticCorpus()
        val random = Random(20260202)
        // Dialogue, a score cue, dialogue again - the shape of a scene change,
        // and the layout the labeller has to recover from a whole episode.
        val speechA = corpus.speech(random, sampleRate * 8).also { SignalTools.normalizeTo(it, -22f) }
        val cue = corpus.melodicBed(random, sampleRate * 8).also { SignalTools.normalizeTo(it, -14f) }
        val speechB = corpus.speech(random, sampleRate * 8).also { SignalTools.normalizeTo(it, -22f) }
        val audio = File(folder.root, "ary/scene.wav")
        WavIo.writeMono(audio, speechA + cue + speechB, sampleRate)

        val entries = AutoLabeler(MlpAudioClassifier.bundledModel()).label(audio, "ary")
        assertTrue("expected several segments, got $entries", entries.size >= 3)

        // The music in the middle must be found, and be where it actually is:
        // 8 s to 16 s, allowing for the classifier turning over inside the
        // transition and for the deliberate edge trim.
        val musicSegments = entries.filter { it.label == AudioClass.MUSIC }
        assertTrue("no music segment found in $entries", musicSegments.isNotEmpty())
        val music = musicSegments.maxBy { it.durationSeconds }
        assertTrue("music started at ${music.start}s, expected near 8s", music.start in 6.5f..10f)
        assertTrue("music ended at ${music.end}s, expected near 16s", music.end in 14f..17.5f)

        // And speech on both sides of it.
        assertTrue(entries.any { it.label == AudioClass.SPEECH && it.start < music.start })
        assertTrue(entries.any { it.label == AudioClass.SPEECH && it.start > music.start })

        // Confidence has to be a real number for the review list to be sortable.
        assertTrue(entries.all { it.confidence in 0f..1f })
    }
}
