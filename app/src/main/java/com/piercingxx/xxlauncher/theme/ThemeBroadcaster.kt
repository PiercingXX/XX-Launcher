package com.piercingxx.xxlauncher.theme

import android.content.Context
import android.content.Intent

/**
 * Fallback sender when xx-apps is not installed. The suite engine now
 * lives in xx-apps ([SuiteThemeClient]); this object stays so a phone
 * without the store still fans out THEME_CHANGED.
 *
 * When the launcher's effective theme changes it broadcasts
 * [ACTION_THEME_CHANGED] carrying:
 *  - [EXTRA_THEME_NAME] (String): the preset *display* name — "AMOLED Night",
 *    "Graphite", "Forest Night", "Ocean Drift", "Burgundy", "Paper", "Mist" —
 *    or "Custom" for the custom color. These names mirror Nope-Mode's
 *    `BackgroundTheme.LABELS` and BRAND-GUIDE §3.3; receivers (e.g. Txxt's
 *    `ThemeSyncReceiver`) match them case-insensitively.
 *  - [EXTRA_BACKGROUND] (Int): the resolved background ARGB color, always
 *    included — it is the only way receivers can honor "Custom".
 *
 * Manifest-declared receivers do not get implicit broadcasts since Android O,
 * so one explicit copy is sent per family package. Send is
 * [Context.sendBroadcast] plus [Intent.setPackage]; receivers gate via
 * `android:permission`. Mixed debug keys are OK.
 *
 * The payload fan-out ([payloads]) is pure Kotlin so plain JUnit can verify
 * the mapping and per-package delivery without Robolectric; only [broadcast]
 * touches the Android platform.
 */
object ThemeBroadcaster {

    const val ACTION_THEME_CHANGED = "xx.launcher.THEME_CHANGED"
    const val EXTRA_THEME_NAME = "xx.launcher.extra.THEME_NAME"
    const val EXTRA_BACKGROUND = "xx.launcher.extra.BACKGROUND"
    const val PERMISSION_THEME_SYNC = "com.piercingxx.xxlauncher.permission.THEME_SYNC"

    /** Display name broadcast when the preset key is not a built-in preset. */
    const val CUSTOM_DISPLAY_NAME = "Custom"

    /**
     * Internal preset key -> display name, per BRAND-GUIDE §3.3 (mirrors
     * Nope-Mode's `BackgroundTheme.LABELS`). Keys match
     * [ThemeManager.presets]; the two must change together.
     */
    val DISPLAY_NAMES: Map<String, String> = linkedMapOf(
        "amoled" to "AMOLED Night",
        "graphite" to "Graphite",
        "forest" to "Forest Night",
        "ocean" to "Ocean Drift",
        "burgundy" to "Burgundy",
        "paper" to "Paper",
        "mist" to "Mist",
    )

    /** Every family app that ships a `ThemeSyncReceiver`. */
    val FAMILY_PACKAGES: List<String> = listOf(
        "com.piercingxx.txxt",
        "com.piercingxx.nopemode",
        "com.piercingxx.calendar",
        "com.piercingxx.xxclock",
        "com.piercingxx.xxnote",
        "com.piercingxx.xxdialer",
        "com.piercingxx.vitals",
        "com.piercingxx.xxdrive",
        "com.piercingxx.xxcalculator",
        "com.xx.weather",
        "com.piercingxx.xxcontacts",
        "com.piercingxx.xxfiles",
        "dev.xxemail",
        "com.piercingxx.xxkeyboard",
        // Debug builds keep applicationIdSuffix ".debug"; fan out both ids.
        "com.piercingxx.xxkeyboard.debug",
        "com.piercingxx.apps",
        "com.piercingxx.camera",
        "com.piercingxx.photos",
        "com.piercingxx.xxauth",
        "com.piercingxx.audiobook",
        "com.skpp.radio",
    )

    /** One theme-changed delivery: what goes into the [Intent] for [targetPackage]. */
    data class Payload(
        val targetPackage: String,
        val themeName: String,
        val backgroundColor: Int,
    )

    /** Maps an internal preset key ("amoled", "custom", ...) to its display name. */
    fun displayName(presetKey: String): String =
        DISPLAY_NAMES[presetKey] ?: CUSTOM_DISPLAY_NAME

    /** Inverse of [displayName] for incoming suite fan-out. */
    fun presetKeyFromDisplayName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val trimmed = name.trim()
        if (CUSTOM_DISPLAY_NAME.equals(trimmed, ignoreCase = true)) return "custom"
        return DISPLAY_NAMES.entries.firstOrNull {
            it.value.equals(trimmed, ignoreCase = true)
        }?.key
    }

    /** The full per-package fan-out for one theme change. Pure; JVM-testable. */
    fun payloads(presetKey: String, colors: ThemeColors): List<Payload> {
        val name = displayName(presetKey)
        return FAMILY_PACKAGES.map { pkg ->
            Payload(pkg, name, colors.backgroundColor)
        }
    }

    /**
     * Sends one explicit [ACTION_THEME_CHANGED] broadcast per family package.
     * Not filtered by [PERMISSION_THEME_SYNC] on the *receiver*: Weather,
     * Vitals, and Nope-Mode are signed with different debug keys, so they
     * cannot hold a signature permission the launcher defines. Senders are
     * still gated by each receiver's `android:permission` (the launcher
     * holds that permission). Absent packages simply drop the broadcast.
     */
    fun broadcast(context: Context, presetKey: String, colors: ThemeColors) {
        payloads(presetKey, colors).forEach { payload ->
            context.sendBroadcast(
                Intent(ACTION_THEME_CHANGED)
                    .setPackage(payload.targetPackage)
                    .putExtra(EXTRA_THEME_NAME, payload.themeName)
                    .putExtra(EXTRA_BACKGROUND, payload.backgroundColor),
            )
        }
    }
}
