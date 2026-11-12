package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock

/**
 * The stroke being drawn, painted by the graphics chip.
 *
 * The brush engine draws its dabs with the ordinary drawing calls; here those calls go into
 * recordings (RenderNodes) instead of a picture in memory, so the graphics chip paints them,
 * every frame, as part of the screen. One recording is closed per frame and kept; the screen
 * draws them all, in order. Nothing is painted by the main processor while the pen moves.
 *
 * When the stroke ends its pixels are needed in memory (to put the paint into the layer, with
 * undo): [readInto] has the chip paint the recordings once more into a buffer and copies that
 * back. If anything here fails, the caller paints the stroke again the old way.
 */
class GpuStroke(private val width: Int, private val height: Int) {

    private val batches = ArrayList<RenderNode>()
    private var open: RenderNode? = null
    private var rec: RecordingCanvas? = null
    private val all = RenderNode("inkora-stroke").apply { setPosition(0, 0, width, height) }
    private var changed = false

    val isEmpty get() = batches.isEmpty() && open == null

    /** where the brush engine draws its next dabs (a recording, kept open until the next frame) */
    fun canvas(): Canvas {
        rec?.let { return it }
        val n = RenderNode("inkora-dabs").apply { setPosition(0, 0, width, height) }
        open = n
        return n.beginRecording(width, height).also { rec = it }
    }

    private fun closeOpen() {
        val n = open ?: return
        n.endRecording()
        batches.add(n)
        open = null; rec = null
        changed = true
    }

    /** the whole stroke as one recording, ready to draw (closes this frame's dabs) */
    private fun content(): RenderNode {
        closeOpen()
        if (changed) {
            val c = all.beginRecording(width, height)
            for (b in batches) c.drawRenderNode(b)
            all.endRecording()
            changed = false
        }
        return all
    }

    /**
     * Draws the stroke on [c] (a screen canvas). [paint]: how it goes over what is below (the
     * brush's opacity and blend); the dabs first meet each other on a layer of their own.
     */
    fun draw(c: Canvas, paint: Paint?): Boolean {
        if (!c.isHardwareAccelerated) return false
        val node = content()
        if (paint == null) { c.drawRenderNode(node); return true }
        val s = c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), paint)
        c.drawRenderNode(node)
        c.restoreToCount(s)
        return true
    }

    fun clear() {
        rec?.let { open?.endRecording() }
        open = null; rec = null
        for (b in batches) b.discardDisplayList()
        batches.clear()
        all.discardDisplayList()
        changed = true
    }

    // ---- reading the stroke back into memory ----

    private var reader: ImageReader? = null
    private var renderer: HardwareRenderer? = null
    private val root = RenderNode("inkora-readback").apply { setPosition(0, 0, width, height) }
    private val copyPaint = Paint().apply { blendMode = BlendMode.SRC }

    /** paints the stroke into [into] (the same size, emptied first); false: it could not */
    fun readInto(into: Bitmap): Boolean = runCatching {
        if (isEmpty) { into.eraseColor(0); return true }
        val r = reader ?: ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT).also { reader = it }
        val hr = renderer ?: HardwareRenderer().also {
            it.setSurface(r.surface)
            it.setContentRoot(root)
            it.isOpaque = false
            renderer = it
        }
        val c = root.beginRecording(width, height)
        c.drawColor(0, BlendMode.CLEAR)
        c.drawRenderNode(content())
        root.endRecording()
        hr.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        // the buffer arrives a moment later
        var image = r.acquireLatestImage()
        val until = SystemClock.uptimeMillis() + 500
        while (image == null && SystemClock.uptimeMillis() < until) { Thread.sleep(2); image = r.acquireLatestImage() }
        if (image == null) return false
        try {
            val hb = image.hardwareBuffer ?: return false
            val hw = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB)) ?: run { hb.close(); return false }
            val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle(); hb.close()
            soft ?: return false
            Canvas(into).drawBitmap(soft, 0f, 0f, copyPaint)
            soft.recycle()
            true
        } finally {
            image.close()
        }
    }.getOrDefault(false)

    fun release() {
        clear()
        runCatching { renderer?.destroy() }
        runCatching { reader?.close() }
        renderer = null; reader = null
    }
}
