package com.piercingxx.xxlauncher.menu

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.piercingxx.xxlauncher.R
import com.piercingxx.xxlauncher.data.AppInfo
import com.piercingxx.xxlauncher.data.AppRepository
import com.piercingxx.xxlauncher.data.RenamePropagator
import com.piercingxx.xxlauncher.data.SettingsRepository
import com.piercingxx.xxlauncher.data.SlotEntry
import com.piercingxx.xxlauncher.folder.FolderManager
import com.piercingxx.xxlauncher.notification.AppMuteListenerService
import com.piercingxx.xxlauncher.theme.ThemeManager
import com.piercingxx.xxlauncher.util.openAppInfo
import com.piercingxx.xxlauncher.util.requestUninstall
import com.piercingxx.xxlauncher.util.showToast
import com.piercingxx.xxlauncher.util.userFromToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Long-press menus for app rows (drawer, folder members), folders and home
 * slots, all as [ActionSheet]s. Rows are grouped: placement first, then the
 * app's own label / visibility / mute, then system actions with Uninstall
 * last. All state changes persist via the shared repositories; [onChanged]
 * lets the caller re-render.
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

    private fun sheet() = ActionSheet(context, themeManager, settings)

    private fun str(@StringRes id: Int): String = context.getString(id)

    private fun row(@StringRes label: Int, enabled: Boolean = true, onTap: () -> Unit) =
        ActionSheet.Row(str(label), enabled = enabled, onTap = onTap)

    // App rows

    fun showAppMenu(
        app: AppInfo,
        isDrawerRow: Boolean = false,
        folderId: Int = -1,
        onChanged: () -> Unit = {},
    ) {
        if (folderId >= 0 && folders != null) {
            // Member position decides which move rows are live.
            scope.launch {
                val keys = folders.getMembers(folderId).map { it.key }
                buildAppMenu(app, isDrawerRow, folderId, keys.indexOf(app.key), keys.size, onChanged)
            }
        } else {
            buildAppMenu(app, isDrawerRow, -1, -1, 0, onChanged)
        }
    }

    private fun buildAppMenu(
        app: AppInfo,
        isDrawerRow: Boolean,
        folderId: Int,
        memberIndex: Int,
        memberCount: Int,
        onChanged: () -> Unit,
    ) {
        val placement = mutableListOf<ActionSheet.Row>()
        val editing = mutableListOf<ActionSheet.Row>()
        val system = mutableListOf<ActionSheet.Row>()

        placement += row(R.string.action_add_to_home) { addToHomeScreen(app, onChanged) }

        if (isDrawerRow) {
            val pinned = appRepo.isPinned(app)
            placement += row(if (pinned) R.string.action_unpin else R.string.action_pin) {
                appRepo.togglePinned(app)
                onChanged()
            }
            if (pinned) {
                val order = settings.pinnedApps
                val index = order.indexOf(app.key)
                placement += row(R.string.action_move_up, enabled = index > 0) {
                    settings.movePinned(app.key, up = true); onChanged()
                }
                placement += row(R.string.action_move_down, enabled = index in 0 until order.size - 1) {
                    settings.movePinned(app.key, up = false); onChanged()
                }
                placement += row(R.string.rearrange_pinned_title, enabled = order.size >= 2) {
                    showPinnedRearrange(onChanged)
                }
            }
            if (folders != null) {
                placement += row(R.string.action_add_to_folder) { showAddToFolderSheet(app, onChanged) }
            }
        }

        if (folderId >= 0 && folders != null) {
            placement += row(R.string.action_move_up, enabled = memberIndex > 0) {
                scope.launch { folders.moveMember(folderId, app.key, up = true); onChanged() }
            }
            placement += row(R.string.action_move_down, enabled = memberIndex in 0 until memberCount - 1) {
                scope.launch { folders.moveMember(folderId, app.key, up = false); onChanged() }
            }
            // Home-screen folders have no folder-level menu, so the full
            // rearrange sheet hangs off the member rows too.
            placement += row(R.string.action_rearrange, enabled = memberCount >= 2) {
                showFolderRearrange(folderId, folderName = null, onChanged = onChanged)
            }
            placement += row(R.string.action_remove_from_folder) {
                scope.launch { folders.removeMember(folderId, app); onChanged() }
            }
        }

        editing += row(R.string.action_change_label) {
            showRenameSheet(str(R.string.action_change_label), app.label, str(R.string.rename_hint_blank_resets)) { newName ->
                // Blank resets to the real label; the repository rewrites any
                // home slot holding the app too.
                appRepo.rename(app, newName)
                onChanged()
            }
        }
        val hidden = appRepo.isHidden(app)
        editing += row(if (hidden) R.string.action_show else R.string.action_hide) {
            appRepo.toggleHidden(app)
            context.showToast(str(if (hidden) R.string.toast_shown else R.string.toast_hidden))
            onChanged()
        }
        editing += row(R.string.action_disable_for) { showDisableForSheet(app) }

        system += row(R.string.action_app_info) {
            context.openAppInfo(app.packageName, context.userFromToken(app.userToken))
        }
        if (app.isShortcut) {
            system += row(R.string.action_delete_shortcut) {
                appRepo.deletePinnedShortcut(app)
                onChanged()
            }
        } else {
            system += row(R.string.action_uninstall) {
                if (app.isSystem) {
                    context.showToast(str(R.string.toast_uninstall_failed))
                    context.openAppInfo(app.packageName, context.userFromToken(app.userToken))
                } else {
                    context.requestUninstall(app.packageName, context.userFromToken(app.userToken))
                }
            }
        }

        sheet()
            .title(app.label)
            .subtitle(appSubtitle(app, hidden))
            .group(placement)
            .group(editing)
            .group(system)
            .show()
    }

    /** Only the facts that distinguish this row; nothing for the common case. */
    private fun appSubtitle(app: AppInfo, hidden: Boolean): String? {
        val parts = mutableListOf<String>()
        if (app.isShortcut) parts += str(R.string.sheet_subtitle_shortcut)
        if (app.isWorkProfile) parts += str(R.string.accessibility_work_profile)
        if (hidden) parts += str(R.string.sheet_subtitle_hidden)
        val muteUntil = settings.getMuteUntil(app.packageName)
        if (muteUntil > System.currentTimeMillis()) {
            val time = DateFormat.getTimeFormat(context).format(Date(muteUntil))
            parts += context.getString(R.string.sheet_subtitle_muted_until, time)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
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
            context.showToast(str(R.string.toast_already_on_home))
            return
        }
        val target = (1..SettingsRepository.MAX_SLOTS).firstOrNull { slot ->
            slot > visible || settings.getSlot(slot).isEmpty
        }
        if (target == null) {
            context.showToast(str(R.string.toast_home_full))
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
        context.showToast(str(R.string.toast_added_to_home))
        onChanged()
    }

    // Home slots

    /**
     * Long-press menu for a home slot. [onChangeApp] opens the picker; the
     * picker itself only picks, every other slot action lives here.
     */
    fun showSlotMenu(slot: Int, onChangeApp: () -> Unit, onChanged: () -> Unit) {
        val entry = settings.getSlot(slot)
        val visible = settings.slotCount.coerceIn(0, SettingsRepository.MAX_SLOTS)
        val order = listOf(
            row(R.string.action_move_up, enabled = slot > 1) {
                settings.swapSlots(slot, slot - 1); onChanged()
            },
            row(R.string.action_move_down, enabled = slot < visible) {
                settings.swapSlots(slot, slot + 1); onChanged()
            },
            row(R.string.rearrange_home_title, enabled = visible >= 2) { showHomeRearrange(onChanged) },
        )
        val clear = row(R.string.action_clear_slot) {
            settings.removeSlot(slot)
            onChanged()
        }

        when {
            entry.isEmpty -> sheet()
                .title(str(R.string.sheet_title_empty_slot))
                .subtitle(context.getString(R.string.sheet_subtitle_home_slot, slot))
                .group(row(R.string.action_choose_app) { onChangeApp() })
                .group(order)
                .group(clear)
                .show()

            entry.isFolder -> sheet()
                .title(entry.label)
                .subtitle(context.getString(R.string.sheet_subtitle_folder_slot, slot))
                .group(
                    row(R.string.action_change_app) { onChangeApp() },
                    row(R.string.action_rename_folder) { showFolderRename(entry.folderId, entry.label, onChanged) },
                    row(R.string.action_rearrange) {
                        showFolderRearrange(entry.folderId, entry.label, onChanged)
                    },
                )
                .group(order)
                .group(
                    clear,
                    row(R.string.action_delete_folder) {
                        confirmDeleteFolder(entry.folderId, entry.label, onChanged)
                    },
                )
                .show()

            else -> sheet()
                .title(entry.label)
                .subtitle(context.getString(R.string.sheet_subtitle_home_slot, slot))
                .group(
                    row(R.string.action_change_app) { onChangeApp() },
                    row(R.string.action_change_label) { showRenameForSlot(entry, onChanged) },
                )
                .group(order)
                .group(
                    row(R.string.action_app_info) {
                        context.openAppInfo(entry.packageName, context.userFromToken(entry.userToken))
                    },
                    clear,
                )
                .show()
        }
    }

    /**
     * Rename entry point for an occupied home app slot, where only a
     * [SlotEntry] is known. Resolves the slot back to its drawer row so the
     * rename runs through [AppRepository.rename] and propagates everywhere;
     * if the app list has not loaded yet, the rename is stored anyway and the
     * home screen's label refresh picks it up on the next render.
     */
    fun showRenameForSlot(entry: SlotEntry, onChanged: () -> Unit = {}) {
        val key = RenamePropagator.renameKey(entry) ?: return
        showRenameSheet(str(R.string.action_change_label), entry.label, str(R.string.rename_hint_blank_resets)) { newName ->
            val app = appRepo.apps.value?.firstOrNull { it.key == key }
            if (app != null) {
                // Blank resets to the real label.
                appRepo.rename(app, newName)
            } else {
                settings.setRenameLabel(key, newName.trim())
                appRepo.refresh()
            }
            onChanged()
        }
    }

    private fun showHomeRearrange(onChanged: () -> Unit) {
        val visible = settings.slotCount.coerceIn(0, SettingsRepository.MAX_SLOTS)
        val entries = (1..visible).map { settings.getSlot(it) }
        if (entries.size < 2) {
            context.showToast(str(R.string.toast_nothing_to_rearrange))
            return
        }
        ReorderSheet(context, themeManager, settings).show(
            title = str(R.string.rearrange_home_title),
            items = entries.mapIndexed { index, entry ->
                ReorderSheet.Item(
                    id = index.toString(),
                    label = if (entry.isEmpty) str(R.string.home_slot_empty) else entry.label,
                    dimmed = entry.isEmpty,
                )
            },
            // Ids index the snapshot, so every drop is a permutation of it.
            onOrderChanged = { ids -> settings.replaceVisibleSlots(ids.map { entries[it.toInt()] }) },
            onDismiss = onChanged,
        )
    }

    // Folders

    fun showFolderMenu(
        folderId: Int,
        folderName: String,
        onChanged: () -> Unit = {},
    ) {
        val folders = folders ?: return
        scope.launch {
            val all = folders.getFolders()
            val index = all.indexOfFirst { it.id == folderId }
            val memberCount = folders.getMembers(folderId).size
            sheet()
                .title(folderName)
                .subtitle(str(R.string.sheet_subtitle_folder))
                .group(
                    row(R.string.action_rename_folder) { showFolderRename(folderId, folderName, onChanged) },
                    row(R.string.action_rearrange, enabled = memberCount >= 2) {
                        showFolderRearrange(folderId, folderName, onChanged)
                    },
                )
                .group(
                    row(R.string.action_move_up, enabled = index > 0) {
                        scope.launch { folders.moveFolder(folderId, up = true); onChanged() }
                    },
                    row(R.string.action_move_down, enabled = index in 0 until all.size - 1) {
                        scope.launch { folders.moveFolder(folderId, up = false); onChanged() }
                    },
                )
                .group(row(R.string.action_delete_folder) { confirmDeleteFolder(folderId, folderName, onChanged) })
                .show()
        }
    }

    fun showCreateFolderDialog(app: AppInfo, onChanged: () -> Unit) {
        val folders = folders ?: return
        showRenameSheet(str(R.string.sheet_title_new_folder), "", str(R.string.folder_name_hint)) { name ->
            scope.launch {
                folders.createFolder(name).fold(
                    onSuccess = { folder ->
                        folders.addMember(folder.id, app)
                        onChanged()
                    },
                    onFailure = { context.showToast(str(R.string.toast_invalid_folder_name)) },
                )
            }
        }
    }

    private fun showFolderRename(folderId: Int, currentName: String, onChanged: () -> Unit) {
        val folders = folders ?: return
        showRenameSheet(str(R.string.action_rename_folder), currentName, str(R.string.folder_name_hint)) { newName ->
            scope.launch {
                folders.renameFolder(folderId, newName)
                    .onFailure { context.showToast(str(R.string.toast_invalid_folder_name)) }
                onChanged()
            }
        }
    }

    private fun confirmDeleteFolder(folderId: Int, folderName: String, onChanged: () -> Unit) {
        val folders = folders ?: return
        val sheet = sheet()
            .title(context.getString(R.string.confirm_delete_folder, folderName))
            .subtitle(str(R.string.confirm_delete_folder_message))
        sheet.button(str(android.R.string.cancel)) { sheet.dismiss() }
        sheet.button(str(R.string.action_delete), primary = true) {
            sheet.dismiss()
            scope.launch { folders.deleteFolder(folderId); onChanged() }
        }
        sheet.show()
    }

    /**
     * Drag-to-reorder for folder members. Each drop is persisted as it lands,
     * so [onChanged] fires once on dismiss.
     */
    private fun showFolderRearrange(folderId: Int, folderName: String?, onChanged: () -> Unit) {
        val folders = folders ?: return
        scope.launch {
            val name = folderName ?: folders.getFolder(folderId)?.name ?: return@launch
            val members = folders.getMembers(folderId)
            if (members.size < 2) {
                context.showToast(str(R.string.toast_nothing_to_rearrange))
                return@launch
            }
            fun items(list: List<AppInfo>) = list.map { ReorderSheet.Item(it.key, it.label) }
            ReorderSheet(context, themeManager, settings).show(
                title = context.getString(R.string.rearrange_title, name),
                items = items(members),
                sortLabel = str(R.string.action_sort_alphabetically),
                onSort = { handle ->
                    scope.launch {
                        folders.sortMembersAlphabetically(folderId)
                        handle.replace(items(folders.getMembers(folderId)))
                    }
                },
                onOrderChanged = { keys -> scope.launch { folders.setMemberOrder(folderId, keys) } },
                onDismiss = onChanged,
            )
        }
    }

    /** Drag-to-reorder for pinned drawer rows; pinned order lives in prefs. */
    private fun showPinnedRearrange(onChanged: () -> Unit) {
        val keys = settings.pinnedApps
        if (keys.size < 2) {
            context.showToast(str(R.string.toast_nothing_to_rearrange))
            return
        }
        fun labelFor(key: String): String =
            appRepo.apps.value?.firstOrNull { it.key == key }?.label ?: key.substringBefore("|")
        ReorderSheet(context, themeManager, settings).show(
            title = str(R.string.rearrange_pinned_title),
            items = keys.map { ReorderSheet.Item(it, labelFor(it)) },
            onOrderChanged = { order -> settings.pinnedApps = order },
            onDismiss = onChanged,
        )
    }

    private fun showAddToFolderSheet(app: AppInfo, onChanged: () -> Unit) {
        val folders = folders ?: return
        scope.launch {
            val existing = folders.getFolders()
            val rows = existing.map { folder ->
                ActionSheet.Row(folder.name) {
                    scope.launch {
                        folders.addMember(folder.id, app).fold(
                            onSuccess = { onChanged() },
                            onFailure = { context.showToast(str(R.string.toast_already_in_folder)) },
                        )
                    }
                }
            }
            sheet()
                .title(str(R.string.action_add_to_folder))
                .subtitle(app.label)
                .group(rows)
                .group(row(R.string.picker_new_folder) { showCreateFolderDialog(app, onChanged) })
                .show()
        }
    }

    // Shared prompts

    private fun showRenameSheet(title: String, current: String, hint: String, onSave: (String) -> Unit) {
        val sheet = sheet().title(title)
        val input = sheet.input(current, hint, onSave)
        sheet.button(str(android.R.string.cancel)) { sheet.dismiss() }
        sheet.button(str(R.string.action_save), primary = true) {
            sheet.dismiss()
            onSave(input.text.toString().trim())
        }
        sheet.show()
    }

    private fun showDisableForSheet(app: AppInfo) {
        // Muting works through the notification listener; route to the system
        // access screen on first use.
        if (!AppMuteListenerService.isConnected) {
            context.showToast(str(R.string.notification_access_needed))
            runCatching {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            return
        }
        val rows = listOf(1, 2, 4, 8).map { hours ->
            ActionSheet.Row(context.resources.getQuantityString(R.plurals.disable_for_hours, hours, hours)) {
                val until = System.currentTimeMillis() + hours * 60 * 60 * 1000L
                settings.setMuteUntil(app.packageName, until)
                AppMuteListenerService.instance?.cancelAllFrom(app.packageName)
                val time = DateFormat.getTimeFormat(context).format(Date(until))
                context.showToast(context.getString(R.string.toast_disabled_until, time))
            }
        }
        sheet()
            .title(str(R.string.action_disable_for))
            .subtitle(app.label)
            .group(rows)
            .show()
    }
}
