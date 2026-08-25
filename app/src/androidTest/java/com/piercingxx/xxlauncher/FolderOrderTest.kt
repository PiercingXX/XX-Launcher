package com.piercingxx.xxlauncher

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.piercingxx.xxlauncher.data.AppInfo
import com.piercingxx.xxlauncher.util.serializeUser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Folder member ordering, against the real on-device database — so this also
 * covers the schema migration that added the member sort column.
 */
@RunWith(AndroidJUnit4::class)
class FolderOrderTest {

    private val app: LauncherApplication
        get() = ApplicationProvider.getApplicationContext()

    private val folderName = "ZZ Ordering Test"

    /** Three real installed apps, so folder reads resolve them instead of pruning. */
    private fun sampleApps(): List<AppInfo> {
        val context: Context = ApplicationProvider.getApplicationContext()
        val launcherApps =
            context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val user = Process.myUserHandle()
        return launcherApps.getActivityList(null, user).take(3).map { activity ->
            AppInfo(
                packageName = activity.componentName.packageName,
                activityClassName = activity.componentName.className,
                label = activity.label.toString(),
                userToken = serializeUser(user),
                isSystem = false,
                installedAt = 0L,
                sizeBytes = 0L,
            )
        }
    }

    @Test
    fun membersKeepInsertionOrderAndCanBeMoved() = runBlocking {
        val folders = app.folders
        val apps = sampleApps()
        assertTrue("needs three launchable apps on the device", apps.size == 3)

        // A leftover folder from an interrupted run would fail createFolder.
        folders.getFolders().firstOrNull { it.name == folderName }
            ?.let { folders.deleteFolder(it.id) }

        val folder = folders.createFolder(folderName).getOrThrow()
        try {
            apps.forEach { folders.addMember(folder.id, it) }

            // Added apps append rather than sorting themselves in.
            assertEquals(
                apps.map { it.key },
                folders.getMembers(folder.id).map { it.key },
            )

            // Last one moves up one place.
            folders.moveMember(folder.id, apps[2].key, up = true).getOrThrow()
            assertEquals(
                listOf(apps[0].key, apps[2].key, apps[1].key),
                folders.getMembers(folder.id).map { it.key },
            )

            // ...and back down again.
            folders.moveMember(folder.id, apps[2].key, up = false).getOrThrow()
            assertEquals(
                apps.map { it.key },
                folders.getMembers(folder.id).map { it.key },
            )

            // Moving past the end is refused, leaving the order untouched.
            assertTrue(folders.moveMember(folder.id, apps[0].key, up = true).isFailure)
            assertEquals(
                apps.map { it.key },
                folders.getMembers(folder.id).map { it.key },
            )

            folders.sortMembersAlphabetically(folder.id).getOrThrow()
            assertEquals(
                folders.getMembers(folder.id).map { it.label },
                folders.getMembers(folder.id).map { it.label }.sortedBy { it.lowercase() },
            )
        } finally {
            folders.deleteFolder(folder.id)
        }
    }
}
