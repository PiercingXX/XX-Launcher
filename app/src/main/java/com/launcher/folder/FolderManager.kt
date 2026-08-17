package com.launcher.folder

import android.content.Context
import android.content.pm.LauncherApps
import com.launcher.data.AppDatabase
import com.launcher.data.AppInfo
import com.launcher.data.Folder
import com.launcher.data.FolderMember
import com.launcher.data.SettingsRepository
import com.launcher.util.serializeUser
import com.launcher.util.userFromToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

data class FolderWithCount(val folder: Folder, val memberCount: Int)

/**
 * Room-backed folders. Members are stored as "package|userToken" keys and
 * resolved against LauncherApps at read time, so uninstalled apps drop out
 * gracefully.
 */
class FolderManager(
    private val context: Context,
    private val settings: SettingsRepository,
) {

    private val db = AppDatabase.getDatabase(context)
    private val folderDao = db.folderDao()

    suspend fun getFolders(): List<Folder> = withContext(Dispatchers.IO) {
        folderDao.getAllFolders().firstOrNull() ?: emptyList()
    }

    suspend fun getFoldersWithCounts(): List<FolderWithCount> = withContext(Dispatchers.IO) {
        getFolders().mapNotNull { folder ->
            val members = getMembers(folder.id)
            if (members.isEmpty()) null else FolderWithCount(folder, members.size)
        }
    }

    suspend fun getFolder(folderId: Int): Folder? = withContext(Dispatchers.IO) {
        folderDao.getFolder(folderId).firstOrNull()
    }

    /**
     * Resolves member rows to launchable apps in the user's manual order;
     * prunes entries that no longer resolve.
     */
    suspend fun getMembers(folderId: Int): List<AppInfo> = withContext(Dispatchers.IO) {
        val launcherApps =
            context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val rows = folderDao.getFolderMembers(folderId)
        val resolved = mutableListOf<Pair<FolderMember, AppInfo>>()
        val stale = mutableListOf<FolderMember>()

        rows.forEach { row ->
            // Keys are "package|userToken", or "package|shortcutId|userToken"
            // for pinned shortcuts — so the profile is always the LAST part.
            val parts = row.appId.split("|")
            val packageName = parts.first()
            val userToken = parts.getOrNull(parts.lastIndex.coerceAtLeast(1)) ?: "personal"
            val user = context.userFromToken(userToken)
            val activity = runCatching {
                launcherApps.getActivityList(packageName, user).firstOrNull()
            }.getOrNull()
            if (activity == null) {
                stale.add(row)
                return@forEach
            }
            val original = activity.label.toString()
            val rename = settings.getRenameLabel(row.appId)
            resolved.add(
                row to AppInfo(
                    packageName = packageName,
                    activityClassName = activity.componentName.className,
                    label = rename.ifBlank { original },
                    originalLabel = original,
                    userToken = serializeUser(user),
                    isSystem = false,
                    installedAt = activity.firstInstallTime,
                    sizeBytes = 0L,
                )
            )
        }

        stale.forEach { folderDao.deleteMember(it) }

        // Ties break alphabetically, so folders written before manual ordering
        // existed (every row at 0) keep the order they used to display in.
        // Renumbering to 0..n-1 also closes gaps left by removed members.
        val ordered = resolved.sortedWith(
            compareBy<Pair<FolderMember, AppInfo>>({ it.first.sortOrder })
                .thenBy { it.second.label.lowercase() }
        )
        val renumbered = ordered.mapIndexedNotNull { index, (row, _) ->
            row.takeIf { it.sortOrder != index }?.copy(sortOrder = index)
        }
        if (renumbered.isNotEmpty()) folderDao.updateMembers(renumbered)

        ordered.map { it.second }
    }

    suspend fun createFolder(name: String): Result<Folder> = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Folder name cannot be blank"))
        }
        if (folderDao.countByName(trimmed) > 0) {
            return@withContext Result.failure(IllegalArgumentException("Folder already exists"))
        }
        val folder = Folder(name = trimmed, sortOrder = folderDao.count())
        val id = folderDao.insert(folder).toInt()
        Result.success(folder.copy(id = id))
    }

    suspend fun renameFolder(folderId: Int, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val trimmed = newName.trim()
            if (trimmed.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Folder name cannot be blank"))
            }
            val folder = folderDao.getFolder(folderId).firstOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Folder not found"))
            if (!folder.name.equals(trimmed, ignoreCase = true) && folderDao.countByName(trimmed) > 0) {
                return@withContext Result.failure(IllegalArgumentException("Folder already exists"))
            }
            folderDao.update(folder.copy(name = trimmed))
            Result.success(Unit)
        }

    suspend fun deleteFolder(folderId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val folder = folderDao.getFolder(folderId).firstOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("Folder not found"))
        folderDao.getFolderMembers(folderId).forEach { folderDao.deleteMember(it) }
        folderDao.delete(folder)
        settings.clearSlotsForFolder(folderId)
        Result.success(Unit)
    }

    suspend fun addMember(folderId: Int, app: AppInfo): Result<Unit> = withContext(Dispatchers.IO) {
        val existing = folderDao.getFolderMembers(folderId)
        if (existing.any { it.appId == app.key }) {
            return@withContext Result.failure(IllegalArgumentException("App already in folder"))
        }
        // New members land at the bottom so they never disturb a manual order.
        folderDao.insertMember(
            FolderMember(
                folderId = folderId,
                appId = app.key,
                sortOrder = (folderDao.maxMemberSortOrder(folderId) ?: -1) + 1,
            )
        )
        Result.success(Unit)
    }

    suspend fun removeMember(folderId: Int, app: AppInfo): Result<Unit> =
        withContext(Dispatchers.IO) {
            folderDao.removeMember(folderId, app.key)
            // A folder that loses its last member disappears; slots clear too.
            if (folderDao.getFolderMembers(folderId).isEmpty()) {
                deleteFolder(folderId)
            }
            Result.success(Unit)
        }

    /** Swaps a member with its neighbour. Fails at the ends of the list. */
    suspend fun moveMember(folderId: Int, appKey: String, up: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val moved = moveInList(getMembers(folderId).map { it.key }, appKey, up)
                ?: return@withContext Result.failure(IllegalStateException("Cannot move"))
            persistMemberOrder(folderId, moved)
            Result.success(Unit)
        }

    /** Resets a folder to alphabetical order by displayed label. */
    suspend fun sortMembersAlphabetically(folderId: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            val ordered = getMembers(folderId)
                .sortedBy { it.label.lowercase() }
                .map { it.key }
            persistMemberOrder(folderId, ordered)
            Result.success(Unit)
        }

    private suspend fun persistMemberOrder(folderId: Int, orderedKeys: List<String>) {
        val rows = folderDao.getFolderMembers(folderId).associateBy { it.appId }
        val updates = orderedKeys.mapIndexedNotNull { index, key ->
            rows[key]?.takeIf { it.sortOrder != index }?.copy(sortOrder = index)
        }
        if (updates.isNotEmpty()) folderDao.updateMembers(updates)
    }

    /** All member keys ("package|userToken") across every folder. */
    suspend fun getAllMemberKeys(): Set<String> = withContext(Dispatchers.IO) {
        db.folderDao().getAllMembers().mapTo(mutableSetOf()) { it.appId }
    }

    suspend fun moveFolder(folderId: Int, up: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val folders = folderDao.getAllFolders().firstOrNull() ?: emptyList()
        val current = folders.firstOrNull { it.id == folderId }
            ?: return@withContext Result.failure(IllegalStateException("Cannot move"))
        val moved = moveInList(folders, current, up)
            ?: return@withContext Result.failure(IllegalStateException("Cannot move"))
        moved.forEachIndexed { i, folder -> folderDao.update(folder.copy(sortOrder = i)) }
        Result.success(Unit)
    }
}

/**
 * Swaps [item] with the neighbour above or below it, returning the new order —
 * or null when [item] is missing or already at that end of the list.
 */
internal fun <T> moveInList(items: List<T>, item: T, up: Boolean): List<T>? {
    val index = items.indexOf(item)
    val target = if (up) index - 1 else index + 1
    if (index < 0 || target < 0 || target >= items.size) return null
    return items.toMutableList().apply {
        this[index] = this[target]
        this[target] = item
    }
}
