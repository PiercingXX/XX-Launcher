package com.piercingxx.xxlauncher

import com.piercingxx.xxlauncher.util.PKG_XX_CLOCK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Clock-widget taps must prefer the family clock the same way weather
 * prefers XX-Weather. Source-pinned: Intent assembly needs a device.
 */
class OpenAlarmAppTest {

    private val source: String =
        sequenceOf(
            File("src/main/java/com/piercingxx/xxlauncher/util/SystemActions.kt"),
            File("app/src/main/java/com/piercingxx/xxlauncher/util/SystemActions.kt"),
        ).first { it.exists() }.readText()

    @Test
    fun `family clock package is the published xxclock id`() {
        assertEquals("com.piercingxx.xxclock", PKG_XX_CLOCK)
    }

    @Test
    fun `openAlarmApp targets XX Clock before the system SHOW_ALARMS chooser`() {
        val fn = source.substringAfter("fun openAlarmApp").substringBefore("fun openCalendarApp")
        assertTrue(fn.contains("PKG_XX_CLOCK") || fn.contains("com.piercingxx.xxclock"))
        assertTrue(fn.contains("ACTION_SHOW_ALARMS"))
        assertTrue(fn.contains("setPackage"))
    }
}
