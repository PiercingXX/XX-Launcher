package com.launcher

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.launcher.data.AppInfo
import com.launcher.theme.applyLauncherFont
import kotlinx.coroutines.launch

/**
 * Picks an app (or folder, or "clear") for a home slot or gesture target.
 * Returns the selection in the activity result extras.
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
        findViewById<android.view.View>(android.R.id.content).setBackgroundColor(colors.backgroundColor)

        fun addRow(label: String, onTap: () -> Unit) {
            container.addView(TextView(this).apply {
                text = label
                setTextColor(colors.textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                isClickable = true
                setOnClickListener { onTap() }
                applyLauncherFont(app.settings.fontFamily)
            })
        }

        fun addMoveRow(up: Boolean) {
            addRow(getString(if (up) R.string.action_move_up else R.string.action_move_down)) {
                setResult(RESULT_OK, Intent()
                    .putExtra(EXTRA_SLOT_INDEX, slotIndex)
                    .putExtra(EXTRA_MOVE_UP, up))
                finish()
            }
        }

        fun render(apps: List<AppInfo>, folders: List<com.launcher.data.Folder>) {
            container.removeAllViews()
            if (intent.getBooleanExtra(EXTRA_ALLOW_MOVE_UP, false)) addMoveRow(up = true)
            if (intent.getBooleanExtra(EXTRA_ALLOW_MOVE_DOWN, false)) addMoveRow(up = false)
            if (allowClear) {
                val clearLabel = intent.getStringExtra(EXTRA_CLEAR_LABEL)
                    ?: getString(R.string.picker_clear_slot)
                addRow(clearLabel) {
                    setResult(RESULT_OK, Intent()
                        .putExtra(EXTRA_SLOT_INDEX, slotIndex)
                        .putExtra(EXTRA_CLEARED, true))
                    finish()
                }
            }
            folders.forEach { folder ->
                addRow("▸ ${folder.name}") {
                    setResult(RESULT_OK, Intent()
                        .putExtra(EXTRA_SLOT_INDEX, slotIndex)
                        .putExtra(EXTRA_FOLDER_ID, folder.id)
                        .putExtra(EXTRA_LABEL, folder.name))
                    finish()
                }
            }
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

        app.appRepo.apps.observe(this) { apps ->
            if (allowFolders) {
                lifecycleScope.launch {
                    render(apps, app.folders.getFolders())
                }
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
        const val EXTRA_ALLOW_MOVE_UP = "allow_move_up"
        const val EXTRA_ALLOW_MOVE_DOWN = "allow_move_down"
        const val EXTRA_MOVE_UP = "move_up"
    }
}
