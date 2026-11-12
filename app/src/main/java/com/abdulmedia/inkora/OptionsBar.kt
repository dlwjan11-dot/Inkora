package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The bar of the current tool's settings, always in the same place: under the top bar on a tablet,
 * over the tool bar on a phone. The tool's name on the left, its settings after it, in one line
 * that slides sideways when it is longer than the screen. Apply / Cancel (when a tool has them)
 * are part of its settings.
 */
@SuppressLint("ViewConstructor")
class OptionsBar(context: Context) : LinearLayout(context) {

    private val icon = IconView(context, Icon.BRUSH).apply { isClickable = false; tint = Ui.ACCENT; iconScale = 0.62f }
    private val title: TextView = Ui.text(context, "", 13.5f, Ui.TEXT, true).apply { maxLines = 1 }
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = false
    }
    private val line = Paint().apply { color = Ui.DIVIDER }
    private var current: View? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 50.dp
        setPadding(10.dp, 4.dp, 10.dp, 4.dp)
        setBackgroundColor(Ui.BAR)
        setWillNotDraw(false)
        isClickable = true // touches here never reach the canvas
        addView(icon, LayoutParams(30.dp, 30.dp))
        addView(title, LayoutParams(-2, -2).apply { marginStart = 6.dp; marginEnd = 12.dp })
        addView(View(context).apply { setBackgroundColor(Ui.DIVIDER) }, LayoutParams(1.dp, 26.dp).apply { marginEnd = 12.dp })
        addView(scroll, LayoutParams(0, -2, 1f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, height - 1f.dp, width.toFloat(), height.toFloat(), line)
    }

    fun setTitle(ic: Icon, name: String) {
        icon.icon = ic
        title.text = L10n.tr(name)
    }

    /**
     * Shows [content] as the settings. A tool's own bar ([fromTool]) was made to float at the bottom:
     * it loses its card and its rows are laid out side by side.
     */
    private var swapping = false

    fun show(ic: Icon, name: String, content: View, fromTool: Boolean) {
        setTitle(ic, name)
        if (current === content || swapping) return
        // removing the old settings can send a touch-cancel to them, which may ask for a new bar:
        // that inner request is ignored here, and the bar ends up holding exactly one thing
        swapping = true
        try {
            (content.parent as? ViewGroup)?.removeView(content)
            if (fromTool) flatten(content)
            scroll.removeAllViews()
            if (scroll.childCount > 0) scroll.removeAllViews()
            scroll.addView(content, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
            current = content
            scroll.scrollTo(0, 0)
        } finally {
            swapping = false
        }
    }

    private fun flatten(v: View) {
        v.background = null
        v.elevation = 0f
        v.setPadding(0, 0, 0, 0)
        if (v is LinearLayout && v.orientation == VERTICAL) {
            v.orientation = HORIZONTAL
            v.gravity = Gravity.CENTER_VERTICAL
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                val lp = c.layoutParams as? LayoutParams ?: continue
                lp.topMargin = 0; lp.bottomMargin = 0
                if (i > 0) lp.marginStart = 14.dp
                // a row that filled the old card's width gets a fixed width in a line
                if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT) lp.width = if (c is LinearLayout && c.orientation == HORIZONTAL) ViewGroup.LayoutParams.WRAP_CONTENT else 230.dp
                lp.weight = 0f
                c.layoutParams = lp
                if (c is LinearLayout) { c.background = null; c.elevation = 0f }
            }
        }
    }
}
