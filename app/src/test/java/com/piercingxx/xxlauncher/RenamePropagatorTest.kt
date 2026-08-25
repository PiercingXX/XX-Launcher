package com.piercingxx.xxlauncher

import com.piercingxx.xxlauncher.data.RenamePropagator
import com.piercingxx.xxlauncher.data.SlotEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenamePropagatorTest {

    private val keep = SlotEntry(
        label = "Notes",
        packageName = "com.google.android.keep",
        activityClassName = "com.google.android.keep.Main",
        userToken = "personal",
    )
    private val shortcut = keep.copy(label = "New note", shortcutId = "new_note")
    private val folder = SlotEntry(label = "Tools", folderId = 3)

    @Test
    fun renameKeyMatchesTheAppKeyFormat() {
        assertEquals("com.google.android.keep|personal", RenamePropagator.renameKey(keep))
        assertEquals(
            "com.google.android.keep|new_note|personal",
            RenamePropagator.renameKey(shortcut),
        )
    }

    @Test
    fun foldersAndEmptySlotsHaveNoRenameKey() {
        assertNull(RenamePropagator.renameKey(folder))
        assertNull(RenamePropagator.renameKey(SlotEntry()))
    }

    @Test
    fun displayLabelPrefersTheRename() {
        assertEquals("Scratchpad", RenamePropagator.displayLabel("Scratchpad", "Keep Notes"))
    }

    @Test
    fun blankRenameFallsBackToTheRealLabel() {
        assertEquals("Keep Notes", RenamePropagator.displayLabel("", "Keep Notes"))
        assertEquals("Keep Notes", RenamePropagator.displayLabel("   ", "Keep Notes"))
    }

    @Test
    fun propagateRewritesEverySlotHoldingTheApp() {
        val slots = mapOf(1 to keep, 2 to folder, 3 to keep.copy(label = "Old"))
        assertEquals(
            mapOf(1 to keep.copy(label = "Scratchpad"), 3 to keep.copy(label = "Scratchpad")),
            RenamePropagator.propagate(slots, "com.google.android.keep|personal", "Scratchpad"),
        )
    }

    @Test
    fun propagateLeavesOtherAppsAndMatchingLabelsAlone() {
        val other = keep.copy(packageName = "com.other.app")
        val slots = mapOf(1 to keep.copy(label = "Scratchpad"), 2 to other, 3 to shortcut)
        assertEquals(
            emptyMap<Int, SlotEntry>(),
            RenamePropagator.propagate(slots, "com.google.android.keep|personal", "Scratchpad"),
        )
    }

    // The reported repro: two folders above two apps, rename the first app.
    private val comms = SlotEntry(label = "Comms", folderId = 1)
    private val tools = SlotEntry(label = "Tools", folderId = 2)
    private val note = SlotEntry(
        label = "XX-Note",
        packageName = "com.piercingxx.xxnote",
        activityClassName = "com.piercingxx.xxnote.Main",
        userToken = "personal",
    )
    private val calendar = SlotEntry(
        label = "XX-Calendar",
        packageName = "com.piercingxx.xxcalendar",
        activityClassName = "com.piercingxx.xxcalendar.Main",
        userToken = "personal",
    )

    /** How [com.piercingxx.xxlauncher.data.AppRepository.rename] writes slots. */
    private fun applyRename(
        slots: Map<Int, SlotEntry>,
        appKey: String,
        label: String,
    ): Map<Int, SlotEntry> {
        val after = slots.toMutableMap()
        RenamePropagator.propagate(slots, appKey, label).forEach { (slot, entry) ->
            val current = after.getValue(slot)
            if (RenamePropagator.holdsSameItem(entry, current)) {
                after[slot] = current.copy(label = entry.label)
            }
        }
        return after
    }

    @Test
    fun renamingOneAppSlotLeavesEveryOtherSlotWhereItWas() {
        val slots = mapOf(1 to comms, 2 to tools, 3 to note, 4 to calendar)

        val after = applyRename(slots, "com.piercingxx.xxnote|personal", "Notes")

        // A rename moves nothing: folders stay put, the renamed app keeps its
        // own slot, and the app below it is untouched.
        assertEquals(comms, after[1])
        assertEquals(tools, after[2])
        assertEquals(note.copy(label = "Notes"), after[3])
        assertEquals(calendar, after[4])
    }

    @Test
    fun emptySlotsStayEmptyAcrossARename() {
        val slots = mapOf(1 to note, 2 to SlotEntry(), 3 to folder, 4 to SlotEntry())

        val after = applyRename(slots, "com.piercingxx.xxnote|personal", "Notes")

        assertEquals(note.copy(label = "Notes"), after[1])
        assertEquals(SlotEntry(), after[2])
        assertEquals(folder, after[3])
        assertEquals(SlotEntry(), after[4])
    }

    @Test
    fun aStaleSlotRefreshCannotMoveAnAppOntoAnotherSlot() {
        // A slot-label refresh that snapshotted the layout before the user
        // moved XX-Note down to slot 3 resolves the fresh "Notes" for it and
        // would write the whole stale entry back into slot 1 — landing the
        // renamed app on top of the Comms folder and shunting everything else
        // along. Re-reading the slot rejects exactly those writes.
        val current = mapOf(1 to comms, 2 to tools, 3 to note.copy(label = "Notes"), 4 to calendar)
        val stale = mapOf(1 to note, 2 to comms, 3 to tools, 4 to calendar)

        val landed = stale.filter { (slot, before) ->
            RenamePropagator.holdsSameItem(before, current.getValue(slot))
        }

        assertEquals(setOf(4), landed.keys)
    }

    @Test
    fun aSlotStillHoldsTheSameItemWhenOnlyItsLabelChanged() {
        assertTrue(RenamePropagator.holdsSameItem(keep, keep.copy(label = "Scratchpad")))
        assertTrue(RenamePropagator.holdsSameItem(folder, folder.copy(label = "Utilities")))
        assertTrue(RenamePropagator.holdsSameItem(SlotEntry(), SlotEntry()))
    }

    @Test
    fun aRePointedSlotNoLongerHoldsTheSameItem() {
        // The cases a slot-label refresh can suspend across: the slot moved,
        // was replaced, was cleared, or swapped with a folder.
        assertFalse(RenamePropagator.holdsSameItem(keep, folder))
        assertFalse(RenamePropagator.holdsSameItem(keep, keep.copy(packageName = "com.other.app")))
        assertFalse(RenamePropagator.holdsSameItem(keep, keep.copy(userToken = "managed")))
        assertFalse(RenamePropagator.holdsSameItem(keep, shortcut))
        assertFalse(RenamePropagator.holdsSameItem(keep, SlotEntry()))
    }

    @Test
    fun blankLabelPropagatesNothing() {
        assertEquals(
            emptyMap<Int, SlotEntry>(),
            RenamePropagator.propagate(mapOf(1 to keep), "com.google.android.keep|personal", " "),
        )
    }
}
