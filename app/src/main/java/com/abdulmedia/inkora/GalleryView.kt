package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
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

    private var filter = Filter.ALL
    private var query = ""
    private val content = LinearLayout(context)
    private val chips = LinearLayout(context)
    private var entries: List<Gallery.Entry> = emptyList()

    init {
        setBackgroundColor(0xFF111114.toInt())
        isClickable = true // block touches to the canvas below

        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // ---- header ----
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28.dp, 22.dp, 24.dp, 6.dp)
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(context).apply {
            text = "Zerin"
            textSize = 30f
            setTextColor(Ui.TEXT)
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)
        })
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
        titles.addView(Ui.text(context, "Your artworks  ·  version $version", 14f, Ui.TEXT_DIM))
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
        tools.addView(chips, LinearLayout.LayoutParams(0, -2, 1f))
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
        tools.addView(search, LinearLayout.LayoutParams(240.dp, -2))
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
        val shown = entries.filter {
            when (filter) {
                Filter.ALL -> true
                Filter.DRAWN -> !it.imported
                Filter.IMPORTED -> it.imported
            } && (query.isEmpty() || it.title.contains(query, ignoreCase = true))
        }
        if (shown.isEmpty()) {
            content.addView(Ui.text(context, "Nothing here.", 15f, Ui.TEXT_DIM).apply { setPadding(12.dp, 24.dp, 0, 0) })
            return
        }

        var rest = shown
        // "Continue" hero card for the most recent artwork
        if (filter == Filter.ALL && query.isEmpty()) {
            content.addView(section("Continue"))
            content.addView(hero(shown[0]), LinearLayout.LayoutParams(-1, -2).apply { setMargins(8.dp, 0, 8.dp, 8.dp) })
            rest = shown.drop(1)
            if (rest.isNotEmpty()) content.addView(section("Recent"))
        }

        val cardW = 230.dp
        val cols = max(1, (width - 32.dp) / (cardW + 16.dp))
        val grid = GridLayout(context).apply { columnCount = cols }
        for (e in rest) grid.addView(card(e, cardW), GridLayout.LayoutParams().apply {
            width = cardW
            setMargins(8.dp, 8.dp, 8.dp, 8.dp)
        })
        content.addView(grid)
    }

    private fun section(t: String) = Ui.text(context, t.uppercase(), 12f, Ui.TEXT_DIM, true).apply {
        setPadding(10.dp, 16.dp, 0, 6.dp)
        letterSpacing = 0.08f
    }

    private fun thumb(e: Gallery.Entry) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = Ui.rounded(0xFF1B1B20.toInt(), 10f)
        runCatching { BitmapFactory.decodeFile(e.thumb.absolutePath) }.getOrNull()?.let { setImageBitmap(it) }
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
        row.addView(frame, LinearLayout.LayoutParams(300.dp, 200.dp))
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(22.dp, 0, 0, 0) }
        info.addView(Ui.text(context, e.title, 22f, bold = true).apply { maxLines = 2 })
        info.addView(Ui.text(context, subtitle(e), 13f, Ui.TEXT_DIM).apply { setPadding(0, 6.dp, 0, 14.dp) })
        info.addView(Ui.button(context, "Continue drawing  ›") { onOpen(e.id) }.apply {
            background = Ui.rounded(Ui.ACCENT, 12f)
            textSize = 15f
        }, LinearLayout.LayoutParams(-2, -2))
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnClickListener { onOpen(e.id) }
        row.setOnLongClickListener { menu(row, e); true }
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
        card.setOnClickListener { onOpen(e.id) }
        card.setOnLongClickListener { menu(card, e); true }
        return card
    }

    private fun menu(anchor: View, e: Gallery.Entry) {
        val pm = PopupMenu(context, anchor)
        pm.menu.add(0, 1, 1, "Rename")
        pm.menu.add(0, 2, 2, "Duplicate")
        pm.menu.add(0, 3, 3, "Delete")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> onRename(e)
                2 -> onDuplicate(e)
                3 -> onDelete(e)
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
        box.addView(Ui.text(context, "Welcome to Zerin", 24f, bold = true))
        box.addView(Ui.text(context, "Start a new canvas, or import a photo or project.", 15f, Ui.TEXT_DIM).apply { setPadding(0, 8.dp, 0, 20.dp) })
        box.addView(Ui.button(context, "＋  New canvas") { onNew() }.apply {
            background = Ui.rounded(Ui.ACCENT, 12f)
            textSize = 16f
            setPadding(24.dp, 12.dp, 24.dp, 12.dp)
        })
        return box
    }
}
