package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.roundToInt

/**
 * Whole-canvas changes: canvas size (crop or add space, with an anchor), resizing the image,
 * turning it 90° and flipping it. Each one makes a new document with every layer, group,
 * vector shape, 3D scene and setting carried over.
 */
object CanvasOps {
    /** the largest canvas Android can hold as one picture per layer */
    const val MAX_PIXELS = 25_000_000L

    /**
     * New canvas size; the artwork keeps its size. [ax], [ay] (0..1) say where it sits:
     * 0 = left / top, 0.5 = center, 1 = right / bottom. Smaller = crop, bigger = more space.
     */
    fun canvasSize(doc: Document, w: Int, h: Int, ax: Float, ay: Float): Document {
        val m = Matrix().apply { setTranslate(((w - doc.width) * ax).roundToInt().toFloat(), ((h - doc.height) * ay).roundToInt().toFloat()) }
        return remake(doc, w, h, m, resample = false)
    }

    /** crop to a rectangle of the canvas */
    fun crop(doc: Document, r: RectF): Document {
        val w = r.width().roundToInt().coerceAtLeast(1); val h = r.height().roundToInt().coerceAtLeast(1)
        val m = Matrix().apply { setTranslate(-r.left.roundToInt().toFloat(), -r.top.roundToInt().toFloat()) }
        return remake(doc, w, h, m, resample = false)
    }

    /** the whole image made bigger or smaller */
    fun resize(doc: Document, w: Int, h: Int): Document {
        val m = Matrix().apply { setScale(w / doc.width.toFloat(), h / doc.height.toFloat()) }
        return remake(doc, w, h, m, resample = true)
    }

    /** turned a quarter: [clockwise] or counter-clockwise */
    fun rotate90(doc: Document, clockwise: Boolean): Document {
        val w = doc.height; val h = doc.width
        val m = Matrix()
        if (clockwise) { m.setRotate(90f); m.postTranslate(w.toFloat(), 0f) }
        else { m.setRotate(-90f); m.postTranslate(0f, h.toFloat()) }
        return remake(doc, w, h, m, resample = false)
    }

    fun flip(doc: Document, horizontal: Boolean): Document {
        val m = Matrix()
        if (horizontal) { m.setScale(-1f, 1f); m.postTranslate(doc.width.toFloat(), 0f) }
        else { m.setScale(1f, -1f); m.postTranslate(0f, doc.height.toFloat()) }
        return remake(doc, doc.width, doc.height, m, resample = false)
    }

    private fun remake(doc: Document, w: Int, h: Int, m: Matrix, resample: Boolean): Document {
        require(w.toLong() * h <= MAX_PIXELS) { "Too big" }
        val d = Document(w, h)
        d.backgroundColor = doc.backgroundColor
        d.backgroundVisible = doc.backgroundVisible
        d.layerNameCounter = doc.layerNameCounter
        d.groupNameCounter = doc.groupNameCounter
        d.galleryId = doc.galleryId
        d.title = doc.title
        d.origin = doc.origin
        for ((id, g) in doc.groups) d.groups[id] = g.copy()
        ProjectIO.guideFromJson(ProjectIO.guideToJson(doc.guide), d.guide)
        val paint = Paint(if (resample) Paint.FILTER_BITMAP_FLAG else 0)
        for (src in doc.layers) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val l = Layer(bmp, src.name)
            l.copySettingsFrom(src)
            l.groupId = src.groupId
            val v = src.vector
            val sc = l.scene
            when {
                v != null -> {
                    val nv = v.copy()
                    nv.shapes.forEach { it.transform(m) }
                    l.vector = nv
                    nv.render(bmp)
                }
                sc != null -> {
                    // a 3D layer is drawn again at the new size (the camera keeps its view)
                    val px = IntArray(w * h)
                    Renderer3D(sc, w, h).render(px, if (w.toLong() * h <= 9_000_000L) 2 else 1)
                    bmp.setPixels(px, 0, w, 0, 0, w, h)
                }
                else -> Canvas(bmp).drawBitmap(src.bitmap, m, paint)
            }
            d.layers.add(l)
        }
        d.activeIndex = doc.activeIndex
        d.clampActive()
        d.normalizeGroups()
        d.revision = doc.revision + 1
        return d
    }
}
