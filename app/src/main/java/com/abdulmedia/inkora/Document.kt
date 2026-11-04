package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.roundToInt

/** Layer blend modes (same set of modes most painting apps offer). */
enum class LayerBlend(val label: String, val mode: BlendMode) {
    NORMAL("Normal", BlendMode.SRC_OVER),
    MULTIPLY("Multiply", BlendMode.MULTIPLY),
    DARKEN("Darken", BlendMode.DARKEN),
    COLOR_BURN("Color Burn", BlendMode.COLOR_BURN),
    LIGHTEN("Lighten", BlendMode.LIGHTEN),
    SCREEN("Screen", BlendMode.SCREEN),
    COLOR_DODGE("Color Dodge", BlendMode.COLOR_DODGE),
    ADD("Add", BlendMode.PLUS),
    OVERLAY("Overlay", BlendMode.OVERLAY),
    SOFT_LIGHT("Soft Light", BlendMode.SOFT_LIGHT),
    HARD_LIGHT("Hard Light", BlendMode.HARD_LIGHT),
    DIFFERENCE("Difference", BlendMode.DIFFERENCE),
    EXCLUSION("Exclusion", BlendMode.EXCLUSION),
    HUE("Hue", BlendMode.HUE),
    SATURATION("Saturation", BlendMode.SATURATION),
    COLOR("Color", BlendMode.COLOR),
    LUMINOSITY("Luminosity", BlendMode.LUMINOSITY);
}

class Layer(var bitmap: Bitmap, var name: String) {
    val id: Int = nextId++
    var visible = true
    var opacity = 1f
    var blend = LayerBlend.NORMAL
    var alphaLock = false
    var isClippingMask = false
    /** non-null = this is a vector layer; [bitmap] is then a rendered copy of the shapes */
    var vector: VectorData? = null

    companion object {
        private var nextId = 1
    }
}

/** Draws something on top of the active layer while a stroke is in progress. */
fun interface ActiveOverlay {
    fun draw(canvas: Canvas)
}

class Document(val width: Int, val height: Int) {
    /** index 0 = bottom layer. */
    val layers = ArrayList<Layer>()
    var activeIndex = 0
    var backgroundColor = 0xFFFFFFFF.toInt()
    var backgroundVisible = true
    var layerNameCounter = 1
    /** grid / perspective guide of this canvas */
    val guide = GuideSettings()
    /** id of this artwork in the gallery (also names its time-lapse) */
    var galleryId: String? = null
    var title = "Untitled Artwork"
    /** "drawn" = started in Inkora, "imported" = came from a project file or photo */
    var origin = "drawn"
    /** Incremented on every change, used to know when autosave is needed. */
    var revision = 0

    val active: Layer get() = layers[activeIndex.coerceIn(0, layers.size - 1)]
    val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())

    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plainPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstInPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_IN }
    private val bgPaint = Paint()

    fun newLayerBitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    fun createLayer(): Layer = Layer(newLayerBitmap(), "Layer ${layerNameCounter++}")

    /** How many layers fit in a safe memory budget (like Procreate's limit). */
    fun maxLayers(): Int {
        val bytes = width.toLong() * height * 4
        return (1_100_000_000L / bytes).toInt().coerceIn(3, 64)
    }

    fun clampActive() {
        activeIndex = activeIndex.coerceIn(0, (layers.size - 1).coerceAtLeast(0))
    }

    /**
     * Composites every visible layer (with opacity + blend mode) onto [canvas],
     * in document coordinates.
     */
    fun draw(canvas: Canvas, filter: Boolean, overlay: ActiveOverlay? = null, withBackground: Boolean = true) {
        val outer = canvas.saveLayer(bounds, null)
        if (withBackground && backgroundVisible) {
            bgPaint.color = backgroundColor
            canvas.drawRect(bounds, bgPaint)
        }
        layerPaint.isFilterBitmap = filter
        plainPaint.isFilterBitmap = filter
        dstInPaint.isFilterBitmap = filter
        for (i in layers.indices) {
            val layer = layers[i]
            if (!layer.visible) continue
            layerPaint.alpha = (layer.opacity * 255).roundToInt().coerceIn(0, 255)
            layerPaint.blendMode = layer.blend.mode
            if (layer.isClippingMask) {
                var baseIndex = i - 1
                while (baseIndex >= 0 && layers[baseIndex].isClippingMask) baseIndex--
                val baseLayer = if (baseIndex >= 0) layers[baseIndex] else null
                if (baseLayer != null && baseLayer.visible) {
                    val s = canvas.saveLayer(bounds, layerPaint)
                    if (overlay != null && i == activeIndex) {
                        val sInner = canvas.saveLayer(bounds, plainPaint)
                        canvas.drawBitmap(layer.bitmap, 0f, 0f, plainPaint)
                        overlay.draw(canvas)
                        canvas.restoreToCount(sInner)
                    } else {
                        canvas.drawBitmap(layer.bitmap, 0f, 0f, plainPaint)
                    }
                    canvas.drawBitmap(baseLayer.bitmap, 0f, 0f, dstInPaint)
                    canvas.restoreToCount(s)
                } else if (baseLayer != null) {
                    // base layer hidden: whatever is clipped to it is hidden too
                    continue
                } else {
                    if (overlay != null && i == activeIndex) {
                        val s = canvas.saveLayer(bounds, layerPaint)
                        canvas.drawBitmap(layer.bitmap, 0f, 0f, plainPaint)
                        overlay.draw(canvas)
                        canvas.restoreToCount(s)
                    } else {
                        canvas.drawBitmap(layer.bitmap, 0f, 0f, layerPaint)
                    }
                }
            } else {
                if (overlay != null && i == activeIndex) {
                    val s = canvas.saveLayer(bounds, layerPaint)
                    canvas.drawBitmap(layer.bitmap, 0f, 0f, plainPaint)
                    overlay.draw(canvas)
                    canvas.restoreToCount(s)
                } else {
                    canvas.drawBitmap(layer.bitmap, 0f, 0f, layerPaint)
                }
            }
        }
        canvas.restoreToCount(outer)
    }

    /** Flattened copy of the artwork. */
    fun flatten(withBackground: Boolean = true, forceOpaque: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (forceOpaque) c.drawColor(if (backgroundVisible) backgroundColor else 0xFFFFFFFF.toInt())
        draw(c, true, null, withBackground)
        return out
    }

    /** Color of the composited image at a document pixel. */
    fun sampleColor(x: Int, y: Int): Int {
        val one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(one)
        c.translate(-x.toFloat(), -y.toFloat())
        draw(c, false)
        val color = one.getPixel(0, 0)
        one.recycle()
        return color
    }

    companion object {
        fun blank(width: Int, height: Int): Document {
            val d = Document(width, height)
            d.layers.add(d.createLayer())
            d.activeIndex = 0
            return d
        }
    }
}
