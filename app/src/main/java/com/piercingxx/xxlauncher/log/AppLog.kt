package com.piercingxx.xxlauncher.log

import android.content.Context
import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Process-wide logger. Writes logcat, a rotating file under filesDir/logs,
 * and a ring Settings can copy/share after a crash.
 *
 * On-phone only — no network. Callers must not put message bodies, full
 * phone numbers, keystrokes, email bodies, or attachment bytes in [message].
 */
object AppLog {

    enum class Level { D, I, W, E }

    data class Line(
        val ts: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val trace: String? = null,
    )

    const val RING_SIZE = 300
    const val FILE_CAP_BYTES = 256 * 1024
    const val LOG_NAME = "launcher.log"
    const val PREV_NAME = "launcher.log.1"
    const val CRASH_NAME = "last-crash.txt"

    @Volatile
    private var dir: File? = null

    @Volatile
    private var logcat: ((Level, String, String, Throwable?) -> Unit)? = null

    private val ring = ArrayDeque<Line>(RING_SIZE)

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "launch-log").apply { isDaemon = true }
    }

    private val clockFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        init(File(context.applicationContext.filesDir, "logs")) { level, tag, msg, t ->
            val name = "Launch.$tag"
            when (level) {
                Level.E -> if (t != null) Log.e(name, msg, t) else Log.e(name, msg)
                Level.W -> if (t != null) Log.w(name, msg, t) else Log.w(name, msg)
                Level.I -> Log.i(name, msg)
                Level.D -> Log.d(name, msg)
            }
        }
    }

    fun init(
        dir: File,
        logcat: ((Level, String, String, Throwable?) -> Unit)? = null,
    ) {
        if (this.dir != null) return
        dir.mkdirs()
        this.dir = dir
        this.logcat = logcat
        i("log", "dir ${dir.absolutePath}")
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            persistCrash(error)
            if (previous != null) {
                previous.uncaughtException(Thread.currentThread(), error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun d(tag: String, message: String) = emit(Level.D, tag, message, null)

    fun i(tag: String, message: String) = emit(Level.I, tag, message, null)

    fun w(tag: String, message: String, t: Throwable? = null) =
        emit(Level.W, tag, message, t)

    fun e(tag: String, message: String, t: Throwable? = null) =
        emit(Level.E, tag, message, t)

    fun persistCrash(t: Throwable) {
        val header = Line(
            ts = System.currentTimeMillis(),
            level = Level.E,
            tag = "crash",
            message = redact(t.toString()),
            trace = t.stackTraceToString(),
        )
        pushRing(header)
        logcat?.invoke(Level.E, "crash", header.message, t)
        val text = buildString {
            appendLine(format(header))
            header.trace?.let { appendLine(it) }
            appendLine()
            appendLine("--- recent ---")
            synchronized(ring) {
                ring.forEach { appendLine(format(it)) }
            }
        }
        runCatching { crashFile()?.writeText(text) }
        runCatching { appendFile(header) }
    }

    fun dump(): String = synchronized(ring) {
        buildString {
            ring.forEach { line ->
                appendLine(format(line))
                line.trace?.let { appendLine(it) }
            }
        }.trimEnd()
    }

    fun shareText(): String = buildString {
        lastCrash()?.let {
            appendLine("--- last crash ---")
            appendLine(it)
            appendLine()
        }
        appendLine("--- live ---")
        append(dump())
    }

    fun lastCrash(): String? {
        val f = crashFile() ?: return null
        if (!f.isFile || f.length() == 0L) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun logFile(): File? = dir?.let { File(it, LOG_NAME) }

    fun crashFile(): File? = dir?.let { File(it, CRASH_NAME) }

    internal fun resetForTest() {
        synchronized(ring) {
            ring.clear()
        }
        dir = null
        logcat = null
    }


    fun installFieldDiagnostics(app: Application) {
        installCrashHandler()
        val pkg = app.packageName
        val info = runCatching { app.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val code = info?.versionCode ?: 0
        i("diag", "start sdk=${Build.VERSION.SDK_INT} pkg=$pkg v=${info?.versionName ?: "?"} code=$code")
        i("diag", "device=${Build.MANUFACTURER} ${Build.MODEL}")
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                i("life", "create ${a.javaClass.simpleName}")
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {
                i("life", "resume ${a.javaClass.simpleName}")
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {
                i("life", "destroy ${a.javaClass.simpleName}")
            }
        })
    }

    internal fun redact(message: String): String {
        var s = message
        s = Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE).replace(s, "Bearer ***")
        s = Regex("(?i)(password|passwd|secret|token|otp|pin)\\s*[=:]\\s*\\S+").replace(s, "$1=***")
        s = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[A-Za-z]{2,}").replace(s, "***@***")
        s = Regex("otpauth://\\S+", RegexOption.IGNORE_CASE).replace(s, "otpauth://***")
        return s
    }

    private fun emit(level: Level, tag: String, message: String, t: Throwable?) {
        val line = Line(
            ts = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = redact(message),
            trace = t?.stackTraceToString(),
        )
        pushRing(line)
        logcat?.invoke(level, tag, line.message, t)
        writer.execute {
            runCatching { appendFile(line) }
        }
    }

    private fun pushRing(line: Line) {
        synchronized(ring) {
            while (ring.size >= RING_SIZE) ring.removeFirst()
            ring.addLast(line)
        }
    }

    private fun appendFile(line: Line) {
        val folder = dir ?: return
        folder.mkdirs()
        val file = File(folder, LOG_NAME)
        if (file.isFile && file.length() > FILE_CAP_BYTES) {
            val prev = File(folder, PREV_NAME)
            if (prev.exists()) prev.delete()
            file.renameTo(prev)
        }
        FileOutputStream(file, true).bufferedWriter().use { out ->
            out.appendLine(format(line))
            line.trace?.let { out.appendLine(it) }
        }
    }

    private fun format(line: Line): String {
        val when_ = synchronized(clockFmt) { clockFmt.format(Date(line.ts)) }
        return "$when_ ${line.level} ${line.tag} ${line.message}"
    }
}
