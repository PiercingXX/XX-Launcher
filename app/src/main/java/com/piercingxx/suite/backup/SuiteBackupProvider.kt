// Canonical copy: xx-apps/app/src/main/java/com/piercingxx/suite/backup/.
// Suite apps vendor this file unchanged and subclass it; the release guard
// checks the hash. Contract: xx-apps/docs/SUITE-BACKUP-PROVIDER.md.
package com.piercingxx.suite.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * The backup door every suite app exposes to xx-apps.
 *
 * Manifest (each app):
 *
 *     <provider
 *         android:name=".backup.SuiteBackupProvider"
 *         android:authorities="${applicationId}.suite.backup"
 *         android:exported="true"
 *         android:permission="com.piercingxx.suite.permission.BACKUP" />
 *
 * xx-apps declares the permission at `signature` level. On top of that,
 * every call verifies the caller is xx-apps signed with our key.
 *
 * Calls: `describe`, `snapshot`, `restore_begin`, `restore_commit`.
 * Subclasses override [contents], [applyExport], and [afterRestore].
 */
open class SuiteBackupProvider : ContentProvider() {

    /** The catalog app name that becomes the `.xx-config/<app>/` directory. */
    open val appName: String get() = context!!.packageName.substringAfterLast('.')

    /** Snapshot schema this app writes and understands. Bump on incompatible change. */
    open val schema: Int = 1

    protected val snapshot: Snapshot by lazy { Snapshot(File(context!!.applicationInfo.dataDir)) }

    /** What to put in the archive. Default: every pref and database. */
    open fun contents(): BackupContents = snapshot.defaultContents()

    /** Hand back an `export/<name>` entry on restore. Default: ignore. */
    open fun applyExport(name: String, body: InputStream) {}

    /** Runs after files are swapped in, before the process ends. */
    open fun afterRestore() {}

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        requireSuiteCaller()
        return when (method) {
            "describe" -> describe()
            "snapshot" -> buildSnapshot()
            "restore_begin" -> restoreBegin()
            "restore_commit" -> restoreCommit()
            else -> throw IllegalArgumentException("unknown method $method")
        }
    }

    private fun describe(): Bundle {
        val c = contents()
        val bytes = (c.prefs + c.databases + c.files).sumOf { it.length() }
        val names = buildList {
            if (c.prefs.isNotEmpty()) add("prefs")
            if (c.databases.isNotEmpty()) add("db")
            if (c.files.isNotEmpty()) add("files")
            c.exports.keys.sorted().forEach { add("export/$it") }
        }
        return Bundle().apply {
            putString("app", appName)
            putString("package", context!!.packageName)
            putInt("schema", schema)
            putLong("version_code", versionCode())
            putString("version_name", versionName())
            putStringArrayList("contents", ArrayList(names))
            putLong("estimated_bytes", bytes)
        }
    }

    private fun buildSnapshot(): Bundle {
        val token = System.nanoTime().toString(36)
        val target = File(cacheDir(), "snapshot-$token.tar.gz")
        val meta = JSONObject()
            .put("schema", schema)
            .put("app", appName)
            .put("package", context!!.packageName)
            .put("version_code", versionCode())
            .put("version_name", versionName())
            .put("created_at", System.currentTimeMillis())
            .toString()
        val sha = snapshot.build(contents(), meta, target)
        return Bundle().apply {
            putString("uri", "content://${authority()}/snapshot/$token")
            putLong("bytes", target.length())
            putString("plaintext_sha256", sha)
        }
    }

    private fun restoreBegin(): Bundle {
        val token = System.nanoTime().toString(36)
        File(cacheDir(), "restore-$token.tar.gz").delete()
        return Bundle().apply { putString("uri", "content://${authority()}/restore/$token") }
    }

    private fun restoreCommit(): Bundle {
        val staged = cacheDir().listFiles()?.filter { it.name.startsWith("restore-") }?.maxByOrNull { it.lastModified() }
            ?: return failure("nothing staged")
        return try {
            val meta = snapshot.apply(staged) { name, body -> applyExport(name, body) }
            val theirSchema = runCatching { JSONObject(meta).optInt("schema", 1) }.getOrDefault(1)
            if (theirSchema > schema) return failure("schema")
            afterRestore()
            staged.delete()
            // Let the reply reach xx-apps, then start clean on the restored files.
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 400)
            Bundle().apply { putBoolean("ok", true) }
        } catch (e: Exception) {
            staged.delete()
            failure(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        requireSuiteCaller()
        val segments = uri.pathSegments
        require(segments.size == 2) { "bad uri" }
        val (kind, token) = segments
        require(token.matches(Regex("[a-z0-9]+"))) { "bad token" }
        return when (kind) {
            "snapshot" -> {
                val f = File(cacheDir(), "snapshot-$token.tar.gz")
                require(f.isFile) { "no such snapshot" }
                // Delete-on-close: the file goes away once xx-apps has read it.
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).also { f.deleteOnExit() }
            }
            "restore" -> {
                val f = File(cacheDir(), "restore-$token.tar.gz")
                ParcelFileDescriptor.open(
                    f,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
                )
            }
            else -> throw IllegalArgumentException("bad uri")
        }
    }

    /** Only xx-apps, and only when it carries our signing certificate. */
    protected fun requireSuiteCaller() {
        val ctx = context ?: throw SecurityException("no context")
        val caller = callingPackage ?: throw SecurityException("no calling package")
        if (caller != SUITE_STORE_PACKAGE) throw SecurityException("caller $caller is not the suite store")
        val pm = ctx.packageManager
        val callerUid = Binder.getCallingUid()
        val named = pm.getPackagesForUid(callerUid)?.toList() ?: emptyList()
        if (SUITE_STORE_PACKAGE !in named) throw SecurityException("uid does not own $SUITE_STORE_PACKAGE")
        if (pm.checkSignatures(callerUid, Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("caller signature does not match")
        }
    }

    private fun cacheDir(): File = File(context!!.cacheDir, "suite-backup").apply { mkdirs() }
    private fun authority(): String = context!!.packageName + ".suite.backup"
    private fun versionCode(): Long = runCatching {
        val info = context!!.packageManager.getPackageInfo(context!!.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)
    private fun versionName(): String = runCatching {
        context!!.packageManager.getPackageInfo(context!!.packageName, 0).versionName ?: ""
    }.getOrDefault("")
    private fun failure(why: String) = Bundle().apply { putBoolean("ok", false); putString("error", why) }

    // The provider is call()/openFile() only.
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/gzip"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        const val SUITE_STORE_PACKAGE = "com.piercingxx.apps"
        const val PERMISSION = "com.piercingxx.suite.permission.BACKUP"
    }
}
