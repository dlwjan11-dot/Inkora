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
    val PANEL = 0xF71C1C21.toInt()
    val PANEL_ROW = 0xFF2A2A31.toInt()
    val PANEL_ROW_ACTIVE = 0xFF233B63.toInt()
    val BAR = 0xF2131316.toInt()
    val ACCENT = 0xFF4C8DFF.toInt()
    val ACCENT_SOFT = 0x334C8DFF
    val TEXT = 0xFFF2F2F5.toInt()
    val TEXT_DIM = 0xFF9B9BA5.toInt()
    val CARD = 0xFF25252C.toInt()
    val CARD_HI = 0xFF2F2F38.toInt()
    val DIVIDER = 0x1FFFFFFF
    val DANGER = 0xFFFF6B6B.toInt()

    fun rounded(color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp.dp
        if (strokeDp > 0) setStroke(strokeDp.dp, strokeColor)
    }

    fun text(ctx: Context, s: String, sp: Float = 15f, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = L10n.tr(s)
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun button(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = L10n.tr(label)
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

    /** small uppercase section label */
    fun caption(ctx: Context, s: String) = text(ctx, s.uppercase(), 10.5f, TEXT_DIM, true).apply { letterSpacing = 0.08f }

    /** thin divider line */
    fun divider(ctx: Context) = View(ctx).apply { setBackgroundColor(DIVIDER) }

    /** pill-shaped button; [primary] = filled with the accent color */
    fun pill(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit) = button(ctx, label, onClick).apply {
        textSize = 13f
        setPadding(14.dp, 8.dp, 14.dp, 8.dp)
        background = rounded(if (primary) ACCENT else PANEL_ROW, 18f)
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
    V_DIRECT, V_PEN, V_PENCIL, V_RECT, V_ELLIPSE, V_POLYGON, V_STAR, V_LINE, PIN, VECTOR,
    HOME, MENU, FILL, ADJUST, SPARKLE, EDIT, STAR, STAR_FILLED, CLOSE, CHECK, SETTINGS, SHARE, PHOTO, FLIP, FIT, FOCUS,
    RULER, SHAPE, BLUR, INVERT, CONTRAST, NOISE, POSTERIZE, BW, SHARPEN, HUE, BALANCE,
    SEL_RECT, SEL_ELLIPSE, SEL_ALL, DESELECT, SEL_INVERT, FEATHER, ROTATE, CUSTOMIZE, DRAG, EXPORT, CLOCK, COPY,
    NAVIGATOR, ORB, REFERENCE, GLOBE, LIGHTBULB }

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
        stroke.pathEffect = null
        stroke.strokeCap = Paint.Cap.ROUND
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
            Icon.HOME -> {
                for ((x, y) in listOf(3.5f to 3.5f, 13.5f to 3.5f, 3.5f to 13.5f, 13.5f to 13.5f))
                    canvas.drawRoundRect(RectF(x, y, x + 7f, y + 7f), 1.8f, 1.8f, if (x == 3.5f && y == 3.5f) fill else stroke)
            }
            Icon.MENU -> {
                canvas.drawCircle(5f, 12f, 2f, fill); canvas.drawCircle(12f, 12f, 2f, fill); canvas.drawCircle(19f, 12f, 2f, fill)
            }
            Icon.FILL -> {
                path.reset()
                path.moveTo(3.5f, 11f); path.lineTo(10.5f, 4f); path.lineTo(17.5f, 11f); path.lineTo(10.5f, 18f); path.close()
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(3.5f, 11f); path.lineTo(17.5f, 11f); path.lineTo(10.5f, 18f); path.close()
                canvas.drawPath(path, fill)
                path.reset()
                path.moveTo(19.8f, 13f); path.lineTo(21.8f, 17f); path.lineTo(17.8f, 17f); path.close()
                canvas.drawPath(path, fill)
                canvas.drawCircle(19.8f, 17.6f, 2f, fill)
            }
            Icon.ADJUST -> {
                for ((x, k) in listOf(6f to 9f, 12f to 15.5f, 18f to 7f)) {
                    canvas.drawLine(x, 3.5f, x, 20.5f, stroke)
                    canvas.drawCircle(x, k, 2.6f, fill)
                }
            }
            Icon.SPARKLE -> {
                path.reset()
                path.moveTo(10f, 2.5f); path.quadTo(10.6f, 9.4f, 17.5f, 10f); path.quadTo(10.6f, 10.6f, 10f, 17.5f)
                path.quadTo(9.4f, 10.6f, 2.5f, 10f); path.quadTo(9.4f, 9.4f, 10f, 2.5f); path.close()
                canvas.drawPath(path, fill)
                path.reset()
                path.moveTo(18f, 13.5f); path.quadTo(18.3f, 16.7f, 21.5f, 17f); path.quadTo(18.3f, 17.3f, 18f, 20.5f)
                path.quadTo(17.7f, 17.3f, 14.5f, 17f); path.quadTo(17.7f, 16.7f, 18f, 13.5f); path.close()
                canvas.drawPath(path, fill)
            }
            Icon.EDIT -> {
                path.reset()
                path.moveTo(15.5f, 3.5f); path.lineTo(19.5f, 7.5f); path.lineTo(8f, 19f); path.lineTo(3.5f, 19.5f)
                path.lineTo(4f, 15f); path.close()
                canvas.drawPath(path, stroke)
                canvas.drawLine(13f, 6f, 17f, 10f, stroke)
                canvas.drawLine(12f, 20.5f, 20.5f, 20.5f, stroke)
            }
            Icon.STAR, Icon.STAR_FILLED -> {
                path.reset()
                for (k in 0 until 10) {
                    val a = Math.toRadians(-90.0 + 36.0 * k)
                    val r = if (k % 2 == 0) 9.5f else 4.2f
                    val x = 12f + r * Math.cos(a).toFloat(); val y = 12.8f + r * Math.sin(a).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, if (icon == Icon.STAR_FILLED) fill else stroke)
            }
            Icon.CLOSE -> {
                stroke.strokeWidth = 2.2f
                canvas.drawLine(6f, 6f, 18f, 18f, stroke); canvas.drawLine(18f, 6f, 6f, 18f, stroke)
            }
            Icon.CHECK -> {
                stroke.strokeWidth = 2.4f
                path.reset(); path.moveTo(4.5f, 12.5f); path.lineTo(9.5f, 17.5f); path.lineTo(19.5f, 6.5f)
                canvas.drawPath(path, stroke)
            }
            Icon.SETTINGS -> {
                canvas.drawCircle(12f, 12f, 3f, stroke)
                canvas.drawCircle(12f, 12f, 6.3f, stroke)
                stroke.strokeWidth = 2.8f
                stroke.strokeCap = Paint.Cap.BUTT
                for (k in 0 until 8) {
                    val a = Math.toRadians(22.5 + 45.0 * k)
                    val c = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
                    canvas.drawLine(12f + c * 6.2f, 12f + sn * 6.2f, 12f + c * 9.2f, 12f + sn * 9.2f, stroke)
                }
                stroke.strokeCap = Paint.Cap.ROUND
            }
            Icon.SHARE -> {
                canvas.drawLine(17.5f, 5.5f, 6.5f, 12f, stroke); canvas.drawLine(6.5f, 12f, 17.5f, 18.5f, stroke)
                canvas.drawCircle(17.5f, 5.5f, 2.6f, fill); canvas.drawCircle(6.5f, 12f, 2.6f, fill); canvas.drawCircle(17.5f, 18.5f, 2.6f, fill)
            }
            Icon.PHOTO -> {
                canvas.drawRoundRect(RectF(3f, 4.5f, 21f, 19.5f), 2.5f, 2.5f, stroke)
                path.reset(); path.moveTo(5f, 17.5f); path.lineTo(10f, 11.5f); path.lineTo(13.5f, 15f); path.lineTo(15.5f, 13f); path.lineTo(19f, 17.5f); path.close()
                canvas.drawPath(path, fill)
                canvas.drawCircle(16f, 8.8f, 1.7f, fill)
            }
            Icon.FLIP -> {
                stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f, 2f), 0f)
                canvas.drawLine(12f, 3f, 12f, 21f, stroke)
                stroke.pathEffect = null
                path.reset(); path.moveTo(9.5f, 6f); path.lineTo(9.5f, 18f); path.lineTo(3f, 18f); path.close()
                canvas.drawPath(path, fill)
                path.reset(); path.moveTo(14.5f, 6f); path.lineTo(14.5f, 18f); path.lineTo(21f, 18f); path.close()
                canvas.drawPath(path, stroke)
            }
            Icon.FIT -> {
                stroke.strokeWidth = 2f
                path.reset()
                path.moveTo(4f, 9f); path.lineTo(4f, 4f); path.lineTo(9f, 4f)
                path.moveTo(15f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 9f)
                path.moveTo(20f, 15f); path.lineTo(20f, 20f); path.lineTo(15f, 20f)
                path.moveTo(9f, 20f); path.lineTo(4f, 20f); path.lineTo(4f, 15f)
                canvas.drawPath(path, stroke)
                canvas.drawRoundRect(RectF(8.5f, 8.5f, 15.5f, 15.5f), 1.5f, 1.5f, fill)
            }
            Icon.FOCUS -> {
                stroke.strokeWidth = 2f
                path.reset()
                path.moveTo(9f, 3.5f); path.lineTo(9f, 9f); path.lineTo(3.5f, 9f)
                path.moveTo(15f, 3.5f); path.lineTo(15f, 9f); path.lineTo(20.5f, 9f)
                path.moveTo(20.5f, 15f); path.lineTo(15f, 15f); path.lineTo(15f, 20.5f)
                path.moveTo(9f, 20.5f); path.lineTo(9f, 15f); path.lineTo(3.5f, 15f)
                canvas.drawPath(path, stroke)
            }
            Icon.RULER -> {
                canvas.rotate(-45f, 12f, 12f)
                canvas.drawRoundRect(RectF(1.5f, 8.5f, 22.5f, 15.5f), 1.5f, 1.5f, stroke)
                for (k in 0 until 5) {
                    val x = 5.5f + k * 3.3f
                    canvas.drawLine(x, 8.5f, x, if (k % 2 == 0) 12.5f else 11f, stroke)
                }
            }
            Icon.SHAPE -> {
                canvas.drawCircle(9f, 9f, 5.8f, stroke)
                canvas.drawRoundRect(RectF(11.5f, 11.5f, 20.5f, 20.5f), 1.8f, 1.8f, fill)
            }
            Icon.BLUR -> {
                path.reset()
                path.moveTo(12f, 2.8f); path.cubicTo(16.5f, 8f, 19f, 11.5f, 19f, 14.5f)
                path.cubicTo(19f, 18.6f, 15.8f, 21.5f, 12f, 21.5f); path.cubicTo(8.2f, 21.5f, 5f, 18.6f, 5f, 14.5f)
                path.cubicTo(5f, 11.5f, 7.5f, 8f, 12f, 2.8f); path.close()
                canvas.drawPath(path, stroke)
                fill.alpha = alpha / 2
                canvas.drawCircle(12f, 15f, 4f, fill)
                fill.alpha = alpha
            }
            Icon.INVERT -> {
                canvas.drawRoundRect(RectF(4f, 4f, 20f, 20f), 2.5f, 2.5f, stroke)
                path.reset(); path.moveTo(20f, 4f); path.lineTo(20f, 20f); path.lineTo(4f, 20f); path.close()
                canvas.drawPath(path, fill)
            }
            Icon.CONTRAST -> {
                canvas.drawCircle(12f, 12f, 4.2f, fill)
                for (k in 0 until 8) {
                    val a = Math.toRadians(45.0 * k)
                    val c = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
                    canvas.drawLine(12f + c * 7f, 12f + sn * 7f, 12f + c * 9.5f, 12f + sn * 9.5f, stroke)
                }
            }
            Icon.NOISE -> {
                val pts = floatArrayOf(5f, 5f, 11f, 4f, 18f, 6f, 7f, 10f, 14f, 9f, 19f, 12f, 4f, 15f, 10f, 14.5f, 16f, 16f, 6f, 20f, 12f, 19.5f, 19f, 19.5f)
                var k = 0
                while (k < pts.size) { canvas.drawCircle(pts[k], pts[k + 1], 1.5f, fill); k += 2 }
            }
            Icon.POSTERIZE -> {
                canvas.drawRoundRect(RectF(4f, 15f, 20f, 19.5f), 1.2f, 1.2f, fill)
                fill.alpha = alpha * 2 / 3
                canvas.drawRoundRect(RectF(4f, 9.8f, 15f, 14.2f), 1.2f, 1.2f, fill)
                fill.alpha = alpha / 3
                canvas.drawRoundRect(RectF(4f, 4.5f, 10f, 9f), 1.2f, 1.2f, fill)
                fill.alpha = alpha
            }
            Icon.BW -> {
                canvas.drawCircle(12f, 12f, 8.5f, stroke)
                path.reset(); path.arcTo(RectF(3.5f, 3.5f, 20.5f, 20.5f), 90f, 180f); path.close()
                canvas.drawPath(path, fill)
            }
            Icon.SHARPEN -> {
                path.reset(); path.moveTo(12f, 3f); path.lineTo(21f, 20f); path.lineTo(3f, 20f); path.close()
                canvas.drawPath(path, stroke)
                path.reset(); path.moveTo(12f, 9f); path.lineTo(16.5f, 17.5f); path.lineTo(7.5f, 17.5f); path.close()
                canvas.drawPath(path, fill)
            }
            Icon.HUE -> {
                canvas.drawCircle(12f, 8.5f, 5.3f, stroke); canvas.drawCircle(8.5f, 14.8f, 5.3f, stroke); canvas.drawCircle(15.5f, 14.8f, 5.3f, stroke)
            }
            Icon.BALANCE -> {
                canvas.drawLine(12f, 4f, 12f, 20f, stroke); canvas.drawLine(8f, 20f, 16f, 20f, stroke); canvas.drawLine(5f, 7f, 19f, 7f, stroke)
                for (x in listOf(5f, 19f)) {
                    canvas.drawLine(x, 7f, x - 2.5f, 13f, stroke); canvas.drawLine(x, 7f, x + 2.5f, 13f, stroke)
                    path.reset(); path.moveTo(x - 3.2f, 13f); path.quadTo(x, 16.5f, x + 3.2f, 13f); path.close()
                    canvas.drawPath(path, fill)
                }
            }
            Icon.SEL_RECT, Icon.SEL_ELLIPSE, Icon.SEL_ALL, Icon.DESELECT -> {
                stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2.6f, 2.2f), 0f)
                when (icon) {
                    Icon.SEL_RECT -> canvas.drawRoundRect(RectF(4f, 6f, 20f, 18f), 1f, 1f, stroke)
                    Icon.SEL_ELLIPSE -> canvas.drawOval(RectF(3.5f, 6f, 20.5f, 18f), stroke)
                    else -> canvas.drawRoundRect(RectF(3.5f, 3.5f, 20.5f, 20.5f), 1f, 1f, stroke)
                }
                stroke.pathEffect = null
                if (icon == Icon.SEL_ALL) canvas.drawRoundRect(RectF(8f, 8f, 16f, 16f), 1.5f, 1.5f, fill)
                if (icon == Icon.DESELECT) { canvas.drawLine(8.5f, 8.5f, 15.5f, 15.5f, stroke); canvas.drawLine(15.5f, 8.5f, 8.5f, 15.5f, stroke) }
            }
            Icon.SEL_INVERT -> {
                path.reset()
                path.fillType = Path.FillType.EVEN_ODD
                path.addRoundRect(RectF(3.5f, 3.5f, 20.5f, 20.5f), 2f, 2f, Path.Direction.CW)
                path.addCircle(12f, 12f, 5f, Path.Direction.CW)
                canvas.drawPath(path, fill)
                path.fillType = Path.FillType.WINDING
            }
            Icon.FEATHER -> {
                path.reset()
                path.moveTo(20f, 4f); path.cubicTo(11f, 4f, 6f, 9f, 6.5f, 17.5f)
                path.cubicTo(14f, 18f, 20f, 13f, 20f, 4f); path.close()
                canvas.drawPath(path, stroke)
                canvas.drawLine(3.5f, 20.5f, 15f, 9f, stroke)
            }
            Icon.ROTATE -> {
                canvas.drawArc(RectF(4.5f, 4.5f, 19.5f, 19.5f), -60f, 290f, false, stroke)
                path.reset(); path.moveTo(15.5f, 2.5f); path.lineTo(16.6f, 6.7f); path.lineTo(12.3f, 7.6f)
                canvas.drawPath(path, stroke)
            }
            Icon.CUSTOMIZE -> {
                canvas.drawRoundRect(RectF(3.5f, 3.5f, 10.5f, 10.5f), 1.8f, 1.8f, stroke)
                canvas.drawRoundRect(RectF(13.5f, 3.5f, 20.5f, 10.5f), 1.8f, 1.8f, stroke)
                canvas.drawRoundRect(RectF(3.5f, 13.5f, 10.5f, 20.5f), 1.8f, 1.8f, stroke)
                stroke.strokeWidth = 2.2f
                canvas.drawLine(17f, 13.5f, 17f, 20.5f, stroke); canvas.drawLine(13.5f, 17f, 20.5f, 17f, stroke)
            }
            Icon.DRAG -> {
                for (x in listOf(9f, 15f)) for (y in listOf(6f, 12f, 18f)) canvas.drawCircle(x, y, 1.8f, fill)
            }
            Icon.EXPORT -> {
                path.reset(); path.moveTo(4f, 14f); path.lineTo(4f, 20f); path.lineTo(20f, 20f); path.lineTo(20f, 14f)
                canvas.drawPath(path, stroke)
                canvas.drawLine(12f, 3.5f, 12f, 15f, stroke)
                path.reset(); path.moveTo(7.5f, 8f); path.lineTo(12f, 3.5f); path.lineTo(16.5f, 8f)
                canvas.drawPath(path, stroke)
            }
            Icon.CLOCK -> {
                canvas.drawCircle(12f, 12f, 8.5f, stroke)
                canvas.drawLine(12f, 12f, 12f, 7f, stroke); canvas.drawLine(12f, 12f, 15.5f, 14f, stroke)
            }
            Icon.COPY -> {
                canvas.drawRoundRect(RectF(8f, 8f, 20f, 20f), 2f, 2f, stroke)
                path.reset(); path.moveTo(5f, 15.5f); path.lineTo(4f, 15.5f); path.lineTo(4f, 4f); path.lineTo(15.5f, 4f); path.lineTo(15.5f, 5f)
                canvas.drawPath(path, stroke)
            }
            Icon.NAVIGATOR -> {
                // a small map of the canvas with the visible area marked
                canvas.drawRoundRect(RectF(3f, 4.5f, 21f, 19.5f), 2f, 2f, stroke)
                canvas.drawRoundRect(RectF(10f, 8f, 17.5f, 14f), 1f, 1f, fill)
                stroke.strokeWidth = 1.2f
                canvas.drawLine(5.5f, 17f, 9f, 13.5f, stroke)
            }
            Icon.ORB -> {
                canvas.drawCircle(12f, 12f, 8.5f, stroke)
                fill.alpha = alpha / 3
                path.reset(); path.addCircle(12f, 12f, 8.5f, Path.Direction.CW)
                canvas.save(); canvas.clipPath(path)
                canvas.drawCircle(16f, 16f, 9f, fill)
                canvas.restore()
                fill.alpha = alpha
                canvas.drawCircle(9f, 9f, 2.2f, fill)
            }
            Icon.REFERENCE -> {
                // two stacked photos
                canvas.drawRoundRect(RectF(7f, 3.5f, 21f, 15f), 2f, 2f, stroke)
                canvas.drawRoundRect(RectF(3f, 8.5f, 17f, 20.5f), 2f, 2f, fill)
            }
            Icon.GLOBE -> {
                canvas.drawCircle(12f, 12f, 8.5f, stroke)
                canvas.drawOval(RectF(8f, 3.5f, 16f, 20.5f), stroke)
                canvas.drawLine(3.5f, 12f, 20.5f, 12f, stroke)
            }
            Icon.LIGHTBULB -> {
                path.reset()
                path.moveTo(9f, 17f); path.cubicTo(9f, 14f, 6f, 13f, 6f, 9.5f)
                path.cubicTo(6f, 6f, 8.7f, 3.5f, 12f, 3.5f); path.cubicTo(15.3f, 3.5f, 18f, 6f, 18f, 9.5f)
                path.cubicTo(18f, 13f, 15f, 14f, 15f, 17f); path.close()
                canvas.drawPath(path, stroke)
                canvas.drawLine(9.5f, 20.5f, 14.5f, 20.5f, stroke)
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
    /** small accent dot in the corner (e.g. "this setting is on") */
    var dot = false
        set(v) { field = v; invalidate() }
    /** icon size relative to the view */
    var iconScale = 0.58f
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressed = false

    init {
        isClickable = true
    }

    override fun setPressed(p: Boolean) {
        super.setPressed(p)
        if (p != pressed) { pressed = p; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val inset = 3f.dp
        val r = RectF(inset, inset, width - inset, height - inset)
        val rad = minOf(r.width(), r.height()) * 0.3f
        if (active) {
            bg.color = Ui.ACCENT_SOFT
            canvas.drawRoundRect(r, rad, rad, bg)
        } else if (pressed) {
            bg.color = 0x22FFFFFF
            canvas.drawRoundRect(r, rad, rad, bg)
        }
        IconPainter.draw(canvas, icon, width / 2f, height / 2f, minOf(width, height) * iconScale,
            if (active) Ui.ACCENT else tint, if (isEnabled) 255 else 80)
        if (dot) {
            bg.color = Ui.ACCENT
            canvas.drawCircle(width - inset - 4f.dp, inset + 4f.dp, 3f.dp, bg)
        }
    }
}

/** Live preview of the brush: its size (scaled down when big), color and opacity. */
class BrushDotView(context: Context) : View(context) {
    var sizePx = 10f
        set(v) { field = v; invalidate() }
    var color = 0xFF000000.toInt()
        set(v) { field = v; invalidate() }
    var opacity = 1f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val maxR = minOf(width, height) / 2f - 2f.dp
        // 1 px → tiny dot, 500 px → full circle (log scale, like the slider)
        val t = (Math.log10(sizePx.coerceIn(1f, 500f).toDouble()) / Math.log10(500.0)).toFloat()
        val r = 1.5f.dp + (maxR - 1.5f.dp) * t
        p.style = Paint.Style.FILL
        p.color = color
        p.alpha = (255 * opacity.coerceIn(0.08f, 1f)).toInt()
        canvas.drawCircle(width / 2f, height / 2f, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f.dp
        p.color = 0x99FFFFFF.toInt()
        canvas.drawCircle(width / 2f, height / 2f, r, p)
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
        message = L10n.tr(text)
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
