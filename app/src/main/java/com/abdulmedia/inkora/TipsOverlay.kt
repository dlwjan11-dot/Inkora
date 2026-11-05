package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * First-run tips: three short cards about the things that are easy to miss
 * (the pen button menu, the custom toolbar, finger gestures).
 */
@SuppressLint("ViewConstructor")
class TipsOverlay(context: Context, private val onDone: (View) -> Unit) : FrameLayout(context) {

    private class Page(val art: Int, val title: String, val body: String)

    private val pages = listOf(
        Page(0, "Your pen button does the work",
            "Click it to switch brush and eraser. Hold it (or double-click) for the quick menu: color wheel in the middle, tools on top, size and opacity on the sides."),
        Page(1, "Your toolbar, your way",
            "The strip on the side holds your favorite tools. Long-press it to add, remove or reorder. In Creative and Editing, tap ☆ on any card to add it."),
        Page(2, "Fingers move the canvas",
            "Pinch to zoom and rotate. Tap with two fingers to undo, three to redo. Hold a finger still to pick a color. Draw a line and pause for a perfect shape.")
    )
    private var index = 0

    private val card = LinearLayout(context)
    private val art = TipArt(context)
    private val title = Ui.text(context, "", 20f, bold = true)
    private val body = Ui.text(context, "", 14.5f, Ui.TEXT_DIM)
    private val dots = LinearLayout(context)
    private val next = Ui.pill(context, "Next", primary = true) { go(index + 1) }

    init {
        setBackgroundColor(0xCC08080F.toInt())
        isClickable = true // nothing behind it reacts while the tips are open

        card.orientation = LinearLayout.VERTICAL
        card.setPadding(24.dp, 22.dp, 24.dp, 20.dp)
        card.background = Ui.rounded(Ui.PANEL, 24f, 0x22FFFFFF, 1)
        card.elevation = 24f.dp

        card.addView(art, LinearLayout.LayoutParams(-1, 190.dp))
        title.setPadding(0, 14.dp, 0, 6.dp)
        card.addView(title)
        body.setLineSpacing(3f.dp, 1f)
        card.addView(body)

        val bottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dots.orientation = LinearLayout.HORIZONTAL
        bottom.addView(dots, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(Ui.button(context, "Skip") { finish() }.apply { background = null; setTextColor(Ui.TEXT_DIM) })
        bottom.addView(next, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 6.dp })
        card.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20.dp })

        addView(card, LayoutParams(min(460.dp, context.resources.displayMetrics.widthPixels - 32.dp), -2, Gravity.CENTER))
        alpha = 0f
        animate().alpha(1f).setDuration(220).start()
        show()
    }

    private fun go(i: Int) {
        if (i >= pages.size) { finish(); return }
        index = i
        card.animate().alpha(0f).translationX(-16f.dp * (if (L10n.isRtl) -1 else 1)).setDuration(110).withEndAction {
            show()
            card.translationX = 16f.dp * (if (L10n.isRtl) -1 else 1)
            card.animate().alpha(1f).translationX(0f).setDuration(160).start()
        }.start()
    }

    private fun show() {
        val p = pages[index]
        art.page = p.art
        title.text = L10n.tr(p.title)
        body.text = L10n.tr(p.body)
        next.text = L10n.tr(if (index == pages.size - 1) "Start drawing" else "Next")
        dots.removeAllViews()
        for (k in pages.indices) {
            dots.addView(View(context).apply {
                background = Ui.rounded(if (k == index) Ui.ACCENT else 0x44FFFFFF, 4f)
            }, LinearLayout.LayoutParams(if (k == index) 22.dp else 8.dp, 8.dp).apply { marginEnd = 6.dp })
        }
    }

    private fun finish() {
        animate().alpha(0f).setDuration(160).withEndAction { onDone(this) }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = true

    /** simple drawings for each tip */
    private class TipArt(context: Context) : View(context) {
        var page = 0
            set(v) { field = v; invalidate() }
        private val disc = ColorDisc().apply { set(0xFFE0457B.toInt()) }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            p.style = Paint.Style.FILL
            p.color = 0xFF16161B.toInt()
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 18f.dp, 18f.dp, p)
            when (page) {
                0 -> {
                    // the pen menu
                    p.color = 0xFF0F0F12.toInt()
                    canvas.drawCircle(cx, cy, 86f.dp, p)
                    disc.layout(cx, cy, 46f.dp, 10f.dp)
                    disc.draw(canvas)
                    val icons = listOf(Icon.BRUSH, Icon.SMUDGE, Icon.ERASER, Icon.EYEDROPPER, Icon.UNDO)
                    icons.forEachIndexed { i, ic ->
                        val a = Math.toRadians(270.0 + (i - 2) * 26.0)
                        val x = cx + cos(a).toFloat() * 70f.dp; val y = cy + sin(a).toFloat() * 70f.dp
                        p.color = if (i == 0) Ui.PANEL_ROW_ACTIVE else 0xFF2B2C33.toInt()
                        canvas.drawCircle(x, y, 13f.dp, p)
                        IconPainter.draw(canvas, ic, x, y, 14f.dp, if (i == 0) Ui.ACCENT else Ui.TEXT)
                    }
                    p.style = Paint.Style.STROKE; p.strokeWidth = 6f.dp; p.strokeCap = Paint.Cap.ROUND
                    p.color = 0xD9FFFFFF.toInt()
                    val r = RectF(cx - 70f.dp, cy - 70f.dp, cx + 70f.dp, cy + 70f.dp)
                    canvas.drawArc(r, 160f, 40f, false, p)
                    canvas.drawArc(r, -20f, 40f, false, p)
                    p.style = Paint.Style.FILL
                    // the pen
                    pen(canvas, cx + 92f.dp, cy + 54f.dp)
                }
                1 -> {
                    // the toolbar with a star being added
                    val w = 44f.dp; val left = cx + 60f.dp
                    p.color = 0xFF131316.toInt()
                    canvas.drawRoundRect(RectF(left, cy - 80f.dp, left + w, cy + 80f.dp), 14f.dp, 14f.dp, p)
                    val icons = listOf(Icon.SELECT, Icon.TRANSFORM, Icon.FILL, Icon.TEXT, Icon.SYMMETRY)
                    icons.forEachIndexed { i, ic ->
                        IconPainter.draw(canvas, ic, left + w / 2, cy - 58f.dp + i * 29f.dp, 20f.dp, if (i == 2) Ui.ACCENT else Ui.TEXT)
                    }
                    // a card with a star
                    p.color = 0xFF27282F.toInt()
                    val cardR = RectF(cx - 130f.dp, cy - 50f.dp, cx + 20f.dp, cy + 50f.dp)
                    canvas.drawRoundRect(cardR, 14f.dp, 14f.dp, p)
                    IconPainter.draw(canvas, Icon.FILL, cardR.centerX(), cardR.centerY(), 40f.dp, Ui.TEXT)
                    IconPainter.draw(canvas, Icon.STAR_FILLED, cardR.right - 16f.dp, cardR.top + 16f.dp, 18f.dp, Ui.ACCENT)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f.dp; p.color = 0x884C8DFF.toInt()
                    p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f.dp, 5f.dp), 0f)
                    canvas.drawLine(cardR.right + 6f.dp, cy, left - 6f.dp, cy, p)
                    p.pathEffect = null; p.style = Paint.Style.FILL
                }
                else -> {
                    // two fingers pinching
                    p.color = 0xFFFBF8F2.toInt()
                    canvas.drawRoundRect(RectF(cx - 110f.dp, cy - 66f.dp, cx + 110f.dp, cy + 66f.dp), 6f.dp, 6f.dp, p)
                    p.color = 0xFF2F6B8F.toInt()
                    canvas.drawCircle(cx + 50f.dp, cy - 20f.dp, 22f.dp, p)
                    p.color = 0x664C8DFF
                    canvas.drawCircle(cx - 40f.dp, cy + 10f.dp, 22f.dp, p)
                    canvas.drawCircle(cx + 10f.dp, cy + 40f.dp, 22f.dp, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f.dp; p.color = Ui.ACCENT
                    canvas.drawCircle(cx - 40f.dp, cy + 10f.dp, 22f.dp, p)
                    canvas.drawCircle(cx + 10f.dp, cy + 40f.dp, 22f.dp, p)
                    canvas.drawLine(cx - 66f.dp, cy - 16f.dp, cx - 82f.dp, cy - 32f.dp, p)
                    canvas.drawLine(cx + 36f.dp, cy + 66f.dp, cx + 52f.dp, cy + 82f.dp, p)
                    p.style = Paint.Style.FILL
                }
            }
        }

        private fun pen(canvas: Canvas, x: Float, y: Float) {
            canvas.save()
            canvas.rotate(-35f, x, y)
            p.color = 0xFF3A3A44.toInt()
            canvas.drawRoundRect(RectF(x - 6f.dp, y - 10f.dp, x + 6f.dp, y + 70f.dp), 6f.dp, 6f.dp, p)
            p.color = Ui.ACCENT
            canvas.drawRoundRect(RectF(x - 2.5f.dp, y + 12f.dp, x + 2.5f.dp, y + 24f.dp), 2f.dp, 2f.dp, p)
            canvas.restore()
        }
    }
}
