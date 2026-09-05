package dev.autotune.tv.session

import android.service.notification.NotificationListenerService

/**
 * Exists purely so the user can grant notification access.
 *
 * `MediaSessionManager.getActiveSessions` requires an enabled notification
 * listener component; it is the only supported way for an ordinary app to learn
 * which app is currently playing. No notification is ever read here.
 */
class PlaybackNotificationListener : NotificationListenerService()
