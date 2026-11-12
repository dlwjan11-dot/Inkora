package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** One tool that can live in the customizable toolbar (and in the Creative / Editing panel). */
class ToolDef(val id: String, val label: String, val icon: Icon, val group: String, val hint: String = "")

/** Every tool Inkora can put in the toolbar. MainActivity runs them by [ToolDef.id]. */
object ToolCatalog {
    private fun adjIcon(k: AdjustKind) = when (k) {
        AdjustKind.HSB -> Icon.HUE
        AdjustKind.BRIGHT_CONTRAST -> Icon.CONTRAST
        AdjustKind.COLOR_BALANCE -> Icon.BALANCE
        AdjustKind.BLUR -> Icon.BLUR
        AdjustKind.SHARPEN -> Icon.SHARPEN
        AdjustKind.NOISE -> Icon.NOISE
        AdjustKind.POSTERIZE -> Icon.POSTERIZE
        AdjustKind.GRADIENT_MAP -> Icon.GRADIENT
        AdjustKind.INVERT -> Icon.INVERT
        AdjustKind.GRAYSCALE -> Icon.BW
    }

    fun iconFor(k: AdjustKind) = adjIcon(k)

    val all: List<ToolDef> = listOf(
        ToolDef("sel_free", "Freehand select", Icon.SELECT, "Selection", "Draw around an area"),
        ToolDef("sel_rect", "Rectangle select", Icon.SEL_RECT, "Selection", "Drag a box"),
        ToolDef("sel_ellipse", "Ellipse select", Icon.SEL_ELLIPSE, "Selection", "Drag an oval"),
        ToolDef("sel_wand", "Magic wand", Icon.WAND, "Selection", "Tap an area of one color"),
        ToolDef("sel_color", "Color range", Icon.SEL_COLOR, "Selection", "Tap a color, select it everywhere"),
        ToolDef("sel_brush", "Selection brush", Icon.SEL_BRUSH, "Selection", "Paint what to select"),
        ToolDef("sel_all", "Select all", Icon.SEL_ALL, "Selection", "The whole layer"),
        ToolDef("sel_invert", "Invert selection", Icon.SEL_INVERT, "Selection", "Swap inside / outside"),
        ToolDef("deselect", "Deselect", Icon.DESELECT, "Selection", "Remove the selection"),
        ToolDef("copy_paste", "Copy & paste", Icon.COPY, "Selection", "Selection to a new layer"),
        ToolDef("transform", "Transform", Icon.TRANSFORM, "Transform", "Move, scale, rotate, flip"),
        ToolDef("liquify", "Liquify", Icon.LIQUIFY, "Transform", "Push, twirl, pinch and bloat"),
        ToolDef("canvas", "Canvas size & crop", Icon.CROP, "Transform", "Crop, resize, rotate the canvas"),
        ToolDef("eyedropper", "Eyedropper", Icon.EYEDROPPER, "Color", "Pick a color from the canvas"),
        ToolDef("fill", "Fill", Icon.FILL, "Color", "Tap areas to fill them"),
        ToolDef("gradient", "Gradient", Icon.GRADIENT, "Color", "Drag: one color into another, or fading out"),
        ToolDef("recolor", "Recolor", Icon.RECOLOR, "Color", "Tap an area; change the color and it follows"),
        ToolDef("palette_photo", "Palette from photo", Icon.PALETTE, "Color", "The main colors of a photo, as a palette"),
        ToolDef("measure", "Measure", Icon.MEASURE, "Guides", "Length and angle; turn it into a ruler"),
        ToolDef("text", "Text", Icon.TEXT, "Create", "Any language, 9 fonts"),
        ToolDef("shapes", "Shapes", Icon.SHAPE, "Create", "Rectangle, circle, star, heart, arrow… with your brush"),
        ToolDef("photo", "Insert photo", Icon.PHOTO, "Create", "As a new layer"),
        ToolDef("vector", "Vector layer", Icon.VECTOR, "Create", "Pen, shapes, logos"),
        ToolDef("pose3d", "3D & poses", Icon.CUBE, "Create", "Posable figures, objects and 3D models"),
        ToolDef("shadow_photo", "Shadow photo", Icon.TRACE, "Create", "Trace a photo, kept out of exports"),
        ToolDef("comic", "Comic panels", Icon.COMIC, "Create", "Frames for a comic page"),
        ToolDef("bubble", "Speech bubble", Icon.BUBBLE, "Create", "Speech, thought, shout or caption"),
        ToolDef("guides", "Grid & perspective", Icon.GRID, "Guides", "All grid types"),
        ToolDef("assisted", "Assisted drawing", Icon.RULER, "Guides", "Strokes snap to the grid"),
        ToolDef("quickshape", "QuickShape", Icon.QUICKSHAPE, "Guides", "Hold to make perfect shapes"),
        ToolDef("symmetry", "Symmetry", Icon.SYMMETRY, "Guides", "Mirror your strokes"),
        ToolDef("adjust", "Adjustments", Icon.ADJUST, "Adjust", "All adjustments & filters")
    ) + AdjustKind.values().map { k ->
        ToolDef("adj_${k.name}", k.label, adjIcon(k), "Adjust")
    } + listOf(
        ToolDef("fit", "Fit to screen", Icon.FIT, "View", "Center the canvas"),
        ToolDef("flip", "Flip view", Icon.FLIP, "View", "Check your drawing mirrored"),
        ToolDef("actual_size", "Actual size", Icon.ACTUAL, "View", "100% zoom"),
        ToolDef("reference", "Reference image", Icon.REFERENCE, "View", "Floating reference window"),
        ToolDef("navigator", "Navigator", Icon.NAVIGATOR, "View", "Floating canvas thumbnail"),
        ToolDef("lighting", "3D Lighting orb", Icon.ORB, "View", "3D reference sphere for light and shade"),
        ToolDef("grey_view", "Grey view", Icon.BW, "View", "See the values in greys"),
        ToolDef("focus", "Focus mode", Icon.FOCUS, "View", "Hide the interface"),
        ToolDef("snapshots", "Snapshots", Icon.SNAPSHOT, "File", "Keep this moment, come back later"),
        ToolDef("export", "Export PNG", Icon.EXPORT, "File", "Save to Pictures/Inkora"),
        ToolDef("share", "Share", Icon.SHARE, "File", "Send the artwork"),
        ToolDef("timelapse", "Time-lapse video", Icon.CLOCK, "File", "Export the recording")
    )

    val byId = all.associateBy { it.id }

    val DEFAULT = listOf("sel_free", "transform", "eyedropper", "fill", "text", "adjust", "guides", "symmetry", "quickshape")
    const val MAX = 14

    fun decode(s: String?): MutableList<String> =
        (s ?: DEFAULT.joinToString(",")).split(',').map { it.trim() }.filter { it in byId }.distinct().toMutableList()

    fun encode(list: List<String>) = list.joinToString(",")
}

/** On / off switch drawn in code. */
class SwitchView(context: Context) : View(context) {
    var checked = false
        set(v) { field = v; invalidate() }
    var onToggle: ((Boolean) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        setOnClickListener { checked = !checked; onToggle?.invoke(checked) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(46.dp, 28.dp)

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        p.color = if (checked) Ui.ACCENT else 0xFF45454E.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, p)
        p.color = 0xFFFFFFFF.toInt()
        val r = h / 2 - 3f.dp
        canvas.drawCircle(if (checked) w - h / 2 else h / 2, h / 2, r, p)
    }
}

/**
 * The customizable toolbar: a strip of your favorite tools on the side of the screen.
 * At its end: ⊞ opens it wide with every tool (hold one to make it a favorite), and « tucks it
 * away to a small tab on the edge. Tap = use the tool. Long-press = customize.
 */
@SuppressLint("ViewConstructor")
class ToolDockView(
    context: Context,
    private val onTool: (String, View) -> Unit,
    private val isActive: (String) -> Boolean,
    private val onCustomize: () -> Unit,
    private val isFavorite: (String) -> Boolean = { false },
    private val toggleFavorite: (String) -> Unit = {},
    private val onSwapSide: () -> Unit = {},
    private val onStateChange: (String) -> Unit = {}
) : LinearLayout(context) {

    companion object {
        const val NORMAL = "normal"
        const val WIDE = "wide"
        const val HIDDEN = "hidden"
    }

    private val column = LinearLayout(context)
    private val scroll = ScrollView(context)
    /** used instead of [scroll] when the toolbar sits on the top or bottom edge */
    private val hscroll = android.widget.HorizontalScrollView(context)
    private val divider: View
    private val buttons = LinkedHashMap<String, IconView>()
    private val head = LinearLayout(context)
    private val foot = LinearLayout(context)
    private val expandBtn: IconView
    private val hideBtn: IconView
    private val editBtn: IconView
    private val handle: IconView
    private var ids: List<String> = emptyList()

    /** which edge the toolbar is on: "Left", "Right", "Top" or "Bottom" */
    var edge = "Right"
        set(v) { field = v; applyState() }

    var state = NORMAL
        private set

    private val horizontal get() = orientation == HORIZONTAL

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(4.dp, 6.dp, 4.dp, 6.dp)
        background = Ui.rounded(Ui.BAR, 16f, Ui.OUTLINE, 1)
        elevation = 8f.dp
        column.orientation = VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.addView(column)
        hscroll.isHorizontalScrollBarEnabled = false
        hscroll.overScrollMode = OVER_SCROLL_NEVER

        // the wide toolbar's title row: what it is, change sides, customize, close
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(10.dp, 2.dp, 2.dp, 4.dp)
        head.addView(Ui.text(context, "All tools", 15f, bold = true), LayoutParams(0, -2, 1f))
        head.addView(IconView(context, Icon.SWAP_SIDE).apply {
            tint = Ui.TEXT_DIM; contentDescription = "Move to the other side"
            setOnClickListener { onSwapSide() }
        }, LayoutParams(38.dp, 38.dp))
        head.addView(IconView(context, Icon.CUSTOMIZE).apply {
            tint = Ui.TEXT_DIM; contentDescription = "Customize toolbar"
            setOnClickListener { onCustomize() }
        }, LayoutParams(38.dp, 38.dp))
        head.addView(IconView(context, Icon.CLOSE).apply {
            tint = Ui.TEXT_DIM; contentDescription = "Close"
            setOnClickListener { setState(NORMAL) }
        }, LayoutParams(38.dp, 38.dp))

        addView(head, LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-2, 0, 1f))
        divider = Ui.divider(context)
        addView(divider, LayoutParams(28.dp, 1.dp).apply { topMargin = 4.dp; bottomMargin = 4.dp })

        foot.orientation = VERTICAL
        foot.gravity = Gravity.CENTER
        expandBtn = IconView(context, Icon.EXPAND).apply {
            tint = Ui.TEXT_DIM; contentDescription = "All tools"
            tip = "Open the toolbar wide, with every tool"
            setOnClickListener { setState(if (state == WIDE) NORMAL else WIDE) }
        }
        hideBtn = IconView(context, Icon.HIDE).apply {
            tint = Ui.TEXT_DIM; contentDescription = "Hide toolbar"
            tip = "Tuck the toolbar into a small tab on the edge"
            setOnClickListener { setState(HIDDEN) }
        }
        editBtn = IconView(context, Icon.CUSTOMIZE).apply {
            tint = Ui.TEXT_DIM; contentDescription = "Customize toolbar"
            setOnClickListener { onCustomize() }
        }
        foot.addView(expandBtn, LayoutParams(46.dp, 40.dp))
        foot.addView(hideBtn, LayoutParams(46.dp, 40.dp))
        addView(foot, LayoutParams(-2, -2))

        handle = IconView(context, Icon.CHEV_LEFT).apply {
            tint = Ui.TEXT_DIM; contentDescription = "Show toolbar"
            setOnClickListener { setState(NORMAL) }
        }
        addView(handle, LayoutParams(22.dp, 64.dp))
        applyState()
    }

    /** restores the remembered state (without telling anyone) */
    fun restoreState(s: String) {
        state = if (s == WIDE || s == HIDDEN) s else NORMAL
        applyState()
    }

    fun setState(s: String) {
        if (state == s) return
        state = s
        applyState()
        onStateChange(s)
    }

    fun setDockOrientation(orient: Int) {
        if (orientation == orient && column.parent != null) { applyState(); return }
        orientation = orient
        column.orientation = orient
        foot.orientation = orient
        gravity = if (orient == HORIZONTAL) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        column.gravity = gravity
        // a vertical ScrollView cannot scroll sideways: swap in a HorizontalScrollView
        (column.parent as? android.view.ViewGroup)?.removeView(column)
        (scroll.parent as? android.view.ViewGroup)?.removeView(scroll)
        (hscroll.parent as? android.view.ViewGroup)?.removeView(hscroll)
        val at = indexOfChild(head) + 1
        if (orient == HORIZONTAL) {
            hscroll.addView(column)
            addView(hscroll, at, LayoutParams(0, -2, 1f))
            divider.layoutParams = LayoutParams(1.dp, 28.dp).apply { leftMargin = 4.dp; rightMargin = 4.dp }
        } else {
            scroll.addView(column)
            addView(scroll, at, LayoutParams(-2, 0, 1f))
            divider.layoutParams = LayoutParams(28.dp, 1.dp).apply { topMargin = 4.dp; bottomMargin = 4.dp }
        }
        applyState()
    }

    private fun applyState() {
        val hidden = state == HIDDEN
        val wide = state == WIDE && !horizontal
        val strip = column.parent as? View
        strip?.visibility = if (hidden) GONE else VISIBLE
        divider.visibility = if (hidden || wide) GONE else VISIBLE
        foot.visibility = if (hidden || wide) GONE else VISIBLE
        head.visibility = if (wide) VISIBLE else GONE
        handle.visibility = if (hidden) VISIBLE else GONE
        // the tab's arrow points into the screen; the hide arrow points to the edge
        handle.icon = when (edge) { "Left" -> Icon.CHEV_RIGHT; "Top" -> Icon.DOWN; "Bottom" -> Icon.UP; else -> Icon.CHEV_LEFT }
        hideBtn.icon = Icon.HIDE
        hideBtn.rotation = when (edge) { "Left" -> 0f; "Top" -> 90f; "Bottom" -> -90f; else -> 180f }
        handle.layoutParams = if (horizontal) LayoutParams(64.dp, 22.dp) else LayoutParams(22.dp, 64.dp)
        if (hidden) setPadding(2.dp, 4.dp, 2.dp, 4.dp)
        else if (wide) setPadding(6.dp, 8.dp, 6.dp, 8.dp)
        else setPadding(4.dp, 6.dp, 4.dp, 6.dp)
        background = Ui.rounded(Ui.BAR, if (hidden) 10f else if (wide) 20f else 16f, Ui.OUTLINE, 1)
        build()
    }

    fun setTools(list: List<String>) {
        ids = list
        // open wide, the tiles mark their favorites themselves (a rebuild would jump to the top)
        if (state != WIDE) build()
    }

    private fun build() {
        column.removeAllViews()
        buttons.clear()
        if (state == HIDDEN) return
        if (state == WIDE) {
            if (horizontal) buildWideRow() else buildWideGrid()
        } else {
            column.gravity = if (horizontal) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
            for (id in ids) {
                val def = ToolCatalog.byId[id] ?: continue
                val b = toolButton(def)
                b.setOnLongClickListener { onCustomize(); true }
                column.addView(b, LayoutParams(46.dp, 46.dp))
            }
            if (ids.isEmpty()) column.addView(editBtn.also { (it.parent as? android.view.ViewGroup)?.removeView(it) }, LayoutParams(46.dp, 46.dp))
        }
        refresh()
    }

    private fun toolButton(def: ToolDef): IconView {
        val b = IconView(context, def.icon)
        b.contentDescription = def.label
        b.tip = def.hint
        b.setOnClickListener { onTool(def.id, b) }
        buttons[def.id] = b
        return b
    }

    /** left or right: every tool as a tile with its name, in groups */
    private fun buildWideGrid() {
        column.gravity = Gravity.START
        val cols = if (context.narrow) 2 else 3
        column.addView(Ui.text(context, "Hold a tool to add it to your favorites, or take it out", 11.5f, Ui.TEXT_DIM).apply {
            setPadding(10.dp, 0, 10.dp, 4.dp)
        }, LayoutParams(cols * 78.dp, -2))
        for ((group, defs) in ToolCatalog.all.groupBy { it.group }) {
            column.addView(Ui.caption(context, group).apply { setPadding(10.dp, 10.dp, 0, 2.dp) })
            val grid = GridLayout(context).apply { columnCount = cols }
            for (d in defs) {
                val tile = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(2.dp, 4.dp, 2.dp, 6.dp)
                    foreground = Ui.ripple(12f)
                    isClickable = true
                }
                val ic = toolButton(d).apply { isClickable = false; dot = isFavorite(d.id) }
                tile.setOnClickListener { onTool(d.id, ic) }
                tile.setOnLongClickListener {
                    toggleFavorite(d.id)
                    ic.dot = isFavorite(d.id)
                    true
                }
                tile.addView(ic, LayoutParams(44.dp, 44.dp))
                tile.addView(Ui.text(context, d.label, 10.5f, Ui.TEXT_DIM).apply {
                    gravity = Gravity.CENTER; maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LayoutParams(-1, -2))
                grid.addView(tile, GridLayout.LayoutParams().apply { width = 76.dp; setMargins(1.dp, 1.dp, 1.dp, 1.dp) })
            }
            column.addView(grid)
        }
    }

    /** top or bottom: every tool in one long row, groups split by a line */
    private fun buildWideRow() {
        column.gravity = Gravity.CENTER_VERTICAL
        var first = true
        for ((_, defs) in ToolCatalog.all.groupBy { it.group }) {
            if (!first) column.addView(Ui.divider(context), LayoutParams(1.dp, 26.dp).apply { leftMargin = 4.dp; rightMargin = 4.dp })
            first = false
            for (d in defs) {
                val b = toolButton(d).apply { dot = isFavorite(d.id) }
                b.setOnLongClickListener { toggleFavorite(d.id); b.dot = isFavorite(d.id); true }
                column.addView(b, LayoutParams(44.dp, 44.dp))
            }
        }
        column.addView(IconView(context, Icon.CLOSE).apply {
            tint = Ui.TEXT_DIM; contentDescription = "Close"
            setOnClickListener { setState(NORMAL) }
        }, LayoutParams(44.dp, 44.dp))
    }

    fun refresh() {
        for ((id, b) in buttons) b.active = isActive(id)
    }
}

/** "Customize toolbar": add, remove and reorder tools, left-handed layout, reset. */
@SuppressLint("ViewConstructor")
class ToolbarEditor(
    context: Context,
    initial: List<String>,
    private var edge: String,
    private val onChange: (List<String>) -> Unit,
    private val onEdge: (String) -> Unit
) : ScrollView(context) {

    private val list = initial.toMutableList()
    private val box = LinearLayout(context)

    init {
        background = Ui.rounded(Ui.PANEL, 18f)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(16.dp, 14.dp, 16.dp, 16.dp)
        addView(box)
        rebuild()
    }

    private fun changed() {
        onChange(list.toList())
        rebuild()
    }

    private fun rebuild() {
        box.removeAllViews()
        box.addView(Ui.text(context, "Customize toolbar", 17f, bold = true))
        box.addView(Ui.text(context, "Your most used tools, always one tap away", 12f, Ui.TEXT_DIM))

        box.addView(Ui.caption(context, "In your toolbar  (${list.size}/${ToolCatalog.MAX})").apply { setPadding(0, 16.dp, 0, 6.dp) })
        if (list.isEmpty()) box.addView(Ui.text(context, "Empty — add tools below", 13f, Ui.TEXT_DIM).apply { setPadding(4.dp, 4.dp, 0, 4.dp) })
        list.forEachIndexed { i, id ->
            val def = ToolCatalog.byId[id] ?: return@forEachIndexed
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(Ui.CARD, 12f)
                setPadding(6.dp, 2.dp, 4.dp, 2.dp)
            }
            row.addView(IconView(context, def.icon).apply { isClickable = false }, LinearLayout.LayoutParams(40.dp, 40.dp))
            row.addView(Ui.text(context, def.label, 14f), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 6.dp })
            val up = IconView(context, Icon.UP).apply { tint = Ui.TEXT_DIM; isEnabled = i > 0 }
            up.setOnClickListener { if (i > 0) { list.add(i - 1, list.removeAt(i)); changed() } }
            val down = IconView(context, Icon.DOWN).apply { tint = Ui.TEXT_DIM; isEnabled = i < list.size - 1 }
            down.setOnClickListener { if (i < list.size - 1) { list.add(i + 1, list.removeAt(i)); changed() } }
            val del = IconView(context, Icon.CLOSE).apply { tint = Ui.DANGER }
            del.setOnClickListener { list.removeAt(i); changed() }
            row.addView(up, LinearLayout.LayoutParams(38.dp, 38.dp))
            row.addView(down, LinearLayout.LayoutParams(38.dp, 38.dp))
            row.addView(del, LinearLayout.LayoutParams(38.dp, 38.dp))
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4.dp })
        }

        val rest = ToolCatalog.all.filter { it.id !in list }
        if (rest.isNotEmpty()) {
            box.addView(Ui.caption(context, "Add tools").apply { setPadding(0, 16.dp, 0, 2.dp) })
            for ((group, defs) in rest.groupBy { it.group }) {
                box.addView(Ui.text(context, group, 12f, Ui.TEXT_DIM).apply { setPadding(2.dp, 8.dp, 0, 4.dp) })
                val grid = GridLayout(context).apply { columnCount = 2 }
                for (d in defs) {
                    val chip = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = Ui.rounded(Ui.PANEL_ROW, 10f)
                        setPadding(4.dp, 0, 8.dp, 0)
                        setOnClickListener {
                            if (list.size >= ToolCatalog.MAX) return@setOnClickListener
                            list.add(d.id); changed()
                        }
                    }
                    chip.addView(IconView(context, d.icon).apply { isClickable = false }, LinearLayout.LayoutParams(34.dp, 36.dp))
                    chip.addView(Ui.text(context, d.label, 12.5f).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
                    chip.addView(IconView(context, Icon.PLUS).apply { isClickable = false; tint = Ui.ACCENT }, LinearLayout.LayoutParams(22.dp, 22.dp))
                    grid.addView(chip, GridLayout.LayoutParams().apply {
                        width = 0
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(2.dp, 2.dp, 2.dp, 2.dp)
                    })
                }
                box.addView(grid, LinearLayout.LayoutParams(-1, -2))
            }
        }

        box.addView(Ui.caption(context, "Where the toolbar sits").apply { setPadding(0, 18.dp, 0, 6.dp) })
        val seg = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(Ui.SUNKEN, 12f)
            setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        }
        for (e in listOf("Left", "Right", "Top", "Bottom")) {
            val on = e == edge
            seg.addView(Ui.text(context, e, 13.5f, if (on) Ui.ON_ACCENT else Ui.TEXT_DIM, on).apply {
                gravity = Gravity.CENTER
                setPadding(4.dp, 9.dp, 4.dp, 9.dp)
                background = if (on) Ui.rounded(Ui.ACCENT, 10f) else null
                setOnClickListener { if (!on) { edge = e; onEdge(e) } }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        box.addView(seg, LinearLayout.LayoutParams(-1, -2))
        box.addView(Ui.text(context, "The size and opacity sliders move to the other side. At the end of the toolbar: ⊞ shows every tool, « tucks it away.", 11.5f, Ui.TEXT_DIM)
            .apply { setPadding(4.dp, 6.dp, 4.dp, 0) })

        box.addView(Ui.pill(context, "Reset to default tools") {
            list.clear(); list.addAll(ToolCatalog.DEFAULT); changed()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.dp })
    }
}
