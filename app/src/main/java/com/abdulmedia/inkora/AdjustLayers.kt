package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Adjustment layers: a layer that changes the colors of everything below it, and stays editable.
 * Its pixels are its mask: where the layer is painted the change shows, where it is erased the
 * picture below is left as it was (a new adjustment layer is painted all over).
 */
enum class LayerAdjust(val label: String, val sliders: AdjustKind?) {
    HSB("Hue / Saturation / Brightness", AdjustKind.HSB),
    BRIGHT_CONTRAST("Brightness / Contrast", AdjustKind.BRIGHT_CONTRAST),
    COLOR_BALANCE("Color Balance", AdjustKind.COLOR_BALANCE),
    CURVES("Curves", null),
    GRADIENT_MAP("Gradient Map", AdjustKind.GRADIENT_MAP),
    POSTERIZE("Posterize", AdjustKind.POSTERIZE),
    INVERT("Invert Colors", AdjustKind.INVERT),
    GRAYSCALE("Black & White", AdjustKind.GRAYSCALE)
}

class AdjustSettings(
    val kind: LayerAdjust,
    val values: FloatArray = FloatArray(4) { i -> kind.sliders?.sliders?.getOrNull(i)?.default ?: 0f },
    /** Curves: four curves (all colors, red, green, blue), each four points x, y from 0 to 1 */
    val curves: FloatArray = Curves.straight(),
    /** Gradient Map's "Your color", kept so the layer looks the same later */
    var userColor: Int = GradientMaps.userColor
) {
    fun copy() = AdjustSettings(kind, values.copyOf(), curves.copyOf(), userColor)

    fun sameAs(o: AdjustSettings) = kind == o.kind && values.contentEquals(o.values) && curves.contentEquals(o.curves) && userColor == o.userColor

    /** the change as a color matrix, for the ones that are only that */
    fun matrix(): ColorMatrix? = kind.sliders?.let { Adjust.matrixFor(it, values) }

    val byLuminance get() = kind == LayerAdjust.GRADIENT_MAP
    val strength get() = if (kind == LayerAdjust.GRADIENT_MAP) (values[1] / 100f).coerceIn(0f, 1f) else 1f

    /**
     * 256 colors. Curves and Posterize: red, green and blue each read their own channel of the table.
     * Gradient Map: the whole color for each lightness.
     */
    fun lut(): IntArray = when (kind) {
        LayerAdjust.GRADIENT_MAP -> {
            val keep = GradientMaps.userColor
            GradientMaps.userColor = userColor
            GradientMaps.lut(values[0].roundToInt()).also { GradientMaps.userColor = keep }
        }
        LayerAdjust.POSTERIZE -> {
            val levels = values[0].roundToInt().coerceIn(2, 32)
            val step = 255f / (levels - 1)
            IntArray(256) { k -> val q = ((k / step).roundToInt() * step).roundToInt().coerceIn(0, 255); (q shl 16) or (q shl 8) or q }
        }
        else -> IntArray(256) { k ->
            val x = k / 255f
            fun ch(c: Int) = (Curves.eval(curves, c + 1, Curves.eval(curves, 0, x)) * 255f).roundToInt().coerceIn(0, 255)
            (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
    }

    fun toJson() = JSONObject().apply {
        put("kind", kind.name)
        put("values", JSONArray().apply { values.forEach { put(it.toDouble()) } })
        if (kind == LayerAdjust.CURVES) put("curves", JSONArray().apply { curves.forEach { put(it.toDouble()) } })
        put("userColor", userColor)
    }

    companion object {
        fun fromJson(o: JSONObject?): AdjustSettings? {
            o ?: return null
            val kind = runCatching { LayerAdjust.valueOf(o.optString("kind")) }.getOrNull() ?: return null
            val s = AdjustSettings(kind)
            o.optJSONArray("values")?.let { a -> for (i in 0 until minOf(a.length(), s.values.size)) s.values[i] = a.optDouble(i).toFloat() }
            o.optJSONArray("curves")?.let { a -> if (a.length() == s.curves.size) for (i in 0 until a.length()) s.curves[i] = a.optDouble(i).toFloat() }
            s.userColor = o.optInt("userColor", s.userColor)
            return s
        }
    }
}

/** smooth curves through four points (they never turn back, so more light never gives less) */
object Curves {
    fun straight() = FloatArray(32) { i -> val p = (i % 8) / 2; floatArrayOf(0f, 0.33f, 0.66f, 1f)[p] }

    /** curve [c] (0 all, 1 red, 2 green, 3 blue) at [t] */
    fun eval(curves: FloatArray, c: Int, t: Float): Float {
        val o = c * 8
        val n = 4
        val x = FloatArray(n) { curves[o + it * 2] }
        val y = FloatArray(n) { curves[o + it * 2 + 1] }
        if (t <= x[0]) return y[0].coerceIn(0f, 1f)
        if (t >= x[n - 1]) return y[n - 1].coerceIn(0f, 1f)
        val d = FloatArray(n - 1) { (y[it + 1] - y[it]) / (x[it + 1] - x[it]).coerceAtLeast(1e-4f) }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) { val k = 3f / sqrt(s); m[i] = k * a * d[i]; m[i + 1] = k * b * d[i] }
        }
        var i = 0
        while (i < n - 2 && t > x[i + 1]) i++
        val h = (x[i + 1] - x[i]).coerceAtLeast(1e-4f)
        val u = (t - x[i]) / h
        val h00 = 2 * u * u * u - 3 * u * u + 1; val h10 = u * u * u - 2 * u * u + u
        val h01 = -2 * u * u * u + 3 * u * u; val h11 = u * u * u - u * u
        return (h00 * y[i] + h10 * h * m[i] + h01 * y[i + 1] + h11 * h * m[i + 1]).coerceIn(0f, 1f)
    }
}

/** an adjustment layer's settings changed */
class AdjustCmd(private val layer: Layer, private val before: AdjustSettings, private val after: AdjustSettings) : Command {
    override val bytes: Long = 256
    override fun undo(doc: Document) { layer.adjust = before.copy() }
    override fun redo(doc: Document) { layer.adjust = after.copy() }
}

/**
 * Draws an adjustment over what is below it. On screen (Android 13 and later) the work is done by
 * the graphics chip; pictures made in memory (export, thumbnails) are worked out pixel by pixel.
 */
object AdjustRender {
    private const val SHADER = """
        uniform shader content;
        uniform shader lut;
        uniform float byLum;
        uniform float strength;
        half4 main(float2 p) {
            half4 c = content.eval(p);
            if (c.a <= 0.0) return c;
            half3 rgb = clamp(c.rgb / c.a, 0.0, 1.0);
            half3 o = rgb;
            if (byLum > 0.5) {
                float l = dot(float3(rgb), float3(0.30, 0.59, 0.11));
                o = lut.eval(float2(l * 255.0 + 0.5, 0.5)).rgb;
            } else {
                o = half3(lut.eval(float2(rgb.r * 255.0 + 0.5, 0.5)).r,
                          lut.eval(float2(rgb.g * 255.0 + 0.5, 0.5)).g,
                          lut.eval(float2(rgb.b * 255.0 + 0.5, 0.5)).b);
            }
            o = mix(rgb, o, half(strength));
            return half4(o * c.a, c.a);
        }
    """

    private class Nodes(val plain: RenderNode, val changed: RenderNode) {
        var lutKey = 0
        var lutBmp: Bitmap? = null
        /** made once: building the shader is the slow part */
        var shader: Any? = null
    }
    private val nodes = WeakHashMap<Layer, Nodes>()

    /** false after the graphics chip's version failed once: pixel by pixel from then on */
    @Volatile private var gpuWorks = true

    /**
     * Draws onto [canvas] what [below] draws, changed by [layer]'s adjustment, through the layer's
     * pixels (its mask, with [ov] — a stroke being painted on it) at the layer's opacity.
     */
    fun draw(canvas: Canvas, bounds: RectF, layer: Layer, ov: ActiveOverlay?, below: (Canvas) -> Unit) {
        val a = layer.adjust ?: return below(canvas)
        val w = bounds.width().toInt(); val h = bounds.height().toInt()
        // the graphics chip works on the whole canvas at once: only for sizes every chip can hold
        val gpu = canvas.isHardwareAccelerated && w <= 8192 && h <= 8192
        val n = if (gpu) synchronized(nodes) { nodes.getOrPut(layer) { Nodes(RenderNode("adjust-below"), RenderNode("adjust-changed")) } } else null
        if (n != null) {
            n.plain.setPosition(0, 0, w, h)
            val rec = n.plain.beginRecording(w, h)
            below(rec)
            n.plain.endRecording()
        }
        fun drawBelow(c: Canvas) { if (n != null) c.drawRenderNode(n.plain) else below(c) }
        val amount = (layer.opacity * 255).roundToInt().coerceIn(0, 255)
        // paints of its own each time: exports can draw on another thread at the same moment
        val outPaint = Paint()
        val plusPaint = Paint().apply { blendMode = BlendMode.PLUS }
        val maskIn = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_IN }
        val maskOut = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_OUT }

        val outer = canvas.saveLayer(bounds, null)
        // the picture as it was, where the mask leaves it
        val s1 = canvas.saveLayer(bounds, null)
        drawBelow(canvas)
        maskOut.alpha = amount
        mask(canvas, bounds, layer, ov, maskOut)
        canvas.restoreToCount(s1)
        // the changed picture, where the mask shows it, added
        val s2 = canvas.saveLayer(bounds, plusPaint)
        val m = a.matrix()
        when {
            m != null -> {
                outPaint.colorFilter = ColorMatrixColorFilter(m)
                val s = canvas.saveLayer(bounds, outPaint)
                drawBelow(canvas)
                canvas.restoreToCount(s)
                outPaint.colorFilter = null
            }
            n != null && Build.VERSION.SDK_INT >= 33 && gpuWorks && runCatching { drawChangedGpu(canvas, n, a, w, h) }
                .onFailure { gpuWorks = false }.isSuccess -> {}
            else -> drawChangedCpu(canvas, bounds, a) { c -> drawBelow(c) }
        }
        maskIn.alpha = amount
        mask(canvas, bounds, layer, ov, maskIn)
        canvas.restoreToCount(s2)
        canvas.restoreToCount(outer)
    }

    private fun mask(canvas: Canvas, bounds: RectF, layer: Layer, ov: ActiveOverlay?, p: Paint) {
        if (ov == null) { canvas.drawBitmap(layer.bitmap, 0f, 0f, p); return }
        val s = canvas.saveLayer(bounds, p)
        canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
        ov.draw(canvas)
        canvas.restoreToCount(s)
    }

    @android.annotation.TargetApi(33)
    private fun drawChangedGpu(canvas: Canvas, n: Nodes, a: AdjustSettings, w: Int, h: Int) {
        val lut = a.lut()
        val key = lut.contentHashCode() * 31 + (if (a.byLuminance) 1 else 0)
        val shader = (n.shader as? RuntimeShader) ?: RuntimeShader(SHADER).also { n.shader = it; n.lutBmp = null }
        if (n.lutBmp == null || n.lutKey != key) {
            n.lutBmp = Bitmap.createBitmap(IntArray(256) { lut[it] or 0xFF000000.toInt() }, 256, 1, Bitmap.Config.ARGB_8888)
            n.lutKey = key
            shader.setInputShader("lut", BitmapShader(n.lutBmp!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        }
        shader.setFloatUniform("byLum", if (a.byLuminance) 1f else 0f)
        shader.setFloatUniform("strength", a.strength)
        n.changed.setPosition(0, 0, w, h)
        n.changed.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        val rec = n.changed.beginRecording(w, h)
        rec.drawRenderNode(n.plain)
        n.changed.endRecording()
        canvas.drawRenderNode(n.changed)
    }

    /** pixel by pixel, only over the part of the canvas being drawn */
    private fun drawChangedCpu(canvas: Canvas, bounds: RectF, a: AdjustSettings, below: (Canvas) -> Unit) {
        val clip = Rect()
        if (!canvas.getClipBounds(clip)) return
        if (!clip.intersect(0, 0, bounds.width().toInt(), bounds.height().toInt())) return
        val tmp = try { Bitmap.createBitmap(clip.width(), clip.height(), Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { return }
        val c = Canvas(tmp)
        c.translate(-clip.left.toFloat(), -clip.top.toFloat())
        below(c)
        applyLut(tmp, a)
        canvas.drawBitmap(tmp, clip.left.toFloat(), clip.top.toFloat(), null)
        tmp.recycle()
    }

    fun applyLut(bmp: Bitmap, a: AdjustSettings) {
        val lut = a.lut()
        val k = a.strength
        val w = bmp.width; val h = bmp.height
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (i in 0 until w) {
                val c = row[i]
                val al = c ushr 24
                if (al == 0) continue
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                var o = if (a.byLuminance) lut[(r * 77 + g * 151 + b * 28) shr 8]
                    else (lut[r] and 0xFF0000) or (lut[g] and 0xFF00) or (lut[b] and 0xFF)
                if (k < 1f) o = GradientMaps.mix(c, o, k)
                row[i] = (al shl 24) or (o and 0xFFFFFF)
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    /** [src] changed by [layer] (through its mask and opacity), as a new picture — for merging it down */
    fun bake(src: Bitmap, layer: Layer): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        draw(Canvas(out), RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), layer, null) { c -> c.drawBitmap(src, 0f, 0f, null) }
        return out
    }
}

/** Curves: a square graph with the four points of one curve to drag; [channel] 0 all, 1 red, 2 green, 3 blue */
@SuppressLint("ViewConstructor")
class CurvesView(context: Context, private val curves: FloatArray, private val onChange: () -> Unit) : View(context) {
    var channel = 0
        set(v) { field = v; invalidate() }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f.dp }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f.dp }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var drag = -1
    private val pad get() = 12f.dp

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.8f).toInt())
    }

    private fun sx(x: Float) = pad + x * (width - 2 * pad)
    private fun sy(y: Float) = height - pad - y * (height - 2 * pad)
    private fun color(c: Int) = when (c) { 1 -> 0xFFE5484D.toInt(); 2 -> 0xFF30A46C.toInt(); 3 -> 0xFF3E7BFA.toInt(); else -> Ui.TEXT }

    override fun onDraw(c: Canvas) {
        grid.color = Ui.DIVIDER
        for (k in 0..4) {
            val f = k / 4f
            c.drawLine(sx(f), sy(0f), sx(f), sy(1f), grid)
            c.drawLine(sx(0f), sy(f), sx(1f), sy(f), grid)
        }
        // the other curves faintly, the one being edited on top
        for (ch in (0..3).sortedBy { if (it == channel) 1 else 0 }) {
            path.reset()
            for (k in 0..64) {
                val x = k / 64f
                val y = Curves.eval(curves, ch, x)
                if (k == 0) path.moveTo(sx(x), sy(y)) else path.lineTo(sx(x), sy(y))
            }
            line.color = color(ch)
            line.alpha = if (ch == channel) 255 else 70
            c.drawPath(path, line)
        }
        val o = channel * 8
        for (i in 0 until 4) {
            dot.color = color(channel)
            c.drawCircle(sx(curves[o + i * 2]), sy(curves[o + i * 2 + 1]), 8f.dp, dot)
            dot.color = Ui.PANEL
            c.drawCircle(sx(curves[o + i * 2]), sy(curves[o + i * 2 + 1]), 4f.dp, dot)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val o = channel * 8
        val x = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        val y = ((height - pad - e.y) / (height - 2 * pad)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = -1
                var best = 40f.dp
                for (i in 0 until 4) {
                    val d = hypot(sx(curves[o + i * 2]) - e.x, sy(curves[o + i * 2 + 1]) - e.y)
                    if (d < best) { best = d; drag = i }
                }
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) {
                val lo = if (drag == 0) 0f else curves[o + (drag - 1) * 2] + 0.02f
                val hi = if (drag == 3) 1f else curves[o + (drag + 1) * 2] - 0.02f
                curves[o + drag * 2] = x.coerceIn(lo, hi.coerceAtLeast(lo))
                curves[o + drag * 2 + 1] = y
                invalidate()
                onChange()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = -1
        }
        return true
    }
}
