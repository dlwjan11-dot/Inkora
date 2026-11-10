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
     * [size] = brush diameter in pixels of the box, [fallback] = the paint color,
     * [wetAt] gives 0..1 = how wet the canvas still is at (x + ox, y + oy).
     * Returns the alpha after drying (for the wet map).
     *
     * The box may be a smaller copy of the stroke (a long stroke is dried at a reduced size and
     * scaled back up, so lifting the pen never makes the app wait). The paper's grain is put on
     * afterwards, at full size ([grainTile]).
     */
    fun dry(px: IntArray, w: Int, h: Int, ox: Int, oy: Int, strength: Float, bloom: Float, size: Float,
            fallback: Int, wetAt: ((Int, Int) -> Float)?): FloatArray {
        val n = w * h
        val a = FloatArray(n) { (px[it] ushr 24) / 255f }
        val st = strength.coerceIn(0f, 1f)
        val out = FloatArray(n)
        if (st <= 0f) { a.copyInto(out); return out }

        val amp = (size * 0.075f * st).coerceIn(0f, 16f)
        val r = (size * 0.14f).coerceIn(2f, 26f).roundToInt()

        // where the stroke is (blocks of 16 px, with room for the wobble and the blur): the rest is skipped
        val gw = (w + 15) shr 4; val gh = (h + 15) shr 4
        val raw = BooleanArray(gw * gh)
        for (y in 0 until h) { val row = y * w; val gy = (y shr 4) * gw
            for (x in 0 until w) if (a[row + x] > 0.004f) raw[gy + (x shr 4)] = true }
        val reach = ((amp + r * 2 + 15) / 16f).toInt() + 1
        val occ = BooleanArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) if (raw[gy * gw + gx]) {
            for (yy in max(0, gy - reach)..min(gh - 1, gy + reach)) for (xx in max(0, gx - reach)..min(gw - 1, gx + reach)) occ[yy * gw + xx] = true
        }

        // 1. the water's edge wanders: the stroke is read through a slow wave and a finer one
        val scale = max(6f, size * 0.42f)
        val a2 = if (amp < 0.5f) a else FloatArray(n).also { o ->
            for (y in 0 until h) { val gy = (y shr 4) * gw
                for (x in 0 until w) {
                    if (!occ[gy + (x shr 4)]) continue
                    val gxs = (x + ox) / scale; val gys = (y + oy) / scale
                    val dx = (noise(gxs, gys, 11) - 0.5f) * 2f * amp + (noise(gxs * 3.1f, gys * 3.1f, 31) - 0.5f) * amp * 0.7f
                    val dy = (noise(gxs, gys, 23) - 0.5f) * 2f * amp + (noise(gxs * 3.1f, gys * 3.1f, 37) - 0.5f) * amp * 0.7f
                    o[y * w + x] = sample(a, w, h, x + dx, y + dy)
                }
            }
        }

        // 2. the water pools: inside its edge the wash lies flat, and the edge itself is crisp
        val lo = 0.05f; val hi = 0.05f + 0.20f * (1.15f - 0.5f * st)
        val m = FloatArray(n)
        for (i in 0 until n) { val v = a2[i]; if (v > lo) { val t = ((v - lo) / (hi - lo)).coerceAtMost(1f); m[i] = t * t * (3 - 2 * t) } }
        // pigment is carried to the edge as it dries: a darker line just inside it
        val rimR = (size * 0.045f).coerceIn(1.5f, 7f).roundToInt()
        val mb = blur(m, w, h, rimR)

        // 3. wet on wet: where the paper is still wet the stroke spreads out softly
        var wide: FloatArray? = null
        var wet: FloatArray? = null
        if (wetAt != null && bloom > 0f) {
            val wt = FloatArray(n)
            var any = false
            for (y in 0 until h) { val gy = (y shr 4) * gw
                for (x in 0 until w) {
                    if (!occ[gy + (x shr 4)]) continue
                    val v = wetAt(x + ox, y + oy)
                    wt[y * w + x] = v
                    if (v > 0.01f) any = true
                } }
            if (any) { wet = wt; wide = blur(a2, w, h, (r * 4).coerceAtMost(64)) }
        }

        val fr = (fallback shr 16) and 255; val fg = (fallback shr 8) and 255; val fb = fallback and 255
        val s2 = scale * 1.7f; val s3 = max(3f, size * 0.11f)
        for (y in 0 until h) { val gy = (y shr 4) * gw
            for (x in 0 until w) {
                val i = y * w + x
                if (!occ[gy + (x shr 4)]) { if (a[i] == 0f) continue }
                val wv = (wet?.get(i) ?: 0f) * bloom
                var mask = m[i]
                var body = a2[i]
                val wd = wide
                if (wd != null && wv > 0f) {
                    // the bleed creeps out unevenly, in soft blooms
                    val bl = (wd[i] * (0.55f + 0.9f * noise((x + ox) / 9f, (y + oy) / 9f, 41)) * 1.3f).coerceAtMost(1f)
                    mask = mask + (max(mask, bl) - mask) * wv
                    body = body + (max(body, bl * 0.7f) - body) * wv
                }
                if (mask <= 0.003f && a2[i] <= 0.003f) { px[i] = 0; continue }
                // a flat wash: how dark it is follows the pressure, but gently
                val level = 0.42f + 0.58f * body
                // uneven: pigment settles more in some places (large blots, and smaller clouds)
                val blot = 1f + st * ((noise((x + ox) / s2, (y + oy) / s2, 77) - 0.5f) * 0.55f + (noise((x + ox) / s3, (y + oy) / s3, 53) - 0.5f) * 0.24f)
                val rim = (m[i] - mb[i]).coerceAtLeast(0f)
                var f = mask * level * (1f - 0.34f * st) * blot + rim * 2.1f * st * (0.45f + 0.55f * level) * (1f - 0.75f * wv)
                // st below 1 keeps some of the stroke as it was painted
                f = a2[i] + (f - a2[i]) * st
                f = f.coerceIn(0f, 1f)
                out[i] = f
                if (f <= 0.003f) { px[i] = 0; continue }
                val c = px[i]
                val hasColor = (c ushr 24) > 8
                var cr = if (hasColor) (c shr 16) and 255 else fr
                var cg = if (hasColor) (c shr 8) and 255 else fg
                var cb = if (hasColor) c and 255 else fb
                // the rim is a little deeper in tone
                val dk = 1f - 0.26f * st * (rim * 2.6f).coerceAtMost(1f)
                cr = (cr * dk).roundToInt(); cg = (cg * dk).roundToInt(); cb = (cb * dk).roundToInt()
                px[i] = ((f * 255).roundToInt().coerceIn(0, 255) shl 24) or (cr shl 16) or (cg shl 8) or cb
            } }
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

    /**
     * Fine paper grain as a square tile of [GRAIN_TILE] pixels that repeats without a seam
     * (0 = smooth paper, 1 = a dip where pigment settles).
     */
    fun grainTile(): FloatArray {
        val t = GRAIN_TILE
        return FloatArray(t * t) { i ->
            val x = i % t; val y = i / t
            pnoise(x / 2.2f, y / 2.2f, 5, 140) * 0.6f + pnoise(x / 7f, y / 7f, 9, 44) * 0.4f
        }
    }

    /** 308 = 140 × 2.2 = 44 × 7: both grain sizes fit a whole number of times */
    const val GRAIN_TILE = 308

    /** [noise] that repeats every [period] cells */
    private fun pnoise(x: Float, y: Float, s: Int, period: Int): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy)
        val xa = Math.floorMod(x0, period); val xb = Math.floorMod(x0 + 1, period)
        val ya = Math.floorMod(y0, period); val yb = Math.floorMod(y0 + 1, period)
        val a = hash(xa, ya, s); val b = hash(xb, ya, s)
        val c = hash(xa, yb, s); val d = hash(xb, yb, s)
        return (a * (1 - ux) + b * ux) * (1 - uy) + (c * (1 - ux) + d * ux) * uy
    }
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

    /** like [paint], from a box [bw] × [bh] that covers [cw] × [ch] canvas pixels at (ox, oy) */
    fun paintScaled(ox: Int, oy: Int, cw: Int, ch: Int, bw: Int, bh: Int, alpha: FloatArray, now: Long) {
        val sx = cw / bw.toFloat(); val sy = ch / bh.toFloat()
        for (y in 0 until bh) for (x in 0 until bw) {
            if (alpha[y * bw + x] < 0.05f) continue
            val gx = ((ox + (x * sx).toInt()) / 4).coerceIn(0, gw - 1); val gy = ((oy + (y * sy).toInt()) / 4).coerceIn(0, gh - 1)
            t[gy * gw + gx] = now
            // a box pixel may cover more than one cell of the map
            if (sx > 4f && gx + 1 < gw) t[gy * gw + gx + 1] = now
            if (sy > 4f && gy + 1 < gh) t[(gy + 1) * gw + gx] = now
        }
    }

    companion object { const val DRY_MS = 30_000L }
}
