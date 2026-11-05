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
        AdjustKind.INVERT -> Icon.INVERT
        AdjustKind.GRAYSCALE -> Icon.BW
    }

    fun iconFor(k: AdjustKind) = adjIcon(k)

    val all: List<ToolDef> = listOf(
        ToolDef("sel_free", "Freehand select", Icon.SELECT, "Selection", "Draw around an area"),
        ToolDef("sel_rect", "Rectangle select", Icon.SEL_RECT, "Selection", "Drag a box"),
        ToolDef("sel_ellipse", "Ellipse select", Icon.SEL_ELLIPSE, "Selection", "Drag an oval"),
        ToolDef("sel_all", "Select all", Icon.SEL_ALL, "Selection", "The whole layer"),
        ToolDef("sel_invert", "Invert selection", Icon.SEL_INVERT, "Selection", "Swap inside / outside"),
        ToolDef("deselect", "Deselect", Icon.DESELECT, "Selection", "Remove the selection"),
        ToolDef("copy_paste", "Copy & paste", Icon.COPY, "Selection", "Selection to a new layer"),
        ToolDef("transform", "Transform", Icon.TRANSFORM, "Transform", "Move, scale, rotate, flip"),
        ToolDef("eyedropper", "Eyedropper", Icon.EYEDROPPER, "Color", "Pick a color from the canvas"),
        ToolDef("fill", "Fill", Icon.FILL, "Color", "Tap areas to fill them"),
        ToolDef("text", "Text", Icon.TEXT, "Create", "Any language, 9 fonts"),
        ToolDef("photo", "Insert photo", Icon.PHOTO, "Create", "As a new layer"),
        ToolDef("vector", "Vector layer", Icon.VECTOR, "Create", "Pen, shapes, logos"),
        ToolDef("pose3d", "3D & poses", Icon.CUBE, "Create", "Posable figures, objects and 3D models"),
        ToolDef("shadow_photo", "Shadow photo", Icon.PHOTO, "Create", "Trace a photo, kept out of exports"),
        ToolDef("guides", "Grid & perspective", Icon.GRID, "Guides", "All grid types"),
        ToolDef("assisted", "Assisted drawing", Icon.RULER, "Guides", "Strokes snap to the grid"),
        ToolDef("quickshape", "QuickShape", Icon.SHAPE, "Guides", "Hold to make perfect shapes"),
        ToolDef("symmetry", "Symmetry", Icon.SYMMETRY, "Guides", "Mirror your strokes"),
        ToolDef("adjust", "Adjustments", Icon.ADJUST, "Adjust", "All adjustments & filters")
    ) + AdjustKind.values().map { k ->
        ToolDef("adj_${k.name}", k.label, adjIcon(k), "Adjust")
    } + listOf(
        ToolDef("fit", "Fit to screen", Icon.FIT, "View", "Center the canvas"),
        ToolDef("flip", "Flip view", Icon.FLIP, "View", "Check your drawing mirrored"),
        ToolDef("actual_size", "Actual size", Icon.FIT, "View", "100% zoom"),
        ToolDef("reference", "Reference image", Icon.REFERENCE, "View", "Floating reference window"),
        ToolDef("navigator", "Navigator", Icon.NAVIGATOR, "View", "Floating canvas thumbnail"),
        ToolDef("lighting", "3D Lighting orb", Icon.ORB, "View", "3D reference sphere for light and shade"),
        ToolDef("focus", "Focus mode", Icon.FOCUS, "View", "Hide the interface"),
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
 * The customizable toolbar: a vertical strip of your favorite tools.
 * Tap = use the tool. Long-press (or the last button) = customize.
 */
@SuppressLint("ViewConstructor")
class ToolDockView(
    context: Context,
    private val onTool: (String, View) -> Unit,
    private val isActive: (String) -> Boolean,
    private val onCustomize: () -> Unit
) : LinearLayout(context) {

    private val column = LinearLayout(context)
    private val scroll = ScrollView(context)
    /** used instead of [scroll] when the toolbar sits on the top or bottom edge */
    private val hscroll = android.widget.HorizontalScrollView(context)
    private val divider: View
    private val buttons = LinkedHashMap<String, IconView>()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(4.dp, 6.dp, 4.dp, 6.dp)
        background = Ui.rounded(Ui.BAR, 16f, 0x14FFFFFF, 1)
        elevation = 8f.dp
        column.orientation = VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.addView(column)
        addView(scroll, LayoutParams(-2, 0, 1f))
        hscroll.isHorizontalScrollBarEnabled = false
        hscroll.overScrollMode = OVER_SCROLL_NEVER
        divider = Ui.divider(context)
        addView(divider, LayoutParams(28.dp, 1.dp).apply { topMargin = 4.dp; bottomMargin = 4.dp })
        val edit = IconView(context, Icon.CUSTOMIZE).apply { tint = Ui.TEXT_DIM }
        edit.setOnClickListener { onCustomize() }
        addView(edit, LayoutParams(46.dp, 42.dp))
    }

    fun setDockOrientation(orient: Int) {
        if (orientation == orient && column.parent != null) return
        orientation = orient
        column.orientation = orient
        gravity = if (orient == HORIZONTAL) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        column.gravity = gravity
        // a vertical ScrollView cannot scroll sideways: swap in a HorizontalScrollView
        (column.parent as? android.view.ViewGroup)?.removeView(column)
        val old = getChildAt(0)
        if (old === scroll || old === hscroll) removeViewAt(0)
        if (orient == HORIZONTAL) {
            hscroll.addView(column)
            addView(hscroll, 0, LayoutParams(0, -2, 1f))
            divider.layoutParams = LayoutParams(1.dp, 28.dp).apply { leftMargin = 4.dp; rightMargin = 4.dp }
        } else {
            scroll.addView(column)
            addView(scroll, 0, LayoutParams(-2, 0, 1f))
            divider.layoutParams = LayoutParams(28.dp, 1.dp).apply { topMargin = 4.dp; bottomMargin = 4.dp }
        }
    }

    fun setTools(ids: List<String>) {
        column.removeAllViews()
        buttons.clear()
        for (id in ids) {
            val def = ToolCatalog.byId[id] ?: continue
            val b = IconView(context, def.icon)
            b.contentDescription = def.label
            b.setOnClickListener { onTool(id, b) }
            b.setOnLongClickListener { onCustomize(); true }
            buttons[id] = b
            column.addView(b, LayoutParams(46.dp, 46.dp))
        }
        refresh()
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
    private var leftHanded: Boolean,
    private val onChange: (List<String>) -> Unit,
    private val onLeftHanded: (Boolean) -> Unit
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

        box.addView(Ui.caption(context, "Layout").apply { setPadding(0, 18.dp, 0, 6.dp) })
        val lh = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(context, "Left-handed layout", 14f))
        texts.addView(Ui.text(context, "Sliders on the right, toolbar on the left", 11.5f, Ui.TEXT_DIM))
        lh.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        lh.addView(SwitchView(context).apply {
            checked = leftHanded
            onToggle = { leftHanded = it; onLeftHanded(it) }
        })
        box.addView(lh)

        box.addView(Ui.pill(context, "Reset to default tools") {
            list.clear(); list.addAll(ToolCatalog.DEFAULT); changed()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14.dp })
    }
}
