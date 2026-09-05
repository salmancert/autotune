package dev.autotune.tv.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dev.autotune.core.features.AnalysisFormat

/**
 * Captures what other apps are playing, using the MediaProjection the user
 * granted.
 *
 * Only `USAGE_MEDIA`, `USAGE_GAME` and `USAGE_UNKNOWN` streams can be captured,
 * and only from apps that have not opted out with `ALLOW_CAPTURE_BY_NONE`. The
 * majors (Netflix, Prime Video, Disney+) do opt out, which is a platform rule
 * and not something an app can work around - see README for what happens then.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackCaptureSource private constructor(
    private val record: AudioRecord,
    private val reader: PcmReader,
) : AnalysisSource {

    override val kind: SourceKind = SourceKind.PLAYBACK_CAPTURE
    override val label: String = "app playback"

    override fun read(out: FloatArray): Int = reader.read(out)

    override fun close() {
        runCatching { record.stop() }
        runCatching { record.release() }
    }

    companion object {
        private const val TAG = "PlaybackCapture"

        @SuppressLint("MissingPermission")
        fun create(
            projection: MediaProjection,
            format: AnalysisFormat = AnalysisFormat.DEFAULT,
        ): PlaybackCaptureSource? {
            return try {
                val deviceRate = 48_000
                val configuration = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(deviceRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()

                val minimum = AudioRecord.getMinBufferSize(
                    deviceRate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                // Four analysis hops of slack: enough that a scheduling hiccup does
                // not drop audio, small enough to stay well inside our latency budget.
                val bufferBytes = maxOf(minimum, format.hopSize * 4 * 2 * 2 * deviceRate / format.sampleRate)

                val record = AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferBytes)
                    .setAudioPlaybackCaptureConfig(configuration)
                    .build()

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    Log.w(TAG, "playback capture could not be initialised")
                    return null
                }
                record.startRecording()
                val frames = bufferBytes / 4
                PlaybackCaptureSource(
                    record,
                    PcmReader(record, deviceRate, channels = 2, analysisSampleRate = format.sampleRate, bufferSamples = frames),
                )
            } catch (error: Exception) {
                Log.w(TAG, "playback capture unavailable", error)
                null
            }
        }
    }
}
