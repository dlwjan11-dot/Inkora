package com.abdulmedia.inkora

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Every kind of drawing guide / grid. */
enum class GuideType(val label: String, val usesSize: Boolean, val vanishingPoints: IntArray) {
    OFF("Off", false, intArrayOf()),
    SQUARE("2D Grid", true, intArrayOf()),
    DOTS("Dot Grid", true, intArrayOf()),
    ISOMETRIC("Isometric", true, intArrayOf()),
    TRIANGLE("Triangle Grid", true, intArrayOf()),
    HEXAGON("Hexagon Grid", true, intArrayOf()),
    POLAR("Polar (circles)", true, intArrayOf()),
    THIRDS("Rule of Thirds", false, intArrayOf()),
    ELLIPSE_RULER("Circle Ruler", true, intArrayOf()),
    PERSPECTIVE_1("1-Point Perspective", true, intArrayOf(0)),
    PERSPECTIVE_2("2-Point Perspective", true, intArrayOf(1, 2)),
    PERSPECTIVE_3("3-Point Perspective", true, intArrayOf(1, 2, 3)),
    /** curvilinear (fisheye) perspective: point 1 = center, point 2 = edge of the fisheye circle on the horizon */
    FISHEYE("Fisheye Perspective", true, intArrayOf(0, 4)),
    /** a straight ruler you move with its two ends; strokes run along it */
    RULER("Ruler", false, intArrayOf(5, 6)),
    /** an ellipse you place and tilt (center, end of the long axis, side point); strokes follow it */
    ELLIPSE_GUIDE("Ellipse Guide", false, intArrayOf(7, 8, 9));

    /** guides whose points you drag on the canvas */
    val hasPoints get() = vanishingPoints.isNotEmpty()

    val isPerspective get() = vanishingPoints.isNotEmpty()
}

/** Guide settings, saved inside the project. Vanishing points are stored as fractions of the canvas. */
class GuideSettings {
    var type = GuideType.OFF
        set(v) {
            field = v; version++
            // a ruler or an ellipse guide is only useful when strokes follow it
            if (v == GuideType.RULER || v == GuideType.ELLIPSE_GUIDE) assisted = true
        }
    var size = 100f
        set(v) { field = v; version++ }
    var thicknessDp = 1.2f
    var opacity = 0.55f
    var color = 0xFF4C8DFF.toInt()
    var assisted = false

    /** x0,y0 … x4,y4 : VP0 = 1-point, VP1+VP2 = 2-point, VP1+VP2+VP3 = 3-point, VP0+VP4 = fisheye (center, edge) */
    val vps = DEFAULT_VPS.copyOf()

    /** bumped whenever the geometry changes (used for caching) */
    var version = 0

    fun setVp(i: Int, fx: Float, fy: Float) {
        vps[i * 2] = fx
        vps[i * 2 + 1] = fy
        version++
    }

    fun resetVps() {
        DEFAULT_VPS.copyInto(vps)
        version++
    }

    fun vpX(i: Int, w: Int) = vps[i * 2] * w
    fun vpY(i: Int, h: Int) = vps[i * 2 + 1] * h

    companion object {
        val DEFAULT_VPS = floatArrayOf(0.5f, 0.45f, 0.06f, 0.42f, 0.94f, 0.42f, 0.5f, 0.96f, 0.1f, 0.45f,
            0.22f, 0.72f, 0.78f, 0.32f, 0.5f, 0.52f, 0.74f, 0.52f, 0.5f, 0.64f)
        const val MIN_SIZE = 8f
        const val MAX_SIZE = 600f
    }
}

/** Geometry of a guide in document pixels. */
class GuideGeometry(val lines: FloatArray, val points: FloatArray, val circles: FloatArray)

/** A shape a stroke can be locked onto (Assisted Drawing). */
sealed class Constraint {
    abstract fun project(x: Float, y: Float, out: FloatArray)

    class Line(val ox: Float, val oy: Float, val dx: Float, val dy: Float) : Constraint() {
        override fun project(x: Float, y: Float, out: FloatArray) {
            val t = (x - ox) * dx + (y - oy) * dy
            out[0] = ox + dx * t
            out[1] = oy + dy * t
        }
    }

    /** an ellipse with half axes [a], [b]; the long one points along (ux, uy) */
    class Ellipse(val cx: Float, val cy: Float, val a: Float, val b: Float, val ux: Float, val uy: Float) : Constraint() {
        override fun project(x: Float, y: Float, out: FloatArray) {
            val dx = x - cx; val dy = y - cy
            val u = dx * ux + dy * uy
            val v = -dx * uy + dy * ux
            val t = atan2(v / b, u / a)
            val pu = a * cos(t); val pv = b * sin(t)
            out[0] = cx + pu * ux - pv * uy
            out[1] = cy + pu * uy + pv * ux
        }

        /** direction of the ellipse at the point nearest (x, y) */
        fun tangent(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - cx; val dy = y - cy
            val t = atan2((-dx * uy + dy * ux) / b, (dx * ux + dy * uy) / a)
            val tu = -a * sin(t); val tv = b * cos(t)
            var tx = tu * ux - tv * uy; var ty = tu * uy + tv * ux
            val l = hypot(tx, ty).takeIf { it > 1e-6f } ?: 1f
            tx /= l; ty /= l
            return tx to ty
        }
    }

    class Circle(val cx: Float, val cy: Float, val r: Float) : Constraint() {
        override fun project(x: Float, y: Float, out: FloatArray) {
            val d = hypot(x - cx, y - cy)
            if (d < 1e-3f) { out[0] = cx + r; out[1] = cy; return }
            out[0] = cx + (x - cx) * r / d
            out[1] = cy + (y - cy) * r / d
        }
    }
}

object Guides {

    // =========================================================
    // geometry
    // =========================================================

    fun build(g: GuideSettings, w: Int, h: Int): GuideGeometry {
        val lines = FloatList()
        val points = FloatList()
        val circles = FloatList()
        val cx = w / 2f
        val cy = h / 2f
        val s = g.size.coerceIn(GuideSettings.MIN_SIZE, GuideSettings.MAX_SIZE)
        when (g.type) {
            GuideType.OFF -> {}
            GuideType.SQUARE -> {
                family(lines, 0f, s, cx, cy, w, h)
                family(lines, 90f, s, cx, cy, w, h)
            }
            GuideType.DOTS -> {
                // keep it light enough to draw every frame (max ~60k dots)
                val s = max(s, sqrt(w.toFloat() * h / 60_000f))
                val x0 = cx - floor(cx / s) * s
                val y0 = cy - floor(cy / s) * s
                var y = y0
                while (y <= h) {
                    var x = x0
                    while (x <= w) { points.add(x); points.add(y); x += s }
                    y += s
                }
            }
            GuideType.ISOMETRIC -> for (a in floatArrayOf(90f, 30f, 150f)) family(lines, a, s * 0.866f, cx, cy, w, h)
            GuideType.TRIANGLE -> for (a in floatArrayOf(0f, 60f, 120f)) family(lines, a, s * 0.866f, cx, cy, w, h)
            GuideType.HEXAGON -> {
                // max ~40k edges so drawing stays smooth
                val minR = sqrt(6f * w * h / (2.598f * 40_000f))
                hexagons(lines, max(s / 2f, minR), cx, cy, w, h)
            }
            GuideType.POLAR -> {
                val maxR = hypot(cx, cy)
                var r = s
                while (r <= maxR) { circles.add(cx); circles.add(cy); circles.add(r); r += s }
                for (k in 0 until 24) {
                    val a = (2 * PI * k / 24).toFloat()
                    clipAdd(lines, cx, cy, cx + cos(a) * maxR * 2, cy + sin(a) * maxR * 2, w, h)
                }
            }
            GuideType.THIRDS -> {
                for (k in 1..2) {
                    val x = w * k / 3f
                    val y = h * k / 3f
                    lines.add(x); lines.add(0f); lines.add(x); lines.add(h.toFloat())
                    lines.add(0f); lines.add(y); lines.add(w.toFloat()); lines.add(y)
                }
            }
            GuideType.ELLIPSE_RULER -> {
                circles.add(cx); circles.add(cy); circles.add(s)
            }
            GuideType.PERSPECTIVE_1 -> {
                val vx = g.vpX(0, w); val vy = g.vpY(0, h)
                rays(lines, vx, vy, w, h)
                family(lines, 0f, s, cx, vy, w, h)      // horizontals (one of them is the horizon)
                family(lines, 90f, s, vx, cy, w, h)     // verticals
            }
            GuideType.PERSPECTIVE_2 -> {
                val ax = g.vpX(1, w); val ay = g.vpY(1, h)
                val bx = g.vpX(2, w); val by = g.vpY(2, h)
                rays(lines, ax, ay, w, h)
                rays(lines, bx, by, w, h)
                horizon(lines, ax, ay, bx, by, w, h)
                family(lines, 90f, s, cx, cy, w, h)
            }
            GuideType.PERSPECTIVE_3 -> {
                val ax = g.vpX(1, w); val ay = g.vpY(1, h)
                val bx = g.vpX(2, w); val by = g.vpY(2, h)
                rays(lines, ax, ay, w, h)
                rays(lines, bx, by, w, h)
                rays(lines, g.vpX(3, w), g.vpY(3, h), w, h)
                horizon(lines, ax, ay, bx, by, w, h)
            }
            GuideType.FISHEYE -> fisheye(lines, circles, g, w, h, s)
            GuideType.RULER -> {
                val ax = g.vpX(5, w); val ay = g.vpY(5, h); val bx = g.vpX(6, w); val by = g.vpY(6, h)
                horizon(lines, ax, ay, bx, by, w, h)
                // a little ruler body along the edge
                var dx = bx - ax; var dy = by - ay
                val l = hypot(dx, dy).takeIf { it > 1f } ?: 1f
                dx /= l; dy /= l
                val off = min(w, h) * 0.02f
                clipAdd(lines, ax + dy * off, ay - dx * off, bx + dy * off, by - dx * off, w, h)
                val ticks = (l / (off * 1.5f)).toInt().coerceIn(2, 200)
                for (k in 0..ticks) {
                    val t = k / ticks.toFloat()
                    val px = ax + (bx - ax) * t; val py = ay + (by - ay) * t
                    val tl = if (k % 5 == 0) off else off * 0.5f
                    clipAdd(lines, px, py, px + dy * tl, py - dx * tl, w, h)
                }
            }
            GuideType.ELLIPSE_GUIDE -> {
                val e = ellipse(g, w, h)
                val n = 120
                var px = 0f; var py = 0f
                for (k in 0..n) {
                    val t = (2 * PI * k / n).toFloat()
                    val qx = e.cx + e.a * cos(t) * e.ux - e.b * sin(t) * e.uy
                    val qy = e.cy + e.a * cos(t) * e.uy + e.b * sin(t) * e.ux
                    if (k > 0) clipAdd(lines, px, py, qx, qy, w, h)
                    px = qx; py = qy
                }
                // axes
                clipAdd(lines, e.cx - e.ux * e.a, e.cy - e.uy * e.a, e.cx + e.ux * e.a, e.cy + e.uy * e.a, w, h)
                clipAdd(lines, e.cx + e.uy * e.b, e.cy - e.ux * e.b, e.cx - e.uy * e.b, e.cy + e.ux * e.b, w, h)
            }
        }
        return GuideGeometry(lines.toArray(), points.toArray(), circles.toArray())
    }

    /** Parallel lines at [angleDeg], [spacing] apart, one of them passing through (ox, oy). */
    private fun family(out: FloatList, angleDeg: Float, spacing: Float, ox: Float, oy: Float, w: Int, h: Int) {
        val a = Math.toRadians(angleDeg.toDouble())
        val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
        val nx = -dy; val ny = dx
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for ((x, y) in listOf(0f to 0f, w.toFloat() to 0f, 0f to h.toFloat(), w.toFloat() to h.toFloat())) {
            val p = (x - ox) * nx + (y - oy) * ny
            lo = min(lo, p); hi = max(hi, p)
        }
        val big = (w + h).toFloat() * 2
        val k0 = ceil(lo / spacing).toInt()
        val k1 = floor(hi / spacing).toInt()
        for (k in k0..k1) {
            val c = k * spacing
            val px = ox + nx * c; val py = oy + ny * c
            clipAdd(out, px - dx * big, py - dy * big, px + dx * big, py + dy * big, w, h)
        }
    }

    /** the ellipse guide: center, half axes and the long axis' direction */
    class Ell(val cx: Float, val cy: Float, val a: Float, val b: Float, val ux: Float, val uy: Float)

    fun ellipse(g: GuideSettings, w: Int, h: Int): Ell {
        val cx = g.vpX(7, w); val cy = g.vpY(7, h)
        var ux = g.vpX(8, w) - cx; var uy = g.vpY(8, h) - cy
        val a = hypot(ux, uy)
        if (a < 1f) { ux = 1f; uy = 0f } else { ux /= a; uy /= a }
        // the side point's distance from the long axis sets the short half axis
        val sx = g.vpX(9, w) - cx; val sy = g.vpY(9, h) - cy
        val b = abs(-uy * sx + ux * sy)
        return Ell(cx, cy, max(a, 4f), max(b, 2f), ux, uy)
    }

    /** center, radius and axes (e1 = along the horizon, e2 = across) of the fisheye guide */
    class Fish(val cx: Float, val cy: Float, val r: Float, val e1x: Float, val e1y: Float) {
        val e2x get() = -e1y
        val e2y get() = e1x
    }

    fun fish(g: GuideSettings, w: Int, h: Int): Fish {
        val cx = g.vpX(0, w); val cy = g.vpY(0, h)
        val ex = g.vpX(4, w); val ey = g.vpY(4, h)
        var dx = cx - ex; var dy = cy - ey
        val r = hypot(dx, dy)
        if (r < 1f) { dx = 1f; dy = 0f } else { dx /= r; dy /= r }
        return Fish(cx, cy, max(r, 20f), dx, dy)
    }

    /**
     * Curvilinear (fisheye) perspective: the circle, arcs through the left and right edge points
     * (curved horizontals), arcs through the top and bottom points (curved verticals), and lines
     * from the center (depth).
     */
    private fun fisheye(lines: FloatList, circles: FloatList, g: GuideSettings, w: Int, h: Int, spacing: Float) {
        val f = fish(g, w, h)
        val R = f.r
        circles.add(f.cx); circles.add(f.cy); circles.add(R)
        // spacing measured across the middle of the circle, at least a few lines
        val step = spacing.coerceIn(R / 14f, R / 2f)
        val reach = hypot(w.toFloat(), h.toFloat()) * 1.5f
        val n = ceil(reach / step).toInt().coerceAtMost(60)
        for (family in 0..1) {
            // family 0: horizontals (through ±e1), family 1: verticals (through ±e2)
            val ax = if (family == 0) f.e1x else f.e2x
            val ay = if (family == 0) f.e1y else f.e2y
            val bx = if (family == 0) f.e2x else f.e1x
            val by = if (family == 0) f.e2y else f.e1y
            for (j in -n..n) {
                val v0 = j * step
                if (j == 0) {
                    clipAdd(lines, f.cx - ax * reach, f.cy - ay * reach, f.cx + ax * reach, f.cy + ay * reach, w, h)
                    continue
                }
                // the arc through the two edge points and the point v0 across the middle
                val k = (v0 * v0 - R * R) / (2 * v0)
                val ocx = f.cx + bx * k; val ocy = f.cy + by * k
                val rr = sqrt(R * R + k * k)
                arc(lines, ocx, ocy, rr, f.cx - ax * R, f.cy - ay * R, f.cx + bx * v0, f.cy + by * v0, f.cx + ax * R, f.cy + ay * R, w, h)
            }
        }
        // depth lines from the center
        for (k in 0 until 32) {
            val a = (2 * PI * k / 32).toFloat()
            clipAdd(lines, f.cx, f.cy, f.cx + cos(a) * reach, f.cy + sin(a) * reach, w, h)
        }
    }

    /** an arc of the circle (ox, oy, r) from point A through point M to point B, as short segments */
    private fun arc(out: FloatList, ox: Float, oy: Float, r: Float,
                    ax: Float, ay: Float, mx: Float, my: Float, bx: Float, by: Float, w: Int, h: Int) {
        val a0 = atan2(ay - oy, ax - ox)
        val am = atan2(my - oy, mx - ox)
        val a1 = atan2(by - oy, bx - ox)
        fun norm(a: Float): Float { var x = a; while (x < 0) x += (2 * PI).toFloat(); while (x >= 2 * PI) x -= (2 * PI).toFloat(); return x }
        // sweep from a0 to a1 counter-clockwise if M lies on that way, otherwise clockwise
        var sweep = norm(a1 - a0)
        if (norm(am - a0) > sweep) sweep -= (2 * PI).toFloat()
        val segs = (abs(sweep) * r / 12f).toInt().coerceIn(12, 160)
        var px = ox + r * cos(a0); var py = oy + r * sin(a0)
        for (i in 1..segs) {
            val a = a0 + sweep * i / segs
            val qx = ox + r * cos(a); val qy = oy + r * sin(a)
            clipAdd(out, px, py, qx, qy, w, h)
            px = qx; py = qy
        }
    }

    /** Lines radiating from a vanishing point. */
    private fun rays(out: FloatList, vx: Float, vy: Float, w: Int, h: Int) {
        val big = (w + h).toFloat() * 4 + hypot(vx, vy)
        val n = 72
        for (k in 0 until n) {
            val a = (2 * PI * k / n).toFloat()
            clipAdd(out, vx, vy, vx + cos(a) * big, vy + sin(a) * big, w, h)
        }
    }

    private fun horizon(out: FloatList, ax: Float, ay: Float, bx: Float, by: Float, w: Int, h: Int) {
        var dx = bx - ax; var dy = by - ay
        val l = hypot(dx, dy)
        if (l < 1e-3f) { dx = 1f; dy = 0f } else { dx /= l; dy /= l }
        val big = (w + h).toFloat() * 4
        clipAdd(out, ax - dx * big, ay - dy * big, ax + dx * big, ay + dy * big, w, h)
    }

    private fun hexagons(out: FloatList, r: Float, cx: Float, cy: Float, w: Int, h: Int) {
        val dx = sqrt(3f) * r
        val dy = 1.5f * r
        val rowMin = floor((-r - cy) / dy).toInt() - 1
        val rowMax = ceil((h + r - cy) / dy).toInt() + 1
        val colMin = floor((-dx - cx) / dx).toInt() - 1
        val colMax = ceil((w + dx - cx) / dx).toInt() + 1
        val vx = FloatArray(6); val vy = FloatArray(6)
        for (row in rowMin..rowMax) {
            val shift = if (row and 1 != 0) dx / 2 else 0f
            val y = cy + row * dy
            for (col in colMin..colMax) {
                val x = cx + col * dx + shift
                if (x < -dx || x > w + dx || y < -r * 2 || y > h + r * 2) continue
                for (k in 0 until 6) {
                    val a = Math.toRadians((-90.0 + 60.0 * k))
                    vx[k] = x + (cos(a) * r).toFloat()
                    vy[k] = y + (sin(a) * r).toFloat()
                }
                for (k in 0 until 6) {
                    val k2 = (k + 1) % 6
                    out.add(vx[k]); out.add(vy[k]); out.add(vx[k2]); out.add(vy[k2])
                }
            }
        }
    }

    /** Liang–Barsky clip of a segment to the canvas rectangle. */
    private fun clipAdd(out: FloatList, x0: Float, y0: Float, x1: Float, y1: Float, w: Int, h: Int) {
        val dx = x1 - x0; val dy = y1 - y0
        var t0 = 0f; var t1 = 1f
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(x0, w - x0, y0, h - y0)
        for (i in 0 until 4) {
            if (p[i] == 0f) {
                if (q[i] < 0f) return
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0f) { if (t > t1) return; if (t > t0) t0 = t }
                else { if (t < t0) return; if (t < t1) t1 = t }
            }
        }
        if (t1 <= t0) return
        out.add(x0 + dx * t0); out.add(y0 + dy * t0)
        out.add(x0 + dx * t1); out.add(y0 + dy * t1)
    }

    // =========================================================
    // Assisted Drawing
    // =========================================================

    /**
     * Picks the guide direction closest to how the stroke started.
     * (sx, sy) = stroke start, (mx, my) = movement so far. Document coordinates.
     */
    fun constraintFor(g: GuideSettings, w: Int, h: Int, sx: Float, sy: Float, mx: Float, my: Float): Constraint? {
        val ml = hypot(mx, my)
        if (ml < 1e-3f || g.type == GuideType.OFF) return null
        val ux = mx / ml; val uy = my / ml
        val cands = ArrayList<Constraint>()

        fun angle(deg: Float) {
            val a = Math.toRadians(deg.toDouble())
            cands.add(Constraint.Line(sx, sy, cos(a).toFloat(), sin(a).toFloat()))
        }
        fun toward(px: Float, py: Float) {
            val dx = px - sx; val dy = py - sy
            val l = hypot(dx, dy)
            if (l > 1f) cands.add(Constraint.Line(sx, sy, dx / l, dy / l))
        }

        when (g.type) {
            GuideType.SQUARE, GuideType.DOTS, GuideType.THIRDS -> { angle(0f); angle(90f) }
            GuideType.ISOMETRIC, GuideType.HEXAGON -> { angle(90f); angle(30f); angle(150f) }
            GuideType.TRIANGLE -> { angle(0f); angle(60f); angle(120f) }
            GuideType.POLAR -> {
                val cx = w / 2f; val cy = h / 2f
                toward(cx, cy)
                val r = hypot(sx - cx, sy - cy)
                if (r > 1f) cands.add(Constraint.Circle(cx, cy, r))
            }
            GuideType.PERSPECTIVE_1 -> { toward(g.vpX(0, w), g.vpY(0, h)); angle(0f); angle(90f) }
            GuideType.PERSPECTIVE_2 -> { toward(g.vpX(1, w), g.vpY(1, h)); toward(g.vpX(2, w), g.vpY(2, h)); angle(90f) }
            GuideType.PERSPECTIVE_3 -> {
                toward(g.vpX(1, w), g.vpY(1, h)); toward(g.vpX(2, w), g.vpY(2, h)); toward(g.vpX(3, w), g.vpY(3, h))
            }
            GuideType.FISHEYE -> {
                val f = fish(g, w, h)
                val R = f.r
                // the stroke start in the fisheye's own axes
                val u = (sx - f.cx) * f.e1x + (sy - f.cy) * f.e1y
                val v = (sx - f.cx) * f.e2x + (sy - f.cy) * f.e2y
                // curved horizontal through the start: an arc through both horizon edge points
                if (abs(v) < 0.5f) cands.add(Constraint.Line(sx, sy, f.e1x, f.e1y))
                else {
                    val k = (u * u + v * v - R * R) / (2 * v)
                    cands.add(Constraint.Circle(f.cx + f.e2x * k, f.cy + f.e2y * k, sqrt(R * R + k * k)))
                }
                // curved vertical through the start
                if (abs(u) < 0.5f) cands.add(Constraint.Line(sx, sy, f.e2x, f.e2y))
                else {
                    val k = (u * u + v * v - R * R) / (2 * u)
                    cands.add(Constraint.Circle(f.cx + f.e1x * k, f.cy + f.e1y * k, sqrt(R * R + k * k)))
                }
                // depth: straight toward the center
                toward(f.cx, f.cy)
            }
            GuideType.RULER -> {
                val ax = g.vpX(5, w); val ay = g.vpY(5, h); val bx = g.vpX(6, w); val by = g.vpY(6, h)
                var dx = bx - ax; var dy = by - ay
                val l = hypot(dx, dy)
                if (l > 1f) {
                    dx /= l; dy /= l
                    // close to the ruler: right on its edge; further away: parallel to it
                    val dist = abs(-(sx - ax) * dy + (sy - ay) * dx)
                    if (dist < hypot(w.toFloat(), h.toFloat()) * 0.03f) cands.add(Constraint.Line(ax, ay, dx, dy))
                    else cands.add(Constraint.Line(sx, sy, dx, dy))
                }
            }
            GuideType.ELLIPSE_GUIDE -> {
                // the ellipse, made bigger or smaller so it passes where the stroke starts
                val e = ellipse(g, w, h)
                val dx = sx - e.cx; val dy = sy - e.cy
                val u = dx * e.ux + dy * e.uy
                val v = -dx * e.uy + dy * e.ux
                val k = sqrt((u / e.a) * (u / e.a) + (v / e.b) * (v / e.b))
                // near the drawn ellipse: snap right onto it
                val kk = if (abs(k - 1f) < 0.12f) 1f else max(k, 0.02f)
                cands.add(Constraint.Ellipse(e.cx, e.cy, e.a * kk, e.b * kk, e.ux, e.uy))
            }
            GuideType.ELLIPSE_RULER -> {
                // strokes follow circles around the canvas center
                val cx = w / 2f; val cy = h / 2f
                val r = hypot(sx - cx, sy - cy)
                if (r > 1f) cands.add(Constraint.Circle(cx, cy, r))
            }
            GuideType.OFF -> {}
        }

        var best: Constraint? = null
        var bestErr = Float.MAX_VALUE
        for (c in cands) {
            // direction of the candidate at the start point
            val (dx, dy) = when (c) {
                is Constraint.Line -> c.dx to c.dy
                is Constraint.Circle -> {
                    val rx = (sx - c.cx) / c.r; val ry = (sy - c.cy) / c.r
                    -ry to rx // tangent
                }
                is Constraint.Ellipse -> c.tangent(sx, sy)
            }
            val err = abs(ux * dy - uy * dx) // |sin| of the angle between them (direction sign ignored)
            if (err < bestErr) { bestErr = err; best = c }
        }
        return best
    }

    fun angleOf(dx: Float, dy: Float) = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
}

/** Growable float list without boxing. */
class FloatList {
    private var a = FloatArray(1024)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    fun toArray(): FloatArray = a.copyOf(size)
}
