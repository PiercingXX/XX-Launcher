package com.piercingxx.xxlauncher.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Provides global actions (lock screen, recents) to the launcher. The service
 * does not inspect events; it exists only so [performGlobalAction] is
 * available. Nothing is collected and nothing leaves the device.
 */
class GestureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        /**
         * GLOBAL_ACTION_LOCK_SCREEN is API 28+. The constant inlines, so the
         * call would not crash on 24-27 — it would just silently return
         * false and look like the service was never enabled.
         */
        fun lockScreen(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            return instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
        }

        fun openRecents(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
    }
}
