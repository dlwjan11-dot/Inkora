package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * A copy of a big picture cut into small tiles, for the screen.
 *
 * The graphics chip gets a picture as a texture, and a picture that changed is sent to it again
 * in full. The stroke being drawn changes every frame, so the whole canvas-sized stroke used to
 * go to the chip 60 times a second. Cut into tiles, only the few tiles the pen touched since the
 * last frame change and travel; tiles the stroke never reached are not drawn at all.
 */
class TileMirror(private val width: Int, private val height: Int) {
    companion object {
        const val TILE = 256
        /** each tile also keeps one pixel of its neighbours all round, so a zoomed screen shows no seams */
        const val M = 1
    }

    private val cols = (width + TILE - 1) / TILE
    private val rows = (height + TILE - 1) / TILE
    private val tiles = arrayOfNulls<Bitmap>(cols * rows)
    private val canvases = arrayOfNulls<Canvas>(cols * rows)
    private val pool = ArrayList<Pair<Bitmap, Canvas>>()
    private val copy = Paint().apply { blendMode = BlendMode.SRC }
    private val src = Rect()
    private val dst = Rect()
    private val inner = Rect()
    private val place = RectF()

    val isEmpty get() = tiles.all { it == null }

    /** copies the part [area] of [from] (the same size as this mirror) into the tiles it covers */
    fun update(from: Bitmap, area: Rect) {
        val r = Rect(area)
        if (!r.intersect(0, 0, width, height)) return
        // every tile whose area (with its border) the change reaches
        val c0 = ((r.left - M).coerceAtLeast(0)) / TILE; val c1 = ((r.right - 1 + M) / TILE).coerceAtMost(cols - 1)
        val r0 = ((r.top - M).coerceAtLeast(0)) / TILE; val r1 = ((r.bottom - 1 + M) / TILE).coerceAtMost(rows - 1)
        for (ty in r0..r1) for (tx in c0..c1) {
            val i = ty * cols + tx
            val x = tx * TILE - M; val y = ty * TILE - M
            src.set(maxOf(r.left, x), maxOf(r.top, y), minOf(r.right, x + TILE + 2 * M), minOf(r.bottom, y + TILE + 2 * M))
            if (src.isEmpty) continue
            val c = canvases[i] ?: take().let { (b, cv) -> tiles[i] = b; canvases[i] = cv; cv }
            dst.set(src.left - x, src.top - y, src.right - x, src.bottom - y)
            c.drawBitmap(from, src, dst, copy)
        }
    }

    fun update(from: Bitmap, area: RectF) {
        update(from, Rect(area.left.toInt() - 2, area.top.toInt() - 2, area.right.toInt() + 3, area.bottom.toInt() + 3))
    }

    fun updateAll(from: Bitmap) = update(from, Rect(0, 0, width, height))

    /** the picture became empty: the tiles go back to the pool */
    fun clear() {
        for (i in tiles.indices) {
            val b = tiles[i] ?: continue
            b.eraseColor(0)
            pool.add(b to canvases[i]!!)
            tiles[i] = null; canvases[i] = null
        }
    }

    /** draws the tiles where the picture would be, at (0, 0) */
    fun draw(canvas: Canvas, paint: Paint?) {
        for (ty in 0 until rows) for (tx in 0 until cols) {
            val b = tiles[ty * cols + tx] ?: continue
            inner.set(M, M, M + TILE, M + TILE)
            place.set((tx * TILE).toFloat(), (ty * TILE).toFloat(), (tx * TILE + TILE).toFloat(), (ty * TILE + TILE).toFloat())
            canvas.drawBitmap(b, inner, place, paint)
        }
    }

    private fun take(): Pair<Bitmap, Canvas> = pool.removeLastOrNull()
        ?: Bitmap.createBitmap(TILE + 2 * M, TILE + 2 * M, Bitmap.Config.ARGB_8888).let { it to Canvas(it) }
}
