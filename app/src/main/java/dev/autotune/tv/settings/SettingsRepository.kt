package dev.autotune.tv.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.autotune.core.engine.ListeningPreset
import dev.autotune.core.engine.StabilizerConfig

/**
 * User settings.
 *
 * Plain SharedPreferences rather than DataStore: the service reads these from
 * its audio thread at start-up and on change, and a synchronous, dependency-free
 * store is the simplest thing that is correct here.
 */
class SettingsRepository(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, true)
        set(value) = preferences.edit { putBoolean(KEY_ENABLED, value) }

    var startOnBoot: Boolean
        get() = preferences.getBoolean(KEY_START_ON_BOOT, true)
        set(value) = preferences.edit { putBoolean(KEY_START_ON_BOOT, value) }

    /** What the user is watching. Presets own the correction settings; Custom does not. */
    var preset: ListeningPreset
        get() = ListeningPreset.fromName(preferences.getString(KEY_PRESET, null))
        set(value) = preferences.edit { putString(KEY_PRESET, value.name) }

    /** 0..1. */
    var strength: Float
        get() = preferences.getFloat(KEY_STRENGTH, 0.8f)
        set(value) = preferences.edit { putFloat(KEY_STRENGTH, value.coerceIn(0f, 1f)) }

    var targetDialogueLufs: Float
        get() = preferences.getFloat(KEY_DIALOGUE_TARGET, -20f)
        set(value) = preferences.edit { putFloat(KEY_DIALOGUE_TARGET, value.coerceIn(-30f, -12f)) }

    var musicCeilingOffsetDb: Float
        get() = preferences.getFloat(KEY_MUSIC_OFFSET, 4f)
        set(value) = preferences.edit { putFloat(KEY_MUSIC_OFFSET, value.coerceIn(0f, 12f)) }

    /** Listening to the room is a last resort, so it is opt-in. */
    var allowMicrophoneFallback: Boolean
        get() = preferences.getBoolean(KEY_MICROPHONE, false)
        set(value) = preferences.edit { putBoolean(KEY_MICROPHONE, value) }

    /**
     * Take over the TV's volume control instead of relying on an audio effect.
     *
     * Needed when the TV passes audio through to a soundbar or receiver, where
     * an effect on the output mix attaches successfully and does nothing.
     */
    var useVolumeControl: Boolean
        get() = preferences.getBoolean(KEY_VOLUME_CONTROL, false)
        set(value) = preferences.edit { putBoolean(KEY_VOLUME_CONTROL, value) }

    var perAppProfiles: Boolean
        get() = preferences.getBoolean(KEY_PER_APP, true)
        set(value) = preferences.edit { putBoolean(KEY_PER_APP, value) }

    fun toConfig(): StabilizerConfig = StabilizerConfig(
        enabled = enabled,
        targetDialogueLufs = targetDialogueLufs,
        musicCeilingOffsetDb = musicCeilingOffsetDb,
        strength = strength,
        preset = preset,
    )

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val NAME = "autotune-settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_START_ON_BOOT = "startOnBoot"
        const val KEY_PRESET = "preset"
        const val KEY_STRENGTH = "strength"
        const val KEY_DIALOGUE_TARGET = "dialogueTarget"
        const val KEY_MUSIC_OFFSET = "musicOffset"
        const val KEY_MICROPHONE = "microphoneFallback"
        const val KEY_PER_APP = "perAppProfiles"
        const val KEY_VOLUME_CONTROL = "useVolumeControl"
    }
}
