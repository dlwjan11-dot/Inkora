package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

enum class SelectShape(val label: String) { FREEHAND("Freehand"), RECTANGLE("Rectangle"), ELLIPSE("Ellipse") }

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
        }
        return p
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
