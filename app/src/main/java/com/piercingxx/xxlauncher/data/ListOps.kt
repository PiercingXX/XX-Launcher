package com.piercingxx.xxlauncher.data

/**
 * Returns the list with the item at [from] lifted out and dropped at [to],
 * shifting everything between. Out-of-range or same-position moves return
 * the receiver itself.
 */
fun <T> List<T>.movedItem(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
