package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class Symmetry(val label: String, val axes: Int) {
    OFF("Off", 0),
    VERTICAL("Vertical", 1),
    HORIZONTAL("Horizontal", 1),
    QUADRANT("Quadrant", 2),
    RADIAL("Radial (8)", 8),
    MANDALA_12("Mandala (12)", 12),
    MANDALA_16("Mandala (16)", 16)
}

/** A recorded input sample (document coordinates). */
class InputPoint(val x: Float, val y: Float, val pressure: Float, val tilt: Float)

/**
 * Turns pen input into brush "dabs" on a stroke bitmap.
 * The stroke bitmap is later merged into the layer, so one stroke has an even opacity.
 */
class StrokeEngine {
    var brush: Brush = Brush(Brushes.presets[0])
    var color: Int = 0xFF000000.toInt()
    var symmetry = Symmetry.OFF
    var centerX = 0f
    var centerY = 0f
    var radialCount = 8

    /** Union of everything drawn by the current stroke. */
    val dirty = RectF()

    /** When set, dabs are handed to this function (x, y, diameter, alpha, angle) instead of being drawn. */
    var dabHandler: ((Float, Float, Float, Float, Float) -> Unit)? = null

    private var canvas: Canvas? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private var random = Random(1)

    // smoothing + interpolation state
    private var smX = 0f
    private var smY = 0f
    private var smP = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastP = 0f
    private var lastTilt = 0f
    private var distToNext = 0f
    private var traveled = 0f
    private var lastAngle = 0f
    private var rawLast: InputPoint? = null
    private var smoothing = true

    fun begin(target: Bitmap, p: InputPoint, smooth: Boolean = true, seed: Int = Random.nextInt()) {
        canvas = Canvas(target)
        random = Random(seed)
        smoothing = smooth
        dirty.setEmpty()
        smX = p.x; smY = p.y; smP = p.pressure
        lastX = p.x; lastY = p.y; lastP = p.pressure; lastTilt = p.tilt
        traveled = 0f
        lastAngle = 0f
        rawLast = p
        stamp(p.x, p.y, p.pressure, p.tilt, 0f)
        distToNext = spacingFor(diameterFor(p.pressure, p.tilt, 0f))
    }

    fun move(p: InputPoint) {
        rawLast = p
        val k = if (smoothing) 1f - brush.streamline.coerceIn(0f, 1f) * 0.92f else 1f
        smX += (p.x - smX) * k
        smY += (p.y - smY) * k
        smP += (p.pressure - smP) * (if (smoothing) 0.6f else 1f)
        segmentTo(smX, smY, smP, p.tilt)
    }

    /** Catch up with the pen (streamline lag) at the end of the stroke. */
    fun end() {
        val r = rawLast ?: return
        if (smoothing && brush.streamline > 0.01f) {
            val steps = 6
            val sx = smX; val sy = smY; val sp = smP
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                segmentTo(sx + (r.x - sx) * t, sy + (r.y - sy) * t, sp + (r.pressure - sp) * t, r.tilt)
            }
        }
        canvas = null
    }

    /** Re-draws a stroke from a list of points (used by QuickShape and brush previews). */
    fun renderPoints(target: Bitmap, pts: List<PointF>, pressure: Float, seed: Int) {
        if (pts.isEmpty()) return
        begin(target, InputPoint(pts[0].x, pts[0].y, pressure, 0f), smooth = false, seed = seed)
        for (i in 1 until pts.size) move(InputPoint(pts[i].x, pts[i].y, pressure, 0f))
        end()
    }

    fun renderPressurePoints(target: Bitmap, pts: List<InputPoint>, seed: Int) {
        if (pts.isEmpty()) return
        begin(target, pts[0], smooth = false, seed = seed)
        for (i in 1 until pts.size) move(pts[i])
        end()
    }

    private fun segmentTo(x: Float, y: Float, p: Float, tilt: Float) {
        val dx = x - lastX
        val dy = y - lastY
        val len = hypot(dx, dy)
        if (len < 0.01f) {
            lastP = p
            return
        }
        val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        var d = distToNext
        while (d <= len) {
            val t = d / len
            val px = lastX + dx * t
            val py = lastY + dy * t
            val pp = lastP + (p - lastP) * t
            val pt = lastTilt + (tilt - lastTilt) * t
            stamp(px, py, pp, pt, traveled + d, angle)
            d += spacingFor(diameterFor(pp, pt, traveled + d))
        }
        distToNext = d - len
        traveled += len
        lastX = x; lastY = y; lastP = p; lastTilt = tilt
        lastAngle = angle
    }

    private fun spacingFor(diameter: Float) = max(0.35f, diameter * brush.spacing.coerceAtLeast(0.01f))

    private fun taperFactor(dist: Float): Float {
        val t = brush.preset.taper
        if (t <= 0f) return 1f
        val len = t * brush.size
        if (len <= 0f) return 1f
        val f = (dist / len).coerceIn(0f, 1f)
        return 0.15f + 0.85f * (f * f * (3 - 2 * f))
    }

    private fun tiltAmount(tilt: Float): Float {
        if (!brush.preset.tiltShading) return 0f
        // tilt is radians from perpendicular; shading starts around 35 degrees
        return ((tilt - 0.6f) / 0.6f).coerceIn(0f, 1f)
    }

    private fun diameterFor(pressure: Float, tilt: Float, dist: Float): Float {
        val pc = pressure.coerceIn(0f, 1f).pow(brush.preset.pressureCurve)
        val sizeF = brush.minSize + (1f - brush.minSize) * pc
        var d = brush.size * sizeF * taperFactor(dist)
        d *= 1f + 2.5f * tiltAmount(tilt)
        return max(0.5f, d)
    }

    private fun stamp(x: Float, y: Float, pressure: Float, tilt: Float, dist: Float, dirAngle: Float = lastAngle) {
        val c = canvas ?: return
        var d = diameterFor(pressure, tilt, dist)
        val pc = pressure.coerceIn(0f, 1f)
        var alpha = brush.flow * (brush.minFlow + (1f - brush.minFlow) * pc)
        alpha *= 1f - 0.6f * tiltAmount(tilt)
        if (d < 1f) {
            alpha *= d
            d = 1f
        }
        var cx = x
        var cy = y
        if (brush.scatter > 0f) {
            val a = random.nextFloat() * 6.2831855f
            val r = random.nextFloat() * brush.scatter * d
            cx += cos(a) * r
            cy += sin(a) * r
        }
        val angle = when (brush.rotation) {
            RotationMode.FIXED -> brush.angle
            RotationMode.FOLLOW -> dirAngle + 90f + brush.angle
            RotationMode.RANDOM -> random.nextFloat() * 360f
            RotationMode.NONE -> 0f
        }
        val handler = dabHandler
        if (handler != null) {
            // e.g. Smudge: the caller paints the dab itself
            handler(cx, cy, d, alpha, angle)
            val r = d * 0.75f + 2f
            if (dirty.isEmpty) dirty.set(cx - r, cy - r, cx + r, cy + r) else dirty.union(cx - r, cy - r, cx + r, cy + r)
            return
        }
        paint.color = color
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        if (paint.alpha == 0) return
        val tip = brush.tip()

        when (symmetry) {
            Symmetry.OFF -> dab(c, tip, cx, cy, d, angle)
            Symmetry.VERTICAL -> {
                dab(c, tip, cx, cy, d, angle)
                dab(c, tip, 2 * centerX - cx, cy, d, 180f - angle)
            }
            Symmetry.HORIZONTAL -> {
                dab(c, tip, cx, cy, d, angle)
                dab(c, tip, cx, 2 * centerY - cy, d, -angle)
            }
            Symmetry.QUADRANT -> {
                dab(c, tip, cx, cy, d, angle)
                dab(c, tip, 2 * centerX - cx, cy, d, 180f - angle)
                dab(c, tip, cx, 2 * centerY - cy, d, -angle)
                dab(c, tip, 2 * centerX - cx, 2 * centerY - cy, d, angle + 180f)
            }
            Symmetry.RADIAL, Symmetry.MANDALA_12, Symmetry.MANDALA_16 -> {
                val n = symmetry.axes
                val ox = cx - centerX
                val oy = cy - centerY
                for (k in 0 until n) {
                    val a = (Math.PI * 2 * k / n).toFloat()
                    val ca = cos(a); val sa = sin(a)
                    dab(c, tip, centerX + ox * ca - oy * sa, centerY + ox * sa + oy * ca, d, angle + 360f * k / n)
                }
            }
        }
    }

    private fun dab(c: Canvas, tip: Bitmap, x: Float, y: Float, d: Float, angle: Float) {
        // the longer side of the tip matches the brush size
        val s = d / max(tip.width, tip.height)
        m.reset()
        m.postTranslate(-tip.width / 2f, -tip.height / 2f)
        m.postScale(s, s)
        if (angle != 0f) m.postRotate(angle)
        m.postTranslate(x, y)
        c.drawBitmap(tip, m, paint)
        val r = d * 0.75f + 2f
        if (dirty.isEmpty) dirty.set(x - r, y - r, x + r, y + r) else dirty.union(x - r, y - r, x + r, y + r)
    }
}
