package com.piercingxx.xxlauncher

import com.piercingxx.xxlauncher.folder.moveInList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoveInListTest {

    private val apps = listOf("files", "calculator", "clock", "settings")

    @Test
    fun movingUpSwapsWithThePreviousItem() {
        assertEquals(
            listOf("files", "clock", "calculator", "settings"),
            moveInList(apps, "clock", up = true),
        )
    }

    @Test
    fun movingDownSwapsWithTheNextItem() {
        assertEquals(
            listOf("calculator", "files", "clock", "settings"),
            moveInList(apps, "files", up = false),
        )
    }

    @Test
    fun movingPastEitherEndFails() {
        assertNull(moveInList(apps, "files", up = true))
        assertNull(moveInList(apps, "settings", up = false))
    }

    @Test
    fun movingSomethingNotInTheListFails() {
        assertNull(moveInList(apps, "camera", up = true))
        assertNull(moveInList(emptyList<String>(), "camera", up = false))
    }

    @Test
    fun theOriginalListIsNotMutated() {
        val original = apps.toList()
        moveInList(apps, "clock", up = true)
        assertEquals(original, apps)
    }
}
