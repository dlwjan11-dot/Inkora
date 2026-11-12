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
    private val onClose: () -> Unit,
    /** "Start new" with a size (width, height); null: ask for a size of your own */
    private val onNewSize: ((Int, Int) -> Unit)? = null,
    private val onCustomSize: (() -> Unit)? = null,
    private val onSettings: (() -> Unit)? = null,
    /** this screen's size in pixels, for the "Screen" size */
    private val screen: Pair<Int, Int> = 2048 to 1536
) : FrameLayout(context) {

    private enum class Filter(val label: String) { ALL("All"), DRAWN("My drawings"), IMPORTED("Imported") }

    private enum class Sort(val label: String) { RECENT("Recent"), NAME("Name"), SIZE("Size") }

    companion object {
        private var sort = Sort.RECENT
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

        // ---- header: Gallery, search, sort, import, new ----
        val phone = context.narrow
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (phone) setPadding(16.dp, 16.dp, 12.dp, 4.dp) else setPadding(32.dp, 26.dp, 32.dp, 6.dp)
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Ui.text(context, "Gallery", 28f, bold = true))
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
        titles.addView(Ui.text(context, "Inkora $version", 12f, Ui.TEXT_DIM))
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        fun quiet(label: String, run: (View) -> Unit) = Ui.text(context, label, 14f, Ui.TEXT).apply {
            gravity = Gravity.CENTER
            setPadding(14.dp, 0, 14.dp, 0)
            background = Ui.rounded(Ui.CARD, 12f)
            foreground = Ui.ripple(12f)
            setOnClickListener { v -> run(v) }
        }
        val search = EditText(context).apply {
            hint = L10n.tr("Search artworks")
            setHintTextColor(Ui.TEXT_DIM)
            setTextColor(Ui.TEXT)
            textSize = 14f
            typeface = Fonts.get(400)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            background = null
            setPadding(8.dp, 0, 4.dp, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim() ?: ""
                    render()
                }
            })
        }
        val field = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 0, 8.dp, 0)
            background = Ui.rounded(Ui.CARD, 12f)
        }
        field.addView(IconView(context, Icon.SEARCH).apply { isClickable = false; tint = Ui.TEXT_DIM; iconScale = 0.6f }, LinearLayout.LayoutParams(24.dp, 24.dp))
        field.addView(search, LinearLayout.LayoutParams(0, -1, 1f))
        if (!phone) head.addView(field, LinearLayout.LayoutParams(280.dp, 44.dp).apply { marginEnd = 10.dp })
        val sortBtn = quiet(L10n.tr(sort.label) + "  ▾") {}
        sortBtn.setOnClickListener {
            val pm = InkMenu(context, sortBtn)
            Sort.values().forEachIndexed { i, so -> pm.menu.add(0, i, i, so.label).isChecked = so == sort }
            pm.setOnMenuItemClickListener { item ->
                sort = Sort.values()[item.itemId]
                sortBtn.text = L10n.tr(sort.label) + "  ▾"
                render(); true
            }
            L10n.menu(pm.menu); pm.show()
        }
        if (!phone) head.addView(sortBtn, LinearLayout.LayoutParams(-2, 44.dp).apply { marginEnd = 10.dp })
        if (showBack) head.addView(quiet("‹ " + L10n.tr("Back to canvas")) { onClose() }, LinearLayout.LayoutParams(-2, 44.dp).apply { marginEnd = 10.dp })
        if (onSettings != null) head.addView(IconView(context, Icon.SETTINGS).apply {
            iconScale = 0.5f
            background = Ui.rounded(Ui.CARD, 12f)
            contentDescription = L10n.tr("Settings")
            setOnClickListener { onSettings.invoke() }
        }, LinearLayout.LayoutParams(44.dp, 44.dp).apply { marginEnd = 10.dp })
        val importBtn = quiet(L10n.tr("Import")) {}
        importBtn.setOnClickListener { onImport(importBtn) }
        head.addView(importBtn, LinearLayout.LayoutParams(-2, 44.dp).apply { marginEnd = 10.dp })
        head.addView(Ui.text(context, if (phone) "＋" else "＋  " + L10n.tr("New canvas"), 14f, Ui.ON_ACCENT, true).apply {
            gravity = Gravity.CENTER
            setPadding(18.dp, 0, 18.dp, 0)
            background = Ui.rounded(Ui.ACCENT, 12f)
            foreground = Ui.ripple(12f)
            contentDescription = L10n.tr("New canvas")
            setOnClickListener { onNew() }
        }, LinearLayout.LayoutParams(-2, 44.dp))
        col.addView(head)
        if (phone) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16.dp, 8.dp, 12.dp, 0) }
            row.addView(field, LinearLayout.LayoutParams(0, 44.dp, 1f).apply { marginEnd = 8.dp })
            row.addView(sortBtn, LinearLayout.LayoutParams(-2, 44.dp))
            col.addView(row)
        }

        // ---- start new: the usual sizes, one tap each ----
        if (onNewSize != null) {
            val presets = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            presets.addView(Ui.text(context, "Start new", 13f, Ui.TEXT_DIM, true), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8.dp })
            val (sw, sh) = screen
            for ((name, size, wh) in listOf(
                Triple("Screen", "$sw × $sh", sw to sh), Triple("Square", "2048", 2048 to 2048),
                Triple("A4", "300 dpi", 2480 to 3508), Triple("Portrait post", "1080 × 1350", 1080 to 1350),
                Triple("4K", "3840 × 2160", 3840 to 2160))) {
                val chip = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(14.dp, 0, 14.dp, 0)
                    background = Ui.rounded(Ui.CARD, 18f)
                    foreground = Ui.ripple(18f)
                    setOnClickListener { onNewSize.invoke(wh.first, wh.second) }
                }
                chip.addView(Ui.text(context, name, 13f, Ui.TEXT, true))
                chip.addView(Ui.text(context, "  $size", 13f, Ui.TEXT_DIM))
                presets.addView(chip, LinearLayout.LayoutParams(-2, 36.dp).apply { marginEnd = 8.dp })
            }
            if (onCustomSize != null) presets.addView(Ui.text(context, "Custom…", 13f, Ui.TEXT, true).apply {
                gravity = Gravity.CENTER
                setPadding(14.dp, 0, 14.dp, 0)
                background = Ui.rounded(Ui.CARD, 18f)
                foreground = Ui.ripple(18f)
                setOnClickListener { onCustomSize.invoke() }
            }, LinearLayout.LayoutParams(-2, 36.dp))
            col.addView(android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                if (phone) setPadding(16.dp, 12.dp, 16.dp, 0) else setPadding(32.dp, 14.dp, 32.dp, 0)
                clipToPadding = false
                addView(presets)
            })
        }

        // ---- which artworks ----
        chips.orientation = LinearLayout.HORIZONTAL
        col.addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            if (phone) setPadding(16.dp, 10.dp, 16.dp, 2.dp) else setPadding(32.dp, 12.dp, 32.dp, 2.dp)
            clipToPadding = false
            addView(chips)
        })

        val scroll = ScrollView(context)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(if (context.narrow) 8.dp else 24.dp, 4.dp, if (context.narrow) 8.dp else 24.dp, 32.dp)
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
            val on = filter == f
            val chip = Ui.text(context, L10n.tr(f.label) + "  $n", 13f, if (on) Ui.ACCENT else Ui.TEXT_DIM, on).apply {
                gravity = Gravity.CENTER
                setPadding(12.dp, 6.dp, 12.dp, 6.dp)
                background = if (on) Ui.rounded(Ui.ACCENT_SOFT, 14f) else null
                setOnClickListener { filter = f; render() }
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
        val sorted = when (sort) {
            Sort.RECENT -> shown.sortedByDescending { it.modified }
            Sort.NAME -> shown.sortedBy { it.title.lowercase() }
            Sort.SIZE -> shown.sortedByDescending { it.width.toLong() * it.height }
        }
        shownNow = sorted
        if (sorted.isEmpty()) {
            content.addView(Ui.text(context, "Nothing here.", 15f, Ui.TEXT_DIM).apply { setPadding(12.dp, 24.dp, 0, 0) })
            return
        }

        val rest = sorted
        val stacked = filter == Filter.ALL && query.isEmpty()
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
        // two across on a phone, four on a wide tablet, three between
        val side = if (context.narrow) 8.dp else 24.dp
        val avail = width - 2 * side
        val wdp = avail / resources.displayMetrics.density
        val cols = when { context.narrow -> 2; wdp >= 1000 -> 4; else -> 3 }
        val gap = if (context.narrow) 8.dp else 12.dp
        val cardW = max(100.dp, avail / cols - 2 * gap)
        val grid = GridLayout(context).apply { columnCount = cols }
        for (make in cards) grid.addView(make(cardW), GridLayout.LayoutParams().apply {
            width = cardW
            setMargins(gap, gap, gap, gap)
        })
        return grid
    }

    /** a stack: two cards fanned behind its newest artwork, its name and how many */
    private fun stackCard(name: String, list: List<Gallery.Entry>, w: Int): View {
        val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val frame = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
        val th = (w * 0.68f).toInt()
        for ((rot, dx) in listOf(-3f to -5f, 2.5f to 5f)) frame.addView(View(context).apply {
            background = Ui.rounded(Ui.CARD_HI, 10f)
            rotation = rot; translationX = dx.dp; translationY = 3f.dp
            elevation = 3f.dp
        }, LayoutParams(-1, -1))
        val top = list.maxByOrNull { it.modified }
        if (top != null) frame.addView(thumb(top).apply { elevation = 6f.dp }, LayoutParams(-1, -1))
        frame.addView(Ui.text(context, L10n.tr("%d artworks").replace("%d", list.size.toString()), 12f, 0xFFFFFFFF.toInt(), true).apply {
            setPadding(10.dp, 4.dp, 10.dp, 4.dp)
            background = Ui.rounded(0x9E000000.toInt(), 10f)
            elevation = 8f.dp
        }, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 10.dp, 10.dp) })
        card.addView(frame, LinearLayout.LayoutParams(-1, th))
        card.addView(Ui.text(context, name, 14.5f, bold = true).apply { maxLines = 1; setPadding(2.dp, 10.dp, 2.dp, 0) })
        card.addView(Ui.text(context, L10n.tr("Stack") + " · " + day(list.maxOf { it.modified }), 12f, Ui.TEXT_DIM).apply { setPadding(2.dp, 1.dp, 2.dp, 0) })
        card.foreground = Ui.ripple(12f)
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

    /** "Today", "Yesterday" or the date */
    private fun day(t: Long): String = DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()

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

    private fun thumb(e: Gallery.Entry) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Ui.rounded(Ui.SUNKEN, 10f)
        clipToOutline = true
        elevation = 4f.dp
        val key = e.thumb.absolutePath + ":" + e.thumb.lastModified()
        val bmp = thumbCache.get(key) ?: runCatching { BitmapFactory.decodeFile(e.thumb.absolutePath) }.getOrNull()?.also { thumbCache.put(key, it) }
        bmp?.let { setImageBitmap(it) }
    }

    private fun badge(e: Gallery.Entry): TextView = Ui.text(context, if (e.imported) "Imported" else "Drawn", 10.5f,
        0xFFFFFFFF.toInt(), true).apply {
        setPadding(8.dp, 3.dp, 8.dp, 3.dp)
        background = Ui.rounded(0xCC000000.toInt(), 8f)
    }

    private fun subtitle(e: Gallery.Entry): String = day(e.modified) + " · ${e.width} × ${e.height}"

    private fun card(e: Gallery.Entry, w: Int): View {
        val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val frame = FrameLayout(context)
        frame.addView(thumb(e), LayoutParams(-1, -1))
        // the artwork that is open: a ring in the accent
        if (e.id == currentId) frame.addView(View(context).apply {
            background = Ui.rounded(0, 10f, Ui.ACCENT, 3); elevation = 7f.dp
        }, LayoutParams(-1, -1))
        if (e.imported) frame.addView(badge(e).apply { elevation = 8f.dp }, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(8.dp, 8.dp, 0, 0) })
        card.addView(frame, LinearLayout.LayoutParams(-1, (w * 0.68f).toInt()))
        card.addView(Ui.text(context, e.title, 14.5f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(2.dp, 10.dp, 2.dp, 0) })
        card.addView(Ui.text(context, subtitle(e), 12f, Ui.TEXT_DIM).apply { maxLines = 1; setPadding(2.dp, 1.dp, 2.dp, 0) })
        card.foreground = Ui.ripple(12f)
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
            setTextColor(Ui.ON_ACCENT)
            textSize = 16f
            setPadding(24.dp, 12.dp, 24.dp, 12.dp)
        })
        return box
    }
}
