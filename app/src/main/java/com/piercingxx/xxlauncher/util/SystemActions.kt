package com.piercingxx.xxlauncher.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

private const val TAG = "SystemActions"

// Stable serialization tokens for user profiles. "personal" is this process's
// user; anything else is `u{serial}` from UserManager. "managed" is the
// pre-serial spelling and still resolves to the first extra profile so old
// prefs and backups keep working.
const val USER_PERSONAL = "personal"
const val USER_MANAGED = "managed"

internal fun userSerialToken(serial: Long): String = "u$serial"

fun serializeUser(user: UserHandle, userManager: UserManager? = null): String {
    if (user == Process.myUserHandle()) return USER_PERSONAL
    val serial = userManager?.getSerialNumberForUser(user) ?: return USER_MANAGED
    return if (serial >= 0L) userSerialToken(serial) else USER_MANAGED
}

fun Context.serializeUser(user: UserHandle): String =
    serializeUser(user, getSystemService(Context.USER_SERVICE) as UserManager)

fun Context.userFromToken(token: String): UserHandle {
    val userManager = getSystemService(Context.USER_SERVICE) as UserManager
    val personal = Process.myUserHandle()
    if (token.isBlank() || token == USER_PERSONAL) return personal
    if (token == USER_MANAGED) {
        return userManager.userProfiles.firstOrNull { it != personal } ?: personal
    }
    if (token.startsWith("u")) {
        val serial = token.removePrefix("u").toLongOrNull()
        if (serial != null) {
            userManager.getUserForSerialNumber(serial)?.let { return it }
        }
    }
    return personal
}

fun Context.showToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

@SuppressLint("WrongConstant", "PrivateApi")
fun expandNotificationDrawer(context: Context) {
    try {
        val statusBarService = context.getSystemService("statusbar")
        val statusBarManager = Class.forName("android.app.StatusBarManager")
        statusBarManager.getMethod("expandNotificationsPanel").invoke(statusBarService)
    } catch (e: Exception) {
        Log.w(TAG, "Unable to expand notification drawer", e)
    }
}

fun openDialerApp(context: Context, options: android.os.Bundle? = null) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL), options)
    } catch (e: Exception) {
        Log.w(TAG, "Unable to open dialer", e)
    }
}

fun openCameraApp(context: Context, options: android.os.Bundle? = null) {
    try {
        context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), options)
    } catch (e: Exception) {
        Log.w(TAG, "Unable to open camera", e)
    }
}

fun openAlarmApp(context: Context) {
    try {
        context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS))
    } catch (e: Exception) {
        Log.w(TAG, "Unable to open alarms", e)
    }
}

fun openCalendarApp(context: Context) {
    try {
        val calendarUri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()
        context.startActivity(Intent(Intent.ACTION_VIEW, calendarUri))
    } catch (e: Exception) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
            context.startActivity(intent)
        } catch (e2: Exception) {
            Log.w(TAG, "Unable to open a calendar app", e2)
        }
    }
}

// Android has no standard "open the weather app" intent, so probe the common
// stock and third-party weather packages before falling back to a web search.
private val WEATHER_PACKAGES = listOf(
    "com.google.android.apps.weather",
    "com.sec.android.daemonapp",
    "com.samsung.android.weather",
    "com.miui.weather2",
    "com.oneplus.weather",
    "com.coloros.weather2",
    "com.oppo.weather",
    "com.huawei.android.totemweather",
    "org.breezyweather",
    "org.breezyweather.debug",
    "wangdaye.com.geometricweather",
    "com.weather.Weather",
    "com.accuweather.android",
    "com.yahoo.mobile.client.android.weather",
    "com.devexpert.weather",
)

fun openWeatherApp(context: Context) {
    for (pkg in WEATHER_PACKAGES) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
        try {
            context.startActivity(intent)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open weather app $pkg", e)
        }
    }
    context.openWebSearch("weather")
}

fun Context.openWebSearch(query: String? = null) {
    try {
        val intent = Intent(Intent.ACTION_WEB_SEARCH)
        intent.putExtra(SearchManager.QUERY, query ?: "")
        startActivity(intent)
    } catch (e: Exception) {
        Log.w(TAG, "Unable to open web search", e)
    }
}

fun Context.openUrl(url: String) {
    if (url.isEmpty()) return
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Log.w(TAG, "Unable to open url", e)
    }
}

fun Context.openAppInfo(packageName: String, user: UserHandle) {
    try {
        val launcher = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val component = launcher.getActivityList(packageName, user).firstOrNull()?.componentName
        if (component != null) {
            launcher.startAppDetailsActivity(component, user, null, null)
            return
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to open app info", e)
    }
    try {
        startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        )
    } catch (e: Exception) {
        Log.w(TAG, "Unable to open app details settings", e)
    }
}

// EXTRA_USER routes the request to the right profile's copy; without it a
// work-profile uninstall would act on the personal install.
fun Context.requestUninstall(packageName: String, user: UserHandle) {
    try {
        startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                .putExtra(Intent.EXTRA_USER, user)
        )
    } catch (e: Exception) {
        Log.w(TAG, "Unable to start uninstall", e)
    }
}

// Animations are suppressed on slow-refresh (e-ink) displays.
fun Context.isEinkDisplay(): Boolean {
    return try {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val refreshRate =
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: Float.MAX_VALUE
        refreshRate <= 30f
    } catch (e: Exception) {
        false
    }
}

fun View.hideKeyboard() {
    clearFocus()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(windowToken, 0)
}

fun View.showKeyboard() {
    if (requestFocus()) {
        postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }
}

fun Activity.showStatusBar() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.show(WindowInsets.Type.statusBars())
    } else {
        @Suppress("DEPRECATION")
        window.decorView.apply {
            systemUiVisibility = (systemUiVisibility
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN) and
                View.SYSTEM_UI_FLAG_FULLSCREEN.inv()
        }
    }
}

fun Activity.hideStatusBar() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.statusBars())
        }
    } else {
        @Suppress("DEPRECATION")
        window.decorView.apply {
            systemUiVisibility = systemUiVisibility or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }
}

// 0 = 3-button, 1 = 2-button, 2 = full gesture navigation.
fun Context.isGestureNavigation(): Boolean =
    android.provider.Settings.Secure.getInt(contentResolver, "navigation_mode", 0) == 2

// The nav bar stays hidden while the launcher is foreground; an edge swipe
// still reveals it transiently. Under gesture navigation the pill is left
// alone — hiding it turns the system's swipe-up (home/recents) into a
// reveal-bars swipe, so the bottom edge keeps belonging to Android while
// swipes elsewhere on the home screen open the app drawer.
fun Activity.hideNavigationBar() {
    if (isGestureNavigation()) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.navigationBars())
        }
    } else {
        @Suppress("DEPRECATION")
        window.decorView.apply {
            systemUiVisibility = systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }
}
