package com.abdulmedia.inkora

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One page of a PDF as a picture: you pick the page, it goes into the drawing (or becomes a new
 * artwork) to draw on top of. Inkora does not keep the PDF.
 */
object PdfPage {

    /** an open PDF; every call runs on [exec], one at a time (the PDF reader can't do two at once) */
    private class Pdf(val renderer: PdfRenderer, val fd: ParcelFileDescriptor, val tmp: File?) {
        val count get() = renderer.pageCount
        fun close() { runCatching { renderer.close() }; runCatching { fd.close() }; tmp?.delete() }

        /** page [i] fitted inside [maxW] × [maxH] (made bigger when the page is small), on white */
        fun render(i: Int, maxW: Int, maxH: Int): Bitmap = renderer.openPage(i).use { p ->
            val s = min(maxW / p.width.toFloat(), maxH / p.height.toFloat())
            val w = (p.width * s).roundToInt().coerceAtLeast(1)
            val h = (p.height * s).roundToInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            b
        }

        /** the page's own size at 300 dots per inch, at most about 24 million pixels */
        fun printSize(i: Int): Pair<Int, Int> = renderer.openPage(i).use { p ->
            var w = p.width * 300f / 72f; var h = p.height * 300f / 72f
            val k = sqrt(24_000_000f / (w * h))
            if (k < 1f) { w *= k; h *= k }
            w.roundToInt().coerceAtLeast(1) to h.roundToInt().coerceAtLeast(1)
        }
    }

    private fun open(a: Activity, uri: Uri): Pdf {
        // straight from the file when the app that has it allows it, otherwise from a copy
        val direct = runCatching { a.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (direct != null) {
            val r = runCatching { PdfRenderer(direct) }
            if (r.isSuccess) return Pdf(r.getOrThrow(), direct, null)
            runCatching { direct.close() }
            r.exceptionOrNull()?.let { if (it is SecurityException) throw it }
        }
        val tmp = File(a.cacheDir, "pdf-page.pdf")
        a.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            ?: throw IllegalArgumentException("Could not open that file")
        val fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        return try { Pdf(PdfRenderer(fd), fd, tmp) } catch (e: Throwable) { fd.close(); tmp.delete(); throw e }
    }

    /**
     * Opens the PDF at [uri] and lets you pick a page. [size] gives the size to draw the picked page
     * at, from the page's own size at print quality (width, height) → (width, height) to fit inside.
     * [onPicked] gets the page (page number counted from 1).
     */
    fun pick(a: Activity, uri: Uri, size: (Int, Int) -> Pair<Int, Int>, onPicked: (Bitmap, Int) -> Unit) {
        val exec = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        exec.execute {
            val pdf = try { open(a, uri) } catch (e: SecurityException) {
                main.post { Ui.toast(a, "This PDF is locked with a password") }; exec.shutdown(); return@execute
            } catch (e: Throwable) {
                main.post { Ui.toast(a, "This is not a PDF file Inkora can read") }; exec.shutdown(); return@execute
            }
            if (pdf.count <= 0) { pdf.close(); main.post { Ui.toast(a, "This PDF has no pages") }; exec.shutdown(); return@execute }
            main.post {
                if (a.isFinishing || a.isDestroyed) { exec.execute { pdf.close() }; exec.shutdown(); return@post }
                showPicker(a, pdf, exec, main, size, onPicked)
            }
        }
    }

    private fun showPicker(a: Activity, pdf: Pdf, exec: java.util.concurrent.ExecutorService, main: Handler,
                           size: (Int, Int) -> Pair<Int, Int>, onPicked: (Bitmap, Int) -> Unit) {
        val count = pdf.count
        var page = 0
        var shown = -1
        var picked = false
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp, 12.dp, 20.dp, 0) }
        val preview = ImageView(a).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = Ui.rounded(Ui.CARD, 10f)
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        }
        val previewH = min(420.dp, (a.resources.displayMetrics.heightPixels * 0.5f).toInt())
        box.addView(preview, LinearLayout.LayoutParams(-1, previewH))
        val label = Ui.text(a, "", 14f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }
        val slider = SeekBar(a).apply {
            max = (count - 1).coerceAtLeast(0)
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT)
            visibility = if (count > 1) android.view.View.VISIBLE else android.view.View.GONE
        }
        fun label() { label.text = String.format(L10n.tr("Page %1\$d of %2\$d"), page + 1, count) }
        fun show() {
            label()
            val want = page
            val pw = preview.width.takeIf { it > 0 } ?: (a.resources.displayMetrics.widthPixels * 0.7f).toInt()
            exec.execute {
                if (want != page || picked) return@execute
                val b = runCatching { pdf.render(want, pw, previewH) }.getOrNull()
                main.post {
                    if (b == null || want != page || picked) { b?.recycle(); return@post }
                    preview.setImageBitmap(b)
                    shown = want
                }
            }
        }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val prev = Ui.button(a, "‹") { if (page > 0) { page--; slider.progress = page; show() } }
        val next = Ui.button(a, "›") { if (page < count - 1) { page++; slider.progress = page; show() } }
        row.addView(prev, LinearLayout.LayoutParams(52.dp, 44.dp))
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(next, LinearLayout.LayoutParams(52.dp, 44.dp))
        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { page = p; label() } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { show() }
        })
        box.addView(slider, LinearLayout.LayoutParams(-1, 40.dp))

        val dialog = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Pick a page"))
            .setView(box)
            .setPositiveButton(L10n.tr("Use this page"), null)
            .setNegativeButton(L10n.tr("Cancel"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                picked = true
                val p = page
                dialog.dismiss()
                Ui.toast(a, "Opening…")
                exec.execute {
                    val b = runCatching {
                        val (pw, ph) = pdf.printSize(p)
                        val (w, h) = size(pw, ph)
                        pdf.render(p, max(1, w), max(1, h))
                    }.getOrNull()
                    pdf.close()
                    main.post {
                        if (b == null) Ui.toast(a, "Not enough memory for this right now")
                        else if (!a.isFinishing && !a.isDestroyed) onPicked(b, p + 1) else b.recycle()
                    }
                }
                exec.shutdown()
            }
        }
        dialog.setOnDismissListener { if (!picked) { exec.execute { pdf.close() }; exec.shutdown() } }
        dialog.show()
        preview.post { show() }
    }
}
