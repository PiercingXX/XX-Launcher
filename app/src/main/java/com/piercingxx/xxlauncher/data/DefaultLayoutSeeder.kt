package com.piercingxx.xxlauncher.data

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.provider.MediaStore
import android.provider.Telephony
import com.piercingxx.xxlauncher.folder.FolderManager
import com.piercingxx.xxlauncher.util.USER_PERSONAL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Seeds the out-of-the-box home screen on first launch:
 *
 *   Notes             -> Google Keep
 *   Audio    (folder) -> Audiobook (Audiobookshelf), Music (YouTube Music)
 *   Comms    (folder) -> Phone, Text, Email (Gmail), Chat (Synology Chat),
 *                        SoftPhone (Cloud Softphone)
 *   Calendar          -> Google Calendar
 *   Tools    (folder) -> Waterfox, Calculator, Camera, Photos (Synology Photos)
 *
 * Swipe left opens Skippy (matched by app label — it installs as a PWA so
 * its package name varies); swipe right opens the camera. Members that don't
 * resolve to an installed app are skipped; folders with no members are not
 * created. Never overwrites a configured home screen.
 */
object DefaultLayoutSeeder {

    data class ResolvedApp(
        val packageName: String,
        val activityClassName: String,
        val label: String,
    )

    /** Injectable for tests; the real implementation talks to LauncherApps/PackageManager. */
    interface AppResolver {
        fun resolvePackage(packageName: String): ResolvedApp?
        fun resolveByLabel(label: String): ResolvedApp?
        fun resolveDialer(): ResolvedApp?
        fun resolveSmsApp(): ResolvedApp?
        fun resolveCameraApp(): ResolvedApp?
        fun resolveCalculator(): ResolvedApp?
    }

    private const val PKG_KEEP = "com.google.android.keep"
    private const val PKG_AUDIOBOOKSHELF = "com.audiobookshelf.app"
    private const val PKG_YT_MUSIC = "com.google.android.apps.youtube.music"
    private const val PKG_GMAIL = "com.google.android.gm"
    private const val PKG_SYNOLOGY_CHAT = "com.synology.dschat"
    private const val PKG_CLOUD_SOFTPHONE = "cz.acrobits.softphone.cloudphone"
    private const val PKG_CALENDAR = "com.google.android.calendar"
    private const val PKG_SYNOLOGY_PHOTOS = "com.synology.projectkailash"
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
    private val PKG_WATERFOX = listOf("net.waterfox.android.release", "net.waterfox.android")
    private val PKG_DIALER_FALLBACKS = listOf("com.google.android.dialer", "com.android.dialer")
    private val PKG_SMS_FALLBACKS = listOf("com.google.android.apps.messaging", "com.android.messaging")
    private val PKG_CAMERA_FALLBACKS = listOf("com.google.android.GoogleCamera", "com.android.camera2", "com.android.camera")
    private val PKG_CALCULATOR_FALLBACKS = listOf("com.google.android.calculator", "com.android.calculator2")

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
        val notes = resolver.resolvePackage(PKG_KEEP)?.copy(label = "Notes")
        val calendar = resolver.resolvePackage(PKG_CALENDAR)?.copy(label = "Calendar")

        val audio = listOfNotNull(
            resolver.resolvePackage(PKG_AUDIOBOOKSHELF)?.copy(label = "Audiobook"),
            resolver.resolvePackage(PKG_YT_MUSIC)?.copy(label = "Music"),
        )
        val comms = listOfNotNull(
            (resolver.resolveDialer() ?: fallback(resolver, PKG_DIALER_FALLBACKS))
                ?.copy(label = "Phone"),
            (resolver.resolveSmsApp() ?: fallback(resolver, PKG_SMS_FALLBACKS))
                ?.copy(label = "Text"),
            resolver.resolvePackage(PKG_GMAIL)?.copy(label = "Email"),
            resolver.resolvePackage(PKG_SYNOLOGY_CHAT)?.copy(label = "Chat"),
            resolver.resolvePackage(PKG_CLOUD_SOFTPHONE)?.copy(label = "SoftPhone"),
        )
        val camera = (resolver.resolveCameraApp() ?: fallback(resolver, PKG_CAMERA_FALLBACKS))
            ?.copy(label = "Camera")
        val tools = listOfNotNull(
            fallback(resolver, PKG_WATERFOX)?.copy(label = "Waterfox"),
            (resolver.resolveCalculator() ?: fallback(resolver, PKG_CALCULATOR_FALLBACKS))
                ?.copy(label = "Calculator"),
            camera,
            resolver.resolvePackage(PKG_SYNOLOGY_PHOTOS)?.copy(label = "Photos"),
        )

        val slots = buildList {
            notes?.let { add(PlannedSlot(it.label, app = it)) }
            if (audio.isNotEmpty()) add(PlannedSlot("Audio", folderMembers = audio))
            if (comms.isNotEmpty()) add(PlannedSlot("Comms", folderMembers = comms))
            calendar?.let { add(PlannedSlot(it.label, app = it)) }
            if (tools.isNotEmpty()) add(PlannedSlot("Tools", folderMembers = tools))
        }

        // Every seeded name is a real rename, so the drawer, search, and
        // folders all show the same label as the home screen.
        val renames = (listOfNotNull(notes, calendar) + audio + comms + tools)
            .associate { it.packageName to it.label }

        return Plan(
            slots = slots,
            renameLabels = renames,
            swipeLeft = resolver.resolveByLabel(LABEL_SKIPPY)?.copy(label = LABEL_SKIPPY),
            swipeRight = camera,
            hiddenPackages = DEFAULT_HIDDEN_PACKAGES,
        )
    }

    private fun fallback(resolver: AppResolver, packages: List<String>): ResolvedApp? =
        packages.firstNotNullOfOrNull(resolver::resolvePackage)

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

        override fun resolveDialer(): ResolvedApp? = resolveIntent(Intent(Intent.ACTION_DIAL))

        override fun resolveSmsApp(): ResolvedApp? =
            runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
                ?.let(::resolvePackage)
                ?: resolveCategory(Intent.CATEGORY_APP_MESSAGING)

        override fun resolveCameraApp(): ResolvedApp? =
            resolveIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE))

        override fun resolveCalculator(): ResolvedApp? =
            resolveCategory(Intent.CATEGORY_APP_CALCULATOR)

        private fun resolveCategory(category: String): ResolvedApp? =
            resolveIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category))

        private fun resolveIntent(intent: Intent): ResolvedApp? = runCatching {
            val resolved = context.packageManager.resolveActivity(intent, 0)
                ?.activityInfo?.packageName ?: return null
            // "android" is the chooser stub, not a real app.
            if (resolved == "android") return null
            resolvePackage(resolved)
        }.getOrNull()

        private fun android.content.pm.LauncherActivityInfo.toResolvedApp() = ResolvedApp(
            packageName = applicationInfo.packageName,
            activityClassName = componentName.className,
            label = label.toString(),
        )
    }
}
