package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How pen pressure turns into brush pressure for the whole app: a smooth curve through points
 * you drag (start, two middle points, end). Also the app-wide stroke smoothing settings.
 */
object PressureCurve {
    /** x, y pairs from left to right; the first is the lightest touch, the last the hardest */
    val pts = floatArrayOf(0f, 0f, 0.33f, 0.33f, 0.66f, 0.66f, 1f, 1f)

    /** smooths every stroke a little or a lot (0..1), on top of each brush's own streamline */
    var stabilization = 0f
    /** takes out the shake of the hand (0..1) */
    var motionFiltering = 0f
    /** how much of the hand's own movement comes back after motion filtering (0..1) */
    var motionExpression = 0f

    private var prefs: SharedPreferences? = null

    fun load(p: SharedPreferences) {
        prefs = p
        val s = p.getString("pressurePts", null)
        val v = s?.split(',')?.mapNotNull { it.toFloatOrNull() }
        if (v != null && v.size == 8) v.toFloatArray().copyInto(pts)
        else {
            // older versions kept a single "softness" number
            val g = p.getFloat("pressureGamma", 1f)
            reset()
            if (g != 1f) { pts[3] = 0.33f.pow(g); pts[5] = 0.66f.pow(g) }
        }
        stabilization = p.getFloat("stabilization", 0f)
        motionFiltering = p.getFloat("motionFiltering", 0f)
        motionExpression = p.getFloat("motionExpression", 0f)
    }

    fun save() {
        prefs?.edit()
            ?.putString("pressurePts", pts.joinToString(","))
            ?.putFloat("stabilization", stabilization)
            ?.putFloat("motionFiltering", motionFiltering)
            ?.putFloat("motionExpression", motionExpression)
            ?.apply()
    }

    fun reset() {
        floatArrayOf(0f, 0f, 0.33f, 0.33f, 0.66f, 0.66f, 1f, 1f).copyInto(pts)
        stabilization = 0f; motionFiltering = 0f; motionExpression = 0f
    }

    val isLinear get() = abs(pts[0]) < 1e-3f && abs(pts[1]) < 1e-3f && abs(pts[6] - 1f) < 1e-3f && abs(pts[7] - 1f) < 1e-3f &&
        abs(pts[3] - pts[2]) < 1e-3f && abs(pts[5] - pts[4]) < 1e-3f

    /** brush pressure for pen pressure [p] (0..1): a monotone curve, so more pressure never gives less */
    fun map(p: Float): Float {
        if (isLinear) return p.coerceIn(0f, 1f)
        val n = 4
        val x = FloatArray(n) { pts[it * 2] }
        val y = FloatArray(n) { pts[it * 2 + 1] }
        val t = p.coerceIn(0f, 1f)
        if (t <= x[0]) return y[0].coerceIn(0f, 1f)
        if (t >= x[n - 1]) return y[n - 1].coerceIn(0f, 1f)
        // Fritsch–Carlson monotone cubic
        val d = FloatArray(n - 1) { (y[it + 1] - y[it]) / (x[it + 1] - x[it]).coerceAtLeast(1e-4f) }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) { val k = 3f / sqrt(s); m[i] = k * a * d[i]; m[i + 1] = k * b * d[i] }
        }
        var i = 0
        while (i < n - 2 && t > x[i + 1]) i++
        val h = (x[i + 1] - x[i]).coerceAtLeast(1e-4f)
        val u = (t - x[i]) / h
        val h00 = 2 * u * u * u - 3 * u * u + 1; val h10 = u * u * u - 2 * u * u + u
        val h01 = -2 * u * u * u + 3 * u * u; val h11 = u * u * u - u * u
        return (h00 * y[i] + h10 * h * m[i] + h01 * y[i + 1] + h11 * h * m[i + 1]).coerceIn(0f, 1f)
    }
}

/** The pressure curve as a square graph: drag the four points. */
@SuppressLint("ViewConstructor")
class PressureCurveView(context: Context, private val onChange: () -> Unit) : View(context) {
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f.dp; color = 0x26FFFFFF }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f.dp; color = 0xFFFFFFFF.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f.dp; color = 0xFFFFFFFF.toInt() }
    private val path = Path()
    private var drag = -1
    private val pad get() = 12f.dp

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    private fun sx(x: Float) = pad + x * (width - 2 * pad)
    private fun sy(y: Float) = height - pad - y * (height - 2 * pad)

    override fun onDraw(c: Canvas) {
        for (k in 0..4) {
            val f = k / 4f
            c.drawLine(sx(f), sy(0f), sx(f), sy(1f), grid)
            c.drawLine(sx(0f), sy(f), sx(1f), sy(f), grid)
        }
        path.reset()
        for (k in 0..64) {
            val x = k / 64f
            val y = PressureCurve.map(x)
            if (k == 0) path.moveTo(sx(x), sy(y)) else path.lineTo(sx(x), sy(y))
        }
        c.drawPath(path, line)
        val p = PressureCurve.pts
        for (i in 0 until 4) {
            c.drawCircle(sx(p[i * 2]), sy(p[i * 2 + 1]), 7f.dp, dot)
            c.drawCircle(sx(p[i * 2]), sy(p[i * 2 + 1]), 7f.dp, ring)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val p = PressureCurve.pts
        val x = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        val y = ((height - pad - e.y) / (height - 2 * pad)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = -1
                var best = 40f.dp
                for (i in 0 until 4) {
                    val d = hypot(sx(p[i * 2]) - e.x, sy(p[i * 2 + 1]) - e.y)
                    if (d < best) { best = d; drag = i }
                }
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) {
                // points keep their order from left to right
                val lo = if (drag == 0) 0f else p[(drag - 1) * 2] + 0.02f
                val hi = if (drag == 3) 1f else p[(drag + 1) * 2] - 0.02f
                p[drag * 2] = x.coerceIn(lo, min(hi, 1f).coerceAtLeast(lo))
                p[drag * 2 + 1] = y
                invalidate()
                onChange()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (drag >= 0) PressureCurve.save(); drag = -1 }
        }
        return true
    }
}
