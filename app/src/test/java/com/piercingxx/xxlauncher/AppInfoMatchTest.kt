package com.piercingxx.xxlauncher

import com.piercingxx.xxlauncher.data.AppInfo
import com.piercingxx.xxlauncher.util.USER_PERSONAL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInfoMatchTest {

    private fun app(label: String) = AppInfo(
        packageName = "com.example",
        activityClassName = null,
        label = label,
        userToken = USER_PERSONAL,
        isSystem = false,
        installedAt = 0L,
        sizeBytes = 0L,
    )

    @Test
    fun `substring match is case-insensitive`() {
        assertTrue(app("Calendar").matches("cal"))
        assertTrue(app("calendar").matches("CAL"))
    }

    @Test
    fun `separator characters are ignored`() {
        assertTrue(app("E-Ink Reader").matches("eink"))
        assertTrue(app("My_Notes App").matches("mynotes"))
    }

    @Test
    fun `diacritics are stripped`() {
        assertTrue(app("Über").matches("uber"))
    }

    @Test
    fun `non-matching query fails`() {
        assertFalse(app("Calendar").matches("xyz"))
    }

    @Test
    fun `blank query matches everything`() {
        assertTrue(app("Anything").matches(""))
    }

    @Test
    fun `renamed label is what search matches`() {
        val renamed = app("Calendar").copy(label = "Schedule")
        assertTrue(renamed.matches("sched"))
    }
}
