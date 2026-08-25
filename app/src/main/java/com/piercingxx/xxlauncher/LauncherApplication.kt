package com.piercingxx.xxlauncher

import android.app.Application
import android.content.Context
import com.piercingxx.xxlauncher.data.AppRepository
import com.piercingxx.xxlauncher.data.SettingsRepository
import com.piercingxx.xxlauncher.folder.FolderManager
import com.piercingxx.xxlauncher.theme.ThemeManager

/** Single shared home for the repositories so activities don't re-enumerate apps. */
class LauncherApplication : Application() {

    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val appRepo: AppRepository by lazy { AppRepository(this, settings) }
    val folders: FolderManager by lazy { FolderManager(this, settings) }
    val themeManager: ThemeManager by lazy { ThemeManager(this, settings) }

    override fun onCreate() {
        super.onCreate()
        themeManager.setAppearanceMode(settings.appearanceMode)
    }

    companion object {
        fun from(context: Context): LauncherApplication =
            context.applicationContext as LauncherApplication
    }
}
