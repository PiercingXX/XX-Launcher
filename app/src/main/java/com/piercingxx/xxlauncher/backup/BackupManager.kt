package com.piercingxx.xxlauncher.backup

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.piercingxx.xxlauncher.data.SettingsRepository
import com.piercingxx.xxlauncher.data.SlotEntry
import com.piercingxx.xxlauncher.folder.FolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val BACKUP_VERSION = 1

data class BackupData(
    val version: Int = BACKUP_VERSION,
    val timestamp: Long = 0L,
    val slotCount: Int = 4,
    val textAlignment: String = "center",
    val dateTimeMode: String = "date_time",
    val statusBarVisible: Boolean = false,
    val widgetsEnabled: List<String>? = emptyList(),
    val widgetTapActions: Map<String, String>? = emptyMap(),
    val weatherTempUnit: String = "fahrenheit",
    val autoShowKeyboard: Boolean = false,
    val sortMode: String = "default",
    val swipeLeftApp: String? = null,
    val swipeRightApp: String? = null,
    val swipeLeftEnabled: Boolean = true,
    val swipeRightEnabled: Boolean = true,
    val swipeDownAction: String = "notifications",
    val doubleTapLock: Boolean = false,
    val homeToRecents: Boolean = false,
    val themePreset: String = "amoled",
    val customBgColor: String = "",
    val appearanceMode: String = "dark",
    val fontFamily: String = "jetbrains_mono_nerd",
    val textSizeScale: Float = 1f,
    val hiddenApps: List<String>? = emptyList(),
    val pinnedApps: List<String>? = emptyList(),
    val renameLabels: Map<String, String>? = emptyMap(),
    val slots: List<BackupSlot>? = emptyList(),
    val folders: List<BackupFolder>? = emptyList(),
    /** `"package|untilEpochMillis"` entries; absent on v1 files written before mutes. */
    val mutedApps: List<String>? = emptyList(),
)

data class BackupSlot(
    val index: Int,
    val label: String,
    val packageName: String,
    val activityClassName: String,
    val userToken: String,
    /** Folder *name* — folder ids are not stable across restores. */
    val folderName: String?,
    /**
     * Set when the slot points at an Android pinned shortcut. Nullable so
     * backups written before this field existed still import.
     */
    val shortcutId: String? = null,
)

data class BackupFolder(
    val name: String,
    val members: List<String>? = emptyList(),
)

/**
 * Versioned JSON export/import of every user-configurable thing. Import
 * validates the full payload before writing anything, then replaces live
 * state instead of merging with leftovers.
 */
class BackupManager(
    private val settings: SettingsRepository,
    private val folders: FolderManager,
) {

    private val gson = Gson()

    suspend fun exportToJson(): String = withContext(Dispatchers.IO) {
        val folderEntities = folders.getFolders()
        val backupFolders = folderEntities.map { folder ->
            BackupFolder(
                name = folder.name,
                members = folders.getMembers(folder.id).map { it.key },
            )
        }
        val folderNamesById = folderEntities.associate { it.id to it.name }

        val slots = (1..SettingsRepository.MAX_SLOTS).mapNotNull { index ->
            val entry = settings.getSlot(index)
            if (entry.isEmpty) return@mapNotNull null
            BackupSlot(
                index = index,
                label = entry.label,
                packageName = entry.packageName,
                activityClassName = entry.activityClassName,
                userToken = entry.userToken,
                folderName = folderNamesById[entry.folderId],
                shortcutId = entry.shortcutId.ifBlank { null },
            )
        }

        gson.toJson(
            BackupData(
                version = BACKUP_VERSION,
                timestamp = System.currentTimeMillis(),
                slotCount = settings.slotCount,
                textAlignment = settings.textAlignment,
                dateTimeMode = settings.dateTimeMode,
                statusBarVisible = settings.statusBarVisible,
                widgetsEnabled = settings.widgetsOrder,
                widgetTapActions = SettingsRepository.ALL_WIDGETS.associateWith {
                    settings.getWidgetTapAction(it)
                },
                weatherTempUnit = settings.weatherTempUnit,
                autoShowKeyboard = settings.autoShowKeyboard,
                sortMode = settings.sortMode,
                swipeLeftApp = settings.swipeLeftApp,
                swipeRightApp = settings.swipeRightApp,
                swipeLeftEnabled = settings.swipeLeftEnabled,
                swipeRightEnabled = settings.swipeRightEnabled,
                swipeDownAction = settings.swipeDownAction,
                doubleTapLock = settings.doubleTapLock,
                homeToRecents = settings.homeToRecents,
                themePreset = settings.themePreset,
                customBgColor = settings.customBgColor.toUInt().toString(16),
                appearanceMode = settings.appearanceMode,
                fontFamily = settings.fontFamily,
                textSizeScale = settings.textSizeScale,
                hiddenApps = settings.hiddenApps.toList(),
                pinnedApps = settings.pinnedApps,
                renameLabels = settings.getRenameLabels(),
                slots = slots,
                folders = backupFolders,
                mutedApps = settings.getMuteEntries().map { (pkg, until) -> "$pkg|$until" },
            )
        )
    }

    suspend fun importFromJson(json: String): Result<Unit> = withContext(Dispatchers.IO) {
        val data = parseBackupJson(gson, json).getOrElse { return@withContext Result.failure(it) }

        if (data.version != BACKUP_VERSION) {
            return@withContext Result.failure(
                IllegalArgumentException("Unsupported backup version ${data.version}")
            )
        }
        if (data.slotCount !in 0..SettingsRepository.MAX_SLOTS ||
            data.slots.orEmpty().any { it.index !in 1..SettingsRepository.MAX_SLOTS } ||
            data.textSizeScale !in 0.25f..4f
        ) {
            return@withContext Result.failure(IllegalArgumentException("Backup failed validation"))
        }

        val nameToId = folders.replaceAll(
            data.folders.orEmpty().map { it.name to it.members.orEmpty() }
        )

        settings.slotCount = data.slotCount
        settings.textAlignment = data.textAlignment
        settings.dateTimeMode = data.dateTimeMode
        settings.statusBarVisible = data.statusBarVisible
        settings.widgetsOrder = data.widgetsEnabled.orEmpty()
        settings.replaceWidgetTapActions(data.widgetTapActions.orEmpty())
        settings.weatherTempUnit = data.weatherTempUnit
        settings.autoShowKeyboard = data.autoShowKeyboard
        settings.sortMode = data.sortMode
        settings.swipeLeftApp = data.swipeLeftApp
        settings.swipeRightApp = data.swipeRightApp
        settings.swipeLeftEnabled = data.swipeLeftEnabled
        settings.swipeRightEnabled = data.swipeRightEnabled
        settings.swipeDownAction = data.swipeDownAction
        settings.doubleTapLock = data.doubleTapLock
        settings.homeToRecents = data.homeToRecents
        settings.themePreset = data.themePreset
        data.customBgColor.toLongOrNull(16)?.let { settings.customBgColor = it.toInt() }
        settings.appearanceMode = data.appearanceMode
        settings.fontFamily = data.fontFamily
        settings.textSizeScale = data.textSizeScale
        settings.hiddenApps = data.hiddenApps.orEmpty().toSet()
        settings.pinnedApps = data.pinnedApps.orEmpty()
        settings.replaceRenameLabels(data.renameLabels.orEmpty())
        settings.replaceMuteEntries(parseMuteEntries(data.mutedApps.orEmpty()))
        settings.firstRunSeeded = true

        for (slot in 1..SettingsRepository.MAX_SLOTS) settings.clearSlot(slot)
        data.slots.orEmpty().forEach { slot ->
            settings.setSlot(
                slot.index,
                SlotEntry(
                    label = slot.label,
                    packageName = slot.packageName,
                    activityClassName = slot.activityClassName,
                    userToken = slot.userToken,
                    folderId = slot.folderName?.let { nameToId[it] } ?: -1,
                    shortcutId = slot.shortcutId.orEmpty(),
                ),
            )
        }

        Result.success(Unit)
    }
}

internal fun parseBackupJson(gson: Gson, json: String): Result<BackupData> {
    val data = try {
        gson.fromJson(json, BackupData::class.java)
    } catch (e: JsonSyntaxException) {
        return Result.failure(IllegalArgumentException("Not a valid backup file"))
    } ?: return Result.failure(IllegalArgumentException("Empty backup file"))
    return Result.success(data.normalized())
}

internal fun BackupData.normalized(): BackupData = BackupData(
    version = version,
    timestamp = timestamp,
    slotCount = slotCount,
    textAlignment = textAlignment,
    dateTimeMode = dateTimeMode,
    statusBarVisible = statusBarVisible,
    widgetsEnabled = widgetsEnabled.orEmpty(),
    widgetTapActions = widgetTapActions.orEmpty(),
    weatherTempUnit = weatherTempUnit,
    autoShowKeyboard = autoShowKeyboard,
    sortMode = sortMode,
    swipeLeftApp = swipeLeftApp,
    swipeRightApp = swipeRightApp,
    swipeLeftEnabled = swipeLeftEnabled,
    swipeRightEnabled = swipeRightEnabled,
    swipeDownAction = swipeDownAction,
    doubleTapLock = doubleTapLock,
    homeToRecents = homeToRecents,
    themePreset = themePreset,
    customBgColor = customBgColor,
    appearanceMode = appearanceMode,
    fontFamily = fontFamily,
    textSizeScale = textSizeScale,
    hiddenApps = hiddenApps.orEmpty(),
    pinnedApps = pinnedApps.orEmpty(),
    renameLabels = renameLabels.orEmpty(),
    slots = slots.orEmpty(),
    folders = folders.orEmpty().map { it.copy(members = it.members.orEmpty()) },
    mutedApps = mutedApps.orEmpty(),
)

internal fun parseMuteEntries(entries: List<String>): Map<String, Long> =
    entries.mapNotNull { entry ->
        val pkg = entry.substringBefore("|")
        val until = entry.substringAfter("|").toLongOrNull() ?: return@mapNotNull null
        if (pkg.isBlank()) null else pkg to until
    }.toMap()
