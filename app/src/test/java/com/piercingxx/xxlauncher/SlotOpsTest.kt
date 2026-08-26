package com.piercingxx.xxlauncher

import com.piercingxx.xxlauncher.data.SlotEntry
import com.piercingxx.xxlauncher.data.SlotOps
import org.junit.Assert.assertEquals
import org.junit.Test

class SlotOpsTest {

    private fun labels(vararg names: String) = names.map { name ->
        if (name.isEmpty()) SlotEntry() else SlotEntry(label = name, packageName = name)
    }

    @Test
    fun `removing a filled slot closes the gap`() {
        val next = SlotOps.removeAt(labels("phone", "messages", "camera"), 2)
        assertEquals(listOf("phone", "camera"), next.map { it.label })
    }

    @Test
    fun `removing the last slot shrinks the list`() {
        val next = SlotOps.removeAt(labels("phone", "messages", ""), 3)
        assertEquals(listOf("phone", "messages"), next.map { it.label })
    }

    @Test
    fun `removing the only slot leaves an empty home`() {
        assertEquals(emptyList<SlotEntry>(), SlotOps.removeAt(labels("phone"), 1))
    }

    @Test
    fun `out of range indexes are a no-op`() {
        val slots = labels("phone", "messages")
        assertEquals(slots, SlotOps.removeAt(slots, 0))
        assertEquals(slots, SlotOps.removeAt(slots, 3))
    }
}
