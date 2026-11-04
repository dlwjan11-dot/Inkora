package com.abdulmedia.inkora

/**
 * Minimal reader for Apple binary property lists ("bplist00"), enough to read
 * the settings stored in a Procreate Brush.archive (an NSKeyedArchiver plist).
 * Pure Kotlin: no Android classes.
 */
class BPlist private constructor(private val b: ByteArray) {

    class Uid(val value: Long)

    private val offIntSize: Int
    private val refSize: Int
    private val numObjects: Int
    private val topObject: Int
    private val offsets: IntArray
    private val cache = HashMap<Int, Any?>()

    init {
        require(b.size > 40 && String(b, 0, 8, Charsets.US_ASCII) == "bplist00") { "not a binary plist" }
        val t = b.size - 32
        offIntSize = b[t + 6].toInt() and 0xFF
        refSize = b[t + 7].toInt() and 0xFF
        numObjects = readUInt(t + 8, 8).toInt()
        topObject = readUInt(t + 16, 8).toInt()
        val tableOffset = readUInt(t + 24, 8).toInt()
        require(numObjects in 1..2_000_000 && tableOffset in 8 until b.size) { "bad plist trailer" }
        offsets = IntArray(numObjects) { readUInt(tableOffset + it * offIntSize, offIntSize).toInt() }
    }

    private fun readUInt(pos: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[pos + i].toLong() and 0xFF)
        return v
    }

    val root: Any? get() = obj(topObject, 0)

    private fun obj(index: Int, depth: Int): Any? {
        if (index !in 0 until numObjects || depth > 64) return null
        if (cache.containsKey(index)) return cache[index]
        val v = parse(offsets[index], depth)
        cache[index] = v
        return v
    }

    /** returns (length, position after length) */
    private fun lengthAt(pos: Int, info: Int): Pair<Int, Int> {
        if (info != 0xF) return info to pos + 1
        val marker = b[pos + 1].toInt() and 0xFF
        val n = 1 shl (marker and 0xF)
        return readUInt(pos + 2, n).toInt() to pos + 2 + n
    }

    private fun parse(pos: Int, depth: Int): Any? {
        val marker = b[pos].toInt() and 0xFF
        val type = marker shr 4
        val info = marker and 0xF
        return when (type) {
            0x0 -> when (info) { 0x8 -> false; 0x9 -> true; else -> null }
            0x1 -> readUInt(pos + 1, 1 shl info)
            0x2 -> {
                val n = 1 shl info
                val raw = readUInt(pos + 1, n)
                if (n == 4) java.lang.Float.intBitsToFloat(raw.toInt()).toDouble() else java.lang.Double.longBitsToDouble(raw)
            }
            0x3 -> java.lang.Double.longBitsToDouble(readUInt(pos + 1, 8))
            0x4 -> {
                val (len, start) = lengthAt(pos, info)
                b.copyOfRange(start, start + len)
            }
            0x5 -> {
                val (len, start) = lengthAt(pos, info)
                String(b, start, len, Charsets.US_ASCII)
            }
            0x6 -> {
                val (len, start) = lengthAt(pos, info)
                String(b, start, len * 2, Charsets.UTF_16BE)
            }
            0x8 -> Uid(readUInt(pos + 1, info + 1))
            0xA, 0xC -> {
                val (len, start) = lengthAt(pos, info)
                List(len) { obj(readUInt(start + it * refSize, refSize).toInt(), depth + 1) }
            }
            0xD -> {
                val (len, start) = lengthAt(pos, info)
                val map = LinkedHashMap<String, Any?>()
                for (i in 0 until len) {
                    val k = obj(readUInt(start + i * refSize, refSize).toInt(), depth + 1)
                    val v = obj(readUInt(start + (len + i) * refSize, refSize).toInt(), depth + 1)
                    map[k.toString()] = v
                }
                map
            }
            else -> null
        }
    }

    companion object {
        fun parse(bytes: ByteArray): BPlist = BPlist(bytes)

        /**
         * Writes a binary plist: maps (string keys), lists, strings, whole numbers, decimals,
         * booleans, byte arrays and [Uid]s. Equal strings are written once.
         */
        fun write(root: Any?): ByteArray {
            val objs = ArrayList<Any?>()
            val strIndex = HashMap<String, Int>()
            // flatten: each container's children get their own indices
            fun add(o: Any?): Int {
                if (o is String) strIndex[o]?.let { return it }
                val i = objs.size
                objs.add(o)
                if (o is String) strIndex[o] = i
                when (o) {
                    is Map<*, *> -> {
                        val refs = ArrayList<Int>()
                        val keys = o.keys.map { it.toString() }
                        for (k in keys) refs.add(add(k))
                        for (k in keys) refs.add(add(o[k]))
                        objs[i] = WDict(refs, keys.size)
                    }
                    is List<*> -> objs[i] = WArray(o.map { add(it) })
                }
                return i
            }
            add(root)
            val n = objs.size
            val refSize = if (n < 256) 1 else if (n < 65536) 2 else 4
            val out = java.io.ByteArrayOutputStream()
            out.write("bplist00".toByteArray(Charsets.US_ASCII))
            val offsets = LongArray(n)
            fun be(v: Long, size: Int) { for (k in size - 1 downTo 0) out.write(((v shr (8 * k)) and 0xFF).toInt()) }
            fun header(type: Int, len: Int) {
                if (len < 15) out.write((type shl 4) or len)
                else {
                    out.write((type shl 4) or 0xF)
                    when {
                        len < 256 -> { out.write(0x10); be(len.toLong(), 1) }
                        len < 65536 -> { out.write(0x11); be(len.toLong(), 2) }
                        else -> { out.write(0x12); be(len.toLong(), 4) }
                    }
                }
            }
            for ((i, o) in objs.withIndex()) {
                offsets[i] = out.size().toLong()
                when (o) {
                    null -> out.write(0x00)
                    is Boolean -> out.write(if (o) 0x09 else 0x08)
                    is Int, is Long -> {
                        val v = (o as Number).toLong()
                        when {
                            v in 0..0xFF -> { out.write(0x10); be(v, 1) }
                            v in 0..0xFFFF -> { out.write(0x11); be(v, 2) }
                            v in 0..0xFFFFFFFFL -> { out.write(0x12); be(v, 4) }
                            else -> { out.write(0x13); be(v, 8) }
                        }
                    }
                    is Float, is Double -> { out.write(0x23); be(java.lang.Double.doubleToLongBits((o as Number).toDouble()), 8) }
                    is ByteArray -> { header(0x4, o.size); out.write(o) }
                    is String -> {
                        if (o.all { it.code < 128 }) { header(0x5, o.length); out.write(o.toByteArray(Charsets.US_ASCII)) }
                        else { header(0x6, o.length); out.write(o.toByteArray(Charsets.UTF_16BE)) }
                    }
                    is Uid -> {
                        val v = o.value
                        when {
                            v < 256 -> { out.write(0x80); be(v, 1) }
                            v < 65536 -> { out.write(0x81); be(v, 2) }
                            else -> { out.write(0x83); be(v, 4) }
                        }
                    }
                    is WArray -> { header(0xA, o.refs.size); for (r in o.refs) be(r.toLong(), refSize) }
                    is WDict -> { header(0xD, o.count); for (r in o.refs) be(r.toLong(), refSize) }
                    else -> throw IllegalArgumentException("can't write ${o.javaClass.simpleName}")
                }
            }
            val tableOffset = out.size().toLong()
            val offSize = if (tableOffset < 256) 1 else if (tableOffset < 65536) 2 else if (tableOffset < 0xFFFFFFFFL) 4 else 8
            for (off in offsets) be(off, offSize)
            repeat(6) { out.write(0) }
            out.write(offSize); out.write(refSize)
            be(n.toLong(), 8); be(0, 8); be(tableOffset, 8)
            return out.toByteArray()
        }

        private class WArray(val refs: List<Int>)
        private class WDict(val refs: List<Int>, val count: Int)

        /**
         * An NSKeyedArchiver plist with one root object of class [className] holding [fields]
         * (strings are stored as references, numbers and booleans in place), as Procreate's
         * Brush.archive is.
         */
        fun keyedArchive(className: String, fields: Map<String, Any?>): ByteArray {
            val objects = ArrayList<Any?>()
            objects.add("\$null")
            val root = LinkedHashMap<String, Any?>()
            objects.add(root)
            for ((k, v) in fields) {
                when (v) {
                    null -> root[k] = Uid(0)
                    is String -> { objects.add(v); root[k] = Uid((objects.size - 1).toLong()) }
                    else -> root[k] = v
                }
            }
            objects.add(mapOf("\$classname" to className, "\$classes" to listOf(className, "NSObject")))
            root["\$class"] = Uid((objects.size - 1).toLong())
            val top = linkedMapOf<String, Any?>(
                "\$version" to 100000,
                "\$archiver" to "NSKeyedArchiver",
                "\$top" to mapOf("root" to Uid(1)),
                "\$objects" to objects
            )
            return write(top)
        }

        /**
         * For an NSKeyedArchiver plist: the root object's fields, with UID references
         * to simple values (strings, numbers) resolved.
         */
        fun keyedRootFields(bytes: ByteArray): Map<String, Any?> {
            val top = parse(bytes).root as? Map<*, *> ?: return emptyMap()
            val objects = top["\$objects"] as? List<*> ?: return emptyMap()
            val topDict = top["\$top"] as? Map<*, *> ?: return emptyMap()
            val rootRef = (topDict["root"] as? Uid)?.value?.toInt() ?: return emptyMap()
            val root = objects.getOrNull(rootRef) as? Map<*, *> ?: return emptyMap()
            val out = HashMap<String, Any?>()
            for ((k, v) in root) {
                val resolved = if (v is Uid) objects.getOrNull(v.value.toInt()) else v
                if (resolved is String && resolved == "\$null") continue
                if (resolved is String || resolved is Number || resolved is Boolean) out[k.toString()] = resolved
            }
            return out
        }
    }
}
