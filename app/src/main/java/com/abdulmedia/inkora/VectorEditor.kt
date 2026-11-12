package com.abdulmedia.inkora

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

enum class VTool(val label: String, val icon: Icon) {
    SELECT("Select", Icon.TRANSFORM),
    DIRECT("Points", Icon.V_DIRECT),
    PEN("Pen", Icon.V_PEN),
    PENCIL("Pencil", Icon.V_PENCIL),
    RECT("Rectangle", Icon.V_RECT),
    ELLIPSE("Ellipse", Icon.V_ELLIPSE),
    POLYGON("Polygon", Icon.V_POLYGON),
    STAR("Star", Icon.V_STAR),
    LINE("Line", Icon.V_LINE)
}

/**
 * Everything the user does on a vector layer: tools, selection, handles, undo.
 * Coordinates: input arrives in screen pixels and is converted with [Host.toDoc].
 */
class VectorEditor(private val host: Host) {

    interface Host {
        val doc: Document
        fun toDoc(x: Float, y: Float): PointF
        fun toScreen(x: Float, y: Float): PointF
        fun viewMatrix(): Matrix
        fun zoom(): Float
        fun density(): Float
        fun redraw()
        fun push(cmd: Command)
        fun message(s: String)
        fun currentColor(): Int
        fun selectionChanged()
    }

    var tool = VTool.PEN
        set(v) {
            if (field != v) finishPen()
            field = v
            host.selectionChanged()
            host.redraw()
        }

    // ---- style for new shapes ----
    var fillKind = FillKind.SOLID
    var strokeOn = false
    var strokeColor = 0xFF1F2026.toInt()
    var strokeWidth = 6f
    var cornerRadius = 0f
    var polygonSides = 6
    var starPoints = 5
    /** square / circle / 45° lines */
    var constrain = false

    val selected = LinkedHashSet<VShape>()
    var activeNode: VNode? = null
        private set

    private val data: VectorData? get() = host.doc.active.vector
    private val layer: Layer get() = host.doc.active

    // ---- drag state ----
    private enum class Drag { NONE, MOVE, SCALE, ROTATE, MARQUEE, NODE, HANDLE_IN, HANDLE_OUT, CREATE, PENCIL, PEN_NODE }
    private var drag = Drag.NONE
    private var down = PointF()
    private var downScreen = PointF()
    private var moved = false
    private var before: List<VShape>? = null
    private val originals = HashMap<VShape, VShape>()
    private var pivot = PointF()
    private var dragNode: VNode? = null
    private var creating: VShape? = null
    private val pencilPts = ArrayList<FloatArray>()
    private var marquee = RectF()
    private var hover: PointF? = null

    // ---- pen ----
    private var penShape: VShape? = null
    private var penClosing = false

    private val d get() = host.density()

    // =========================================================
    // input
    // =========================================================

    fun onDown(sx: Float, sy: Float) {
        val data = data ?: return
        downScreen = PointF(sx, sy)
        down = host.toDoc(sx, sy)
        moved = false
        drag = Drag.NONE
        when (tool) {
            VTool.SELECT -> downSelect(sx, sy, data)
            VTool.DIRECT -> downDirect(sx, sy, data)
            VTool.PEN -> downPen(sx, sy, data)
            VTool.PENCIL -> {
                drag = Drag.PENCIL
                pencilPts.clear()
                pencilPts.add(floatArrayOf(down.x, down.y))
            }
            else -> {
                drag = Drag.CREATE
                before = data.copyShapes()
                creating = null
            }
        }
        host.redraw()
    }

    fun onMove(sx: Float, sy: Float) {
        val data = data ?: return
        if (!moved && hypot(sx - downScreen.x, sy - downScreen.y) > 5f * d) moved = true
        val p = host.toDoc(sx, sy)
        when (drag) {
            Drag.MOVE, Drag.SCALE, Drag.ROTATE -> if (moved) applyTransform(p)
            Drag.MARQUEE -> marquee.set(min(down.x, p.x), min(down.y, p.y), max(down.x, p.x), max(down.y, p.y))
            Drag.NODE -> if (moved) moveNode(p)
            Drag.HANDLE_IN, Drag.HANDLE_OUT -> moveHandle(p)
            Drag.PEN_NODE -> if (moved) penDragHandle(p)
            Drag.PENCIL -> pencilPts.add(floatArrayOf(p.x, p.y))
            Drag.CREATE -> if (moved) updateCreate(data, p)
            Drag.NONE -> {}
        }
        if (drag != Drag.MARQUEE && drag != Drag.PENCIL && drag != Drag.NONE) data.render(layer.bitmap)
        host.redraw()
    }

    fun onUp(sx: Float, sy: Float) {
        val data = data ?: return
        val p = host.toDoc(sx, sy)
        when (drag) {
            Drag.MOVE, Drag.SCALE, Drag.ROTATE, Drag.NODE, Drag.HANDLE_IN, Drag.HANDLE_OUT -> {
                if (moved) commit() else before = null
            }
            Drag.MARQUEE -> {
                if (moved) {
                    data.shapes.filter { RectF.intersects(it.bounds(), marquee) && marquee.contains(it.bounds()) }
                        .forEach { selected.add(it) }
                }
                marquee.setEmpty()
                host.selectionChanged()
            }
            Drag.PEN_NODE -> {
                if (penClosing) finishPen()
            }
            Drag.PENCIL -> finishPencil(data, p)
            Drag.CREATE -> {
                val c = creating
                if (c != null && moved) {
                    selected.clear(); selected.add(c)
                    commit()
                    host.selectionChanged()
                } else {
                    before = null
                    host.message("Drag to draw the shape")
                }
                creating = null
            }
            Drag.NONE -> {}
        }
        drag = Drag.NONE
        dragNode = null
        data.render(layer.bitmap)
        host.redraw()
    }

    fun onCancel() {
        val b = before
        val data = data
        if (b != null && data != null) {
            data.shapes.clear(); data.shapes.addAll(b.map { it.copy() })
            selected.clear()
            data.render(layer.bitmap)
        }
        before = null
        drag = Drag.NONE
        creating = null
        host.redraw()
    }

    fun onHover(sx: Float, sy: Float) {
        hover = PointF(sx, sy)
        if (tool == VTool.PEN && penShape != null) host.redraw()
    }

    // ---------- Select tool ----------

    private fun selectionBounds(): RectF? {
        if (selected.isEmpty()) return null
        val r = RectF()
        var first = true
        for (s in selected) {
            val b = s.bounds()
            if (first) { r.set(b); first = false } else r.union(b)
        }
        return r
    }

    /** screen corners of the selection box: TL, TR, BR, BL */
    private fun boxScreen(b: RectF): FloatArray {
        val pts = floatArrayOf(b.left, b.top, b.right, b.top, b.right, b.bottom, b.left, b.bottom)
        host.viewMatrix().mapPoints(pts)
        return pts
    }

    private fun rotateHandleScreen(c: FloatArray): PointF {
        val mx = (c[0] + c[2]) / 2; val my = (c[1] + c[3]) / 2
        val cx = (c[0] + c[4]) / 2; val cy = (c[1] + c[5]) / 2
        var dx = mx - cx; var dy = my - cy
        val l = max(1f, hypot(dx, dy))
        dx /= l; dy /= l
        return PointF(mx + dx * 36f * d, my + dy * 36f * d)
    }

    private fun topHit(data: VectorData, p: PointF): VShape? {
        val tol = 10f * d / host.zoom()
        return data.shapes.lastOrNull { VGeom.hit(it, p.x, p.y, tol) }
    }

    private fun downSelect(sx: Float, sy: Float, data: VectorData) {
        val b = selectionBounds()
        if (b != null) {
            val c = boxScreen(b)
            val rh = rotateHandleScreen(c)
            val reach = 26f * d
            if (hypot(sx - rh.x, sy - rh.y) < reach) { startTransform(Drag.ROTATE, b, data); return }
            for (i in 0 until 4) {
                if (hypot(sx - c[i * 2], sy - c[i * 2 + 1]) < reach) {
                    // scale around the opposite corner
                    val o = (i + 2) % 4
                    startTransform(Drag.SCALE, b, data, host.toDoc(c[o * 2], c[o * 2 + 1]))
                    return
                }
            }
        }
        val hit = topHit(data, down)
        when {
            hit != null && hit in selected -> startTransform(Drag.MOVE, b!!, data)
            hit != null -> {
                if (!multi) selected.clear()
                selected.add(hit)
                host.selectionChanged()
                startTransform(Drag.MOVE, selectionBounds()!!, data)
            }
            else -> {
                if (!multi) { selected.clear(); host.selectionChanged() }
                drag = Drag.MARQUEE
                marquee.set(down.x, down.y, down.x, down.y)
            }
        }
    }

    /** add to the selection instead of replacing it */
    var multi = false

    private fun startTransform(kind: Drag, b: RectF, data: VectorData, anchor: PointF? = null) {
        drag = kind
        before = data.copyShapes()
        originals.clear()
        selected.forEach { originals[it] = it.copy() }
        pivot = anchor ?: PointF(b.centerX(), b.centerY())
    }

    private fun applyTransform(p: PointF) {
        val m = Matrix()
        when (drag) {
            Drag.MOVE -> {
                var dx = p.x - down.x; var dy = p.y - down.y
                if (constrain) { if (abs(dx) > abs(dy)) dy = 0f else dx = 0f }
                m.setTranslate(snap(dx), snap(dy))
            }
            Drag.SCALE -> {
                val dx0 = down.x - pivot.x; val dy0 = down.y - pivot.y
                val dx1 = p.x - pivot.x; val dy1 = p.y - pivot.y
                var sx = if (abs(dx0) > 0.5f) dx1 / dx0 else 1f
                var sy = if (abs(dy0) > 0.5f) dy1 / dy0 else 1f
                if (constrain) { val s = max(abs(sx), abs(sy)); sx = s * sign(sx).let { if (it == 0f) 1f else it }; sy = s * sign(sy).let { if (it == 0f) 1f else it } }
                m.setScale(sx, sy, pivot.x, pivot.y)
            }
            Drag.ROTATE -> {
                val a0 = Math.toDegrees(atan2((down.y - pivot.y).toDouble(), (down.x - pivot.x).toDouble()))
                val a1 = Math.toDegrees(atan2((p.y - pivot.y).toDouble(), (p.x - pivot.x).toDouble()))
                var a = (a1 - a0).toFloat()
                val step = if (constrain) 15f else 45f
                val snapped = (a / step).roundToInt() * step
                if (constrain || abs(a - snapped) < 3f) a = snapped
                m.setRotate(a, pivot.x, pivot.y)
            }
            else -> return
        }
        for ((s, o) in originals) {
            s.setFrom(o)
            s.transform(m)
        }
    }

    // ---------- Direct selection (points) ----------

    private fun downDirect(sx: Float, sy: Float, data: VectorData) {
        val reach = 22f * d
        // 1. handles and points of selected shapes
        for (s in selected) for (c in s.contours) for (n in c.nodes) {
            val nn = host.toScreen(n.x, n.y)
            if (n === activeNode || n.smooth) {
                if (n.hasIn) { val h = host.toScreen(n.inX, n.inY); if (hypot(sx - h.x, sy - h.y) < reach) { beginNodeDrag(Drag.HANDLE_IN, n, data); return } }
                if (n.hasOut) { val h = host.toScreen(n.outX, n.outY); if (hypot(sx - h.x, sy - h.y) < reach) { beginNodeDrag(Drag.HANDLE_OUT, n, data); return } }
            }
            if (hypot(sx - nn.x, sy - nn.y) < reach) { beginNodeDrag(Drag.NODE, n, data); return }
        }
        // 2. tap on the outline of a selected shape adds a point there
        val tol = 12f * d / host.zoom()
        for (s in selected) for (c in s.contours) {
            if (c.nodes.size < 2) continue
            val (i, t, dist) = VGeom.nearestOnContour(c, down.x, down.y)
            if (i >= 0 && dist < tol && t > 0.02f && t < 0.98f) {
                before = data.copyShapes()
                activeNode = VGeom.insertNode(c, i, t)
                commit()
                host.message("Point added")
                host.selectionChanged()
                return
            }
        }
        // 3. pick a shape
        val hit = topHit(data, down)
        selected.clear()
        activeNode = null
        if (hit != null) selected.add(hit)
        host.selectionChanged()
    }

    private fun beginNodeDrag(kind: Drag, n: VNode, data: VectorData) {
        drag = kind
        dragNode = n
        activeNode = n
        before = data.copyShapes()
        nodeStart = n.copy()
        host.selectionChanged()
    }

    private var nodeStart = VNode(0f, 0f)

    private fun moveNode(p: PointF) {
        val n = dragNode ?: return
        val s = nodeStart
        val tx = snapAbs(s.x + p.x - down.x)
        val ty = snapAbs(s.y + p.y - down.y)
        n.x = s.x; n.y = s.y; n.inX = s.inX; n.inY = s.inY; n.outX = s.outX; n.outY = s.outY
        n.translate(tx - s.x, ty - s.y)
    }

    private fun moveHandle(p: PointF) {
        val n = dragNode ?: return
        if (drag == Drag.HANDLE_IN) { n.inX = p.x; n.inY = p.y } else { n.outX = p.x; n.outY = p.y }
        if (n.smooth) {
            // keep the opposite handle on a straight line (its own length)
            val (hx, hy) = if (drag == Drag.HANDLE_IN) n.inX to n.inY else n.outX to n.outY
            val (ox, oy) = if (drag == Drag.HANDLE_IN) n.outX to n.outY else n.inX to n.inY
            val len = hypot(ox - n.x, oy - n.y)
            val dx = n.x - hx; val dy = n.y - hy
            val l = max(0.001f, hypot(dx, dy))
            val nx = n.x + dx / l * len; val ny = n.y + dy / l * len
            if (drag == Drag.HANDLE_IN) { n.outX = nx; n.outY = ny } else { n.inX = nx; n.inY = ny }
        }
    }

    /** smooth ⇄ corner for the active point */
    fun toggleSmooth() {
        val n = activeNode ?: run { host.message("Tap a point first"); return }
        val data = data ?: return
        before = data.copyShapes()
        if (n.smooth || n.hasIn || n.hasOut) n.collapseHandles()
        else {
            // build handles from the neighbours' direction
            val c = selected.flatMap { it.contours }.firstOrNull { n in it.nodes } ?: return
            val i = c.nodes.indexOf(n)
            val prev = c.nodes.getOrNull(i - 1) ?: if (c.closed) c.nodes.last() else n
            val next = c.nodes.getOrNull(i + 1) ?: if (c.closed) c.nodes.first() else n
            val tx = (next.x - prev.x) / 6f; val ty = (next.y - prev.y) / 6f
            n.inX = n.x - tx; n.inY = n.y - ty; n.outX = n.x + tx; n.outY = n.y + ty
            n.smooth = true
        }
        commit()
    }

    fun deletePoint() {
        val n = activeNode ?: run { host.message("Tap a point first"); return }
        val data = data ?: return
        before = data.copyShapes()
        for (s in selected) for (c in s.contours) c.nodes.remove(n)
        // remove empty pieces / shapes
        selected.forEach { s -> s.contours.removeAll { it.nodes.size < 2 } }
        data.shapes.removeAll { it.contours.isEmpty() }
        selected.removeAll { it.contours.isEmpty() }
        activeNode = null
        commit()
        host.selectionChanged()
    }

    // ---------- Pen ----------

    private fun newStyledShape(contour: VContour, closedShape: Boolean): VShape {
        val color = host.currentColor() or 0xFF000000.toInt()
        val fill = if (closedShape && fillKind != FillKind.NONE) VPaint(fillKind, color, 0xFFFFFFFF.toInt()) else VPaint(FillKind.NONE, color)
        val stroke = if (strokeOn || !closedShape || fill.kind == FillKind.NONE)
            VPaint(FillKind.SOLID, if (strokeOn) strokeColor else color) else VPaint(FillKind.NONE, strokeColor)
        return VShape(mutableListOf(contour), fill, stroke, strokeWidth)
    }

    private fun downPen(sx: Float, sy: Float, data: VectorData) {
        val shape = penShape
        val p = PointF(snapAbs(down.x), snapAbs(down.y))
        if (shape == null) {
            before = data.copyShapes()
            val c = VContour(mutableListOf(VNode(p.x, p.y)), false)
            val s = newStyledShape(c, closedShape = false)
            // pen paths get the fill color as their stroke until they are closed
            data.shapes.add(s)
            penShape = s
            selected.clear(); selected.add(s)
            dragNode = c.nodes[0]
            penClosing = false
        } else {
            val c = shape.contours[0]
            val first = c.nodes[0]
            val fs = host.toScreen(first.x, first.y)
            if (c.nodes.size >= 2 && hypot(sx - fs.x, sy - fs.y) < 22f * d) {
                c.closed = true
                penClosing = true
                dragNode = first
                // a closed pen path becomes a filled shape
                if (fillKind != FillKind.NONE) {
                    shape.fill = VPaint(fillKind, host.currentColor() or 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
                    shape.fill.fitTo(shape.bounds())
                    if (!strokeOn) shape.stroke.kind = FillKind.NONE
                }
            } else {
                val n = VNode(p.x, p.y)
                c.nodes.add(n)
                dragNode = n
            }
        }
        drag = Drag.PEN_NODE
        data.render(layer.bitmap)
    }

    /** dragging while placing a point pulls out symmetric handles = a smooth curve */
    private fun penDragHandle(p: PointF) {
        val n = dragNode ?: return
        n.outX = p.x; n.outY = p.y
        n.inX = 2 * n.x - p.x; n.inY = 2 * n.y - p.y
        n.smooth = true
    }

    /** Ends the path being drawn with the pen (also called when changing tool). */
    fun finishPen() {
        val s = penShape ?: return
        val data = data
        penShape = null
        penClosing = false
        if (data == null) return
        if (s.contours[0].nodes.size < 2) {
            data.shapes.remove(s)
            selected.remove(s)
            data.render(layer.bitmap)
            before = null
        } else commit()
        host.selectionChanged()
        host.redraw()
    }

    val penActive get() = penShape != null

    // ---------- Pencil ----------

    private fun finishPencil(data: VectorData, p: PointF) {
        if (pencilPts.size < 3 || !moved) { pencilPts.clear(); return }
        val z = host.zoom()
        val first = pencilPts.first()
        val closed = pencilPts.size > 10 && hypot(p.x - first[0], p.y - first[1]) * z < 18f * d
        val contour = VGeom.smoothCurve(pencilPts, 2.5f / z, closed)
        before = data.copyShapes()
        val s = newStyledShape(contour, closedShape = closed)
        if (closed) s.fill.fitTo(s.bounds())
        data.shapes.add(s)
        selected.clear(); selected.add(s)
        pencilPts.clear()
        commit()
        host.selectionChanged()
    }

    // ---------- shape tools ----------

    private fun updateCreate(data: VectorData, p0: PointF) {
        var x1 = snapAbs(p0.x); var y1 = snapAbs(p0.y)
        val x0 = snapAbs(down.x); val y0 = snapAbs(down.y)
        if (constrain && tool != VTool.LINE) {
            val s = max(abs(x1 - x0), abs(y1 - y0))
            x1 = x0 + s * (if (x1 >= x0) 1 else -1)
            y1 = y0 + s * (if (y1 >= y0) 1 else -1)
        }
        if (constrain && tool == VTool.LINE) {
            val a = Math.round(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()) / (Math.PI / 4)) * (Math.PI / 4)
            val len = hypot(x1 - x0, y1 - y0)
            x1 = x0 + (Math.cos(a) * len).toFloat(); y1 = y0 + (Math.sin(a) * len).toFloat()
        }
        val r = hypot(x1 - x0, y1 - y0)
        val rot = atan2(y1 - y0, x1 - x0) + (Math.PI / 2).toFloat()
        val contour = when (tool) {
            VTool.RECT -> VGeom.rect(x0, y0, x1, y1, cornerRadius)
            VTool.ELLIPSE -> VGeom.ellipse((x0 + x1) / 2, (y0 + y1) / 2, abs(x1 - x0) / 2, abs(y1 - y0) / 2)
            VTool.POLYGON -> VGeom.polygon(x0, y0, r, polygonSides, if (constrain) 0f else rot)
            VTool.STAR -> VGeom.star(x0, y0, r, starPoints, 0.45f, if (constrain) 0f else rot)
            VTool.LINE -> VGeom.line(x0, y0, x1, y1)
            else -> return
        }
        val c = creating
        if (c == null) {
            val s = newStyledShape(contour, closedShape = tool != VTool.LINE)
            data.shapes.add(s)
            creating = s
        } else {
            c.contours.clear(); c.contours.add(contour)
        }
        creating?.let { s -> s.fill.fitTo(s.bounds()) }
    }

    // ---------- grid snapping ----------

    /** Snaps to the 2D / dot grid when the guide is on and Assisted Drawing is enabled. */
    private fun gridStep(): Float? {
        val g = host.doc.guide
        if (!g.assisted) return null
        if (g.type != GuideType.SQUARE && g.type != GuideType.DOTS) return null
        return g.size.coerceAtLeast(GuideSettings.MIN_SIZE)
    }

    private fun snapAbs(v: Float): Float {
        val s = gridStep() ?: return v
        // the grid is centered on the canvas
        val ox = host.doc.width / 2f % s
        return ((v - ox) / s).roundToInt() * s + ox
    }

    private fun snap(delta: Float): Float {
        val s = gridStep() ?: return delta
        return (delta / s).roundToInt() * s
    }

    // =========================================================
    // commands on the selection
    // =========================================================

    private fun commit() {
        val data = data ?: return
        val b = before ?: return
        before = null
        data.render(layer.bitmap)
        host.push(VectorEdit(layer, b, data.copyShapes()))
    }

    private fun edit(block: (VectorData) -> Unit) {
        val data = data ?: return
        before = data.copyShapes()
        block(data)
        commit()
        host.redraw()
    }

    fun deleteSelected() {
        if (selected.isEmpty()) { host.message("Nothing selected"); return }
        edit { data -> data.shapes.removeAll { it in selected } }
        selected.clear()
        activeNode = null
        host.selectionChanged()
    }

    fun duplicateSelected() {
        if (selected.isEmpty()) { host.message("Nothing selected"); return }
        val off = 20f / max(0.2f, host.zoom())
        edit { data ->
            val copies = data.shapes.filter { it in selected }.map { it.copy().also { c -> c.transform(Matrix().apply { setTranslate(off, off) }) } }
            data.shapes.addAll(copies)
            selected.clear(); selected.addAll(copies)
        }
        host.selectionChanged()
    }

    fun arrange(how: String) {
        if (selected.isEmpty()) { host.message("Nothing selected"); return }
        edit { data ->
            val sel = data.shapes.filter { it in selected }
            when (how) {
                "front" -> { data.shapes.removeAll(sel); data.shapes.addAll(sel) }
                "back" -> { data.shapes.removeAll(sel); data.shapes.addAll(0, sel) }
                "forward" -> for (s in sel.reversed()) {
                    val i = data.shapes.indexOf(s)
                    if (i < data.shapes.size - 1) { data.shapes.removeAt(i); data.shapes.add(i + 1, s) }
                }
                "backward" -> for (s in sel) {
                    val i = data.shapes.indexOf(s)
                    if (i > 0) { data.shapes.removeAt(i); data.shapes.add(i - 1, s) }
                }
            }
        }
    }

    fun align(how: String) {
        if (selected.isEmpty()) { host.message("Nothing selected"); return }
        // one shape → align to the canvas; several → align to their common box
        val ref = if (selected.size == 1) RectF(0f, 0f, host.doc.width.toFloat(), host.doc.height.toFloat()) else selectionBounds()!!
        edit {
            for (s in selected) {
                val b = s.bounds()
                val dx = when (how) {
                    "left" -> ref.left - b.left
                    "hcenter" -> ref.centerX() - b.centerX()
                    "right" -> ref.right - b.right
                    else -> 0f
                }
                val dy = when (how) {
                    "top" -> ref.top - b.top
                    "vcenter" -> ref.centerY() - b.centerY()
                    "bottom" -> ref.bottom - b.bottom
                    else -> 0f
                }
                s.transform(Matrix().apply { setTranslate(dx, dy) })
            }
        }
    }

    fun flip(horizontal: Boolean) {
        val b = selectionBounds() ?: run { host.message("Nothing selected"); return }
        edit {
            val m = Matrix().apply { if (horizontal) setScale(-1f, 1f, b.centerX(), b.centerY()) else setScale(1f, -1f, b.centerX(), b.centerY()) }
            selected.forEach { it.transform(m) }
        }
    }

    fun booleanOp(op: BoolOp) {
        val data = data ?: return
        val sel = data.shapes.filter { it in selected }
        if (sel.size < 2) { host.message("Select 2 or more shapes (turn on Multi-select)"); return }
        val result = VGeom.boolean(sel, op)
        edit { d ->
            val idx = d.shapes.indexOf(sel[0])
            d.shapes.removeAll(sel)
            if (result != null) d.shapes.add(idx.coerceIn(0, d.shapes.size), result)
        }
        selected.clear()
        result?.let { selected.add(it) }
        if (result == null) host.message("Nothing left after ${op.label}")
        host.selectionChanged()
    }

    fun selectAll() {
        val data = data ?: return
        selected.clear(); selected.addAll(data.shapes)
        host.selectionChanged(); host.redraw()
    }

    fun deselect() {
        finishPen()
        selected.clear()
        activeNode = null
        host.selectionChanged(); host.redraw()
    }

    /** ColorDrop on a vector layer recolors the shape under the finger. */
    fun dropColor(sx: Float, sy: Float, color: Int): Boolean {
        val data = data ?: return false
        val hit = topHit(data, host.toDoc(sx, sy)) ?: return false
        edit {
            hit.fill.kind = if (hit.fill.kind == FillKind.NONE) FillKind.SOLID else hit.fill.kind
            hit.fill.color = color or 0xFF000000.toInt()
        }
        return true
    }

    /** picking a color while shapes are selected recolors their fill (or stroke if they have no fill) */
    fun applyColor(color: Int) {
        if (selected.isEmpty()) return
        edit {
            for (s in selected) {
                if (s.fill.kind != FillKind.NONE) s.fill.color = color or 0xFF000000.toInt()
                else s.stroke.color = color or 0xFF000000.toInt()
            }
        }
    }

    /** recolor while a color panel is open (no undo step until [endStyle]) */
    fun recolorLive(color: Int) {
        for (s in selected) {
            if (s.fill.kind != FillKind.NONE) s.fill.color = color or 0xFF000000.toInt()
            else s.stroke.color = color or 0xFF000000.toInt()
        }
        styleChanged()
    }

    /** style of the first selected shape (for the style panel) */
    fun sample(): VShape? = selected.firstOrNull()

    // ---- style panel support: one undo step for all changes made while the panel is open ----

    private var styleBefore: List<VShape>? = null

    fun beginStyle() {
        styleBefore = data?.copyShapes()
    }

    fun styleChanged() {
        val data = data ?: return
        data.render(layer.bitmap)
        host.redraw()
    }

    fun endStyle() {
        val data = data ?: return
        val b = styleBefore ?: return
        styleBefore = null
        host.push(VectorEdit(layer, b, data.copyShapes()))
    }

    /** the layer changed under us (undo, other layer…) */
    fun reset() {
        penShape = null
        selected.clear()
        activeNode = null
        drag = Drag.NONE
        creating = null
        before = null
    }

    // =========================================================
    // on-screen overlay
    // =========================================================

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.ACCENT }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun drawOverlay(c: Canvas) {
        val vm = host.viewMatrix()
        outline.strokeWidth = 1.5f * d
        // outlines of selected shapes
        for (s in selected) {
            val p = s.toPath()
            p.transform(vm)
            c.drawPath(p, outline)
        }
        // live pencil line
        if (drag == Drag.PENCIL && pencilPts.size > 1) {
            val p = Path()
            p.moveTo(pencilPts[0][0], pencilPts[0][1])
            for (i in 1 until pencilPts.size) p.lineTo(pencilPts[i][0], pencilPts[i][1])
            p.transform(vm)
            c.drawPath(p, outline)
        }
        // marquee
        if (drag == Drag.MARQUEE && moved) {
            val pts = floatArrayOf(marquee.left, marquee.top, marquee.right, marquee.bottom)
            vm.mapPoints(pts)
            thin.color = Ui.ACCENT
            thin.strokeWidth = 1f * d
            thin.pathEffect = DashPathEffect(floatArrayOf(6f * d, 4f * d), 0f)
            c.drawRect(min(pts[0], pts[2]), min(pts[1], pts[3]), max(pts[0], pts[2]), max(pts[1], pts[3]), thin)
            thin.pathEffect = null
        }
        when (tool) {
            VTool.SELECT -> selectionBounds()?.let { drawBox(c, it) }
            VTool.DIRECT -> drawNodes(c, showAllHandles = false)
            VTool.PEN -> {
                drawNodes(c, showAllHandles = true)
                drawRubberBand(c)
            }
            else -> {}
        }
    }

    private fun drawBox(c: Canvas, b: RectF) {
        val pts = boxScreen(b)
        thin.color = Ui.ACCENT
        thin.strokeWidth = 1.2f * d
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            c.drawLine(pts[i * 2], pts[i * 2 + 1], pts[j * 2], pts[j * 2 + 1], thin)
        }
        val rh = rotateHandleScreen(pts)
        c.drawLine((pts[0] + pts[2]) / 2, (pts[1] + pts[3]) / 2, rh.x, rh.y, thin)
        for (i in 0 until 4) {
            handle.color = Color.WHITE
            c.drawRect(pts[i * 2] - 6 * d, pts[i * 2 + 1] - 6 * d, pts[i * 2] + 6 * d, pts[i * 2 + 1] + 6 * d, handle)
            handle.color = Ui.ACCENT
            c.drawRect(pts[i * 2] - 4 * d, pts[i * 2 + 1] - 4 * d, pts[i * 2] + 4 * d, pts[i * 2 + 1] + 4 * d, handle)
        }
        handle.color = Color.WHITE
        c.drawCircle(rh.x, rh.y, 8 * d, handle)
        handle.color = 0xFF2ECC71.toInt()
        c.drawCircle(rh.x, rh.y, 5.5f * d, handle)
    }

    private fun drawNodes(c: Canvas, showAllHandles: Boolean) {
        thin.color = (Ui.ACCENT and 0xFFFFFF) or 0xCC000000.toInt()
        thin.strokeWidth = 1f * d
        for (s in selected) for (con in s.contours) for (n in con.nodes) {
            val p = host.toScreen(n.x, n.y)
            if (showAllHandles || n === activeNode || n.smooth) {
                if (n.hasIn) {
                    val h = host.toScreen(n.inX, n.inY)
                    c.drawLine(p.x, p.y, h.x, h.y, thin)
                    handle.color = Ui.ACCENT; c.drawCircle(h.x, h.y, 4.5f * d, handle)
                }
                if (n.hasOut) {
                    val h = host.toScreen(n.outX, n.outY)
                    c.drawLine(p.x, p.y, h.x, h.y, thin)
                    handle.color = Ui.ACCENT; c.drawCircle(h.x, h.y, 4.5f * d, handle)
                }
            }
            val r = if (n === activeNode) 6.5f * d else 5f * d
            handle.color = if (n === activeNode) Ui.ACCENT else Color.WHITE
            c.drawRect(p.x - r, p.y - r, p.x + r, p.y + r, handle)
            thin.color = Ui.ACCENT
            c.drawRect(p.x - r, p.y - r, p.x + r, p.y + r, thin)
            thin.color = (Ui.ACCENT and 0xFFFFFF) or 0xCC000000.toInt()
        }
    }

    private fun drawRubberBand(c: Canvas) {
        val s = penShape ?: return
        val h = hover ?: return
        val last = s.contours[0].nodes.lastOrNull() ?: return
        val p = Path()
        val a = host.toScreen(last.x, last.y)
        val o = host.toScreen(last.outX, last.outY)
        p.moveTo(a.x, a.y)
        p.cubicTo(o.x, o.y, h.x, h.y, h.x, h.y)
        thin.color = (Ui.ACCENT and 0xFFFFFF) or 0x99000000.toInt()
        thin.strokeWidth = 1.2f * d
        thin.pathEffect = DashPathEffect(floatArrayOf(5f * d, 4f * d), 0f)
        c.drawPath(p, thin)
        thin.pathEffect = null
        // highlight the first point: tap it to close the shape
        val f = s.contours[0].nodes[0]
        val fp = host.toScreen(f.x, f.y)
        if (s.contours[0].nodes.size >= 2 && hypot(h.x - fp.x, h.y - fp.y) < 22f * d) {
            thin.color = 0xFF2ECC71.toInt(); thin.strokeWidth = 2f * d
            c.drawCircle(fp.x, fp.y, 11f * d, thin)
        }
    }
}
