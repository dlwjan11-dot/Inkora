package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** One adjustment = a title + slider definitions + how to compute the result. */
enum class AdjustKind(val label: String, val sliders: List<AdjustSlider>) {
    HSB("Hue / Saturation / Brightness", listOf(
        AdjustSlider("Hue", -180f, 180f, 0f, "°"),
        AdjustSlider("Saturation", 0f, 200f, 100f, "%"),
        AdjustSlider("Brightness", -100f, 100f, 0f, "%"))),
    BRIGHT_CONTRAST("Brightness / Contrast", listOf(
        AdjustSlider("Brightness", -100f, 100f, 0f, "%"),
        AdjustSlider("Contrast", -100f, 100f, 0f, "%"))),
    COLOR_BALANCE("Color Balance", listOf(
        AdjustSlider("Cyan ↔ Red", -100f, 100f, 0f, ""),
        AdjustSlider("Magenta ↔ Green", -100f, 100f, 0f, ""),
        AdjustSlider("Yellow ↔ Blue", -100f, 100f, 0f, ""))),
    BLUR("Gaussian Blur", listOf(AdjustSlider("Radius", 0f, 80f, 6f, " px"))),
    SHARPEN("Sharpen", listOf(AdjustSlider("Amount", 0f, 300f, 80f, "%"))),
    NOISE("Noise", listOf(AdjustSlider("Amount", 0f, 100f, 20f, "%"))),
    POSTERIZE("Posterize", listOf(AdjustSlider("Levels", 2f, 16f, 5f, ""))),
    GRADIENT_MAP("Gradient Map", listOf(
        AdjustSlider("Gradient", 0f, (GradientMaps.presets.size - 1).toFloat(), 1f, "", GradientMaps.presets.map { it.first }),
        AdjustSlider("Strength", 0f, 100f, 100f, "%"))),
    INVERT("Invert Colors", emptyList()),
    GRAYSCALE("Black & White", emptyList());
}

/** [names]: the slider picks one of these (shown instead of a number) */
class AdjustSlider(val name: String, val min: Float, val max: Float, val default: Float, val unit: String, val names: List<String>? = null)

/**
 * Gradient Map: the dark parts of the picture take the first color of a gradient, the light parts the
 * last, and everything between follows the gradient. "Your color" runs from black through the color
 * you paint with to white.
 */
object GradientMaps {
    /** set before an adjustment starts: the color in hand, for "Your color" */
    @Volatile var userColor = 0xFF2F6FE0.toInt()

    val presets: List<Pair<String, IntArray>> = listOf(
        "Your color" to intArrayOf(),
        "Sunset" to colors(0xFF1B0B33, 0xFF7A1F5C, 0xFFE0533D, 0xFFF6C35B, 0xFFFFF4D6),
        "Ocean" to colors(0xFF04111F, 0xFF0E4C7A, 0xFF3FA7C9, 0xFFCFF3F2),
        "Forest" to colors(0xFF0D1A0F, 0xFF2E5B2E, 0xFF9BBF5A, 0xFFF1F2C9),
        "Sepia" to colors(0xFF1E140A, 0xFF7A5230, 0xFFE8D3A9, 0xFFFFF8EA),
        "Moonlight" to colors(0xFF0A0F1E, 0xFF3B4A73, 0xFFA9B9D9, 0xFFF4F7FF),
        "Neon" to colors(0xFF0B0221, 0xFF5A0FC8, 0xFFFF2E97, 0xFFFFE66D),
        "Fire" to colors(0xFF000000, 0xFF5C0A00, 0xFFD23B00, 0xFFFF9F1C, 0xFFFFF3B0),
        "Duotone" to colors(0xFF1D2B64, 0xFFF8CDDA)
    )

    private fun colors(vararg c: Long) = IntArray(c.size) { c[it].toInt() }

    fun stops(i: Int): IntArray {
        val p = presets[i.coerceIn(0, presets.size - 1)].second
        return if (p.isNotEmpty()) p else intArrayOf(0xFF000000.toInt(), userColor or 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
    }

    /** 256 colors, from the darkest to the lightest */
    fun lut(i: Int): IntArray {
        val st = stops(i)
        return IntArray(256) { k ->
            val f = k / 255f * (st.size - 1)
            val a = f.toInt().coerceAtMost(st.size - 2).coerceAtLeast(0)
            val t = (f - a).coerceIn(0f, 1f)
            mix(st[a], st[(a + 1).coerceAtMost(st.size - 1)], t)
        }
    }

    fun mix(x: Int, y: Int, t: Float): Int {
        fun ch(sh: Int) = (((x shr sh) and 255) * (1 - t) + ((y shr sh) and 255) * t).roundToInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

object Adjust {

    /** Computes [kind] of [src] into a new bitmap. Safe on a background thread. */
    /** the adjustments that are only a color matrix: these can be shown at once, in one pass */
    fun matrixFor(kind: AdjustKind, v: FloatArray): ColorMatrix? = when (kind) {
        AdjustKind.HSB -> ColorMatrix().apply {
            postConcat(hue(v[0]))
            postConcat(ColorMatrix().apply { setSaturation(v[1] / 100f) })
            val b = v[2] / 100f * 255f
            postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f)))
        }
        AdjustKind.BRIGHT_CONTRAST -> {
            val c = 1f + v[1] / 100f * (if (v[1] > 0) 2f else 1f)
            val b = v[0] / 100f * 255f
            val t = 128f * (1f - c) + b
            ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
        }
        AdjustKind.COLOR_BALANCE -> {
            val r = v[0] / 100f * 60f
            val g = v[1] / 100f * 60f
            val bl = v[2] / 100f * 60f
            ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, r, 0f, 1f, 0f, 0f, g, 0f, 0f, 1f, 0f, bl, 0f, 0f, 0f, 1f, 0f))
        }
        AdjustKind.INVERT -> ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
        AdjustKind.GRAYSCALE -> ColorMatrix().apply { setSaturation(0f) }
        else -> null
    }

    fun apply(kind: AdjustKind, src: Bitmap, v: FloatArray): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val m = matrixFor(kind, v)
        if (m != null) { colorMatrix(src, out, m); return out }
        when (kind) {
            AdjustKind.BLUR -> pixels(src, out) { px, w, h -> blur(px, w, h, v[0]) }
            AdjustKind.SHARPEN -> pixels(src, out) { px, w, h ->
                val orig = px.copyOf()
                blur(px, w, h, 2.5f)
                val a = v[0] / 100f
                for (i in px.indices) {
                    val o = orig[i]
                    val bl = px[i]
                    px[i] = (o and 0xFF000000.toInt()) or
                        (ch((o shr 16) and 0xFF, (bl shr 16) and 0xFF, a) shl 16) or
                        (ch((o shr 8) and 0xFF, (bl shr 8) and 0xFF, a) shl 8) or
                        ch(o and 0xFF, bl and 0xFF, a)
                }
            }
            AdjustKind.NOISE -> pixels(src, out) { px, _, _ ->
                val rnd = Random(99)
                val amt = v[0] / 100f * 90f
                for (i in px.indices) {
                    val c = px[i]
                    if (c ushr 24 == 0) continue
                    val n = ((rnd.nextFloat() - 0.5f) * 2f * amt).roundToInt()
                    px[i] = (c and 0xFF000000.toInt()) or
                        (clamp(((c shr 16) and 0xFF) + n) shl 16) or
                        (clamp(((c shr 8) and 0xFF) + n) shl 8) or
                        clamp((c and 0xFF) + n)
                }
            }
            AdjustKind.GRADIENT_MAP -> pixels(src, out) { px, _, _ ->
                val lut = GradientMaps.lut(v[0].roundToInt())
                val k = (v[1] / 100f).coerceIn(0f, 1f)
                for (i in px.indices) {
                    val c = px[i]
                    val a = c ushr 24
                    if (a == 0) continue
                    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                    val l = (r * 77 + g * 150 + b * 29) shr 8
                    val m = lut[l]
                    px[i] = (a shl 24) or (if (k >= 1f) m else GradientMaps.mix(c, m, k))
                }
            }
            AdjustKind.POSTERIZE -> pixels(src, out) { px, _, _ ->
                val levels = v[0].roundToInt().coerceIn(2, 32)
                val step = 255f / (levels - 1)
                fun q(x: Int) = ((x / step).roundToInt() * step).roundToInt().coerceIn(0, 255)
                for (i in px.indices) {
                    val c = px[i]
                    px[i] = (c and 0xFF000000.toInt()) or (q((c shr 16) and 0xFF) shl 16) or (q((c shr 8) and 0xFF) shl 8) or q(c and 0xFF)
                }
            }
            else -> {}
        }
        return out
    }

    private fun ch(o: Int, blur: Int, a: Float) = clamp((o + (o - blur) * a).roundToInt())
    private fun clamp(x: Int) = if (x < 0) 0 else if (x > 255) 255 else x

    private fun colorMatrix(src: Bitmap, out: Bitmap, cm: ColorMatrix) {
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
    }

    private fun pixels(src: Bitmap, out: Bitmap, f: (IntArray, Int, Int) -> Unit) {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        f(px, w, h)
        out.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** Luminance-preserving hue rotation. */
    private fun hue(deg: Float): ColorMatrix {
        val a = Math.toRadians(deg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        return ColorMatrix(floatArrayOf(
            lr + c * (1 - lr) + s * (-lr), lg + c * (-lg) + s * (-lg), lb + c * (-lb) + s * (1 - lb), 0f, 0f,
            lr + c * (-lr) + s * 0.143f, lg + c * (1 - lg) + s * 0.140f, lb + c * (-lb) + s * (-0.283f), 0f, 0f,
            lr + c * (-lr) + s * (-(1 - lr)), lg + c * (-lg) + s * lg, lb + c * (1 - lb) + s * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
    }

    /**
     * Gaussian-like blur: three box blurs, on premultiplied colors so
     * transparent edges don't turn dark.
     */
    fun blur(px: IntArray, w: Int, h: Int, radius: Float) {
        if (radius < 0.5f) return
        val n = w * h
        val a = IntArray(n); val r = IntArray(n); val g = IntArray(n); val b = IntArray(n)
        for (i in 0 until n) {
            val c = px[i]
            val al = c ushr 24
            a[i] = al
            r[i] = ((c shr 16) and 0xFF) * al / 255
            g[i] = ((c shr 8) and 0xFF) * al / 255
            b[i] = (c and 0xFF) * al / 255
        }
        // box sizes for 3 passes approximating a gaussian (sigma = radius / 2)
        val sigma = radius / 2f
        val wIdeal = Math.sqrt((12.0 * sigma * sigma / 3) + 1)
        var wl = wIdeal.toInt(); if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12 * sigma * sigma - 3 * wl * wl - 4 * 3 * wl - 3 * 3) / (-4 * wl - 4)
        val m = mIdeal.roundToInt()
        val tmp = IntArray(n)
        for (pass in 0 until 3) {
            val box = ((if (pass < m) wl else wu) - 1) / 2
            if (box < 1) continue
            for (chArr in arrayOf(a, r, g, b)) {
                boxH(chArr, tmp, w, h, box)
                boxV(tmp, chArr, w, h, box)
            }
        }
        for (i in 0 until n) {
            val al = a[i].coerceIn(0, 255)
            if (al == 0) { px[i] = 0; continue }
            px[i] = (al shl 24) or
                ((r[i] * 255 / al).coerceIn(0, 255) shl 16) or
                ((g[i] * 255 / al).coerceIn(0, 255) shl 8) or
                (b[i] * 255 / al).coerceIn(0, 255)
        }
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (k in -r..r) sum += src[row + min(max(k, 0), w - 1)]
            for (x in 0 until w) {
                dst[row + x] = sum / div
                val add = src[row + min(x + r + 1, w - 1)]
                val sub = src[row + max(x - r, 0)]
                sum += add - sub
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sum = 0
            for (k in -r..r) sum += src[min(max(k, 0), h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / div
                val add = src[min(y + r + 1, h - 1) * w + x]
                val sub = src[max(y - r, 0) * w + x]
                sum += add - sub
            }
        }
    }
}
