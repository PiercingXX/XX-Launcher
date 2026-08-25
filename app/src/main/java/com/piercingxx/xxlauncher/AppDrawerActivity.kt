package com.piercingxx.xxlauncher

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.piercingxx.xxlauncher.data.AppInfo
import com.piercingxx.xxlauncher.data.AppRepository
import com.piercingxx.xxlauncher.data.SettingsRepository
import com.piercingxx.xxlauncher.folder.FolderWithCount
import com.piercingxx.xxlauncher.menu.ItemActionMenu
import com.piercingxx.xxlauncher.theme.ThemeManager
import com.piercingxx.xxlauncher.theme.applyLauncherFont
import com.piercingxx.xxlauncher.util.hideKeyboard
import com.piercingxx.xxlauncher.util.hideNavigationBar
import com.piercingxx.xxlauncher.util.hideStatusBar
import com.piercingxx.xxlauncher.util.showStatusBar
import com.piercingxx.xxlauncher.util.isEinkDisplay
import com.piercingxx.xxlauncher.util.openUrl
import com.piercingxx.xxlauncher.util.showKeyboard
import kotlinx.coroutines.launch

class AppDrawerActivity : AppCompatActivity() {

    private lateinit var appRepo: AppRepository
    private lateinit var settings: SettingsRepository
    private lateinit var themeManager: ThemeManager
    private lateinit var itemMenu: ItemActionMenu

    private lateinit var searchEditText: EditText
    private lateinit var appListContainer: LinearLayout
    private lateinit var drawerContainer: View
    private lateinit var scrollView: android.widget.ScrollView

    private var apps: List<AppInfo> = emptyList()
    private var folders: List<FolderWithCount> = emptyList()
    private var folderMemberKeys: Set<String> = emptySet()
    private var expandedFolderId = -1
    private var currentResults: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = LauncherApplication.from(this)
        appRepo = app.appRepo
        settings = app.settings
        themeManager = app.themeManager
        itemMenu = ItemActionMenu(this, appRepo, settings, themeManager, app.folders)

        setContentView(R.layout.activity_app_drawer)
        drawerContainer = findViewById(R.id.drawerContainer)
        searchEditText = findViewById(R.id.searchEditText)
        appListContainer = findViewById(R.id.appListContainer)
        scrollView = findViewById(R.id.appScrollView)

        applyTheme()

        // Tapping the exposed top strip closes the sheet.
        findViewById<View>(R.id.topSpacer).setOnClickListener { finish() }

        initScrollBehavior()

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                renderList(s?.toString() ?: "")
            }
        })

        // Enter launches the first result; with none, falls back to web search.
        searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                val query = searchEditText.text.toString()
                when {
                    query.isBlank() -> Unit
                    currentResults.isNotEmpty() -> launchApp(currentResults.first())
                    else -> webSearch(query.removePrefix("!").trim())
                }
                true
            } else {
                false
            }
        }

        if (settings.autoShowKeyboard) searchEditText.showKeyboard()

        appRepo.apps.observe(this) { newApps ->
            apps = newApps
            refreshFolders()
            renderList(searchEditText.text.toString())
        }
        appRepo.refresh()
        refreshFolders()
    }

    override fun onResume() {
        super.onResume()
        hideNavigationBar()
        if (settings.statusBarVisible) showStatusBar() else hideStatusBar()
        refreshFolders()
    }

    private fun applyTheme() {
        val colors = themeManager.getCurrentColors()
        window.navigationBarColor = colors.backgroundColor
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        // Only the sheet is colored; the top strip stays see-through.
        drawerContainer.setBackgroundColor(colors.backgroundColor)
        searchEditText.setTextColor(colors.textColor)
        searchEditText.setHintTextColor(ColorUtils.setAlphaComponent(colors.textColor, 128))
        searchEditText.applyLauncherFont(settings.fontFamily)
    }

    /**
     * Over-scrolling past the top closes the drawer; reaching the top
     * re-summons the keyboard; scrolling down hides it.
     */
    // The listener returns false and never consumes a click, so the
    // ScrollView's own click/accessibility handling is untouched.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun initScrollBehavior() {
        var downY = 0f
        var startedAtTop = false
        var closing = false
        scrollView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    startedAtTop = scrollView.scrollY == 0
                    closing = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val pulled = event.y - downY
                    if (!closing && startedAtTop && scrollView.scrollY == 0 && pulled > dp(80)) {
                        closing = true
                        finish()
                    }
                }
            }
            false
        }
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            if (scrollView.scrollY == 0) {
                if (settings.autoShowKeyboard) searchEditText.showKeyboard()
            } else {
                searchEditText.hideKeyboard()
            }
        }
    }

    private fun refreshFolders() {
        lifecycleScope.launch {
            val manager = LauncherApplication.from(this@AppDrawerActivity).folders
            folders = manager.getFoldersWithCounts()
            folderMemberKeys = manager.getAllMemberKeys()
            renderList(searchEditText.text.toString())
        }
    }

    // Only slots that actually render are excluded from browsing — otherwise
    // lowering the slot count would strand an app: off the home screen and
    // out of the drawer at the same time.
    private fun homeSlotKeys(): Set<String> = buildSet {
        val visible = settings.slotCount.coerceIn(0, SettingsRepository.MAX_SLOTS)
        for (slot in 1..visible) {
            val entry = settings.getSlot(slot)
            if (!entry.isFolder && entry.packageName.isNotBlank()) {
                add("${entry.packageName}|${entry.userToken}")
            }
        }
    }

    private fun renderList(rawQuery: String) {
        val query = rawQuery.trim()
        val searching = query.isNotEmpty()

        // "!query" opens the whole query as a DuckDuckGo search.
        if (rawQuery.startsWith("!")) {
            renderRows(
                emptyList(),
                emptyList(),
                showWebSearchRow = rawQuery.length > 1,
                showSettingsRow = false,
            )
            currentResults = emptyList()
            return
        }

        val pinnedOrder = settings.pinnedApps
        val hiddenExcluded = apps.filter { !appRepo.isHidden(it) }

        val browseList: List<AppInfo> = if (searching) {
            // Hidden apps match search but never browse.
            apps.filter { it.matches(query) }
        } else {
            // Home-slot apps and folder members never browse (search still finds
            // them); explicitly pinned apps stay regardless.
            val slotKeys = homeSlotKeys()
            hiddenExcluded.filter {
                appRepo.isPinned(it) || (it.key !in slotKeys && it.key !in folderMemberKeys)
            }
        }

        val sorted = sortApps(browseList, pinnedOrder)
        currentResults = sorted

        // The launcher's own settings row stays findable by search too.
        val settingsRowVisible = !searching ||
            getString(R.string.launcher_settings).contains(query, true)

        // Auto-launch on exactly one result; a leading space suppresses it, and
        // a visible settings row keeps the list on screen so it stays reachable.
        if (searching && sorted.size == 1 && !settingsRowVisible && !rawQuery.startsWith(" ")) {
            launchApp(sorted.first())
            return
        }

        renderRows(
            sorted,
            if (searching) emptyList() else folders,
            showWebSearchRow = searching && sorted.isEmpty(),
            showSettingsRow = settingsRowVisible,
        )
    }

    private fun sortApps(list: List<AppInfo>, pinnedOrder: List<String>): List<AppInfo> {
        val mode = settings.sortMode
        val base = when (mode) {
            "install_date" -> list.sortedByDescending { it.installedAt }
            "apk_size" -> list.sortedByDescending { it.sizeBytes }
            else -> list.sortedBy { it.label.lowercase() }
        }
        if (mode == "alphabetical_no_priority") return base
        // Pinned rows float to the top in their manual order.
        val (pinned, rest) = base.partition { appRepo.isPinned(it) }
        val pinnedSorted =
            if (mode == "default") pinned.sortedBy { pinnedOrder.indexOf(it.key) }
            else pinned
        return pinnedSorted + rest
    }

    private fun renderRows(
        rows: List<AppInfo>,
        folderRows: List<FolderWithCount>,
        showWebSearchRow: Boolean,
        showSettingsRow: Boolean,
    ) {
        appListContainer.removeAllViews()
        val colors = themeManager.getCurrentColors()
        val scale = settings.textSizeScale

        rows.forEach { app ->
            appListContainer.addView(createAppRow(app, colors.textColor, scale))
        }

        folderRows.forEach { entry ->
            appListContainer.addView(createFolderRow(entry, colors.textColor, scale))
            if (entry.folder.id == expandedFolderId) {
                addFolderMemberRows(entry.folder.id, colors.textColor, scale)
            }
        }

        // The drawer always offers the launcher's own settings as a browse entry.
        if (showSettingsRow) {
            appListContainer.addView(
                makeRow(getString(R.string.launcher_settings), colors.textColor, 21f * scale) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            )
        }

        if (showWebSearchRow) {
            val query = searchEditText.text.toString().removePrefix("!").trim()
            appListContainer.addView(
                makeRow(
                    getString(R.string.search_web_prefix) + query,
                    colors.textColor,
                    21f * scale,
                ) { webSearch(query) }
            )
        }
    }

    private fun createAppRow(app: AppInfo, textColor: Int, scale: Float): View {
        val parts = buildString {
            append(app.label)
            if (app.isShortcut) append("  ↗")
            if (appRepo.isPinned(app)) append("  ⊙")
            if (app.isNew) append("  •")
            if (app.isWorkProfile) append("  ⧉")
        }
        val row = makeRow(parts, textColor, 18f * scale) { launchApp(app) }
        row.contentDescription = buildString {
            append(app.label)
            if (appRepo.isPinned(app)) append(", ").append(getString(R.string.accessibility_pinned))
            if (app.isWorkProfile) append(", ").append(getString(R.string.accessibility_work_profile))
            if (app.isNew) append(", ").append(getString(R.string.accessibility_new_app))
        }
        row.setOnLongClickListener {
            itemMenu.showAppMenu(app, isDrawerRow = true) {
                appRepo.refresh()
                refreshFolders()
            }
            true
        }
        return row
    }

    private fun createFolderRow(entry: FolderWithCount, textColor: Int, scale: Float): View {
        val label = "▸ ${entry.folder.name}  (${entry.memberCount})"
        val row = makeRow(label, textColor, 18f * scale) {
            expandedFolderId = if (expandedFolderId == entry.folder.id) -1 else entry.folder.id
            renderList(searchEditText.text.toString())
        }
        row.setTag(R.id.tag_folder_id, entry.folder.id)
        row.contentDescription = getString(
            R.string.accessibility_folder_row, entry.folder.name, entry.memberCount
        )
        row.setOnLongClickListener {
            itemMenu.showFolderMenu(entry.folder.id, entry.folder.name) { refreshFolders() }
            true
        }
        return row
    }

    private fun addFolderMemberRows(folderId: Int, textColor: Int, scale: Float) {
        lifecycleScope.launch {
            val members =
                LauncherApplication.from(this@AppDrawerActivity).folders.getMembers(folderId)
            // Guard against a re-render racing the load.
            if (expandedFolderId != folderId) return@launch
            val anchor = appListContainer.children().indexOfFirst { view ->
                view.getTag(R.id.tag_folder_id) == folderId
            }
            var insertAt = if (anchor >= 0) anchor + 1 else appListContainer.childCount
            members.forEach { member ->
                val row = makeRow(member.label, textColor, 16f * scale, indent = true) {
                    launchApp(member)
                }
                row.setOnLongClickListener {
                    itemMenu.showAppMenu(member, folderId = folderId) { refreshFolders() }
                    true
                }
                appListContainer.addView(row, insertAt++)
            }
        }
    }

    private fun makeRow(
        text: String,
        textColor: Int,
        textSizeSp: Float,
        indent: Boolean = false,
        onTap: () -> Unit,
    ): TextView = TextView(this).apply {
        this.text = text
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        gravity = when (settings.textAlignment) {
            "left" -> Gravity.START
            "right" -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        // Symmetric horizontal padding keeps centered/right alignment true;
        // indented rows shrink inward from both edges.
        dp(if (indent) 40 else 24).let { setPadding(it, dp(10), it, dp(10)) }
        isClickable = true
        isLongClickable = true
        setOnClickListener { onTap() }
        applyLauncherFont(settings.fontFamily)
    }

    private fun launchApp(app: AppInfo) {
        if (appRepo.launch(app)) finish()
    }

    private fun webSearch(query: String) {
        openUrl("https://duckduckgo.com/?q=" + android.net.Uri.encode(query))
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun LinearLayout.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    override fun finish() {
        super.finish()
        if (!isEinkDisplay()) overridePendingTransition(0, R.anim.slide_down)
    }
}
