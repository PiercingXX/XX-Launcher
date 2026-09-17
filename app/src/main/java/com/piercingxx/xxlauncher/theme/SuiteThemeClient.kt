package com.piercingxx.xxlauncher.theme

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Ask xx-apps (the suite theme engine) to persist and fan out a theme.
 * Returns false when xx-apps is not installed so the caller can fall back
 * to local [ThemeBroadcaster] fan-out.
 */
object SuiteThemeClient {

    const val APPS_PACKAGE = "com.piercingxx.apps"
    const val ACTION_SET_SUITE_THEME = "xx.apps.SET_SUITE_THEME"
    const val EXTRA_PRESET_KEY = "xx.apps.extra.PRESET_KEY"

    fun isAppsInstalled(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(APPS_PACKAGE, 0)
            true
        }.getOrDefault(false)

    fun requestIntent(presetKey: String, colors: ThemeColors): Intent =
        Intent(ACTION_SET_SUITE_THEME)
            .setPackage(APPS_PACKAGE)
            .putExtra(EXTRA_PRESET_KEY, presetKey)
            .putExtra(ThemeBroadcaster.EXTRA_THEME_NAME, ThemeBroadcaster.displayName(presetKey))
            .putExtra(ThemeBroadcaster.EXTRA_BACKGROUND, colors.backgroundColor)

    fun request(context: Context, presetKey: String, colors: ThemeColors): Boolean {
        if (!isAppsInstalled(context)) return false
        context.sendBroadcast(requestIntent(presetKey, colors))
        return true
    }
}
