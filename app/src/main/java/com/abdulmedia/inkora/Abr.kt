package com.abdulmedia.inkora

import kotlin.math.max
import kotlin.math.min

/**
 * Reads the brush shapes in a Photoshop brush file (.abr).
 *
 * Only the shape of each brush comes across (the grey picture that says where paint goes) and,
 * for the old files, the spacing. The rest of Photoshop's settings (scatter, texture, dynamics)
 * live in a descriptor format of their own and are not read: the brushes get Inkora's defaults.
 *
 * Old files (versions 1 and 2) list the brushes one after another. New files (6 and later) keep
 * the shapes in a block tagged "samp".
 */
object Abr {
    class Shape(val name: String?, val w: Int, val h: Int, val alpha: ByteArray, val spacing: Float?)

    private class In(val b: ByteArray, var p: Int = 0) {
        val left get() = b.size - p
        fun u8(): Int { need(1); return b[p++].toInt() and 0xFF }
        fun u16(): Int = (u8() shl 8) or u8()
        fun s16(): Int = u16().toShort().toInt()
        fun s32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun skip(n: Int) { need(n); p += n }
        fun bytes(n: Int): ByteArray { need(n); return b.copyOfRange(p, p + n).also { p += n } }
        fun need(n: Int) { if (n < 0 || p + n > b.size) throw IllegalStateException("short file") }
    }

    /** the version a brush file says it is, or -1 */
    fun version(b: ByteArray): Int = if (b.size < 4) -1 else ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)

    fun looksLikeAbr(b: ByteArray): Boolean = version(b) in setOf(1, 2, 6, 7, 10)

    /** all the shapes that could be read; a broken brush in the middle ends the list, keeping the ones before it */
    fun read(bytes: ByteArray): List<Shape> {
        val out = ArrayList<Shape>()
        runCatching {
            val f = In(bytes)
            when (val v = f.u16()) {
                1, 2 -> readOld(f, v, out)
                6, 7, 10 -> readNew(f, out)
            }
        }
        return out
    }

    private fun readOld(f: In, v: Int, out: MutableList<Shape>) {
        val count = f.u16()
        repeat(count) {
            val type = f.u16()
            val size = f.s32()
            val next = f.p + size
            if (type == 2) {
                f.s32() // misc
                val spacing = f.u16()
                val name = if (v == 2) ucs2(f) else null
                f.u8() // antialias
                f.skip(8) // short bounds
                val top = f.s32(); val left = f.s32(); val bottom = f.s32(); val right = f.s32()
                val depth = f.s16()
                val compressed = f.u8() != 0
                pixels(f, right - left, bottom - top, depth, compressed)?.let {
                    out.add(Shape(name, right - left, bottom - top, it, if (spacing in 1..999) spacing / 100f else null))
                }
            }
            // computed (round) brushes are only numbers: Inkora has its own round brushes
            f.p = next
        }
    }

    private fun readNew(f: In, out: MutableList<Shape>) {
        val sub = f.u16()
        // the blocks: "8BIM", a tag, a length
        while (f.left >= 12) {
            val sig = String(f.bytes(4), Charsets.ISO_8859_1)
            if (sig != "8BIM") return
            val tag = String(f.bytes(4), Charsets.ISO_8859_1)
            val len = f.s32()
            val end = f.p + len
            if (tag == "samp") {
                while (f.p < end - 4) {
                    var size = f.s32()
                    while (size % 4 != 0) size++
                    val next = f.p + size
                    f.skip(if (sub == 1) 47 else 301)
                    val top = f.s32(); val left = f.s32(); val bottom = f.s32(); val right = f.s32()
                    val depth = f.s16()
                    val compressed = f.u8() != 0
                    pixels(f, right - left, bottom - top, depth, compressed)?.let { out.add(Shape(null, right - left, bottom - top, it, null)) }
                    f.p = next
                }
                return
            }
            f.p = end + (len and 1) // blocks are padded to an even length in some files
            if (f.p > f.b.size) return
        }
    }

    private fun ucs2(f: In): String? {
        val n = f.s32()
        if (n <= 0 || n > 512) return null
        val raw = f.bytes(n * 2)
        return String(raw, Charsets.UTF_16BE).trimEnd('\u0000').trim().ifBlank { null }
    }

    /** reads a w × h picture, raw or packed (PackBits per row); 16-bit pictures keep their top byte */
    private fun pixels(f: In, w: Int, h: Int, depth: Int, compressed: Boolean): ByteArray? {
        if (w <= 0 || h <= 0 || w > 5000 || h > 5000) return null
        val bpp = max(1, depth / 8)
        val rowBytes = w * bpp
        val raw = ByteArray(rowBytes * h)
        if (!compressed) {
            System.arraycopy(f.bytes(raw.size), 0, raw, 0, raw.size)
        } else {
            val counts = IntArray(h) { f.u16() }
            var o = 0
            for (y in 0 until h) {
                val rowEnd = f.p + counts[y]
                val rowStart = o
                while (f.p < rowEnd && o - rowStart < rowBytes) {
                    val n = f.u8().toByte().toInt()
                    when {
                        n >= 0 -> { val k = min(n + 1, rowBytes - (o - rowStart)); System.arraycopy(f.bytes(k), 0, raw, o, k); o += k; if (k < n + 1) f.skip(n + 1 - k) }
                        n != -128 -> { val v = f.u8().toByte(); val k = min(-n + 1, rowBytes - (o - rowStart)); java.util.Arrays.fill(raw, o, o + k, v); o += k }
                    }
                }
                f.p = rowEnd
                o = rowStart + rowBytes
            }
        }
        if (bpp == 1) return raw
        return ByteArray(w * h) { raw[it * bpp] }
    }

    /** a smaller copy (averaging), at most [maxSide] on its longer side */
    fun shrink(s: Shape, maxSide: Int): Shape {
        val k = max(s.w, s.h).toFloat() / maxSide
        if (k <= 1f) return s
        val nw = max(1, (s.w / k).toInt()); val nh = max(1, (s.h / k).toInt())
        val out = ByteArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            val x0 = (x * s.w / nw); val x1 = max(x0 + 1, (x + 1) * s.w / nw)
            val y0 = (y * s.h / nh); val y1 = max(y0 + 1, (y + 1) * s.h / nh)
            var sum = 0; var n = 0
            for (yy in y0 until min(y1, s.h)) for (xx in x0 until min(x1, s.w)) { sum += s.alpha[yy * s.w + xx].toInt() and 0xFF; n++ }
            out[y * nw + x] = (sum / max(1, n)).toByte()
        }
        return Shape(s.name, nw, nh, out, s.spacing)
    }
}
