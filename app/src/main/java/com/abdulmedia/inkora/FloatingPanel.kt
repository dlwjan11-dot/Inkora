package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * A pinned panel: stays on screen over the canvas, slightly see-through,
 * and can be dragged anywhere by its top strip.
 */
@SuppressLint("ViewConstructor")
class FloatingPanel(
    context: Context,
    title: String,
    content: View,
    private val onClose: () -> Unit,
    private val onMoved: (Int, Int) -> Unit
) : LinearLayout(context) {

    private val idleAlpha = 0.93f

    init {
        orientation = VERTICAL
        background = Ui.rounded(0xE61E1E22.toInt(), 16f, 0x33FFFFFF, 1)
        elevation = 14f.dp
        alpha = 0f
        animate().alpha(idleAlpha).setDuration(180).start()

        val strip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 4.dp, 4.dp, 0)
        }
        strip.addView(Ui.text(context, "⠿  ${L10n.tr(title)}", 12f, Ui.TEXT_DIM, true), LayoutParams(0, -2, 1f))
        strip.addView(Ui.button(context, "Unpin ✕") { onClose() }.apply {
            textSize = 11f
            background = null
            setPadding(8.dp, 6.dp, 8.dp, 6.dp)
        })
        addView(strip, LayoutParams(-1, 34.dp))
        // the panel's own background is not needed inside the floating frame
        content.background = null
        addView(content, LayoutParams(-1, 0, 1f))

        var startX = 0f
        var startY = 0f
        var baseL = 0
        var baseT = 0
        strip.setOnTouchListener { _, e ->
            val lp = layoutParams as FrameLayout.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    baseL = lp.leftMargin; baseT = lp.topMargin
                    alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val p = parent as? View
                    val maxL = ((p?.width ?: 2000) - width).coerceAtLeast(0)
                    val maxT = ((p?.height ?: 2000) - 60.dp).coerceAtLeast(0)
                    lp.leftMargin = (baseL + (e.rawX - startX)).toInt().coerceIn(0, maxL)
                    lp.topMargin = (baseT + (e.rawY - startY)).toInt().coerceIn(56.dp, maxT)
                    layoutParams = lp
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    alpha = idleAlpha
                    onMoved(lp.leftMargin, lp.topMargin)
                }
            }
            true
        }
    }

    /** fade out while the user is drawing so the canvas stays visible */
    fun setDrawing(drawing: Boolean) {
        animate().alpha(if (drawing) 0.22f else idleAlpha).setDuration(if (drawing) 120 else 250).start()
    }
}
