package com.piercingxx.xxlauncher.backup

import com.piercingxx.suite.backup.BackupContents
import com.piercingxx.suite.backup.SuiteBackupProvider
import com.piercingxx.xxlauncher.LauncherApplication
import java.io.InputStream

/**
 * xx-launcher's door in the suite backup contract
 * (xx-apps/docs/SUITE-BACKUP-PROVIDER.md). The snapshot is the vendored
 * defaults — every SharedPreferences xml and every database, which
 * includes `launcher.db`, the folder store — plus the existing JSON
 * backup as `export/layout.json`. Restore hands that export straight to
 * the same [BackupManager] the manual Import button uses, then the base
 * class ends the process so the next launch reads the restored state.
 */
class LauncherBackupProvider : SuiteBackupProvider() {

    override val appName: String get() = "xx-launcher"

    private val backupManager by lazy {
        val app = LauncherApplication.from(context!!)
        BackupManager(app.settings, app.folders)
    }

    override fun contents(): BackupContents {
        val defaults = snapshot.defaultContents()
        return BackupContents(
            prefs = defaults.prefs,
            databases = defaults.databases,
            files = emptyList(),
            exports = mapOf(LAYOUT_EXPORT to { out -> backupManager.exportToStream(out) }),
        )
    }

    override fun applyExport(name: String, body: InputStream) {
        if (name == LAYOUT_EXPORT) {
            backupManager.importFromStream(body).getOrThrow()
        }
    }

    private companion object {
        const val LAYOUT_EXPORT = "layout.json"
    }
}
