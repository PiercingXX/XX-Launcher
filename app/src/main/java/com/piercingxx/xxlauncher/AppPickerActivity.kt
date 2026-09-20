package com.piercingxx.xxlauncher

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.piercingxx.xxlauncher.data.AppInfo
import com.piercingxx.xxlauncher.data.Folder
import com.piercingxx.xxlauncher.menu.ActionSheet
import com.piercingxx.xxlauncher.theme.applyLauncherFont
import com.piercingxx.xxlauncher.util.showToast
import kotlinx.coroutines.launch

/**
 * Picks an app (or folder, or "clear") for a home slot, a swipe gesture or a
 * widget tap. It only picks: slot actions (rename, move, clear) live in the
 * slot's long-press sheet. Returns the selection in the activity result extras.
 */
class AppPickerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        val app = LauncherApplication.from(this)
        val container = findViewById<LinearLayout>(R.id.appListContainer)
        val slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, -1)
        val allowFolders = intent.getBooleanExtra(EXTRA_ALLOW_FOLDERS, false)
        val allowClear = intent.getBooleanExtra(EXTRA_ALLOW_CLEAR, false)

        val colors = app.themeManager.getCurrentColors()
        val fontKey = app.settings.fontFamily
        val scale = app.settings.textSizeScale
        val textGravity = ActionSheet.gravityFor(app.settings.textAlignment)
        findViewById<View>(android.R.id.content).setBackgroundColor(colors.backgroundColor)
        findViewById<TextView>(R.id.pickerTitle).apply {
            setTextColor(colors.textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * scale)
            gravity = textGravity
            applyLauncherFont(fontKey)
        }

        fun addRow(label: String, onTap: () -> Unit) {
            container.addView(TextView(this).apply {
                text = label
                setTextColor(colors.textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
                gravity = textGravity or Gravity.CENTER_VERTICAL
                minHeight = dp(52)
                setPadding(dp(24), dp(12), dp(24), dp(12))
                isClickable = true
                foreground = ActionSheet.rippleFor(colors.textColor)
                setOnClickListener { onTap() }
                applyLauncherFont(fontKey)
            })
        }

        // Sections read as groups, the same hairline the sheets use.
        fun addDivider() {
            container.addView(
                View(this).apply { setBackgroundColor(ActionSheet.hairlineFor(colors.textColor)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)).apply {
                    marginStart = dp(24); marginEnd = dp(24); topMargin = dp(6); bottomMargin = dp(6)
                },
            )
        }

        fun showNewFolderSheet() {
            fun save(name: String) {
                lifecycleScope.launch {
                    app.folders.createFolder(name).fold(
                        onSuccess = { folder ->
                            setResult(
                                RESULT_OK,
                                Intent()
                                    .putExtra(EXTRA_SLOT_INDEX, slotIndex)
                                    .putExtra(EXTRA_FOLDER_ID, folder.id)
                                    .putExtra(EXTRA_LABEL, folder.name),
                            )
                            finish()
                        },
                        onFailure = { showToast(getString(R.string.toast_invalid_folder_name)) },
                    )
                }
            }
            val sheet = ActionSheet(this, app.themeManager, app.settings)
                .title(getString(R.string.sheet_title_new_folder))
            val input = sheet.input("", getString(R.string.folder_name_hint), ::save)
            sheet.button(getString(android.R.string.cancel)) { sheet.dismiss() }
            sheet.button(getString(R.string.action_save), primary = true) {
                sheet.dismiss()
                save(input.text.toString().trim())
            }
            sheet.show()
        }

        fun render(apps: List<AppInfo>, folders: List<Folder>) {
            container.removeAllViews()
            var sections = 0
            fun section(block: () -> Unit) {
                if (sections++ > 0) addDivider()
                block()
            }

            if (allowClear) {
                section {
                    val clearLabel = intent.getStringExtra(EXTRA_CLEAR_LABEL)
                        ?: getString(R.string.picker_clear_slot)
                    addRow(clearLabel) {
                        setResult(RESULT_OK, Intent()
                            .putExtra(EXTRA_SLOT_INDEX, slotIndex)
                            .putExtra(EXTRA_CLEARED, true))
                        finish()
                    }
                }
            }
            if (allowFolders) {
                section {
                    folders.forEach { folder ->
                        addRow("▸ ${folder.name}") {
                            setResult(RESULT_OK, Intent()
                                .putExtra(EXTRA_SLOT_INDEX, slotIndex)
                                .putExtra(EXTRA_FOLDER_ID, folder.id)
                                .putExtra(EXTRA_LABEL, folder.name))
                            finish()
                        }
                    }
                    addRow(getString(R.string.picker_new_folder)) { showNewFolderSheet() }
                }
            }
            section {
                apps.forEach { appInfo ->
                    val suffix = buildString {
                        if (appInfo.isShortcut) append("  ↗")
                        if (appInfo.isWorkProfile) append("  ⧉")
                    }
                    addRow(appInfo.label + suffix) {
                        setResult(RESULT_OK, Intent()
                            .putExtra(EXTRA_SLOT_INDEX, slotIndex)
                            .putExtra(EXTRA_PACKAGE, appInfo.packageName)
                            .putExtra(EXTRA_ACTIVITY, appInfo.activityClassName)
                            .putExtra(EXTRA_USER, appInfo.userToken)
                            .putExtra(EXTRA_SHORTCUT_ID, appInfo.shortcutId)
                            .putExtra(EXTRA_LABEL, appInfo.label))
                        finish()
                    }
                }
            }
        }

        app.appRepo.apps.observe(this) { apps ->
            if (allowFolders) {
                lifecycleScope.launch { render(apps, app.folders.getFolders()) }
            } else {
                render(apps, emptyList())
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SLOT_INDEX = "slot_index"
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_ACTIVITY = "activity_class"
        const val EXTRA_USER = "user_token"
        const val EXTRA_LABEL = "label"
        const val EXTRA_FOLDER_ID = "folder_id"
        const val EXTRA_SHORTCUT_ID = "shortcut_id"
        const val EXTRA_CLEARED = "cleared"
        const val EXTRA_CLEAR_LABEL = "clear_label"
        const val EXTRA_ALLOW_FOLDERS = "allow_folders"
        const val EXTRA_ALLOW_CLEAR = "allow_clear"
    }
}
