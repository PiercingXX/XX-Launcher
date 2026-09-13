package com.piercingxx.xxlauncher.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Translucent windows do not resize for the IME (`adjustResize` is a no-op).
 * Pad [root] by the live IME / nav inset so a bottom search field stays above
 * the keyboard, including mid-animation frames.
 */
object ImeLift {

    fun bottomInset(imeBottom: Int, navBottom: Int): Int = maxOf(imeBottom, navBottom)

    fun attach(root: View) {
        val apply = fun(insets: WindowInsetsCompat) {
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            root.updatePadding(bottom = bottomInset(ime, nav))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            apply(insets)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    apply(insets)
                    return insets
                }
            },
        )
    }
}
