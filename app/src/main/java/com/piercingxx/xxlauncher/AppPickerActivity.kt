package com.piercingxx.xxlauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.piercingxx.xxlauncher.data.AppInfo
import com.piercingxx.xxlauncher.theme.applyLauncherFont
import com.piercingxx.xxlauncher.theme.applyLauncherTheme
import com.piercingxx.xxlauncher.util.showToast
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

        fun showNewFolderDialog() {
            val input = EditText(this).apply {
                imeOptions = EditorInfo.IME_ACTION_DONE
                isSingleLine = true
                applyLauncherFont(app.settings.fontFamily)
            }

            fun save() {
                val name = input.text.toString().trim()
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
                        onFailure = {
                            showToast(getString(R.string.toast_invalid_folder_name))
                        },
                    )
                }
            }

            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.picker_new_folder)
                .setView(input)
                .setPositiveButton(R.string.action_done) { _, _ -> save() }
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    save()
                    dialog.dismiss()
                    true
                } else {
                    false
                }
            }

            dialog.show()
            dialog.applyLauncherTheme(app.themeManager, app.settings.fontFamily)
            input.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }

        fun render(apps: List<AppInfo>, folders: List<com.piercingxx.xxlauncher.data.Folder>) {
            container.removeAllViews()
            if (intent.getBooleanExtra(EXTRA_ALLOW_MOVE_UP, false)) addMoveRow(up = true)
            if (intent.getBooleanExtra(EXTRA_ALLOW_MOVE_DOWN, false)) addMoveRow(up = false)
            if (intent.getBooleanExtra(EXTRA_ALLOW_REARRANGE, false)) {
                addRow(getString(R.string.picker_rearrange)) {
                    setResult(RESULT_OK, Intent()
                        .putExtra(EXTRA_SLOT_INDEX, slotIndex)
                        .putExtra(EXTRA_REARRANGE, true))
                    finish()
                }
            }
            // Renaming the slot's app is handled back in the caller, where the
            // rename dialog and propagation live.
            if (intent.getBooleanExtra(EXTRA_ALLOW_RENAME, false)) {
                addRow(getString(R.string.action_change_label)) {
                    setResult(RESULT_OK, Intent()
                        .putExtra(EXTRA_SLOT_INDEX, slotIndex)
                        .putExtra(EXTRA_RENAME, true))
                    finish()
                }
            }
            if (intent.getIntExtra(EXTRA_FOLDER_OPTIONS_ID, -1) >= 0) {
                addRow(getString(R.string.picker_folder_options)) {
                    setResult(RESULT_OK, Intent()
                        .putExtra(EXTRA_SLOT_INDEX, slotIndex)
                        .putExtra(EXTRA_FOLDER_OPTIONS, true))
                    finish()
                }
            }
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
            if (allowFolders) {
                addRow(getString(R.string.picker_new_folder)) {
                    showNewFolderDialog()
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
        const val EXTRA_ALLOW_REARRANGE = "allow_rearrange"
        const val EXTRA_REARRANGE = "rearrange"
        const val EXTRA_ALLOW_RENAME = "allow_rename"
        const val EXTRA_RENAME = "rename"
        const val EXTRA_FOLDER_OPTIONS_ID = "folder_options_id"
        const val EXTRA_FOLDER_OPTIONS = "folder_options"
    }
}
