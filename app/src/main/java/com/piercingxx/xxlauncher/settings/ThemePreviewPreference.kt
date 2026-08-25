package com.piercingxx.xxlauncher.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.piercingxx.xxlauncher.R
import com.piercingxx.xxlauncher.theme.ThemeColors

/**
 * Horizontal strip of theme-preset swatches. Tapping a swatch applies the
 * preset immediately (live preview — the previous preset is one tap away).
 */
class ThemePreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    var presets: Map<String, ThemeColors> = emptyMap()
    var selectedKey: String = ""
    var onPresetSelected: ((String) -> Unit)? = null
    var onCustomSelected: (() -> Unit)? = null

    init {
        layoutResource = R.layout.preference_theme_strip
        isPersistent = false
    }

    fun refresh() = notifyChanged()

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val strip = holder.findViewById(R.id.themeStrip) as LinearLayout
        strip.removeAllViews()

        presets.forEach { (key, colors) ->
            strip.addView(makeSwatch(key, colors.backgroundColor, colors.textColor, key == selectedKey))
        }
        // Custom color swatch opens the color picker dialog.
        strip.addView(
            makeSwatch("custom", Color.TRANSPARENT, Color.GRAY, selectedKey == "custom").apply {
                text = "＋"
                setOnClickListener { onCustomSelected?.invoke() }
            }
        )
    }

    private fun makeSwatch(
        key: String,
        backgroundColor: Int,
        textColor: Int,
        selected: Boolean,
    ): TextView = TextView(context).apply {
        text = "Aa"
        gravity = Gravity.CENTER
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(backgroundColor)
            setStroke(dp(if (selected) 3 else 1), if (selected) textColor else Color.GRAY)
        }
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            marginEnd = dp(10)
        }
        contentDescription = key
        isClickable = true
        setOnClickListener { onPresetSelected?.invoke(key) }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
