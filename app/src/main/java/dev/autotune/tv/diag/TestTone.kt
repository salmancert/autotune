package dev.autotune.tv.diag

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a tone from this app, as a control for the capture path.
 *
 * Capture that reads nothing but silence has three possible causes that look
 * identical: the app being played opts out (an opted-out stream arrives as
 * silence, not as an error), the sound never enters Android's audio at all
 * (a tuner or HDMI input), or this app's capture is simply broken.
 *
 * A tone this app plays itself is always capturable by this app's own
 * projection. So if the meter sees the tone, capture works and the problem is
 * the source. If it does not, the problem is here.
 */
object TestTone {

    private const val TAG = "AutotuneDiag"
    private const val SAMPLE_RATE = 48_000
    private const val FREQUENCY = 440.0
    private const val SECONDS = 4
    private const val AMPLITUDE = 0.25

    fun play() {
        val frames = SAMPLE_RATE * SECONDS
        val samples = ShortArray(frames) { i ->
            (sin(2.0 * PI * FREQUENCY * i / SAMPLE_RATE) * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
        }

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // USAGE_MEDIA is what playback capture is configured to
                        // take, so this exercises the same path a video app uses.
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (error: Exception) {
            Log.w(TAG, "test tone: could not open an AudioTrack", error)
            return
        }

        runCatching {
            track.write(samples, 0, samples.size)
            track.play()
            Log.i(TAG, "test tone: playing ${SECONDS}s at ${FREQUENCY.toInt()} Hz through USAGE_MEDIA")
            Log.i(TAG, "test tone: if the meter stays at -100 LUFS for the next ${SECONDS}s, capture is not working")
            Thread.sleep(SECONDS * 1000L + 500L)
        }.onFailure { Log.w(TAG, "test tone failed", it) }

        runCatching { track.stop() }
        runCatching { track.release() }
        Log.i(TAG, "test tone: finished")
    }

    /** Volume the tone is audible at, for the person in the room. */
    fun describe(audio: AudioManager?): String {
        val volume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        return "media volume is $volume - the tone is quiet on purpose, but audible"
    }
}
