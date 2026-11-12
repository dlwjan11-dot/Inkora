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
    companion object { const val TILE = 256 }

    private val cols = (width + TILE - 1) / TILE
    private val rows = (height + TILE - 1) / TILE
    private val tiles = arrayOfNulls<Bitmap>(cols * rows)
    private val canvases = arrayOfNulls<Canvas>(cols * rows)
    private val pool = ArrayList<Pair<Bitmap, Canvas>>()
    private val copy = Paint().apply { blendMode = BlendMode.SRC }
    private val src = Rect()
    private val dst = Rect()

    val isEmpty get() = tiles.all { it == null }

    /** copies the part [area] of [from] (the same size as this mirror) into the tiles it covers */
    fun update(from: Bitmap, area: Rect) {
        val r = Rect(area)
        if (!r.intersect(0, 0, width, height)) return
        val c0 = r.left / TILE; val c1 = (r.right - 1) / TILE
        val r0 = r.top / TILE; val r1 = (r.bottom - 1) / TILE
        for (ty in r0..r1) for (tx in c0..c1) {
            val i = ty * cols + tx
            val x = tx * TILE; val y = ty * TILE
            val c = canvases[i] ?: take().let { (b, cv) -> tiles[i] = b; canvases[i] = cv; cv }
            src.set(maxOf(r.left, x), maxOf(r.top, y), minOf(r.right, x + TILE), minOf(r.bottom, y + TILE))
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
            canvas.drawBitmap(b, (tx * TILE).toFloat(), (ty * TILE).toFloat(), paint)
        }
    }

    private fun take(): Pair<Bitmap, Canvas> = pool.removeLastOrNull()
        ?: Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888).let { it to Canvas(it) }
}
