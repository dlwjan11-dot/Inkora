package com.abdulmedia.inkora

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * .inkora project file = a zip containing document.json + one PNG per layer.
 */
object ProjectIO {

    /** Immutable copy of a document, safe to write on a background thread. */
    class Snapshot(
        val width: Int,
        val height: Int,
        val background: Int,
        val backgroundVisible: Boolean,
        val active: Int,
        val layers: List<LayerSnap>,
        val guide: JSONObject
    )

    fun guideToJson(g: GuideSettings) = JSONObject().apply {
        put("type", g.type.name)
        put("size", g.size.toDouble())
        put("thickness", g.thicknessDp.toDouble())
        put("opacity", g.opacity.toDouble())
        put("color", g.color)
        put("assisted", g.assisted)
        put("vps", JSONArray().apply { g.vps.forEach { put(it.toDouble()) } })
    }

    fun guideFromJson(o: JSONObject?, g: GuideSettings) {
        if (o == null) return
        g.type = runCatching { GuideType.valueOf(o.optString("type", "OFF")) }.getOrDefault(GuideType.OFF)
        g.size = o.optDouble("size", 100.0).toFloat()
        g.thicknessDp = o.optDouble("thickness", 1.2).toFloat()
        g.opacity = o.optDouble("opacity", 0.55).toFloat()
        g.color = o.optInt("color", g.color)
        g.assisted = o.optBoolean("assisted", false)
        val arr = o.optJSONArray("vps")
        if (arr != null && arr.length() == g.vps.size) {
            for (i in 0 until arr.length()) g.vps[i] = arr.getDouble(i).toFloat()
            g.version++
        }
    }

    class LayerSnap(
        val name: String, val visible: Boolean, val opacity: Float,
        val blend: String, val alphaLock: Boolean, val bitmap: Bitmap,
        val vector: String? = null,
        val clipping: Boolean = false
    )

    fun snapshot(doc: Document) = Snapshot(
        doc.width, doc.height, doc.backgroundColor, doc.backgroundVisible, doc.activeIndex,
        doc.layers.map {
            LayerSnap(it.name, it.visible, it.opacity, it.blend.name, it.alphaLock, it.bitmap.copy(Bitmap.Config.ARGB_8888, false),
                it.vector?.let { v -> VGeom.toJson(v).toString() }, it.isClippingMask)
        },
        guideToJson(doc.guide)
    )

    fun write(s: Snapshot, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            val json = JSONObject()
            json.put("app", "Zerin")
            json.put("version", 1)
            json.put("width", s.width)
            json.put("height", s.height)
            json.put("background", s.background)
            json.put("backgroundVisible", s.backgroundVisible)
            json.put("active", s.active)
            json.put("guide", s.guide)
            val arr = JSONArray()
            s.layers.forEachIndexed { i, l ->
                val file = "layer_$i.png"
                arr.put(JSONObject().apply {
                    put("name", l.name)
                    put("visible", l.visible)
                    put("opacity", l.opacity.toDouble())
                    put("blend", l.blend)
                    put("alphaLock", l.alphaLock)
                    put("clipping", l.clipping)
                    put("file", file)
                    if (l.vector != null) put("vector", JSONArray(l.vector))
                })
                zip.putNextEntry(ZipEntry(file))
                l.bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
            json.put("layers", arr)
            zip.putNextEntry(ZipEntry("document.json"))
            zip.write(json.toString(2).toByteArray())
            zip.closeEntry()
        }
    }

    fun read(input: InputStream): Document {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val bos = ByteArrayOutputStream()
                    zip.copyTo(bos)
                    files[e.name] = bos.toByteArray()
                }
                e = zip.nextEntry
            }
        }
        val jsonBytes = files["document.json"] ?: throw IllegalArgumentException("Not an Zerin project")
        val json = JSONObject(String(jsonBytes))
        val doc = Document(json.getInt("width"), json.getInt("height"))
        doc.backgroundColor = json.optInt("background", 0xFFFFFFFF.toInt())
        doc.backgroundVisible = json.optBoolean("backgroundVisible", true)
        guideFromJson(json.optJSONObject("guide"), doc.guide)
        val arr = json.getJSONArray("layers")
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val bytes = files[o.getString("file")] ?: continue
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: continue
            val bmp = if (decoded.width == doc.width && decoded.height == doc.height && decoded.isMutable) decoded
            else Bitmap.createScaledBitmap(decoded, doc.width, doc.height, true).copy(Bitmap.Config.ARGB_8888, true)
            val layer = Layer(bmp, o.optString("name", "Layer ${i + 1}"))
            layer.visible = o.optBoolean("visible", true)
            layer.opacity = o.optDouble("opacity", 1.0).toFloat()
            layer.blend = runCatching { LayerBlend.valueOf(o.optString("blend", "NORMAL")) }.getOrDefault(LayerBlend.NORMAL)
            layer.alphaLock = o.optBoolean("alphaLock", false)
            layer.isClippingMask = o.optBoolean("clipping", false)
            o.optJSONArray("vector")?.let { va ->
                val data = VGeom.fromJson(va)
                layer.vector = data
                data.render(layer.bitmap)
            }
            doc.layers.add(layer)
        }
        if (doc.layers.isEmpty()) doc.layers.add(doc.createLayer())
        doc.layerNameCounter = doc.layers.size + 1
        doc.activeIndex = json.optInt("active", 0)
        doc.clampActive()
        return doc
    }

    // ---------- autosave ----------

    fun autosaveFile(ctx: Context) = File(ctx.filesDir, "autosave.inkora")

    fun writeAutosave(ctx: Context, s: Snapshot) {
        val tmp = File(ctx.filesDir, "autosave.tmp")
        tmp.outputStream().use { write(s, it) }
        val dst = autosaveFile(ctx)
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
    }

    fun readAutosave(ctx: Context): Document? {
        val f = autosaveFile(ctx)
        if (!f.exists()) return null
        return runCatching { f.inputStream().use { read(it) } }.getOrNull()
    }

    // ---------- export to gallery ----------

    fun exportToGallery(ctx: Context, bmp: Bitmap, jpeg: Boolean): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Zerin_$stamp." + if (jpeg) "jpg" else "png")
            put(MediaStore.Images.Media.MIME_TYPE, if (jpeg) "image/jpeg" else "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Zerin")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (jpeg) bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                else bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return null
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            return null
        }
    }

    /** Loads a photo, scaled down so it is not bigger than maxW x maxH. */
    fun decodeImage(ctx: Context, uri: Uri, maxW: Int, maxH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW || bounds.outHeight / (sample * 2) >= maxH) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
