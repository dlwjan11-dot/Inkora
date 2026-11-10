package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Comic page helpers: panel frames and speech bubbles. */
object Comics {

    /** a page layout: rows from top to bottom, each with its height share and the width shares of its panels */
    class Layout(val label: String, val rows: List<Pair<Float, List<Float>>>)

    private fun even(n: Int) = List(n) { 1f }

    val layouts = listOf(
        Layout("Two rows", listOf(1f to even(1), 1f to even(1))),
        Layout("Three rows", listOf(1f to even(1), 1f to even(1), 1f to even(1))),
        Layout("Four panels", listOf(1f to even(2), 1f to even(2))),
        Layout("Six panels", listOf(1f to even(2), 1f to even(2), 1f to even(2))),
        Layout("Wide panel, then two", listOf(1.2f to even(1), 1f to even(2))),
        Layout("Two, wide panel, two", listOf(1f to even(2), 1.1f to even(1), 1f to even(2))),
        Layout("Uneven page", listOf(1.3f to listOf(1.6f, 1f), 0.9f to even(3), 1.2f to listOf(1f, 1.5f))),
        Layout("Strip of four", listOf(1f to even(4)))
    )

    /** the panels of [layout] on a page of [w] x [h], with a [margin] around and a [gutter] between */
    fun rects(layout: Layout, w: Int, h: Int, margin: Float, gutter: Float): List<RectF> {
        val out = ArrayList<RectF>()
        val rows = layout.rows
        val availH = h - 2 * margin - gutter * (rows.size - 1)
        val sumH = rows.sumOf { it.first.toDouble() }.toFloat()
        var y = margin
        for ((share, cols) in rows) {
            val rh = availH * share / sumH
            val availW = w - 2 * margin - gutter * (cols.size - 1)
            val sumW = cols.sum()
            var x = margin
            for (c in cols) {
                val cw = availW * c / sumW
                out.add(RectF(x, y, x + cw, y + rh))
                x += cw + gutter
            }
            y += rh + gutter
        }
        return out
    }

    fun drawPanels(canvas: Canvas, rects: List<RectF>, border: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = border; this.color = color; strokeJoin = Paint.Join.MITER
        }
        for (r in rects) canvas.drawRect(r, p)
    }

    enum class Bubble(val label: String) { SPEECH("Speech"), THOUGHT("Thought"), SHOUT("Shout"), CAPTION("Caption box") }

    /** A bubble around [text] (a rendered text bitmap): white inside, with an outline of [border]. */
    fun bubble(text: Bitmap, kind: Bubble, border: Float, ink: Int = Color.BLACK, paper: Int = Color.WHITE): Bitmap? {
        val tw = text.width.toFloat(); val th = text.height.toFloat()
        val pad = max(th * 0.18f, border * 3f)
        // an ellipse has to be about 1.42x the box inside it
        val grow = if (kind == Bubble.CAPTION) 1f else 1.42f
        val bw = tw * grow + pad * 2; val bh = th * grow + pad * 2
        val spike = if (kind == Bubble.SHOUT) 0.22f else if (kind == Bubble.THOUGHT) 0.10f else 0f
        val ex = bw * spike + border * 2; val ey = bh * spike + border * 2
        val tail = if (kind == Bubble.CAPTION) 0f else bh * 0.42f
        val w = (bw + ex * 2).toInt() + 2; val h = (bh + ey * 2 + tail).toInt() + 2
        val out = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { return null }
        val c = Canvas(out)
        val cx = w / 2f; val cy = ey + bh / 2f + 1
        val body = RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        val path = Path()
        when (kind) {
            Bubble.SPEECH -> {
                path.addOval(body, Path.Direction.CW)
                val t = Path().apply {
                    moveTo(cx - bw * 0.22f, cy + bh * 0.36f)
                    lineTo(cx - bw * 0.30f, cy + bh / 2 + tail)
                    lineTo(cx - bw * 0.04f, cy + bh * 0.44f)
                    close()
                }
                path.op(t, Path.Op.UNION)
            }
            Bubble.THOUGHT -> {
                path.addOval(body, Path.Direction.CW)
                val n = 14
                for (i in 0 until n) {
                    val a = 2 * PI * i / n
                    val px = cx + cos(a).toFloat() * bw / 2 * 0.92f
                    val py = cy + sin(a).toFloat() * bh / 2 * 0.92f
                    val r = (bw + bh) * 0.5f * (0.085f + 0.02f * ((i * 7) % 3))
                    path.op(Path().apply { addCircle(px, py, r, Path.Direction.CW) }, Path.Op.UNION)
                }
                var r = bh * 0.11f
                var py = cy + bh / 2 + ey * 0.6f + r
                var px = cx - bw * 0.22f
                repeat(3) {
                    path.addCircle(px, py, r, Path.Direction.CW)
                    py += r * 1.9f; px -= r * 1.1f; r *= 0.62f
                }
            }
            Bubble.SHOUT -> {
                val n = 22
                for (i in 0 until n * 2) {
                    val a = 2 * PI * i / (n * 2) - PI / 2
                    // the points are not all the same length: livelier
                    val k = if (i % 2 == 0) 1f + spike * 2 * (0.7f + 0.3f * (((i / 2) * 5) % 4) / 3f) else 1f
                    val px = cx + cos(a).toFloat() * bw / 2 * k
                    val py = cy + sin(a).toFloat() * bh / 2 * k
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                val t = Path().apply {
                    moveTo(cx - bw * 0.20f, cy + bh * 0.34f)
                    lineTo(cx - bw * 0.34f, cy + bh / 2 + tail)
                    lineTo(cx - bw * 0.02f, cy + bh * 0.42f)
                    close()
                }
                path.op(t, Path.Op.UNION)
            }
            Bubble.CAPTION -> path.addRect(body, Path.Direction.CW)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL; p.color = paper
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = border; p.color = ink; p.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, p)
        c.drawBitmap(text, cx - tw / 2, cy - th / 2, null)
        return out
    }
}
