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
    /** the group this layer belongs to ([Document.groups]), or null. A group's layers sit together. */
    var groupId: Int? = null
    /**
     * A "shadow" layer: you see it while you draw (a photo to trace, a pose), but it stays out of
     * the time-lapse, exports and gallery thumbnails.
     */
    var shadow = false
    /** non-null = a 3D layer (figures, poses, objects); [bitmap] is then a rendered copy of the scene */
    var scene: Scene3D? = null
    /** a reference layer: Fill and ColorDrop find their edges in the reference layers (your line art) */
    var reference = false
    /**
     * A layer mask for the layer right below it: where the mask has paint the layer shows, where it
     * is erased the layer is hidden. Paint brings parts back, the eraser hides them.
     */
    var isLayerMask = false
    /** non-null = an adjustment layer: it changes the colors below it; [bitmap] is where it shows */
    var adjust: AdjustSettings? = null
    /** shown on screen only, while an adjustment's slider moves (the pixels change when it is applied) */
    var previewFilter: android.graphics.ColorFilter? = null

    /** copies the layer settings (not the pixels) of [src] */
    fun copySettingsFrom(src: Layer) {
        opacity = src.opacity; blend = src.blend; alphaLock = src.alphaLock; isClippingMask = src.isClippingMask
        visible = src.visible; shadow = src.shadow; scene = src.scene?.copy()
        reference = src.reference; isLayerMask = src.isLayerMask; adjust = src.adjust?.copy()
    }

    companion object {
        private var nextId = 1
    }
}

/**
 * A layer group. Its layers keep their own opacity and blend modes; the group can hide them all,
 * fade them together and blend them as one picture. A Normal group at 100% passes its layers'
 * blend modes through to what is below (like Photoshop's "Pass Through").
 */
class LayerGroup(var name: String, val id: Int = nextId++) {
    var visible = true
    var opacity = 1f
    var blend = LayerBlend.NORMAL
    var collapsed = false

    /** blended as one picture (instead of passing through) */
    val isolated get() = blend != LayerBlend.NORMAL || opacity < 0.999f

    fun copy() = LayerGroup(name, id).also { it.visible = visible; it.opacity = opacity; it.blend = blend; it.collapsed = collapsed }

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

    /** layer groups by id; a layer's [Layer.groupId] points here */
    val groups = LinkedHashMap<Int, LayerGroup>()
    var groupNameCounter = 1

    val active: Layer get() = layers[activeIndex.coerceIn(0, layers.size - 1)]

    /** the group of [layer], if it has a live one */
    fun groupOf(layer: Layer): LayerGroup? = layer.groupId?.let { groups[it] }

    fun newGroup(): LayerGroup = LayerGroup("Group ${groupNameCounter++}").also { groups[it.id] = it }

    /** the layers of group [id], bottom first */
    fun members(id: Int): List<Layer> = layers.filter { it.groupId == id }

    /** index range (bottom..top) of group [id], or null */
    fun rangeOf(id: Int): IntRange? {
        val a = layers.indexOfFirst { it.groupId == id }
        if (a < 0) return null
        return a..layers.indexOfLast { it.groupId == id }
    }

    /**
     * Keeps every group's layers together: a layer that ends up between two layers of one group
     * joins it, and a layer whose group no longer exists leaves it.
     */
    fun normalizeGroups() {
        for (l in layers) if (l.groupId != null && groups[l.groupId!!] == null) l.groupId = null
        for (id in groups.keys.toList()) {
            val r = rangeOf(id) ?: continue
            for (i in r) layers[i].groupId = id
        }
    }
    val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())

    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plainPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstInPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_IN }
    private val bgPaint = Paint()
    private val groupPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun newLayerBitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    fun createLayer(): Layer = Layer(newLayerBitmap(), "Layer ${layerNameCounter++}")

    /** How many layers fit in this device's memory budget (like Procreate's limit). */
    fun maxLayers(): Int {
        val bytes = width.toLong() * height * 4
        return (layerBudget / bytes).toInt().coerceIn(3, 200)
    }

    fun clampActive() {
        activeIndex = activeIndex.coerceIn(0, (layers.size - 1).coerceAtLeast(0))
    }

    /**
     * Composites every visible layer (with opacity + blend mode) onto [canvas],
     * in document coordinates.
     */
    fun draw(
        canvas: Canvas, filter: Boolean, overlay: ActiveOverlay? = null, withBackground: Boolean = true,
        /** more overlays for other layers (a transform of several layers at once) */
        extra: Map<Layer, ActiveOverlay>? = null,
        /** false = a picture that leaves the app (export, time-lapse, thumbnail): shadow layers stay out */
        screen: Boolean = true
    ) {
        drawShadows = screen
        val outer = canvas.saveLayer(bounds, null)
        layerPaint.isFilterBitmap = filter
        plainPaint.isFilterBitmap = filter
        dstInPaint.isFilterBitmap = filter
        val base: (Canvas) -> Unit = { c ->
            if (withBackground && backgroundVisible) {
                bgPaint.color = backgroundColor
                c.drawRect(bounds, bgPaint)
            }
        }
        drawStack(canvas, layers.size - 1, base, { i -> if (overlay != null && i == activeIndex) overlay else extra?.get(layers[i]) })
        canvas.restoreToCount(outer)
        drawShadows = true
    }

    private var drawShadows = true

    /** true when [l] is an adjustment layer that changes the picture now */
    private fun adjusting(l: Layer) = l.adjust != null && l.visible && l.groupId == null && !(l.shadow && !drawShadows)

    /**
     * Layers 0..[to] over [base]: the top adjustment layer changes everything under it (drawn
     * again the same way, for the adjustment layers lower down), then the layers above it go on top.
     * Adjustment layers inside a group do nothing for now.
     */
    private fun drawStack(canvas: Canvas, to: Int, base: (Canvas) -> Unit, overlayAt: (Int) -> ActiveOverlay?, groups: Boolean = true, include: (Int) -> Boolean = { true }) {
        var k = to
        while (k >= 0 && !(adjusting(layers[k]) && include(k))) k--
        if (k < 0) {
            base(canvas)
            drawLayers(canvas, 0, to, overlayAt, groups, include)
            return
        }
        AdjustRender.draw(canvas, bounds, layers[k], overlayAt(k)) { c -> drawStack(c, k - 1, base, overlayAt, groups, include) }
        if (k < to) drawLayers(canvas, k + 1, to, overlayAt, groups, include)
    }

    /** true when the artwork has layers that only show on screen */
    val hasShadowLayers get() = layers.any { it.shadow }

    /**
     * Draws layers [from]..[to] (with their groups when [withGroups]). Only layers [include]
     * accepts are drawn.
     */
    private fun drawLayers(
        canvas: Canvas, from: Int, to: Int, overlayAt: (Int) -> ActiveOverlay?, withGroups: Boolean,
        include: (Int) -> Boolean = { true }
    ) {
        var i = from
        while (i <= to) {
            val g = if (withGroups) groupOf(layers[i]) else null
            if (g != null) {
                var end = i
                while (end + 1 <= to && layers[end + 1].groupId == g.id) end++
                if (g.visible) {
                    if (g.isolated) {
                        groupPaint.alpha = (g.opacity * 255).roundToInt().coerceIn(0, 255)
                        groupPaint.blendMode = g.blend.mode
                        val s = canvas.saveLayer(bounds, groupPaint)
                        drawLayers(canvas, i, end, overlayAt, false, include)
                        canvas.restoreToCount(s)
                    } else drawLayers(canvas, i, end, overlayAt, false, include)
                }
                i = end + 1
                continue
            }
            val layer = layers[i]
            if (layer.isLayerMask) { i++; continue } // drawn together with the layer it masks
            if (layer.adjust != null) { i++; continue } // its change is drawn by drawStack
            if (include(i)) {
                val mask = maskOf(i)
                if (mask != null) drawMasked(canvas, i, overlayAt(i), mask, overlayAt(i + 1))
                else drawLayer(canvas, i, overlayAt(i))
            }
            i++
        }
    }

    /** the visible layer mask of layer [i], if it has one */
    fun maskOf(i: Int): Layer? {
        val m = layers.getOrNull(i + 1) ?: return null
        if (!m.isLayerMask || m.groupId != layers[i].groupId || layers[i].isLayerMask) return null
        return if (m.visible) m else null
    }

    /** true when layer [i] has a mask right above it (shown or hidden) */
    fun hasMask(i: Int): Boolean {
        val m = layers.getOrNull(i + 1) ?: return false
        return m.isLayerMask && m.groupId == layers[i].groupId && !layers[i].isLayerMask
    }

    private fun drawMasked(canvas: Canvas, i: Int, ov: ActiveOverlay?, mask: Layer, maskOv: ActiveOverlay?) {
        val layer = layers[i]
        if (!layer.visible) return
        if (layer.shadow && !drawShadows) return
        layerPaint.alpha = (layer.opacity * 255).roundToInt().coerceIn(0, 255)
        layerPaint.blendMode = layer.blend.mode
        layerPaint.colorFilter = layer.previewFilter
        val s = canvas.saveLayer(bounds, layerPaint)
        canvas.drawBitmap(layer.bitmap, 0f, 0f, plainPaint)
        ov?.draw(canvas)
        // the mask (with a stroke being painted on it) cuts the layer
        val m = canvas.saveLayer(bounds, dstInPaint)
        canvas.drawBitmap(mask.bitmap, 0f, 0f, plainPaint)
        maskOv?.draw(canvas)
        canvas.restoreToCount(m)
        canvas.restoreToCount(s)
    }

    /**
     * The picture Fill and ColorDrop look at: the reference layers when there are any (so you can
     * color on another layer under your line art), otherwise the whole artwork.
     */
    fun fillReference(): Bitmap {
        val refs = layers.filter { it.reference && it.visible }
        return if (refs.isEmpty()) flatten(withBackground = true) else compose(refs)
    }

    private fun drawLayer(canvas: Canvas, i: Int, ov: ActiveOverlay?) {
        val layer = layers[i]
        if (!layer.visible) return
        if (layer.shadow && !drawShadows) return
        layerPaint.alpha = (layer.opacity * 255).roundToInt().coerceIn(0, 255)
        layerPaint.blendMode = layer.blend.mode
        layerPaint.colorFilter = layer.previewFilter
        if (layer.isClippingMask) {
            // clipped to the first non-clipping layer below it, inside the same group
            var baseIndex = i - 1
            while (baseIndex >= 0 && (layers[baseIndex].isClippingMask || layers[baseIndex].isLayerMask) && layers[baseIndex].groupId == layer.groupId) baseIndex--
            val baseLayer = if (baseIndex >= 0 && layers[baseIndex].groupId == layer.groupId) layers[baseIndex] else null
            if (baseLayer != null) {
                // base layer hidden: whatever is clipped to it is hidden too
                if (!baseLayer.visible) return
                val s = canvas.saveLayer(bounds, layerPaint)
                if (ov != null) {
                    val sInner = canvas.saveLayer(bounds, plainPaint)
                    canvas.drawBitmap(layer.bitmap, 0f, 0f, plainPaint)
                    ov.draw(canvas)
                    canvas.restoreToCount(sInner)
                } else {
                    canvas.drawBitmap(layer.bitmap, 0f, 0f, plainPaint)
                }
                canvas.drawBitmap(baseLayer.bitmap, 0f, 0f, dstInPaint)
                canvas.restoreToCount(s)
                return
            }
        }
        if (ov != null) {
            val s = canvas.saveLayer(bounds, layerPaint)
            canvas.drawBitmap(layer.bitmap, 0f, 0f, plainPaint)
            ov.draw(canvas)
            canvas.restoreToCount(s)
        } else {
            canvas.drawBitmap(layer.bitmap, 0f, 0f, layerPaint)
        }
    }

    /**
     * The layers [subset] composited on their own (no background, no group effects), for merging
     * them into one layer.
     */
    fun compose(subset: Collection<Layer>): Bitmap {
        val out = newLayerBitmap()
        val c = Canvas(out)
        layerPaint.isFilterBitmap = false
        plainPaint.isFilterBitmap = false
        dstInPaint.isFilterBitmap = false
        val set = subset.toHashSet()
        val s = c.saveLayer(bounds, null)
        drawStack(c, layers.size - 1, {}, { null }, false) { layers[it] in set }
        c.restoreToCount(s)
        return out
    }

    /** Flattened copy of the artwork. */
    fun flatten(withBackground: Boolean = true, forceOpaque: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (forceOpaque) c.drawColor(if (backgroundVisible) backgroundColor else 0xFFFFFFFF.toInt())
        draw(c, true, null, withBackground, screen = false)
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
        /**
         * Memory for all layers together: a quarter of the device's RAM (layer pixels live in
         * native memory, not the Java heap), between 600 MB and 4 GB. Set at start-up.
         * 12 GB of RAM gives about 137 layers at 2960 × 1848.
         */
        var layerBudget = 1_100_000_000L
            private set

        fun setDeviceMemory(totalRamBytes: Long) {
            if (totalRamBytes <= 0) return
            layerBudget = (totalRamBytes / 4).coerceIn(600_000_000L, 4_000_000_000L)
        }

        fun blank(width: Int, height: Int): Document {
            val d = Document(width, height)
            d.layers.add(d.createLayer())
            d.activeIndex = 0
            return d
        }
    }
}
