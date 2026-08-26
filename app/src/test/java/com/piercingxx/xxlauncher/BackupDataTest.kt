package com.piercingxx.xxlauncher

import com.google.gson.Gson
import com.piercingxx.xxlauncher.backup.BackupData
import com.piercingxx.xxlauncher.backup.BackupFolder
import com.piercingxx.xxlauncher.backup.BackupSlot
import com.piercingxx.xxlauncher.backup.parseBackupJson
import com.piercingxx.xxlauncher.backup.parseMuteEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDataTest {

    private val gson = Gson()

    @Test
    fun aSparseV1FileNormalizesEveryCollection() {
        val data = parseBackupJson(
            gson,
            """{"version":1,"slotCount":4,"textSizeScale":1.0}""",
        ).getOrThrow()

        assertEquals(emptyList<String>(), data.widgetsEnabled)
        assertEquals(emptyMap<String, String>(), data.widgetTapActions)
        assertEquals(emptyList<String>(), data.hiddenApps)
        assertEquals(emptyList<String>(), data.pinnedApps)
        assertEquals(emptyMap<String, String>(), data.renameLabels)
        assertEquals(emptyList<BackupSlot>(), data.slots)
        assertEquals(emptyList<BackupFolder>(), data.folders)
        assertEquals(emptyList<String>(), data.mutedApps)
        data.folders!!.forEach { error("folders should be empty") }
        data.widgetTapActions!!.forEach { error("taps should be empty") }
    }

    @Test
    fun mutedAppsRoundTripThroughJson() {
        val original = BackupData(
            version = 1,
            slotCount = 4,
            textSizeScale = 1f,
            mutedApps = listOf("com.example.mail|1700000000000"),
        )
        val parsed = parseBackupJson(gson, gson.toJson(original)).getOrThrow()
        assertEquals(
            mapOf("com.example.mail" to 1700000000000L),
            parseMuteEntries(parsed.mutedApps.orEmpty()),
        )
    }

    @Test
    fun restorePayloadDoesNotCarryLeftoverRenamesOrFolders() {
        val file = BackupData(
            version = 1,
            slotCount = 4,
            textSizeScale = 1f,
            renameLabels = mapOf("com.keep|personal" to "Notes"),
            folders = listOf(BackupFolder("Tools", listOf("com.calc|personal"))),
            mutedApps = emptyList(),
        )
        val parsed = parseBackupJson(gson, gson.toJson(file)).getOrThrow()

        assertEquals(setOf("com.keep|personal"), parsed.renameLabels!!.keys)
        assertEquals(listOf("Tools"), parsed.folders!!.map { it.name })
        assertTrue(parsed.mutedApps!!.isEmpty())
    }

    @Test
    fun invalidJsonIsRejected() {
        val result = parseBackupJson(gson, "{not json")
        assertTrue(result.isFailure)
    }
}
