package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Photoshop files (.psd): Inkora's layers with their names, opacity, blend modes, visibility,
 * alpha lock and clipping masks, and a flattened image for apps that only read that.
 * 8-bit RGB, RLE (PackBits) compressed, as Photoshop writes them.
 */
object PsdIO {

    private val BLEND_KEYS = mapOf(
        LayerBlend.NORMAL to "norm", LayerBlend.MULTIPLY to "mul ", LayerBlend.DARKEN to "dark",
        LayerBlend.COLOR_BURN to "idiv", LayerBlend.LIGHTEN to "lite", LayerBlend.SCREEN to "scrn",
        LayerBlend.COLOR_DODGE to "div ", LayerBlend.ADD to "lddg", LayerBlend.OVERLAY to "over",
        LayerBlend.SOFT_LIGHT to "sLit", LayerBlend.HARD_LIGHT to "hLit", LayerBlend.DIFFERENCE to "diff",
        LayerBlend.EXCLUSION to "smud", LayerBlend.HUE to "hue ", LayerBlend.SATURATION to "sat ",
        LayerBlend.COLOR to "colr", LayerBlend.LUMINOSITY to "lum "
    )

    // =========================================================
    // writing
    // =========================================================

    /** PackBits: runs of 2..128 equal bytes, or literal stretches of 1..128 */
    fun packBits(src: ByteArray, off: Int, len: Int, out: java.io.ByteArrayOutputStream) {
        var i = 0
        while (i < len) {
            var run = 1
            while (i + run < len && run < 128 && src[off + i + run] == src[off + i]) run++
            if (run >= 2) {
                out.write(257 - run) // -(run-1) as a byte
                out.write(src[off + i].toInt())
                i += run
            } else {
                val start = i
                var lit = 0
                while (i < len && lit < 128) {
                    if (i + 1 < len && src[off + i] == src[off + i + 1] && i + 2 < len && src[off + i] == src[off + i + 2]) break
                    i++; lit++
                }
                out.write(lit - 1)
                out.write(src, off + start, lit)
            }
        }
    }

    /** one channel of [w]×[h] as RLE: the rows' byte counts, then the rows */
    private fun rleChannel(plane: ByteArray, w: Int, h: Int): Pair<ShortArray, ByteArray> {
        val counts = ShortArray(h)
        val data = java.io.ByteArrayOutputStream(plane.size / 3 + 64)
        for (y in 0 until h) {
            val before = data.size()
            packBits(plane, y * w, w, data)
            counts[y] = (data.size() - before).toShort()
        }
        return counts to data.toByteArray()
    }

    /** the four channels (alpha, red, green, blue) of [bmp] inside [r], unpremultiplied */
    private fun planes(bmp: Bitmap, l: Int, t: Int, w: Int, h: Int): Array<ByteArray> {
        val px = IntArray(w)
        val a = ByteArray(w * h); val r = ByteArray(w * h); val g = ByteArray(w * h); val b = ByteArray(w * h)
        for (y in 0 until h) {
            bmp.getPixels(px, 0, w, l, t + y, w, 1)
            val o = y * w
            for (x in 0 until w) {
                val c = px[x]
                a[o + x] = (c ushr 24).toByte()
                r[o + x] = (c shr 16).toByte()
                g[o + x] = (c shr 8).toByte()
                b[o + x] = c.toByte()
            }
        }
        return arrayOf(a, r, g, b)
    }

    private fun pascalName(name: String): ByteArray {
        val ascii = name.map { if (it.code in 32..126) it.code.toByte() else '_'.code.toByte() }.take(255).toByteArray()
        val total = ((1 + ascii.size + 3) / 4) * 4
        val out = ByteArray(total)
        out[0] = ascii.size.toByte()
        System.arraycopy(ascii, 0, out, 1, ascii.size)
        return out
    }

    /** Writes [doc] as a layered PSD. [tmpDir] holds the pixel data while the file is put together. */
    fun write(doc: Document, out: OutputStream, tmpDir: File) {
        val w = doc.width
        val h = doc.height
        // the layers, bottom first; a visible background becomes a layer of its own
        // section: 0 = a layer, 1 = an open group, 2 = a closed group, 3 = the end of a group
        class L(val bmp: Bitmap?, val name: String, val opacity: Float, val blend: LayerBlend, val visible: Boolean,
                val clip: Boolean, val lock: Boolean, val owned: Boolean, val section: Int = 0, val pass: Boolean = false)
        val layers = ArrayList<L>()
        if (doc.backgroundVisible) {
            val bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bg.eraseColor(doc.backgroundColor or 0xFF000000.toInt())
            layers.add(L(bg, "Background", 1f, LayerBlend.NORMAL, true, false, false, true))
        }
        // groups as Photoshop keeps them, bottom first: the end marker, the layers, then the group itself
        doc.normalizeGroups()
        doc.layers.forEachIndexed { i, l ->
            val g = doc.groupOf(l)
            if (g != null && (i == 0 || doc.layers[i - 1].groupId != g.id))
                layers.add(L(null, "</Layer group>", 1f, LayerBlend.NORMAL, true, false, false, false, 3))
            if (!l.isLayerMask || !(i > 0 && doc.hasMask(i - 1))) {
                // a layer with a mask goes out with the mask applied (the mask itself is not a Photoshop layer here)
                val masked = if (doc.hasMask(i)) l.bitmap.copy(Bitmap.Config.ARGB_8888, true).also {
                    Canvas(it).drawBitmap(doc.layers[i + 1].bitmap, 0f, 0f, Paint().apply { blendMode = android.graphics.BlendMode.DST_IN })
                } else null
                layers.add(L(masked ?: l.bitmap, l.name, l.opacity, l.blend, l.visible && !l.shadow, l.isClippingMask, l.alphaLock, masked != null))
            }
            if (g != null && (i == doc.layers.size - 1 || doc.layers[i + 1].groupId != g.id))
                layers.add(L(null, g.name, g.opacity, g.blend, g.visible, false, false, false,
                    if (g.collapsed) 2 else 1, pass = !g.isolated))
        }

        val tmp = File.createTempFile("psd", ".bin", tmpDir)
        try {
            // 1) every layer's channels, compressed, into the temporary file; remember the sizes
            class Rec(val top: Int, val left: Int, val bottom: Int, val right: Int, val lens: IntArray)
            val recs = ArrayList<Rec>()
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { d ->
                for (l in layers) {
                    val r = l.bmp?.let { Selections.contentBounds(it) }?.takeIf { !it.isEmpty && it.intersect(0, 0, w, h) }
                    if (r == null) {
                        // an empty layer: no pixels, four empty channels
                        for (c in 0 until 4) d.writeShort(0)
                        recs.add(Rec(0, 0, 0, 0, IntArray(4) { 2 }))
                        continue
                    }
                    val cw = r.width(); val ch = r.height()
                    val ps = planes(l.bmp!!, r.left, r.top, cw, ch)
                    val lens = IntArray(4)
                    for (c in 0 until 4) {
                        val (counts, data) = rleChannel(ps[c], cw, ch)
                        d.writeShort(1)
                        for (k in counts) d.writeShort(k.toInt())
                        d.write(data)
                        lens[c] = 2 + counts.size * 2 + data.size
                    }
                    recs.add(Rec(r.top, r.left, r.bottom, r.right, lens))
                }
            }

            // 2) the records
            val recBytes = java.io.ByteArrayOutputStream()
            DataOutputStream(recBytes).use { d ->
                layers.forEachIndexed { i, l ->
                    val rc = recs[i]
                    d.writeInt(rc.top); d.writeInt(rc.left); d.writeInt(rc.bottom); d.writeInt(rc.right)
                    d.writeShort(4)
                    val ids = intArrayOf(-1, 0, 1, 2)
                    for (c in 0 until 4) { d.writeShort(ids[c]); d.writeInt(rc.lens[c]) }
                    d.writeBytes("8BIM")
                    val key = if (l.pass) "pass" else BLEND_KEYS[l.blend] ?: "norm"
                    d.writeBytes(key)
                    d.writeByte((l.opacity * 255).toInt().coerceIn(0, 255))
                    d.writeByte(if (l.clip) 1 else 0)
                    var flags = 0
                    if (l.lock) flags = flags or 1
                    if (!l.visible) flags = flags or 2
                    d.writeByte(flags)
                    d.writeByte(0)
                    // extra data: no mask, no blending ranges, the name, and the name in Unicode
                    val name = pascalName(l.name)
                    val uni = l.name.toByteArray(Charsets.UTF_16BE)
                    val luniLen = 4 + uni.size + (if ((4 + uni.size) % 2 == 1) 1 else 0)
                    val luniPadded = ((luniLen + 3) / 4) * 4
                    d.writeInt(4 + 4 + name.size + 12 + luniPadded + (if (l.section != 0) 24 else 0))
                    d.writeInt(0)
                    d.writeInt(0)
                    d.write(name)
                    d.writeBytes("8BIM"); d.writeBytes("luni"); d.writeInt(luniPadded)
                    d.writeInt(l.name.length); d.write(uni)
                    repeat(luniPadded - 4 - uni.size) { d.writeByte(0) }
                    if (l.section != 0) {
                        d.writeBytes("8BIM"); d.writeBytes("lsct"); d.writeInt(12)
                        d.writeInt(l.section); d.writeBytes("8BIM"); d.writeBytes(key)
                    }
                }
            }
            val dataLen = tmp.length()
            var layerInfoLen = 2 + recBytes.size() + dataLen
            val layerPad = if (layerInfoLen % 2 == 1L) 1 else 0
            layerInfoLen += layerPad

            // 3) the file
            val d = DataOutputStream(BufferedOutputStream(out, 1 shl 16))
            d.writeBytes("8BPS"); d.writeShort(1); repeat(6) { d.writeByte(0) }
            d.writeShort(3); d.writeInt(h); d.writeInt(w); d.writeShort(8); d.writeShort(3)
            d.writeInt(0) // color mode data
            d.writeInt(0) // image resources
            d.writeInt((4 + layerInfoLen + 4).toInt())
            d.writeInt(layerInfoLen.toInt())
            d.writeShort(layers.size)
            recBytes.writeTo(d)
            tmp.inputStream().use { it.copyTo(d, 1 shl 16) }
            if (layerPad == 1) d.writeByte(0)
            d.writeInt(0) // global layer mask info
            // the flattened image, for apps that don't read layers
            val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val fc = Canvas(flat)
            fc.drawColor(if (doc.backgroundVisible) doc.backgroundColor or 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            doc.draw(fc, false, withBackground = false, screen = false)
            d.writeShort(1)
            val ps = planes(flat, 0, 0, w, h)
            flat.recycle()
            val packed = (1..3).map { rleChannel(ps[it], w, h) }
            for ((counts, _) in packed) for (k in counts) d.writeShort(k.toInt())
            for ((_, data) in packed) d.write(data)
            d.flush()
        } finally {
            tmp.delete()
            layers.filter { it.owned }.forEach { it.bmp?.recycle() }
        }
    }

    // =========================================================
    // reading
    // =========================================================

    class Result(val doc: Document, val note: String?)

    private class In(stream: InputStream) {
        val d = DataInputStream(java.io.BufferedInputStream(stream, 1 shl 16))
        var pos = 0L
        fun u8(): Int { pos += 1; return d.readUnsignedByte() }
        fun u16(): Int { pos += 2; return d.readUnsignedShort() }
        fun s16(): Int { pos += 2; return d.readShort().toInt() }
        fun s32(): Int { pos += 4; return d.readInt() }
        fun u32(): Long { pos += 4; return d.readInt().toLong() and 0xFFFFFFFFL }
        fun key(): String { val b = ByteArray(4); d.readFully(b); pos += 4; return String(b, Charsets.ISO_8859_1) }
        fun bytes(n: Int): ByteArray { val b = ByteArray(n); d.readFully(b); pos += n; return b }
        fun skip(n: Long) { var left = n; while (left > 0) { val k = d.skip(left); if (k <= 0) { d.readByte(); left-- } else left -= k }; pos += n }
    }

    fun isPsd(head: ByteArray) = head.size >= 4 && head[0] == '8'.code.toByte() && head[1] == 'B'.code.toByte() &&
        head[2] == 'P'.code.toByte() && head[3] == 'S'.code.toByte()

    /** unpacks [len] bytes of PackBits from [src] into [dst] at [off] */
    private fun unpack(src: ByteArray, dst: ByteArray, off: Int, len: Int) {
        var i = 0; var o = off
        val end = off + len
        while (i < src.size && o < end) {
            val n = src[i++].toInt()
            if (n >= 0) {
                val c = minOf(n + 1, end - o, src.size - i)
                System.arraycopy(src, i, dst, o, c); i += n + 1; o += c
            } else if (n != -128) {
                val c = minOf(1 - n, end - o)
                val v = src[i++]
                for (k in 0 until c) dst[o + k] = v
                o += c
            }
        }
    }

    /** one channel of [w]×[h]: raw or RLE */
    private fun readChannel(inp: In, w: Int, h: Int, totalLen: Long): ByteArray? {
        val plane = ByteArray(w * h)
        if (totalLen < 2) return plane
        val comp = inp.u16()
        when (comp) {
            0 -> { val b = inp.bytes(w * h); System.arraycopy(b, 0, plane, 0, b.size); inp.skip(totalLen - 2 - w.toLong() * h) }
            1 -> {
                val counts = IntArray(h) { inp.u16() }
                for (y in 0 until h) unpack(inp.bytes(counts[y]), plane, y * w, w)
                inp.skip(totalLen - 2 - h * 2L - counts.sum())
            }
            else -> { inp.skip(totalLen - 2); return null } // ZIP: not read
        }
        return plane
    }

    /** Reads an 8-bit RGB PSD: its layers (groups are flattened out), or its flattened image. */
    fun read(stream: InputStream, maxLayers: (Int, Int) -> Int): Result {
        val inp = In(stream)
        require(inp.key() == "8BPS") { "Not a Photoshop file" }
        val version = inp.u16()
        require(version == 1) { "Large documents (PSB) aren't supported" }
        inp.skip(6)
        val channels = inp.u16()
        val h = inp.s32()
        val w = inp.s32()
        val depth = inp.u16()
        val mode = inp.u16()
        require(depth == 8) { "Only 8-bit Photoshop files can be opened" }
        require(mode == 3) { "Only RGB Photoshop files can be opened" }
        require(w in 1..16384 && h in 1..16384 && w.toLong() * h <= 64_000_000L) { "This image is too large" }
        inp.skip(inp.u32())   // color mode data
        inp.skip(inp.u32())   // image resources
        val doc = Document(w, h)
        doc.backgroundVisible = false
        var note: String? = null
        val lmLen = inp.u32()
        val lmStart = inp.pos
        if (lmLen > 0) {
            val liLen = inp.u32()
            val liStart = inp.pos
            if (liLen > 0) {
                val count = kotlin.math.abs(inp.s16())
                class Rec(val t: Int, val l: Int, val b: Int, val r: Int, val chans: List<Pair<Int, Long>>, val blend: String,
                          val opacity: Int, val clip: Int, val flags: Int, var name: String, var divider: Int)
                val recs = ArrayList<Rec>()
                repeat(count) {
                    val t = inp.s32(); val l = inp.s32(); val b = inp.s32(); val r = inp.s32()
                    val nc = inp.u16()
                    val chans = (0 until nc).map { inp.s16() to inp.u32() }
                    inp.key() // 8BIM
                    val blend = inp.key()
                    val opacity = inp.u8(); val clip = inp.u8(); val flags = inp.u8(); inp.u8()
                    val extra = inp.u32()
                    val exStart = inp.pos
                    inp.skip(inp.u32()) // mask
                    inp.skip(inp.u32()) // blending ranges
                    val nameLen = inp.u8()
                    var name = String(inp.bytes(nameLen), Charsets.ISO_8859_1)
                    val padTo = ((1 + nameLen + 3) / 4) * 4
                    inp.skip((padTo - 1 - nameLen).toLong())
                    var divider = 0
                    while (inp.pos - exStart + 12 <= extra) {
                        val sig = inp.key()
                        if (sig != "8BIM" && sig != "8B64") break
                        val k = inp.key()
                        val len = inp.u32()
                        val st = inp.pos
                        when (k) {
                            "luni" -> { val n = inp.s32(); name = String(inp.bytes(n * 2), Charsets.UTF_16BE) }
                            "lsct", "lsdk" -> divider = inp.s32()
                        }
                        inp.skip(len - (inp.pos - st))
                    }
                    inp.skip(extra - (inp.pos - exStart))
                    recs.add(Rec(t, l, b, r, chans, blend, opacity, clip, flags, name, divider))
                }
                val limit = maxLayers(w, h)
                var nested = false
                var tooMany = false
                // open groups, outermost first; Inkora keeps one level, so inner groups join the outer one
                val open = ArrayList<LayerGroup>()
                for (rec in recs) {
                    val lw = rec.r - rec.l; val lh = rec.b - rec.t
                    val planes = HashMap<Int, ByteArray?>()
                    for ((id, len) in rec.chans) planes[id] = if (lw > 0 && lh > 0) readChannel(inp, lw, lh, len) else { inp.skip(len); null }
                    if (rec.divider == 3) {
                        open.add(LayerGroup(""))
                        if (open.size > 1) nested = true
                        continue
                    }
                    if (rec.divider == 1 || rec.divider == 2) {
                        val g = open.removeLastOrNull() ?: continue
                        if (open.isEmpty()) {
                            g.name = rec.name.ifBlank { "Group ${doc.groups.size + 1}" }
                            g.visible = rec.flags and 2 == 0
                            g.opacity = rec.opacity / 255f
                            g.blend = BLEND_KEYS.entries.firstOrNull { it.value == rec.blend }?.key ?: LayerBlend.NORMAL
                            g.collapsed = rec.divider == 2
                            if (doc.layers.any { it.groupId == g.id }) doc.groups[g.id] = g
                        }
                        continue
                    }
                    if (doc.layers.size >= limit) { tooMany = true; continue }
                    val layer = Layer(doc.newLayerBitmap(), rec.name.ifBlank { "Layer ${doc.layers.size + 1}" })
                    val r = planes[0]; val g = planes[1]; val b = planes[2]; val a = planes[-1]
                    if (lw > 0 && lh > 0 && r != null && g != null && b != null) {
                        val px = IntArray(lw)
                        for (y in 0 until lh) {
                            val yy = rec.t + y
                            if (yy < 0 || yy >= h) continue
                            for (x in 0 until lw) {
                                val i = y * lw + x
                                val av = a?.get(i)?.toInt()?.and(0xFF) ?: 255
                                px[x] = (av shl 24) or ((r[i].toInt() and 0xFF) shl 16) or ((g[i].toInt() and 0xFF) shl 8) or (b[i].toInt() and 0xFF)
                            }
                            val x0 = maxOf(0, rec.l); val x1 = minOf(w, rec.r)
                            if (x1 > x0) layer.bitmap.setPixels(px, x0 - rec.l, lw, x0, yy, x1 - x0, 1)
                        }
                    }
                    layer.opacity = rec.opacity / 255f
                    layer.blend = BLEND_KEYS.entries.firstOrNull { it.value == rec.blend }?.key ?: LayerBlend.NORMAL
                    layer.visible = rec.flags and 2 == 0
                    layer.alphaLock = rec.flags and 1 != 0
                    layer.isClippingMask = rec.clip == 1
                    layer.groupId = open.firstOrNull()?.id
                    doc.layers.add(layer)
                }
                doc.normalizeGroups()
                doc.groupNameCounter = doc.groups.size + 1
                if (nested) note = "Groups inside groups were opened as one group"
                if (tooMany) note = "Not every layer fit in memory: the top ones were left out"
            }
            inp.skip(liLen - (inp.pos - liStart))
        }
        inp.skip(lmLen - (inp.pos - lmStart))
        if (doc.layers.isEmpty()) {
            // no layers: the flattened image
            val comp = inp.u16()
            val n = channels.coerceAtMost(4)
            val planes = Array(n) { ByteArray(w * h) }
            if (comp == 1) {
                val counts = IntArray(channels * h) { inp.u16() }
                for (c in 0 until channels) for (y in 0 until h) {
                    val src = inp.bytes(counts[c * h + y])
                    if (c < n) unpack(src, planes[c], y * w, w)
                }
            } else if (comp == 0) {
                for (c in 0 until channels) { val b = inp.bytes(w * h); if (c < n) System.arraycopy(b, 0, planes[c], 0, b.size) }
            } else throw IllegalArgumentException("This Photoshop file is compressed in a way Inkora can't read")
            val layer = Layer(doc.newLayerBitmap(), "Layer 1")
            val px = IntArray(w)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val a = if (n >= 4) planes[3][i].toInt() and 0xFF else 255
                    px[x] = (a shl 24) or ((planes[0][i].toInt() and 0xFF) shl 16) or ((planes[1][i].toInt() and 0xFF) shl 8) or (planes[2][i].toInt() and 0xFF)
                }
                layer.bitmap.setPixels(px, 0, w, 0, y, w, 1)
            }
            doc.layers.add(layer)
        }
        doc.activeIndex = doc.layers.size - 1
        return Result(doc, note)
    }
}

/** TIFF (flattened, with transparency), PackBits compressed: for print shops and other apps. */
object TiffIO {
    fun write(bmp: Bitmap, out: OutputStream) {
        val w = bmp.width; val h = bmp.height
        // rows, RGBA, PackBits
        val strips = ArrayList<ByteArray>(h)
        val px = IntArray(w)
        val row = ByteArray(w * 4)
        for (y in 0 until h) {
            bmp.getPixels(px, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = px[x]
                row[x * 4] = (c shr 16).toByte(); row[x * 4 + 1] = (c shr 8).toByte()
                row[x * 4 + 2] = c.toByte(); row[x * 4 + 3] = (c ushr 24).toByte()
            }
            val o = java.io.ByteArrayOutputStream()
            PsdIO.packBits(row, 0, row.size, o)
            strips.add(o.toByteArray())
        }
        val entries = 12
        val ifdOffset = 8L
        val ifdSize = 2 + entries * 12 + 4
        val bpsOffset = ifdOffset + ifdSize
        val offsetsOffset = bpsOffset + 8
        val countsOffset = offsetsOffset + 4L * h
        val dataOffset = countsOffset + 4L * h
        val d = LittleOut(out)
        d.bytes("II".toByteArray()); d.u16(42); d.u32(ifdOffset)
        d.u16(entries)
        fun tag(t: Int, type: Int, count: Long, value: Long) { d.u16(t); d.u16(type); d.u32(count); d.u32(value) }
        tag(256, 4, 1, w.toLong())                // width
        tag(257, 4, 1, h.toLong())                // height
        tag(258, 3, 4, bpsOffset)                 // bits per sample 8,8,8,8
        tag(259, 3, 1, 32773)                     // PackBits
        tag(262, 3, 1, 2)                         // RGB
        tag(273, 4, h.toLong(), if (h == 1) dataOffset else offsetsOffset)
        tag(277, 3, 1, 4)                         // samples per pixel
        tag(278, 4, 1, 1)                         // a row per strip
        tag(279, 4, h.toLong(), if (h == 1) strips[0].size.toLong() else countsOffset)
        tag(284, 3, 1, 1)                         // chunky
        tag(296, 3, 1, 2)                         // inches
        tag(338, 3, 1, 2)                         // unassociated alpha
        d.u32(0)
        repeat(4) { d.u16(8) }
        var off = dataOffset
        for (s in strips) { d.u32(off); off += s.size }
        for (s in strips) d.u32(s.size.toLong())
        for (s in strips) d.bytes(s)
        d.flush()
    }

    private class LittleOut(out: OutputStream) {
        private val o = BufferedOutputStream(out, 1 shl 16)
        fun u16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
        fun u32(v: Long) { for (k in 0 until 4) o.write(((v shr (8 * k)) and 0xFF).toInt()) }
        fun bytes(b: ByteArray) = o.write(b)
        fun flush() = o.flush()
    }
}
