package com.piercingxx.xxlauncher

import com.piercingxx.xxlauncher.theme.ThemeBroadcaster
import com.piercingxx.xxlauncher.theme.ThemeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sender side of the theme sync contract: the key -> display-name mapping
 * (BRAND-GUIDE §3.3, mirrored by Nope-Mode's `BackgroundTheme.LABELS` and
 * matched case-insensitively by the family receivers) and the per-package
 * payload fan-out. Intent assembly itself is a thin Android shim over
 * [ThemeBroadcaster.payloads], which plain JUnit can't drive.
 */
class ThemeBroadcasterTest {

    @Test
    fun `every internal preset key maps to its brand display name`() {
        assertEquals("AMOLED Night", ThemeBroadcaster.displayName("amoled"))
        assertEquals("Graphite", ThemeBroadcaster.displayName("graphite"))
        assertEquals("Forest Night", ThemeBroadcaster.displayName("forest"))
        assertEquals("Ocean Drift", ThemeBroadcaster.displayName("ocean"))
        assertEquals("Burgundy", ThemeBroadcaster.displayName("burgundy"))
        assertEquals("Paper", ThemeBroadcaster.displayName("paper"))
        assertEquals("Mist", ThemeBroadcaster.displayName("mist"))
    }

    @Test
    fun `custom and unknown keys map to Custom`() {
        assertEquals("Custom", ThemeBroadcaster.displayName("custom"))
        assertEquals("Custom", ThemeBroadcaster.displayName("no-such-preset"))
    }

    @Test
    fun `contract constants match the family receivers`() {
        // Txxt's ThemeSyncReceiver hard-codes these; they must never drift.
        assertEquals("xx.launcher.THEME_CHANGED", ThemeBroadcaster.ACTION_THEME_CHANGED)
        assertEquals("xx.launcher.extra.THEME_NAME", ThemeBroadcaster.EXTRA_THEME_NAME)
        assertEquals("xx.launcher.extra.BACKGROUND", ThemeBroadcaster.EXTRA_BACKGROUND)
    }

    @Test
    fun `payloads fan out one explicit delivery per family package`() {
        val colors = ThemeColors(0xFF10261B.toInt(), 0xFFFFFFFF.toInt())
        val payloads = ThemeBroadcaster.payloads("forest", colors)

        assertEquals(ThemeBroadcaster.FAMILY_PACKAGES, payloads.map { it.targetPackage })
        assertTrue(payloads.all { it.themeName == "Forest Night" })
        assertTrue(payloads.all { it.backgroundColor == 0xFF10261B.toInt() })
    }

    @Test
    fun `custom payloads carry the resolved background so receivers can honor Custom`() {
        val custom = 0xFF123456.toInt()
        val payloads = ThemeBroadcaster.payloads("custom", ThemeColors(custom, 0xFFFFFFFF.toInt()))

        assertTrue(payloads.all { it.themeName == "Custom" })
        assertTrue(payloads.all { it.backgroundColor == custom })
    }

    @Test
    fun `family package list covers every receiver app exactly once`() {
        val expected = listOf(
            "com.piercingxx.txxt",
            "com.piercingxx.nopemode",
            "com.piercingxx.calendar",
            "com.piercingxx.xxclock",
            "com.piercingxx.xxnote",
            "com.piercingxx.xxphone",
            "com.piercingxx.vitals",
            "com.piercingxx.xxdrive",
        )
        assertEquals(expected, ThemeBroadcaster.FAMILY_PACKAGES)
        assertEquals(expected.size, ThemeBroadcaster.FAMILY_PACKAGES.distinct().size)
    }
}
