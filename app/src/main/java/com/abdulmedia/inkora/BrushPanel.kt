package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Grain texture paint used when a stroke is merged (DST_IN = keep paint only where the texture is). */
fun grainPaintFor(b: Brush): Paint? {
    val g = b.grain() ?: return null
    val shader = BitmapShader(g, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    val m = Matrix()
    val sc = b.grainScale.coerceIn(0.1f, 8f) * 256f / max(g.width, g.height)
    m.setScale(sc, sc)
    shader.setLocalMatrix(m)
    val depth = b.dyn.grainDepth.coerceIn(0f, 1f)
    return Paint(Paint.FILTER_BITMAP_FLAG).apply {
        this.shader = shader
        blendMode = BlendMode.DST_IN
        // grain depth: less depth lets paint show between the grain too
        if (depth < 0.999f) colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, depth, (1f - depth) * 255f))
    }
}

/**
 * What makes a stroke look like watercolor while it is drawn:
 *  - granulation: the stroke through the grain is pushed to "paint or paper" (the paint catches
 *    on the paper's high points first), and light pressure is kept lighter;
 *  - wet edge: the middle of the wash is made lighter than its rim.
 * The stroke and its grain are drawn into a layer with [layer] (when there is one), then the
 * stroke is drawn again with [tone] and with [core].
 */
class StrokeLook(val layer: Paint?, val tone: Paint?, val core: Paint?)

private fun alphaFilter(scale: Float, add: Float) = android.graphics.ColorMatrixColorFilter(floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    0f, 0f, 0f, scale, add * 255f))

fun strokeLookFor(b: Brush): StrokeLook? {
    val gr = if (b.grain() == null) 0f else b.dyn.granulation.coerceIn(0f, 1f)
    val edge = b.dyn.wetEdge.coerceIn(0f, 1f)
    if (gr < 0.01f && edge < 0.01f) return null
    var layer: Paint? = null
    var tone: Paint? = null
    if (gr >= 0.01f) {
        // alpha' = gain × alpha − cut: with a deep grain a full stroke is solid, a faint one only
        // reaches the paper's high points
        val gain = 1f + 4f * gr
        layer = Paint().apply { colorFilter = alphaFilter(gain, -0.05f * gr * gain) }
        tone = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_IN; colorFilter = alphaFilter(0.5f, 0.5f) }
    }
    // the stroke's own middle (where it is more than half covered) takes some of the paint away again
    val core = if (edge < 0.01f) null else Paint(Paint.FILTER_BITMAP_FLAG).apply {
        blendMode = BlendMode.DST_OUT
        colorFilter = alphaFilter(1.2f * edge, -0.6f * edge)
    }
    return StrokeLook(layer, tone, core)
}

/**
 * Brush library: the sets down the side (each with its icon), the chosen set's brushes beside it,
 * each with a stroke drawn by the brush itself. Tap a brush to paint with it; tap it again to
 * open the Brush Studio. Imported sets are listed after Inkora's own.
 */
@SuppressLint("ViewConstructor")
class BrushPanel(
    context: Context,
    private val title: String,
    private val brushes: List<Brush>,
    private var selected: Brush,
    private val onSelect: (Brush) -> Unit,
    private val onImportFile: () -> Unit,
    private val onImportLink: () -> Unit,
    private val onDelete: (Brush) -> Unit,
    private val onPin: (() -> Unit)? = null,
    private val onDeleteSet: ((String, List<Brush>) -> Unit)? = null,
    /** opens the Brush Studio for the brush */
    private val onEdit: (Brush) -> Unit = {},
    private val onNewBrush: (() -> Unit)? = null,
    private val onExportSet: ((String, List<Brush>) -> Unit)? = null,
    /** a copy of the brush as one of the user's own */
    private val onDuplicate: ((Brush) -> Unit)? = null,
    /** the brush's settings go back to how it came */
    private val onReset: ((Brush) -> Unit)? = null
) : LinearLayout(context) {

    /** what is typed in the search field ("" = show the chosen set) */
    private var query = ""
    private val search = android.widget.EditText(context)
    private val setsColumn = LinearLayout(context)
    private val list = LinearLayout(context)
    private val listScroll = ScrollView(context)
    private var currentSet: String = selected.preset.category
    /** the brush whose actions (pin, duplicate, reset) are showing */
    private var openActions: String? = null

    /** the brush that is slid open, and its buttons */
    private var openCard: Pair<View, View>? = null

    init {
        loadLists(context)
        orientation = VERTICAL
        setPadding(14.dp, 14.dp, 14.dp, 14.dp)
        background = Ui.rounded(Ui.PANEL, 20f, Ui.OUTLINE, 1)
        isClickable = true
        setsColumn.orientation = VERTICAL
        list.orientation = VERTICAL
        listScroll.isVerticalScrollBarEnabled = false
        listScroll.addView(list)
        rebuild()
    }

    /** the brushes of a set; Pinned and Recent are made from the lists kept between runs */
    private fun itemsOf(set: String): List<Brush> = when (set) {
        PINNED -> brushes.filter { it.preset.id in pins }
        RECENT -> recents.mapNotNull { id -> brushes.firstOrNull { it.preset.id == id } }
        else -> brushes.filter { it.preset.category == set }
    }

    private fun sets(): List<String> {
        val seen = LinkedHashSet<String>()
        if (recents.any { id -> brushes.any { it.preset.id == id } }) seen.add(RECENT)
        if (brushes.any { it.preset.id in pins }) seen.add(PINNED)
        for (b in brushes) if (!b.preset.imported) seen.add(b.preset.category)
        for (b in brushes) if (b.preset.imported) seen.add(b.preset.category)
        return seen.toList()
    }

    private fun iconFor(set: String, imported: Boolean): Icon = when {
        set == PINNED -> Icon.PIN
        set == RECENT -> Icon.CLOCK
        imported -> Icon.FOLDER
        set.startsWith("Ink") -> Icon.INK
        set.startsWith("Callig") -> Icon.NIB
        set.startsWith("Sketch") -> Icon.PENCIL
        set.startsWith("Paint") -> Icon.PALETTE
        set.startsWith("Living") -> Icon.PALETTE
        set.startsWith("Water") || set.startsWith("Aquarelle") -> Icon.DROP
        set.startsWith("Tempera") -> Icon.PALETTE
        set.startsWith("Marker") -> Icon.NIB
        set.startsWith("Light &") -> Icon.LIGHTBULB
        set.startsWith("Air") -> Icon.SPRAY
        set.startsWith("Textur") -> Icon.TEXTURE
        set.startsWith("Effect") -> Icon.SPARKLE
        set.startsWith("Pixel") -> Icon.PIXEL
        set.startsWith("Draw") -> Icon.PENCIL
        set.startsWith("Artist") -> Icon.PALETTE
        set.startsWith("Abstract") -> Icon.SHAPE
        set.startsWith("Charcoal") -> Icon.PENCIL
        set.startsWith("Element") -> Icon.DROP
        set.startsWith("Spray") -> Icon.SPRAY
        set.startsWith("Touch") -> Icon.SMUDGE
        set.startsWith("Vintage") -> Icon.CLOCK
        set.startsWith("Lumin") -> Icon.LIGHTBULB
        set.startsWith("Industr") -> Icon.WRENCH
        set.startsWith("Organic") -> Icon.STAR
        set.startsWith("Material") -> Icon.TEXTURE
        set.startsWith("Retro") -> Icon.STAR_FILLED
        else -> Icon.BRUSH
    }


    /** the brushes the search finds, in every set */
    private fun matches(q: String): List<Brush> {
        val t = q.trim().lowercase()
        return brushes.filter {
            it.preset.name.lowercase().contains(t) || L10n.tr(it.preset.name).lowercase().contains(t) ||
                it.preset.category.lowercase().contains(t) || L10n.tr(it.preset.category).lowercase().contains(t)
        }.distinctBy { it.preset.id }
    }

    /** the name a set shows: Pinned is "Favorites" (the star) */
    private fun setLabel(set: String) = L10n.tr(if (set == PINNED) "Favorites" else set)

    /** a short line under a brush's name: its set (in lists from several sets) or what it does */
    private fun kindOf(b: Brush, mixed: Boolean): String {
        if (mixed) return L10n.tr(b.preset.category)
        val p = b.preset
        val traits = ArrayList<String>()
        if (p.living != null) traits.add("Living Paint")
        if (p.imported) traits.add("Your brush")
        if (p.minSize < 0.6f) traits.add("Pressure: size")
        if (p.minFlow < 0.6f) traits.add("Pressure: opacity")
        if (p.grain != GrainType.NONE) traits.add("Texture")
        return traits.take(2).joinToString(" · ") { L10n.tr(it) }
    }

    private fun rebuild() {
        removeAllViews()
        // ---- header: name, search, Import ----
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(6.dp, 0, 0, 10.dp) }
        head.addView(Ui.text(context, title, 20f, bold = true), LayoutParams(-2, -2).apply { marginEnd = 10.dp })
        val field = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp, 0, 8.dp, 0)
            background = Ui.rounded(Ui.CARD, 12f)
        }
        field.addView(IconView(context, Icon.SEARCH).apply { isClickable = false; tint = Ui.TEXT_DIM; iconScale = 0.6f }, LayoutParams(24.dp, 24.dp))
        (search.parent as? android.view.ViewGroup)?.removeView(search)
        search.apply {
            hint = L10n.tr("Search brushes")
            setHintTextColor(Ui.TEXT_DIM)
            setTextColor(Ui.TEXT)
            textSize = 13.5f
            typeface = Fonts.get(400)
            background = null
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(6.dp, 0, 0, 0)
            if (text.toString() != query) setText(query)
        }
        if (search.tag == null) {
            search.tag = "watch"
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    val q = e?.toString() ?: ""
                    if (q == query) return
                    query = q
                    openActions = null
                    buildList(); refreshSets()
                }
            })
        }
        field.addView(search, LayoutParams(0, -1, 1f))
        head.addView(field, LayoutParams(0, 40.dp, 1f))
        if (onPin != null) {
            head.addView(IconView(context, Icon.PIN).apply {
                iconScale = 0.5f
                contentDescription = L10n.tr("Pin")
                tip = L10n.tr("Keep the library open as a floating window")
                setOnClickListener { onPin.invoke() }
            }, LayoutParams(40.dp, 40.dp).apply { marginStart = 4.dp })
        }
        val import = Ui.text(context, "Import", 13.5f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(14.dp, 0, 14.dp, 0)
            background = Ui.rounded(Ui.CARD, 12f)
            foreground = Ui.ripple(12f)
            contentDescription = L10n.tr("Import brushes…")
        }
        import.setOnClickListener {
            val pm = InkMenu(context, import)
            pm.menu.add(0, 1, 1, "Import file…")
            pm.menu.add(0, 2, 2, "Import from link…")
            if (onNewBrush != null) pm.menu.add(0, 3, 3, "New brush from this one")
            pm.setOnMenuItemClickListener {
                when (it.itemId) { 1 -> onImportFile(); 2 -> onImportLink(); else -> onNewBrush?.invoke() }
                true
            }
            L10n.menu(pm.menu); pm.show()
        }
        head.addView(import, LayoutParams(-2, 40.dp).apply { marginStart = 6.dp })
        addView(head)
        addView(View(context).apply { setBackgroundColor(Ui.DIVIDER) }, LayoutParams(-1, 1).apply { bottomMargin = 8.dp })

        // ---- sets on the start side, brushes beside them ----
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        val setScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        setsColumn.removeAllViews()
        (setsColumn.parent as? android.view.ViewGroup)?.removeView(setsColumn)
        setScroll.addView(setsColumn)
        row.addView(setScroll, LayoutParams(if (context.narrow) 116.dp else 178.dp, -1))
        row.addView(View(context).apply { setBackgroundColor(Ui.DIVIDER) }, LayoutParams(1, -1).apply { marginStart = 6.dp; marginEnd = 10.dp })
        (listScroll.parent as? android.view.ViewGroup)?.removeView(listScroll)
        row.addView(listScroll, LayoutParams(0, -1, 1f))
        addView(row, LayoutParams(-1, 0, 1f))

        val all = sets()
        if (currentSet !in all) currentSet = all.firstOrNull() ?: ""
        for (set in all) {
            val virtual = set == PINNED || set == RECENT
            val imported = !virtual && brushes.any { it.preset.category == set && it.preset.imported } && brushes.none { it.preset.category == set && !it.preset.imported }
            val hasSelected = !virtual && selected.preset.category == set
            val item = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10.dp, 0, 10.dp, 0)
                foreground = Ui.ripple(10f)
                setOnClickListener {
                    currentSet = set; openActions = null
                    if (query.isNotEmpty()) { query = ""; search.setText("") }
                    buildList(); refreshSets()
                }
                // long-press a set: export it (any set), or delete it (yours)
                setOnLongClickListener { v ->
                    val list = itemsOf(set)
                    val pm = InkMenu(context, v)
                    if (onExportSet != null) pm.menu.add(0, 1, 1, "Export set (.brushset)…")
                    if (imported && onDeleteSet != null) pm.menu.add(0, 2, 2, "Delete set")
                    pm.setOnMenuItemClickListener {
                        if (it.itemId == 1) onExportSet?.invoke(set, list) else onDeleteSet?.invoke(set, list)
                        true
                    }
                    L10n.menu(pm.menu); pm.show()
                    true
                }
                tag = set
            }
            item.addView(Ui.text(context, setLabel(set), 13.5f).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LayoutParams(0, -2, 1f))
            if (hasSelected) item.addView(View(context).apply { background = Ui.rounded(Ui.ACCENT, 4f); tag = "dot" }, LayoutParams(6.dp, 6.dp).apply { marginEnd = 6.dp })
            item.addView(Ui.text(context, itemsOf(set).size.toString(), 11.5f, Ui.TEXT_DIM))
            setsColumn.addView(item, LayoutParams(-1, 40.dp).apply { bottomMargin = 2.dp })
        }
        refreshSets()
        buildList()
    }

    private fun refreshSets() {
        for (i in 0 until setsColumn.childCount) {
            val item = setsColumn.getChildAt(i) as? LinearLayout ?: continue
            val on = query.isEmpty() && item.tag == currentSet
            item.background = if (on) Ui.rounded(Ui.ACCENT_SOFT, 10f) else null
            (item.getChildAt(0) as? android.widget.TextView)?.let {
                it.setTextColor(if (on) Ui.ACCENT else Ui.TEXT)
                it.typeface = Fonts.get(if (on) 600 else 400)
            }
        }
    }

    private fun buildList() {
        list.removeAllViews()
        openCard = null
        val searching = query.isNotBlank()
        val items = if (searching) matches(query) else itemsOf(currentSet)
        val mixed = searching || currentSet == PINNED || currentSet == RECENT
        val tintLight = if (Ui.light) android.graphics.PorterDuffColorFilter(Ui.TEXT, android.graphics.PorterDuff.Mode.SRC_IN) else null
        if (searching && items.isEmpty())
            list.addView(Ui.text(context, "No brush by that name", 13f, Ui.TEXT_DIM).apply { setPadding(8.dp, 16.dp, 8.dp, 8.dp) })
        for (b in items) {
            val on = b === selected
            val card = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dp, 0, 6.dp, 0)
                // solid, so the buttons behind it show only where it has slid away
                background = if (on) Ui.rounded(Ui.CARD, 14f, Ui.ACCENT, 2) else Ui.rounded(Ui.CARD, 14f)
                contentDescription = L10n.tr(b.preset.name)
            }
            val texts = LinearLayout(context).apply { orientation = VERTICAL }
            texts.addView(Ui.text(context, b.preset.name, 14f, Ui.TEXT, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val kind = kindOf(b, mixed)
            if (kind.isNotEmpty()) texts.addView(Ui.text(context, kind, 11.5f, Ui.TEXT_DIM).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            card.addView(texts, LayoutParams(if (context.narrow) 96.dp else 124.dp, -2))
            val img = ImageView(context)
            img.setImageBitmap(preview(b))
            img.scaleType = ImageView.ScaleType.FIT_XY
            if (tintLight != null) img.colorFilter = tintLight
            card.addView(img, LayoutParams(0, 44.dp, 1f).apply { marginStart = 8.dp; marginEnd = 4.dp })
            // the star: a favorite (the same list as Pinned)
            val fav = b.preset.id in pins
            card.addView(IconView(context, if (fav) Icon.STAR_FILLED else Icon.STAR).apply {
                iconScale = 0.46f
                tint = if (fav) Ui.ACCENT else Ui.TEXT_DIM
                contentDescription = L10n.tr(if (fav) "Remove from favorites" else "Add to favorites")
                setOnClickListener {
                    if (fav) pins.remove(b.preset.id) else pins.add(b.preset.id)
                    saveLists(context)
                    openActions = null
                    rebuild()
                }
            }, LayoutParams(40.dp, 44.dp))
            card.setOnClickListener {
                if (b === selected) {
                    // the brush in hand, tapped again: the Brush Studio
                    onEdit(b)
                } else {
                    selected = b
                    onSelect(b)
                    buildList()
                    refreshSets()
                }
            }
            if (b.preset.imported) card.setOnLongClickListener { onDelete(b); true }
            card.foreground = Ui.ripple(14f)
            // the brush slides to the side and its buttons show where it was
            val actions = actionsFor(b)
            val reach = actions.second.toFloat()
            val wrap = android.widget.FrameLayout(context)
            wrap.addView(actions.first, android.widget.FrameLayout.LayoutParams(actions.second, -1, Gravity.RIGHT))
            wrap.addView(card, android.widget.FrameLayout.LayoutParams(-1, 70.dp))
            val open = openActions == b.preset.id
            actions.first.visibility = if (open) View.VISIBLE else View.INVISIBLE
            card.translationX = if (open) -reach else 0f
            if (open) openCard = card to actions.first
            slide(card, actions.first, reach) { nowOpen ->
                if (nowOpen) {
                    // one brush open at a time
                    openCard?.let { (c, a) -> if (c !== card) close(c, a) }
                    openCard = card to actions.first
                    openActions = b.preset.id
                } else if (openActions == b.preset.id) { openActions = null; openCard = null }
            }
            wrap.tag = b.preset.id
            list.addView(wrap, LayoutParams(-1, 70.dp).apply { bottomMargin = 8.dp })
        }
        val hint = when {
            brushes.none { it.preset.imported } ->
                "Import Procreate brushes (.brush, .brushset), Photoshop brushes (.abr), zip files or PNG stamps. You can also open a brush file with Inkora from your Files app."
            items.any { it.preset.imported } -> "Tap the selected brush again for Brush Studio. Slide a brush to the side to duplicate or delete it."
            else -> "Tap the selected brush again for Brush Studio. Slide a brush to the side to duplicate or reset it."
        }
        list.addView(Ui.text(context, hint, 12f, Ui.TEXT_DIM).apply { setPadding(6.dp, 4.dp, 8.dp, 8.dp) })
    }


    /** opens the set the brush belongs to, scrolls to the brush and flashes it */
    private fun find(b: Brush) {
        openActions = null
        currentSet = b.preset.category
        buildList()
        refreshSets()
        post {
            (0 until setsColumn.childCount).map { setsColumn.getChildAt(it) }.firstOrNull { it.tag == currentSet }?.let { item ->
                (setsColumn.parent as? ScrollView)?.smoothScrollTo(0, (item.top - 60.dp).coerceAtLeast(0))
            }
            val row = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.tag == b.preset.id } ?: return@post
            listScroll.smoothScrollTo(0, (row.top - 40.dp).coerceAtLeast(0))
            // two soft blinks, so the eye lands on it
            row.animate().alpha(0.25f).setDuration(160).withEndAction {
                row.animate().alpha(1f).setDuration(160).withEndAction {
                    row.animate().alpha(0.25f).setDuration(160).withEndAction { row.animate().alpha(1f).setDuration(200).start() }.start()
                }.start()
            }.start()
        }
    }

    private fun close(card: View, actions: View) {
        card.animate().translationX(0f).setDuration(140).withEndAction { actions.visibility = View.INVISIBLE }.start()
    }

    /**
     * Find, Duplicate and Reset (or Delete for a brush of your own): the buttons behind a brush,
     * and how wide they are together.
     */
    private fun actionsFor(b: Brush): Pair<LinearLayout, Int> {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val tile = 66.dp
        var n = 0
        fun add(label: String, color: Int, run: () -> Unit) {
            row.addView(Ui.text(context, label, 11.5f, 0xFFFFFFFF.toInt(), true).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                background = Ui.rounded(color, 12f)
                foreground = Ui.ripple(12f)
                setOnClickListener { run() }
            }, LayoutParams(tile - 6.dp, -1).apply { leftMargin = 6.dp; topMargin = 3.dp; bottomMargin = 3.dp })
            n++
        }
        // in Pinned and Recent: where the brush lives in the library
        if (currentSet == PINNED || currentSet == RECENT) add("Find", Ui.ACCENT) { find(b) }
        if (onDuplicate != null && b.preset.living == null) add("Duplicate", 0xFF5E5E63.toInt()) { openActions = null; onDuplicate.invoke(b) }
        if (b.preset.imported) add("Delete", Ui.DANGER) { openActions = null; onDelete(b) }
        else if (onReset != null) add("Reset", 0xFF8A6D3B.toInt()) {
            onReset.invoke(b)
            openActions = null
            buildList()
        }
        return row to n * tile
    }

    /**
     * Sliding a brush sideways with the finger: it follows, and when let go it stays open (its
     * buttons showing, [reach] wide) or goes back. Taps and long presses go on as before.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun slide(card: View, actions: View, reach: Float, onState: (Boolean) -> Unit) {
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var x0 = 0f; var y0 = 0f; var base = 0f; var dragging = false
        card.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; base = view.translationX; dragging = false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0
                    val ax = kotlin.math.abs(dx); val ay = kotlin.math.abs(e.rawY - y0)
                    if (!dragging && ax > slop && ax > ay * 1.3f) {
                        dragging = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        view.cancelLongPress()
                        view.isPressed = false
                        actions.visibility = View.VISIBLE
                    }
                    if (dragging) view.translationX = (base + dx).coerceIn(-reach, 0f)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> if (dragging) {
                    val open = view.translationX < -reach * 0.4f
                    if (open) view.animate().translationX(-reach).setDuration(140).start() else close(view, actions)
                    onState(open)
                }
            }
            // once it is a slide, the rest of the gesture is not a tap
            dragging
        }
    }

    companion object {
        private val cache = HashMap<String, Bitmap>()

        const val PINNED = "Pinned"
        const val RECENT = "Recent"
        private const val MAX_RECENT = 12
        /** ids of the pinned brushes, and of the last brushes used (newest first), kept between runs */
        private val pins = LinkedHashSet<String>()
        private val recents = ArrayList<String>()
        private var listsLoaded = false

        private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("inkora", Context.MODE_PRIVATE)

        private fun loadLists(ctx: Context) {
            if (listsLoaded) return
            listsLoaded = true
            val p = prefs(ctx)
            p.getString("brush_pins", "")!!.split('\n').filter { it.isNotBlank() }.forEach { pins.add(it) }
            p.getString("brush_recent", "")!!.split('\n').filter { it.isNotBlank() }.forEach { recents.add(it) }
        }

        private fun saveLists(ctx: Context) {
            prefs(ctx).edit().putString("brush_pins", pins.joinToString("\n")).putString("brush_recent", recents.joinToString("\n")).apply()
        }

        /** ids of the brushes pinned in the library */
        fun pinnedIds(ctx: Context): Set<String> { loadLists(ctx); return pins }

        /** the brush that was just taken in hand goes to the top of Recent */
        fun noteRecent(ctx: Context, id: String) {
            loadLists(ctx)
            if (recents.firstOrNull() == id) return
            recents.remove(id)
            recents.add(0, id)
            while (recents.size > MAX_RECENT) recents.removeAt(recents.size - 1)
            saveLists(ctx)
        }

        /** the brush's stroke as it is set now (a changed setting draws a new one) */
        fun preview(b: Brush): Bitmap {
            val key = b.preset.id + "#" + BrushSettings.toJson(b).toString().hashCode()
            return cache.getOrPut(key) {
                // a broken brush must never take the whole app down
                runCatching { render(b) }.getOrElse { Bitmap.createBitmap(420, 64, Bitmap.Config.ARGB_8888) }
            }
        }

        fun forget(id: String) { cache.keys.removeAll { it == id || it.startsWith("$id#") } }

        /** Draws a sample S-curve stroke (light → heavy → light pressure). */
        fun render(source: Brush): Bitmap {
            val w = 420
            val h = 64
            if (source.preset.living != null) {
                LivingPainter.preview(Brush(source.preset).apply { size = source.preset.defaultSize.coerceIn(5f, 26f); opacity = source.opacity },
                    w, h, 0xFFE8E9ED.toInt())?.let { return it }
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val brush = Brush(source.preset).apply {
                size = source.preset.defaultSize.coerceIn(5f, 26f)
                streamline = 0f
                sizePressure = source.sizePressure
                flowPressure = source.flowPressure
                spacing = source.spacing
                scatter = source.scatter
                flow = source.flow
                rotation = source.rotation
                angle = source.angle
                grainOn = source.grainOn
                grainScale = source.grainScale * 0.5f
                dyn = source.dyn.copy()
            }
            val engine = StrokeEngine()
            engine.brush = brush
            engine.color = 0xFFE8E9ED.toInt()
            val mask = if (brush.dualTip() != null) Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8) else null
            engine.maskTarget = mask
            val pts = ArrayList<InputPoint>()
            val n = 120
            for (i in 0..n) {
                val t = i / n.toFloat()
                val x = 20f + t * (w - 40f)
                val y = h / 2f + sin(t * 2 * PI).toFloat() * (h * 0.22f)
                val p = sin(t * PI).toFloat()
                pts.add(InputPoint(x, y, 0.15f + 0.85f * p, 0f))
            }
            engine.renderPressurePoints(bmp, pts, 7)
            if (mask != null) { StrokeEngine.applyMask(bmp, mask); mask.recycle() }
            val grain = grainPaintFor(brush)
            val look = strokeLookFor(brush)
            if (look == null) {
                if (grain != null) Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), grain)
                return bmp
            }
            // a watercolor brush shows its sandy, rimmed stroke in the library too
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val i = if (look.layer != null) c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), look.layer) else -1
            c.drawBitmap(bmp, 0f, 0f, null)
            if (grain != null) c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), grain)
            if (i >= 0) c.restoreToCount(i)
            look.tone?.let { c.drawBitmap(bmp, 0f, 0f, it) }
            look.core?.let { c.drawBitmap(bmp, 0f, 0f, it) }
            bmp.recycle()
            return out
        }
    }
}
