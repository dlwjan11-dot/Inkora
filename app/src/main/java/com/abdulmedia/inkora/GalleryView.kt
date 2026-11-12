package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/**
 * Home screen: the app opens here.
 * "Continue" card for the last artwork, then every artwork with filters (all / my drawings / imported) and search.
 */
@SuppressLint("ViewConstructor")
class GalleryView(
    context: Context,
    private val currentId: String?,
    private val showBack: Boolean,
    private val onOpen: (String) -> Unit,
    private val onNew: () -> Unit,
    private val onImport: (View) -> Unit,
    private val onRename: (Gallery.Entry) -> Unit,
    private val onDuplicate: (Gallery.Entry) -> Unit,
    private val onDelete: (Gallery.Entry) -> Unit,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    private enum class Filter(val label: String) { ALL("All"), DRAWN("My drawings"), IMPORTED("Imported") }

    companion object {
        /** decoded thumbnails, by file and the time it was written (about 40 MB at most) */
        private val thumbCache = object : android.util.LruCache<String, android.graphics.Bitmap>(40 * 1024 * 1024) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
        }
    }

    private var filter = Filter.ALL
    private var query = ""
    /** the stack being looked into (null: the whole gallery) */
    private var openStack: String? = null
    private val content = LinearLayout(context)
    private val chips = LinearLayout(context)
    private var entries: List<Gallery.Entry> = emptyList()

    init {
        setBackgroundColor(Ui.SURFACE)
        isClickable = true // block touches to the canvas below

        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // ---- header ----
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (context.narrow) setPadding(14.dp, 16.dp, 12.dp, 4.dp) else setPadding(28.dp, 22.dp, 24.dp, 6.dp)
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(context).apply {
            text = "Inkora"
            textSize = 30f
            setTextColor(Ui.TEXT)
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)
        })
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
        titles.addView(Ui.text(context, if (context.narrow) "version $version" else "Your artworks  ·  version $version", 14f, Ui.TEXT_DIM))
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        if (showBack) head.addView(Ui.button(context, "‹ Back to canvas") { onClose() },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8.dp })
        val importBtn = Ui.button(context, "Import…") {}
        importBtn.setOnClickListener { onImport(importBtn) }
        head.addView(importBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8.dp })
        head.addView(Ui.button(context, "＋  New canvas") { onNew() }.apply {
            background = Ui.rounded(Ui.ACCENT, 12f)
            textSize = 15f
            setPadding(18.dp, 10.dp, 18.dp, 10.dp)
        })
        col.addView(head)

        // ---- filters + search ----
        val tools = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dp, 10.dp, 24.dp, 4.dp)
        }
        chips.orientation = LinearLayout.HORIZONTAL
        // the filters slide sideways when there is no room for them all (phones)
        val chipScroll = android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        val phone = context.narrow
        if (phone) { tools.orientation = LinearLayout.VERTICAL; tools.gravity = Gravity.START; tools.setPadding(14.dp, 8.dp, 14.dp, 4.dp) }
        tools.addView(chipScroll, if (phone) LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp } else LinearLayout.LayoutParams(0, -2, 1f))
        val search = EditText(context).apply {
            hint = "Search"
            setHintTextColor(Ui.TEXT_DIM)
            setTextColor(Ui.TEXT)
            textSize = 14f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            background = Ui.rounded(Ui.PANEL_ROW, 10f)
            setPadding(14.dp, 8.dp, 14.dp, 8.dp)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim() ?: ""
                    render()
                }
            })
        }
        tools.addView(search, LinearLayout.LayoutParams(if (phone) -1 else 240.dp, -2))
        col.addView(tools)

        val scroll = ScrollView(context)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(16.dp, 4.dp, 16.dp, 32.dp)
        scroll.addView(content)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(col, LayoutParams(-1, -1))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) post { render() }
    }

    /** reload from disk */
    fun refresh() {
        entries = Gallery.list(context)
        render()
    }

    private fun render() {
        if (width == 0) return
        // chips with counts
        chips.removeAllViews()
        for (f in Filter.values()) {
            val n = when (f) {
                Filter.ALL -> entries.size
                Filter.DRAWN -> entries.count { !it.imported }
                Filter.IMPORTED -> entries.count { it.imported }
            }
            val chip = Ui.button(context, "${f.label}  $n") { filter = f; render() }.apply {
                textSize = 13f
                background = Ui.rounded(if (filter == f) Ui.PANEL_ROW_ACTIVE else Ui.PANEL_ROW, 18f,
                    if (filter == f) Ui.ACCENT else 0, if (filter == f) 1 else 0)
            }
            chips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8.dp })
        }

        content.removeAllViews()
        if (entries.isEmpty()) {
            content.addView(emptyState())
            return
        }
        val stack = openStack
        if (stack != null) {
            // inside a stack: its artworks only
            val inside = entries.filter { it.stack == stack }
            if (inside.isEmpty()) { openStack = null; render(); return }
            val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(4.dp, 12.dp, 0, 4.dp) }
            bar.addView(Ui.button(context, "‹ All artworks") { openStack = null; render() }.apply { background = null; textSize = 15f })
            bar.addView(Ui.text(context, stack, 22f, bold = true).apply { setPadding(10.dp, 0, 0, 0) })
            bar.addView(Ui.text(context, "  ${inside.size}", 15f, Ui.TEXT_DIM))
            content.addView(bar)
            content.addView(gridOf(inside.map { e -> { w: Int -> card(e, w) } }))
            return
        }
        val shown = entries.filter {
            when (filter) {
                Filter.ALL -> true
                Filter.DRAWN -> !it.imported
                Filter.IMPORTED -> it.imported
            } && (query.isEmpty() || it.title.contains(query, ignoreCase = true))
        }
        shownNow = shown
        if (shown.isEmpty()) {
            content.addView(Ui.text(context, "Nothing here.", 15f, Ui.TEXT_DIM).apply { setPadding(12.dp, 24.dp, 0, 0) })
            return
        }

        var rest = shown
        val stacked = filter == Filter.ALL && query.isEmpty()
        // "Continue" hero card for the most recent artwork
        if (stacked) {
            content.addView(section("Continue"))
            content.addView(hero(shown[0]), LinearLayout.LayoutParams(-1, -2).apply { setMargins(8.dp, 0, 8.dp, 8.dp) })
            rest = shown.drop(1)
            if (rest.isNotEmpty()) content.addView(section("Recent"))
        }
        // stacks show as one card each, where their newest artwork would be
        val cards = ArrayList<(Int) -> View>()
        val seen = HashSet<String>()
        for (e in rest) {
            val st = e.stack
            if (stacked && st != null) {
                if (seen.add(st)) cards.add { w -> stackCard(st, entries.filter { it.stack == st }, w) }
            } else cards.add { w -> card(e, w) }
        }
        content.addView(gridOf(cards))
    }

    private fun gridOf(cards: List<(Int) -> View>): View {
        // on a phone: two across, whatever its width
        val cardW = if (context.narrow && width > 0) ((width - 32.dp) / 2 - 16.dp).coerceIn(120.dp, 230.dp) else 230.dp
        val cols = max(1, (width - 32.dp) / (cardW + 16.dp))
        val grid = GridLayout(context).apply { columnCount = cols }
        for (make in cards) grid.addView(make(cardW), GridLayout.LayoutParams().apply {
            width = cardW
            setMargins(8.dp, 8.dp, 8.dp, 8.dp)
        })
        return grid
    }

    /** a stack: its newest artworks fanned out, its name and how many */
    private fun stackCard(name: String, list: List<Gallery.Entry>, w: Int): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp, 8.dp, 8.dp, 10.dp)
            background = Ui.rounded(Ui.PANEL_ROW, 14f)
        }
        val frame = FrameLayout(context)
        val top = list.sortedByDescending { it.modified }.take(3)
        for ((i, e) in top.withIndex().reversed()) {
            val v = thumb(e)
            val off = (i * 9).dp
            v.rotation = if (i == 0) 0f else if (i == 1) -4f else 4f
            v.elevation = (6 - i).toFloat()
            frame.addView(v, LayoutParams(-1, -1).apply { setMargins(off, off / 2, off, 0) })
        }
        card.addView(frame, LinearLayout.LayoutParams(-1, (w * 0.7f).toInt()))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(IconView(context, Icon.FOLDER).apply { isClickable = false; tint = Ui.ACCENT }, LinearLayout.LayoutParams(22.dp, 22.dp))
        row.addView(Ui.text(context, name, 15f, bold = true).apply { maxLines = 1; setPadding(6.dp, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
        card.addView(Ui.text(context, L10n.tr("Stack") + " · ${list.size}", 11.5f, Ui.TEXT_DIM).apply { setPadding(2.dp, 2.dp, 2.dp, 0) })
        card.foreground = Ui.ripple(14f)
        card.setOnClickListener { openStack = name; render() }
        card.setOnLongClickListener {
            val pm = InkMenu(context, card)
            pm.menu.add(0, 1, 1, "Rename stack")
            pm.menu.add(0, 2, 2, "Unstack")
            pm.setOnMenuItemClickListener { item ->
                if (item.itemId == 1) askName("Rename stack", name) { n -> Gallery.renameStack(context, name, n); refresh() }
                else { Gallery.renameStack(context, name, null); refresh() }
                true
            }
            L10n.menu(pm.menu); pm.show()
            true
        }
        return card
    }

    /** a small dialog asking for a name */
    private fun askName(title: String, start: String, done: (String) -> Unit) {
        val input = EditText(context).apply { setText(start); setSingleLine(); setSelection(start.length) }
        val box = FrameLayout(context).apply { setPadding(24.dp, 8.dp, 24.dp, 0); addView(input) }
        android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr(title))
            .setView(box)
            .setPositiveButton(L10n.tr("OK")) { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    /** Move to stack: one of the stacks there are, or a new one */
    private fun moveToStack(e: Gallery.Entry) {
        val names = entries.mapNotNull { it.stack }.distinct().filter { it != e.stack }
        val items = names + L10n.tr("New stack…")
        android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Move to stack"))
            .setItems(items.toTypedArray()) { _, i ->
                if (i < names.size) { Gallery.setStack(context, e.id, names[i]); refresh() }
                else askName("New stack", L10n.tr("Stack") + " ${names.size + 1}") { n -> Gallery.setStack(context, e.id, n); refresh() }
            }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    private fun section(t: String) = Ui.text(context, t.uppercase(), 12f, Ui.TEXT_DIM, true).apply {
        setPadding(10.dp, 16.dp, 0, 6.dp)
        letterSpacing = 0.08f
    }

    private fun thumb(e: Gallery.Entry) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = Ui.rounded(Ui.SUNKEN, 10f)
        val key = e.thumb.absolutePath + ":" + e.thumb.lastModified()
        val bmp = thumbCache.get(key) ?: runCatching { BitmapFactory.decodeFile(e.thumb.absolutePath) }.getOrNull()?.also { thumbCache.put(key, it) }
        bmp?.let { setImageBitmap(it) }
    }

    private fun badge(e: Gallery.Entry): TextView = Ui.text(context, if (e.imported) "IMPORTED" else "DRAWN", 10f,
        if (e.imported) 0xFFFFC86B.toInt() else 0xFF8FD3A8.toInt(), true).apply {
        setPadding(8.dp, 3.dp, 8.dp, 3.dp)
        background = Ui.rounded(0xCC000000.toInt(), 8f)
    }

    private fun subtitle(e: Gallery.Entry): String {
        val ago = DateUtils.getRelativeTimeSpanString(e.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return "${e.width} × ${e.height} · edited $ago"
    }

    private fun hero(e: Gallery.Entry): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 12.dp, 20.dp, 12.dp)
            background = Ui.rounded(if (e.id == currentId) Ui.PANEL_ROW_ACTIVE else Ui.PANEL_ROW, 18f)
        }
        val frame = FrameLayout(context)
        frame.addView(thumb(e), LayoutParams(-1, -1))
        frame.addView(badge(e), LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(8.dp, 8.dp, 0, 0) })
        row.addView(frame, if (context.narrow) LinearLayout.LayoutParams(132.dp, 96.dp) else LinearLayout.LayoutParams(300.dp, 200.dp))
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(22.dp, 0, 0, 0) }
        info.addView(Ui.text(context, e.title, 22f, bold = true).apply { maxLines = 2 })
        info.addView(Ui.text(context, subtitle(e), 13f, Ui.TEXT_DIM).apply { setPadding(0, 6.dp, 0, 14.dp) })
        info.addView(Ui.button(context, "Continue drawing  ›") { onOpen(e.id) }.apply {
            background = Ui.rounded(Ui.ACCENT, 12f)
            textSize = 15f
        }, LinearLayout.LayoutParams(-2, -2))
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        row.foreground = Ui.ripple(18f)
        row.setOnClickListener { onOpen(e.id) }
        row.setOnLongClickListener { menu(row, e); true }
        row.tag = e.id
        return row
    }

    private fun card(e: Gallery.Entry, w: Int): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp, 8.dp, 8.dp, 10.dp)
            background = Ui.rounded(if (e.id == currentId) Ui.PANEL_ROW_ACTIVE else Ui.PANEL_ROW, 14f)
        }
        val frame = FrameLayout(context)
        frame.addView(thumb(e), LayoutParams(-1, -1))
        frame.addView(badge(e), LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(6.dp, 6.dp, 0, 0) })
        card.addView(frame, LinearLayout.LayoutParams(-1, (w * 0.7f).toInt()))
        card.addView(Ui.text(context, e.title, 15f, bold = true).apply { maxLines = 1; setPadding(2.dp, 8.dp, 2.dp, 0) })
        card.addView(Ui.text(context, subtitle(e), 11.5f, Ui.TEXT_DIM).apply { maxLines = 1; setPadding(2.dp, 2.dp, 2.dp, 0) })
        card.foreground = Ui.ripple(14f)
        card.setOnClickListener { onOpen(e.id) }
        card.setOnLongClickListener { menu(card, e); true }
        card.tag = e.id
        return card
    }

    /** the artworks as they are listed now (for the big preview to go through) */
    private var shownNow: List<Gallery.Entry> = emptyList()
    private var preview: GalleryPreview? = null

    fun previewOpen() = preview != null

    fun closePreview() { preview?.let { removeView(it) }; preview = null }

    private fun openPreview(id: String) {
        val list = shownNow.ifEmpty { entries }
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        closePreview()
        val p = GalleryPreview(context, list, i, onOpen = { closePreview(); onOpen(it) }, onClose = { closePreview() })
        preview = p
        addView(p, LayoutParams(-1, -1))
    }

    // pinch a card open: its big preview
    private var pinchD0 = 0f
    private var pinchDone = false

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (preview == null && pinchCheck(e)) {
            // the cards and the scrolling let go of this touch
            val c = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(c); c.recycle()
            return true
        }
        if (pinchDone && e.actionMasked != MotionEvent.ACTION_DOWN) { if (e.actionMasked == MotionEvent.ACTION_UP) pinchDone = false; return true }
        return super.dispatchTouchEvent(e)
    }

    private fun pinchCheck(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pinchDone = false
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                pinchD0 = kotlin.math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)); pinchDone = false
            }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2 && !pinchDone && pinchD0 > 0f) {
                val d = kotlin.math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                if (d > pinchD0 * 1.45f) {
                    pinchDone = true
                    val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                    cardAt(this, mx, my)?.let { openPreview(it) }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pinchD0 = 0f
        }
        return false
    }

    /** the artwork id of the card under a point of this view */
    private fun cardAt(root: View, x: Float, y: Float): String? {
        val loc = IntArray(2); val me = IntArray(2)
        getLocationOnScreen(me)
        fun walk(v: View): String? {
            if (v.tag is String && v.isShown) {
                v.getLocationOnScreen(loc)
                val sx = x + me[0]; val sy = y + me[1]
                if (sx >= loc[0] && sx <= loc[0] + v.width && sy >= loc[1] && sy <= loc[1] + v.height) return v.tag as String
            }
            if (v is android.view.ViewGroup) for (k in 0 until v.childCount) walk(v.getChildAt(k))?.let { return it }
            return null
        }
        return walk(root)
    }

    private fun menu(anchor: View, e: Gallery.Entry) {
        val pm = InkMenu(context, anchor)
        pm.menu.add(0, 9, 0, "Preview")
        pm.menu.add(0, 1, 1, "Rename")
        pm.menu.add(0, 2, 2, "Duplicate")
        pm.menu.add(0, 4, 3, "Move to stack…")
        if (e.stack != null) pm.menu.add(0, 5, 4, "Take out of the stack")
        pm.menu.add(0, 3, 5, "Delete")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                9 -> openPreview(e.id)
                1 -> onRename(e)
                2 -> onDuplicate(e)
                3 -> onDelete(e)
                4 -> moveToStack(e)
                5 -> { Gallery.setStack(context, e.id, null); refresh() }
            }
            true
        }
        L10n.menu(pm.menu); pm.show()
    }

    private fun emptyState(): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp, 80.dp, 24.dp, 24.dp)
        }
        box.addView(Ui.text(context, "Welcome to Inkora", 24f, bold = true))
        box.addView(Ui.text(context, "Start a new canvas, or import a photo or project.", 15f, Ui.TEXT_DIM).apply { setPadding(0, 8.dp, 0, 20.dp) })
        box.addView(Ui.button(context, "＋  New canvas") { onNew() }.apply {
            background = Ui.rounded(Ui.ACCENT, 12f)
            textSize = 16f
            setPadding(24.dp, 12.dp, 24.dp, 12.dp)
        })
        return box
    }
}
