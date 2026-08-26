package com.piercingxx.xxlauncher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** One home-screen slot. Empty strings / -1 mean "unset". */
data class SlotEntry(
    val label: String = "",
    val packageName: String = "",
    val activityClassName: String = "",
    val userToken: String = "",
    val folderId: Int = -1,
    val shortcutId: String = "",
) {
    val isEmpty: Boolean get() = label.isBlank() && packageName.isBlank() && folderId < 0
    val isFolder: Boolean get() = folderId >= 0
}

class SettingsRepository(context: Context) {

    companion object {
        const val PREFS_NAME = "launcher_prefs"
        const val MAX_SLOTS = 8

        const val KEY_SLOT_COUNT = "slot_count"
        const val KEY_TEXT_ALIGNMENT = "text_alignment"
        const val KEY_DATE_TIME_MODE = "date_time_mode"
        const val KEY_STATUS_BAR_VISIBLE = "status_bar_visible"

        const val KEY_WIDGETS_ORDER = "widgets_order_csv"
        const val KEY_WEATHER_TEMP_UNIT = "weather_temp_unit"
        val ALL_WIDGETS = listOf("clock", "date", "weather", "battery")

        const val KEY_AUTO_SHOW_KEYBOARD = "auto_show_keyboard"
        const val KEY_SORT_MODE = "sort_mode"

        const val KEY_SWIPE_LEFT_APP = "swipe_left_app"
        const val KEY_SWIPE_RIGHT_APP = "swipe_right_app"
        const val KEY_SWIPE_LEFT_ENABLED = "swipe_left_enabled"
        const val KEY_SWIPE_RIGHT_ENABLED = "swipe_right_enabled"
        const val KEY_SWIPE_DOWN_ACTION = "swipe_down_action"
        const val KEY_DOUBLE_TAP_LOCK = "double_tap_lock"
        const val KEY_HOME_TO_RECENTS = "home_to_recents"

        const val KEY_THEME_PRESET = "theme_preset"
        const val KEY_CUSTOM_BG_COLOR = "custom_bg_color"
        const val KEY_APPEARANCE_MODE = "appearance_mode"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_TEXT_SIZE_SCALE = "text_size_scale"

        private const val KEY_HIDDEN_APPS = "hidden_apps_set"
        private const val KEY_PINNED_APPS = "pinned_apps_ordered"
        private const val KEY_MUTED_APPS = "muted_apps_set"
        private const val KEY_FIRST_RUN_SEEDED = "first_run_seeded"
        private const val KEY_HIDE_DEFAULT_LAUNCHER_PROMPT = "hide_default_launcher_prompt"
        private const val KEY_WEATHER_CACHED_SUMMARY = "weather_cached_summary"
        private const val KEY_WEATHER_CACHED_AT = "weather_cached_at"
        private const val RENAME_PREFIX = "rename_"

        private fun slotLabelKey(slot: Int) = "slot_label_$slot"
        private fun slotPackageKey(slot: Int) = "slot_package_$slot"
        private fun slotActivityKey(slot: Int) = "slot_activity_$slot"
        private fun slotUserKey(slot: Int) = "slot_user_$slot"
        private fun slotFolderKey(slot: Int) = "slot_folder_$slot"
        private fun slotShortcutKey(slot: Int) = "slot_shortcut_$slot"
        private fun widgetTapKey(widget: String) = "widget_tap_$widget"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Home
    var slotCount: Int
        // The settings screen stores ListPreference values as strings.
        get() = prefs.getString(KEY_SLOT_COUNT, null)?.toIntOrNull()
            ?: runCatching { prefs.getInt(KEY_SLOT_COUNT, 4) }.getOrDefault(4)
        set(value) = prefs.edit { putString(KEY_SLOT_COUNT, value.toString()) }

    var textAlignment: String
        get() = prefs.getString(KEY_TEXT_ALIGNMENT, "center") ?: "center"
        set(value) = prefs.edit { putString(KEY_TEXT_ALIGNMENT, value) }

    var dateTimeMode: String
        get() = prefs.getString(KEY_DATE_TIME_MODE, "date_time") ?: "date_time"
        set(value) = prefs.edit { putString(KEY_DATE_TIME_MODE, value) }

    var statusBarVisible: Boolean
        get() = prefs.getBoolean(KEY_STATUS_BAR_VISIBLE, false)
        set(value) = prefs.edit { putBoolean(KEY_STATUS_BAR_VISIBLE, value) }

    // Widgets: ordered CSV of enabled widget types; position = display order.
    var widgetsOrder: List<String>
        get() {
            val stored = prefs.getString(KEY_WIDGETS_ORDER, null)
                ?: return listOf("clock", "date", "weather", "battery")
            return stored.split(",").filter { it in ALL_WIDGETS }
        }
        set(value) = prefs.edit {
            putString(KEY_WIDGETS_ORDER, value.filter { it in ALL_WIDGETS }.joinToString(","))
        }

    val widgetsEnabled: Set<String> get() = widgetsOrder.toSet()

    /** Tap override for a widget: "pkg|activity|user", or "" for the default action. */
    fun getWidgetTapAction(widget: String): String =
        prefs.getString(widgetTapKey(widget), "").orEmpty()

    fun setWidgetTapAction(widget: String, action: String) =
        prefs.edit { putString(widgetTapKey(widget), action) }

    var weatherTempUnit: String
        get() = prefs.getString(KEY_WEATHER_TEMP_UNIT, "fahrenheit") ?: "fahrenheit"
        set(value) = prefs.edit { putString(KEY_WEATHER_TEMP_UNIT, value) }

    var weatherCachedSummary: String
        get() = prefs.getString(KEY_WEATHER_CACHED_SUMMARY, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_WEATHER_CACHED_SUMMARY, value) }

    var weatherCachedAt: Long
        get() = prefs.getLong(KEY_WEATHER_CACHED_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_WEATHER_CACHED_AT, value) }

    // Drawer
    var autoShowKeyboard: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SHOW_KEYBOARD, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_SHOW_KEYBOARD, value) }

    var sortMode: String
        get() = prefs.getString(KEY_SORT_MODE, "default") ?: "default"
        set(value) = prefs.edit { putString(KEY_SORT_MODE, value) }

    // Gestures
    var swipeLeftApp: String?
        get() = prefs.getString(KEY_SWIPE_LEFT_APP, null)
        set(value) = prefs.edit { putString(KEY_SWIPE_LEFT_APP, value) }

    var swipeRightApp: String?
        get() = prefs.getString(KEY_SWIPE_RIGHT_APP, null)
        set(value) = prefs.edit { putString(KEY_SWIPE_RIGHT_APP, value) }

    var swipeLeftEnabled: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_LEFT_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_SWIPE_LEFT_ENABLED, value) }

    var swipeRightEnabled: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_RIGHT_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_SWIPE_RIGHT_ENABLED, value) }

    var swipeDownAction: String
        get() = prefs.getString(KEY_SWIPE_DOWN_ACTION, "notifications") ?: "notifications"
        set(value) = prefs.edit { putString(KEY_SWIPE_DOWN_ACTION, value) }

    var doubleTapLock: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_TAP_LOCK, false)
        set(value) = prefs.edit { putBoolean(KEY_DOUBLE_TAP_LOCK, value) }

    var homeToRecents: Boolean
        get() = prefs.getBoolean(KEY_HOME_TO_RECENTS, false)
        set(value) = prefs.edit { putBoolean(KEY_HOME_TO_RECENTS, value) }

    // Theme
    var themePreset: String
        get() = prefs.getString(KEY_THEME_PRESET, "amoled") ?: "amoled"
        set(value) = prefs.edit { putString(KEY_THEME_PRESET, value) }

    var customBgColor: Int
        get() = prefs.getString(KEY_CUSTOM_BG_COLOR, null)?.toLongOrNull(16)?.toInt()
            ?: 0xFF111827.toInt()
        set(value) = prefs.edit { putString(KEY_CUSTOM_BG_COLOR, value.toUInt().toString(16)) }

    var appearanceMode: String
        get() = prefs.getString(KEY_APPEARANCE_MODE, "dark") ?: "dark"
        set(value) = prefs.edit { putString(KEY_APPEARANCE_MODE, value) }

    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, "jetbrains_mono_nerd") ?: "jetbrains_mono_nerd"
        set(value) = prefs.edit { putString(KEY_FONT_FAMILY, value) }

    var textSizeScale: Float
        // SeekBarPreference stores an int percentage.
        get() = runCatching { prefs.getInt(KEY_TEXT_SIZE_SCALE, 100) / 100f }
            .getOrElse { runCatching { prefs.getFloat(KEY_TEXT_SIZE_SCALE, 1f) }.getOrDefault(1f) }
        set(value) = prefs.edit { putInt(KEY_TEXT_SIZE_SCALE, (value * 100).toInt()) }

    // General flags
    var firstRunSeeded: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN_SEEDED, false)
        set(value) = prefs.edit { putBoolean(KEY_FIRST_RUN_SEEDED, value) }

    var hideDefaultLauncherPrompt: Boolean
        get() = prefs.getBoolean(KEY_HIDE_DEFAULT_LAUNCHER_PROMPT, false)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_DEFAULT_LAUNCHER_PROMPT, value) }

    // Home slots (1-based, 1..MAX_SLOTS)
    fun getSlot(slot: Int): SlotEntry = SlotEntry(
        label = prefs.getString(slotLabelKey(slot), "").orEmpty(),
        packageName = prefs.getString(slotPackageKey(slot), "").orEmpty(),
        activityClassName = prefs.getString(slotActivityKey(slot), "").orEmpty(),
        userToken = prefs.getString(slotUserKey(slot), "").orEmpty(),
        folderId = prefs.getInt(slotFolderKey(slot), -1),
        shortcutId = prefs.getString(slotShortcutKey(slot), "").orEmpty(),
    )

    fun setSlot(slot: Int, entry: SlotEntry) = prefs.edit {
        putString(slotLabelKey(slot), entry.label)
        putString(slotPackageKey(slot), entry.packageName)
        putString(slotActivityKey(slot), entry.activityClassName)
        putString(slotUserKey(slot), entry.userToken)
        putInt(slotFolderKey(slot), entry.folderId)
        putString(slotShortcutKey(slot), entry.shortcutId)
    }

    fun clearSlot(slot: Int) = setSlot(slot, SlotEntry())

    /** Clears every home slot pointing at the given folder (after deletion). */
    fun clearSlotsForFolder(folderId: Int) {
        for (slot in 1..MAX_SLOTS) {
            if (getSlot(slot).folderId == folderId) clearSlot(slot)
        }
    }

    // Hidden apps, stored as "package|userToken"
    var hiddenApps: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN_APPS, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_HIDDEN_APPS, value) }

    fun isHidden(key: String): Boolean = key in hiddenApps

    fun toggleHidden(key: String) {
        val current = hiddenApps.toMutableSet()
        if (!current.remove(key)) current.add(key)
        hiddenApps = current
    }

    // Pinned apps: ordered "package|userToken" keys
    var pinnedApps: List<String>
        get() = prefs.getString(KEY_PINNED_APPS, "").orEmpty()
            .split(",").filter { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_PINNED_APPS, value.joinToString(",")) }

    fun isPinned(key: String): Boolean = key in pinnedApps

    fun togglePinned(key: String) {
        val current = pinnedApps.toMutableList()
        if (!current.remove(key)) current.add(key)
        pinnedApps = current
    }

    fun movePinned(key: String, up: Boolean) {
        val current = pinnedApps.toMutableList()
        val index = current.indexOf(key)
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target < 0 || target >= current.size) return
        current[index] = current[target]
        current[target] = key
        pinnedApps = current
    }

    // Rename labels, keyed by "package|userToken"
    fun getRenameLabel(key: String): String =
        prefs.getString(RENAME_PREFIX + key, "").orEmpty()

    fun setRenameLabel(key: String, label: String) =
        prefs.edit { putString(RENAME_PREFIX + key, label) }

    fun getRenameLabels(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith(RENAME_PREFIX) && it.value is String && (it.value as String).isNotEmpty() }
            .associate { it.key.removePrefix(RENAME_PREFIX) to it.value as String }

    /**
     * Per-app notification mute deadlines, stored as "package|untilEpochMillis".
     * Expired entries are pruned on write.
     */
    fun getMuteUntil(packageName: String): Long =
        prefs.getStringSet(KEY_MUTED_APPS, null)
            ?.firstOrNull { it.substringBefore("|") == packageName }
            ?.substringAfter("|")?.toLongOrNull() ?: 0L

    fun setMuteUntil(packageName: String, untilMillis: Long) {
        val now = System.currentTimeMillis()
        val entries = prefs.getStringSet(KEY_MUTED_APPS, null).orEmpty()
            .filter { entry ->
                entry.substringBefore("|") != packageName &&
                    (entry.substringAfter("|").toLongOrNull() ?: 0L) > now
            }
            .toMutableSet()
        if (untilMillis > now) entries.add("$packageName|$untilMillis")
        prefs.edit { putStringSet(KEY_MUTED_APPS, entries) }
    }

    fun getMuteEntries(): Map<String, Long> {
        val now = System.currentTimeMillis()
        return prefs.getStringSet(KEY_MUTED_APPS, null).orEmpty().mapNotNull { entry ->
            val pkg = entry.substringBefore("|")
            val until = entry.substringAfter("|").toLongOrNull() ?: return@mapNotNull null
            if (pkg.isBlank() || until <= now) null else pkg to until
        }.toMap()
    }

    fun replaceMuteEntries(entries: Map<String, Long>) {
        val now = System.currentTimeMillis()
        val set = entries.mapNotNull { (pkg, until) ->
            if (pkg.isBlank() || until <= now) null else "$pkg|$until"
        }.toSet()
        prefs.edit { putStringSet(KEY_MUTED_APPS, set) }
    }

    fun replaceRenameLabels(labels: Map<String, String>) {
        val existing = prefs.all.keys.filter { it.startsWith(RENAME_PREFIX) }
        prefs.edit {
            existing.forEach { remove(it) }
            labels.forEach { (key, label) ->
                if (key.isNotBlank() && label.isNotBlank()) {
                    putString(RENAME_PREFIX + key, label)
                }
            }
        }
    }

    fun replaceWidgetTapActions(actions: Map<String, String>) {
        prefs.edit {
            ALL_WIDGETS.forEach { widget ->
                putString(widgetTapKey(widget), actions[widget].orEmpty())
            }
        }
    }

    fun migrateUserToken(from: String, to: String) {
        if (from == to) return
        hiddenApps = hiddenApps.map { AppKey.rewriteUserToken(it, from, to) }.toSet()
        pinnedApps = pinnedApps.map { AppKey.rewriteUserToken(it, from, to) }
        val labels = getRenameLabels()
        if (labels.any { AppKey.parse(it.key).userToken == from }) {
            replaceRenameLabels(
                labels.mapKeys { (key, _) -> AppKey.rewriteUserToken(key, from, to) }
            )
        }
        for (slot in 1..MAX_SLOTS) {
            val entry = getSlot(slot)
            if (entry.userToken == from) setSlot(slot, entry.copy(userToken = to))
        }
        swipeLeftApp = swipeLeftApp?.let { rewriteEmbeddedUserToken(it, from, to) }
        swipeRightApp = swipeRightApp?.let { rewriteEmbeddedUserToken(it, from, to) }
        ALL_WIDGETS.forEach { widget ->
            val action = getWidgetTapAction(widget)
            if (action.isNotBlank()) {
                setWidgetTapAction(widget, rewriteEmbeddedUserToken(action, from, to))
            }
        }
    }
}
