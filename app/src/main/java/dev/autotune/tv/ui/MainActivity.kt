package dev.autotune.tv.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.autotune.core.engine.AppProfile
import dev.autotune.tv.R
import dev.autotune.tv.capture.AnalysisSourceFactory
import dev.autotune.tv.databinding.ActivityMainBinding
import dev.autotune.tv.databinding.ViewSettingRowBinding
import dev.autotune.tv.service.StabilizerServiceController
import dev.autotune.tv.service.StabilizerStatus
import dev.autotune.tv.service.StabilizerStatusBus
import dev.autotune.tv.session.PlaybackMonitor
import dev.autotune.tv.settings.SettingsRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The whole UI: status, live meter, and the handful of settings worth exposing.
 *
 * Built for a remote control - every row is focusable, centre toggles, and
 * left/right adjusts a value. No touch targets, no dialogs to get stuck in.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsRepository
    private lateinit var monitor: PlaybackMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsRepository(this)
        monitor = PlaybackMonitor(this)

        bindRows()

        if (settings.enabled) {
            // Opening the app counts as user intent, which is what lets the
            // service claim the microphone type if the user enabled that.
            StabilizerServiceController.start(this, userInitiated = true)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StabilizerStatusBus.status.collectLatest(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRows()
    }

    private fun bindRows() {
        binding.rowEnabled.onSelect {
            settings.enabled = !settings.enabled
            if (settings.enabled) {
                StabilizerServiceController.start(this, userInitiated = true)
            } else {
                StabilizerServiceController.stop(this)
            }
            refreshRows()
        }

        binding.rowStrength.onAdjust { direction ->
            settings.strength = (settings.strength + direction * 0.05f).coerceIn(0f, 1f)
            refreshRows()
        }

        binding.rowDialogueTarget.onAdjust { direction ->
            settings.targetDialogueLufs = (settings.targetDialogueLufs + direction * 0.5f).coerceIn(-30f, -12f)
            refreshRows()
        }

        binding.rowMusicHeadroom.onAdjust { direction ->
            settings.musicCeilingOffsetDb = (settings.musicCeilingOffsetDb + direction * 0.5f).coerceIn(0f, 12f)
            refreshRows()
        }

        binding.rowNightMode.onSelect {
            settings.nightMode = !settings.nightMode
            refreshRows()
        }

        binding.rowCapture.onSelect {
            startActivity(Intent(this, CaptureConsentActivity::class.java))
        }

        binding.rowNotificationAccess.onSelect {
            // No guarantee every TV build ships this screen, so fall back to
            // the top-level settings rather than crashing on a missing activity.
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            runCatching { startActivity(intent) }
                .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }

        binding.rowMicrophone.onSelect {
            settings.allowMicrophoneFallback = !settings.allowMicrophoneFallback
            StabilizerServiceController.start(this, userInitiated = true)
            refreshRows()
        }

        binding.rowBoot.onSelect {
            settings.startOnBoot = !settings.startOnBoot
            refreshRows()
        }

        binding.rowEnabled.root.requestFocus()
    }

    private fun refreshRows() {
        binding.rowEnabled.set(
            getString(R.string.action_enable),
            if (settings.enabled) getString(R.string.status_running) else getString(R.string.status_stopped),
        )
        binding.rowStrength.set(
            "${getString(R.string.action_strength)}  ${(settings.strength * 100).toInt()}%",
            getString(R.string.hint_strength),
        )
        binding.rowDialogueTarget.set(
            "${getString(R.string.action_dialogue_target)}  %.1f LUFS".format(settings.targetDialogueLufs),
            getString(R.string.hint_dialogue_target),
        )
        binding.rowMusicHeadroom.set(
            "${getString(R.string.action_music_headroom)}  %.1f dB".format(settings.musicCeilingOffsetDb),
            getString(R.string.hint_music_headroom),
        )
        binding.rowNightMode.set(
            getString(R.string.action_night_mode),
            if (settings.nightMode) "On" else "Off",
        )
        binding.rowCapture.set(
            getString(R.string.action_grant_capture),
            if (AnalysisSourceFactory.hasRecordPermission(this)) {
                getString(R.string.hint_capture)
            } else {
                "Audio permission not granted yet · ${getString(R.string.hint_capture)}"
            },
        )
        binding.rowNotificationAccess.set(
            getString(R.string.action_grant_notifications),
            if (monitor.hasAccess()) "Granted" else getString(R.string.hint_notifications),
        )
        binding.rowMicrophone.set(
            getString(R.string.action_use_microphone),
            if (settings.allowMicrophoneFallback) "On - ${getString(R.string.hint_microphone)}" else "Off",
        )
        binding.rowBoot.set(
            getString(R.string.action_start_on_boot),
            if (settings.startOnBoot) "On" else "Off",
        )
    }

    private fun render(status: StabilizerStatus) {
        binding.meter.state = status.state
        binding.statusText.text = buildString {
            appendLine(if (status.running) getString(R.string.status_running) else getString(R.string.status_stopped))
            appendLine(
                status.sourceLabel?.let { getString(R.string.status_analysis_format, it) }
                    ?: getString(R.string.status_no_analysis),
            )
            appendLine(
                status.processorLabel?.let { getString(R.string.status_effect_format, it) }
                    ?: getString(R.string.status_no_effect),
            )
            val profile = status.state.profile
            val app = status.playingPackage
            append(
                getString(
                    R.string.status_profile_format,
                    if (app != null) "${profile.label} ($app)" else profile.label,
                ),
            )
            if (profile == AppProfile.GENERIC && app == null && !monitor.hasAccess()) {
                append(" · ")
                append(getString(R.string.hint_notifications))
            }
        }
    }

    // -------------------------------------------------------- row behaviour

    private fun ViewSettingRowBinding.set(title: String, subtitle: String) {
        rowTitle.text = title
        rowSubtitle.text = subtitle
    }

    private fun ViewSettingRowBinding.onSelect(action: () -> Unit) {
        root.setOnClickListener { action() }
    }

    /** Left/right on the remote nudges the value; centre does nothing. */
    private fun ViewSettingRowBinding.onAdjust(action: (Int) -> Unit) {
        root.setOnKeyListener { _: View, keyCode: Int, event: KeyEvent ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    action(-1); true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    action(+1); true
                }
                else -> false
            }
        }
    }
}
