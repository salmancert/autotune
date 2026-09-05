package dev.autotune.training.data

import dev.autotune.core.dsp.AudioBuffers
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A decoded audio file, downmixed to mono at the analysis rate. */
class DecodedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val sourceSampleRate: Int,
    val sourceChannels: Int,
) {
    val durationSeconds: Float get() = samples.size.toFloat() / sampleRate
}

/**
 * Minimal RIFF/WAVE reader and writer.
 *
 * Deliberately hand-rolled rather than `javax.sound.sampled`: the formats that
 * matter here are the handful ffmpeg emits, and a parser we control gives a
 * clear error on a malformed file instead of an
 * `UnsupportedAudioFileException` with nothing useful in it.
 *
 * Handles 8/16/24/32-bit integer and 32/64-bit float PCM, any channel count,
 * any sample rate, including WAVE_FORMAT_EXTENSIBLE.
 */
object WavIo {

    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    fun read(file: File, targetSampleRate: Int): DecodedAudio {
        require(file.isFile) { "not a file: $file" }
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)

        require(buffer.remaining() > 12) { "$file is too small to be a WAV file" }
        require(readTag(buffer) == "RIFF") { "$file is not a RIFF file (is it still an .m4a or .mp3?)" }
        buffer.int // total size, unreliable in streamed files
        require(readTag(buffer) == "WAVE") { "$file is not a WAVE file" }

        var format = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var data: ByteBuffer? = null

        while (buffer.remaining() >= 8) {
            val id = readTag(buffer)
            val size = buffer.int
            if (size < 0 || size > buffer.remaining()) {
                // Truncated final chunk: take whatever is actually there.
                if (id == "data") data = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                break
            }
            when (id) {
                "fmt " -> {
                    val start = buffer.position()
                    format = buffer.short.toInt() and 0xFFFF
                    channels = buffer.short.toInt() and 0xFFFF
                    sampleRate = buffer.int
                    buffer.int // byte rate
                    buffer.short // block align
                    bitsPerSample = buffer.short.toInt() and 0xFFFF
                    if (format == FORMAT_EXTENSIBLE && size >= 40) {
                        buffer.short // cbSize
                        buffer.short // valid bits
                        buffer.int // channel mask
                        // The real format is the first two bytes of the sub-format GUID.
                        format = buffer.short.toInt() and 0xFFFF
                    }
                    buffer.position(start + size)
                }
                "data" -> {
                    val slice = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                    slice.limit(size)
                    data = slice
                    buffer.position(buffer.position() + size)
                }
                else -> buffer.position(buffer.position() + size)
            }
            // Chunks are word-aligned.
            if (size % 2 == 1 && buffer.remaining() > 0) buffer.position(buffer.position() + 1)
        }

        val payload = requireNotNull(data) { "$file has no data chunk" }
        require(channels > 0 && sampleRate > 0) { "$file has no usable fmt chunk" }

        val interleaved = decodeSamples(payload, format, bitsPerSample, file)
        val frames = interleaved.size / channels
        require(frames > 0) { "$file contains no audio frames" }

        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            val base = frame * channels
            for (c in 0 until channels) sum += interleaved[base + c]
            mono[frame] = sum / channels
        }

        val resampled = if (sampleRate == targetSampleRate) {
            mono
        } else {
            val out = FloatArray((frames.toLong() * targetSampleRate / sampleRate).toInt() + 1)
            val written = AudioBuffers.resampleLinear(mono, frames, sampleRate, out, targetSampleRate)
            out.copyOf(written)
        }

        return DecodedAudio(resampled, targetSampleRate, sampleRate, channels)
    }

    private fun decodeSamples(data: ByteBuffer, format: Int, bitsPerSample: Int, file: File): FloatArray =
        when {
            format == FORMAT_PCM && bitsPerSample == 8 -> FloatArray(data.remaining()) {
                ((data.get().toInt() and 0xFF) - 128) / 128f
            }
            format == FORMAT_PCM && bitsPerSample == 16 -> FloatArray(data.remaining() / 2) {
                data.short / 32768f
            }
            format == FORMAT_PCM && bitsPerSample == 24 -> FloatArray(data.remaining() / 3) {
                val low = data.get().toInt() and 0xFF
                val mid = data.get().toInt() and 0xFF
                val high = data.get().toInt() // signed, carries the sign bit
                ((high shl 16) or (mid shl 8) or low) / 8_388_608f
            }
            format == FORMAT_PCM && bitsPerSample == 32 -> FloatArray(data.remaining() / 4) {
                data.int / 2_147_483_648f
            }
            format == FORMAT_FLOAT && bitsPerSample == 32 -> FloatArray(data.remaining() / 4) { data.float }
            format == FORMAT_FLOAT && bitsPerSample == 64 -> FloatArray(data.remaining() / 8) {
                data.double.toFloat()
            }
            else -> error(
                "$file uses an unsupported WAV encoding (format $format, $bitsPerSample bits). " +
                    "Convert it with: ffmpeg -i <input> -ac 1 -ar 16000 -c:a pcm_s16le <output>.wav",
            )
        }

    /** Writes mono 16-bit PCM. Used by the tests and to export review clips. */
    fun writeMono(file: File, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(FORMAT_PCM.toShort())
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataBytes)
        for (sample in samples) {
            buffer.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        file.parentFile?.mkdirs()
        file.writeBytes(buffer.array())
    }

    private fun readTag(buffer: ByteBuffer): String {
        val bytes = ByteArray(4)
        buffer.get(bytes)
        return String(bytes, Charsets.US_ASCII)
    }
}
