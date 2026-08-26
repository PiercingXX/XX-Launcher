package com.piercingxx.xxlauncher.data

/**
 * Pure home-slot list edits. SharedPreferences I/O stays in [SettingsRepository];
 * this is the shape change so "Clear slot" can drop a row instead of leaving
 * an empty "· · ·" placeholder.
 */
object SlotOps {

    /**
     * Removes the 1-based [slot] and closes the gap. Out-of-range indexes
     * leave the list unchanged.
     */
    fun removeAt(entries: List<SlotEntry>, slot: Int): List<SlotEntry> {
        if (slot !in 1..entries.size) return entries
        return entries.filterIndexed { index, _ -> index != slot - 1 }
    }
}
