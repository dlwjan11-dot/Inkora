package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

enum class HarmonyMode(val label: String) {
    NONE("None"),
    COMPLEMENTARY("Complementary"),
    TRIADIC("Triadic"),
    ANALOGOUS("Analogous"),
    SPLIT_COMPLEMENTARY("Split-Comp"),
    TETRADIC("Tetradic")
}

/**
 * The standard color wheel: a hue ring with a saturation/brightness square inside.
 * Drawn into any canvas (the pen quick menu uses it) or shown with [ColorDiscView].
 * Red sits at the top, hues go clockwise.
 */
class ColorDisc {
    /** HSV, hue kept even for grays so the ring doesn't jump */
    var hue = 0f
        private set
    var sat = 0f
        private set
    var value = 0f
        private set
    var harmony = HarmonyMode.NONE

    val color: Int get() = Color.HSVToColor(floatArrayOf(hue, sat, value))

    fun getHarmonyHues(): List<Float> {
        return when (harmony) {
            HarmonyMode.NONE -> emptyList()
            HarmonyMode.COMPLEMENTARY -> listOf((hue + 180f) % 360f)
            HarmonyMode.TRIADIC -> listOf((hue + 120f) % 360f, (hue + 240f) % 360f)
            HarmonyMode.ANALOGOUS -> listOf((hue + 30f) % 360f, (hue + 330f) % 360f)
            HarmonyMode.SPLIT_COMPLEMENTARY -> listOf((hue + 150f) % 360f, (hue + 210f) % 360f)
            HarmonyMode.TETRADIC -> listOf((hue + 90f) % 360f, (hue + 180f) % 360f, (hue + 270f) % 360f)
        }
    }

    // geometry (set by layout())
    var cx = 0f; private set
    var cy = 0f; private set
    var rOut = 0f; private set
    private var rIn = 0f
    private val sq = RectF()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var sweep: SweepGradient? = null
    private var sweepKey = ""

    private enum class Zone { NONE, RING, SQUARE }
    private var zone = Zone.NONE

    fun set(c: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(c or 0xFF000000.toInt(), hsv)
        if (hsv[1] > 0.01f && hsv[2] > 0.01f) hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
    }

    /** [outer] = radius of the hue ring, [ringWidth] = its thickness */
    fun layout(x: Float, y: Float, outer: Float, ringWidth: Float) {
        cx = x; cy = y; rOut = outer
        rIn = outer - ringWidth
        val half = (rIn - ringWidth * 0.45f) / sqrt(2f)
        sq.set(cx - half, cy - half, cx + half, cy + half)
    }

    fun contains(x: Float, y: Float) = hypot(x - cx, y - cy) <= rOut + 6f.dp

    fun draw(canvas: Canvas, alpha: Int = 255) {
        val ringW = rOut - rIn
        val key = "$cx/$cy"
        if (sweep == null || key != sweepKey) {
            // SweepGradient starts at 3 o'clock; red must sit at 12 o'clock
            val hues = IntArray(13) { Color.HSVToColor(floatArrayOf((it * 30f + 90f) % 360f, 1f, 1f)) }
            sweep = SweepGradient(cx, cy, hues, null)
            sweepKey = key
        }
        ringPaint.shader = sweep
        ringPaint.strokeWidth = ringW
        ringPaint.alpha = alpha
        canvas.drawCircle(cx, cy, (rOut + rIn) / 2f, ringPaint)
        ringPaint.shader = null

        // saturation (left → right) × brightness (top → bottom)
        val pure = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        val s = LinearGradient(sq.left, 0f, sq.right, 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
        val v = LinearGradient(0f, sq.top, 0f, sq.bottom, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
        fill.shader = ComposeShader(s, v, PorterDuff.Mode.MULTIPLY)
        fill.alpha = alpha
        canvas.drawRoundRect(sq, 6f.dp, 6f.dp, fill)
        fill.shader = null

        // hue handle
        val a = Math.toRadians((hue - 90f).toDouble())
        val rm = (rOut + rIn) / 2f
        val mainX = cx + (cos(a) * rm).toFloat()
        val mainY = cy + (sin(a) * rm).toFloat()

        // harmony lines and nodes
        for (secHue in getHarmonyHues()) {
            val secA = Math.toRadians((secHue - 90f).toDouble())
            val secX = cx + (cos(secA) * rm).toFloat()
            val secY = cy + (sin(secA) * rm).toFloat()

            line.color = 0xAAFFFFFF.toInt()
            line.strokeWidth = 2f.dp
            line.alpha = alpha
            canvas.drawLine(mainX, mainY, secX, secY, line)

            val secColor = Color.HSVToColor(floatArrayOf(secHue, sat, value))
            handle(canvas, secX, secY, ringW / 2.5f, secColor, alpha)
        }

        handle(canvas, mainX, mainY, ringW / 2f + 1.5f.dp, pure, alpha)
        // color handle
        handle(canvas, sq.left + sat * sq.width(), sq.top + (1f - value) * sq.height(), 9f.dp, color, alpha)
    }

    private fun handle(canvas: Canvas, x: Float, y: Float, r: Float, inside: Int, alpha: Int) {
        fill.color = inside
        fill.alpha = alpha
        canvas.drawCircle(x, y, r, fill)
        line.color = Color.WHITE
        line.alpha = alpha
        line.strokeWidth = 3f.dp
        canvas.drawCircle(x, y, r, line)
        line.color = 0x55000000
        line.alpha = alpha * 0x55 / 255
        line.strokeWidth = 1f.dp
        canvas.drawCircle(x, y, r + 2f.dp, line)
    }

    /** returns true when the touch belongs to the wheel (and the color may have changed) */
    fun touch(x: Float, y: Float, action: Int): Boolean {
        if (action == MotionEvent.ACTION_DOWN) {
            val d = hypot(x - cx, y - cy)
            val slop = 10f.dp
            zone = when {
                d in (rIn - 6f.dp)..(rOut + slop) -> Zone.RING
                x in (sq.left - slop)..(sq.right + slop) && y in (sq.top - slop)..(sq.bottom + slop) -> Zone.SQUARE
                else -> Zone.NONE
            }
        }
        val z = zone
        when (z) {
            Zone.RING -> {
                var a = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat() + 90f
                while (a < 0) a += 360f
                while (a >= 360f) a -= 360f
                hue = a
            }
            Zone.SQUARE -> {
                sat = ((x - sq.left) / sq.width()).coerceIn(0f, 1f)
                value = 1f - ((y - sq.top) / sq.height()).coerceIn(0f, 1f)
            }
            Zone.NONE -> return false
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) zone = Zone.NONE
        return true
    }

    val dragging get() = zone != Zone.NONE

    /**
     * A double tap: on the ring, the nearest pure hue (every 30°, full strength); in the square,
     * the nearest even step (quarters) of saturation and brightness.
     */
    fun snap(x: Float, y: Float): Boolean {
        val d = hypot(x - cx, y - cy)
        return when {
            d in (rIn - 6f.dp)..(rOut + 10f.dp) -> {
                var a = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat() + 90f
                while (a < 0) a += 360f
                hue = (Math.round(a / 30f) * 30f) % 360f
                sat = 1f; value = 1f
                true
            }
            x in sq.left..sq.right && y in sq.top..sq.bottom -> {
                sat = Math.round(((x - sq.left) / sq.width()) * 4f) / 4f
                value = Math.round((1f - (y - sq.top) / sq.height()) * 4f) / 4f
                true
            }
            else -> false
        }
    }
}

/** A standalone standard color wheel. */
@SuppressLint("ViewConstructor")
class ColorDiscView(context: Context, initial: Int) : View(context) {
    val disc = ColorDisc().apply { set(initial) }
    var onColor: ((Int) -> Unit)? = null
    /** finger lifted: a good moment to remember the color */
    var onCommit: ((Int) -> Unit)? = null

    fun setColor(c: Int) { disc.set(c); invalidate() }
    private var snapping = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w.coerceAtMost(300.dp))
    }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f - 4f.dp
        disc.layout(width / 2f, height / 2f, r, (r * 0.17f).coerceIn(14f.dp, 26f.dp))
        disc.draw(canvas)
    }

    private var lastUpAt = 0L
    private var lastUpX = 0f
    private var lastUpY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val a = e.actionMasked
        if (a == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (a == MotionEvent.ACTION_DOWN && e.eventTime - lastUpAt < 320 && hypot(e.x - lastUpX, e.y - lastUpY) < 24f.dp && disc.snap(e.x, e.y)) {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onColor?.invoke(disc.color)
            onCommit?.invoke(disc.color)
            invalidate()
            lastUpAt = 0L
            snapping = true
            return true
        }
        if (snapping) { if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) snapping = false; return true }
        if (a == MotionEvent.ACTION_UP) { lastUpAt = e.eventTime; lastUpX = e.x; lastUpY = e.y }
        if (disc.touch(e.x, e.y, a)) {
            onColor?.invoke(disc.color)
            invalidate()
        }
        if (a == MotionEvent.ACTION_UP) onCommit?.invoke(disc.color)
        return true
    }
}
