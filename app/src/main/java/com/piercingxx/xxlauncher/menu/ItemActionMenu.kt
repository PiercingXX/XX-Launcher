package com.piercingxx.xxlauncher.menu

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.piercingxx.xxlauncher.R
import com.piercingxx.xxlauncher.data.AppInfo
import com.piercingxx.xxlauncher.data.AppRepository
import com.piercingxx.xxlauncher.data.SettingsRepository
import com.piercingxx.xxlauncher.data.SlotEntry
import com.piercingxx.xxlauncher.folder.FolderManager
import com.piercingxx.xxlauncher.notification.AppMuteListenerService
import com.piercingxx.xxlauncher.theme.ThemeManager
import com.piercingxx.xxlauncher.theme.applyLauncherFont
import com.piercingxx.xxlauncher.theme.applyLauncherTheme
import com.piercingxx.xxlauncher.util.openAppInfo
import com.piercingxx.xxlauncher.util.requestUninstall
import com.piercingxx.xxlauncher.util.showToast
import com.piercingxx.xxlauncher.util.userFromToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Long-press action menu for app rows (drawer, folder members) and folders.
 * All state changes persist via the shared repositories; [onChanged] lets the
 * caller re-render.
 */
class ItemActionMenu(
    private val context: Context,
    private val appRepo: AppRepository,
    private val settings: SettingsRepository,
    private val themeManager: ThemeManager,
    private val folders: FolderManager? = null,
) {

    /**
     * Tied to the host activity when there is one, so a sheet that resolves
     * after the activity is gone never calls show() on a dead window.
     * MainScope is only the fallback for a non-lifecycle context.
     */
    private val scope: CoroutineScope =
        (context as? LifecycleOwner)?.lifecycleScope ?: MainScope()

    fun showAppMenu(
        app: AppInfo,
        isDrawerRow: Boolean = false,
        folderId: Int = -1,
        onChanged: () -> Unit = {},
    ) {
        val items = mutableListOf<Pair<String, () -> Unit>>()

        items.add(context.getString(R.string.action_app_info) to {
            context.openAppInfo(app.packageName, context.userFromToken(app.userToken))
        })

        items.add(context.getString(R.string.action_change_label) to {
            showRenameDialog(app.label) { newName ->
                // Blank resets to the real label.
                settings.setRenameLabel(app.key, newName)
                appRepo.refresh()
                onChanged()
            }
        })

        items.add(context.getString(R.string.action_add_to_home) to {
            addToHomeScreen(app, onChanged)
        })

        val hidden = appRepo.isHidden(app)
        items.add(
            (if (hidden) context.getString(R.string.action_show)
            else context.getString(R.string.action_hide)) to {
                appRepo.toggleHidden(app)
                context.showToast(
                    context.getString(if (hidden) R.string.toast_shown else R.string.toast_hidden)
                )
                onChanged()
            }
        )

        items.add(context.getString(R.string.action_disable_for) to {
            showDisableForDialog(app)
        })

        if (isDrawerRow) {
            val pinned = appRepo.isPinned(app)
            items.add(
                (if (pinned) context.getString(R.string.action_unpin)
                else context.getString(R.string.action_pin)) to {
                    appRepo.togglePinned(app)
                    onChanged()
                }
            )
            if (pinned) {
                items.add(context.getString(R.string.action_move_up) to {
                    settings.movePinned(app.key, up = true); onChanged()
                })
                items.add(context.getString(R.string.action_move_down) to {
                    settings.movePinned(app.key, up = false); onChanged()
                })
                items.add(context.getString(R.string.action_rearrange) to {
                    showPinnedRearrangeDialog(onChanged)
                })
            }
            if (folders != null) {
                items.add(context.getString(R.string.action_add_to_folder) to {
                    showAddToFolderDialog(app, onChanged)
                })
            }
        }

        if (folderId >= 0 && folders != null) {
            items.add(context.getString(R.string.action_move_up) to {
                scope.launch {
                    folders.moveMember(folderId, app.key, up = true).onFailure {
                        context.showToast(context.getString(R.string.toast_already_at_top))
                    }
                    onChanged()
                }
            })
            items.add(context.getString(R.string.action_move_down) to {
                scope.launch {
                    folders.moveMember(folderId, app.key, up = false).onFailure {
                        context.showToast(context.getString(R.string.toast_already_at_bottom))
                    }
                    onChanged()
                }
            })
            // Home-screen folders have no folder-level menu, so the full
            // rearrange sheet hangs off the member rows too.
            items.add(context.getString(R.string.action_rearrange) to {
                showRearrangeDialog(folderId, folderName = null, onChanged = onChanged)
            })
            items.add(context.getString(R.string.action_remove_from_folder) to {
                scope.launch {
                    folders.removeMember(folderId, app)
                    onChanged()
                }
            })
        }

        if (app.isShortcut) {
            items.add(context.getString(R.string.action_delete_shortcut) to {
                appRepo.deletePinnedShortcut(app)
                onChanged()
            })
        } else {
            items.add(context.getString(R.string.action_uninstall) to {
                if (app.isSystem) {
                    context.showToast(context.getString(R.string.toast_uninstall_failed))
                    context.openAppInfo(app.packageName, context.userFromToken(app.userToken))
                } else {
                    context.requestUninstall(
                        app.packageName,
                        context.userFromToken(app.userToken),
                    )
                }
            })
        }

        showThemedList(app.label, items)
    }

    /**
     * Puts the app in the first empty visible home slot, growing the visible
     * slot row by one when every slot is taken (a slot past the visible count
     * never renders, so a stale entry there is safe to overwrite).
     */
    private fun addToHomeScreen(app: AppInfo, onChanged: () -> Unit) {
        val visible = settings.slotCount.coerceIn(0, SettingsRepository.MAX_SLOTS)
        val alreadyPlaced = (1..visible).any { slot ->
            val entry = settings.getSlot(slot)
            !entry.isFolder && entry.packageName == app.packageName &&
                entry.userToken == app.userToken &&
                entry.shortcutId == app.shortcutId.orEmpty()
        }
        if (alreadyPlaced) {
            context.showToast(context.getString(R.string.toast_already_on_home))
            return
        }
        val target = (1..SettingsRepository.MAX_SLOTS).firstOrNull { slot ->
            slot > visible || settings.getSlot(slot).isEmpty
        }
        if (target == null) {
            context.showToast(context.getString(R.string.toast_home_full))
            return
        }
        if (target > visible) settings.slotCount = target
        settings.setSlot(
            target,
            SlotEntry(
                label = app.label,
                packageName = app.packageName,
                activityClassName = app.activityClassName.orEmpty(),
                userToken = app.userToken,
                shortcutId = app.shortcutId.orEmpty(),
            ),
        )
        context.showToast(context.getString(R.string.toast_added_to_home))
        onChanged()
    }

    fun showFolderMenu(
        folderId: Int,
        folderName: String,
        onChanged: () -> Unit = {},
    ) {
        val folders = folders ?: return
        val items = listOf<Pair<String, () -> Unit>>(
            context.getString(R.string.action_rename) to {
                showRenameDialog(folderName) { newName ->
                    scope.launch {
                        folders.renameFolder(folderId, newName)
                            .onFailure { context.showToast(context.getString(R.string.toast_invalid_folder_name)) }
                        onChanged()
                    }
                }
            },
            context.getString(R.string.action_rearrange) to {
                showRearrangeDialog(folderId, folderName, onChanged)
            },
            context.getString(R.string.action_move_up) to {
                scope.launch { folders.moveFolder(folderId, up = true); onChanged() }
            },
            context.getString(R.string.action_move_down) to {
                scope.launch { folders.moveFolder(folderId, up = false); onChanged() }
            },
            context.getString(R.string.action_delete) to {
                AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.confirm_delete_folder, folderName))
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        scope.launch { folders.deleteFolder(folderId); onChanged() }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                    .applyLauncherTheme(themeManager, settings.fontFamily)
            },
        )
        showThemedList(folderName, items)
    }

    fun showCreateFolderDialog(app: AppInfo, onChanged: () -> Unit) {
        val folders = folders ?: return
        showRenameDialog("") { name ->
            scope.launch {
                folders.createFolder(name).fold(
                    onSuccess = { folder ->
                        folders.addMember(folder.id, app)
                        onChanged()
                    },
                    onFailure = {
                        context.showToast(context.getString(R.string.toast_invalid_folder_name))
                    },
                )
            }
        }
    }

    /**
     * Manual folder ordering. Rows rebuild in place after every move so the
     * sheet stays open for a run of adjustments; each move is already
     * persisted, so [onChanged] fires once on dismiss.
     */
    private fun showRearrangeDialog(folderId: Int, folderName: String?, onChanged: () -> Unit) {
        val folders = folders ?: return
        scope.launch {
            val name = folderName ?: folders.getFolder(folderId)?.name ?: return@launch
            val members = folders.getMembers(folderId).toMutableList()
            if (members.size < 2) {
                context.showToast(context.getString(R.string.toast_nothing_to_rearrange))
                return@launch
            }

            val colors = themeManager.getCurrentColors()
            val fontKey = settings.fontFamily
            val scale = settings.textSizeScale
            fun dp(value: Int): Int =
                (value * context.resources.displayMetrics.density).toInt()

            val list = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(4), dp(20), dp(4))
            }

            fun arrow(
                glyph: Int,
                enabled: Boolean,
                description: String,
                onTap: () -> Unit,
            ): TextView = TextView(context).apply {
                text = context.getString(glyph)
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
                members.forEachIndexed { index, member ->
                    fun move(up: Boolean) {
                        scope.launch {
                            folders.moveMember(folderId, member.key, up)
                            members.clear()
                            members.addAll(folders.getMembers(folderId))
                            render()
                        }
                    }

                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val label = TextView(context).apply {
                        text = member.label
                        setTextColor(colors.textColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
                        setPadding(0, dp(6), dp(8), dp(6))
                        applyLauncherFont(fontKey)
                    }
                    row.addView(
                        label,
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                    )
                    row.addView(
                        arrow(
                            R.string.rearrange_up,
                            enabled = index > 0,
                            description = context.getString(
                                R.string.accessibility_move_up, member.label
                            ),
                        ) { move(up = true) }
                    )
                    row.addView(
                        arrow(
                            R.string.rearrange_down,
                            enabled = index < members.size - 1,
                            description = context.getString(
                                R.string.accessibility_move_down, member.label
                            ),
                        ) { move(up = false) }
                    )
                    list.addView(
                        row,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    )
                }
            }

            render()

            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.rearrange_title, name))
                .setView(ScrollView(context).apply { addView(list) })
                .setNeutralButton(R.string.action_sort_alphabetically, null)
                .setPositiveButton(R.string.action_done, null)
                .setOnDismissListener { onChanged() }
                .create()

            dialog.show()
            dialog.applyLauncherTheme(themeManager, fontKey)
            // Sorting re-renders in place; only Done closes the sheet.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                scope.launch {
                    folders.sortMembersAlphabetically(folderId)
                    members.clear()
                    members.addAll(folders.getMembers(folderId))
                    render()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.dismiss() }
        }
    }

    /**
     * Manual ordering for pinned drawer rows; mirrors the folder rearrange
     * sheet, but pinned order lives in prefs so no coroutine is needed.
     */
    private fun showPinnedRearrangeDialog(onChanged: () -> Unit) {
        if (settings.pinnedApps.size < 2) {
            context.showToast(context.getString(R.string.toast_nothing_to_rearrange))
            return
        }

        val colors = themeManager.getCurrentColors()
        val fontKey = settings.fontFamily
        val scale = settings.textSizeScale
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

        fun labelFor(key: String): String =
            appRepo.apps.value?.firstOrNull { it.key == key }?.label
                ?: key.substringBefore("|")

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }

        fun arrow(
            glyph: Int,
            enabled: Boolean,
            description: String,
            onTap: () -> Unit,
        ): TextView = TextView(context).apply {
            text = context.getString(glyph)
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
            val keys = settings.pinnedApps
            keys.forEachIndexed { index, key ->
                val labelText = labelFor(key)
                fun move(up: Boolean) {
                    settings.movePinned(key, up)
                    render()
                }

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val label = TextView(context).apply {
                    text = labelText
                    setTextColor(colors.textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
                    setPadding(0, dp(6), dp(8), dp(6))
                    applyLauncherFont(fontKey)
                }
                row.addView(
                    label,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                row.addView(
                    arrow(
                        R.string.rearrange_up,
                        enabled = index > 0,
                        description = context.getString(R.string.accessibility_move_up, labelText),
                    ) { move(up = true) }
                )
                row.addView(
                    arrow(
                        R.string.rearrange_down,
                        enabled = index < keys.size - 1,
                        description = context.getString(R.string.accessibility_move_down, labelText),
                    ) { move(up = false) }
                )
                list.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                )
            }
        }

        render()

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.rearrange_pinned_title)
            .setView(ScrollView(context).apply { addView(list) })
            .setPositiveButton(R.string.action_done, null)
            .setOnDismissListener { onChanged() }
            .create()
        dialog.show()
        dialog.applyLauncherTheme(themeManager, fontKey)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.dismiss() }
    }

    private fun showAddToFolderDialog(app: AppInfo, onChanged: () -> Unit) {
        val folders = folders ?: return
        scope.launch {
            val existing = folders.getFolders()
            val names = existing.map { it.name } + context.getString(R.string.action_create_folder)
            AlertDialog.Builder(context)
                .setTitle(R.string.action_add_to_folder)
                .setItems(names.toTypedArray()) { _, which ->
                    if (which < existing.size) {
                        scope.launch {
                            folders.addMember(existing[which].id, app).fold(
                                onSuccess = { onChanged() },
                                onFailure = {
                                    context.showToast(
                                        context.getString(R.string.toast_already_in_folder)
                                    )
                                },
                            )
                        }
                    } else {
                        showCreateFolderDialog(app, onChanged)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .applyLauncherTheme(themeManager, settings.fontFamily)
        }
    }

    private fun showThemedList(title: String, items: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                items[which].second()
            }
            .show()
            .applyLauncherTheme(themeManager, settings.fontFamily)
    }

    private fun showRenameDialog(currentLabel: String, onSave: (String) -> Unit) {
        val input = EditText(context).apply {
            setText(currentLabel)
            setSelection(0, text.length)
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.action_change_label)
            .setView(input)
            .setPositiveButton(R.string.action_rename) { _, _ ->
                onSave(input.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // Commit via keyboard Done too, not just the button.
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onSave(input.text.toString().trim())
                dialog.dismiss()
                true
            } else {
                false
            }
        }

        dialog.show()
        dialog.applyLauncherTheme(themeManager, settings.fontFamily)
        input.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showDisableForDialog(app: AppInfo) {
        // Muting works through the notification listener; route to the system
        // access screen on first use.
        if (!AppMuteListenerService.isConnected) {
            context.showToast(context.getString(R.string.notification_access_needed))
            runCatching {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            return
        }

        val hours = listOf(1, 2, 4, 8)
        val labels = hours.map { context.getString(R.string.disable_for_hours, it) }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.action_disable_for))
            .setItems(labels.toTypedArray()) { _, which ->
                val until = System.currentTimeMillis() + hours[which] * 60 * 60 * 1000L
                settings.setMuteUntil(app.packageName, until)
                AppMuteListenerService.instance?.cancelAllFrom(app.packageName)
                val time = DateFormat.getTimeFormat(context).format(Date(until))
                context.showToast(context.getString(R.string.toast_disabled_until, time))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .applyLauncherTheme(themeManager, settings.fontFamily)
    }
}
