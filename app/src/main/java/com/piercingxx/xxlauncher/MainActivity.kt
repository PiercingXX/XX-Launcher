package com.piercingxx.xxlauncher

import android.Manifest
import android.animation.LayoutTransition
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.piercingxx.xxlauncher.accessibility.GestureAccessibilityService
import com.piercingxx.xxlauncher.data.AppRepository
import com.piercingxx.xxlauncher.data.DefaultLayoutSeeder
import com.piercingxx.xxlauncher.data.SettingsRepository
import com.piercingxx.xxlauncher.data.SlotEntry
import com.piercingxx.xxlauncher.databinding.ActivityHomeBinding
import com.piercingxx.xxlauncher.menu.ItemActionMenu
import com.piercingxx.xxlauncher.theme.ThemeManager
import com.piercingxx.xxlauncher.theme.applyLauncherFont
import com.piercingxx.xxlauncher.theme.applyLauncherTheme
import com.piercingxx.xxlauncher.util.USER_PERSONAL
import com.piercingxx.xxlauncher.util.expandNotificationDrawer
import com.piercingxx.xxlauncher.util.hideNavigationBar
import com.piercingxx.xxlauncher.util.hideStatusBar
import com.piercingxx.xxlauncher.util.isEinkDisplay
import com.piercingxx.xxlauncher.util.isPackageInstalled
import com.piercingxx.xxlauncher.util.openCameraApp
import com.piercingxx.xxlauncher.util.openDialerApp
import com.piercingxx.xxlauncher.util.openWebSearch
import com.piercingxx.xxlauncher.util.showStatusBar
import com.piercingxx.xxlauncher.util.showToast
import com.piercingxx.xxlauncher.util.userFromToken
import com.piercingxx.xxlauncher.widgets.WidgetContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var appRepo: AppRepository
    private lateinit var settings: SettingsRepository
    private lateinit var themeManager: ThemeManager
    private lateinit var gestureDetector: GestureDetector
    private lateinit var widgetContainer: WidgetContainer
    private lateinit var itemMenu: ItemActionMenu

    private val slotViews = mutableListOf<TextView>()

    /** Slot whose folder is dropped open inline, or -1. */
    private var expandedFolderSlot = -1
    private val folderRowViews = mutableListOf<View>()

    private val weatherPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) widgetContainer.refreshWeather(force = true)
    }

    private val pickSlotAppLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        val slot = data.getIntExtra(AppPickerActivity.EXTRA_SLOT_INDEX, -1)
        if (result.resultCode != RESULT_OK || slot < 1) return@registerForActivityResult
        when {
            data.getBooleanExtra(AppPickerActivity.EXTRA_REARRANGE, false) ->
                showSlotRearrangeDialog()

            data.getBooleanExtra(AppPickerActivity.EXTRA_FOLDER_OPTIONS, false) -> {
                val entry = settings.getSlot(slot)
                if (entry.isFolder) {
                    itemMenu.showFolderMenu(entry.folderId, entry.label) { renderHomeSlots() }
                }
            }

            data.hasExtra(AppPickerActivity.EXTRA_MOVE_UP) ->
                swapSlots(
                    slot,
                    if (data.getBooleanExtra(AppPickerActivity.EXTRA_MOVE_UP, true)) slot - 1
                    else slot + 1,
                )

            data.getBooleanExtra(AppPickerActivity.EXTRA_CLEARED, false) ->
                settings.clearSlot(slot)

            data.hasExtra(AppPickerActivity.EXTRA_FOLDER_ID) -> {
                val folderId = data.getIntExtra(AppPickerActivity.EXTRA_FOLDER_ID, -1)
                val label = data.getStringExtra(AppPickerActivity.EXTRA_LABEL).orEmpty()
                settings.setSlot(slot, SlotEntry(label = label, folderId = folderId))
            }

            else -> settings.setSlot(
                slot,
                SlotEntry(
                    label = data.getStringExtra(AppPickerActivity.EXTRA_LABEL).orEmpty(),
                    packageName = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE).orEmpty(),
                    activityClassName = data.getStringExtra(AppPickerActivity.EXTRA_ACTIVITY).orEmpty(),
                    userToken = data.getStringExtra(AppPickerActivity.EXTRA_USER) ?: USER_PERSONAL,
                    shortcutId = data.getStringExtra(AppPickerActivity.EXTRA_SHORTCUT_ID).orEmpty(),
                ),
            )
        }
        renderHomeSlots()
    }

    private val defaultLauncherLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    // The home container's touch listener drives a GestureDetector; the same
    // actions are exposed to TalkBack as explicit accessibility actions in
    // initAccessibilityActions(), so there is no click to forward.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = LauncherApplication.from(this)
        appRepo = app.appRepo
        settings = app.settings
        themeManager = app.themeManager
        itemMenu = ItemActionMenu(this, appRepo, settings, themeManager, app.folders)

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gestureDetector = GestureDetector(this, HomeGestureListener())
        binding.homeContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        // Home is the bottom of the stack: claim back so edge swipes don't
        // play the system's predictive-back animation on the launcher.
        onBackPressedDispatcher.addCallback(this) { collapseFolder() }

        widgetContainer = WidgetContainer(this).also {
            it.settings = settings
            it.themeManager = themeManager
            it.onWeatherPermissionNeeded = {
                weatherPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            it.launchTapAction = { action ->
                val parts = action.split("|")
                if (parts.size >= 3) {
                    appRepo.launch(
                        parts[0],
                        parts[1].ifBlank { null },
                        parts[2],
                        parts.getOrNull(3)?.ifBlank { null },
                    )
                } else {
                    false
                }
            }
        }
        binding.widgetsContainer.addView(widgetContainer)

        if (!isEinkDisplay()) {
            binding.homeSlotsContainer.layoutTransition =
                LayoutTransition().apply { setDuration(120) }
        }

        initAccessibilityActions()
        seedFirstRunIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
        collapseFolder()
        widgetContainer.rebuild()
        renderHomeSlots()
        binding.root.applyLauncherFont(settings.fontFamily)
        maybeShowDefaultLauncherPrompt()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Pressing home while already on the home screen optionally opens recents.
        if (settings.homeToRecents && lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.RESUMED
            )
        ) {
            GestureAccessibilityService.openRecents()
        }
    }

    private fun applyTheme() {
        val colors = themeManager.getCurrentColors()
        binding.root.setBackgroundColor(colors.backgroundColor)
        window.statusBarColor = colors.backgroundColor
        window.navigationBarColor = colors.backgroundColor
        if (settings.statusBarVisible) showStatusBar() else hideStatusBar()
        hideNavigationBar()
    }

    // Swipe gestures have no TalkBack equivalent; expose them as custom actions.
    private fun initAccessibilityActions() {
        ViewCompat.addAccessibilityAction(binding.homeContainer, getString(R.string.accessibility_open_app_drawer)) { _, _ ->
            openAppDrawer(); true
        }
        ViewCompat.addAccessibilityAction(binding.homeContainer, getString(R.string.accessibility_open_settings)) { _, _ ->
            openSettings(); true
        }
    }

    // Home slots

    private fun renderHomeSlots() {
        collapseFolder()
        binding.homeSlotsContainer.removeAllViews()
        slotViews.clear()

        val colors = themeManager.getCurrentColors()
        val scale = settings.textSizeScale
        val gravity = when (settings.textAlignment) {
            "left" -> Gravity.START
            "right" -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }

        val count = settings.slotCount.coerceIn(0, SettingsRepository.MAX_SLOTS)
        for (slot in 1..count) {
            val entry = settings.getSlot(slot)
            // Prune apps that are gone.
            if (!entry.isFolder && entry.packageName.isNotBlank() &&
                !isPackageInstalled(entry.packageName, userFromToken(entry.userToken))
            ) {
                settings.clearSlot(slot)
            }
            val current = settings.getSlot(slot)

            val view = TextView(this).apply {
                text = if (current.isEmpty) getString(R.string.home_slot_empty) else current.label
                alpha = if (current.isEmpty) 0.4f else 1f
                setTextColor(colors.textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f * scale)
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                isLongClickable = true
                contentDescription = if (current.isEmpty) {
                    getString(R.string.accessibility_home_slot_empty, slot)
                } else {
                    getString(R.string.accessibility_home_slot_filled, current.label, slot)
                }
                setOnClickListener { onSlotClicked(slot) }
                setOnLongClickListener { onSlotLongPressed(slot); true }
            }
            // Wrap-width rows so the tap target hugs the label.
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { this.gravity = gravity }
            binding.homeSlotsContainer.addView(view, params)
            slotViews.add(view)
        }

        // Slot labels are a snapshot from pick time; refresh them from live names.
        val slotViewSnapshot = slotViews.mapIndexed { index, view -> index + 1 to view }
        lifecycleScope.launch {
            for ((slot, view) in slotViewSnapshot) {
                val entry = settings.getSlot(slot)
                if (entry.isEmpty) continue
                val resolved = resolveSlotLabel(entry) ?: continue
                if (resolved == entry.label) continue
                settings.setSlot(slot, entry.copy(label = resolved))
                if (view.isAttachedToWindow) {
                    view.text = resolved
                    view.contentDescription =
                        getString(R.string.accessibility_home_slot_filled, resolved, slot)
                }
            }
        }
    }

    /** Current name for a filled slot, or null to leave the stored copy alone. */
    private suspend fun resolveSlotLabel(entry: SlotEntry): String? {
        if (entry.isFolder) {
            return LauncherApplication.from(this).folders.getFolder(entry.folderId)?.name
        }
        val key = if (entry.shortcutId.isNotBlank()) {
            "${entry.packageName}|${entry.shortcutId}|${entry.userToken}"
        } else {
            "${entry.packageName}|${entry.userToken}"
        }
        val rename = settings.getRenameLabel(key)
        if (rename.isNotBlank()) return rename
        if (entry.shortcutId.isNotBlank()) return null
        return withContext(Dispatchers.IO) {
            val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
            val activities = runCatching {
                launcherApps.getActivityList(entry.packageName, userFromToken(entry.userToken))
            }.getOrNull().orEmpty()
            val activity = activities.firstOrNull {
                it.componentName.className == entry.activityClassName
            } ?: activities.firstOrNull()
            activity?.label?.toString()
        }
    }

    private fun onSlotClicked(slot: Int) {
        val entry = settings.getSlot(slot)
        when {
            entry.isFolder -> toggleFolder(slot, entry.folderId)
            entry.packageName.isNotBlank() -> {
                collapseFolder()
                val launched = appRepo.launch(
                    entry.packageName,
                    entry.activityClassName.ifBlank { null },
                    entry.userToken,
                    entry.shortcutId.ifBlank { null },
                )
                if (!launched) {
                    settings.clearSlot(slot)
                    renderHomeSlots()
                }
            }
            // An empty slot goes straight to the picker; long-press works too.
            else -> onSlotLongPressed(slot)
        }
    }

    private fun onSlotLongPressed(slot: Int) {
        val entry = settings.getSlot(slot)
        val filled = !entry.isEmpty
        val visible = settings.slotCount.coerceIn(0, SettingsRepository.MAX_SLOTS)
        val filledCount = (1..visible).count { !settings.getSlot(it).isEmpty }
        val intent = Intent(this, AppPickerActivity::class.java)
            .putExtra(AppPickerActivity.EXTRA_SLOT_INDEX, slot)
            .putExtra(AppPickerActivity.EXTRA_ALLOW_FOLDERS, true)
            .putExtra(AppPickerActivity.EXTRA_ALLOW_CLEAR, filled)
            .putExtra(AppPickerActivity.EXTRA_ALLOW_MOVE_UP, filled && slot > 1)
            .putExtra(AppPickerActivity.EXTRA_ALLOW_MOVE_DOWN, filled && slot < visible)
            .putExtra(AppPickerActivity.EXTRA_ALLOW_REARRANGE, filledCount >= 2)
        if (entry.isFolder) {
            intent.putExtra(AppPickerActivity.EXTRA_FOLDER_OPTIONS_ID, entry.folderId)
        }
        pickSlotAppLauncher.launch(intent)
    }

    private fun swapSlots(a: Int, b: Int) {
        val first = settings.getSlot(a)
        settings.setSlot(a, settings.getSlot(b))
        settings.setSlot(b, first)
    }

    /**
     * Manual home-slot ordering. Rows rebuild in place after every swap so the
     * sheet stays open for a run of adjustments; [renderHomeSlots] fires on dismiss.
     */
    private fun showSlotRearrangeDialog() {
        val colors = themeManager.getCurrentColors()
        val fontKey = settings.fontFamily
        val scale = settings.textSizeScale
        val visible = settings.slotCount.coerceIn(0, SettingsRepository.MAX_SLOTS)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }

        fun arrow(
            glyph: Int,
            enabled: Boolean,
            description: String,
            onTap: () -> Unit,
        ): TextView = TextView(this).apply {
            text = getString(glyph)
            setTextColor(colors.textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            // Dimmed rather than hidden so rows keep a stable width.
            alpha = if (enabled) 1f else 0.25f
            contentDescription = description
            applyLauncherFont(fontKey)
            if (enabled) {
                isClickable = true
                setOnClickListener { onTap() }
            }
        }

        fun render() {
            list.removeAllViews()
            for (slot in 1..visible) {
                val entry = settings.getSlot(slot)
                val name = if (entry.isEmpty) getString(R.string.home_slot_empty) else entry.label
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val label = TextView(this).apply {
                    text = name
                    setTextColor(colors.textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
                    setPadding(0, dp(6), dp(8), dp(6))
                    alpha = if (entry.isEmpty) 0.4f else 1f
                    applyLauncherFont(fontKey)
                }
                row.addView(
                    label,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                row.addView(
                    arrow(
                        R.string.rearrange_up,
                        enabled = slot > 1,
                        description = getString(R.string.accessibility_move_up, name),
                    ) {
                        swapSlots(slot, slot - 1)
                        render()
                    },
                )
                row.addView(
                    arrow(
                        R.string.rearrange_down,
                        enabled = slot < visible,
                        description = getString(R.string.accessibility_move_down, name),
                    ) {
                        swapSlots(slot, slot + 1)
                        render()
                    },
                )
                list.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }

        render()

        AlertDialog.Builder(this)
            .setTitle(R.string.rearrange_home_title)
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton(R.string.action_done, null)
            .setOnDismissListener { renderHomeSlots() }
            .show()
            .applyLauncherTheme(themeManager, fontKey)
    }

    // Inline folder drop-down: rows appear directly under the folder slot; the
    // slot list is vertically centered so it visibly grows around the folder.
    private fun toggleFolder(slot: Int, folderId: Int) {
        if (expandedFolderSlot == slot) {
            collapseFolder()
            return
        }
        lifecycleScope.launch {
            val folders = LauncherApplication.from(this@MainActivity).folders
            if (folders.getFolder(folderId) == null) {
                settings.clearSlot(slot)
                renderHomeSlots()
                return@launch
            }
            val members = folders.getMembers(folderId)
            if (members.isEmpty()) {
                showToast(getString(R.string.toast_folder_empty))
                return@launch
            }

            collapseFolder()
            val colors = themeManager.getCurrentColors()
            val scale = settings.textSizeScale
            val gravity = when (settings.textAlignment) {
                "left" -> Gravity.START
                "right" -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
            var insertAt =
                binding.homeSlotsContainer.indexOfChild(slotViews[slot - 1]) + 1
            members.forEach { member ->
                val row = TextView(this@MainActivity).apply {
                    text = member.label
                    setTextColor(colors.textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
                    // Symmetric padding so centered rows stay centered; the
                    // smaller text size sets members apart from slots.
                    setPadding(dp(24), dp(6), dp(24), dp(6))
                    isClickable = true
                    isLongClickable = true
                    setOnClickListener {
                        collapseFolder()
                        appRepo.launch(member)
                    }
                    setOnLongClickListener {
                        itemMenu.showAppMenu(member, folderId = folderId) {
                            // renderHomeSlots collapses; drop the folder back
                            // open so a reorder is visible straight away.
                            renderHomeSlots()
                            toggleFolder(slot, folderId)
                        }
                        true
                    }
                }
                row.applyLauncherFont(settings.fontFamily)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { this.gravity = gravity }
                binding.homeSlotsContainer.addView(row, insertAt++, params)
                folderRowViews.add(row)
            }
            expandedFolderSlot = slot
        }
    }

    private fun collapseFolder() {
        if (expandedFolderSlot < 0) return
        folderRowViews.forEach { binding.homeSlotsContainer.removeView(it) }
        folderRowViews.clear()
        expandedFolderSlot = -1
    }

    // First-run seeding: the out-of-the-box layout (Notes, Audio/Comms/Tools
    // folders, Calendar, Skippy/Camera swipes, default hidden apps).
    private fun seedFirstRunIfNeeded() {
        if (settings.firstRunSeeded) return
        settings.firstRunSeeded = true
        val folders = LauncherApplication.from(this).folders
        lifecycleScope.launch {
            if (DefaultLayoutSeeder.applyIfNeeded(this@MainActivity, settings, folders)) {
                renderHomeSlots()
            }
        }
    }

    // Default-launcher role

    private fun maybeShowDefaultLauncherPrompt() {
        if (settings.hideDefaultLauncherPrompt || isDefaultLauncher()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.default_launcher_title)
            .setMessage(R.string.default_launcher_message)
            .setPositiveButton(R.string.default_launcher_set) { _, _ -> requestHomeRole() }
            .setNegativeButton(R.string.default_launcher_not_now) { _, _ ->
                settings.hideDefaultLauncherPrompt = true
            }
            .show()
            .applyLauncherTheme(themeManager, settings.fontFamily)
    }

    private fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName == packageName
    }

    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(ROLE_SERVICE) as RoleManager
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                defaultLauncherLauncher.launch(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                )
                return
            }
        }
        runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
    }

    // Gestures

    inner class HomeGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            val dy = e2.y - (e1?.y ?: 0f)
            val dx = e2.x - (e1?.x ?: 0f)
            when {
                dy < -SWIPE_THRESHOLD && Math.abs(dy) > Math.abs(dx) -> openAppDrawer()
                dy > SWIPE_THRESHOLD && Math.abs(dy) > Math.abs(dx) -> handleSwipeDown()
                dx < -SWIPE_THRESHOLD && Math.abs(dx) > Math.abs(dy) -> handleSwipeLeft()
                dx > SWIPE_THRESHOLD && Math.abs(dx) > Math.abs(dy) -> handleSwipeRight()
                else -> return false
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (settings.doubleTapLock) {
                lockScreen()
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            openSettings()
        }
    }

    private fun openAppDrawer() {
        startActivity(Intent(this, AppDrawerActivity::class.java))
        if (!isEinkDisplay()) overridePendingTransition(R.anim.slide_up, 0)
    }

    private fun handleSwipeDown() {
        when (settings.swipeDownAction) {
            "web_search" -> openWebSearch()
            "disabled" -> Unit
            else -> expandNotificationDrawer(this)
        }
    }

    private fun handleSwipeLeft() {
        if (!settings.swipeLeftEnabled) return
        // Swiping left pulls the app in from the right edge.
        if (!launchSwipeApp(settings.swipeLeftApp, R.anim.slide_in_right)) {
            openCameraApp(this, swipeAnimOptions(R.anim.slide_in_right))
        }
    }

    private fun handleSwipeRight() {
        if (!settings.swipeRightEnabled) return
        // Swiping right pulls the app in from the left edge.
        if (!launchSwipeApp(settings.swipeRightApp, R.anim.slide_in_left)) {
            openDialerApp(this, swipeAnimOptions(R.anim.slide_in_left))
        }
    }

    /**
     * The animation must ride along in the launch's ActivityOptions:
     * overrideActivityTransition/overridePendingTransition are ignored for
     * cross-task launches. The system only honors it when the launch opens
     * a fresh task; bringing an existing task back to front always plays
     * the system default — customizing that needs privileged permissions a
     * third-party launcher can't hold (all verified on-device, API 34+).
     */
    private fun swipeAnimOptions(enterAnim: Int): android.os.Bundle? =
        if (isEinkDisplay()) null
        else android.app.ActivityOptions.makeCustomAnimation(this, enterAnim, 0).toBundle()

    /** Value is "pkg|activity|user|shortcutId"; trailing parts are optional. */
    private fun launchSwipeApp(value: String?, enterAnim: Int): Boolean {
        if (value.isNullOrBlank()) return false
        val parts = value.split("|")
        return appRepo.launch(
            parts[0],
            parts.getOrNull(1)?.ifBlank { null },
            parts.getOrNull(2)?.ifBlank { null } ?: USER_PERSONAL,
            parts.getOrNull(3)?.ifBlank { null },
            swipeAnimOptions(enterAnim),
        )
    }

    private fun lockScreen() {
        if (!GestureAccessibilityService.lockScreen()) {
            showToast(getString(R.string.accessibility_service_needed))
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SWIPE_THRESHOLD = 100f
    }
}
