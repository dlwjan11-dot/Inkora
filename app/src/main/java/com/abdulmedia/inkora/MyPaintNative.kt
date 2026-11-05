package com.abdulmedia.inkora

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.ln
import kotlin.math.max

/**
 * The MyPaint brush engine (libmypaint, ISC license), built into the app as native code.
 * "Living" brushes paint with it: the paint on the layer mixes, smears and thins out while
 * you draw, like wet paint does.
 */
object MyPaintNative {
    /** false when the native part could not be loaded: living brushes then are not offered */
    val ready: Boolean = runCatching { System.loadLibrary("inkora_paint"); true }.getOrDefault(false)

    @JvmStatic external fun nBrushNew(): Long
    @JvmStatic external fun nBrushFree(b: Long)
    @JvmStatic external fun nBrushLoad(b: Long, json: String): Boolean
    @JvmStatic external fun nBrushSet(b: Long, name: String, v: Float)
    @JvmStatic external fun nBrushGet(b: Long, name: String): Float
    @JvmStatic external fun nBrushColor(b: Long, h: Float, s: Float, v: Float)
    @JvmStatic external fun nSurfaceNew(w: Int, h: Int): Long
    @JvmStatic external fun nSurfaceFree(s: Long)
    @JvmStatic external fun nSymmetry(s: Long, type: Int, cx: Float, cy: Float, angle: Float, lines: Int)
    @JvmStatic external fun nBegin(s: Long, b: Long)
    @JvmStatic external fun nStroke(s: Long, b: Long, layer: Bitmap, events: FloatArray, n: Int,
                                    viewZoom: Float, viewRotation: Float, out: IntArray): Boolean
    @JvmStatic external fun nOriginal(s: Long, layer: Bitmap, dst: Bitmap, x: Int, y: Int): Boolean
    @JvmStatic external fun nEnd(s: Long)
}

/** The living brushes' settings files (in the app's assets, folder "living"). */
object LivingBrushes {
    private var appContext: Context? = null
    private val cache = HashMap<String, String>()

    fun init(context: Context) { appContext = context.applicationContext }

    /** the .myb text of a living brush, or null */
    fun json(name: String): String? = cache[name] ?: runCatching {
        appContext!!.assets.open("living/$name.myb").bufferedReader().use { it.readText() }
    }.getOrNull()?.also { cache[name] = it }

    /** the brush's own size (diameter in pixels) as its file sets it */
    fun defaultSize(name: String): Float {
        val j = json(name) ?: return 20f
        return runCatching {
            val r = org.json.JSONObject(j).getJSONObject("settings").getJSONObject("radius_logarithmic").getDouble("base_value")
            (kotlin.math.exp(r) * 2).toFloat()
        }.getOrDefault(20f)
    }
}

/**
 * One canvas's living-brush painter. A stroke paints straight onto the layer; the layer as it
 * was is kept by the engine for the parts the stroke touched, so undo costs only that.
 */
class LivingPainter {
    private var surface = 0L
    private var sw = 0
    private var sh = 0
    private var brush = 0L
    private var events = FloatArray(6 * 64)
    private var count = 0
    private var lastTime = 0L
    private val out = IntArray(4)
    /** the part of the layer this stroke changed so far */
    val dirty = Rect()
    var active = false
        private set
    var viewZoom = 1f

    /**
     * Gets ready for a stroke with [b] in [color] on a [w] × [h] layer. Returns false when the
     * brush can't be used (no native engine, or a broken file).
     */
    fun begin(b: Brush, color: Int, erasing: Boolean, alphaLock: Boolean, w: Int, h: Int,
              symmetry: Symmetry, cx: Float, cy: Float, time: Long): Boolean {
        val name = b.preset.living ?: return false
        if (!MyPaintNative.ready) return false
        val json = LivingBrushes.json(name) ?: return false
        if (surface == 0L || sw != w || sh != h) {
            if (surface != 0L) MyPaintNative.nSurfaceFree(surface)
            surface = MyPaintNative.nSurfaceNew(w, h)
            sw = w; sh = h
        }
        if (brush == 0L) brush = MyPaintNative.nBrushNew()
        if (surface == 0L || brush == 0L) return false
        if (!MyPaintNative.nBrushLoad(brush, json)) return false
        configure(brush, b, color, erasing, alphaLock)
        when (symmetry) {
            Symmetry.OFF -> MyPaintNative.nSymmetry(surface, -1, cx, cy, 0f, 2)
            Symmetry.VERTICAL -> MyPaintNative.nSymmetry(surface, 0, cx, cy, 0f, 2)
            Symmetry.HORIZONTAL -> MyPaintNative.nSymmetry(surface, 1, cx, cy, 0f, 2)
            Symmetry.QUADRANT -> MyPaintNative.nSymmetry(surface, 2, cx, cy, 0f, 2)
            Symmetry.KALEIDOSCOPE -> MyPaintNative.nSymmetry(surface, 4, cx, cy, 0f, Symmetry.segments)
            else -> MyPaintNative.nSymmetry(surface, 3, cx, cy, 0f, symmetry.axes)
        }
        MyPaintNative.nBegin(surface, brush)
        count = 0
        lastTime = time
        dirty.setEmpty()
        active = true
        return true
    }

    /** one pen position (canvas pixels), pressure 0..1, at [time] (ms) */
    fun add(x: Float, y: Float, pressure: Float, time: Long) {
        if (!active) return
        if ((count + 1) * 6 > events.size) events = events.copyOf(events.size * 2)
        val k = count * 6
        events[k] = x; events[k + 1] = y; events[k + 2] = pressure
        events[k + 3] = 0f; events[k + 4] = 0f
        events[k + 5] = max(1L, time - lastTime) / 1000f
        lastTime = time
        count++
    }

    /** paints what was added onto [layer]; returns the changed part (or null) */
    fun flush(layer: Bitmap): Rect? {
        if (!active || count == 0) return null
        val changed = MyPaintNative.nStroke(surface, brush, layer, events, count, viewZoom, 0f, out)
        count = 0
        if (!changed) return null
        val r = Rect(out[0], out[1], out[0] + out[2], out[1] + out[3])
        if (!r.intersect(0, 0, sw, sh)) return null
        dirty.union(r)
        return r
    }

    /** the layer as it was before this stroke, for [r] */
    fun original(layer: Bitmap, r: Rect): Bitmap? {
        if (r.isEmpty) return null
        val bmp = runCatching { Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        return if (MyPaintNative.nOriginal(surface, layer, bmp, r.left, r.top)) bmp else { bmp.recycle(); null }
    }

    fun end() {
        if (surface != 0L) MyPaintNative.nEnd(surface)
        active = false
        count = 0
    }

    fun release() {
        end()
        if (surface != 0L) MyPaintNative.nSurfaceFree(surface)
        if (brush != 0L) MyPaintNative.nBrushFree(brush)
        surface = 0L; brush = 0L
    }

    companion object {
        /** The app's brush settings on top of the brush file: size, opacity, color, smoothing. */
        fun configure(handle: Long, b: Brush, color: Int, erasing: Boolean, alphaLock: Boolean) {
            val name = b.preset.living ?: return
            // the size: the file's own pressure curve stays, its base moves to the chosen size
            val fileSize = LivingBrushes.defaultSize(name)
            val base = MyPaintNative.nBrushGet(handle, "radius_logarithmic")
            MyPaintNative.nBrushSet(handle, "radius_logarithmic", base + ln(max(0.5f, b.size) / max(0.5f, fileSize)))
            val op = MyPaintNative.nBrushGet(handle, "opaque")
            MyPaintNative.nBrushSet(handle, "opaque", op * b.opacity.coerceIn(0f, 1f))
            if (b.streamline > 0f) {
                val st = MyPaintNative.nBrushGet(handle, "slow_tracking")
                MyPaintNative.nBrushSet(handle, "slow_tracking", max(st, b.streamline * 6f))
            }
            if (erasing) MyPaintNative.nBrushSet(handle, "eraser", 1f)
            if (alphaLock) MyPaintNative.nBrushSet(handle, "lock_alpha", 1f)
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            MyPaintNative.nBrushColor(handle, hsv[0] / 360f, hsv[1], hsv[2])
        }

        /** a sample stroke for the brush library (light → heavy → light) */
        fun preview(b: Brush, w: Int, h: Int, color: Int): Bitmap? {
            if (!MyPaintNative.ready || b.preset.living == null) return null
            val json = LivingBrushes.json(b.preset.living) ?: return null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val s = MyPaintNative.nSurfaceNew(w, h)
            val br = MyPaintNative.nBrushNew()
            try {
                if (s == 0L || br == 0L || !MyPaintNative.nBrushLoad(br, json)) return null
                val pb = Brush(b.preset).apply { size = b.size.coerceIn(4f, h * 0.45f); opacity = b.opacity; streamline = 0f }
                // a blender only moves paint: give it some to move
                if (MyPaintNative.nBrushGet(br, "smudge") >= 0.99f) {
                    val c = android.graphics.Canvas(bmp)
                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    p.color = 0xFF5B8DEF.toInt(); c.drawRect(w * 0.2f, h * 0.15f, w * 0.45f, h * 0.85f, p)
                    p.color = 0xFFE8B04A.toInt(); c.drawRect(w * 0.55f, h * 0.15f, w * 0.8f, h * 0.85f, p)
                }
                configure(br, pb, color, erasing = false, alphaLock = false)
                MyPaintNative.nSymmetry(s, -1, 0f, 0f, 0f, 2)
                MyPaintNative.nBegin(s, br)
                val n = 120
                val ev = FloatArray(6 * (n + 1))
                for (i in 0..n) {
                    val t = i / n.toFloat()
                    ev[i * 6] = 20f + t * (w - 40f)
                    ev[i * 6 + 1] = h / 2f + kotlin.math.sin(t * 2 * Math.PI).toFloat() * (h * 0.22f)
                    ev[i * 6 + 2] = 0.15f + 0.85f * kotlin.math.sin(t * Math.PI).toFloat()
                    ev[i * 6 + 5] = 1f / 120f
                }
                MyPaintNative.nStroke(s, br, bmp, ev, n + 1, 1f, 0f, IntArray(4))
                return bmp
            } finally {
                if (s != 0L) MyPaintNative.nSurfaceFree(s)
                if (br != 0L) MyPaintNative.nBrushFree(br)
            }
        }
    }
}
