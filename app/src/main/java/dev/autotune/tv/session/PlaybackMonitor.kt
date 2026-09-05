package dev.autotune.tv.session

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.util.Log
import dev.autotune.core.engine.AppProfile

/**
 * Tracks which app is actually playing, so the engine can switch profiles.
 *
 * Falls back silently to [AppProfile.GENERIC] when notification access has not
 * been granted - knowing the app makes the correction better, it is not
 * required for it to work.
 */
class PlaybackMonitor(private val context: Context) {

    private val component = ComponentName(context, PlaybackNotificationListener::class.java)
    private val manager = context.getSystemService(MediaSessionManager::class.java)

    private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var onProfileChanged: ((AppProfile, String?) -> Unit)? = null

    var currentPackage: String? = null
        private set

    var currentProfile: AppProfile = AppProfile.GENERIC
        private set

    fun hasAccess(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    fun start(onChanged: (AppProfile, String?) -> Unit) {
        onProfileChanged = onChanged
        if (!hasAccess() || manager == null) return
        val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }
        listener = sessionListener
        runCatching { manager.addOnActiveSessionsChangedListener(sessionListener, component) }
            .onFailure { Log.w(TAG, "could not observe media sessions", it) }
        refresh()
    }

    /**
     * Re-reads the active sessions. Called on session-list changes and polled by
     * the service, because a session that merely starts *playing* does not
     * change the list.
     */
    fun refresh() {
        if (manager == null || !hasAccess()) return
        val playing = runCatching { manager.getActiveSessions(component) }
            .getOrElse {
                Log.w(TAG, "could not read media sessions", it)
                return
            }
            .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?.packageName

        if (playing == currentPackage) return
        currentPackage = playing
        val profile = AppProfile.forPackage(playing)
        currentProfile = profile
        onProfileChanged?.invoke(profile, playing)
    }

    fun stop() {
        listener?.let { existing ->
            runCatching { manager?.removeOnActiveSessionsChangedListener(existing) }
        }
        listener = null
        onProfileChanged = null
    }

    private companion object {
        const val TAG = "PlaybackMonitor"
        const val ENABLED_LISTENERS = "enabled_notification_listeners"
    }
}
