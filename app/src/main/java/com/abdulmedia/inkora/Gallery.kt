package com.abdulmedia.inkora

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.min

/**
 * The artwork gallery: every artwork is <id>.inkora + <id>.png (thumbnail) + <id>.json (title, size, date)
 * inside the app's private storage.
 */
object Gallery {

    class Entry(
        val id: String, val title: String, val width: Int, val height: Int,
        val modified: Long, val created: Long, val origin: String, val thumb: File,
        /** the stack it is in, if any */
        val stack: String? = null
    ) {
        val imported get() = origin == "imported"
    }

    fun dir(ctx: Context) = File(ctx.filesDir, "gallery").apply { mkdirs() }
    private fun projectFile(ctx: Context, id: String) = File(dir(ctx), "$id.inkora")
    private fun thumbFile(ctx: Context, id: String) = File(dir(ctx), "$id.png")
    private fun metaFile(ctx: Context, id: String) = File(dir(ctx), "$id.json")

    private fun refFile(ctx: Context, id: String) = File(dir(ctx), "$id.ref.webp")
    private fun snapDir(ctx: Context, id: String) = File(dir(ctx), "$id.snaps")

    fun newId(): String = UUID.randomUUID().toString()

    // ---- the reference photo of an artwork ----

    /** Keeps (or with null forgets) the reference photo of an artwork. Background thread. */
    fun saveRef(ctx: Context, id: String, bmp: Bitmap?) {
        val f = refFile(ctx, id)
        if (bmp == null) { f.delete(); return }
        val tmp = File(dir(ctx), "$id.ref.tmp")
        runCatching {
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, it) }
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }.onFailure { tmp.delete() }
    }

    fun loadRef(ctx: Context, id: String): Bitmap? {
        val f = refFile(ctx, id)
        if (!f.exists()) return null
        return try { android.graphics.BitmapFactory.decodeFile(f.path) } catch (e: OutOfMemoryError) { null }
    }

    // ---- snapshots: earlier states of an artwork you can go back to ----

    class Snap(val time: Long, val file: File, val thumb: File, val auto: Boolean)

    const val MAX_SNAPS = 12

    fun snapshots(ctx: Context, id: String): List<Snap> {
        val d = snapDir(ctx, id)
        return (d.listFiles() ?: emptyArray()).filter { it.name.endsWith(".inkora") }.mapNotNull { f ->
            val stem = f.name.removeSuffix(".inkora")
            val auto = stem.endsWith("a")
            val t = stem.removeSuffix("a").toLongOrNull() ?: return@mapNotNull null
            Snap(t, f, File(d, "$stem.png"), auto)
        }.sortedByDescending { it.time }
    }

    /** Writes a snapshot; the oldest ones go when there are more than [MAX_SNAPS]. Background thread. */
    fun saveSnapshot(ctx: Context, id: String, snap: ProjectIO.Snapshot, thumb: Bitmap?, auto: Boolean) {
        val d = snapDir(ctx, id).apply { mkdirs() }
        val stem = System.currentTimeMillis().toString() + if (auto) "a" else ""
        val tmp = File(d, "$stem.tmp")
        try {
            tmp.outputStream().use { ProjectIO.write(snap, it) }
            if (!tmp.renameTo(File(d, "$stem.inkora"))) throw java.io.IOException("rename")
        } catch (e: Throwable) { tmp.delete(); throw e }
        if (thumb != null) runCatching { File(d, "$stem.png").outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 90, it) } }
        snapshots(ctx, id).drop(MAX_SNAPS).forEach { deleteSnapshot(it) }
    }

    fun loadSnapshot(s: Snap): Document? = runCatching { s.file.inputStream().use { ProjectIO.read(it) } }.getOrNull()

    fun deleteSnapshot(s: Snap) { s.file.delete(); s.thumb.delete() }

    fun list(ctx: Context): List<Entry> {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching {
                val id = f.name.removeSuffix(".json")
                if (!projectFile(ctx, id).exists()) return@runCatching null
                val o = JSONObject(f.readText())
                val modified = o.optLong("modified", f.lastModified())
                Entry(id, o.optString("title", "Untitled"), o.optInt("width"), o.optInt("height"),
                    modified, o.optLong("created", modified), o.optString("origin", "drawn"), thumbFile(ctx, id),
                    o.optString("stack", "").ifBlank { null })
            }.getOrNull()
        }.sortedByDescending { it.modified }
    }

    fun exists(ctx: Context, id: String) = projectFile(ctx, id).exists()

    /** Small preview, made on the main thread (fast: scaled drawing). */
    fun makeThumb(doc: Document): Bitmap {
        val s = min(480f / doc.width, 480f / doc.height)
        val w = (doc.width * s).toInt().coerceAtLeast(1)
        val h = (doc.height * s).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFFFFFFFF.toInt())
        c.scale(s, s)
        doc.draw(c, true, screen = false)
        return b
    }

    /** Writes an artwork. Call on a background thread with a snapshot. */
    fun save(ctx: Context, id: String, title: String, snap: ProjectIO.Snapshot, thumb: Bitmap?, origin: String = "drawn") {
        val tmp = File(dir(ctx), "$id.tmp")
        tmp.outputStream().use { ProjectIO.write(snap, it) }
        val dst = projectFile(ctx, id)
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
        if (thumb != null) thumbFile(ctx, id).outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 90, it) }
        writeMeta(ctx, id, title, snap.width, snap.height, origin)
    }

    private fun writeMeta(ctx: Context, id: String, title: String, w: Int, h: Int, origin: String) {
        val f = metaFile(ctx, id)
        val old = runCatching { JSONObject(f.readText()) }.getOrNull()
        val now = System.currentTimeMillis()
        f.writeText(JSONObject().apply {
            put("title", title)
            put("width", w)
            put("height", h)
            put("modified", now)
            put("created", old?.optLong("created", now) ?: now)
            put("origin", old?.optString("origin", origin) ?: origin)
            old?.optString("stack", "")?.takeIf { it.isNotBlank() }?.let { put("stack", it) }
        }.toString())
    }

    fun load(ctx: Context, id: String): Document? {
        val f = projectFile(ctx, id)
        if (!f.exists()) return null
        val doc = runCatching { f.inputStream().use { ProjectIO.read(it) } }.getOrNull() ?: return null
        doc.galleryId = id
        val meta = runCatching { JSONObject(metaFile(ctx, id).readText()) }.getOrNull()
        doc.title = meta?.optString("title", "Untitled") ?: "Untitled"
        doc.origin = meta?.optString("origin", "drawn") ?: "drawn"
        return doc
    }

    fun rename(ctx: Context, id: String, title: String) {
        val f = metaFile(ctx, id)
        val o = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        o.put("title", title)
        f.writeText(o.toString())
    }

    /** Puts an artwork in the stack [name], or takes it out ([name] null). */
    fun setStack(ctx: Context, id: String, name: String?) {
        val f = metaFile(ctx, id)
        val o = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        if (name.isNullOrBlank()) o.remove("stack") else o.put("stack", name.trim())
        f.writeText(o.toString())
    }

    /** Renames a stack (every artwork in it follows); [to] null unstacks them all. */
    fun renameStack(ctx: Context, from: String, to: String?) {
        for (e in list(ctx)) if (e.stack == from) setStack(ctx, e.id, to)
    }

    fun duplicate(ctx: Context, id: String): String? {
        val src = projectFile(ctx, id)
        if (!src.exists()) return null
        val nid = newId()
        src.copyTo(projectFile(ctx, nid))
        thumbFile(ctx, id).takeIf { it.exists() }?.copyTo(thumbFile(ctx, nid))
        val o = runCatching { JSONObject(metaFile(ctx, id).readText()) }.getOrDefault(JSONObject())
        o.put("title", o.optString("title", "Untitled") + " copy")
        o.put("modified", System.currentTimeMillis())
        o.put("created", System.currentTimeMillis())
        metaFile(ctx, nid).writeText(o.toString())
        refFile(ctx, id).takeIf { it.exists() }?.let { runCatching { it.copyTo(refFile(ctx, nid)) } }
        return nid
    }

    fun delete(ctx: Context, id: String) {
        projectFile(ctx, id).delete()
        thumbFile(ctx, id).delete()
        metaFile(ctx, id).delete()
        refFile(ctx, id).delete()
        snapDir(ctx, id).deleteRecursively()
        TimeLapse.clear(ctx, id)
    }

    /** Older versions kept a single autosave: move it into the gallery once. */
    fun migrateAutosave(ctx: Context): String? {
        val old = ProjectIO.autosaveFile(ctx)
        if (!old.exists()) return null
        val id = newId()
        old.copyTo(projectFile(ctx, id), overwrite = true)
        val doc = runCatching { load(ctx, id) }.getOrNull()
        if (doc == null) {
            projectFile(ctx, id).delete()
            old.delete()
            return null
        }
        thumbFile(ctx, id).outputStream().use { makeThumb(doc).compress(Bitmap.CompressFormat.PNG, 90, it) }
        writeMeta(ctx, id, "My first artwork", doc.width, doc.height, "drawn")
        old.delete()
        return id
    }
}
