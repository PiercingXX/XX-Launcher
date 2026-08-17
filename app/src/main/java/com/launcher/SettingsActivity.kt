package com.launcher

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import com.launcher.theme.applyLauncherFont
import com.launcher.theme.applyLauncherTheme
import com.launcher.util.hideStatusBar
import com.launcher.util.showStatusBar
import com.launcher.backup.BackupManager
import com.launcher.data.SettingsRepository
import com.launcher.settings.ThemePreviewPreference
import com.launcher.theme.FontImportResult
import com.launcher.theme.LauncherFont
import com.launcher.theme.clearFontCache
import com.launcher.theme.importCustomFont
import com.launcher.util.showToast
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Settings render on the launcher's own background color.
        val colors = LauncherApplication.from(this).themeManager.getCurrentColors()
        window.decorView.setBackgroundColor(colors.backgroundColor)
        window.statusBarColor = colors.backgroundColor
        window.navigationBarColor = colors.backgroundColor
        supportActionBar?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(colors.backgroundColor)
        )
        supportActionBar?.elevation = 0f
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        // The status bar stays hidden everywhere unless the user enables it;
        // a swipe from the top shows it transiently.
        if (LauncherApplication.from(this).settings.statusBarVisible) {
            showStatusBar()
        } else {
            hideStatusBar()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val app get() = LauncherApplication.from(requireContext())
        private val backupManager by lazy {
            BackupManager(app.settings, app.folders)
        }

        private val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            // The fragment's own scope, not viewLifecycleOwner's: an activity
            // result can land before onCreateView, and viewLifecycleOwner
            // throws in that window.
            lifecycleScope.launch {
                runCatching {
                    val json = backupManager.exportToJson()
                    requireContext().contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray())
                    }
                }.fold(
                    onSuccess = { requireContext().showToast(getString(R.string.toast_backup_created)) },
                    onFailure = { requireContext().showToast(getString(R.string.toast_restore_failed)) },
                )
            }
        }

        private val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            // The fragment's own scope, not viewLifecycleOwner's: an activity
            // result can land before onCreateView, and viewLifecycleOwner
            // throws in that window.
            lifecycleScope.launch {
                val result = runCatching {
                    val json = requireContext().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error("Unable to read file")
                    backupManager.importFromJson(json).getOrThrow()
                }
                result.fold(
                    onSuccess = {
                        requireContext().showToast(getString(R.string.toast_restore_complete))
                        // Runtime permissions and custom fonts are not restored.
                        requireContext().showToast(getString(R.string.restore_caveats))
                        app.appRepo.refresh()
                    },
                    onFailure = { requireContext().showToast(getString(R.string.toast_restore_failed)) },
                )
            }
        }

        /** Swipe pref ("swipe_left_app"/"swipe_right_app") being picked, while AppPicker is open. */
        private var pendingSwipeKey: String? = null

        private val swipeAppLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val key = pendingSwipeKey ?: return@registerForActivityResult
            pendingSwipeKey = null
            if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val value = if (data.getBooleanExtra(AppPickerActivity.EXTRA_CLEARED, false)) {
                null
            } else {
                val pkg = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE).orEmpty()
                if (pkg.isBlank()) return@registerForActivityResult
                listOf(
                    pkg,
                    data.getStringExtra(AppPickerActivity.EXTRA_ACTIVITY).orEmpty(),
                    data.getStringExtra(AppPickerActivity.EXTRA_USER).orEmpty(),
                    data.getStringExtra(AppPickerActivity.EXTRA_SHORTCUT_ID).orEmpty(),
                ).joinToString("|")
            }
            if (key == "swipe_left_app") app.settings.swipeLeftApp = value
            else app.settings.swipeRightApp = value
            updateSwipeSummaries()
        }

        private fun pickSwipeApp(key: String, current: String?) {
            pendingSwipeKey = key
            swipeAppLauncher.launch(
                Intent(requireContext(), AppPickerActivity::class.java)
                    .putExtra(AppPickerActivity.EXTRA_ALLOW_CLEAR, current != null)
            )
        }

        /** Shows the chosen app's label; empty when nothing is set. */
        private fun updateSwipeSummaries() {
            fun labelFor(value: String?): String {
                val pkg = value?.substringBefore("|").orEmpty()
                if (pkg.isBlank()) return ""
                return runCatching {
                    val pm = requireContext().packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
            }
            findPreference<Preference>("swipe_left_app")?.summary =
                labelFor(app.settings.swipeLeftApp)
            findPreference<Preference>("swipe_right_app")?.summary =
                labelFor(app.settings.swipeRightApp)
        }

        /** Widget whose tap action is being picked, while AppPicker is open. */
        private var pendingTapWidget: String? = null

        private val tapActionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val widget = pendingTapWidget ?: return@registerForActivityResult
            pendingTapWidget = null
            val data = result.data ?: return@registerForActivityResult
            if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
            if (data.getBooleanExtra(AppPickerActivity.EXTRA_CLEARED, false)) {
                app.settings.setWidgetTapAction(widget, "")
            } else {
                val pkg = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE).orEmpty()
                val activity = data.getStringExtra(AppPickerActivity.EXTRA_ACTIVITY).orEmpty()
                val user = data.getStringExtra(AppPickerActivity.EXTRA_USER).orEmpty()
                val shortcutId = data.getStringExtra(AppPickerActivity.EXTRA_SHORTCUT_ID).orEmpty()
                if (pkg.isNotBlank()) {
                    app.settings.setWidgetTapAction(widget, "$pkg|$activity|$user|$shortcutId")
                }
            }
        }

        private val fontImportLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            when (val result = requireContext().importCustomFont(uri)) {
                is FontImportResult.Success -> {
                    app.settings.fontFamily = LauncherFont.CUSTOM
                    findPreference<ListPreference>("font_family")?.value = LauncherFont.CUSTOM
                    requireContext().showToast(
                        getString(R.string.font_import_success, result.displayName)
                    )
                }

                else -> requireContext().showToast(getString(R.string.font_import_failed))
            }
        }

        // Preference rows pick up the launcher font and text color as they
        // attach, so recycled rows stay in theme too.
        override fun onCreateRecyclerView(
            inflater: LayoutInflater,
            parent: ViewGroup,
            savedInstanceState: Bundle?,
        ): RecyclerView {
            val recycler = super.onCreateRecyclerView(inflater, parent, savedInstanceState)
            recycler.addOnChildAttachStateChangeListener(
                object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) = themeRow(view)
                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                }
            )
            return recycler
        }

        private fun themeRow(view: View) {
            val colors = app.themeManager.getCurrentColors()
            view.applyLauncherFont(app.settings.fontFamily)
            view.findViewById<TextView>(android.R.id.title)?.setTextColor(colors.textColor)
            view.findViewById<TextView>(android.R.id.summary)
                ?.setTextColor(ColorUtils.setAlphaComponent(colors.textColor, 180))
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // The repositories and the settings UI must share one prefs file.
            preferenceManager.sharedPreferencesName = com.launcher.data.SettingsRepository.PREFS_NAME
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("default_launcher")?.setOnPreferenceClickListener {
                requestHomeRole(); true
            }
            findPreference<Preference>("hidden_apps")?.setOnPreferenceClickListener {
                showHiddenApps(); true
            }
            findPreference<Preference>("gestures_help")?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pref_gestures)
                    .setMessage(R.string.gestures_help_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                    .applyLauncherTheme(app.themeManager, app.settings.fontFamily)
                true
            }
            findPreference<Preference>("widgets_config")?.setOnPreferenceClickListener {
                showWidgetConfigDialog(); true
            }
            findPreference<Preference>("export_backup")?.setOnPreferenceClickListener {
                exportLauncher.launch("launcher-backup.json"); true
            }
            findPreference<Preference>("import_backup")?.setOnPreferenceClickListener {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")); true
            }
            findPreference<Preference>("import_font")?.setOnPreferenceClickListener {
                fontImportLauncher.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream", "*/*"))
                true
            }
            findPreference<Preference>("custom_color")?.setOnPreferenceClickListener {
                showCustomColorDialog(); true
            }
            findPreference<Preference>("swipe_left_app")?.setOnPreferenceClickListener {
                pickSwipeApp("swipe_left_app", app.settings.swipeLeftApp); true
            }
            findPreference<Preference>("swipe_right_app")?.setOnPreferenceClickListener {
                pickSwipeApp("swipe_right_app", app.settings.swipeRightApp); true
            }
            updateSwipeSummaries()
            findPreference<Preference>("version")?.summary = BuildConfig.VERSION_NAME
            findPreference<Preference>("appearance_mode")?.setOnPreferenceChangeListener { _, value ->
                app.themeManager.setAppearanceMode(value.toString()); true
            }
            findPreference<Preference>("font_family")?.setOnPreferenceChangeListener { _, _ ->
                clearFontCache(); true
            }

            findPreference<ThemePreviewPreference>("theme_strip")?.let { strip ->
                strip.title = getString(R.string.pref_theme_preset)
                strip.presets = app.themeManager.presets
                strip.selectedKey = app.settings.themePreset
                strip.onPresetSelected = { key ->
                    app.settings.themePreset = key
                    strip.selectedKey = key
                    strip.refresh()
                    app.themeManager.applyWallpaper()
                }
                strip.onCustomSelected = { showCustomColorDialog() }
            }
        }

        /** Enable/disable, reorder, and set tap actions for the home widgets. */
        private fun showWidgetConfigDialog() {
            val context = requireContext()
            val container = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(12))
            }

            lateinit var rebuild: () -> Unit
            rebuild = {
                container.removeAllViews()
                val order = app.settings.widgetsOrder
                val allInOrder = order + SettingsRepository.ALL_WIDGETS.filter { it !in order }
                allInOrder.forEach { widget ->
                    val row = android.widget.LinearLayout(context).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    val checkbox = android.widget.CheckBox(context).apply {
                        isChecked = widget in order
                        text = widget.replaceFirstChar { it.uppercase() }
                        setOnCheckedChangeListener { _, checked ->
                            val current = app.settings.widgetsOrder.toMutableList()
                            if (checked && widget !in current) current.add(widget)
                            if (!checked) current.remove(widget)
                            app.settings.widgetsOrder = current
                            rebuild()
                        }
                    }
                    row.addView(
                        checkbox,
                        android.widget.LinearLayout.LayoutParams(0, -2, 1f),
                    )

                    fun arrow(label: String, up: Boolean) =
                        android.widget.TextView(context).apply {
                            text = label
                            textSize = 20f
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                            isClickable = true
                            alpha = if (widget in order) 1f else 0.3f
                            setOnClickListener {
                                val current = app.settings.widgetsOrder.toMutableList()
                                val index = current.indexOf(widget)
                                val target = if (up) index - 1 else index + 1
                                if (index >= 0 && target in current.indices) {
                                    current[index] = current[target]
                                    current[target] = widget
                                    app.settings.widgetsOrder = current
                                    rebuild()
                                }
                            }
                        }
                    row.addView(arrow("↑", up = true))
                    row.addView(arrow("↓", up = false))

                    val tap = android.widget.TextView(context).apply {
                        text = getString(R.string.widget_tap_action)
                        setPadding(dp(12), dp(8), dp(4), dp(8))
                        isClickable = true
                        paint.isUnderlineText = true
                        setOnClickListener {
                            pendingTapWidget = widget
                            tapActionLauncher.launch(
                                Intent(context, AppPickerActivity::class.java)
                                    .putExtra(AppPickerActivity.EXTRA_ALLOW_CLEAR, true)
                                    .putExtra(
                                        AppPickerActivity.EXTRA_CLEAR_LABEL,
                                        getString(R.string.widget_tap_default),
                                    )
                            )
                        }
                    }
                    row.addView(tap)
                    container.addView(row)
                }
            }
            rebuild()

            AlertDialog.Builder(context)
                .setTitle(R.string.pref_widgets)
                .setView(android.widget.ScrollView(context).apply { addView(container) })
                .setPositiveButton(android.R.string.ok, null)
                .show()
                .applyLauncherTheme(app.themeManager, app.settings.fontFamily)
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt()

        private fun requestHomeRole() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager =
                    requireContext().getSystemService(RoleManager::class.java)
                if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    return
                }
            }
            runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        }

        private fun showHiddenApps() {
            val hidden = app.settings.hiddenApps.toList().sorted()
            if (hidden.isEmpty()) {
                requireContext().showToast(getString(R.string.no_hidden_apps))
                return
            }
            // Show real app labels; fall back to the package for stale keys.
            val byKey = app.appRepo.apps.value.orEmpty().associateBy({ it.key }, { it.label })
            val labels = hidden.map { byKey[it] ?: it.substringBefore("|") }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_hidden_apps)
                .setItems(labels) { _, which ->
                    app.settings.toggleHidden(hidden[which])
                    requireContext().showToast(getString(R.string.toast_shown))
                    app.appRepo.refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .applyLauncherTheme(app.themeManager, app.settings.fontFamily)
        }

        private fun showCustomColorDialog() {
            val input = android.widget.EditText(requireContext()).apply {
                hint = "RRGGBB"
                setText(
                    String.format("%06X", 0xFFFFFF and app.settings.customBgColor)
                )
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_custom_color)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val hex = input.text.toString().trim().removePrefix("#")
                    val parsed = hex.toLongOrNull(16)
                    if (hex.length == 6 && parsed != null) {
                        app.settings.customBgColor = (0xFF000000L or parsed).toInt()
                        app.settings.themePreset = "custom"
                        app.themeManager.applyWallpaper()
                        findPreference<ThemePreviewPreference>("theme_strip")?.let {
                            it.selectedKey = "custom"
                            it.refresh()
                        }
                    } else {
                        requireContext().showToast(getString(R.string.invalid_color))
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .applyLauncherTheme(app.themeManager, app.settings.fontFamily)
        }
    }
}
