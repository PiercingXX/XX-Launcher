package com.piercingxx.xxlauncher.data

/**
 * Pure label-propagation rules for app renames. A rename is stored once in
 * the rename-label map (keyed like [AppInfo.key]), but each home [SlotEntry]
 * carries its own label copy from pick time — these helpers keep those copies
 * in agreement with the map so home, drawer, search, and folders all show the
 * same name. A blank/whitespace rename clears the override and restores the
 * app's real label (the rule the drawer rename has always followed).
 */
object RenamePropagator {

    /**
     * Rename-map key for the app a slot holds ("package|userToken", or
     * "package|shortcutId|userToken" for pinned shortcuts), matching
     * [AppInfo.key]; null for folders and empty slots.
     */
    fun renameKey(entry: SlotEntry): String? {
        if (entry.isFolder || entry.packageName.isBlank()) return null
        return if (entry.shortcutId.isNotBlank()) {
            "${entry.packageName}|${entry.shortcutId}|${entry.userToken}"
        } else {
            "${entry.packageName}|${entry.userToken}"
        }
    }

    /** The label a rename should display: blank falls back to the real label. */
    fun displayLabel(rename: String, originalLabel: String): String =
        rename.trim().ifBlank { originalLabel }

    /**
     * True when two reads of the same slot still point at the same thing —
     * same folder, or same app/shortcut in the same profile — ignoring the
     * label, which is the only field a rename is allowed to touch.
     *
     * A slot label refresh reads the slot, then suspends on a slow name
     * lookup, so anything that lands on that slot meanwhile (a rename, a
     * move, a clear, a replacement) is invisible to it. Writing the pre-lookup
     * copy back would restore the slot's earlier occupant on top of whatever
     * moved in, which shuffles the home screen; callers gate the write on this.
     */
    fun holdsSameItem(before: SlotEntry, after: SlotEntry): Boolean =
        before.copy(label = "") == after.copy(label = "")

    /**
     * The slot rewrites needed so every slot holding [appKey] shows [label];
     * keys are slot indices, and already-correct slots are left out. A blank
     * [label] rewrites nothing — with no real name to restore, the stored
     * copies stand until the home screen's own label refresh resolves them.
     */
    fun propagate(
        slots: Map<Int, SlotEntry>,
        appKey: String,
        label: String,
    ): Map<Int, SlotEntry> {
        if (label.isBlank()) return emptyMap()
        return slots
            .filterValues { renameKey(it) == appKey && it.label != label }
            .mapValues { (_, entry) -> entry.copy(label = label) }
    }
}
