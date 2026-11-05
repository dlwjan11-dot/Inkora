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
import kotlin.math.min
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
    MANDALA_16("Mandala (16)", 16),
    /** turned copies that are also mirrored, like a kaleidoscope; [segments] of them */
    KALEIDOSCOPE("Kaleidoscope", 0);

    companion object {
        /** how many slices the kaleidoscope has (each slice holds a stroke and its mirror) */
        var segments = 6
    }
}

/** A recorded input sample (document coordinates). */
class InputPoint(val x: Float, val y: Float, val pressure: Float, val tilt: Float)

/**
 * Turns pen input into brush "dabs" on a stroke bitmap.
 * The stroke bitmap is later merged into the layer, so one stroke has an even opacity.
 *
 * Brush Studio settings it follows ([Brush.dyn]): stabilization and motion filtering, taper,
 * roundness and flips, size and opacity jitter, color dynamics (per dab, per stroke, pressure and
 * tilt), wet mix (dilution, charge, pull from [wetSource]) and the dual brush (a second shape
 * stamped into [maskTarget], which the stroke is then cut by).
 */
class StrokeEngine {
    var brush: Brush = Brush(Brushes.presets[0])
    var color: Int = 0xFF000000.toInt()
    var symmetry = Symmetry.OFF
    var centerX = 0f
    var centerY = 0f
    var radialCount = 8

    /** the layer under the stroke, for wet mix to pick its colors up (null: only the stroke itself) */
    var wetSource: Bitmap? = null
    /** where the dual brush's second shape is stamped (the stroke is kept only where it is) */
    var maskTarget: Bitmap? = null

    /** Union of everything drawn by the current stroke. */
    val dirty = RectF()

    /** When set, dabs are handed to this function (x, y, diameter, alpha, angle) instead of being drawn. */
    var dabHandler: ((Float, Float, Float, Float, Float) -> Unit)? = null

    private var canvas: Canvas? = null
    private var target: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
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

    // stabilization: the last points of the pen, averaged
    private val ringX = FloatArray(32)
    private val ringY = FloatArray(32)
    private var ringN = 0
    private var ringI = 0
    private var acceptedX = 0f
    private var acceptedY = 0f

    // color dynamics and wet mix, for this stroke
    private val baseHsv = FloatArray(3)
    private val hsv = FloatArray(3)
    private var strokeHue = 0f
    private var strokeLight = 0f
    private var loadR = 0f
    private var loadG = 0f
    private var loadB = 0f
    private var paintLeft = 1f

    fun begin(target: Bitmap, p: InputPoint, smooth: Boolean = true, seed: Int = Random.nextInt()) {
        canvas = Canvas(target)
        this.target = target
        maskCanvas = maskTarget?.let { Canvas(it) }
        random = Random(seed)
        smoothing = smooth
        dirty.setEmpty()
        smX = p.x; smY = p.y; smP = p.pressure
        lastX = p.x; lastY = p.y; lastP = p.pressure; lastTilt = p.tilt
        acceptedX = p.x; acceptedY = p.y
        ringN = 0; ringI = 0
        traveled = 0f
        lastAngle = 0f
        rawLast = p
        val dyn = brush.dyn
        android.graphics.Color.colorToHSV(color, baseHsv)
        strokeHue = (random.nextFloat() - 0.5f) * 2f * dyn.strokeHueJitter * 180f
        strokeLight = (random.nextFloat() - 0.5f) * 2f * dyn.strokeLightJitter * 0.5f
        loadR = ((color shr 16) and 0xFF).toFloat()
        loadG = ((color shr 8) and 0xFF).toFloat()
        loadB = (color and 0xFF).toFloat()
        paintLeft = 1f
        stamp(p.x, p.y, p.pressure, p.tilt, 0f)
        distToNext = spacingFor(diameterFor(p.pressure, p.tilt, 0f))
    }

    fun move(p: InputPoint) {
        rawLast = p
        val dyn = brush.dyn
        var tx = p.x
        var ty = p.y
        if (smoothing) {
            // motion filtering: tiny shakes (in canvas pixels, relative to the brush) are ignored
            if (dyn.motionFilter > 0f) {
                val min = dyn.motionFilter * max(1.5f, brush.size * 0.08f)
                if (hypot(tx - acceptedX, ty - acceptedY) < min) {
                    smP += (p.pressure - smP) * 0.6f
                    return
                }
                acceptedX = tx; acceptedY = ty
            }
            // stabilization: the average of the last few points
            if (dyn.stabilization > 0f) {
                val n = (1 + dyn.stabilization * 30f).toInt().coerceIn(1, ringX.size)
                ringX[ringI] = tx; ringY[ringI] = ty
                ringI = (ringI + 1) % ringX.size
                ringN = min(ringN + 1, ringX.size)
                val k = min(n, ringN)
                var sx = 0f; var sy = 0f
                for (i in 1..k) {
                    val j = (ringI - i + ringX.size) % ringX.size
                    sx += ringX[j]; sy += ringY[j]
                }
                tx = sx / k; ty = sy / k
            }
        }
        val k = if (smoothing) 1f - brush.streamline.coerceIn(0f, 1f) * 0.92f else 1f
        smX += (tx - smX) * k
        smY += (ty - smY) * k
        smP += (p.pressure - smP) * (if (smoothing) 0.6f else 1f)
        segmentTo(smX, smY, smP, p.tilt)
    }

    /** Catch up with the pen (streamline lag) at the end of the stroke. */
    fun end() {
        val r = rawLast ?: return
        if (smoothing && (brush.streamline > 0.01f || brush.dyn.stabilization > 0f)) {
            val steps = 6
            val sx = smX; val sy = smY; val sp = smP
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                segmentTo(sx + (r.x - sx) * t, sy + (r.y - sy) * t, sp + (r.pressure - sp) * t, r.tilt)
            }
        }
        canvas = null
        target = null
        maskCanvas = null
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

    /** 0..1 along the start taper (1 = past it) */
    private fun taperT(dist: Float): Float {
        val t = brush.dyn.taperLength
        if (t <= 0f) return 1f
        val len = t * brush.size
        if (len <= 0f) return 1f
        val f = (dist / len).coerceIn(0f, 1f)
        return f * f * (3 - 2 * f)
    }

    private fun taperFactor(dist: Float): Float {
        val f = taperT(dist)
        if (f >= 1f) return 1f
        val thin = 1f - 0.85f * brush.dyn.taperSize
        return thin + (1f - thin) * f
    }

    private fun tiltAmount(tilt: Float): Float {
        if (!brush.dyn.tiltShading) return 0f
        // tilt is radians from perpendicular; shading starts around 35 degrees
        return ((tilt - 0.6f) / 0.6f).coerceIn(0f, 1f)
    }

    private fun diameterFor(pressure: Float, tilt: Float, dist: Float): Float {
        val pc = pressure.coerceIn(0f, 1f).pow(brush.dyn.pressureCurve)
        val sizeF = brush.minSize + (1f - brush.minSize) * pc
        var d = brush.size * sizeF * taperFactor(dist)
        d *= 1f + 2.5f * tiltAmount(tilt)
        return max(0.5f, d)
    }

    /** The color of this dab: color dynamics, then wet mix. */
    private fun dabColor(cx: Float, cy: Float, pressure: Float, tilt: Float): Int {
        val dyn = brush.dyn
        var c = color
        if (dyn.hasColorDynamics) {
            val light = 1f - pressure.coerceIn(0f, 1f)
            var h = baseHsv[0] + strokeHue + dyn.pressureHue * light * 60f +
                (random.nextFloat() - 0.5f) * 2f * dyn.hueJitter * 180f
            var sat = baseHsv[1] + dyn.pressureSat * light + (random.nextFloat() - 0.5f) * 2f * dyn.satJitter
            var v = baseHsv[2] + strokeLight + dyn.pressureBright * light + dyn.tiltBright * tiltAmountAlways(tilt) +
                (random.nextFloat() - 0.5f) * 2f * dyn.lightJitter * 0.6f
            h = ((h % 360f) + 360f) % 360f
            sat = sat.coerceIn(0f, 1f); v = v.coerceIn(0f, 1f)
            hsv[0] = h; hsv[1] = sat; hsv[2] = v
            c = android.graphics.Color.HSVToColor(hsv)
        }
        if (dyn.hasWetMix) {
            // the brush's load takes on the colors it passes over (the layer, and this stroke)
            val under = sampleUnder(cx, cy)
            val ua = (under ushr 24) / 255f
            val pull = dyn.wetPull * 0.45f * ua
            val r = ((c shr 16) and 0xFF).toFloat(); val g = ((c shr 8) and 0xFF).toFloat(); val b = (c and 0xFF).toFloat()
            // fresh paint keeps coming from the brush as long as it is charged
            val fresh = 0.12f * paintLeft
            loadR += (r - loadR) * fresh; loadG += (g - loadG) * fresh; loadB += (b - loadB) * fresh
            loadR += (((under shr 16) and 0xFF) - loadR) * pull
            loadG += (((under shr 8) and 0xFF) - loadG) * pull
            loadB += ((under and 0xFF) - loadB) * pull
            c = (0xFF shl 24) or (loadR.toInt().coerceIn(0, 255) shl 16) or (loadG.toInt().coerceIn(0, 255) shl 8) or loadB.toInt().coerceIn(0, 255)
        }
        return c
    }

    private fun tiltAmountAlways(tilt: Float) = ((tilt - 0.6f) / 0.6f).coerceIn(0f, 1f)

    /** the color showing at (x, y): this stroke over the layer */
    private fun sampleUnder(x: Float, y: Float): Int {
        val xi = x.toInt(); val yi = y.toInt()
        var below = 0
        wetSource?.let { s -> if (xi in 0 until s.width && yi in 0 until s.height) below = s.getPixel(xi, yi) }
        val t = target ?: return below
        if (xi !in 0 until t.width || yi !in 0 until t.height) return below
        val top = t.getPixel(xi, yi)
        val ta = (top ushr 24) / 255f
        if (ta <= 0f) return below
        val ba = (below ushr 24) / 255f
        val a = ta + ba * (1 - ta)
        if (a <= 0f) return 0
        fun ch(sh: Int) = ((((top shr sh) and 0xFF) * ta + ((below shr sh) and 0xFF) * ba * (1 - ta)) / a).toInt().coerceIn(0, 255)
        return ((a * 255).toInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun stamp(x: Float, y: Float, pressure: Float, tilt: Float, dist: Float, dirAngle: Float = lastAngle) {
        val c = canvas ?: return
        val dyn = brush.dyn
        var d = diameterFor(pressure, tilt, dist)
        val pc = pressure.coerceIn(0f, 1f)
        var alpha = brush.flow * (brush.minFlow + (1f - brush.minFlow) * pc)
        alpha *= 1f - 0.6f * tiltAmount(tilt)
        if (dyn.taperOpacity > 0f) alpha *= 1f - dyn.taperOpacity * (1f - taperT(dist))
        if (dyn.sizeJitter > 0f) d *= 1f - dyn.sizeJitter * random.nextFloat() * 0.9f
        if (dyn.opacityJitter > 0f) alpha *= 1f - dyn.opacityJitter * random.nextFloat()
        if (dyn.hasWetMix) {
            alpha *= paintLeft * (1f - 0.65f * dyn.wetDilution)
            // the brush runs dry as the stroke goes on, the less charge the faster
            paintLeft = max(0f, paintLeft - (1f - dyn.wetCharge) * 0.012f)
        }
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
        paint.color = if (dyn.hasColorDynamics || dyn.hasWetMix) dabColor(cx, cy, pressure, tilt) else color
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        if (paint.alpha == 0) return
        val tip = brush.tip()
        val sx = if (dyn.flipX && random.nextBoolean()) -1f else 1f
        val sy = if (dyn.flipY && random.nextBoolean()) -1f else 1f
        flipX = sx; flipY = sy
        // the dual brush's second shape: one per dab, scattered around it
        val dual = brush.dualTip()
        if (dual != null) {
            dualAngle = random.nextFloat() * 360f
            val a = random.nextFloat() * 6.2831855f
            val r = random.nextFloat() * dyn.dualScatter * d
            dualDx = cos(a) * r; dualDy = sin(a) * r
        }

        when (symmetry) {
            Symmetry.OFF -> dab(c, tip, cx, cy, d, angle, dual)
            Symmetry.VERTICAL -> {
                dab(c, tip, cx, cy, d, angle, dual)
                dab(c, tip, 2 * centerX - cx, cy, d, 180f - angle, dual)
            }
            Symmetry.HORIZONTAL -> {
                dab(c, tip, cx, cy, d, angle, dual)
                dab(c, tip, cx, 2 * centerY - cy, d, -angle, dual)
            }
            Symmetry.QUADRANT -> {
                dab(c, tip, cx, cy, d, angle, dual)
                dab(c, tip, 2 * centerX - cx, cy, d, 180f - angle, dual)
                dab(c, tip, cx, 2 * centerY - cy, d, -angle, dual)
                dab(c, tip, 2 * centerX - cx, 2 * centerY - cy, d, angle + 180f, dual)
            }
            Symmetry.RADIAL, Symmetry.MANDALA_12, Symmetry.MANDALA_16 -> {
                val n = symmetry.axes
                val ox = cx - centerX
                val oy = cy - centerY
                for (k in 0 until n) {
                    val a = (Math.PI * 2 * k / n).toFloat()
                    val ca = cos(a); val sa = sin(a)
                    dab(c, tip, centerX + ox * ca - oy * sa, centerY + ox * sa + oy * ca, d, angle + 360f * k / n, dual)
                }
            }
            Symmetry.KALEIDOSCOPE -> {
                val n = Symmetry.segments.coerceIn(2, 32)
                val ox = cx - centerX
                val oy = cy - centerY
                for (k in 0 until n) {
                    val a = (Math.PI * 2 * k / n).toFloat()
                    val ca = cos(a); val sa = sin(a)
                    dab(c, tip, centerX + ox * ca - oy * sa, centerY + ox * sa + oy * ca, d, angle + 360f * k / n, dual)
                    // the mirror image (across the slice's edge), turned the same way
                    dab(c, tip, centerX + ox * ca + oy * sa, centerY + ox * sa - oy * ca, d, -angle + 360f * k / n, dual)
                }
            }
        }
    }

    private var flipX = 1f
    private var flipY = 1f
    private var dualAngle = 0f
    private var dualDx = 0f
    private var dualDy = 0f

    private fun dab(c: Canvas, tip: Bitmap, x: Float, y: Float, d: Float, angle: Float, dual: Bitmap?) {
        // the longer side of the tip matches the brush size
        val s = d / max(tip.width, tip.height)
        val round = brush.dyn.roundness
        m.reset()
        m.postTranslate(-tip.width / 2f, -tip.height / 2f)
        m.postScale(s * flipX, s * round * flipY)
        if (angle != 0f) m.postRotate(angle)
        m.postTranslate(x, y)
        c.drawBitmap(tip, m, paint)
        var r = d * 0.75f + 2f
        val mc = maskCanvas
        if (dual != null && mc != null) {
            val dd = d * brush.dyn.dualSize
            val s2 = dd / max(dual.width, dual.height)
            m.reset()
            m.postTranslate(-dual.width / 2f, -dual.height / 2f)
            m.postScale(s2, s2)
            m.postRotate(dualAngle)
            m.postTranslate(x + dualDx, y + dualDy)
            mc.drawBitmap(dual, m, maskPaint)
            r = max(r, dd * 0.75f + 2f + kotlin.math.abs(dualDx) + kotlin.math.abs(dualDy))
        }
        if (dirty.isEmpty) dirty.set(x - r, y - r, x + r, y + r) else dirty.union(x - r, y - r, x + r, y + r)
    }

    companion object {
        private val dstIn = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = android.graphics.BlendMode.DST_IN }

        /** For previews: keeps [stroke] only where the dual brush's [mask] was stamped. */
        fun applyMask(stroke: Bitmap, mask: Bitmap) {
            Canvas(stroke).drawBitmap(mask, 0f, 0f, dstIn)
        }
    }
}
