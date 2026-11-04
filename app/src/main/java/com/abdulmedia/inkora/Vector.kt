package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// =========================================================
// model
// =========================================================

/** An anchor point with its two Bézier handles (absolute document coordinates). */
class VNode(
    var x: Float, var y: Float,
    var inX: Float = x, var inY: Float = y,
    var outX: Float = x, var outY: Float = y,
    var smooth: Boolean = false
) {
    fun copy() = VNode(x, y, inX, inY, outX, outY, smooth)
    val hasIn get() = abs(inX - x) > 0.01f || abs(inY - y) > 0.01f
    val hasOut get() = abs(outX - x) > 0.01f || abs(outY - y) > 0.01f

    fun translate(dx: Float, dy: Float) {
        x += dx; y += dy; inX += dx; inY += dy; outX += dx; outY += dy
    }

    fun map(m: Matrix) {
        val p = floatArrayOf(x, y, inX, inY, outX, outY)
        m.mapPoints(p)
        x = p[0]; y = p[1]; inX = p[2]; inY = p[3]; outX = p[4]; outY = p[5]
    }

    fun collapseHandles() {
        inX = x; inY = y; outX = x; outY = y
        smooth = false
    }
}

class VContour(val nodes: MutableList<VNode>, var closed: Boolean) {
    fun copy() = VContour(nodes.map { it.copy() }.toMutableList(), closed)
}

enum class FillKind(val label: String) { NONE("None"), SOLID("Color"), LINEAR("Linear"), RADIAL("Radial") }

/** Fill or stroke paint. Gradient points are in document coordinates. */
class VPaint(
    var kind: FillKind,
    var color: Int,
    var color2: Int = 0xFFFFFFFF.toInt(),
    var x1: Float = 0f, var y1: Float = 0f, var x2: Float = 1f, var y2: Float = 0f
) {
    fun copy() = VPaint(kind, color, color2, x1, y1, x2, y2)

    fun shader(): Shader? = when (kind) {
        FillKind.LINEAR -> LinearGradient(x1, y1, x2, y2, color, color2, Shader.TileMode.CLAMP)
        FillKind.RADIAL -> RadialGradient(x1, y1, max(1f, hypot(x2 - x1, y2 - y1)), color, color2, Shader.TileMode.CLAMP)
        else -> null
    }

    /** places the gradient across a shape's bounds */
    fun fitTo(b: RectF) {
        when (kind) {
            FillKind.LINEAR -> { x1 = b.left; y1 = b.centerY(); x2 = b.right; y2 = b.centerY() }
            FillKind.RADIAL -> { x1 = b.centerX(); y1 = b.centerY(); x2 = b.right; y2 = b.bottom }
            else -> {}
        }
    }

    fun map(m: Matrix) {
        val p = floatArrayOf(x1, y1, x2, y2)
        m.mapPoints(p)
        x1 = p[0]; y1 = p[1]; x2 = p[2]; y2 = p[3]
    }
}

class VShape(
    val contours: MutableList<VContour>,
    var fill: VPaint,
    var stroke: VPaint,
    var strokeWidth: Float = 4f,
    var cap: Paint.Cap = Paint.Cap.ROUND,
    var join: Paint.Join = Paint.Join.ROUND,
    var dashed: Boolean = false,
    var opacity: Float = 1f,
    var evenOdd: Boolean = false
) {
    fun copy() = VShape(contours.map { it.copy() }.toMutableList(), fill.copy(), stroke.copy(),
        strokeWidth, cap, join, dashed, opacity, evenOdd)

    /** copies everything from [o] into this object (keeps identity for the editor) */
    fun setFrom(o: VShape) {
        contours.clear(); contours.addAll(o.contours.map { it.copy() })
        fill = o.fill.copy(); stroke = o.stroke.copy()
        strokeWidth = o.strokeWidth; cap = o.cap; join = o.join; dashed = o.dashed; opacity = o.opacity; evenOdd = o.evenOdd
    }

    fun toPath(): Path {
        val p = Path()
        p.fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        for (c in contours) {
            val n = c.nodes
            if (n.isEmpty()) continue
            p.moveTo(n[0].x, n[0].y)
            for (i in 1 until n.size) seg(p, n[i - 1], n[i])
            if (c.closed && n.size > 1) {
                seg(p, n.last(), n[0])
                p.close()
            }
        }
        return p
    }

    private fun seg(p: Path, a: VNode, b: VNode) {
        if (a.hasOut || b.hasIn) p.cubicTo(a.outX, a.outY, b.inX, b.inY, b.x, b.y) else p.lineTo(b.x, b.y)
    }

    fun bounds(): RectF {
        val r = RectF()
        toPath().computeBounds(r, true)
        return r
    }

    fun transform(m: Matrix) {
        contours.forEach { c -> c.nodes.forEach { it.map(m) } }
        fill.map(m)
        stroke.map(m)
    }

    fun draw(c: Canvas) {
        val path = toPath()
        val bounds = RectF()
        path.computeBounds(bounds, true)
        bounds.inset(-strokeWidth - 2, -strokeWidth - 2)
        val layered = opacity < 0.999f
        val save = if (layered) c.saveLayerAlpha(bounds, (opacity * 255).toInt().coerceIn(0, 255)) else -1
        if (fill.kind != FillKind.NONE) {
            fillPaint.shader = fill.shader()
            fillPaint.color = if (fill.kind == FillKind.SOLID) fill.color else 0xFF000000.toInt()
            c.drawPath(path, fillPaint)
        }
        if (stroke.kind != FillKind.NONE && strokeWidth > 0f) {
            strokePaint.shader = stroke.shader()
            strokePaint.color = if (stroke.kind == FillKind.SOLID) stroke.color else 0xFF000000.toInt()
            strokePaint.strokeWidth = strokeWidth
            strokePaint.strokeCap = cap
            strokePaint.strokeJoin = join
            strokePaint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(strokeWidth * 3f, strokeWidth * 2f), 0f) else null
            c.drawPath(path, strokePaint)
        }
        if (layered) c.restoreToCount(save)
    }

    companion object {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    }
}

/** The contents of a vector layer: shapes from back to front. */
class VectorData(val shapes: MutableList<VShape> = mutableListOf()) {
    fun copyShapes(): List<VShape> = shapes.map { it.copy() }

    fun copy() = VectorData(shapes.map { it.copy() }.toMutableList())

    /** Re-draws the layer's pixels from its shapes. */
    fun render(target: Bitmap) {
        target.eraseColor(0)
        val c = Canvas(target)
        for (s in shapes) s.draw(c)
    }
}

/** Undo step for anything done on a vector layer. */
class VectorEdit(private val layer: Layer, private val before: List<VShape>, private val after: List<VShape>) : Command {
    override val bytes: Long = 4096L + (before.size + after.size) * 512L
    private fun apply(list: List<VShape>) {
        val data = layer.vector ?: return
        data.shapes.clear()
        data.shapes.addAll(list.map { it.copy() })
        data.render(layer.bitmap)
    }
    override fun undo(doc: Document) = apply(before)
    override fun redo(doc: Document) = apply(after)
}

/** Undo step for turning a vector layer into pixels. */
class RasterizeCmd(private val layer: Layer, private val data: VectorData) : Command {
    override val bytes: Long = 4096
    override fun undo(doc: Document) {
        layer.vector = data
        data.render(layer.bitmap)
    }
    override fun redo(doc: Document) { layer.vector = null }
}

enum class BoolOp(val label: String, val op: Path.Op) {
    UNION("Unite", Path.Op.UNION),
    SUBTRACT("Minus front", Path.Op.DIFFERENCE),
    INTERSECT("Intersect", Path.Op.INTERSECT),
    EXCLUDE("Exclude", Path.Op.XOR)
}

// =========================================================
// geometry helpers
// =========================================================

object VGeom {
    private const val K = 0.5522848f // circle → Bézier

    fun style(fill: VPaint, stroke: VPaint, width: Float) = Triple(fill, stroke, width)

    fun rect(l: Float, t: Float, r: Float, b: Float, radius: Float = 0f): VContour {
        val x0 = min(l, r); val x1 = max(l, r); val y0 = min(t, b); val y1 = max(t, b)
        val rr = radius.coerceIn(0f, min(x1 - x0, y1 - y0) / 2)
        if (rr < 0.5f) return VContour(mutableListOf(VNode(x0, y0), VNode(x1, y0), VNode(x1, y1), VNode(x0, y1)), true)
        val k = rr * K
        return VContour(mutableListOf(
            VNode(x0 + rr, y0, inX = x0 + rr - k, inY = y0),
            VNode(x1 - rr, y0, outX = x1 - rr + k, outY = y0),
            VNode(x1, y0 + rr, inX = x1, inY = y0 + rr - k),
            VNode(x1, y1 - rr, outX = x1, outY = y1 - rr + k),
            VNode(x1 - rr, y1, inX = x1 - rr + k, inY = y1),
            VNode(x0 + rr, y1, outX = x0 + rr - k, outY = y1),
            VNode(x0, y1 - rr, inX = x0, inY = y1 - rr + k),
            VNode(x0, y0 + rr, outX = x0, outY = y0 + rr - k)
        ), true)
    }

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): VContour {
        val kx = rx * K; val ky = ry * K
        return VContour(mutableListOf(
            VNode(cx, cy - ry, cx - kx, cy - ry, cx + kx, cy - ry, true),
            VNode(cx + rx, cy, cx + rx, cy - ky, cx + rx, cy + ky, true),
            VNode(cx, cy + ry, cx + kx, cy + ry, cx - kx, cy + ry, true),
            VNode(cx - rx, cy, cx - rx, cy + ky, cx - rx, cy - ky, true)
        ), true)
    }

    fun polygon(cx: Float, cy: Float, r: Float, sides: Int, rotation: Float): VContour {
        val n = sides.coerceIn(3, 64)
        return VContour((0 until n).map {
            val a = rotation + (2 * Math.PI * it / n).toFloat() - (Math.PI / 2).toFloat()
            VNode(cx + cos(a) * r, cy + sin(a) * r)
        }.toMutableList(), true)
    }

    fun star(cx: Float, cy: Float, r: Float, points: Int, innerRatio: Float, rotation: Float): VContour {
        val n = points.coerceIn(3, 64)
        return VContour((0 until n * 2).map {
            val a = rotation + (Math.PI * it / n).toFloat() - (Math.PI / 2).toFloat()
            val rr = if (it % 2 == 0) r else r * innerRatio
            VNode(cx + cos(a) * rr, cy + sin(a) * rr)
        }.toMutableList(), true)
    }

    fun line(x0: Float, y0: Float, x1: Float, y1: Float) = VContour(mutableListOf(VNode(x0, y0), VNode(x1, y1)), false)

    /**
     * Freehand points → smooth Bézier curve: simplify, then Catmull-Rom tangents.
     */
    fun smoothCurve(pts: List<FloatArray>, tolerance: Float, closed: Boolean): VContour {
        val simple = simplify(pts, tolerance)
        val n = simple.size
        val nodes = ArrayList<VNode>(n)
        for (i in 0 until n) {
            val p = simple[i]
            val prev = when {
                i > 0 -> simple[i - 1]
                closed -> simple[n - 1]
                else -> p
            }
            val next = when {
                i < n - 1 -> simple[i + 1]
                closed -> simple[0]
                else -> p
            }
            val tx = (next[0] - prev[0]) / 6f
            val ty = (next[1] - prev[1]) / 6f
            val end = !closed && (i == 0 || i == n - 1)
            nodes.add(if (end) VNode(p[0], p[1]) else VNode(p[0], p[1], p[0] - tx, p[1] - ty, p[0] + tx, p[1] + ty, true))
        }
        // open curve ends: give them a handle toward the neighbour so the curve stays smooth
        if (!closed && n >= 3) {
            val a = nodes[0]; val b = nodes[1]
            a.outX = a.x + (b.inX - a.x) * 0.5f; a.outY = a.y + (b.inY - a.y) * 0.5f
            val z = nodes[n - 1]; val y = nodes[n - 2]
            z.inX = z.x + (y.outX - z.x) * 0.5f; z.inY = z.y + (y.outY - z.y) * 0.5f
        }
        return VContour(nodes, closed)
    }

    /** Ramer–Douglas–Peucker */
    fun simplify(pts: List<FloatArray>, eps: Float): List<FloatArray> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true; keep[pts.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, pts.size - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            var maxD = 0f; var idx = -1
            for (i in a + 1 until b) {
                val d = distToSeg(pts[i][0], pts[i][1], pts[a][0], pts[a][1], pts[b][0], pts[b][1])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > eps) {
                keep[idx] = true
                stack.addLast(intArrayOf(a, idx)); stack.addLast(intArrayOf(idx, b))
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    fun distToSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-6f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    // ---------- hit testing ----------

    /** Is (x, y) on the shape (inside its fill, or near its outline/stroke)? */
    fun hit(s: VShape, x: Float, y: Float, tol: Float): Boolean {
        val path = s.toPath()
        if (s.fill.kind != FillKind.NONE) {
            val b = RectF()
            path.computeBounds(b, true)
            if (b.contains(x, y)) {
                val region = Region()
                region.setPath(path, Region(b.left.toInt() - 2, b.top.toInt() - 2, b.right.toInt() + 2, b.bottom.toInt() + 2))
                if (region.contains(x.toInt(), y.toInt())) return true
            }
        }
        val reach = tol + (if (s.stroke.kind != FillKind.NONE) s.strokeWidth / 2 else 0f)
        return outlineDistance(path, x, y) <= reach
    }

    fun outlineDistance(path: Path, x: Float, y: Float): Float {
        val a = path.approximate(0.5f)
        var best = Float.MAX_VALUE
        var i = 3
        while (i + 2 < a.size) {
            // equal fractions = jump to the next contour, not a real segment
            if (a[i] != a[i - 3]) {
                val d = distToSeg(x, y, a[i - 2], a[i - 1], a[i + 1], a[i + 2])
                if (d < best) best = d
            }
            i += 3
        }
        return best
    }

    // ---------- splitting a segment (to add a point) ----------

    private fun bez(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val u = 1 - t
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3
    }

    /** Finds the closest point on the contour; returns (segment index, t, distance). */
    fun nearestOnContour(c: VContour, x: Float, y: Float): Triple<Int, Float, Float> {
        val n = c.nodes.size
        val segs = if (c.closed) n else n - 1
        var bestI = -1; var bestT = 0f; var bestD = Float.MAX_VALUE
        for (i in 0 until segs) {
            val a = c.nodes[i]; val b = c.nodes[(i + 1) % n]
            for (k in 0..40) {
                val t = k / 40f
                val px = bez(a.x, a.outX, b.inX, b.x, t)
                val py = bez(a.y, a.outY, b.inY, b.y, t)
                val d = hypot(px - x, py - y)
                if (d < bestD) { bestD = d; bestI = i; bestT = t }
            }
        }
        return Triple(bestI, bestT, bestD)
    }

    /** de Casteljau split: inserts a node at [t] on segment [i] without changing the curve's shape. */
    fun insertNode(c: VContour, i: Int, t: Float): VNode {
        val n = c.nodes.size
        val a = c.nodes[i]; val b = c.nodes[(i + 1) % n]
        fun lerp(p: Float, q: Float) = p + (q - p) * t
        val ax = lerp(a.x, a.outX); val ay = lerp(a.y, a.outY)
        val bx = lerp(a.outX, b.inX); val by = lerp(a.outY, b.inY)
        val cx = lerp(b.inX, b.x); val cy = lerp(b.inY, b.y)
        val dx = lerp(ax, bx); val dy = lerp(ay, by)
        val ex = lerp(bx, cx); val ey = lerp(by, cy)
        val fx = lerp(dx, ex); val fy = lerp(dy, ey)
        val curved = a.hasOut || b.hasIn
        val node = if (curved) VNode(fx, fy, dx, dy, ex, ey, true) else VNode(fx, fy)
        if (curved) {
            a.outX = ax; a.outY = ay
            b.inX = cx; b.inY = cy
        }
        c.nodes.add(i + 1, node)
        return node
    }

    // ---------- Path → shape (results of boolean operations) ----------

    fun fromPath(path: Path, template: VShape): VShape {
        val contours = mutableListOf<VContour>()
        if (Build.VERSION.SDK_INT >= 34) readWithIterator(path, contours) else readApproximate(path, contours)
        val s = template.copy()
        s.contours.clear()
        s.contours.addAll(contours.filter { it.nodes.size >= 2 })
        s.evenOdd = path.fillType == Path.FillType.EVEN_ODD || path.fillType == Path.FillType.INVERSE_EVEN_ODD
        return s
    }

    @android.annotation.TargetApi(34)
    private fun readWithIterator(path: Path, out: MutableList<VContour>) {
        val it = path.pathIterator
        var cur: VContour? = null
        var lx = 0f; var ly = 0f
        fun last() = cur!!.nodes.last()
        while (it.hasNext()) {
            val s = it.next()
            val p = s.points
            // some versions include the current point first in each segment
            val o = if (s.verb != android.graphics.PathIterator.VERB_MOVE && p.size >= 4 &&
                abs(p[0] - lx) < 0.01f && abs(p[1] - ly) < 0.01f) 2 else 0
            when (s.verb) {
                android.graphics.PathIterator.VERB_MOVE -> {
                    cur = VContour(mutableListOf(VNode(p[0], p[1])), false).also { out.add(it) }
                    lx = p[0]; ly = p[1]
                }
                android.graphics.PathIterator.VERB_LINE -> {
                    cur?.nodes?.add(VNode(p[o], p[o + 1])); lx = p[o]; ly = p[o + 1]
                }
                android.graphics.PathIterator.VERB_QUAD, android.graphics.PathIterator.VERB_CONIC -> {
                    if (cur == null) continue
                    val w = if (s.verb == android.graphics.PathIterator.VERB_CONIC) s.conicWeight else 1f
                    val k = if (s.verb == android.graphics.PathIterator.VERB_CONIC) 4f * w / (3f * (1f + w)) else 2f / 3f
                    val c1x = lx + (p[o] - lx) * k; val c1y = ly + (p[o + 1] - ly) * k
                    val ex = p[o + 2]; val ey = p[o + 3]
                    val c2x = ex + (p[o] - ex) * k; val c2y = ey + (p[o + 1] - ey) * k
                    last().outX = c1x; last().outY = c1y
                    cur!!.nodes.add(VNode(ex, ey, c2x, c2y, ex, ey))
                    lx = ex; ly = ey
                }
                android.graphics.PathIterator.VERB_CUBIC -> {
                    if (cur == null) continue
                    last().outX = p[o]; last().outY = p[o + 1]
                    cur!!.nodes.add(VNode(p[o + 4], p[o + 5], p[o + 2], p[o + 3], p[o + 4], p[o + 5]))
                    lx = p[o + 4]; ly = p[o + 5]
                }
                android.graphics.PathIterator.VERB_CLOSE -> {
                    val c = cur ?: continue
                    c.closed = true
                    mergeClosingNode(c)
                }
            }
        }
    }

    private fun mergeClosingNode(c: VContour) {
        if (c.nodes.size < 3) return
        val f = c.nodes.first(); val l = c.nodes.last()
        if (abs(f.x - l.x) < 0.05f && abs(f.y - l.y) < 0.05f) {
            f.inX = l.inX; f.inY = l.inY
            c.nodes.removeAt(c.nodes.size - 1)
        }
    }

    private fun readApproximate(path: Path, out: MutableList<VContour>) {
        val a = path.approximate(0.4f)
        var cur: VContour? = null
        var i = 0
        while (i + 2 < a.size) {
            val newContour = cur == null || (i >= 3 && a[i] == a[i - 3])
            if (newContour) {
                cur?.let { c -> c.closed = true; mergeClosingNode(c) }
                cur = VContour(mutableListOf(), false).also { out.add(it) }
            }
            cur!!.nodes.add(VNode(a[i + 1], a[i + 2]))
            i += 3
        }
        cur?.let { c -> c.closed = true; mergeClosingNode(c) }
    }

    fun boolean(shapes: List<VShape>, op: BoolOp): VShape? {
        if (shapes.size < 2) return null
        val acc = Path(shapes[0].toPath())
        for (i in 1 until shapes.size) acc.op(shapes[i].toPath(), op.op)
        val r = fromPath(acc, shapes[0])
        return if (r.contours.isEmpty()) null else r
    }

    // =========================================================
    // save / load
    // =========================================================

    private fun paintJson(p: VPaint) = JSONObject().apply {
        put("k", p.kind.name); put("c", p.color); put("c2", p.color2)
        put("g", JSONArray(listOf(p.x1.toDouble(), p.y1.toDouble(), p.x2.toDouble(), p.y2.toDouble())))
    }

    private fun paintFrom(o: JSONObject?): VPaint {
        if (o == null) return VPaint(FillKind.NONE, 0xFF000000.toInt())
        val g = o.optJSONArray("g")
        return VPaint(runCatching { FillKind.valueOf(o.optString("k")) }.getOrDefault(FillKind.NONE),
            o.optInt("c", 0xFF000000.toInt()), o.optInt("c2", -1),
            g?.optDouble(0)?.toFloat() ?: 0f, g?.optDouble(1)?.toFloat() ?: 0f,
            g?.optDouble(2)?.toFloat() ?: 1f, g?.optDouble(3)?.toFloat() ?: 0f)
    }

    fun toJson(d: VectorData): JSONArray {
        val arr = JSONArray()
        for (s in d.shapes) {
            val cs = JSONArray()
            for (c in s.contours) {
                val ns = JSONArray()
                for (n in c.nodes) ns.put(JSONArray(listOf(n.x, n.y, n.inX, n.inY, n.outX, n.outY).map { it.toDouble() }).put(if (n.smooth) 1 else 0))
                cs.put(JSONObject().put("closed", c.closed).put("n", ns))
            }
            arr.put(JSONObject().apply {
                put("c", cs); put("fill", paintJson(s.fill)); put("stroke", paintJson(s.stroke))
                put("w", s.strokeWidth.toDouble()); put("cap", s.cap.name); put("join", s.join.name)
                put("dash", s.dashed); put("op", s.opacity.toDouble()); put("eo", s.evenOdd)
            })
        }
        return arr
    }

    fun fromJson(arr: JSONArray): VectorData {
        val d = VectorData()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cs = o.optJSONArray("c") ?: continue
            val contours = mutableListOf<VContour>()
            for (j in 0 until cs.length()) {
                val co = cs.optJSONObject(j) ?: continue
                val ns = co.optJSONArray("n") ?: continue
                val nodes = mutableListOf<VNode>()
                for (k in 0 until ns.length()) {
                    val a = ns.optJSONArray(k) ?: continue
                    fun f(idx: Int) = a.optDouble(idx).toFloat()
                    nodes.add(VNode(f(0), f(1), f(2), f(3), f(4), f(5), a.optInt(6) == 1))
                }
                contours.add(VContour(nodes, co.optBoolean("closed")))
            }
            d.shapes.add(VShape(contours, paintFrom(o.optJSONObject("fill")), paintFrom(o.optJSONObject("stroke")),
                o.optDouble("w", 4.0).toFloat(),
                runCatching { Paint.Cap.valueOf(o.optString("cap")) }.getOrDefault(Paint.Cap.ROUND),
                runCatching { Paint.Join.valueOf(o.optString("join")) }.getOrDefault(Paint.Join.ROUND),
                o.optBoolean("dash"), o.optDouble("op", 1.0).toFloat(), o.optBoolean("eo")))
        }
        return d
    }
}
