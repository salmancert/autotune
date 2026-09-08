package dev.autotune.tv.effects

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dev.autotune.core.engine.StabilizerConfig
import dev.autotune.core.engine.StabilizerState
import dev.autotune.core.engine.VolumeStepController

/**
 * Applies the correction by moving the TV's own volume.
 *
 * The last resort, and the only output that always does something. Audio
 * effects attach to the output mix; on a TV feeding a soundbar or receiver over
 * HDMI the mix can be passed through untouched, so the effect attaches, reports
 * success, and is inaudible. The volume control is the same one the remote
 * drives, so it survives that.
 *
 * It is coarse - a step is a couple of dB and they are unevenly spaced - and the
 * change is visible on screen. That is the trade, and it is why this is opt-in
 * or a fallback rather than the default.
 */
class StreamVolumeOutput private constructor(
    private val audioManager: AudioManager,
    private val controller: VolumeStepController,
) : AudioOutputProcessor {

    override val label: String = "TV volume (coarse)"

    private var bypassed = false

    override fun apply(state: StabilizerState) {
        if (bypassed) return
        val current = currentVolume() ?: return
        val target = controller.update(state.gainDb, current, System.currentTimeMillis()) ?: return
        setVolume(target)
    }

    override fun applyStaticPreset(config: StabilizerConfig) {
        // Nothing to do: with no analysis there is no gain to translate, and
        // moving the volume on a guess would be worse than leaving it alone.
    }

    override fun setBypassed(bypassed: Boolean) {
        this.bypassed = bypassed
        if (bypassed) setVolume(controller.restoreIndex())
    }

    override fun close() {
        setVolume(controller.restoreIndex())
    }

    private fun currentVolume(): Int? = runCatching {
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }.getOrNull()

    private fun setVolume(index: Int) {
        runCatching {
            // No FLAG_SHOW_UI: the system volume panel appearing every few
            // seconds over the film would be worse than the problem.
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        }.onFailure { Log.w(TAG, "could not set the media volume", it) }
    }

    companion object {
        private const val TAG = "StreamVolumeOutput"

        /**
         * True when the device refuses volume changes entirely - fixed-volume
         * HDMI output, most commonly. Worth telling the user rather than
         * silently doing nothing.
         */
        fun isVolumeFixed(context: Context): Boolean {
            val manager = context.getSystemService(AudioManager::class.java) ?: return false
            return runCatching { manager.isVolumeFixed }.getOrDefault(false)
        }

        fun create(context: Context): StreamVolumeOutput? {
            val manager = context.getSystemService(AudioManager::class.java) ?: return null
            if (runCatching { manager.isVolumeFixed }.getOrDefault(false)) {
                Log.i(TAG, "the device reports fixed volume; not taking it over")
                return null
            }
            val max = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
            if (max <= 1) return null
            val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { manager.getStreamMinVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
            } else {
                0
            }
            val controller = VolumeStepController(minIndex = min, maxIndex = max)
            val current = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return null
            controller.attach(current)
            return StreamVolumeOutput(manager, controller)
        }
    }
}
