package com.piercingxx.xxlauncher.data

import com.piercingxx.xxlauncher.util.USER_PERSONAL

/**
 * Stable identity for a launchable row: a package in a profile, or a pinned
 * shortcut of that package. Encoded as `package|userToken` or
 * `package|shortcutId|userToken` — the profile is always the last segment.
 */
data class AppKey(
    val packageName: String,
    val shortcutId: String? = null,
    val userToken: String = USER_PERSONAL,
) {
    fun encoded(): String =
        if (shortcutId.isNullOrBlank()) "$packageName|$userToken"
        else "$packageName|$shortcutId|$userToken"

    companion object {
        fun parse(raw: String): AppKey {
            val parts = raw.split("|")
            val packageName = parts.first()
            val userToken = parts.getOrNull(parts.lastIndex.coerceAtLeast(1)) ?: USER_PERSONAL
            val shortcutId = if (parts.size >= 3) {
                parts.subList(1, parts.lastIndex).joinToString("|").ifBlank { null }
            } else {
                null
            }
            return AppKey(packageName, shortcutId, userToken)
        }

        fun rewriteUserToken(raw: String, from: String, to: String): String {
            val parsed = parse(raw)
            return if (parsed.userToken == from) parsed.copy(userToken = to).encoded() else raw
        }
    }
}

/**
 * Swipe targets and widget tap overrides store `pkg|activity|user[|shortcutId]`.
 * The user token is the third field when present.
 */
internal fun rewriteEmbeddedUserToken(raw: String, from: String, to: String): String {
    if (raw.isBlank()) return raw
    val parts = raw.split("|").toMutableList()
    if (parts.size >= 3 && parts[2] == from) {
        parts[2] = to
        return parts.joinToString("|")
    }
    return raw
}
