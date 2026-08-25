package com.piercingxx.xxlauncher.theme

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils

/**
 * Recolors a shown AlertDialog to the launcher theme: background, text,
 * buttons, list rows (as they attach), and the launcher font. Call after
 * show() so the button bar exists.
 */
fun AlertDialog.applyLauncherTheme(themeManager: ThemeManager, fontKey: String) {
    val colors = themeManager.getCurrentColors()
    val density = context.resources.displayMetrics.density

    window?.setBackgroundDrawable(
        GradientDrawable().apply {
            setColor(colors.backgroundColor)
            cornerRadius = 24f * density
            // A hairline keeps the sheet visible when it matches the screen color.
            setStroke(
                (1.5f * density).toInt(),
                ColorUtils.setAlphaComponent(colors.textColor, 70),
            )
        }
    )

    fun themeTree(view: View) {
        view.applyLauncherFont(fontKey)
        recolor(view, colors.textColor)
    }

    window?.decorView?.let(::themeTree)

    // List rows are created lazily (and recycled); theme each one as it lands.
    listView?.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
        override fun onChildViewAdded(parent: View?, child: View?) {
            child?.let(::themeTree)
        }

        override fun onChildViewRemoved(parent: View?, child: View?) = Unit
    })
}

private fun recolor(view: View, textColor: Int) {
    when (view) {
        is EditText -> {
            view.setTextColor(textColor)
            view.setHintTextColor(ColorUtils.setAlphaComponent(textColor, 128))
        }

        is TextView -> view.setTextColor(textColor)

        is ViewGroup -> {
            for (index in 0 until view.childCount) {
                recolor(view.getChildAt(index), textColor)
            }
        }
    }
}
