package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture

/**
 * The stroke being drawn, painted by the graphics chip.
 *
 * The brush engine draws its dabs with the ordinary drawing calls; here those calls are
 * recorded (as Pictures) instead of painted into a picture in memory. The screen plays the
 * recordings back on the graphics chip every frame, so the main processor does not paint dabs
 * while the pen moves.
 *
 * When the stroke ends its pixels are needed in memory (to put the paint into the layer, with
 * undo): [readInto] has the chip paint the recordings into a hardware bitmap and copies that
 * back. If anything here fails, the caller paints the stroke again the old way.
 */
class GpuStroke(private val width: Int, private val height: Int) {

    private val batches = ArrayList<Picture>()
    private var open: Picture? = null
    private var rec: Canvas? = null

    val isEmpty get() = batches.isEmpty() && open == null

    /** where the brush engine draws its next dabs (a recording, closed when the screen draws) */
    fun canvas(): Canvas {
        rec?.let { return it }
        val p = Picture()
        open = p
        return p.beginRecording(width, height).also { rec = it }
    }

    private fun closeOpen() {
        val p = open ?: return
        p.endRecording()
        batches.add(p)
        open = null; rec = null
        // many small recordings become one now and then, so a long stroke stays quick to play
        if (batches.size >= 48) {
            val merged = Picture()
            val c = merged.beginRecording(width, height)
            for (b in batches) c.drawPicture(b)
            merged.endRecording()
            batches.clear()
            batches.add(merged)
        }
    }

    /**
     * Draws the stroke on [c] (a screen canvas). [paint]: how it goes over what is below (the
     * brush's opacity and blend); the dabs first meet each other on a layer of their own.
     */
    fun draw(c: Canvas, paint: Paint?): Boolean {
        closeOpen()
        if (batches.isEmpty()) return true
        val s = if (paint != null) c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), paint) else -1
        for (b in batches) c.drawPicture(b)
        if (s >= 0) c.restoreToCount(s)
        return true
    }

    fun clear() {
        if (open != null) runCatching { open?.endRecording() }
        open = null; rec = null
        batches.clear()
    }

    private val copyPaint = Paint().apply { blendMode = BlendMode.SRC }

    /** paints the stroke into [into] (the same size) on the graphics chip; false: it could not */
    fun readInto(into: Bitmap): Boolean = runCatching {
        closeOpen()
        if (batches.isEmpty()) { into.eraseColor(0); return true }
        val all = if (batches.size == 1) batches[0] else Picture().also { p ->
            val c = p.beginRecording(width, height)
            for (b in batches) c.drawPicture(b)
            p.endRecording()
        }
        val hw = Bitmap.createBitmap(all, width, height, Bitmap.Config.HARDWARE)
        val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
        hw.recycle()
        soft ?: return false
        Canvas(into).drawBitmap(soft, 0f, 0f, copyPaint)
        soft.recycle()
        true
    }.getOrDefault(false)

    fun release() = clear()
}
