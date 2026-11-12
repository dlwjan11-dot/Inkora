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
    /** settings that stay one under the other (several sliders): not in the sideways strip, so a drag only moves the slider */
    private val stack = android.widget.FrameLayout(context).apply { visibility = GONE }
    private val line = Paint().apply { color = Ui.DIVIDER }
    private var current: View? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 52.dp
        setPadding(12.dp, 4.dp, 8.dp, 4.dp)
        // a floating island, as wide as its settings (up to the screen's width)
        background = Ui.rounded(Ui.BAR, 18f, Ui.OUTLINE, 1)
        elevation = 8f.dp
        isClickable = true // touches here never reach the canvas
        addView(icon, LayoutParams(30.dp, 30.dp))
        addView(title, LayoutParams(-2, -2).apply { marginStart = 6.dp; marginEnd = 12.dp })
        addView(View(context).apply { setBackgroundColor(Ui.DIVIDER) }, LayoutParams(1.dp, 26.dp).apply { marginEnd = 12.dp })
        addView(scroll, LayoutParams(-2, -2))
        addView(stack, LayoutParams(-2, -2).apply { topMargin = 6.dp; bottomMargin = 6.dp })
    }

    companion object {
        /** set as a settings view's tag to keep its rows one under the other */
        const val STACKED = "options-stacked"
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
            scroll.removeAllViews()
            stack.removeAllViews()
            if (content.tag == STACKED || (fromTool && content is LinearLayout && content.orientation == VERTICAL && hasSlider(content))) {
                // sliders keep their own line each, wide enough to drag
                if (content.minimumWidth < 300.dp) content.minimumWidth = minOf(340.dp, resources.displayMetrics.widthPixels - 190.dp).coerceAtLeast(200.dp)
                content.background = null
                content.elevation = 0f
                content.setPadding(0, 0, 0, 0)
                scroll.visibility = GONE
                stack.visibility = VISIBLE
                stack.addView(content, android.widget.FrameLayout.LayoutParams(-2, -2))
            } else {
                if (fromTool) flatten(content)
                stack.visibility = GONE
                scroll.visibility = VISIBLE
                scroll.addView(content, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
                scroll.scrollTo(0, 0)
            }
            current = content
        } finally {
            swapping = false
        }
    }

    private fun hasSlider(v: View): Boolean = v is android.widget.SeekBar ||
        (v is ViewGroup && (0 until v.childCount).any { hasSlider(v.getChildAt(it)) })

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
