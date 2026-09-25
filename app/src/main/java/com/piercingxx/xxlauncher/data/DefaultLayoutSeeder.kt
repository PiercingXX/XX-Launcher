package com.piercingxx.xxlauncher.data

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import com.piercingxx.xxlauncher.folder.FolderManager
import com.piercingxx.xxlauncher.util.USER_PERSONAL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Seeds the out-of-the-box home screen on first launch with the PiercingXX
 * suite. Apps keep their own names — the slot shows whatever the installed
 * app calls itself; the seeder never renames.
 *
 *   slot 1            -> xx-note
 *   Audio    (folder) -> xx-audiobook, SKPP-Radio
 *   Comms    (folder) -> xx-dialer, Txxt, xx-chat, xx-email
 *   slot 4            -> xx-calendar
 *   Tools    (folder) -> Waterfox, xx-calculator, xx-camera, xx-photos, xx-weather, xx-auth
 *
 * This is the operator's Pixel 9 Pro home screen as of 2026-09-25, kept as
 * the out-of-the-box layout for every fresh install. Swipe left opens Skippy
 * (matched by app label — it installs as a PWA so its package name varies);
 * swipe right opens xx-camera. Suite apps that are
 * not installed are skipped; folders with no members are not created. There
 * are no third-party fallbacks: "Reset home layout" re-seeds once the suite
 * is installed. Never overwrites a configured home screen.
 */
object DefaultLayoutSeeder {

    data class ResolvedApp(
        val packageName: String,
        val activityClassName: String,
        val label: String,
    )

    /** Injectable for tests; the real implementation talks to LauncherApps. */
    interface AppResolver {
        fun resolvePackage(packageName: String): ResolvedApp?
        fun resolveByLabel(label: String): ResolvedApp?
    }

    private const val PKG_XX_NOTE = "com.piercingxx.xxnote"
    private const val PKG_XX_AUDIOBOOK = "com.piercingxx.audiobook"
    private const val PKG_SKPP_RADIO = "com.skpp.radio"
    private const val PKG_XX_DIALER = "com.piercingxx.xxdialer"
    private const val PKG_TXXT = "com.piercingxx.txxt"
    private const val PKG_XX_CHAT = "com.piercingxx.chat"
    private const val PKG_XX_EMAIL = "dev.xxemail"
    private const val PKG_XX_CALENDAR = "com.piercingxx.calendar"
    private const val PKG_XX_CALCULATOR = "com.piercingxx.xxcalculator"
    private const val PKG_XX_CAMERA = "com.piercingxx.camera"
    private const val PKG_XX_PHOTOS = "com.piercingxx.photos"
    private const val PKG_WATERFOX = "net.waterfox.android.release"
    private const val PKG_XX_WEATHER = "com.xx.weather"
    private const val PKG_XX_AUTH = "com.piercingxx.xxauth"
    private const val LABEL_SKIPPY = "Skippy"

    /** Hidden out of the box; they only ever show up via search. */
    private val DEFAULT_HIDDEN_PACKAGES = listOf(
        "com.google.android.apps.recorder",
        "com.google.android.apps.subscriptions.red",
        "com.google.android.apps.docs.editors.docs",
        "com.google.android.youtube",
        "com.google.android.apps.pixel.nowplaying",
        "com.google.android.apps.wearables.maestro.companion",
        "com.google.android.apps.safetyhub",
        "com.google.android.apps.betterbug",
        "com.google.android.videos",
        "com.google.android.apps.walletnfcrel",
        "com.irobot.home",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.tips",
    )

    /** One planned home slot: an app, or a named folder of apps. */
    data class PlannedSlot(
        val label: String,
        val app: ResolvedApp? = null,
        val folderMembers: List<ResolvedApp> = emptyList(),
    )

    data class Plan(
        val slots: List<PlannedSlot>,
        /** Package name -> label; the drawer, search, and folders show it too. */
        val renameLabels: Map<String, String>,
        val swipeLeft: ResolvedApp?,
        val swipeRight: ResolvedApp?,
        val hiddenPackages: List<String>,
    )

    fun plan(resolver: AppResolver): Plan {
        val notes = resolver.resolvePackage(PKG_XX_NOTE)
        val calendar = resolver.resolvePackage(PKG_XX_CALENDAR)
        val camera = resolver.resolvePackage(PKG_XX_CAMERA)

        val audio = listOfNotNull(
            resolver.resolvePackage(PKG_XX_AUDIOBOOK),
            resolver.resolvePackage(PKG_SKPP_RADIO),
        )
        val comms = listOfNotNull(
            resolver.resolvePackage(PKG_XX_DIALER),
            resolver.resolvePackage(PKG_TXXT),
            resolver.resolvePackage(PKG_XX_CHAT),
            resolver.resolvePackage(PKG_XX_EMAIL),
        )
        val tools = listOfNotNull(
            resolver.resolvePackage(PKG_WATERFOX),
            resolver.resolvePackage(PKG_XX_CALCULATOR),
            camera,
            resolver.resolvePackage(PKG_XX_PHOTOS),
            resolver.resolvePackage(PKG_XX_WEATHER),
            resolver.resolvePackage(PKG_XX_AUTH),
        )

        // App slots carry the app's own label; only folders have a name of their own.
        val slots = buildList {
            notes?.let { add(PlannedSlot(it.label, app = it)) }
            if (audio.isNotEmpty()) add(PlannedSlot("Audio", folderMembers = audio))
            if (comms.isNotEmpty()) add(PlannedSlot("Comms", folderMembers = comms))
            calendar?.let { add(PlannedSlot(it.label, app = it)) }
            if (tools.isNotEmpty()) add(PlannedSlot("Tools", folderMembers = tools))
        }

        return Plan(
            slots = slots,
            renameLabels = emptyMap(),
            swipeLeft = resolver.resolveByLabel(LABEL_SKIPPY)?.copy(label = LABEL_SKIPPY),
            swipeRight = camera,
            hiddenPackages = DEFAULT_HIDDEN_PACKAGES,
        )
    }

    /** Applies the default layout; returns true if the home screen changed. */
    suspend fun applyIfNeeded(
        context: Context,
        settings: SettingsRepository,
        folders: FolderManager,
        resolver: AppResolver = SystemAppResolver(context),
    ): Boolean = withContext(Dispatchers.IO) {
        // Never clobber a home screen the user has already set up.
        if ((1..SettingsRepository.MAX_SLOTS).any { !settings.getSlot(it).isEmpty }) {
            return@withContext false
        }
        if (folders.getFolders().isNotEmpty()) return@withContext false

        val plan = plan(resolver)

        plan.renameLabels.forEach { (packageName, label) ->
            settings.setRenameLabel("$packageName|$USER_PERSONAL", label)
        }

        var slot = 1
        for (planned in plan.slots) {
            if (slot > SettingsRepository.MAX_SLOTS) break
            val app = planned.app
            if (app != null) {
                settings.setSlot(
                    slot++,
                    SlotEntry(
                        label = planned.label,
                        packageName = app.packageName,
                        activityClassName = app.activityClassName,
                        userToken = USER_PERSONAL,
                    ),
                )
            } else {
                val folder = folders.createFolder(planned.label).getOrNull() ?: continue
                planned.folderMembers.forEach { member ->
                    folders.addMember(folder.id, member.toAppInfo())
                }
                settings.setSlot(slot++, SlotEntry(label = planned.label, folderId = folder.id))
            }
        }
        if (slot > 1) settings.slotCount = slot - 1

        plan.swipeLeft?.let {
            settings.swipeLeftApp = "${it.packageName}|${it.activityClassName}|$USER_PERSONAL"
        }
        plan.swipeRight?.let {
            settings.swipeRightApp = "${it.packageName}|${it.activityClassName}|$USER_PERSONAL"
        }

        settings.hiddenApps =
            settings.hiddenApps + plan.hiddenPackages.map { "$it|$USER_PERSONAL" }

        slot > 1
    }

    private fun ResolvedApp.toAppInfo() = AppInfo(
        packageName = packageName,
        activityClassName = activityClassName,
        label = label,
        userToken = USER_PERSONAL,
        isSystem = false,
        installedAt = 0L,
        sizeBytes = 0L,
    )

    class SystemAppResolver(private val context: Context) : AppResolver {
        private val launcherApps =
            context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        private val myUser = Process.myUserHandle()

        override fun resolvePackage(packageName: String): ResolvedApp? = runCatching {
            launcherApps.getActivityList(packageName, myUser).firstOrNull()?.toResolvedApp()
        }.getOrNull()

        override fun resolveByLabel(label: String): ResolvedApp? = runCatching {
            launcherApps.getActivityList(null, myUser)
                .firstOrNull { it.label.toString().equals(label, ignoreCase = true) }
                ?.toResolvedApp()
        }.getOrNull()

        private fun android.content.pm.LauncherActivityInfo.toResolvedApp() = ResolvedApp(
            packageName = applicationInfo.packageName,
            activityClassName = componentName.className,
            label = label.toString(),
        )
    }
}
