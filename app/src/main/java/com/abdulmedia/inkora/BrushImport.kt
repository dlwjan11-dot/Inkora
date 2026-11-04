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
                lower.endsWith(".abr") || isAbr(bytes) -> "Photoshop .abr brushes aren't supported yet. Use Procreate brushes (.brush / .brushset) or PNG stamps."
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
    private fun isAbr(b: ByteArray) = b.size > 4 && b[0].toInt() == 0 && b[1].toInt() in 1..10

    private fun collect(ctx: Context, bytes: ByteArray, fileName: String?, out: MutableList<BrushPreset>, depth: Int) {
        when {
            isZip(bytes) && depth < 3 -> importZip(ctx, bytes, fileName, out, depth)
            isImage(bytes) -> {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
                out.add(save(ctx, baseName(fileName) ?: "Stamp", tipFromImage(bmp), null, emptyMap(), stamp = true))
                bmp.recycle()
            }
        }
    }

    private fun importZip(ctx: Context, bytes: ByteArray, fileName: String?, out: MutableList<BrushPreset>, depth: Int) {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val name = e.name
                if (!e.isDirectory && !name.startsWith("__MACOSX") && !name.substringAfterLast('/').startsWith("._")) {
                    val bos = ByteArrayOutputStream()
                    zip.copyTo(bos)
                    total += bos.size()
                    if (total > MAX_TOTAL) throw IllegalArgumentException("File is too large")
                    entries[name] = bos.toByteArray()
                }
                e = zip.nextEntry
            }
        }
        val byLower = entries.keys.associateBy { it.lowercase() }

        // 1) Procreate brushes: one folder per brush with Shape.png
        val shapes = entries.keys.filter { it.lowercase().endsWith("shape.png") }
        val single = shapes.size == 1
        for (shapeName in shapes) {
            val prefix = shapeName.substring(0, shapeName.length - "shape.png".length)
            val grainBytes = byLower[(prefix + "grain.png").lowercase()]?.let { entries[it] }
            val archive = byLower[(prefix + "brush.archive").lowercase()]?.let { entries[it] }
            val meta = archive?.let { runCatching { BPlist.keyedRootFields(it) }.getOrNull() } ?: emptyMap()
            val shapeBmp = entries[shapeName]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: continue
            val grainBmp = grainBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            val name = (meta["name"] as? String)?.takeIf { it.isNotBlank() }
                ?: (if (single) baseName(fileName) else null)
                ?: "Imported ${out.size + 1}"
            out.add(save(ctx, name, tipFromImage(shapeBmp), grainBmp?.let { grainFromImage(it) }, meta, stamp = false))
            shapeBmp.recycle()
            grainBmp?.recycle()
        }

        // 2) nested brush files (e.g. a .zip full of .brushset files)
        for ((name, data) in entries) {
            val l = name.lowercase()
            if (l.endsWith(".brush") || l.endsWith(".brushset") || l.endsWith(".zip")) {
                collect(ctx, data, name.substringAfterLast('/'), out, depth + 1)
            }
        }

        // 3) nothing Procreate-like: every image is a stamp
        if (shapes.isEmpty()) {
            for ((name, data) in entries) {
                val l = name.lowercase()
                if (!(l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".webp"))) continue
                if (l.endsWith("grain.png") || l.contains("thumbnail")) continue
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: continue
                out.add(save(ctx, baseName(name) ?: "Stamp", tipFromImage(bmp), null, emptyMap(), stamp = true))
                bmp.recycle()
            }
        }
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

    /** Grain: white = paint shows, black = paper shows. */
    fun grainFromImage(src: Bitmap): Bitmap {
        val bmp = scaled(src.copy(Bitmap.Config.ARGB_8888, false), 512)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val a = ByteArray(w * h) { i ->
            val alpha = (px[i] ushr 24) / 255f
            (luminance(px[i]) * alpha * 255).roundToInt().coerceIn(0, 255).toByte()
        }
        return Brushes.alphaBitmap(a, w, h)
    }

    // =========================================================
    // storage
    // =========================================================

    private fun num(meta: Map<String, Any?>, key: String): Float? = (meta[key] as? Number)?.toFloat()

    private fun save(ctx: Context, name: String, tip: Bitmap, grain: Bitmap?, meta: Map<String, Any?>, stamp: Boolean): BrushPreset {
        val id = UUID.randomUUID().toString()
        val dir = File(root(ctx), id).apply { mkdirs() }
        // Procreate keeps spacing/jitter as 0..1 fractions
        val spacing = num(meta, "plotSpacing")?.takeIf { it > 0f && it <= 2f }?.coerceAtLeast(0.02f)
            ?: if (stamp) 0.25f else 0.08f
        val scatter = num(meta, "plotJitter")?.takeIf { it in 0f..3f } ?: 0f
        val json = JSONObject().apply {
            put("name", name)
            put("spacing", spacing.toDouble())
            put("scatter", scatter.toDouble())
            put("stamp", stamp)
        }
        File(dir, "brush.json").writeText(json.toString(2))
        writeAlpha(tip, File(dir, "shape.png"))
        if (grain != null) writeAlpha(grain, File(dir, "grain.png"))
        return makePreset(id, json, tip, grain)
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

    private fun makePreset(id: String, json: JSONObject, tip: Bitmap, grain: Bitmap?): BrushPreset {
        val stamp = json.optBoolean("stamp", false)
        return BrushPreset(
            name = json.optString("name", "Imported"),
            category = "Imported",
            tip = TipShape.CUSTOM,
            spacing = json.optDouble("spacing", 0.1).toFloat(),
            minSize = if (stamp) 0.8f else 0.4f,
            minFlow = if (stamp) 0.8f else 0.3f,
            flow = 1f,
            streamline = 0.25f,
            defaultSize = if (stamp) 80f else 40f,
            scatter = json.optDouble("scatter", 0.0).toFloat(),
            grain = if (grain != null) GrainType.CUSTOM else GrainType.NONE,
            id = "user:$id",
            customTip = tip,
            customGrain = grain
        )
    }

    fun loadAll(ctx: Context): List<BrushPreset> {
        val dirs = root(ctx).listFiles()?.filter { it.isDirectory }?.sortedBy { it.lastModified() } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            runCatching {
                val json = JSONObject(File(dir, "brush.json").readText())
                val tip = readAlpha(File(dir, "shape.png")) ?: return@runCatching null
                makePreset(dir.name, json, tip, readAlpha(File(dir, "grain.png")))
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
    fun download(link: String): Pair<ByteArray, String?> {
        var url = URL(directLink(link))
        var redirects = 0
        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "Zerin/0.9 (Android)")
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
