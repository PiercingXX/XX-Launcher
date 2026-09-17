package com.piercingxx.xxlauncher.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.piercingxx.xxlauncher.LauncherApplication

/**
 * Apply a suite theme that xx-apps already fanned out. Do not publish() —
 * that would loop back into xx-apps.
 */
class ThemeSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val applied = handle(
            action = intent?.action,
            themeName = intent?.getStringExtra(ThemeBroadcaster.EXTRA_THEME_NAME),
            background = if (intent?.hasExtra(ThemeBroadcaster.EXTRA_BACKGROUND) == true) {
                intent.getIntExtra(ThemeBroadcaster.EXTRA_BACKGROUND, 0)
            } else {
                null
            },
        ) ?: return
        val app = context.applicationContext as? LauncherApplication ?: return
        app.settings.themePreset = applied.presetKey
        if (applied.presetKey == "custom" && applied.backgroundColor != null) {
            app.settings.customBgColor = applied.backgroundColor
        }
        app.themeManager.applyWallpaper()
    }

    companion object {
        data class Incoming(val presetKey: String, val backgroundColor: Int?)

        fun handle(action: String?, themeName: String?, background: Int?): Incoming? {
            if (action != ThemeBroadcaster.ACTION_THEME_CHANGED) return null
            val key = ThemeBroadcaster.presetKeyFromDisplayName(themeName) ?: return null
            return Incoming(key, background)
        }
    }
}
