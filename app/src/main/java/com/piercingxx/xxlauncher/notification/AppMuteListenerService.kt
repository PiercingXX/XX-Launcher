package com.piercingxx.xxlauncher.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.piercingxx.xxlauncher.data.SettingsRepository

/**
 * Implements "Disable for…": while a package's mute window (persisted in
 * [SettingsRepository]) is active, every notification it posts is removed.
 */
class AppMuteListenerService : NotificationListenerService() {

    private lateinit var settings: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        // Sweep anything posted while the listener was down.
        activeNotifications?.forEach { sbn ->
            if (isMuted(sbn.packageName)) cancelNotification(sbn.key)
        }
    }

    override fun onListenerDisconnected() {
        if (instance == this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isMuted(sbn.packageName)) cancelNotification(sbn.key)
    }

    private fun isMuted(packageName: String): Boolean =
        settings.getMuteUntil(packageName) > System.currentTimeMillis()

    /** Cancels the package's current notifications right when a mute starts. */
    fun cancelAllFrom(packageName: String) {
        activeNotifications
            ?.filter { it.packageName == packageName }
            ?.forEach { cancelNotification(it.key) }
    }

    companion object {
        @Volatile
        var instance: AppMuteListenerService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}
