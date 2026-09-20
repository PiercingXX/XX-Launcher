package com.piercingxx.xxlauncher.backup

import com.piercingxx.suite.backup.BackupContents
import com.piercingxx.suite.backup.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Round-trips a snapshot the shape [LauncherBackupProvider] builds:
 * default prefs and databases (which picks up `launcher.db`, the folder
 * store) plus `export/layout.json`. Exercises [Snapshot] directly, as
 * BK-1 asks — the provider itself needs a live Android context that this
 * module's plain JUnit tests don't have.
 */
class LauncherBackupProviderTest {

    @Test
    fun `snapshot captures prefs the folder db and layout json, then restores them`() {
        val dataDir = Files.createTempDirectory("launcher-data").toFile()
        File(dataDir, "shared_prefs").mkdirs()
        File(dataDir, "databases").mkdirs()
        File(dataDir, "shared_prefs/launcher_prefs.xml")
            .writeText("<map><string name=\"theme_preset\">amoled</string></map>")
        File(dataDir, "databases/launcher.db").writeBytes(ByteArray(2048) { 7 })

        val snap = Snapshot(dataDir)
        val defaults = snap.defaultContents()
        assertEquals(listOf("launcher_prefs.xml"), defaults.prefs.map { it.name })
        assertEquals(listOf("launcher.db"), defaults.databases.map { it.name })

        val layoutJson = """{"version":1,"slotCount":4,"textSizeScale":1.0,"themePreset":"amoled"}"""
        val contents = BackupContents(
            prefs = defaults.prefs,
            databases = defaults.databases,
            files = emptyList(),
            exports = mapOf("layout.json" to { out -> out.write(layoutJson.toByteArray()) }),
        )
        val archive = File(dataDir.parentFile, "launcher-snap.tar.gz")
        val sha = snap.build(contents, "{\"schema\":1}", archive)
        assertEquals(64, sha.length)
        assertTrue(archive.length() > 0)

        // Wreck the data dir like a fresh phone before restoring.
        File(dataDir, "shared_prefs/launcher_prefs.xml").writeText("<map/>")
        File(dataDir, "shared_prefs/leftover.xml").writeText("<map/>")
        File(dataDir, "databases/launcher.db").delete()

        var handedExportName: String? = null
        var handedExportBody: String? = null
        val meta = snap.apply(archive) { name, body ->
            handedExportName = name
            handedExportBody = String(body.readBytes())
        }

        assertEquals("{\"schema\":1}", meta)
        assertTrue(File(dataDir, "shared_prefs/launcher_prefs.xml").readText().contains("amoled"))
        assertTrue(
            "prefs are replaced wholesale, not merged",
            !File(dataDir, "shared_prefs/leftover.xml").exists(),
        )
        assertEquals(2048, File(dataDir, "databases/launcher.db").length().toInt())
        assertEquals("layout.json", handedExportName)
        assertEquals(layoutJson, handedExportBody)
    }
}
