package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Smudge: carries the colors under the brush along the stroke.
 * A small "paint load" buffer picks up the pixels under each dab and is
 * laid down again (through the brush tip) at the next one.
 */
class Smudger(private val layer: Bitmap, private val brush: Brush) {

    private val size = max(4, ceil(brush.size * 1.1f).toInt() + 2)
    private val load = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val stamp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val loadCanvas = Canvas(load)
    private val stampCanvas = Canvas(stamp)
    private val layerCanvas = Canvas(layer)
    private var first = true

    private val src = Paint().apply { blendMode = BlendMode.SRC }
    private val tipMask = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_IN }
    private val lay = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fade = Paint().apply { blendMode = BlendMode.DST_OUT }
    private val add = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.PLUS }
    private val m = Matrix()
    private val r1 = Rect()
    private val r2 = Rect()

    /** strength = how much of the carried paint is laid down (brush opacity) */
    private val strength = brush.opacity.coerceIn(0.05f, 1f)
    /** how fast the load takes on the colors it passes over */
    private val pickup = (1f - strength) * 0.7f + 0.08f

    fun dab(x: Float, y: Float, d: Float, alpha: Float, angle: Float) {
        val dd = d.coerceIn(2f, size.toFloat())
        val n = dd.roundToInt().coerceIn(2, size)
        val left = (x - n / 2f).roundToInt()
        val top = (y - n / 2f).roundToInt()
        r1.set(left, top, left + n, top + n)   // area on the layer
        r2.set(0, 0, n, n)                     // area in the buffers

        if (first) {
            first = false
            load.eraseColor(0)
            loadCanvas.drawBitmap(layer, r1, r2, src)
            return
        }

        // 1. lay the carried paint down through the brush tip
        stamp.eraseColor(0)
        stampCanvas.drawBitmap(load, r2, r2, src)
        val tip = brush.tip()
        val s = n.toFloat() / max(tip.width, tip.height)
        m.reset()
        m.postTranslate(-tip.width / 2f, -tip.height / 2f)
        m.postScale(s, s)
        if (angle != 0f) m.postRotate(angle)
        m.postTranslate(n / 2f, n / 2f)
        stampCanvas.save()
        stampCanvas.clipRect(r2)
        // keep only what is under the tip
        val tipLayer = stampCanvas.saveLayer(0f, 0f, n.toFloat(), n.toFloat(), tipMask)
        stampCanvas.drawBitmap(tip, m, null)
        stampCanvas.restoreToCount(tipLayer)
        stampCanvas.restore()
        lay.alpha = (strength * alpha.coerceIn(0f, 1f) * 255).roundToInt().coerceIn(0, 255)
        layerCanvas.drawBitmap(stamp, r2, r1, lay)

        // 2. pick up the colors here: load = load * (1 - p) + layer * p
        val p = (pickup * 255).roundToInt().coerceIn(1, 255)
        fade.alpha = p
        loadCanvas.save()
        loadCanvas.clipRect(r2)
        loadCanvas.drawPaint(fade)
        add.alpha = p
        loadCanvas.drawBitmap(layer, r1, r2, add)
        loadCanvas.restore()
    }

    fun recycle() {
        load.recycle()
        stamp.recycle()
    }
}
