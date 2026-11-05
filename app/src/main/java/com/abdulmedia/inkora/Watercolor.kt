package com.abdulmedia.inkora

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How wet paint dries, applied to a finished stroke (non-premultiplied ARGB pixels):
 *  - the pigment runs to the edges, so edges get darker and the middle lighter
 *  - the edge wanders a little, like water does on paper
 *  - pigment settles into the paper's grain (granulation)
 *  - on paint that is still wet, the stroke bleeds out softly (wet on wet)
 */
object Watercolor {

    /**
     * [px] = the stroke's pixels in a w × h box whose top-left is (ox, oy) on the canvas.
     * [strength] 0..1 = how watery, [bloom] 0..1 = how much it bleeds on wet paint,
     * [size] = brush diameter in canvas pixels, [fallback] = the paint color,
     * [wetAt] gives 0..1 = how wet the canvas still is at a canvas pixel.
     * Returns the alpha after drying (for the wet map).
     */
    fun dry(px: IntArray, w: Int, h: Int, ox: Int, oy: Int, strength: Float, bloom: Float, size: Float,
            fallback: Int, wetAt: ((Int, Int) -> Float)?): FloatArray {
        val n = w * h
        val a = FloatArray(n) { (px[it] ushr 24) / 255f }
        val st = strength.coerceIn(0f, 1f)

        // 1. a wandering edge: the stroke is read through a slow wavy displacement
        val amp = (size * 0.07f * st).coerceIn(0f, 14f)
        val scale = max(6f, size * 0.4f)
        val a2 = if (amp < 0.5f) a else FloatArray(n).also { out ->
            for (y in 0 until h) for (x in 0 until w) {
                val gx = (x + ox) / scale; val gy = (y + oy) / scale
                val dx = (noise(gx, gy, 11) - 0.5f) * 2f * amp
                val dy = (noise(gx, gy, 23) - 0.5f) * 2f * amp
                out[y * w + x] = sample(a, w, h, x + dx, y + dy)
            }
        }

        // 2. pigment pools at the edges: the stroke minus a blurred copy of itself
        val r = (size * 0.14f).coerceIn(2f, 26f).roundToInt()
        val b = blur(a2, w, h, r)

        // 3. wet on wet: where the canvas is still wet the stroke spreads out softly
        var wide: FloatArray? = null
        val wet = FloatArray(n)
        if (wetAt != null && bloom > 0f) {
            var any = false
            for (y in 0 until h) for (x in 0 until w) {
                val v = wetAt(x + ox, y + oy)
                wet[y * w + x] = v
                if (v > 0.01f) any = true
            }
            if (any) wide = blur(a2, w, h, (r * 4).coerceAtMost(64))
        }

        val out = FloatArray(n)
        val fr = (fallback shr 16) and 255; val fg = (fallback shr 8) and 255; val fb = fallback and 255
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val wv = wet[i] * bloom
            var al = a2[i]
            val wd = wide
            if (wd != null && wv > 0f) {
                // the bleed creeps out unevenly, in soft blooms
                val bl = wd[i] * (0.55f + 0.9f * noise((x + ox) / 9f, (y + oy) / 9f, 41)) * 1.2f
                al = al + (max(al, bl.coerceAtMost(1f)) - al) * wv
            }
            if (al <= 0.003f) { px[i] = 0; continue }
            val edge = (a2[i] - b[i]).coerceIn(0f, 1f)
            // lighter middle, darker rim (less of a rim where the paint spread on wet paper)
            // an uneven wash: pigment settles a little more in some places than others
            val blot = 0.82f + 0.36f * noise((x + ox) / (scale * 1.6f), (y + oy) / (scale * 1.6f), 77)
            var f = al * (1f - 0.36f * st) * (1f + (blot - 1f) * st) + edge * 2.6f * st * (1f - 0.75f * wv)
            // granulation: pigment sitting in the paper's grain, most visible in thin washes
            val g = grain(x + ox, y + oy)
            f *= 1f - st * (0.12f + 0.25f * (1f - al)) * g
            f = f.coerceIn(0f, 1f)
            out[i] = f
            val c = px[i]
            val hasColor = (c ushr 24) > 8
            var cr = if (hasColor) (c shr 16) and 255 else fr
            var cg = if (hasColor) (c shr 8) and 255 else fg
            var cb = if (hasColor) c and 255 else fb
            // the rim is a little deeper in tone
            val dk = 1f - 0.22f * st * edge.coerceAtMost(0.6f) / 0.6f
            cr = (cr * dk).roundToInt(); cg = (cg * dk).roundToInt(); cb = (cb * dk).roundToInt()
            px[i] = ((f * 255).roundToInt().coerceIn(0, 255) shl 24) or (cr shl 16) or (cg shl 8) or cb
        }
        return out
    }

    /** two box blurs (close to a gaussian), radius [r] */
    fun blur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r < 1) return src.copyOf()
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        boxH(src, tmp, w, h, r); boxV(tmp, out, w, h, r)
        boxH(out, tmp, w, h, r); boxV(tmp, out, w, h, r)
        return out
    }

    private fun boxH(s: FloatArray, d: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (k in -r..r) acc += s[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                d[row + x] = acc * inv
                acc += s[row + min(w - 1, x + r + 1)] - s[row + max(0, x - r)]
            }
        }
    }

    private fun boxV(s: FloatArray, d: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        for (x in 0 until w) {
            var acc = 0f
            for (k in -r..r) acc += s[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                d[y * w + x] = acc * inv
                acc += s[min(h - 1, y + r + 1) * w + x] - s[max(0, y - r) * w + x]
            }
        }
    }

    private fun sample(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        fun at(xx: Int, yy: Int) = if (xx in 0 until w && yy in 0 until h) a[yy * w + xx] else 0f
        return (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) + (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy
    }

    private fun hash(x: Int, y: Int, s: Int): Float {
        var h = x * 374761393 + y * 668265263 + s * 2147483647.toInt()
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    /** smooth value noise 0..1 */
    fun noise(x: Float, y: Float, s: Int): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy)
        val a = hash(x0, y0, s); val b = hash(x0 + 1, y0, s)
        val c = hash(x0, y0 + 1, s); val d = hash(x0 + 1, y0 + 1, s)
        return (a * (1 - ux) + b * ux) * (1 - uy) + (c * (1 - ux) + d * ux) * uy
    }

    /** fine paper grain, the same at every place of the canvas */
    private fun grain(x: Int, y: Int): Float =
        noise(x / 2.2f, y / 2.2f, 5) * 0.6f + noise(x / 7f, y / 7f, 9) * 0.4f
}

/**
 * Where a layer's paint is still wet: the time each spot was last painted with a wet brush,
 * on a grid 4 × smaller than the canvas. Paint dries over [DRY_MS].
 */
class WetMap(val w: Int, val h: Int) {
    private val gw = (w + 3) / 4
    private val gh = (h + 3) / 4
    private val t = LongArray(gw * gh)

    fun wetness(x: Int, y: Int, now: Long): Float {
        val gx = (x / 4).coerceIn(0, gw - 1); val gy = (y / 4).coerceIn(0, gh - 1)
        val v = t[gy * gw + gx]
        if (v == 0L) return 0f
        return (1f - (now - v) / DRY_MS.toFloat()).coerceIn(0f, 1f)
    }

    fun paint(ox: Int, oy: Int, bw: Int, bh: Int, alpha: FloatArray, now: Long) {
        for (y in 0 until bh step 2) for (x in 0 until bw step 2) {
            if (alpha[y * bw + x] < 0.05f) continue
            val gx = ((x + ox) / 4).coerceIn(0, gw - 1); val gy = ((y + oy) / 4).coerceIn(0, gh - 1)
            t[gy * gw + gx] = now
        }
    }

    companion object { const val DRY_MS = 30_000L }
}
