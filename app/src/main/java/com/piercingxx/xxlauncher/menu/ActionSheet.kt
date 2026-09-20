package com.piercingxx.xxlauncher.menu

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.piercingxx.xxlauncher.R
import com.piercingxx.xxlauncher.data.SettingsRepository
import com.piercingxx.xxlauncher.theme.ThemeManager
import com.piercingxx.xxlauncher.theme.applyLauncherFont
import com.piercingxx.xxlauncher.util.hideNavigationBar
import com.piercingxx.xxlauncher.util.isGestureNavigation

/**
 * The launcher's one sheet. Every long-press menu, chooser, confirmation and
 * text prompt is a bottom sheet drawn in the theme colors and font, with rows
 * in groups and a hairline between groups. The surface is the home ground
 * lifted a touch toward the text color so it reads as a layer even when the
 * home screen is the same black.
 *
 * Build with the fluent calls, then [show] once.
 */
class ActionSheet(
    private val context: Context,
    themeManager: ThemeManager,
    settings: SettingsRepository,
) {
    class Row(
        val label: String,
        val enabled: Boolean = true,
        /** Rows normally close the sheet before acting; a toggle that re-renders in place keeps it open. */
        val keepOpen: Boolean = false,
        val onTap: () -> Unit,
    )

    private val colors = themeManager.getCurrentColors()
    private val fontKey = settings.fontFamily
    private val scale = settings.textSizeScale
    private val density = context.resources.displayMetrics.density
    private val textGravity = gravityFor(settings.textAlignment)

    val textColor: Int get() = colors.textColor
    val surfaceColor: Int = surfaceFor(colors.backgroundColor, colors.textColor)
    val hairlineColor: Int = hairlineFor(colors.textColor)

    private var title: String? = null
    private var subtitle: String? = null
    private val sections = mutableListOf<Section>()
    private val buttons = mutableListOf<Button>()
    private var onDismiss: (() -> Unit)? = null
    private var keyboardTarget: EditText? = null
    private var dialog: BottomSheetDialog? = null

    private sealed interface Section {
        class Rows(val rows: List<Row>) : Section
        class Custom(val view: View, val scrolls: Boolean) : Section
    }

    private class Button(val label: String, val primary: Boolean, val onTap: () -> Unit)

    fun title(text: String) = apply { title = text }
    fun subtitle(text: String?) = apply { subtitle = text?.takeIf { it.isNotBlank() } }

    /** One group of rows; empty groups are skipped so callers can build conditionally. */
    fun group(rows: List<Row>) = apply { if (rows.isNotEmpty()) sections += Section.Rows(rows) }
    fun group(vararg rows: Row) = group(rows.toList())

    /**
     * A custom block. [scrolls] marks a view that scrolls on its own (a
     * RecyclerView); the sheet then leaves the body unwrapped so nested
     * scrolling reaches it.
     */
    fun view(view: View, scrolls: Boolean = false) = apply { sections += Section.Custom(view, scrolls) }

    /** Trailing text buttons, in order. The primary one is drawn bold. */
    fun button(label: String, primary: Boolean = false, onTap: () -> Unit) =
        apply { buttons += Button(label, primary, onTap) }

    fun onDismiss(block: () -> Unit) = apply { onDismiss = block }

    fun dismiss() {
        dialog?.dismiss()
    }

    /**
     * A single-line text field in the sheet's style. Done on the keyboard
     * fires [onSubmit] and closes the sheet; the keyboard opens with the sheet.
     */
    fun input(initial: String, hint: String?, onSubmit: (String) -> Unit): EditText {
        val field = EditText(context).apply {
            setText(initial)
            setSelection(0, text.length)
            this.hint = hint
            setTextColor(colors.textColor)
            setHintTextColor(ColorUtils.setAlphaComponent(colors.textColor, 110))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * scale)
            gravity = textGravity
            background = null
            setPadding(dp(24), dp(12), dp(24), dp(12))
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                textCursorDrawable = null
            }
            applyLauncherFont(fontKey)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    dismiss()
                    onSubmit(text.toString().trim())
                    true
                } else {
                    false
                }
            }
        }
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                View(context).apply { setBackgroundColor(ColorUtils.setAlphaComponent(colors.textColor, 120)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)).apply {
                    marginStart = dp(24); marginEnd = dp(24); bottomMargin = dp(8)
                },
            )
        }
        keyboardTarget = field
        view(block)
        return field
    }

    fun show(): BottomSheetDialog {
        val dialog = BottomSheetDialog(context, R.style.Theme_Launcher_Sheet)
        this.dialog = dialog

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                val radius = dpF(28f)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                setStroke(dp(1).coerceAtLeast(1), hairlineColor)
            }
            clipToOutline = true
        }

        // Drag handle: the only chrome, and the cue that the sheet pulls down.
        val handleColor = ColorUtils.setAlphaComponent(colors.textColor, 90)
        root.addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    setColor(handleColor)
                    cornerRadius = dpF(2f)
                }
            },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
                bottomMargin = dp(6)
            },
        )

        title?.let { text ->
            root.addView(TextView(context).apply {
                this.text = text
                setTextColor(colors.textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * scale)
                gravity = textGravity
                maxLines = 2
                setPadding(dp(24), dp(10), dp(24), if (subtitle == null) dp(10) else dp(2))
                applyLauncherFont(fontKey)
            })
        }
        subtitle?.let { text ->
            root.addView(TextView(context).apply {
                this.text = text
                setTextColor(colors.textColor)
                alpha = 0.55f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
                gravity = textGravity
                setPadding(dp(24), 0, dp(24), dp(10))
                applyLauncherFont(fontKey)
            })
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }
        var scrollsItself = false
        sections.forEachIndexed { index, section ->
            if (index > 0) content.addView(divider())
            when (section) {
                is Section.Rows -> section.rows.forEach { content.addView(rowView(it)) }
                is Section.Custom -> {
                    if (section.scrolls) scrollsItself = true
                    content.addView(
                        section.view,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
            }
        }
        val body: View = if (scrollsItself) {
            content
        } else {
            NestedScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(content)
            }
        }
        root.addView(
            MaxHeightFrame(context, maxBodyHeight()).apply {
                addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        if (buttons.isNotEmpty()) {
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), dp(10))
            }
            buttons.forEach { button ->
                bar.addView(TextView(context).apply {
                    text = button.label
                    setTextColor(colors.textColor)
                    alpha = if (button.primary) 1f else 0.7f
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
                    if (button.primary) setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                    minHeight = dp(48)
                    gravity = Gravity.CENTER
                    isClickable = true
                    foreground = rippleFor(colors.textColor)
                    applyLauncherFont(fontKey)
                    setOnClickListener { button.onTap() }
                })
            }
            root.addView(bar)
        }

        dialog.setContentView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        dialog.window?.apply {
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    if (keyboardTarget != null) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            )
            navigationBarColor = surfaceColor
            setDimAmount(0.45f)
        }
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.dismissWithAnimation = true
        dialog.show()

        // Material paints its own rounded surface under ours; clear it so the
        // launcher drawable is the only thing on screen.
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
        dialog.window?.matchHostSystemBars(context)

        keyboardTarget?.let { field ->
            field.requestFocus()
            field.post {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        return dialog
    }

    private fun rowView(row: Row): TextView = TextView(context).apply {
        text = row.label
        setTextColor(colors.textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
        gravity = textGravity or Gravity.CENTER_VERTICAL
        minHeight = dp(52)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        applyLauncherFont(fontKey)
        if (row.enabled) {
            isClickable = true
            foreground = rippleFor(colors.textColor)
            setOnClickListener {
                if (!row.keepOpen) dismiss()
                row.onTap()
            }
        } else {
            alpha = 0.35f
            isEnabled = false
        }
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(hairlineColor)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)).apply {
            marginStart = dp(24); marginEnd = dp(24); topMargin = dp(6); bottomMargin = dp(6)
        }
    }

    private fun maxBodyHeight(): Int =
        (context.resources.displayMetrics.heightPixels * 0.62f).toInt()

    private fun dp(value: Int): Int = (value * density).toInt()
    private fun dpF(value: Float): Float = value * density

    /** Wrap-content frame that stops growing at [maxHeight]; the child scrolls past it. */
    private class MaxHeightFrame(context: Context, private val maxHeight: Int) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val capped = when (mode) {
                MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
                else -> MeasureSpec.makeMeasureSpec(minOf(size, maxHeight), MeasureSpec.AT_MOST)
            }
            super.onMeasure(widthMeasureSpec, capped)
        }
    }

    companion object {
        fun gravityFor(alignment: String): Int = when (alignment) {
            "left" -> Gravity.START
            "right" -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }

        /** Sheet ground: the theme background nudged toward the text color. */
        fun surfaceFor(background: Int, text: Int): Int = ColorUtils.blendARGB(background, text, 0.07f)

        fun hairlineFor(text: Int): Int = ColorUtils.setAlphaComponent(text, 46)

        /** Bounded press feedback in the text color, for any tappable text row. */
        fun rippleFor(text: Int): Drawable = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(text, 40)),
            null,
            ColorDrawable(Color.WHITE),
        )

        /**
         * A sheet is its own window, so the host's hidden navigation bar would
         * pop back for as long as it is open. Mirror the host: if its bar is
         * hidden, hide ours the same way.
         */
        private fun Window.matchHostSystemBars(context: Context) {
            val host = (context as? Activity)?.window?.decorView ?: return
            if (context.isGestureNavigation()) return
            val insets = ViewCompat.getRootWindowInsets(host) ?: return
            if (!insets.isVisible(WindowInsetsCompat.Type.navigationBars())) {
                hideNavigationBar(context)
            }
        }
    }
}
