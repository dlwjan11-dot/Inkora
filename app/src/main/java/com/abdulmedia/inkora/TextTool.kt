package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class TextFont(val label: String, val family: String, val style: Int) {
    SANS("Sans", "sans-serif", Typeface.NORMAL),
    SANS_BOLD("Sans Bold", "sans-serif", Typeface.BOLD),
    SANS_BLACK("Sans Black", "sans-serif-black", Typeface.NORMAL),
    CONDENSED("Condensed", "sans-serif-condensed", Typeface.NORMAL),
    SERIF("Serif", "serif", Typeface.NORMAL),
    SERIF_ITALIC("Serif Italic", "serif", Typeface.ITALIC),
    MONO("Monospace", "monospace", Typeface.NORMAL),
    HANDWRITING("Handwriting", "cursive", Typeface.NORMAL),
    CASUAL("Casual", "casual", Typeface.NORMAL);

    fun typeface(): Typeface = Typeface.create(family, style)
}

object TextTool {
    /**
     * Renders text (any language, Arabic included — Android shapes it and lays it right-to-left)
     * into a bitmap that fits inside maxW x maxH.
     */
    fun render(text: String, font: TextFont, sizePx: Float, color: Int, align: Layout.Alignment, maxW: Int, maxH: Int): Bitmap? {
        if (text.isBlank()) return null
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color
            textSize = sizePx
            typeface = font.typeface()
        }
        val natural = text.split('\n').maxOf { ceil(paint.measureText(it)).toInt() }
        val width = min(max(1, natural + 4), max(16, maxW))
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setIncludePad(true)
            .build()
        val h = min(layout.height + 4, max(16, maxH))
        val bmp = Bitmap.createBitmap(width, max(1, h), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(0f, 2f)
        layout.draw(c)
        return bmp
    }

    // ---- text on a path ----

    /** how text is laid out: in a block, along an arc, round a circle, or along a line you draw */
    const val STRAIGHT = 0
    const val ARC = 1
    const val CIRCLE = 2
    const val LINE = 3
    val shapeNames = listOf("Straight", "Arc", "Circle", "Along a line I draw")

    /** a smoothed path through [pts]; drawn right to left it is turned round so the text stays upright */
    fun pathThrough(pts: List<PointF>): Path {
        var p = pts
        if (p.last().x < p.first().x) p = p.reversed()
        // a moving average takes out the shake of the hand
        val k = 4
        val sm = p.indices.map { i ->
            var x = 0f; var y = 0f; var n = 0
            for (j in max(0, i - k)..min(p.size - 1, i + k)) { x += p[j].x; y += p[j].y; n++ }
            PointF(x / n, y / n)
        }
        val path = Path()
        path.moveTo(p.first().x, p.first().y)
        for (i in 1 until sm.size - 1) path.lineTo(sm[i].x, sm[i].y)
        path.lineTo(p.last().x, p.last().y)
        return path
    }

    /** an arc [w] wide, centred on (cx, cy); [bend] 0..1 from almost flat to a half circle */
    fun arcPath(cx: Float, cy: Float, w: Float, bend: Float): Path {
        val sweep = 20f + bend * 160f
        val r = (w / 2f) / kotlin.math.sin(Math.toRadians(sweep / 2.0)).toFloat()
        // the arc's middle height sits on cy
        val top = cy - r * (1 - kotlin.math.cos(Math.toRadians(sweep / 2.0)).toFloat()) / 2f
        return Path().apply { addArc(RectF(cx - r, top, cx + r, top + 2 * r), 270f - sweep / 2f, sweep) }
    }

    /** a circle of radius [r]; the text starts at the top */
    fun circlePath(cx: Float, cy: Float, r: Float): Path =
        Path().apply { addArc(RectF(cx - r, cy - r, cx + r, cy + r), 180f, 359.9f) }

    /**
     * Draws [text] along [path] onto [c], centred along it; text longer than the path gets smaller
     * until it fits. Returns the area drawn (roughly).
     */
    fun drawOnPath(c: Canvas, text: String, font: TextFont, sizePx: Float, color: Int, path: Path, centred: Boolean = true): RectF {
        val line = text.replace('\n', ' ').trim()
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color
            textSize = sizePx
            typeface = font.typeface()
        }
        val len = PathMeasure(path, false).length
        var w = paint.measureText(line)
        if (w > len * 0.98f && w > 0f) { paint.textSize = sizePx * len * 0.98f / w; w = paint.measureText(line) }
        val off = if (centred) ((len - w) / 2f).coerceAtLeast(0f) else 0f
        c.drawTextOnPath(line, path, off, 0f, paint)
        val b = RectF(); path.computeBounds(b, true)
        b.inset(-paint.textSize * 1.3f, -paint.textSize * 1.3f)
        return b
    }
}
