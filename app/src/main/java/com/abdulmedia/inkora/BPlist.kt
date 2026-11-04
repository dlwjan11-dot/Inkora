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
