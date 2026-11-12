package com.abdulmedia.inkora

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Imports brushes downloaded from the internet:
 *  - Procreate .brush / .brushset files (Shape.png + Grain.png + Brush.archive)
 *  - .zip files that contain those, or plain PNG brush stamps
 *  - a single PNG / JPG / WEBP image used as a brush stamp
 * Photoshop .abr is not supported (closed format).
 */
object BrushImport {

    class Result(val presets: List<BrushPreset>, val message: String)

    private const val MAX_TOTAL = 300L * 1024 * 1024

    private fun root(ctx: Context) = File(ctx.filesDir, "brushes").apply { mkdirs() }

    // =========================================================
    // import
    // =========================================================

    fun importBytes(ctx: Context, bytes: ByteArray, fileName: String?): Result {
        val found = ArrayList<BrushPreset>()
        collect(ctx, bytes, fileName, found, 0)
        if (found.isEmpty()) {
            val lower = fileName?.lowercase() ?: ""
            val why = when {
                lower.endsWith(".abr") || isAbr(bytes) -> "No brush shapes could be read from this Photoshop file. Some newer .abr files keep their brushes in a way Inkora can't read yet."
                else -> "No brushes found in this file."
            }
            return Result(emptyList(), why)
        }
        return Result(found, if (found.size == 1) "Imported \"${found[0].name}\"" else "Imported ${found.size} brushes")
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
    private fun isImage(b: ByteArray) = b.size > 12 && (
        (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) ||          // PNG
            (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||          // JPEG
            (b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte()))    // WEBP
    private fun isAbr(b: ByteArray) = Abr.looksLikeAbr(b)

    private fun collect(ctx: Context, bytes: ByteArray, fileName: String?, out: MutableList<BrushPreset>, depth: Int) {
        when {
            isZip(bytes) && depth < 3 -> importZip(ctx, bytes, fileName, out, depth)
            isAbr(bytes) -> importAbr(ctx, bytes, fileName, out)
            isImage(bytes) -> {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
                out.add(save(ctx, baseName(fileName) ?: "Stamp", "Imported", tipFromImage(bmp), null, GrainType.NONE, emptyMap(), stamp = true))
                bmp.recycle()
            }
        }
    }

    /** Photoshop brushes: each shape becomes a brush; spacing comes across from the old files */
    private fun importAbr(ctx: Context, bytes: ByteArray, fileName: String?, out: MutableList<BrushPreset>) {
        val base = baseName(fileName) ?: "Photoshop"
        val shapes = Abr.read(bytes)
        shapes.forEachIndexed { i, raw ->
            if (raw.alpha.none { it.toInt() != 0 }) return@forEachIndexed
            val s = Abr.shrink(raw, 256)
            val name = raw.name ?: if (shapes.size == 1) base else "$base ${i + 1}"
            val meta = HashMap<String, Any?>()
            meta["plotSpacing"] = raw.spacing ?: 0.25f
            // start at the size the brush was made at (within reason)
            meta["maxSize"] = max(raw.w, raw.h) / 262f
            meta["paintSize"] = 1f
            out.add(save(ctx, name, "Photoshop", Brushes.alphaBitmap(s.alpha, s.w, s.h), null, GrainType.NONE, meta, stamp = false))
        }
    }

    private fun importZip(ctx: Context, bytes: ByteArray, fileName: String?, out: MutableList<BrushPreset>, depth: Int) {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val name = e.name.replace('\\', '/')
                if (!e.isDirectory && !name.startsWith("__MACOSX") && !name.substringAfterLast('/').startsWith("._")) {
                    // counted while it is read, so one huge packed file can't fill the memory first
                    val data = readLimited(zip, MAX_TOTAL - total)
                    total += data.size
                    entries[name] = data
                }
                e = zip.nextEntry
            }
        }
        val byLower = HashMap<String, String>()
        for (k in entries.keys) byLower[k.lowercase()] = k
        fun get(path: String): ByteArray? = byLower[path.lowercase()]?.let { entries[it] }

        // a brush set names itself and lists its brushes' folders in order
        val setPlist = entries.keys.firstOrNull { it.substringAfterLast('/').equals("brushset.plist", true) }
        val (setName, setOrder) = setPlist?.let { readSetList(entries[it]!!) } ?: (null to emptyList())
        val setRoot = setPlist?.substringBeforeLast('/', "")?.let { if (it.isEmpty()) "" else "$it/" } ?: ""
        val category = setName?.takeIf { it.isNotBlank() && it != "Imported" }
            ?: if (setPlist != null) baseName(fileName) ?: "Imported" else "Imported"

        // 1) Procreate brushes: one folder per brush, each with a Brush.archive
        //    (a dual brush keeps its second brush in "Sub01/": that is part of it, not a brush of its own)
        val archives = entries.keys.filter { it.substringAfterLast('/').equals("brush.archive", true) }
            .map { it.substring(0, it.length - "brush.archive".length) }
            .filter { !it.endsWith("Sub01/", ignoreCase = true) }
        val folders = if (setOrder.isEmpty()) archives else {
            val ordered = setOrder.map { setRoot + it.trimEnd('/') + "/" }.filter { f -> archives.any { it.equals(f, true) } }
                .map { f -> archives.first { it.equals(f, true) } }
            ordered + archives.filter { it !in ordered }
        }
        val single = folders.size == 1
        for (prefix in folders) {
            runCatching {
                val meta = get(prefix + "Brush.archive")?.let { runCatching { BPlist.keyedRootFields(it) }.getOrNull() } ?: emptyMap()
                val name = (meta["name"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (if (single) baseName(fileName) else null)
                    ?: "Imported ${out.size + 1}"
                fun flag(k: String) = meta[k] == true || (meta[k] as? Number)?.toInt() == 1
                val shapeInverted = flag("shapeInverted")
                val grainInverted = flag("textureInverted")
                // the shape: its own image (by the name its path gives, or Shape.png), else a stock one
                val shapePath = (meta["bundledShapePath"] as? String)?.takeIf { it != "\$null" }
                val shapeBytes = shapePath?.let { get(prefix + it) ?: get(prefix + it.substringAfterLast('/')) } ?: get(prefix + "Shape.png")
                val tip: Bitmap = if (shapeBytes != null) {
                    val bmp = BitmapFactory.decodeByteArray(shapeBytes, 0, shapeBytes.size) ?: return@runCatching
                    tipFromProcreate(bmp, shapeInverted).also { bmp.recycle() }
                } else stockTip(shapePath ?: "", shapeInverted)
                // the grain: its own image, else a stock one we have something like
                val grainPath = (meta["bundledGrainPath"] as? String)?.takeIf { it != "\$null" }
                val grainBytes = grainPath?.let { get(prefix + it) ?: get(prefix + it.substringAfterLast('/')) } ?: get(prefix + "Grain.png")
                var grainType = GrainType.NONE
                val grain: Bitmap? = if (grainBytes != null) {
                    BitmapFactory.decodeByteArray(grainBytes, 0, grainBytes.size)?.let { g ->
                        grainFromImage(g, grainInverted).also { g.recycle() }
                    }
                } else {
                    grainType = stockGrain(grainPath ?: "")
                    null
                }
                // a dual brush keeps its second brush in Sub01/: its shape becomes ours
                val dual = get(prefix + "Sub01/Shape.png")?.let { b ->
                    val subMeta = get(prefix + "Sub01/Brush.archive")?.let { runCatching { BPlist.keyedRootFields(it) }.getOrNull() } ?: emptyMap()
                    val inv = subMeta["shapeInverted"] == true || (subMeta["shapeInverted"] as? Number)?.toInt() == 1
                    BitmapFactory.decodeByteArray(b, 0, b.size)?.let { bmp -> tipFromProcreate(bmp, inv).also { bmp.recycle() } }
                }
                out.add(save(ctx, name, category, tip, grain, grainType, meta, stamp = false, dual = dual))
            }
        }

        // 2) nested brush files (e.g. a .zip full of .brushset files)
        for ((name, data) in entries) {
            val l = name.lowercase()
            if (l.endsWith(".brush") || l.endsWith(".brushset") || l.endsWith(".zip")) {
                collect(ctx, data, name.substringAfterLast('/'), out, depth + 1)
            }
        }

        // 3) nothing Procreate-like: every image is a stamp
        if (folders.isEmpty()) {
            val stampSet = baseName(fileName) ?: "Imported"
            for ((name, data) in entries) {
                val l = name.lowercase()
                if (!(l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".webp"))) continue
                if (l.endsWith("grain.png") || l.contains("thumbnail") || l.contains("quicklook")) continue
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: continue
                out.add(save(ctx, baseName(name) ?: "Stamp", stampSet, tipFromImage(bmp), null, GrainType.NONE, emptyMap(), stamp = true))
                bmp.recycle()
            }
        }
    }

    /** The set's name and its brushes' folders, from brushset.plist (XML or binary). */
    private fun readSetList(bytes: ByteArray): Pair<String?, List<String>> {
        if (bytes.size > 8 && String(bytes, 0, 6, Charsets.US_ASCII) == "bplist") {
            val root = runCatching { BPlist.parse(bytes).root as? Map<*, *> }.getOrNull() ?: return null to emptyList()
            val name = root["name"] as? String
            val list = (root["brushes"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            return name to list
        }
        val text = String(bytes, Charsets.UTF_8)
        fun unescape(v: String) = v.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        val name = Regex("<key>name</key>\\s*<string>([^<]*)</string>").find(text)?.groupValues?.get(1)?.let(::unescape)
        val arr = Regex("<key>brushes</key>\\s*<array>(.*?)</array>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1) ?: ""
        val list = Regex("<string>([^<]*)</string>").findAll(arr).map { unescape(it.groupValues[1]) }.toList()
        return name to list
    }

    private fun baseName(path: String?): String? {
        val n = path?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
        val b = n.substringBeforeLast('.').replace('_', ' ').trim()
        return b.ifEmpty { null }
    }

    // =========================================================
    // image -> brush tip / grain
    // =========================================================

    private fun scaled(src: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / max(src.width, src.height)
        if (s >= 1f) return src
        return Bitmap.createScaledBitmap(src, max(1, (src.width * s).roundToInt()), max(1, (src.height * s).roundToInt()), true)
    }

    private fun luminance(c: Int): Float {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }

    /**
     * Procreate shapes are white-on-black; many PNG stamps are dark-on-transparent
     * or dark-on-white. All become "alpha = where paint goes".
     */
    fun tipFromImage(src: Bitmap): Bitmap {
        val bmp = scaled(src.copy(Bitmap.Config.ARGB_8888, false), 256)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val translucent = px.count { (it ushr 24) < 250 }
        val a = ByteArray(w * h)
        if (translucent > px.size / 50) {
            for (i in px.indices) a[i] = (px[i] ushr 24).toByte()
        } else {
            var border = 0f
            var n = 0
            for (x in 0 until w) { border += luminance(px[x]) + luminance(px[(h - 1) * w + x]); n += 2 }
            for (y in 0 until h) { border += luminance(px[y * w]) + luminance(px[y * w + w - 1]); n += 2 }
            val invert = border / n > 0.5f
            for (i in px.indices) {
                val l = luminance(px[i])
                a[i] = ((if (invert) 1f - l else l) * 255).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return Brushes.alphaBitmap(a, w, h)
    }

    /**
     * A Procreate shape: white paints, black doesn't (the other way round when [inverted]).
     * An image with real transparency uses that instead.
     */
    fun tipFromProcreate(src: Bitmap, inverted: Boolean): Bitmap {
        val bmp = scaled(src.copy(Bitmap.Config.ARGB_8888, false), 256)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val translucent = px.count { (it ushr 24) < 250 } > px.size / 50
        val a = ByteArray(w * h) { i ->
            val v = if (translucent) (px[i] ushr 24) / 255f
                else luminance(px[i]).let { if (inverted) 1f - it else it }
            (v * 255).roundToInt().coerceIn(0, 255).toByte()
        }
        return Brushes.alphaBitmap(a, w, h)
    }

    /** Procreate's own shapes aren't in the file: the nearest of ours. */
    private fun stockTip(path: String, inverted: Boolean): Bitmap {
        val n = path.lowercase()
        fun has(vararg w: String) = w.any { n.contains(it) }
        if (has("blank")) {
            // the stamp's whole square (nothing, inverted: then a round tip is the useful reading)
            if (!inverted) {
                val a = ByteArray(64 * 64) { 0xFF.toByte() }
                return Brushes.alphaBitmap(a, 64, 64)
            }
        }
        val shape = when {
            has("square") -> TipShape.SQUARE
            has("spray", "spatter", "splat", "dots", "speckle") -> TipShape.SPRAY
            has("pencil", "graphite") -> TipShape.PENCIL
            has("charcoal", "chalk", "pastel") -> TipShape.CHARCOAL
            has("bristle", "hair", "fan") -> TipShape.BRISTLE
            has("flat") -> TipShape.FLAT
            has("ultra-soft", "ultrasoft", "soft", "air", "blur", "glow") -> TipShape.SOFT
            else -> TipShape.HARD
        }
        return Brushes.tip(shape)
    }

    /** Procreate's own grains aren't in the file either: the nearest of ours, or none. */
    private fun stockGrain(path: String): GrainType {
        val n = path.lowercase()
        if (n.isBlank() || n.contains("blank")) return GrainType.NONE
        return when {
            listOf("canvas", "linen", "fabric", "weave", "denim", "twill").any { n.contains(it) } -> GrainType.CANVAS
            listOf("rough", "grit", "noise", "sand", "concrete", "rust", "dirt", "grunge").any { n.contains(it) } -> GrainType.ROUGH
            else -> GrainType.PAPER
        }
    }

    /** Grain: white = paint shows, black = paper shows. */
    fun grainFromImage(src: Bitmap, inverted: Boolean = false): Bitmap {
        val bmp = scaled(src.copy(Bitmap.Config.ARGB_8888, false), 512)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val a = ByteArray(w * h) { i ->
            val alpha = (px[i] ushr 24) / 255f
            val v = luminance(px[i]) * alpha
            ((if (inverted) 1f - v else v) * 255).roundToInt().coerceIn(0, 255).toByte()
        }
        return Brushes.alphaBitmap(a, w, h)
    }

    // =========================================================
    // storage
    // =========================================================

    private fun num(meta: Map<String, Any?>, key: String): Float? =
        (meta[key] as? Number)?.toFloat()?.takeIf { it.isFinite() }

    /**
     * Keeps a brush: its shape and grain as images, and what we can carry over of its settings
     * (Procreate keeps most of them as 0..1 amounts) in brush.json.
     */
    private fun save(ctx: Context, name: String, category: String, tip: Bitmap, grain: Bitmap?, grainType: GrainType,
                     meta: Map<String, Any?>, stamp: Boolean, dual: Bitmap? = null): BrushPreset {
        val id = UUID.randomUUID().toString()
        val dir = File(root(ctx), id).apply { mkdirs() }
        val spacing = num(meta, "plotSpacing")?.takeIf { it > 0f && it <= 4f }?.coerceIn(0.02f, 2f)
            ?: if (stamp) 0.25f else 0.08f
        val scatter = num(meta, "plotJitter")?.takeIf { it in 0f..3f }?.coerceAtMost(1.5f) ?: 0f
        // sizes: the file's most size is in units of 262 canvas pixels; start halfway up its slider
        val maxPx = num(meta, "maxSize")?.let { it * 262f }?.takeIf { it > 0f }
        val sliderPos = num(meta, "paintSize")?.coerceIn(0f, 1f) ?: 0.5f
        val size = maxPx?.let { (it * (0.15f + 0.85f * sliderPos * sliderPos)).coerceIn(2f, 400f) }
            ?: if (stamp) 80f else 40f
        val pressureSize = num(meta, "dynamicsPressureSize")?.coerceIn(0f, 1f)
        val pressureOpacity = num(meta, "dynamicsPressureOpacity")?.coerceIn(0f, 1f)
        val streamline = num(meta, "plotSmoothing")?.coerceIn(0f, 1f) ?: 0.25f
        val opacity = num(meta, "paintOpacity")?.coerceIn(0.05f, 1f) ?: 1f
        val flow = num(meta, "dynamicsGlazedFlow")?.coerceIn(0.05f, 1f) ?: 1f
        val randomRot = meta["shapeRandomise"] == true || (meta["shapeRandomise"] as? Number)?.toInt() == 1
        val rot = num(meta, "shapeRotation") ?: 0f
        val angle = Math.toDegrees((num(meta, "shapeAngle") ?: 0f).toDouble()).toFloat()
        val rotation = when {
            randomRot -> RotationMode.RANDOM
            kotlin.math.abs(rot) >= 0.5f -> RotationMode.FOLLOW
            kotlin.math.abs(angle) > 0.5f -> RotationMode.FIXED
            else -> RotationMode.NONE
        }
        val taper = listOfNotNull(num(meta, "pencilTaperStartLength"), num(meta, "pencilTaperEndLength"),
            num(meta, "taperStartLength"), num(meta, "taperEndLength")).maxOrNull()?.coerceIn(0f, 1f) ?: 0f
        val grainScale = num(meta, "textureScale")?.let { kotlin.math.sqrt((it / 16f).coerceAtLeast(0f)) }
        val json = JSONObject().apply {
            put("name", name)
            put("category", category)
            put("spacing", spacing.toDouble())
            put("scatter", scatter.toDouble())
            put("stamp", stamp)
            put("size", size.toDouble())
            pressureSize?.let { put("pressureSize", it.toDouble()) }
            pressureOpacity?.let { put("pressureOpacity", it.toDouble()) }
            put("streamline", streamline.toDouble())
            put("opacity", opacity.toDouble())
            put("flow", flow.toDouble())
            put("rotation", rotation.name)
            put("angle", angle.toDouble())
            put("taper", (taper * 8f).toDouble())
            if (grainType != GrainType.NONE) put("grainType", grainType.name)
            grainScale?.let { put("grainScale", (0.3f + it * 3f).toDouble()) }
            put("dyn", dynamicsFrom(meta, taper * 8f, dual != null))
        }
        File(dir, "brush.json").writeText(json.toString(2))
        writeAlpha(tip, File(dir, "shape.png"))
        if (grain != null) writeAlpha(grain, File(dir, "grain.png"))
        if (dual != null) writeAlpha(dual, File(dir, "dual.png"))
        return makePreset(id, json, tip, grain, dual)
    }

    /** the Brush Studio's settings, from the file's (Procreate's names, and Inkora's own) */
    private fun dynamicsFrom(meta: Map<String, Any?>, taperLength: Float, hasDual: Boolean): JSONObject {
        val d = BrushDynamics(taperLength = taperLength)
        fun n(k: String) = num(meta, k)
        fun flag(k: String) = meta[k] == true || (meta[k] as? Number)?.toInt() == 1
        n("plotMovingAverageStabilization")?.let { d.stabilization = it.coerceIn(0f, 1f) }
        n("plotFFTSmoothingAmount")?.let { d.motionFilter = it.coerceIn(0f, 1f) }
        (n("pencilTaperSize") ?: n("taperSize"))?.let { d.taperSize = it.coerceIn(0f, 1f) }
        (n("pencilTaperOpacity") ?: n("taperOpacity"))?.let { d.taperOpacity = it.coerceIn(0f, 1f) }
        n("shapeRoundness")?.let { d.roundness = it.coerceIn(0.05f, 1f) }
        d.flipX = flag("shapeFlipXJitter"); d.flipY = flag("shapeFlipYJitter")
        n("grainDepth")?.let { d.grainDepth = it.coerceIn(0f, 1f) }
        n("dynamicsJitterHue")?.let { d.hueJitter = it.coerceIn(0f, 1f) }
        n("dynamicsJitterSaturation")?.let { d.satJitter = it.coerceIn(0f, 1f) }
        (n("dynamicsJitterLightness") ?: n("dynamicsJitterDarkness"))?.let { d.lightJitter = it.coerceIn(0f, 1f) }
        n("dynamicsJitterStrokeHue")?.let { d.strokeHueJitter = it.coerceIn(0f, 1f) }
        (n("dynamicsJitterStrokeLightness") ?: n("dynamicsJitterStrokeDarkness"))?.let { d.strokeLightJitter = it.coerceIn(0f, 1f) }
        n("dynamicsPressureHue")?.let { d.pressureHue = it.coerceIn(-1f, 1f) }
        n("dynamicsPressureSaturation")?.let { d.pressureSat = it.coerceIn(-1f, 1f) }
        n("dynamicsPressureBrightness")?.let { d.pressureBright = it.coerceIn(-1f, 1f) }
        n("dynamicsTiltBrightness")?.let { d.tiltBright = it.coerceIn(-1f, 1f) }
        n("dynamicsMix")?.let { d.wetDilution = it.coerceIn(0f, 1f) }
        n("dynamicsLoad")?.let { d.wetCharge = it.coerceIn(0f, 1f) }
        n("dynamicsWetAccumulation")?.let { d.wetPull = it.coerceIn(0f, 1f) }
        n("dynamicsJitterSize")?.let { d.sizeJitter = it.coerceIn(0f, 1f) }
        n("dynamicsJitterOpacity")?.let { d.opacityJitter = it.coerceIn(0f, 1f) }
        (meta["inkoraBlend"] as? String)?.let { b -> runCatching { d.blend = LayerBlend.valueOf(b) } }
        if (meta.containsKey("inkoraTiltShading")) d.tiltShading = flag("inkoraTiltShading")
        n("inkoraPressureCurve")?.let { d.pressureCurve = it.coerceIn(0.3f, 3f) }
        n("inkoraDualSize")?.let { d.dualSize = it.coerceIn(0.1f, 4f) }
        n("inkoraDualScatter")?.let { d.dualScatter = it.coerceIn(0f, 2f) }
        if (hasDual) { d.dualOn = true; d.dualShape = "CUSTOM" }
        return d.toJson()
    }

    /**
     * Keeps a copy of [b] as a brush of the user's own, named [name] in [category], with its
     * shape, grain and every setting as they are now (Duplicate in the Brush Studio).
     */
    fun saveCustom(ctx: Context, b: Brush, name: String, category: String): BrushPreset {
        val id = UUID.randomUUID().toString()
        val dir = File(root(ctx), id).apply { mkdirs() }
        val json = JSONObject().apply {
            put("name", name)
            put("category", category)
            put("spacing", b.spacing.toDouble())
            put("scatter", b.scatter.toDouble())
            put("stamp", false)
            put("size", b.size.toDouble())
            put("pressureSize", b.sizePressure.toDouble())
            put("pressureOpacity", b.flowPressure.toDouble())
            put("streamline", b.streamline.toDouble())
            put("opacity", b.opacity.toDouble())
            put("flow", b.flow.toDouble())
            put("rotation", b.rotation.name)
            put("angle", b.angle.toDouble())
            put("taper", b.dyn.taperLength.toDouble())
            put("grainScale", b.grainScale.toDouble())
            put("dyn", b.dyn.toJson())
        }
        val tip = b.tip()
        val grain = if (b.preset.grain != GrainType.NONE) (b.preset.customGrain ?: Brushes.grain(b.preset.grain)) else null
        val dual = b.preset.dualTip
        File(dir, "brush.json").writeText(json.toString(2))
        writeAlpha(tip, File(dir, "shape.png"))
        if (grain != null) writeAlpha(grain, File(dir, "grain.png"))
        if (dual != null) writeAlpha(dual, File(dir, "dual.png"))
        return makePreset(id, json, readAlpha(File(dir, "shape.png")) ?: tip, grain?.let { readAlpha(File(dir, "grain.png")) }, dual)
    }

    /**
     * A stamp brush made from a picture (a part of the artwork): dark and solid parts stamp, light and
     * empty parts don't. One mark per touch along the stroke, far apart, like a rubber stamp.
     */
    fun saveStamp(ctx: Context, picture: Bitmap, name: String, category: String, size: Float): BrushPreset? {
        val tip = stampTip(picture) ?: return null
        val id = UUID.randomUUID().toString()
        val dir = File(root(ctx), id).apply { mkdirs() }
        val json = JSONObject().apply {
            put("name", name)
            put("category", category)
            put("spacing", 1.4)
            put("scatter", 0.0)
            put("stamp", true)
            put("size", size.toDouble())
            put("pressureSize", 0.2)
            put("pressureOpacity", 0.15)
            put("streamline", 0.0)
            put("opacity", 1.0)
            put("flow", 1.0)
            put("rotation", RotationMode.NONE.name)
            put("angle", 0.0)
            put("taper", 0.0)
        }
        File(dir, "brush.json").writeText(json.toString(2))
        writeAlpha(tip, File(dir, "shape.png"))
        return makePreset(id, json, readAlpha(File(dir, "shape.png")) ?: tip, null)
    }

    /** the stamp's shape: square, centered, darkness times coverage (only coverage for light pictures) */
    private fun stampTip(src: Bitmap): Bitmap? {
        val side = maxOf(src.width, src.height)
        if (side < 2) return null
        val sq = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(sq).drawBitmap(src, (side - src.width) / 2f, (side - src.height) / 2f, null)
        val bmp = scaled(sq, 256)
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        if (bmp !== sq) sq.recycle()
        val dark = FloatArray(px.size) { i -> (px[i] ushr 24) / 255f * (1f - luminance(px[i])) }
        val peak = dark.maxOrNull() ?: 0f
        val a = ByteArray(px.size)
        if (peak > 0.15f) {
            for (i in px.indices) a[i] = (dark[i] / peak * 255).roundToInt().coerceIn(0, 255).toByte()
        } else {
            // a light picture (white lines, pale colors): its outline is the stamp
            var any = false
            for (i in px.indices) { val v = px[i] ushr 24; a[i] = v.toByte(); if (v > 20) any = true }
            if (!any) return null
        }
        return Brushes.alphaBitmap(a, w, h)
    }

    /** Renames a brush of the user's own (kept in brush.json). */
    fun rename(ctx: Context, preset: BrushPreset, name: String) {
        if (!preset.imported) return
        val f = File(File(root(ctx), preset.id.removePrefix("user:")), "brush.json")
        runCatching { f.writeText(JSONObject(f.readText()).put("name", name).toString(2)) }
    }

    /** Replaces the shape or grain image of a brush of the user's own ("shape.png" / "grain.png"). */
    fun replaceImage(ctx: Context, preset: BrushPreset, file: String, alpha: Bitmap) {
        if (!preset.imported) return
        writeAlpha(alpha, File(File(root(ctx), preset.id.removePrefix("user:")), file))
    }

    /** Reloads one brush of the user's own from disk (after its images or name changed). */
    fun reload(ctx: Context, preset: BrushPreset): BrushPreset? {
        if (!preset.imported) return null
        val dir = File(root(ctx), preset.id.removePrefix("user:"))
        return runCatching {
            val json = JSONObject(File(dir, "brush.json").readText())
            val tip = readAlpha(File(dir, "shape.png")) ?: return null
            makePreset(dir.name, json, tip, readAlpha(File(dir, "grain.png")), readAlpha(File(dir, "dual.png")))
        }.getOrNull()
    }

    private fun writeAlpha(alpha: Bitmap, f: File) {
        val argb = Bitmap.createBitmap(alpha.width, alpha.height, Bitmap.Config.ARGB_8888)
        Canvas(argb).drawBitmap(alpha, 0f, 0f, Paint().apply { color = 0xFFFFFFFF.toInt() })
        f.outputStream().use { argb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        argb.recycle()
    }

    private fun readAlpha(f: File): Bitmap? {
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        val a = bmp.extractAlpha()
        bmp.recycle()
        return a
    }

    private fun makePreset(id: String, json: JSONObject, tip: Bitmap, grain: Bitmap?, dual: Bitmap? = null): BrushPreset {
        val stamp = json.optBoolean("stamp", false)
        fun f(key: String, def: Float) = json.optDouble(key, def.toDouble()).toFloat().takeIf { it.isFinite() } ?: def
        val stockGrain = runCatching { GrainType.valueOf(json.optString("grainType", "NONE")) }.getOrDefault(GrainType.NONE)
        return BrushPreset(
            name = json.optString("name", "Imported"),
            category = json.optString("category", "Imported").ifBlank { "Imported" },
            tip = TipShape.CUSTOM,
            spacing = f("spacing", 0.1f),
            minSize = 1f - f("pressureSize", if (stamp) 0.2f else 0.6f),
            minFlow = 1f - f("pressureOpacity", if (stamp) 0.2f else 0.7f),
            flow = f("flow", 1f),
            streamline = f("streamline", 0.25f),
            defaultSize = f("size", if (stamp) 80f else 40f),
            defaultOpacity = f("opacity", 1f),
            scatter = f("scatter", 0f),
            rotation = runCatching { RotationMode.valueOf(json.optString("rotation", "NONE")) }.getOrDefault(RotationMode.NONE),
            angle = f("angle", 0f),
            taper = f("taper", 0f),
            grain = if (grain != null) GrainType.CUSTOM else stockGrain,
            grainScale = f("grainScale", 1f),
            id = "user:$id",
            customTip = tip,
            customGrain = grain,
            dynamics = json.optJSONObject("dyn")?.let { d -> BrushDynamics(taperLength = f("taper", 0f)).also { it.read(d) } },
            dualTip = dual
        )
    }

    fun loadAll(ctx: Context): List<BrushPreset> {
        val dirs = root(ctx).listFiles()?.filter { it.isDirectory }?.sortedBy { it.lastModified() } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            runCatching {
                val json = JSONObject(File(dir, "brush.json").readText())
                val tip = readAlpha(File(dir, "shape.png")) ?: return@runCatching null
                makePreset(dir.name, json, tip, readAlpha(File(dir, "grain.png")), readAlpha(File(dir, "dual.png")))
            }.getOrNull()
        }
    }

    fun delete(ctx: Context, preset: BrushPreset) {
        if (!preset.imported) return
        File(root(ctx), preset.id.removePrefix("user:")).deleteRecursively()
    }

    // =========================================================
    // download from a link
    // =========================================================

    /** Turns common share links into direct-download links. */
    fun directLink(url: String): String {
        val u = url.trim()
        Regex("drive\\.google\\.com/file/d/([^/?#]+)").find(u)?.let {
            return "https://drive.google.com/uc?export=download&id=${it.groupValues[1]}"
        }
        if (u.contains("dropbox.com")) {
            return when {
                u.contains("dl=0") -> u.replace("dl=0", "dl=1")
                u.contains("dl=1") -> u
                u.contains("?") -> "$u&dl=1"
                else -> "$u?dl=1"
            }
        }
        return u
    }

    /** Returns (bytes, file name). Runs on a background thread. */
    /** all of [input], or an error when it is longer than [max] bytes */
    fun readLimited(input: java.io.InputStream, max: Long = MAX_TOTAL): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IllegalArgumentException("File is too large")
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    fun download(link: String): Pair<ByteArray, String?> {
        var url = URL(directLink(link))
        var redirects = 0
        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "Inkora/0.9 (Android)")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IllegalArgumentException("Bad redirect")
                conn.disconnect()
                url = URL(url, loc)
                if (++redirects > 8) throw IllegalArgumentException("Too many redirects")
                continue
            }
            if (code !in 200..299) throw IllegalArgumentException("Server answered $code")
            val disposition = conn.getHeaderField("Content-Disposition")
            val name = disposition?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?").find(it)?.groupValues?.get(1) }
                ?: url.path.substringAfterLast('/').ifEmpty { null }
            val bos = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_TOTAL) throw IllegalArgumentException("File is too large")
                    bos.write(buf, 0, n)
                }
            }
            conn.disconnect()
            return bos.toByteArray() to name
        }
    }
}
