package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class LiquifyMode(val label: String) {
    PUSH("Push"), TWIRL_RIGHT("Twirl ↻"), TWIRL_LEFT("Twirl ↺"), PINCH("Pinch"), BLOAT("Bloat"), RECONSTRUCT("Reconstruct")
}

/**
 * Liquify on one layer: a mesh of points over the layer that the brush pushes, turns, pulls in
 * or spreads out; the layer is drawn through the mesh. Only the area under the brush is redrawn.
 */
class LiquifySession(val layer: Layer) {
    /** the layer as it was: what the mesh bends, and what Cancel / undo go back to */
    val original: Bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
    private val w = layer.bitmap.width
    private val h = layer.bitmap.height
    val cols: Int
    val rows: Int
    private val base: FloatArray
    private val verts: FloatArray
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clear = Paint().apply { blendMode = BlendMode.CLEAR }
    var mode = LiquifyMode.PUSH
    /** 0..1 */
    var strength = 0.5f
    var changed = false
        private set

    init {
        // about 30 px per mesh cell, at most 140 cells across
        val cell = max(8f, max(w, h) / 140f)
        cols = max(4, (w / cell).toInt())
        rows = max(4, (h / cell).toInt())
        base = FloatArray((cols + 1) * (rows + 1) * 2)
        for (j in 0..rows) for (i in 0..cols) {
            val k = (j * (cols + 1) + i) * 2
            base[k] = w * i / cols.toFloat()
            base[k + 1] = h * j / rows.toFloat()
        }
        verts = base.copyOf()
    }

    /**
     * One brush touch at (x, y) with radius [r] (canvas pixels); (dx, dy) = how far the brush
     * moved since the last touch (for Push).
     */
    fun dab(x: Float, y: Float, r: Float, dx: Float, dy: Float) {
        if (r < 1f) return
        val r2 = r * r
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        val s = strength.coerceIn(0.02f, 1f)
        val ang = (if (mode == LiquifyMode.TWIRL_LEFT) -1f else 1f) * 0.09f * s
        var touched = false
        for (k in 0 until verts.size / 2) {
            val vx = verts[k * 2]; val vy = verts[k * 2 + 1]
            val ex = vx - x; val ey = vy - y
            val d2 = ex * ex + ey * ey
            if (d2 >= r2) continue
            val t = 1f - d2 / r2
            val f = t * t
            var nx = vx; var ny = vy
            when (mode) {
                LiquifyMode.PUSH -> { nx += dx * f * (0.4f + 0.6f * s); ny += dy * f * (0.4f + 0.6f * s) }
                LiquifyMode.TWIRL_RIGHT, LiquifyMode.TWIRL_LEFT -> {
                    val a = ang * f
                    val c = cos(a); val sn = sin(a)
                    nx = x + ex * c - ey * sn; ny = y + ex * sn + ey * c
                }
                LiquifyMode.PINCH -> { nx -= ex * 0.08f * s * f; ny -= ey * 0.08f * s * f }
                LiquifyMode.BLOAT -> { nx += ex * 0.08f * s * f * (1f - sqrt(d2) / r); ny += ey * 0.08f * s * f * (1f - sqrt(d2) / r) }
                LiquifyMode.RECONSTRUCT -> { nx += (base[k * 2] - vx) * 0.2f * s * f; ny += (base[k * 2 + 1] - vy) * 0.2f * s * f }
            }
            // the frame of the mesh stays on the canvas edges
            val i = k % (cols + 1); val j = k / (cols + 1)
            if (i == 0 || i == cols) nx = base[k * 2]
            if (j == 0 || j == rows) ny = base[k * 2 + 1]
            verts[k * 2] = nx; verts[k * 2 + 1] = ny
            x0 = min(x0, min(vx, nx)); x1 = max(x1, max(vx, nx)); y0 = min(y0, min(vy, ny)); y1 = max(y1, max(vy, ny))
            touched = true
        }
        if (!touched) return
        changed = true
        // the cells around the moved points change: redraw that area
        val cw = w / cols.toFloat() * 2; val ch = h / rows.toFloat() * 2
        redraw(Rect((x0 - cw).toInt(), (y0 - ch).toInt(), (x1 + cw).toInt() + 1, (y1 + ch).toInt() + 1))
    }

    private fun redraw(r: Rect) {
        if (!r.intersect(0, 0, w, h)) return
        val c = Canvas(layer.bitmap)
        c.save()
        c.clipRect(r)
        c.drawRect(r, clear)
        c.drawBitmapMesh(original, cols, rows, verts, 0, null, 0, paint)
        c.restore()
    }

    /** every point back where it started */
    fun reset() {
        base.copyInto(verts)
        changed = false
        redraw(Rect(0, 0, w, h))
    }

    /** puts the layer back as it was (Cancel) */
    fun restore() {
        val c = Canvas(layer.bitmap)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), clear)
        c.drawBitmap(original, 0f, 0f, null)
    }
}
