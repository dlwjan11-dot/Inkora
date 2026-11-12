package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Books: PDF files to read inside Inkora. Each one is copied into the app (so it opens again later
 * even if the original moves) with a small note of where you stopped.
 */
object Books {
    class Book(val id: String, val title: String, val page: Int, val pages: Int, val rtl: Boolean, val opened: Long)

    fun dir(ctx: Context): File = File(ctx.filesDir, "books").apply { mkdirs() }

    private fun meta(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    fun cover(ctx: Context, id: String) = File(dir(ctx), "$id.png")
    fun file(ctx: Context, id: String) = File(dir(ctx), "$id.pdf")

    fun list(ctx: Context): List<Book> {
        val d = dir(ctx)
        return (d.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
            runCatching {
                val id = f.name.removeSuffix(".json")
                if (!File(d, "$id.pdf").exists()) return@runCatching null
                val o = JSONObject(f.readText())
                Book(id, o.optString("title", "Book"), o.optInt("page", 0), o.optInt("pages", 1), o.optBoolean("rtl", false), o.optLong("opened", f.lastModified()))
            }.getOrNull()
        }.sortedByDescending { it.opened }
    }

    fun get(ctx: Context, id: String): Book? = list(ctx).firstOrNull { it.id == id }

    /** copies the PDF at [uri] in, with its first page as the cover. Background thread. Returns the book's id. */
    fun add(ctx: Context, uri: Uri, name: String?): String {
        val id = "b" + System.currentTimeMillis()
        val dst = file(ctx, id)
        val tmp = File(dir(ctx), "$id.tmp")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                ?: throw IllegalArgumentException("Could not open that file")
            // only a real PDF stays
            val pages = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    if (r.pageCount > 0) r.openPage(0).use { p ->
                        val w = 360; val h = (360f * p.height / p.width.coerceAtLeast(1)).roundToInt().coerceIn(1, 1080)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        runCatching { cover(ctx, id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) } }
                        bmp.recycle()
                    }
                    r.pageCount
                }
            }
            if (pages <= 0) throw IllegalArgumentException("This PDF has no pages")
            if (!tmp.renameTo(dst)) { tmp.copyTo(dst, true); tmp.delete() }
            val title = name?.substringBeforeLast('.')?.ifBlank { null } ?: "Book"
            meta(ctx, id).writeText(JSONObject().put("title", title).put("page", 0).put("pages", pages)
                .put("rtl", false).put("opened", System.currentTimeMillis()).toString())
            return id
        } catch (e: SecurityException) {
            tmp.delete(); throw IllegalArgumentException("This PDF is locked with a password")
        } catch (e: java.io.IOException) {
            tmp.delete(); throw IllegalArgumentException("This is not a PDF file Inkora can read")
        } catch (e: Throwable) {
            tmp.delete(); throw e
        }
    }

    /** where you stopped (and which way the pages turn) */
    fun save(ctx: Context, id: String, page: Int, rtl: Boolean) {
        val f = meta(ctx, id)
        val o = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        o.put("page", page).put("rtl", rtl).put("opened", System.currentTimeMillis())
        runCatching { f.writeText(o.toString()) }
    }

    fun rename(ctx: Context, id: String, title: String) {
        val f = meta(ctx, id)
        val o = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        runCatching { f.writeText(o.put("title", title).toString()) }
    }

    fun delete(ctx: Context, id: String) {
        file(ctx, id).delete(); meta(ctx, id).delete(); cover(ctx, id).delete()
    }
}

/** The bookshelf: every book with its cover and where you stopped; + adds a PDF. */
@SuppressLint("ViewConstructor")
class BooksView(
    context: Context,
    private val onAdd: () -> Unit,
    private val onOpen: (String) -> Unit,
    private val onRename: (Books.Book) -> Unit,
    private val onDelete: (Books.Book) -> Unit,
    private val onClose: () -> Unit
) : FrameLayout(context) {
    private val grid = GridLayout(context)
    private val empty = Ui.text(context, "No books yet. Add a PDF to read it here; Inkora remembers the page you stopped on.", 14f, Ui.TEXT_DIM).apply {
        gravity = Gravity.CENTER; setPadding(32.dp, 60.dp, 32.dp, 0)
    }

    init {
        tag = "books"
        setBackgroundColor(Ui.SURFACE)
        isClickable = true
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val phone = context.narrow
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (phone) setPadding(12.dp, 16.dp, 12.dp, 8.dp) else setPadding(28.dp, 26.dp, 32.dp, 10.dp)
        }
        head.addView(IconView(context, Icon.CHEV_LEFT).apply {
            iconScale = 0.5f; contentDescription = L10n.tr("Back")
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(44.dp, 44.dp))
        head.addView(Ui.text(context, "Books", 28f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 6.dp })
        head.addView(Ui.text(context, "＋  " + L10n.tr("Add a PDF"), 14f, Ui.ON_ACCENT, true).apply {
            gravity = Gravity.CENTER
            setPadding(18.dp, 0, 18.dp, 0)
            background = Ui.rounded(Ui.ACCENT, 12f)
            foreground = Ui.ripple(12f)
            setOnClickListener { onAdd() }
        }, LinearLayout.LayoutParams(-2, 44.dp))
        col.addView(head)
        val scroll = ScrollView(context)
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(empty)
        inner.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { if (phone) setMargins(12.dp, 4.dp, 12.dp, 24.dp) else setMargins(28.dp, 8.dp, 28.dp, 32.dp) })
        scroll.addView(inner)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(col)
        refresh()
    }

    fun refresh() {
        grid.removeAllViews()
        val books = Books.list(context)
        empty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        val wDp = resources.configuration.screenWidthDp
        val cols = max(2, (wDp - 56) / 170)
        grid.columnCount = cols
        val cellW = ((resources.displayMetrics.widthPixels - (if (context.narrow) 24.dp else 56.dp)) / cols)
        for (b in books) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8.dp, 8.dp, 8.dp, 12.dp)
                foreground = Ui.ripple(14f)
                setOnClickListener { onOpen(b.id) }
                setOnLongClickListener { v ->
                    val pm = InkMenu(context, v)
                    pm.menu.add(0, 1, 1, "Rename")
                    pm.menu.add(0, 2, 2, "Delete")
                    pm.setOnMenuItemClickListener { if (it.itemId == 1) onRename(b) else onDelete(b); true }
                    L10n.menu(pm.menu); pm.show(); true
                }
            }
            val img = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.rounded(Ui.CARD, 10f, Ui.OUTLINE, 1)
                clipToOutline = true
                runCatching { BitmapFactory.decodeFile(Books.cover(context, b.id).path) }.getOrNull()?.let { setImageBitmap(it) }
            }
            card.addView(img, LinearLayout.LayoutParams(-1, ((cellW - 16.dp) * 1.35f).toInt()))
            card.addView(Ui.text(context, b.title, 14f, Ui.TEXT, true).apply { maxLines = 2; setPadding(2.dp, 8.dp, 2.dp, 0) })
            val where = if (b.page <= 0) L10n.tr("Not started") else String.format(L10n.tr("Page %1\$d of %2\$d"), b.page + 1, b.pages)
            card.addView(Ui.text(context, where, 12f, Ui.TEXT_DIM).apply { setPadding(2.dp, 2.dp, 2.dp, 0) })
            // how far you are
            card.addView(View(context).apply {
                background = Ui.rounded(Ui.TRACK, 2f)
                foreground = android.graphics.drawable.ClipDrawable(Ui.rounded(Ui.ACCENT, 2f), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
                    .apply { level = (10000f * (b.page + 1) / b.pages.coerceAtLeast(1)).toInt() }
            }, LinearLayout.LayoutParams(-1, 4.dp).apply { topMargin = 6.dp })
            grid.addView(card, GridLayout.LayoutParams().apply { width = cellW; height = -2 })
        }
    }
}

/**
 * Reading a book: one page at a time. Swipe or tap the edges to turn pages, pinch or double-tap
 * to zoom, tap the middle to show or hide the bars. The page you are on is kept.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ReaderView(
    context: Context,
    private val bookId: String,
    private val onClose: () -> Unit,
    /** the page you see, as a picture, becomes a new artwork to draw on */
    private val onDrawOn: (Bitmap, String) -> Unit
) : FrameLayout(context) {

    private val book = Books.get(context, bookId)
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var count = 1
    var page = 0
        private set
    private var rtl = book?.rtl ?: false
    /** the pages drawn so far, at most about 90 MB of them */
    private val cache = object : LruCache<Int, Bitmap>(90 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val pageView = PageView()
    private val top = LinearLayout(context)
    private val bottom = LinearLayout(context)
    private val pageLabel = Ui.text(context, "", 13f, Ui.TEXT_DIM)
    private val slider = SeekBar(context)
    private var barsShown = true
    @Volatile private var closed = false

    init {
        tag = "reader"
        setBackgroundColor(if (Ui.light) 0xFFE8E8EA.toInt() else 0xFF101012.toInt())
        isClickable = true
        addView(pageView, LayoutParams(-1, -1))

        top.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 8.dp, 12.dp, 8.dp)
            background = Ui.rounded(Ui.BAR, 0f)
            elevation = 6f.dp
        }
        top.addView(IconView(context, Icon.CHEV_LEFT).apply {
            iconScale = 0.5f; contentDescription = L10n.tr("Back")
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(44.dp, 44.dp))
        top.addView(Ui.text(context, book?.title ?: "", 15f, Ui.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 6.dp })
        top.addView(pageLabel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 10.dp })
        val dirBtn = Ui.button(context, "") {}
        fun dirText() { dirBtn.text = L10n.tr(if (rtl) "Pages: right to left" else "Pages: left to right") }
        dirText()
        dirBtn.textSize = 12.5f
        dirBtn.setOnClickListener { rtl = !rtl; dirText(); slider.layoutDirection = if (rtl) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR; keep() }
        top.addView(dirBtn, LinearLayout.LayoutParams(-2, 40.dp).apply { marginEnd = 8.dp })
        top.addView(Ui.button(context, "Draw on this page") {
            val bmp = cache.get(page) ?: return@button
            runCatching { bmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()?.let { onDrawOn(it, (book?.title ?: "Book") + " · " + (page + 1)) }
        }.apply { textSize = 12.5f }, LinearLayout.LayoutParams(-2, 40.dp))
        addView(top, LayoutParams(-1, -2, Gravity.TOP))

        bottom.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 6.dp, 16.dp, 10.dp)
            background = Ui.rounded(Ui.BAR, 0f)
            elevation = 6f.dp
        }
        slider.apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT)
            layoutDirection = if (rtl) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { pageLabel.text = label(p) } }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { go(progress) }
            })
        }
        bottom.addView(slider, LinearLayout.LayoutParams(0, 40.dp, 1f))
        addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM))

        exec.execute {
            val r = runCatching {
                val f = Books.file(context, bookId)
                val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                fd = d
                PdfRenderer(d)
            }.getOrNull()
            main.post {
                if (closed) return@post
                if (r == null) { Ui.toast(context, "Could not open this book"); onClose(); return@post }
                renderer = r
                count = r.pageCount.coerceAtLeast(1)
                slider.max = (count - 1).coerceAtLeast(0)
                go((book?.page ?: 0).coerceIn(0, count - 1))
            }
        }
    }

    private fun label(p: Int) = String.format(L10n.tr("Page %1\$d of %2\$d"), p + 1, count)

    private fun keep() = Books.save(context, bookId, page, rtl)

    fun go(p: Int) {
        page = p.coerceIn(0, count - 1)
        pageLabel.text = label(page)
        slider.progress = page
        pageView.resetZoom()
        pageView.invalidate()
        keep()
        need(page)
        // the pages around it, so turning is instant
        need(page + 1); need(page - 1)
    }

    fun next() { if (page < count - 1) go(page + 1) }
    fun prev() { if (page > 0) go(page - 1) }

    private val pending = HashSet<Int>()

    /** renders page [p] (a bit bigger than it shows, so zooming stays sharp) */
    private fun need(p: Int) {
        if (closed || p < 0 || p >= count || cache.get(p) != null || p in pending) return
        val vw = max(width, resources.displayMetrics.widthPixels)
        val vh = max(height, resources.displayMetrics.heightPixels)
        pending.add(p)
        exec.execute {
            val bmp = runCatching {
                val r = renderer ?: return@runCatching null
                if (closed) return@runCatching null
                r.openPage(p).use { pg ->
                    val fit = min(vw / pg.width.toFloat(), vh / pg.height.toFloat())
                    var s = fit * 1.6f
                    // at most about 3200 pixels on the long side
                    s = min(s, 3200f / max(pg.width, pg.height))
                    val w = (pg.width * s).roundToInt().coerceAtLeast(1)
                    val h = (pg.height * s).roundToInt().coerceAtLeast(1)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                }
            }.getOrNull()
            main.post {
                pending.remove(p)
                if (closed) { bmp?.recycle(); return@post }
                if (bmp != null) { cache.put(p, bmp); if (p == page) pageView.invalidate() }
            }
        }
    }

    private fun toggleBars() {
        barsShown = !barsShown
        val v = if (barsShown) View.VISIBLE else View.GONE
        top.visibility = v; bottom.visibility = v
    }

    fun close() {
        if (closed) return
        closed = true
        keep()
        exec.execute {
            runCatching { renderer?.close() }
            runCatching { fd?.close() }
        }
        exec.shutdown()
        // the bitmaps are let go with the view (a page may still be on screen until it is removed)
        cache.evictAll()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        close()
    }

    /** the page, fitted to the screen, with zoom and turning */
    private inner class PageView : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val shadow = Paint().apply { color = 0x33000000 }
        private var zoom = 1f
        private var panX = 0f
        private var panY = 0f
        private val m = Matrix()
        private val dst = RectF()

        fun resetZoom() { zoom = 1f; panX = 0f; panY = 0f }

        private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val nz = (zoom * d.scaleFactor).coerceIn(1f, 5f)
                val k = nz / zoom
                // zoom around the fingers
                panX = (panX - (d.focusX - width / 2f)) * k + (d.focusX - width / 2f)
                panY = (panY - (d.focusY - height / 2f)) * k + (d.focusY - height / 2f)
                zoom = nz
                clampPan(); invalidate(); return true
            }
        })

        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (zoom > 1.01f) { toggleBars(); return true }
                val third = width / 3f
                when {
                    e.x < third -> if (rtl) next() else prev()
                    e.x > width - third -> if (rtl) prev() else next()
                    else -> toggleBars()
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1.01f) resetZoom() else {
                    zoom = 2.5f
                    panX = -(e.x - width / 2f) * 1.5f; panY = -(e.y - height / 2f) * 1.5f
                    clampPan()
                }
                invalidate(); return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (zoom <= 1.01f) return false
                panX -= dx; panY -= dy; clampPan(); invalidate(); return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (zoom > 1.01f || e1 == null) return false
                val dx = e2.x - e1.x
                if (abs(dx) < 60.dp || abs(dx) < abs(e2.y - e1.y)) return false
                // a swipe to the left goes on (the other way in a right-to-left book)
                if ((dx < 0) != rtl) next() else prev()
                return true
            }
        })

        private fun fitted(b: Bitmap): RectF {
            val s = min(width / b.width.toFloat(), height / b.height.toFloat()) * 0.98f
            val w = b.width * s * zoom; val h = b.height * s * zoom
            val cx = width / 2f + panX; val cy = height / 2f + panY
            return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        }

        private fun clampPan() {
            val b = cache.get(page) ?: return
            val s = min(width / b.width.toFloat(), height / b.height.toFloat()) * 0.98f
            val w = b.width * s * zoom; val h = b.height * s * zoom
            val mx = max(0f, (w - width) / 2f); val my = max(0f, (h - height) / 2f)
            panX = panX.coerceIn(-mx, mx); panY = panY.coerceIn(-my, my)
        }

        override fun onDraw(c: Canvas) {
            val b = cache.get(page)
            if (b == null || b.isRecycled) {
                paint.color = Ui.TEXT_DIM
                paint.textSize = 15f.dp
                paint.textAlign = Paint.Align.CENTER
                c.drawText(L10n.tr("Opening…"), width / 2f, height / 2f, paint)
                return
            }
            dst.set(fitted(b))
            c.drawRect(dst.left + 2.dp, dst.top + 3.dp, dst.right + 2.dp, dst.bottom + 3.dp, shadow)
            c.drawBitmap(b, null, dst, paint)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            // another size (turned the tablet): the pages are drawn again for it
            if (ow != 0 && (abs(w - ow) > 40 || abs(h - oh) > 40)) { cache.evictAll(); need(page) }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            scale.onTouchEvent(e)
            if (!scale.isInProgress) gestures.onTouchEvent(e)
            return true
        }
    }
}
