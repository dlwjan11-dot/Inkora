package com.abdulmedia.inkora

import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Shapes QuickShape can recognise. Points are in document coordinates. */
sealed class Shape {
    abstract val label: String
    abstract fun center(): PointF
    /** returns a new shape scaled + rotated around its center */
    abstract fun transformed(scale: Float, rotationRad: Float): Shape

    data class Line(val a: PointF, val b: PointF) : Shape() {
        override val label = "Line"
        override fun center() = PointF((a.x + b.x) / 2, (a.y + b.y) / 2)
        override fun transformed(scale: Float, rotationRad: Float) = this
    }

    data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angle: Float) : Shape() {
        override val label = if (abs(rx - ry) < 0.02f * max(rx, ry)) "Circle" else "Ellipse"
        override fun center() = PointF(cx, cy)
        override fun transformed(scale: Float, rotationRad: Float) = Ellipse(cx, cy, rx * scale, ry * scale, angle + rotationRad)
    }

    data class Poly(val pts: List<PointF>, val closed: Boolean) : Shape() {
        override val label = when {
            !closed -> "Polyline"
            pts.size == 3 -> "Triangle"
            pts.size == 4 -> "Rectangle"
            else -> "Polygon"
        }
        override fun center(): PointF {
            var x = 0f; var y = 0f
            pts.forEach { x += it.x; y += it.y }
            return PointF(x / pts.size, y / pts.size)
        }
        override fun transformed(scale: Float, rotationRad: Float): Shape {
            val c = center()
            val ca = cos(rotationRad); val sa = sin(rotationRad)
            return Poly(pts.map {
                val dx = (it.x - c.x) * scale
                val dy = (it.y - c.y) * scale
                PointF(c.x + dx * ca - dy * sa, c.y + dx * sa + dy * ca)
            }, closed)
        }
    }
}

/**
 * The perfect version of a shape (a finger touches the screen while QuickShape holds it):
 * an ellipse becomes a circle, a four-sided shape a square, any closed shape a regular one,
 * a line keeps to 15° steps.
 */
fun Shape.perfected(): Shape = when (this) {
    is Shape.Ellipse -> { val r = (rx + ry) / 2f; Shape.Ellipse(cx, cy, r, r, angle) }
    is Shape.Line -> {
        val len = kotlin.math.hypot(b.x - a.x, b.y - a.y)
        val ang = kotlin.math.atan2(b.y - a.y, b.x - a.x)
        val step = (Math.PI / 12).toFloat()
        val s = Math.round(ang / step) * step
        Shape.Line(a, PointF(a.x + len * cos(s), a.y + len * sin(s)))
    }
    is Shape.Poly -> if (!closed || pts.size < 3) this else {
        val c = center()
        val n = pts.size
        // same middle, same size (average distance of the corners), turned like its first corner
        val r = pts.map { kotlin.math.hypot(it.x - c.x, it.y - c.y) }.average().toFloat()
        val a0 = kotlin.math.atan2(pts[0].y - c.y, pts[0].x - c.x)
        // the corners keep the way they were drawn (clockwise or not)
        val cross = (pts[1].x - pts[0].x) * (pts[2].y - pts[0].y) - (pts[1].y - pts[0].y) * (pts[2].x - pts[0].x)
        val dir = if (cross >= 0) 1f else -1f
        Shape.Poly(List(n) { k -> val a = a0 + dir * 2f * Math.PI.toFloat() * k / n; PointF(c.x + r * cos(a), c.y + r * sin(a)) }, true)
    }
}

object QuickShape {

    fun detect(input: List<InputPoint>): Shape? {
        if (input.size < 4) return null
        // hand tremor makes the drawn path zig-zag; average it out before measuring
        val raw = smooth(input.map { PointF(it.x, it.y) }, 4)
        val length = pathLength(raw)
        if (length < 12f) return null
        val pts = resample(raw, 96)
        val a = PointF(input.first().x, input.first().y)
        val b = PointF(input.last().x, input.last().y)
        val chord = dist(a, b)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in pts) {
            minX = minOf(minX, p.x); minY = minOf(minY, p.y); maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
        }
        val diag = hypot(maxX - minX, maxY - minY)

        // 1. straight line: every point stays close to the line from start to end
        if (chord > 0.8f * diag && chord > 0.75f * length) {
            val maxDev = pts.maxOf { distToSegment(it, a, b) }
            if (maxDev < max(4f, 0.07f * chord)) return Shape.Line(a, b)
        }

        val closed = chord < 0.22f * length || chord < 0.18f * diag
        if (closed) {
            // 2. ellipse fit (principal axes)
            val ellipse = fitEllipse(pts)
            if (ellipse != null) return ellipse
            // 3. polygon
            val loop = pts.dropLast(1)
            val simplified = simplifyClosed(loop, 0.06f * diag)
            if (simplified.size in 3..8) return Shape.Poly(simplified, true)
            return null
        }

        // 4. open polyline (e.g. an "L" or zig-zag)
        val simplified = removeCollinear(rdp(pts, 0.035f * diag), false)
        if (simplified.size in 3..6) {
            // every segment must hug the drawn path
            return Shape.Poly(simplified, false)
        }
        return null
    }

    private fun fitEllipse(pts: List<PointF>): Shape.Ellipse? {
        var cx = 0f; var cy = 0f
        pts.forEach { cx += it.x; cy += it.y }
        cx /= pts.size; cy /= pts.size
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (p in pts) {
            val dx = p.x - cx; val dy = p.y - cy
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        sxx /= pts.size; syy /= pts.size; sxy /= pts.size
        val angle = 0.5f * atan2(2 * sxy, sxx - syy)
        val ca = cos(angle); val sa = sin(angle)
        var vu = 0f; var vw = 0f
        for (p in pts) {
            val dx = p.x - cx; val dy = p.y - cy
            val u = dx * ca + dy * sa
            val w = -dx * sa + dy * ca
            vu += u * u; vw += w * w
        }
        var rx = sqrt(2f * vu / pts.size)
        var ry = sqrt(2f * vw / pts.size)
        if (rx < 2f || ry < 2f) return null
        var err = 0f
        for (p in pts) {
            val dx = p.x - cx; val dy = p.y - cy
            val u = dx * ca + dy * sa
            val w = -dx * sa + dy * ca
            val v = sqrt((u / rx) * (u / rx) + (w / ry) * (w / ry))
            err += abs(v - 1f)
        }
        err /= pts.size
        if (err > 0.085f) return null
        // nearly round -> perfect circle
        if (minOf(rx, ry) / max(rx, ry) > 0.9f) {
            val r = (rx + ry) / 2
            rx = r; ry = r
        }
        return Shape.Ellipse(cx, cy, rx, ry, angle)
    }

    /** Dense points along the shape, for rendering with the brush. */
    fun toPoints(shape: Shape, step: Float = 1.5f): List<PointF> {
        val out = ArrayList<PointF>()
        when (shape) {
            is Shape.Line -> addEdge(out, shape.a, shape.b, step, true)
            is Shape.Ellipse -> {
                val perim = (2 * PI * sqrt((shape.rx * shape.rx + shape.ry * shape.ry) / 2)).toFloat()
                val n = max(24, (perim / step).toInt())
                val ca = cos(shape.angle); val sa = sin(shape.angle)
                for (i in 0..n + 2) { // small overlap so the joint is invisible
                    val t = (2 * PI * i / n).toFloat()
                    val u = cos(t) * shape.rx
                    val w = sin(t) * shape.ry
                    out.add(PointF(shape.cx + u * ca - w * sa, shape.cy + u * sa + w * ca))
                }
            }
            is Shape.Poly -> {
                val p = shape.pts
                for (i in 0 until p.size - 1) addEdge(out, p[i], p[i + 1], step, i == 0)
                if (shape.closed) {
                    addEdge(out, p.last(), p.first(), step, false)
                    addEdge(out, p.first(), p[1], step, false)
                }
            }
        }
        return out
    }

    private fun addEdge(out: MutableList<PointF>, a: PointF, b: PointF, step: Float, includeStart: Boolean) {
        val len = dist(a, b)
        val n = max(1, (len / step).toInt())
        for (i in (if (includeStart) 0 else 1)..n) {
            val t = i / n.toFloat()
            out.add(PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
        }
    }

    // ---------- geometry helpers ----------

    private fun dist(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)

    /** moving average over ±[r] points (ends kept in place) */
    private fun smooth(p: List<PointF>, r: Int): List<PointF> {
        if (p.size < 2 * r + 2) return p
        return p.indices.map { i ->
            if (i == 0 || i == p.size - 1) PointF(p[i].x, p[i].y) else {
                val a = maxOf(0, i - r); val b = minOf(p.size - 1, i + r)
                var x = 0f; var y = 0f
                for (k in a..b) { x += p[k].x; y += p[k].y }
                PointF(x / (b - a + 1), y / (b - a + 1))
            }
        }
    }

    private fun pathLength(p: List<PointF>): Float {
        var l = 0f
        for (i in 1 until p.size) l += dist(p[i - 1], p[i])
        return l
    }

    private fun resample(p: List<PointF>, n: Int): List<PointF> {
        val total = pathLength(p)
        val step = total / (n - 1)
        val out = ArrayList<PointF>(n)
        out.add(PointF(p[0].x, p[0].y))
        var acc = 0f
        var prev = PointF(p[0].x, p[0].y)
        var i = 1
        while (i < p.size && out.size < n) {
            val cur = p[i]
            val d = dist(prev, cur)
            if (acc + d >= step && d > 0f) {
                val t = (step - acc) / d
                val q = PointF(prev.x + (cur.x - prev.x) * t, prev.y + (cur.y - prev.y) * t)
                out.add(q)
                prev = q
                acc = 0f
            } else {
                acc += d
                prev = PointF(cur.x, cur.y)
                i++
            }
        }
        while (out.size < n) out.add(PointF(p.last().x, p.last().y))
        return out
    }

    private fun distToSegment(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        if (l2 == 0f) return dist(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private fun rdp(p: List<PointF>, eps: Float): List<PointF> {
        if (p.size < 3) return p
        var maxD = 0f
        var idx = 0
        for (i in 1 until p.size - 1) {
            val d = distToSegment(p[i], p.first(), p.last())
            if (d > maxD) { maxD = d; idx = i }
        }
        return if (maxD > eps) {
            val left = rdp(p.subList(0, idx + 1), eps)
            val right = rdp(p.subList(idx, p.size), eps)
            left.dropLast(1) + right
        } else listOf(p.first(), p.last())
    }

    private fun simplifyClosed(loop: List<PointF>, eps: Float): List<PointF> {
        if (loop.size < 6) return loop
        // split the loop at the point farthest from the start so RDP sees two halves
        val start = loop[0]
        var far = 0
        var farD = 0f
        for (i in loop.indices) {
            val d = dist(start, loop[i])
            if (d > farD) { farD = d; far = i }
        }
        val first = rdp(loop.subList(0, far + 1), eps)
        val second = rdp(loop.subList(far, loop.size) + listOf(start), eps)
        val merged = first.dropLast(1) + second.dropLast(1)
        return removeCollinear(merged, true)
    }

    /** Removes vertices whose corner is almost straight (< 25 degrees of turn). */
    private fun removeCollinear(input: List<PointF>, closed: Boolean): List<PointF> {
        val pts = input.toMutableList()
        var changed = true
        while (changed && pts.size > 3) {
            changed = false
            val range = if (closed) pts.indices else 1 until pts.size - 1
            for (i in range) {
                val prev = pts[(i - 1 + pts.size) % pts.size]
                val cur = pts[i]
                val next = pts[(i + 1) % pts.size]
                val v1x = cur.x - prev.x; val v1y = cur.y - prev.y
                val v2x = next.x - cur.x; val v2y = next.y - cur.y
                val l1 = hypot(v1x, v1y); val l2 = hypot(v2x, v2y)
                if (l1 < 1e-3f || l2 < 1e-3f) { pts.removeAt(i); changed = true; break }
                val cosA = ((v1x * v2x + v1y * v2y) / (l1 * l2)).coerceIn(-1f, 1f)
                val turn = Math.toDegrees(acos(cosA).toDouble())
                if (turn < 25.0) { pts.removeAt(i); changed = true; break }
            }
        }
        return pts
    }
}
