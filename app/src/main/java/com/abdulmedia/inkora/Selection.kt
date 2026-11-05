package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

enum class SelectShape(val label: String) {
    FREEHAND("Freehand"), RECTANGLE("Rectangle"), ELLIPSE("Ellipse"),
    /** tap an area: the touching pixels of a similar color */
    WAND("Magic wand"),
    /** tap a color: every pixel of a similar color on the canvas */
    COLOR("Color range"),
    /** paint the selection with a round brush */
    BRUSH("Selection brush");

    /** shapes that act on one tap instead of a drawn outline */
    val isTap get() = this == WAND || this == COLOR
}

/** How a new selection meets the one you already have. */
enum class SelectCombine(val label: String) { NEW("New"), ADD("Add"), SUBTRACT("Subtract") }

/**
 * A selection: an alpha mask the size of the canvas (255 = selected) plus its outline
 * for the "marching ants".
 */
class Selection(val mask: Bitmap, val outline: Path, val bounds: Rect)

object Selections {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    val dstIn = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_IN }
    val dstOut = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_OUT }
    private val srcPaint = Paint().apply { blendMode = BlendMode.SRC }
    private val plusPaint = Paint().apply { blendMode = BlendMode.PLUS }

    fun fromPath(w: Int, h: Int, path: Path): Selection? {
        val rf = RectF()
        path.computeBounds(rf, true)
        if (rf.width() < 2f && rf.height() < 2f) return null
        val r = Rect()
        rf.roundOut(r)
        r.inset(-2, -2)
        if (!r.intersect(0, 0, w, h)) return null
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(mask).drawPath(path, maskPaint)
        return Selection(mask, Path(path), r)
    }

    fun shapePath(shape: SelectShape, pts: List<FloatArray>, x0: Float, y0: Float, x1: Float, y1: Float): Path {
        val p = Path()
        when (shape) {
            SelectShape.FREEHAND -> {
                if (pts.isNotEmpty()) {
                    p.moveTo(pts[0][0], pts[0][1])
                    for (i in 1 until pts.size) p.lineTo(pts[i][0], pts[i][1])
                    p.close()
                }
            }
            SelectShape.RECTANGLE -> p.addRect(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1), Path.Direction.CW)
            SelectShape.ELLIPSE -> p.addOval(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1), Path.Direction.CW)
            SelectShape.WAND, SelectShape.COLOR, SelectShape.BRUSH -> {}
        }
        return p
    }

    /** a selection from a mask (ALPHA_8 or ARGB, canvas-sized); null when nothing is selected */
    fun fromMask(mask: Bitmap): Selection? {
        val m = if (mask.config == Bitmap.Config.ALPHA_8) mask else
            Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8).also { Canvas(it).drawBitmap(mask, 0f, 0f, null) }
        val traced = trace(m) ?: return null
        return Selection(m, traced.first, traced.second)
    }

    /**
     * The outline of a mask for the marching ants: the edges between selected and unselected cells
     * on a grid of about 1000 cells across, joined into runs. Also gives the bounds.
     */
    fun trace(mask: Bitmap): Pair<Path, Rect>? {
        val w = mask.width; val h = mask.height
        val a = ByteArray(w * h)
        val buf = java.nio.ByteBuffer.wrap(a)
        if (mask.config == Bitmap.Config.ALPHA_8 && mask.rowBytes == w) mask.copyPixelsToBuffer(buf)
        else {
            val px = IntArray(w * h); mask.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) a[i] = (px[i] ushr 24).toByte()
        }
        val step = maxOf(1, maxOf(w, h) / 1000)
        val gw = (w + step - 1) / step; val gh = (h + step - 1) / step
        val inside = BooleanArray(gw * gh)
        var any = false
        var x0 = gw; var y0 = gh; var x1 = -1; var y1 = -1
        for (gy in 0 until gh) {
            val sy = minOf(h - 1, gy * step + step / 2)
            for (gx in 0 until gw) {
                val sx = minOf(w - 1, gx * step + step / 2)
                if ((a[sy * w + sx].toInt() and 255) >= 128) {
                    inside[gy * gw + gx] = true; any = true
                    if (gx < x0) x0 = gx; if (gx > x1) x1 = gx; if (gy < y0) y0 = gy; if (gy > y1) y1 = gy
                }
            }
        }
        if (!any) return null
        fun at(gx: Int, gy: Int) = gx in 0 until gw && gy in 0 until gh && inside[gy * gw + gx]
        val p = Path()
        val s = step.toFloat()
        // horizontal edges
        for (gy in y0..y1 + 1) {
            var gx = x0
            while (gx <= x1) {
                if (at(gx, gy) != at(gx, gy - 1)) {
                    val st = gx
                    while (gx <= x1 && at(gx, gy) != at(gx, gy - 1)) gx++
                    p.moveTo(st * s, gy * s); p.lineTo(gx * s, gy * s)
                } else gx++
            }
        }
        // vertical edges
        for (gx in x0..x1 + 1) {
            var gy = y0
            while (gy <= y1) {
                if (at(gx, gy) != at(gx - 1, gy)) {
                    val st = gy
                    while (gy <= y1 && at(gx, gy) != at(gx - 1, gy)) gy++
                    p.moveTo(gx * s, st * s); p.lineTo(gx * s, gy * s)
                } else gy++
            }
        }
        val r = Rect(x0 * step - 2, y0 * step - 2, (x1 + 1) * step + 2, (y1 + 1) * step + 2)
        r.intersect(0, 0, w, h)
        return p to r
    }

    /** [old] and [fresh] together, as [mode] says */
    fun combine(old: Selection?, fresh: Selection?, mode: SelectCombine, w: Int, h: Int): Selection? {
        if (mode == SelectCombine.NEW) return fresh
        if (old == null) return if (mode == SelectCombine.ADD) fresh else null
        if (fresh == null) return old
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(mask)
        c.drawBitmap(old.mask, 0f, 0f, null)
        c.drawBitmap(fresh.mask, 0f, 0f, if (mode == SelectCombine.ADD) null else dstOut)
        return fromMask(mask)
    }

    /**
     * Magic wand ([contiguous] = true) or color range: the pixels of [ref] whose color is within
     * [tolerance] (0..1) of the color at (x, y).
     */
    fun byColor(ref: Bitmap, x: Int, y: Int, tolerance: Float, contiguous: Boolean): Selection? {
        val w = ref.width; val h = ref.height
        if (x !in 0 until w || y !in 0 until h) return null
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        if (contiguous) {
            val r = FloodFill.compute(ref, x, y, 0xFF000000.toInt(), tolerance) ?: return null
            Canvas(mask).drawBitmap(r.bitmap, r.rect.left.toFloat(), r.rect.top.toFloat(), null)
            r.bitmap.recycle()
        } else {
            val px = IntArray(w * h)
            ref.getPixels(px, 0, w, 0, 0, w, h)
            val t = px[y * w + x]
            val tol = (tolerance.coerceIn(0f, 1f) * 255).toInt()
            val tA = t ushr 24; val tR = (t shr 16) and 255; val tG = (t shr 8) and 255; val tB = t and 255
            val a = ByteArray(w * h)
            for (i in px.indices) {
                val c = px[i]
                if (kotlin.math.abs((c ushr 24) - tA) <= tol && kotlin.math.abs(((c shr 16) and 255) - tR) <= tol &&
                    kotlin.math.abs(((c shr 8) and 255) - tG) <= tol && kotlin.math.abs((c and 255) - tB) <= tol) a[i] = -1
            }
            if (mask.rowBytes == w) mask.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(a))
            else {
                val argb = IntArray(w * h) { if (a[it].toInt() != 0) -0x1000000 else 0 }
                val tmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
                Canvas(mask).drawBitmap(tmp, 0f, 0f, null); tmp.recycle()
            }
        }
        return fromMask(mask)
    }

    /** the area a selection-brush stroke covers */
    fun brushPath(pts: List<FloatArray>, width: Float): Path {
        val line = Path()
        if (pts.isEmpty()) return line
        line.moveTo(pts[0][0], pts[0][1])
        for (i in 1 until pts.size) line.lineTo(pts[i][0], pts[i][1])
        if (pts.size == 1) line.lineTo(pts[0][0] + 0.1f, pts[0][1])
        val p = Paint().apply { style = Paint.Style.STROKE; strokeWidth = width; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val out = Path()
        p.getFillPath(line, out)
        return out
    }

    fun invert(s: Selection, w: Int, h: Int): Selection {
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(mask)
        c.drawColor(0xFF000000.toInt())
        c.drawBitmap(s.mask, 0f, 0f, dstOut)
        val outline = Path(s.outline).apply {
            addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
            fillType = Path.FillType.EVEN_ODD
        }
        return Selection(mask, outline, Rect(0, 0, w, h))
    }

    fun all(w: Int, h: Int): Selection {
        val p = Path().apply { addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW) }
        return fromPath(w, h, p)!!
    }

    /** Selection moved by a transform matrix (doc -> doc). */
    fun transformed(s: Selection, m: Matrix, w: Int, h: Int): Selection? {
        val p = Path(s.outline)
        p.transform(m)
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(mask).drawBitmap(s.mask, m, Paint(Paint.FILTER_BITMAP_FLAG))
        val rf = RectF()
        p.computeBounds(rf, true)
        val r = Rect()
        rf.roundOut(r)
        r.inset(-2, -2)
        if (!r.intersect(0, 0, w, h)) return null
        return Selection(mask, p, r)
    }

    /**
     * Keeps the new pixels only inside the selection: outside it the layer goes back to [before].
     * [beforeRegion] holds the pixels of rectangle [r] before the change.
     */
    fun restrict(layer: Bitmap, beforeRegion: Bitmap, r: Rect, sel: Selection) {
        val w = r.width()
        val h = r.height()
        if (w <= 0 || h <= 0) return
        val local = Rect(0, 0, w, h)
        val inside = Bitmap.createBitmap(layer, r.left, r.top, w, h).copy(Bitmap.Config.ARGB_8888, true)
        Canvas(inside).drawBitmap(sel.mask, r, local, dstIn)
        val outside = beforeRegion.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(outside).drawBitmap(sel.mask, r, local, dstOut)
        val c = Canvas(layer)
        c.drawBitmap(outside, r.left.toFloat(), r.top.toFloat(), srcPaint)
        c.drawBitmap(inside, r.left.toFloat(), r.top.toFloat(), plusPaint)
        inside.recycle()
        outside.recycle()
    }

    /** Smallest rectangle containing visible pixels of [b], or null if empty. */
    fun contentBounds(b: Bitmap): Rect? {
        val w = b.width
        val h = b.height
        val row = IntArray(w)
        var top = -1; var bottom = -1; var left = w; var right = -1
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            var any = false
            for (x in 0 until w) {
                if ((row[x] ushr 24) != 0) {
                    any = true
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
            if (any) {
                if (top < 0) top = y
                bottom = y
            }
        }
        if (top < 0) return null
        return Rect(left, top, right + 1, bottom + 1)
    }
}

/**
 * Moving / scaling / rotating pixels. [floating] is the lifted piece of the layer,
 * [matrix] maps it into the document.
 */
class TransformSession(
    val layer: Layer,
    val floating: Bitmap,
    val source: Rect,
    /** full copy of the layer before lifting, for cancel + undo */
    val before: Bitmap,
    /** selection that was lifted, so it can follow the pixels */
    val selection: Selection?,
    /** the other layers moving with it (several layers or a group), lifted from the same rectangle */
    val extras: List<Piece> = emptyList()
) {
    class Piece(val layer: Layer, val floating: Bitmap, val before: Bitmap)

    val matrix = Matrix().apply { setTranslate(source.left.toFloat(), source.top.toFloat()) }
    private val start = Matrix(matrix)

    val fw get() = floating.width.toFloat()
    val fh get() = floating.height.toFloat()

    /** corners of the floating piece in document coordinates: x0,y0 … x3,y3 (TL, TR, BR, BL) */
    fun corners(): FloatArray {
        val pts = floatArrayOf(0f, 0f, fw, 0f, fw, fh, 0f, fh)
        matrix.mapPoints(pts)
        return pts
    }

    fun center(): FloatArray {
        val c = floatArrayOf(fw / 2, fh / 2)
        matrix.mapPoints(c)
        return c
    }

    fun flipH() = matrix.preScale(-1f, 1f, fw / 2, fh / 2)
    fun flipV() = matrix.preScale(1f, -1f, fw / 2, fh / 2)
    fun rotate90() = matrix.preRotate(90f, fw / 2, fh / 2)
    fun reset() = matrix.set(start)

    fun fitTo(w: Int, h: Int) {
        val s = minOf(w / fw, h / fh)
        matrix.setScale(s, s)
        matrix.postTranslate((w - fw * s) / 2, (h - fh * s) / 2)
    }

    /** document-space rectangle touched by the result */
    fun resultBounds(w: Int, h: Int): Rect {
        val rf = RectF(0f, 0f, fw, fh)
        matrix.mapRect(rf)
        val r = Rect()
        rf.roundOut(r)
        r.inset(-2, -2)
        r.union(source)
        r.intersect(0, 0, w, h)
        return r
    }

    /** where the selection ends up: doc -> doc movement = matrix * inverse(start) */
    fun movement(): Matrix {
        val inv = Matrix()
        start.invert(inv)
        return Matrix(matrix).apply { preConcat(inv) }
    }
}
