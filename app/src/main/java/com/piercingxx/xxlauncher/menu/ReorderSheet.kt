package com.piercingxx.xxlauncher.menu

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.piercingxx.xxlauncher.R
import com.piercingxx.xxlauncher.data.SettingsRepository
import com.piercingxx.xxlauncher.data.movedItem
import com.piercingxx.xxlauncher.theme.ThemeManager
import com.piercingxx.xxlauncher.theme.applyLauncherFont

/**
 * Drag-to-reorder list inside an [ActionSheet]. Long-press anywhere on a row,
 * or touch its handle, to lift it; dropping fires [Handle] callbacks with the
 * whole order so callers persist in one write. One sheet serves home slots,
 * pinned drawer rows and folder members.
 */
class ReorderSheet(
    private val context: Context,
    private val themeManager: ThemeManager,
    private val settings: SettingsRepository,
) {
    data class Item(val id: String, val label: String, val dimmed: Boolean = false)

    /** Lets an async action (sort) swap the list in place while the sheet stays open. */
    inner class Handle internal constructor(private val adapter: Adapter) {
        fun replace(items: List<Item>) = adapter.replace(items)
    }

    fun show(
        title: String,
        items: List<Item>,
        sortLabel: String? = null,
        onSort: ((Handle) -> Unit)? = null,
        onOrderChanged: (List<String>) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val colors = themeManager.getCurrentColors()
        val fontKey = settings.fontFamily
        val scale = settings.textSizeScale
        val gravity = ActionSheet.gravityFor(settings.textAlignment)

        lateinit var touchHelper: ItemTouchHelper
        val adapter = Adapter(items.toMutableList(), colors.textColor, fontKey, scale, gravity) { holder ->
            touchHelper.startDrag(holder)
        }
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            itemAnimator?.moveDuration = 120
        }
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0,
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewHolder.itemView.animate().scaleX(1.03f).scaleY(1.03f).setDuration(90).start()
                    viewHolder.itemView.setBackgroundColor(
                        ColorUtils.setAlphaComponent(colors.textColor, 18)
                    )
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                viewHolder.itemView.background = null
                onOrderChanged(adapter.ids())
            }
        })
        touchHelper.attachToRecyclerView(list)

        val sheet = ActionSheet(context, themeManager, settings)
            .title(title)
            .subtitle(context.getString(R.string.reorder_hint))
            .view(list, scrolls = true)
            .onDismiss(onDismiss)
        if (sortLabel != null && onSort != null) {
            val handle = Handle(adapter)
            sheet.button(sortLabel) { onSort(handle) }
        }
        sheet.button(context.getString(R.string.action_done), primary = true) { sheet.dismiss() }
        sheet.show()
    }

    internal class Adapter(
        private val items: MutableList<Item>,
        private val textColor: Int,
        private val fontKey: String,
        private val scale: Float,
        private val gravity: Int,
        private val startDrag: (RecyclerView.ViewHolder) -> Unit,
    ) : RecyclerView.Adapter<Adapter.Holder>() {

        class Holder(view: View, val label: TextView, val handle: ImageView) : RecyclerView.ViewHolder(view)

        fun ids(): List<String> = items.map { it.id }

        fun move(from: Int, to: Int) {
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return
            val moved = items.movedItem(from, to)
            if (moved === items) return
            items.clear()
            items.addAll(moved)
            notifyItemMoved(from, to)
        }

        @SuppressLint("NotifyDataSetChanged")
        fun replace(newItems: List<Item>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(24), 0, dp(12), 0)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val label = TextView(context).apply {
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
                this.gravity = this@Adapter.gravity or Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), dp(12), dp(12))
                applyLauncherFont(fontKey)
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val handle = ImageView(context).apply {
                setImageDrawable(DragHandleDrawable(ColorUtils.setAlphaComponent(textColor, 130), density))
                scaleType = ImageView.ScaleType.CENTER
                isFocusable = true
            }
            row.addView(handle, LinearLayout.LayoutParams(dp(48), dp(48)))
            return Holder(row, label, handle)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.label.text = item.label
            holder.label.alpha = if (item.dimmed) 0.4f else 1f
            holder.handle.contentDescription =
                holder.itemView.context.getString(R.string.accessibility_drag_handle, item.label)
            holder.handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) startDrag(holder)
                false
            }
        }
    }

    /** Two short bars: a font-independent grip glyph. */
    private class DragHandleDrawable(color: Int, private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
        }

        override fun draw(canvas: Canvas) {
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val half = 9f * density
            val gap = 3f * density
            canvas.drawLine(cx - half, cy - gap, cx + half, cy - gap, paint)
            canvas.drawLine(cx - half, cy + gap, cx + half, cy + gap, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = (24 * density).toInt()
        override fun getIntrinsicHeight(): Int = (24 * density).toInt()
    }
}
