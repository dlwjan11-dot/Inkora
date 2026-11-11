package com.abdulmedia.inkora

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One file with every artwork (layers, thumbnails, reference photos, snapshots), your palettes
 * and imported brushes. Saved wherever you like (Google Drive, a USB stick, your computer) and read
 * back into any Inkora. Nothing already on the device is lost when restoring.
 */
object Backup {
    private const val MANIFEST = "inkora-backup.json"
    private const val FORMAT = 1

    class Result(val added: Int, val updated: Int, val kept: Int)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("inkora", Context.MODE_PRIVATE)

    /** how many artworks there are, and roughly how big the backup is (bytes) */
    fun size(ctx: Context, withSnaps: Boolean): Pair<Int, Long> {
        val g = Gallery.dir(ctx)
        val n = Gallery.list(ctx).size
        var bytes = 0L
        g.walkTopDown().filter { it.isFile && (withSnaps || !it.path.contains(".snaps")) }.forEach { bytes += it.length() }
        File(ctx.filesDir, "brushes").walkTopDown().filter { it.isFile }.forEach { bytes += it.length() }
        return n to bytes
    }

    /** Writes the backup. [progress] gets 0..1. Background thread. */
    fun write(ctx: Context, out: OutputStream, withSnaps: Boolean, appVersion: String, progress: (Float) -> Unit) {
        val g = Gallery.dir(ctx)
        val brushes = File(ctx.filesDir, "brushes")
        val files = ArrayList<Pair<String, File>>()
        g.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") && (withSnaps || !it.path.contains(".snaps")) }
            .forEach { files.add("gallery/" + it.relativeTo(g).invariantSeparatorsPath to it) }
        if (brushes.isDirectory) brushes.walkTopDown().filter { it.isFile }
            .forEach { files.add("brushes/" + it.relativeTo(brushes).invariantSeparatorsPath to it) }
        val total = files.sumOf { it.second.length() }.coerceAtLeast(1)
        var done = 0L
        ZipOutputStream(out.buffered(1 shl 16)).use { zip ->
            zip.setLevel(1) // artworks are compressed already: speed matters more
            val man = JSONObject()
                .put("format", FORMAT).put("app", appVersion).put("created", System.currentTimeMillis())
                .put("artworks", Gallery.list(ctx).size)
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(man.toString(2).toByteArray()); zip.closeEntry()
            prefs(ctx).getString("palettes", null)?.let {
                zip.putNextEntry(ZipEntry("palettes.json")); zip.write(it.toByteArray()); zip.closeEntry()
            }
            val buf = ByteArray(1 shl 16)
            for ((name, f) in files) {
                zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                f.inputStream().use { inp ->
                    while (true) {
                        val r = inp.read(buf)
                        if (r <= 0) break
                        zip.write(buf, 0, r)
                        done += r
                    }
                }
                zip.closeEntry()
                progress(done.toFloat() / total)
            }
        }
        prefs(ctx).edit().putLong("last_backup", System.currentTimeMillis()).apply()
    }

    /** an entry name we accept: a plain file in gallery/ (one folder deep for snapshots) or brushes/ */
    private fun safe(name: String): Boolean {
        if (name.contains("..") || name.contains('\\') || name.startsWith("/")) return false
        val parts = name.split('/')
        return parts.all { it.isNotEmpty() } && when (parts[0]) {
            "gallery" -> parts.size in 2..3
            "brushes" -> parts.size in 2..4
            else -> false
        }
    }

    /**
     * Reads a backup back. An artwork that is not here yet is added; one that is here already is
     * replaced only when the backup's copy is newer. Background thread.
     */
    fun restore(ctx: Context, input: InputStream, progress: (Int) -> Unit): Result {
        val g = Gallery.dir(ctx)
        val stage = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var sawManifest = false
        var palettes: String? = null
        try {
            ZipInputStream(input.buffered(1 shl 16)).use { zip ->
                val buf = ByteArray(1 shl 16)
                var n = 0
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    when {
                        e.name == MANIFEST -> {
                            val o = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                            if (o.optInt("format", 0) > FORMAT) throw IllegalStateException("newer")
                            sawManifest = true
                        }
                        e.name == "palettes.json" -> palettes = zip.readBytes().toString(Charsets.UTF_8)
                        safe(e.name) -> {
                            val f = File(stage, e.name)
                            f.parentFile?.mkdirs()
                            f.outputStream().use { o -> while (true) { val r = zip.read(buf); if (r <= 0) break; o.write(buf, 0, r) } }
                            if (e.time > 0) f.setLastModified(e.time)
                        }
                    }
                    progress(++n)
                }
            }
            if (!sawManifest) throw IllegalArgumentException("not a backup")

            // the artworks, one by one, by their id
            val sg = File(stage, "gallery")
            var added = 0; var updated = 0; var kept = 0
            val ids = (sg.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".inkora") }.map { it.name.removeSuffix(".inkora") }
            for (id in ids) {
                val meta = File(sg, "$id.json")
                val here = File(g, "$id.json")
                val take = when {
                    !File(g, "$id.inkora").exists() -> { added++; true }
                    modified(meta) > modified(here) -> { updated++; true }
                    else -> { kept++; false }
                }
                if (!take) continue
                (sg.listFiles() ?: emptyArray()).filter { it.name.startsWith("$id.") }.forEach { src ->
                    val dst = File(g, src.name)
                    if (src.isDirectory) { dst.deleteRecursively(); src.copyRecursively(dst, overwrite = true) }
                    else {
                        val tmp = File(g, src.name + ".tmp")
                        src.copyTo(tmp, overwrite = true)
                        if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
                    }
                }
            }
            // imported brushes: the ones not here yet
            File(stage, "brushes").takeIf { it.isDirectory }?.let { sb ->
                val bd = File(ctx.filesDir, "brushes").apply { mkdirs() }
                sb.walkTopDown().filter { it.isFile }.forEach { src ->
                    val dst = File(bd, src.relativeTo(sb).path)
                    if (!dst.exists()) { dst.parentFile?.mkdirs(); src.copyTo(dst) }
                }
            }
            palettes?.let { mergePalettes(it) }
            return Result(added, updated, kept)
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun modified(meta: File): Long =
        runCatching { JSONObject(meta.readText()).optLong("modified", meta.lastModified()) }.getOrDefault(0L)

    /** palettes from the backup that are not here (by name and colors) are added */
    private fun mergePalettes(json: String) {
        runCatching {
            val arr = JSONObject(json).getJSONArray("list")
            for (i in 0 until arr.length()) {
                val po = arr.getJSONObject(i)
                val cs = po.getJSONArray("colors")
                val colors = List(cs.length()) { cs.getLong(it).toInt() }
                val name = po.optString("name", "Palette")
                if (PaletteStore.palettes.none { it.name == name && it.colors == colors }) {
                    PaletteStore.palettes.add(Palette(name, colors.toMutableList()))
                }
            }
            PaletteStore.save()
        }
    }

    /** days since the last backup, or -1 for never */
    fun daysSince(ctx: Context): Int {
        val t = prefs(ctx).getLong("last_backup", 0L)
        return if (t == 0L) -1 else ((System.currentTimeMillis() - t) / 86_400_000L).toInt()
    }

}
