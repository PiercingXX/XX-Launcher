package com.launcher

import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.launcher.theme.applyLauncherTheme
import com.launcher.util.showToast

/** Accepts Android pin-shortcut requests (the standard confirmation activity). */
class PinItemActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(null)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            finish()
            return
        }

        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = launcherApps.getPinItemRequest(intent)
        if (request != null) {
            handleRequest(request)
        } else {
            showToast(getString(R.string.pin_request_invalid))
            finish()
        }
    }

    // Pinning multiplies drawer rows, so it needs explicit consent — the
    // confirmation dialog defers finish() until the user has answered.
    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleRequest(request: LauncherApps.PinItemRequest) {
        if (request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
            showToast(getString(R.string.pin_widgets_unsupported))
            finish()
            return
        }
        val shortcut = request.shortcutInfo
        if (shortcut == null) {
            showToast(getString(R.string.pin_shortcut_failed))
            finish()
            return
        }

        val app = LauncherApplication.from(this)
        val label = shortcut.shortLabel?.toString()
            ?: shortcut.longLabel?.toString()
            ?: shortcut.`package`
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.pin_confirm_message, label))
            .setPositiveButton(R.string.pin_confirm_add) { _, _ ->
                val accepted =
                    runCatching { request.isValid && request.accept() }.getOrDefault(false)
                if (accepted) {
                    showToast(getString(R.string.pin_shortcut_success))
                    app.appRepo.refresh()
                } else {
                    showToast(getString(R.string.pin_shortcut_failed))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { finish() }
            .show()
            .applyLauncherTheme(app.themeManager, app.settings.fontFamily)
    }
}
