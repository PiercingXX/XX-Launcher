package com.piercingxx.xxlauncher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.piercingxx.xxlauncher.util.serializeUser
import com.piercingxx.xxlauncher.util.userFromToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.Normalizer

private const val TAG = "AppRepository"
private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
private val SEPARATORS = Regex("[-_+,. ]")
private const val ONE_HOUR_MS = 60 * 60 * 1000L

data class AppInfo(
    val packageName: String,
    val activityClassName: String?,
    val label: String,
    val userToken: String,
    val isSystem: Boolean,
    val installedAt: Long,
    val sizeBytes: Long,
    /** Non-null for Android pinned-shortcut rows. */
    val shortcutId: String? = null,
    /** Pre-rename label, so search still matches the app's real name. */
    val originalLabel: String = "",
) {
    val isWorkProfile: Boolean get() = userToken == com.piercingxx.xxlauncher.util.USER_MANAGED
    val isNew: Boolean get() = System.currentTimeMillis() - installedAt < ONE_HOUR_MS
    val isShortcut: Boolean get() = shortcutId != null

    /** Stable identity for hidden/pinned/renamed persistence. */
    val key: String
        get() = if (shortcutId != null) "$packageName|$shortcutId|$userToken"
        else "$packageName|$userToken"

    /**
     * Forgiving matcher: case-insensitive substring on the shown label (and on
     * the pre-rename original, so a renamed app is still findable by its real
     * name), plus a separator/diacritic-stripped pass so "eink" matches "E-Ink".
     */
    fun matches(query: CharSequence): Boolean {
        if (query.isBlank()) return true
        return labelMatches(label, query) ||
            (originalLabel.isNotBlank() && labelMatches(originalLabel, query))
    }

    private fun labelMatches(candidate: String, query: CharSequence): Boolean =
        candidate.contains(query.trim(), true) ||
            Normalizer.normalize(candidate, Normalizer.Form.NFD)
                .replace(DIACRITICS, "")
                .replace(SEPARATORS, "")
                .contains(query.trim(), true)
}

/**
 * Enumerates launchable activities across all user profiles via [LauncherApps]
 * (the correct API for a launcher: covers work profiles and install times) and
 * layers persisted rename labels on top. Hidden/pinned state lives in
 * [SettingsRepository] so it survives process death.
 */
class AppRepository(private val context: Context, private val settings: SettingsRepository) {

    private val launcherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _apps = MutableLiveData<List<AppInfo>>(emptyList())
    val apps: LiveData<List<AppInfo>> = _apps

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            pruneRemovedPackage(packageName, serializeUser(user))
            refresh()
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
        override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = refresh()
        override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = refresh()
    }

    init {
        launcherApps.registerCallback(callback)
        refresh()
    }

    fun refresh() {
        scope.launch {
            try {
                _apps.postValue(loadApps())
            } catch (e: Exception) {
                Log.w(TAG, "Unable to enumerate apps", e)
            }
        }
    }

    private fun loadApps(): List<AppInfo> {
        val result = mutableListOf<AppInfo>()

        // Other home-screen launchers (and this one) never show up in the list.
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val otherLaunchers = context.packageManager
            .queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }

        for (profile in userManager.userProfiles) {
            val token = serializeUser(profile)
            for (activity in launcherApps.getActivityList(null, profile)) {
                val packageName = activity.applicationInfo.packageName
                if (packageName == context.packageName || packageName in otherLaunchers) continue
                val original = activity.label.toString()
                val rename = settings.getRenameLabel("$packageName|$token")
                result.add(
                    AppInfo(
                        packageName = packageName,
                        activityClassName = activity.componentName.className,
                        label = rename.ifBlank { original },
                        originalLabel = original,
                        userToken = token,
                        isSystem = (activity.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        installedAt = activity.firstInstallTime,
                        sizeBytes = apkSize(activity.applicationInfo),
                    )
                )
            }
        }

        result.addAll(loadPinnedShortcuts())

        return result
            .distinctBy { it.packageName + "|" + it.activityClassName + "|" + it.shortcutId + "|" + it.userToken }
            .sortedBy { it.label.lowercase() }
    }

    /** Android pinned shortcuts become drawer rows (needs shortcut-host permission). */
    private fun loadPinnedShortcuts(): List<AppInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        if (runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false).not()) {
            return emptyList()
        }
        val shortcuts = mutableListOf<AppInfo>()
        val query = LauncherApps.ShortcutQuery().apply {
            setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        }
        for (profile in launcherApps.profiles) {
            val token = serializeUser(profile)
            try {
                launcherApps.getShortcuts(query, profile)?.forEach { shortcut ->
                    if (!shortcut.isPinned) return@forEach
                    val original = shortcut.shortLabel?.toString()
                        ?: shortcut.longLabel?.toString().orEmpty()
                    val rename =
                        settings.getRenameLabel("${shortcut.`package`}|${shortcut.id}|$token")
                    shortcuts.add(
                        AppInfo(
                            packageName = shortcut.`package`,
                            activityClassName = null,
                            label = rename.ifBlank { original },
                            originalLabel = original,
                            userToken = token,
                            isSystem = false,
                            installedAt = 0L,
                            sizeBytes = 0L,
                            shortcutId = shortcut.id,
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to enumerate pinned shortcuts", e)
            }
        }
        return shortcuts
    }

    /** Un-pins an Android shortcut by re-pinning the package's remaining set. */
    fun deletePinnedShortcut(app: AppInfo) {
        val shortcutId = app.shortcutId ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val user = context.userFromToken(app.userToken)
        try {
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(app.packageName)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }
            val remaining = launcherApps.getShortcuts(query, user)
                ?.filter { it.id != shortcutId }
                ?.map { it.id }
                ?: return
            launcherApps.pinShortcuts(app.packageName, remaining, user)
            refresh()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to delete pinned shortcut", e)
        }
    }

    private fun apkSize(applicationInfo: ApplicationInfo): Long {
        val paths = buildList {
            add(applicationInfo.sourceDir)
            applicationInfo.splitSourceDirs?.let { addAll(it) }
        }.distinct()
        return paths.sumOf { path -> runCatching { File(path).length() }.getOrDefault(0L) }
    }

    /** Drops an uninstalled package from slots, pins, and hidden apps. */
    private fun pruneRemovedPackage(packageName: String, userToken: String) {
        val key = "$packageName|$userToken"
        settings.pinnedApps = settings.pinnedApps.filter { it != key }
        settings.hiddenApps = settings.hiddenApps.filter { it != key }.toSet()
        for (slot in 1..SettingsRepository.MAX_SLOTS) {
            val entry = settings.getSlot(slot)
            if (!entry.isFolder && entry.packageName == packageName && entry.userToken == userToken) {
                settings.clearSlot(slot)
            }
        }
    }

    /** Launches into the right profile via LauncherApps. */
    fun launch(
        packageName: String,
        activityClassName: String?,
        userToken: String,
        shortcutId: String? = null,
        opts: android.os.Bundle? = null,
    ): Boolean {
        val user = context.userFromToken(userToken)
        if (shortcutId != null) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                try {
                    launcherApps.startShortcut(packageName, shortcutId, null, opts, user)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to launch shortcut $shortcutId", e)
                    false
                }
            } else {
                false
            }
        }
        return try {
            val activities = launcherApps.getActivityList(packageName, user)
            val component = activities
                .firstOrNull { activityClassName != null && it.componentName.className == activityClassName }
                ?.componentName
                ?: activities.firstOrNull()?.componentName
                ?: return false
            launcherApps.startMainActivity(component, user, null, opts)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to launch $packageName", e)
            false
        }
    }

    fun launch(app: AppInfo): Boolean =
        launch(app.packageName, app.activityClassName, app.userToken, app.shortcutId)

    // Persisted state passthroughs keyed by "package|userToken"
    fun isHidden(app: AppInfo) = settings.isHidden(app.key)
    fun isPinned(app: AppInfo) = settings.isPinned(app.key)

    fun toggleHidden(app: AppInfo) = settings.toggleHidden(app.key)
    fun togglePinned(app: AppInfo) = settings.togglePinned(app.key)

    /**
     * Persists a rename (blank clears it back to the real label) and rewrites
     * the label copy of every home slot holding the app, so home and drawer
     * agree immediately. Folder members store only keys and re-resolve their
     * labels from the rename map on read, so they need no rewrite.
     *
     * Rewrites are slot-keyed and label-only: a rename never moves, clears,
     * or re-points a slot, and a slot that stopped holding the app between
     * the read and the write is left alone.
     */
    fun rename(app: AppInfo, newLabel: String) {
        val trimmed = newLabel.trim()
        settings.setRenameLabel(app.key, trimmed)
        val shown = RenamePropagator.displayLabel(trimmed, app.originalLabel)
        val slots = (1..SettingsRepository.MAX_SLOTS).associateWith(settings::getSlot)
        RenamePropagator.propagate(slots, app.key, shown).forEach { (slot, entry) ->
            val current = settings.getSlot(slot)
            if (RenamePropagator.holdsSameItem(entry, current)) {
                settings.setSlot(slot, current.copy(label = entry.label))
            }
        }
        refresh()
    }
}
