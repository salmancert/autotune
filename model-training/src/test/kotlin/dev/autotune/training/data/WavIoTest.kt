package dev.autotune.training.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class WavIoTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun tone(samples: Int, sampleRate: Int, frequency: Double) =
        FloatArray(samples) { (0.5 * sin(2.0 * PI * frequency * it / sampleRate)).toFloat() }

    @Test
    fun `mono 16 bit survives a write and read round trip`() {
        val file = File(folder.root, "tone.wav")
        val original = tone(16_000, 16_000, 440.0)
        WavIo.writeMono(file, original, 16_000)

        val decoded = WavIo.read(file, 16_000)
        assertEquals(16_000, decoded.sampleRate)
        assertEquals(1, decoded.sourceChannels)
        assertEquals(original.size, decoded.samples.size)
        for (i in original.indices) {
            assertEquals(original[i].toDouble(), decoded.samples[i].toDouble(), 1e-4)
        }
    }

    @Test
    fun `a 48 kHz stereo file is downmixed and resampled`() {
        val file = File(folder.root, "stereo48.wav")
        writeInterleaved(
            file,
            sampleRate = 48_000,
            channels = 2,
            samples = FloatArray(48_000 * 2) { index ->
                val frame = index / 2
                // Identical content in both channels, so the downmix must
                // reproduce it rather than cancel it.
                (0.5 * sin(2.0 * PI * 200.0 * frame / 48_000)).toFloat()
            },
        )

        val decoded = WavIo.read(file, 16_000)
        assertEquals(48_000, decoded.sourceSampleRate)
        assertEquals(2, decoded.sourceChannels)
        assertEquals(1.0, decoded.durationSeconds.toDouble(), 0.02)
        for (i in 100 until 15_900) {
            val expected = 0.5 * sin(2.0 * PI * 200.0 * i / 16_000)
            assertEquals("sample $i", expected, decoded.samples[i].toDouble(), 0.02)
        }
    }

    @Test
    fun `32 bit float files are read`() {
        val file = File(folder.root, "float32.wav")
        val samples = tone(8_000, 16_000, 300.0)
        val buffer = ByteBuffer.allocate(44 + samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        writeHeader(buffer, format = 3, channels = 1, sampleRate = 16_000, bits = 32, dataBytes = samples.size * 4)
        for (sample in samples) buffer.putFloat(sample)
        file.writeBytes(buffer.array())

        val decoded = WavIo.read(file, 16_000)
        assertEquals(samples.size, decoded.samples.size)
        for (i in samples.indices) assertEquals(samples[i].toDouble(), decoded.samples[i].toDouble(), 1e-6)
    }

    @Test
    fun `unknown chunks before the data are skipped`() {
        // ffmpeg happily writes a LIST/INFO chunk; a parser that assumed fmt
        // then data would read metadata as audio.
        val samples = tone(4_000, 16_000, 250.0)
        val listBytes = "INFOISFT".toByteArray() + ByteArray(8)
        val buffer = ByteBuffer.allocate(44 + 8 + listBytes.size + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(0)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1); buffer.putShort(1); buffer.putInt(16_000)
        buffer.putInt(32_000); buffer.putShort(2); buffer.putShort(16)
        buffer.put("LIST".toByteArray())
        buffer.putInt(listBytes.size)
        buffer.put(listBytes)
        buffer.put("data".toByteArray())
        buffer.putInt(samples.size * 2)
        for (sample in samples) buffer.putShort((sample * 32767f).toInt().toShort())

        val file = File(folder.root, "withlist.wav")
        file.writeBytes(buffer.array())

        val decoded = WavIo.read(file, 16_000)
        assertEquals(samples.size, decoded.samples.size)
        assertEquals(samples[100].toDouble(), decoded.samples[100].toDouble(), 1e-4)
    }

    @Test
    fun `a non-wav file is rejected with a useful message`() {
        val file = File(folder.root, "video.m4a")
        file.writeBytes(ByteArray(64) { 7 })
        val error = runCatching { WavIo.read(file, 16_000) }.exceptionOrNull()
        assertTrue("expected a failure", error != null)
        assertTrue("message was '${error?.message}'", error!!.message!!.contains("RIFF"))
    }

    private fun writeInterleaved(file: File, sampleRate: Int, channels: Int, samples: FloatArray) {
        val buffer = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        writeHeader(buffer, format = 1, channels = channels, sampleRate = sampleRate, bits = 16, dataBytes = samples.size * 2)
        for (sample in samples) buffer.putShort((sample * 32767f).toInt().toShort())
        file.writeBytes(buffer.array())
    }

    private fun writeHeader(
        buffer: ByteBuffer,
        format: Int,
        channels: Int,
        sampleRate: Int,
        bits: Int,
        dataBytes: Int,
    ) {
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(format.toShort())
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * bits / 8)
        buffer.putShort((channels * bits / 8).toShort())
        buffer.putShort(bits.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(dataBytes)
    }
}
