package com.piercingxx.xxlauncher.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeLiftTest {

    @Test
    fun imeWinsOverNav() {
        assertEquals(400, ImeLift.bottomInset(400, 48))
        assertEquals(48, ImeLift.bottomInset(0, 48))
        assertEquals(0, ImeLift.bottomInset(0, 0))
    }

    @Test
    fun drawerPadsRootForIme() {
        val activity = File("src/main/java/com/piercingxx/xxlauncher/AppDrawerActivity.kt").readText()
        assertTrue(activity.contains("ImeLift.attach"))
        assertTrue(activity.contains("WindowCompat.setDecorFitsSystemWindows"))
    }

    @Test
    fun searchIsBelowTheAppList() {
        val layout = File("src/main/res/layout/activity_app_drawer.xml").readText()
        assertTrue(layout.indexOf("@+id/appScrollView") < layout.indexOf("@+id/searchEditText"))
    }
}
