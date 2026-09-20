package com.piercingxx.xxlauncher

import com.piercingxx.xxlauncher.data.movedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ListOpsTest {

    private val apps = listOf("files", "calculator", "clock", "settings")

    @Test
    fun draggingDownShiftsTheRowsBetweenUp() {
        assertEquals(
            listOf("calculator", "clock", "files", "settings"),
            apps.movedItem(0, 2),
        )
    }

    @Test
    fun draggingUpShiftsTheRowsBetweenDown() {
        assertEquals(
            listOf("settings", "files", "calculator", "clock"),
            apps.movedItem(3, 0),
        )
    }

    @Test
    fun adjacentMoveIsASwap() {
        assertEquals(
            listOf("files", "clock", "calculator", "settings"),
            apps.movedItem(1, 2),
        )
    }

    @Test
    fun noOpMovesReturnTheSameList() {
        assertSame(apps, apps.movedItem(1, 1))
        assertSame(apps, apps.movedItem(-1, 1))
        assertSame(apps, apps.movedItem(1, 4))
        val empty = emptyList<String>()
        assertSame(empty, empty.movedItem(0, 0))
    }
}
