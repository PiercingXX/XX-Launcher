package com.piercingxx.xxlauncher.theme

import android.content.Context
import android.content.Intent

/**
 * SENDER side of the family-wide theme sync contract.
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
 * so one explicit copy is sent per family package via [Intent.setPackage].
 *
 * The payload fan-out ([payloads]) is pure Kotlin so plain JUnit can verify
 * the mapping and per-package delivery without Robolectric; only [broadcast]
 * touches the Android platform.
 */
object ThemeBroadcaster {

    const val ACTION_THEME_CHANGED = "xx.launcher.THEME_CHANGED"
    const val EXTRA_THEME_NAME = "xx.launcher.extra.THEME_NAME"
    const val EXTRA_BACKGROUND = "xx.launcher.extra.BACKGROUND"

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
        "com.piercingxx.xxphone",
        "com.piercingxx.vitals",
        "com.piercingxx.xxdrive",
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

    /** The full per-package fan-out for one theme change. Pure; JVM-testable. */
    fun payloads(presetKey: String, colors: ThemeColors): List<Payload> {
        val name = displayName(presetKey)
        return FAMILY_PACKAGES.map { pkg ->
            Payload(pkg, name, colors.backgroundColor)
        }
    }

    /**
     * Sends one explicit [ACTION_THEME_CHANGED] broadcast per family package.
     * Fire-and-forget: absent packages simply drop the broadcast.
     */
    fun broadcast(context: Context, presetKey: String, colors: ThemeColors) {
        payloads(presetKey, colors).forEach { payload ->
            context.sendBroadcast(
                Intent(ACTION_THEME_CHANGED)
                    .setPackage(payload.targetPackage)
                    .putExtra(EXTRA_THEME_NAME, payload.themeName)
                    .putExtra(EXTRA_BACKGROUND, payload.backgroundColor)
            )
        }
    }
}
