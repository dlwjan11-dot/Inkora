package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).roundToInt()
val Float.dp: Float get() = this * Resources.getSystem().displayMetrics.density

object Ui {
    val PANEL = 0xF51E1E22.toInt()
    val PANEL_ROW = 0xFF2A2A30.toInt()
    val PANEL_ROW_ACTIVE = 0xFF2F4F86.toInt()
    val BAR = 0xE6161619.toInt()
    val ACCENT = 0xFF4C8DFF.toInt()
    val TEXT = 0xFFF2F2F5.toInt()
    val TEXT_DIM = 0xFF9B9BA5.toInt()

    fun rounded(color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp.dp
        if (strokeDp > 0) setStroke(strokeDp.dp, strokeColor)
    }

    fun text(ctx: Context, s: String, sp: Float = 15f, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun button(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14f
        setTextColor(TEXT)
        gravity = Gravity.CENTER
        setPadding(12.dp, 8.dp, 12.dp, 8.dp)
        background = rounded(PANEL_ROW, 10f)
        setOnClickListener { onClick() }
    }

    fun seek(ctx: Context, value: Float, onChange: (Float) -> Unit) = SeekBar(ctx).apply {
        max = 1000
        progress = (value * 1000).roundToInt()
        progressTintList = ColorStateList.valueOf(ACCENT)
        thumbTintList = ColorStateList.valueOf(TEXT)
        progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p / 1000f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    /** "Label ......... [slider]" row with a live value. */
    fun sliderRow(ctx: Context, label: String, value: Float, format: (Float) -> String, onChange: (Float) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp, 6.dp, 4.dp, 2.dp)
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val name = text(ctx, label, 13f, TEXT_DIM)
        val v = text(ctx, format(value), 13f, TEXT)
        head.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(v)
        row.addView(head)
        row.addView(seek(ctx, value) { f -> v.text = format(f); onChange(f) }, LinearLayout.LayoutParams(-1, -2))
        return row
    }
}

enum class Icon { BRUSH, ERASER, LAYERS, ACTIONS, UNDO, REDO, SYMMETRY, EYEDROPPER, PLUS, EYE, EYE_OFF, UP, DOWN, LOCK, GRID,
    WAND, SELECT, TRANSFORM, SMUDGE, TEXT,
    V_DIRECT, V_PEN, V_PENCIL, V_RECT, V_ELLIPSE, V_POLYGON, V_STAR, V_LINE, PIN, VECTOR }

/** Simple vector icons drawn in code (24x24 design grid). */
/** Draws the app's vector icons (24x24 design grid) anywhere. */
object IconPainter {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** [sizePx] = full icon box size, centered on (cx, cy). */
    fun draw(canvas: Canvas, icon: Icon, cx: Float, cy: Float, sizePx: Float, color: Int, alpha: Int = 255) {
        val s = sizePx / 24f
        stroke.color = color
        fill.color = color
        stroke.alpha = alpha
        fill.alpha = alpha
        stroke.strokeWidth = 1.8f
        canvas.save()
        canvas.translate(cx - 12 * s, cy - 12 * s)
        canvas.scale(s, s)
        when (icon) {
            Icon.BRUSH -> {
                path.reset()
                path.moveTo(19f, 2.5f); path.lineTo(21.5f, 5f); path.lineTo(12.5f, 14.5f); path.lineTo(9.5f, 11.5f); path.close()
                canvas.drawPath(path, fill)
                path.reset()
                path.moveTo(8.5f, 12.8f)
                path.cubicTo(5.5f, 13f, 5f, 16.5f, 3f, 21f)
                path.cubicTo(7f, 20.5f, 11f, 18.5f, 11.2f, 15.5f)
                path.close()
                canvas.drawPath(path, fill)
            }
            Icon.ERASER -> {
                path.reset()
                path.moveTo(14f, 3.5f); path.lineTo(20.5f, 10f); path.lineTo(11f, 19.5f); path.lineTo(4.5f, 13f); path.close()
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(8f, 9.5f); path.lineTo(14.5f, 16f); path.lineTo(11f, 19.5f); path.lineTo(4.5f, 13f); path.close()
                canvas.drawPath(path, fill)
                canvas.drawLine(11f, 20f, 20f, 20f, stroke)
            }
            Icon.LAYERS -> {
                path.reset()
                path.moveTo(12f, 3.5f); path.lineTo(21f, 8.5f); path.lineTo(12f, 13.5f); path.lineTo(3f, 8.5f); path.close()
                canvas.drawPath(path, fill)
                path.reset()
                path.moveTo(3f, 12.5f); path.lineTo(12f, 17.5f); path.lineTo(21f, 12.5f)
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(3f, 16.5f); path.lineTo(12f, 21.5f); path.lineTo(21f, 16.5f)
                canvas.drawPath(path, stroke)
            }
            Icon.ACTIONS -> {
                canvas.drawLine(3f, 6f, 21f, 6f, stroke)
                canvas.drawLine(3f, 12f, 21f, 12f, stroke)
                canvas.drawLine(3f, 18f, 21f, 18f, stroke)
                canvas.drawCircle(8f, 6f, 2.4f, fill)
                canvas.drawCircle(16f, 12f, 2.4f, fill)
                canvas.drawCircle(10f, 18f, 2.4f, fill)
            }
            Icon.UNDO, Icon.REDO -> {
                if (icon == Icon.REDO) canvas.scale(-1f, 1f, 12f, 12f)
                path.reset()
                path.moveTo(5f, 9.5f); path.lineTo(15f, 9.5f)
                path.cubicTo(18.5f, 9.5f, 20.5f, 12f, 20.5f, 14.5f)
                path.cubicTo(20.5f, 17.5f, 18f, 19.5f, 15f, 19.5f)
                path.lineTo(9f, 19.5f)
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(9f, 5.5f); path.lineTo(5f, 9.5f); path.lineTo(9f, 13.5f)
                canvas.drawPath(path, stroke)
            }
            Icon.SYMMETRY -> {
                path.reset()
                path.moveTo(9.5f, 5f); path.lineTo(9.5f, 19f); path.lineTo(3f, 19f); path.close()
                canvas.drawPath(path, fill)
                path.reset()
                path.moveTo(14.5f, 5f); path.lineTo(14.5f, 19f); path.lineTo(21f, 19f); path.close()
                canvas.drawPath(path, stroke)
                canvas.drawLine(12f, 2f, 12f, 4f, stroke)
                canvas.drawLine(12f, 7f, 12f, 9f, stroke)
                canvas.drawLine(12f, 12f, 12f, 14f, stroke)
                canvas.drawLine(12f, 17f, 12f, 19f, stroke)
                canvas.drawLine(12f, 21.5f, 12f, 22f, stroke)
            }
            Icon.EYEDROPPER -> {
                stroke.strokeWidth = 2.6f
                canvas.drawLine(4.5f, 19.5f, 13f, 11f, stroke)
                stroke.strokeWidth = 1.8f
                canvas.drawCircle(16.5f, 7.5f, 3.6f, fill)
                canvas.drawLine(11.5f, 8f, 16f, 12.5f, stroke)
            }
            Icon.PLUS -> {
                stroke.strokeWidth = 2.2f
                canvas.drawLine(12f, 4f, 12f, 20f, stroke)
                canvas.drawLine(4f, 12f, 20f, 12f, stroke)
            }
            Icon.EYE, Icon.EYE_OFF -> {
                path.reset()
                path.moveTo(2.5f, 12f)
                path.quadTo(12f, 2f, 21.5f, 12f)
                path.quadTo(12f, 22f, 2.5f, 12f)
                canvas.drawPath(path, stroke)
                canvas.drawCircle(12f, 12f, 3f, fill)
                if (icon == Icon.EYE_OFF) {
                    stroke.strokeWidth = 2.2f
                    canvas.drawLine(4f, 20f, 20f, 4f, stroke)
                }
            }
            Icon.UP, Icon.DOWN -> {
                if (icon == Icon.DOWN) canvas.scale(1f, -1f, 12f, 12f)
                stroke.strokeWidth = 2.2f
                path.reset()
                path.moveTo(5f, 15f); path.lineTo(12f, 8f); path.lineTo(19f, 15f)
                canvas.drawPath(path, stroke)
            }
            Icon.WAND -> {
                stroke.strokeWidth = 2.4f
                canvas.drawLine(4f, 20f, 14.5f, 9.5f, stroke)
                stroke.strokeWidth = 1.6f
                canvas.drawLine(18f, 2.5f, 18f, 7.5f, stroke)
                canvas.drawLine(15.5f, 5f, 20.5f, 5f, stroke)
                canvas.drawLine(20.5f, 11f, 20.5f, 14f, stroke)
                canvas.drawLine(19f, 12.5f, 22f, 12.5f, stroke)
                canvas.drawCircle(10f, 4f, 1.2f, fill)
            }
            Icon.SELECT -> {
                stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2.6f, 2.2f), 0f)
                path.reset()
                path.moveTo(5f, 9f)
                path.cubicTo(5f, 3f, 19f, 3f, 19f, 9f)
                path.cubicTo(19f, 14f, 12f, 14f, 10f, 13f)
                path.cubicTo(6f, 12f, 5f, 11f, 5f, 9f)
                canvas.drawPath(path, stroke)
                stroke.pathEffect = null
                path.reset()
                path.moveTo(10f, 13f)
                path.cubicTo(8f, 15f, 9f, 18f, 11f, 21f)
                canvas.drawPath(path, stroke)
            }
            Icon.TRANSFORM -> {
                path.reset()
                path.moveTo(6f, 3f); path.lineTo(6f, 19f); path.lineTo(10f, 15.2f); path.lineTo(12.8f, 21f)
                path.lineTo(15.3f, 19.8f); path.lineTo(12.6f, 14.2f); path.lineTo(18f, 14f); path.close()
                canvas.drawPath(path, fill)
            }
            Icon.SMUDGE -> {
                path.reset()
                path.moveTo(3f, 19f)
                path.cubicTo(7f, 19.5f, 9f, 14f, 13f, 12f)
                path.cubicTo(16f, 10.5f, 18f, 8f, 21f, 4f)
                path.cubicTo(19f, 10f, 16.5f, 14f, 13.5f, 16f)
                path.cubicTo(10f, 18.5f, 7f, 20.5f, 3f, 19f)
                path.close()
                canvas.drawPath(path, fill)
                canvas.drawCircle(5.5f, 7f, 2.2f, stroke)
            }
            Icon.TEXT -> {
                stroke.strokeWidth = 2.4f
                canvas.drawLine(5f, 5f, 19f, 5f, stroke)
                canvas.drawLine(12f, 5f, 12f, 20f, stroke)
                canvas.drawLine(9f, 20f, 15f, 20f, stroke)
            }
            Icon.V_DIRECT -> {
                path.reset()
                path.moveTo(6f, 3f); path.lineTo(6f, 19f); path.lineTo(10f, 15.2f); path.lineTo(12.8f, 21f)
                path.lineTo(15.3f, 19.8f); path.lineTo(12.6f, 14.2f); path.lineTo(18f, 14f); path.close()
                canvas.drawPath(path, stroke)
            }
            Icon.V_PEN -> {
                path.reset()
                path.moveTo(12f, 2.5f); path.lineTo(18.5f, 11f); path.lineTo(14.5f, 20f)
                path.lineTo(9.5f, 20f); path.lineTo(5.5f, 11f); path.close()
                canvas.drawPath(path, stroke)
                canvas.drawLine(12f, 2.5f, 12f, 11f, stroke)
                canvas.drawCircle(12f, 12.5f, 1.6f, fill)
                canvas.drawLine(8.5f, 22f, 15.5f, 22f, stroke)
            }
            Icon.V_PENCIL -> {
                path.reset()
                path.moveTo(16.5f, 3.5f); path.lineTo(20.5f, 7.5f); path.lineTo(8f, 20f); path.lineTo(3.5f, 20.5f)
                path.lineTo(4f, 16f); path.close()
                canvas.drawPath(path, stroke)
                canvas.drawLine(14f, 6f, 18f, 10f, stroke)
            }
            Icon.V_RECT -> canvas.drawRoundRect(RectF(4f, 6f, 20f, 18f), 1.5f, 1.5f, stroke)
            Icon.V_ELLIPSE -> canvas.drawOval(RectF(3.5f, 6f, 20.5f, 18f), stroke)
            Icon.V_POLYGON -> {
                path.reset()
                for (k in 0 until 6) {
                    val a = Math.toRadians(-90.0 + 60.0 * k)
                    val x = 12f + 8.5f * Math.cos(a).toFloat(); val y = 12.5f + 8.5f * Math.sin(a).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, stroke)
            }
            Icon.V_STAR -> {
                path.reset()
                for (k in 0 until 10) {
                    val a = Math.toRadians(-90.0 + 36.0 * k)
                    val r = if (k % 2 == 0) 9.5f else 4f
                    val x = 12f + r * Math.cos(a).toFloat(); val y = 13f + r * Math.sin(a).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, stroke)
            }
            Icon.V_LINE -> {
                canvas.drawLine(4.5f, 19.5f, 19.5f, 4.5f, stroke)
                canvas.drawCircle(4.5f, 19.5f, 1.8f, fill)
                canvas.drawCircle(19.5f, 4.5f, 1.8f, fill)
            }
            Icon.PIN -> {
                path.reset()
                path.moveTo(9f, 3f); path.lineTo(15f, 3f); path.lineTo(14f, 10f); path.lineTo(17.5f, 14f)
                path.lineTo(6.5f, 14f); path.lineTo(10f, 10f); path.close()
                canvas.drawPath(path, fill)
                stroke.strokeWidth = 2f
                canvas.drawLine(12f, 14f, 12f, 21f, stroke)
            }
            Icon.VECTOR -> {
                path.reset()
                path.moveTo(4f, 19f)
                path.cubicTo(6f, 6f, 18f, 18f, 20f, 5f)
                canvas.drawPath(path, stroke)
                fill.alpha = 255
                canvas.drawRect(2.5f, 17.5f, 5.5f, 20.5f, fill)
                canvas.drawRect(18.5f, 3.5f, 21.5f, 6.5f, fill)
                canvas.drawLine(4f, 19f, 8f, 11f, stroke)
                canvas.drawCircle(8f, 11f, 1.4f, fill)
            }
            Icon.GRID -> {
                canvas.drawRoundRect(RectF(3.5f, 3.5f, 20.5f, 20.5f), 2.5f, 2.5f, stroke)
                canvas.drawLine(9.2f, 3.5f, 9.2f, 20.5f, stroke)
                canvas.drawLine(14.8f, 3.5f, 14.8f, 20.5f, stroke)
                canvas.drawLine(3.5f, 9.2f, 20.5f, 9.2f, stroke)
                canvas.drawLine(3.5f, 14.8f, 20.5f, 14.8f, stroke)
            }
            Icon.LOCK -> {
                canvas.drawRoundRect(RectF(5f, 10.5f, 19f, 20.5f), 2f, 2f, fill)
                path.reset()
                path.moveTo(8f, 10.5f); path.lineTo(8f, 7.5f)
                path.cubicTo(8f, 3f, 16f, 3f, 16f, 7.5f)
                path.lineTo(16f, 10.5f)
                canvas.drawPath(path, stroke)
            }
        }
        canvas.restore()
    }
}

/** Toolbar button showing one icon. */
class IconView(context: Context, var icon: Icon) : View(context) {
    var active = false
        set(v) { field = v; invalidate() }
    var tint = Ui.TEXT
        set(v) { field = v; invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        if (active) {
            bg.color = 0x334C8DFF
            canvas.drawRoundRect(RectF(2.dp.toFloat(), 2.dp.toFloat(), width - 2f.dp, height - 2f.dp), 10f.dp, 10f.dp, bg)
        }
        IconPainter.draw(canvas, icon, width / 2f, height / 2f, minOf(width, height) * 0.62f,
            if (active) Ui.ACCENT else tint, if (isEnabled) 255 else 90)
    }
}

/** Round button showing the current color. */
class ColorSwatchView(context: Context) : View(context) {
    var color = 0xFF000000.toInt()
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f - 7f.dp
        p.style = Paint.Style.FILL
        p.color = color
        canvas.drawCircle(width / 2f, height / 2f, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f.dp
        p.color = 0xCCFFFFFF.toInt()
        canvas.drawCircle(width / 2f, height / 2f, r + 1f.dp, p)
    }
}

/** Vertical slider like the side bar of most painting apps. Value 0..1, top = 1. */
class VerticalSlider(context: Context) : View(context) {
    var value = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onChange: ((Float) -> Unit)? = null
    var onDragState: ((Boolean) -> Unit)? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragStartY = 0f
    private var dragStartValue = 0f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val tw = 8f.dp
        track.color = 0x55FFFFFF
        canvas.drawRoundRect(RectF(w / 2 - tw / 2, 10f.dp, w / 2 + tw / 2, h - 10f.dp), tw / 2, tw / 2, track)
        val y = thumbY()
        track.color = 0xAAFFFFFF.toInt()
        canvas.drawRoundRect(RectF(w / 2 - tw / 2, y, w / 2 + tw / 2, h - 10f.dp), tw / 2, tw / 2, track)
        thumb.color = Ui.TEXT
        thumb.setShadowLayer(4f.dp, 0f, 1f.dp, 0x88000000.toInt())
        canvas.drawRoundRect(RectF(w / 2 - 12f.dp, y - 9f.dp, w / 2 + 12f.dp, y + 9f.dp), 6f.dp, 6f.dp, thumb)
    }

    private fun thumbY(): Float {
        val top = 19f.dp
        val bottom = height - 19f.dp
        return bottom - (bottom - top) * value
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val usable = max(1f, height - 38f.dp)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStartY = e.y
                dragStartValue = value
                onDragState?.invoke(true)
            }
            MotionEvent.ACTION_MOVE -> {
                value = dragStartValue - (e.y - dragStartY) / usable
                onChange?.invoke(value)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onDragState?.invoke(false)
        }
        return true
    }
}

/** Small message pill + optional brush size preview, drawn over the canvas. */
class HudView(context: Context) : View(context) {
    private var message: String? = null
    private var circle = 0f
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT
        textSize = 15f.dp
        textAlign = Paint.Align.CENTER
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0141416.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hide = Runnable { message = null; circle = 0f; invalidate() }

    fun show(text: String, circleDiameterPx: Float = 0f, sticky: Boolean = false) {
        message = text
        circle = circleDiameterPx
        removeCallbacks(hide)
        if (!sticky) postDelayed(hide, 1100)
        invalidate()
    }

    fun hideSoon() {
        removeCallbacks(hide)
        postDelayed(hide, 500)
    }

    override fun onDraw(canvas: Canvas) {
        val m = message ?: return
        val tw = textPaint.measureText(m)
        val cx = width / 2f
        val top = 76f.dp
        canvas.drawRoundRect(RectF(cx - tw / 2 - 16f.dp, top, cx + tw / 2 + 16f.dp, top + 36f.dp), 18f.dp, 18f.dp, pill)
        canvas.drawText(m, cx, top + 24f.dp, textPaint)
        if (circle > 0f) {
            val r = circle / 2f
            ring.strokeWidth = 3f.dp
            ring.color = 0x66000000
            canvas.drawCircle(cx, height / 2f, r, ring)
            ring.strokeWidth = 1.5f.dp
            ring.color = 0xEEFFFFFF.toInt()
            canvas.drawCircle(cx, height / 2f, r, ring)
        }
    }
}
