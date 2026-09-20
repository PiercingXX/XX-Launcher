// Canonical copy: xx-apps/app/src/main/java/com/piercingxx/suite/backup/.
// Suite apps vendor this file unchanged; the release guard checks the hash.
package com.piercingxx.suite.backup

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * What an app puts in its snapshot. Paths are relative to the app's data
 * directory for `prefs`, `databases`, and `files`; `exports` are produced
 * by the app and land under `export/`.
 */
class BackupContents(
    /** Files under `shared_prefs/` and `files/datastore/`; default: all of them. */
    val prefs: List<File>,
    /** SQLite databases under `databases/`; default: all `.db` (and extension-less) files, WAL folded in first. */
    val databases: List<File>,
    /** App-private files worth keeping, relative to `files/`; default: none. */
    val files: List<File>,
    /** Named exports written by the app: `export/<name>`. */
    val exports: Map<String, (OutputStream) -> Unit>,
)

/** Layout inside the tar. */
object SnapshotLayout {
    const val PREFS = "prefs/"
    const val DB = "db/"
    const val FILES = "files/"
    const val EXPORT = "export/"
    const val META = "meta.json"
}

/**
 * Builds and applies snapshot archives (gzip tar) for one app's data
 * directory. Pure file work; the provider adds the IPC and security.
 */
class Snapshot(private val dataDir: File) {

    val sharedPrefsDir: File get() = File(dataDir, "shared_prefs")
    val databasesDir: File get() = File(dataDir, "databases")
    val filesDir: File get() = File(dataDir, "files")

    /** Sensible default: every pref file, every database, no loose files, no exports. */
    fun defaultContents(): BackupContents = BackupContents(
        prefs = listFiles(sharedPrefsDir) { it.extension == "xml" } +
            listFiles(File(filesDir, "datastore")) { true },
        databases = listFiles(databasesDir) { f ->
            !f.name.endsWith("-journal") && !f.name.endsWith("-wal") && !f.name.endsWith("-shm")
        },
        files = emptyList(),
        exports = emptyMap(),
    )

    /** Fold WAL pages into each database so the file on disk is complete. */
    fun checkpoint(databases: List<File>) {
        for (db in databases) {
            if (!db.isFile || !isSqlite(db)) continue
            runCatching {
                SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { conn ->
                    conn.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                }
            }
        }
    }

    /** Write the archive to [target]; returns the SHA-256 of the plaintext archive. */
    fun build(contents: BackupContents, meta: String, target: File): String {
        checkpoint(contents.databases)
        val digest = MessageDigest.getInstance("SHA-256")
        target.parentFile?.mkdirs()
        DigestOut(FileOutputStream(target), digest).use { raw ->
            GZIPOutputStream(raw).use { gz ->
                val tar = TarWriter(gz)
                tar.addFile(SnapshotLayout.META, meta.toByteArray())
                for (f in contents.prefs) addUnder(tar, SnapshotLayout.PREFS, sharedPrefsOrDatastoreRelative(f), f)
                for (f in contents.databases) addUnder(tar, SnapshotLayout.DB, f.name, f)
                for (f in contents.files) addUnder(tar, SnapshotLayout.FILES, f.relativeTo(filesDir).path, f)
                for ((name, writer) in contents.exports) {
                    val tmp = File.createTempFile("export", null, target.parentFile)
                    try {
                        FileOutputStream(tmp).use(writer)
                        FileInputStream(tmp).use { tar.addFile(SnapshotLayout.EXPORT + name, tmp.length(), it) }
                    } finally {
                        tmp.delete()
                    }
                }
                tar.finish()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Apply an archive: stage everything, then swap prefs, databases, and
     * files into place and hand each export to [applyExport]. Returns the
     * meta.json text. Caller ends the process afterwards.
     */
    fun apply(archive: File, applyExport: (String, InputStream) -> Unit): String {
        val staging = File(dataDir, "suite-restore-staging").apply { deleteRecursively(); mkdirs() }
        var meta = ""
        try {
            GZIPInputStream(FileInputStream(archive)).use { gz ->
                val tar = TarReader(gz)
                while (true) {
                    val entry = tar.next() ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    when {
                        name == SnapshotLayout.META -> meta = String(tar.readBytes())
                        name.startsWith(SnapshotLayout.EXPORT) -> {
                            val out = safeChild(File(staging, "export"), name.removePrefix(SnapshotLayout.EXPORT))
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { tar.body().copyTo(it) }
                        }
                        name.startsWith(SnapshotLayout.PREFS) ||
                            name.startsWith(SnapshotLayout.DB) ||
                            name.startsWith(SnapshotLayout.FILES) -> {
                            val out = safeChild(staging, name)
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { tar.body().copyTo(it) }
                        }
                        else -> tar.readBytes() // unknown: skip
                    }
                }
            }
            // Swap in. Prefs and databases replace wholesale; files merge.
            // `prefs/datastore/*` came from files/datastore and goes back there;
            // the rest of `prefs/` is shared_prefs.
            File(staging, "prefs").takeIf { it.isDirectory }?.let { stagedPrefs ->
                swapDir(File(stagedPrefs, "datastore"), File(filesDir, "datastore"), wipeTarget = true)
                sharedPrefsDir.listFiles()?.forEach { it.deleteRecursively() }
                sharedPrefsDir.mkdirs()
                stagedPrefs.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(sharedPrefsDir, it.name), overwrite = true) }
            }
            File(staging, "db").takeIf { it.isDirectory }?.let { staged ->
                databasesDir.listFiles()?.forEach { if (it.isFile) it.delete() }
                databasesDir.mkdirs()
                staged.listFiles()?.forEach { it.copyTo(File(databasesDir, it.name), overwrite = true) }
            }
            swapDir(File(staging, "files"), filesDir, wipeTarget = false)
            File(staging, "export").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.forEach { f ->
                FileInputStream(f).use { applyExport(f.relativeTo(File(staging, "export")).path, it) }
            }
        } finally {
            staging.deleteRecursively()
        }
        return meta
    }

    private fun swapDir(staged: File, target: File, wipeTarget: Boolean) {
        if (!staged.isDirectory) return
        if (wipeTarget) target.listFiles()?.forEach { it.deleteRecursively() }
        target.mkdirs()
        staged.walkTopDown().filter { it.isFile }.forEach { f ->
            val out = File(target, f.relativeTo(staged).path)
            out.parentFile?.mkdirs()
            f.copyTo(out, overwrite = true)
        }
    }

    private fun sharedPrefsOrDatastoreRelative(f: File): String =
        if (f.path.startsWith(sharedPrefsDir.path)) f.name else "datastore/" + f.name

    private fun addUnder(tar: TarWriter, prefix: String, rel: String, f: File) {
        if (!f.isFile) return
        FileInputStream(f).use { tar.addFile(prefix + rel, f.length(), it) }
    }

    /** Only a real SQLite file may be opened; anything else would be zeroed by the engine. */
    private fun isSqlite(f: File): Boolean = runCatching {
        FileInputStream(f).use { val h = ByteArray(16); it.read(h) == 16 && String(h, Charsets.ISO_8859_1).startsWith("SQLite format 3") }
    }.getOrDefault(false)

    private fun listFiles(dir: File, keep: (File) -> Boolean): List<File> =
        dir.listFiles()?.filter { it.isFile && keep(it) }?.sortedBy { it.name } ?: emptyList()

    /** Refuse entries that escape their directory. */
    private fun safeChild(base: File, rel: String): File {
        val out = File(base, rel).canonicalFile
        require(out.path.startsWith(base.canonicalPath + File.separator)) { "unsafe entry: $rel" }
        return out
    }

    private class DigestOut(private val inner: OutputStream, private val digest: MessageDigest) : OutputStream() {
        override fun write(b: Int) { inner.write(b); digest.update(b.toByte()) }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); digest.update(b, off, len) }
        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }
}
