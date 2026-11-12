package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/** One place in the tool bar: a tool, or sister tools that share the place (the last one used shows). */
class RailSlot(val key: String, val label: String, val family: String, val tools: List<String>)

object RailTools {
    val paint = mapOf(
        "brush" to ToolDef("brush", "Brush", Icon.BRUSH, "Paint", "Paint. Tap it again for the brushes"),
        "smudge" to ToolDef("smudge", "Smudge", Icon.SMUDGE, "Paint", "Blend colors. Tap it again for the brushes"),
        "eraser" to ToolDef("eraser", "Eraser", Icon.ERASER, "Paint", "Erase. Tap it again for the brushes")
    )

    fun def(id: String): ToolDef? = paint[id] ?: ToolCatalog.byId[id]

    val slots = listOf(
        RailSlot("brush", "Brush", "paint", listOf("brush")),
        RailSlot("smudge", "Smudge", "paint", listOf("smudge")),
        RailSlot("eraser", "Eraser", "paint", listOf("eraser")),
        RailSlot("fill", "Fill tools", "color", listOf("fill", "gradient", "recolor")),
        RailSlot("eyedropper", "Eyedropper", "color", listOf("eyedropper")),
        RailSlot("select", "Selection tools", "edit", listOf("sel_free", "sel_rect", "sel_ellipse", "sel_wand", "sel_color", "sel_brush")),
        RailSlot("transform", "Transform tools", "edit", listOf("transform", "liquify", "canvas")),
        RailSlot("shape", "Shape tools", "make", listOf("shapes", "vector")),
        RailSlot("text", "Text", "make", listOf("text")),
        RailSlot("measure", "Measure", "make", listOf("measure")),
        // not in the tool bar at first: add them from ⋯
        RailSlot("view", "View tools", "view", listOf("fit", "actual_size", "flip", "grey_view")),
        RailSlot("windows", "Floating windows", "view", listOf("reference", "navigator", "lighting")),
        RailSlot("help", "Drawing help", "view", listOf("guides", "assisted", "quickshape", "symmetry")),
        RailSlot("copy", "Copy & paste", "more", listOf("copy_paste")),
        RailSlot("snapshots", "Snapshots", "more", listOf("snapshots")),
        RailSlot("focus", "Focus mode", "more", listOf("focus"))
    )
    val byKey = slots.associateBy { it.key }
    val defaults = listOf("brush", "smudge", "eraser", "fill", "eyedropper", "select", "transform", "shape", "text", "measure")
}

/**
 * The tool bar, like a desktop painting program's: every tool you use is always on it, docked on the
 * left or right edge. A place with a small corner triangle holds sister tools: hold it (or tap it again)
 * and they open beside it; the one you pick takes the place.
 *
 * Three ways to have it: docked (icons), wide (every sister tool with its name), or floating (a small
 * bar of only the tools you keep, which you can drag anywhere). On a phone it sits along the bottom.
 * ⋯ edits it: take tools out, add them back, change the order.
 */
@SuppressLint("ViewConstructor")
class ToolRail(context: Context, private val prefs: SharedPreferences, private val host: Host) : LinearLayout(context) {

    interface Host {
        /** [again]: the tool was already the one in hand */
        fun pick(id: String, anchor: View, again: Boolean)
        fun isActive(id: String): Boolean
        /** the rail changed shape or mode: place it (and what depends on it) again */
        fun layoutChanged()
        fun side(): String
        fun setSide(side: String)
    }

    companion object {
        const val DOCK = "dock"
        const val WIDE = "wide"
        const val FLOAT = "float"
        const val DOCK_W = 60
        const val WIDE_W = 220
        const val PHONE_H = 58
    }

    var mode: String = prefs.getString("rail_mode", DOCK).let { if (it == WIDE || it == FLOAT) it else DOCK }
        private set

    /** a phone: the rail lies along the bottom */
    var horizontal = false
        set(v) { if (field != v) { field = v; rebuild() } }

    private var order: MutableList<String> = loadOrder()
    private val views = ArrayList<Pair<List<String>, IconView>>()
    private var editor: PopupWindow? = null

    private fun loadOrder(): MutableList<String> {
        val saved = prefs.getString("rail_order", null) ?: return RailTools.defaults.toMutableList()
        return saved.split(',').filter { it in RailTools.byKey }.distinct().toMutableList()
    }

    private fun saveOrder() = prefs.edit().putString("rail_order", order.joinToString(",")).apply()

    private fun shown() = order.mapNotNull { RailTools.byKey[it] }

    fun variant(slot: RailSlot): String {
        val v = prefs.getString("rail_var_" + slot.key, null)
        return if (v != null && v in slot.tools) v else slot.tools.first()
    }

    private fun setVariant(slot: RailSlot, id: String) = prefs.edit().putString("rail_var_" + slot.key, id).apply()

    /** the place a tool lives in, so picking it from elsewhere shows it on the rail */
    fun remember(id: String) {
        val slot = RailTools.slots.firstOrNull { id in it.tools } ?: return
        if (slot.tools.size > 1 && variant(slot) != id) { setVariant(slot, id); rebuild() }
    }

    fun setMode(m: String) {
        if (m == mode) return
        mode = m
        prefs.edit().putString("rail_mode", m).apply()
        rebuild()
        host.layoutChanged()
    }

    init { rebuild() }

    fun rebuild() {
        removeAllViews()
        views.clear()
        elevation = 0f
        when {
            horizontal -> buildPhone()
            mode == FLOAT -> buildFloat()
            mode == WIDE -> buildWide()
            else -> buildDock()
        }
        refresh()
    }

    fun refresh() {
        for ((ids, v) in views) {
            val on = ids.firstOrNull { host.isActive(it) }
            v.active = on != null
            // a sister tool picked somewhere else shows in its place while it is on
            if (ids.size > 1) RailTools.def(on ?: (v.tag as? String ?: ids.first()))?.let { if (v.icon != it.icon) { v.icon = it.icon; v.invalidate() } }
        }
    }

    // ---------------------------------------------------------------- pieces

    private fun slotButton(slot: RailSlot, size: Int = 46): IconView {
        val id = variant(slot)
        val def = RailTools.def(id)
        val b = IconView(context, def?.icon ?: Icon.BRUSH)
        b.contentDescription = def?.label ?: slot.label
        b.tip = def?.hint
        b.corner = slot.tools.size > 1
        b.setOnClickListener {
            val again = host.isActive(id)
            if (again && slot.tools.size > 1) showSisters(slot, b) else host.pick(id, b, again)
        }
        if (slot.tools.size > 1) b.setOnLongClickListener { showSisters(slot, b); true }
        b.tag = id
        views.add(slot.tools to b)
        return b
    }

    private fun showSisters(slot: RailSlot, anchor: View) {
        val cur = variant(slot)
        val m = InkMenu(context, anchor)
        m.beside = !horizontal
        slot.tools.forEachIndexed { i, t ->
            val d = RailTools.def(t) ?: return@forEachIndexed
            m.menu.add(0, i, i, d.label).icon(d.icon).isChecked = t == cur
        }
        L10n.menu(m.menu)
        m.setOnMenuItemClickListener { item ->
            val t = slot.tools[item.itemId]
            setVariant(slot, t)
            rebuild()
            val v = views.firstOrNull { t in it.first }?.second ?: this
            post { host.pick(t, v, false) }
            true
        }
        m.show()
    }

    private fun divider(vertical: Boolean) = View(context).apply { setBackgroundColor(Ui.DIVIDER) }.also {
        it.layoutParams = if (vertical) LayoutParams(30.dp, 1.dp).apply { topMargin = 5.dp; bottomMargin = 5.dp }
            else LayoutParams(1.dp, 30.dp).apply { leftMargin = 5.dp; rightMargin = 5.dp }
    }

    private fun small(icon: Icon, desc: String, tip: String?, onClick: () -> Unit) = IconView(context, icon).apply {
        tint = Ui.TEXT_DIM
        iconScale = 0.5f
        contentDescription = desc
        this.tip = tip
        setOnClickListener { onClick() }
    }

    private fun moreButton() = small(Icon.MORE, "Edit tool bar", "Add tools, take them out, change the order") { showEditor() }

    /** the »/« button: open wide or back to icons (the arrows point the way it grows) */
    private fun wideButton(): IconView {
        val toRight = host.side() != "Right"
        return small(Icon.HIDE, if (mode == WIDE) "Show fewer" else "Show every tool", null) {
            setMode(if (mode == WIDE) DOCK else WIDE)
        }.apply { rotation = if ((mode == WIDE) == toRight) 0f else 180f }
    }

    private fun floatButton() = small(Icon.FLOAT, "Floating tool bar", "Hide the bars; keep only your tools in a small bar you can move") { setMode(FLOAT) }

    private fun addTools(col: LinearLayout, vertical: Boolean, size: Int) {
        var family: String? = null
        for (slot in shown()) {
            if (family != null && slot.family != family) col.addView(divider(vertical))
            family = slot.family
            col.addView(slotButton(slot), LayoutParams(size.dp, size.dp).apply {
                if (vertical) { topMargin = 1.dp; bottomMargin = 1.dp } else { leftMargin = 1.dp; rightMargin = 1.dp }
            })
        }
    }

    private fun edgeBackground() = ColorDrawable(Ui.BAR)

    // ---------------------------------------------------------------- the four shapes

    private fun buildDock() {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 6.dp, 0, 8.dp)
        background = edgeBackground()
        addView(wideButton(), LayoutParams(46.dp, 34.dp))
        addView(floatButton(), LayoutParams(46.dp, 34.dp))
        addView(divider(true))
        val col = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        addTools(col, true, 46)
        col.addView(moreButton(), LayoutParams(46.dp, 40.dp).apply { topMargin = 4.dp })
        val sc = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(col) }
        addView(sc, LayoutParams(-2, 0, 1f))
    }

    private fun buildWide() {
        orientation = VERTICAL
        gravity = Gravity.START
        setPadding(8.dp, 6.dp, 8.dp, 8.dp)
        background = edgeBackground()
        addView(textRow(Icon.HIDE, "Show fewer", Ui.TEXT_DIM, rot = if (host.side() == "Right") 180f else 0f) { setMode(DOCK) }, LayoutParams(-1, 36.dp))
        addView(textRow(Icon.FLOAT, "Floating", Ui.TEXT_DIM) { setMode(FLOAT) }, LayoutParams(-1, 36.dp))
        addView(View(context).apply { setBackgroundColor(Ui.DIVIDER) }, LayoutParams(-1, 1.dp).apply { topMargin = 4.dp; bottomMargin = 4.dp })
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        var family: String? = null
        for (slot in shown()) {
            if (family != null && slot.family != family) col.addView(View(context).apply { setBackgroundColor(Ui.DIVIDER) },
                LayoutParams(-1, 1.dp).apply { topMargin = 5.dp; bottomMargin = 5.dp; leftMargin = 6.dp; rightMargin = 6.dp })
            family = slot.family
            for (t in slot.tools) {
                val d = RailTools.def(t) ?: continue
                lateinit var ic: IconView
                val row = textRow(d.icon, d.label, Ui.TEXT) {
                    val again = host.isActive(t)
                    if (slot.tools.size > 1) setVariant(slot, t)
                    host.pick(t, ic, again)
                }
                ic = row.getChildAt(0) as IconView
                ic.tip = d.hint
                views.add(listOf(t) to ic)
                col.addView(row, LayoutParams(-1, 40.dp))
            }
        }
        col.addView(textRow(Icon.MORE, "Edit tool bar", Ui.TEXT_DIM) { showEditor() }, LayoutParams(-1, 40.dp).apply { topMargin = 4.dp })
        val sc = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(col) }
        addView(sc, LayoutParams(-1, 0, 1f))
    }

    private fun textRow(icon: Icon, label: String, color: Int, rot: Float = 0f, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp, 0, 8.dp, 0)
            foreground = Ui.ripple(10f)
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(IconView(context, icon).apply { isClickable = false; tint = color; iconScale = 0.6f; rotation = rot }, LayoutParams(36.dp, 36.dp))
        row.addView(Ui.text(context, label, 13.5f, color).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LayoutParams(0, -2, 1f).apply { marginStart = 8.dp })
        return row
    }

    private fun buildFloat() {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(4.dp, 2.dp, 4.dp, 6.dp)
        background = Ui.rounded(Ui.BAR, 18f, Ui.OUTLINE, 1)
        elevation = 10f.dp
        // drag the dots to move the bar; it stays where you leave it
        val grip = IconView(context, Icon.GRIP).apply { tint = Ui.TEXT_DIM; iconScale = 0.7f; contentDescription = "Move the tool bar" }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        grip.setOnTouchListener { _, e ->
            val lp = layoutParams as? FrameLayout.LayoutParams ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.leftMargin; oy = lp.topMargin }
                MotionEvent.ACTION_MOVE -> {
                    val p = parent as? View ?: return@setOnTouchListener true
                    lp.leftMargin = (ox + (e.rawX - sx).toInt()).coerceIn(0, (p.width - width).coerceAtLeast(0))
                    lp.topMargin = (oy + (e.rawY - sy).toInt()).coerceIn(56.dp, (p.height - height).coerceAtLeast(56.dp))
                    layoutParams = lp
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    prefs.edit().putInt("rail_float_x", lp.leftMargin).putInt("rail_float_y", lp.topMargin).apply()
            }
            true
        }
        addView(grip, LayoutParams(40.dp, 22.dp))
        val col = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        addTools(col, true, 46)
        val sc = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(col) }
        addView(sc, LayoutParams(-2, 0, 1f))
        addView(divider(true))
        addView(small(Icon.MORE, "Edit tool bar", null) { showEditor() }, LayoutParams(46.dp, 34.dp))
        addView(small(Icon.DOCK, "Dock the tool bar", "Back to the edge of the screen") { setMode(DOCK) }, LayoutParams(46.dp, 34.dp))
    }

    private fun buildPhone() {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(4.dp, 0, 4.dp, 0)
        background = edgeBackground()
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addTools(row, false, 46)
        row.addView(moreButton(), LayoutParams(42.dp, 46.dp))
        val hs = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(row) }
        addView(hs, LayoutParams(-1, -1))
    }

    // ---------------------------------------------------------------- ⋯ edit the tool bar

    fun showEditor() {
        editor?.dismiss()
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            background = Ui.rounded(Ui.PANEL, 18f, Ui.OUTLINE, 1)
            clipToOutline = true
            layoutDirection = if (L10n.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val body = LinearLayout(context).apply { orientation = VERTICAL; setPadding(10.dp, 4.dp, 10.dp, 12.dp) }
        fun fill() {
            body.removeAllViews()
            if (!horizontal) {
                body.addView(Ui.caption(context, "Tool bar").apply { setPadding(6.dp, 8.dp, 0, 6.dp) })
                body.addView(segment(listOf(DOCK to "Docked", WIDE to "Wide", FLOAT to "Floating"), mode) { setMode(it); fill() })
                body.addView(Ui.caption(context, "Side").apply { setPadding(6.dp, 12.dp, 0, 6.dp) })
                body.addView(segment(listOf("Left" to "Left", "Right" to "Right"), host.side()) { host.setSide(it); rebuild(); fill() })
            }
            body.addView(Ui.text(context, "Tools you take out stay here: tap one to use it.", 12f, Ui.TEXT_DIM).apply { setPadding(6.dp, 14.dp, 6.dp, 2.dp) })
            body.addView(Ui.caption(context, "In the tool bar").apply { setPadding(6.dp, 10.dp, 0, 4.dp) })
            val list = shown()
            list.forEachIndexed { i, slot ->
                body.addView(editRow(slot, true, i > 0, i < list.size - 1,
                    up = { order.removeAt(i); order.add(i - 1, slot.key); saveOrder(); rebuild(); fill() },
                    down = { order.removeAt(i); order.add(i + 1, slot.key); saveOrder(); rebuild(); fill() },
                    toggle = { if (order.size > 1) { order.remove(slot.key); saveOrder(); rebuild(); host.layoutChanged(); fill() } }))
            }
            val hidden = RailTools.slots.filter { it.key !in order }
            if (hidden.isNotEmpty()) {
                body.addView(Ui.caption(context, "More tools").apply { setPadding(6.dp, 14.dp, 0, 4.dp) })
                for (slot in hidden) body.addView(editRow(slot, false, false, false, {}, {},
                    toggle = { order.add(slot.key); saveOrder(); rebuild(); host.layoutChanged(); fill() }))
            }
        }
        // header
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 0, 6.dp, 0)
        }
        head.addView(Ui.text(context, "Edit tool bar", 17f, bold = true), LayoutParams(0, -2, 1f))
        head.addView(Ui.text(context, "Reset", 13f, Ui.TEXT).apply {
            setPadding(12.dp, 7.dp, 12.dp, 7.dp)
            background = Ui.rounded(Ui.CARD, 9f)
            setOnClickListener {
                order = RailTools.defaults.toMutableList(); saveOrder()
                for (s in RailTools.slots) prefs.edit().remove("rail_var_" + s.key).apply()
                rebuild(); host.layoutChanged(); fill()
            }
        })
        head.addView(IconView(context, Icon.CLOSE).apply { tint = Ui.TEXT_DIM; iconScale = 0.5f; contentDescription = "Close"; setOnClickListener { editor?.dismiss() } },
            LayoutParams(40.dp, 40.dp).apply { marginStart = 4.dp })
        box.addView(head, LayoutParams(-1, 54.dp))
        box.addView(Ui.divider(context), LayoutParams(-1, 1.dp))
        box.addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(body) }, LayoutParams(-1, 0, 1f))
        fill()

        val root = rootView
        val rw = root.width; val rh = root.height
        val loc = IntArray(2); getLocationInWindow(loc)
        val w: Int; val h: Int; val x: Int; val y: Int
        if (horizontal) {
            w = rw; h = (rh * 0.7f).toInt(); x = 0; y = rh - h - height
        } else {
            w = minOf(380.dp, rw - 24.dp); h = minOf(660.dp, rh - 90.dp)
            val right = loc[0] + width + 8.dp
            x = if (right + w <= rw - 8.dp) right else (loc[0] - w - 8.dp).coerceAtLeast(8.dp)
            y = 64.dp
        }
        val pw = PopupWindow(box, w, h, true).apply {
            setBackgroundDrawable(ColorDrawable(0))
            isOutsideTouchable = true
            elevation = 16f.dp
            animationStyle = android.R.style.Animation_Dialog
        }
        editor = pw
        runCatching { pw.showAtLocation(root, Gravity.TOP or Gravity.LEFT, x, y) }
    }

    private fun segment(options: List<Pair<String, String>>, chosen: String, onPick: (String) -> Unit): View {
        val seg = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = Ui.rounded(Ui.SUNKEN, 12f)
            setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        }
        for ((key, label) in options) {
            val on = key == chosen
            seg.addView(Ui.text(context, label, 13.5f, if (on) Ui.ON_ACCENT else Ui.TEXT_DIM, on).apply {
                gravity = Gravity.CENTER
                setPadding(4.dp, 9.dp, 4.dp, 9.dp)
                background = if (on) Ui.rounded(Ui.ACCENT, 10f) else null
                setOnClickListener { if (!on) onPick(key) }
            }, LayoutParams(0, -2, 1f))
        }
        return seg
    }

    private fun editRow(slot: RailSlot, inBar: Boolean, canUp: Boolean, canDown: Boolean,
                        up: () -> Unit, down: () -> Unit, toggle: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(2.dp, 2.dp, 4.dp, 2.dp)
        }
        if (inBar) {
            row.addView(small(Icon.UP, "Move up", null) { up() }.apply { isEnabled = canUp; alpha = if (canUp) 1f else 0.3f }, LayoutParams(32.dp, 40.dp))
            row.addView(small(Icon.DOWN, "Move down", null) { down() }.apply { isEnabled = canDown; alpha = if (canDown) 1f else 0.3f }, LayoutParams(32.dp, 40.dp))
        }
        val id = variant(slot)
        val ic = IconView(context, RailTools.def(id)?.icon ?: Icon.BRUSH).apply {
            background = Ui.rounded(Ui.SUNKEN, 10f)
            iconScale = 0.55f
            contentDescription = RailTools.def(id)?.label
            // a tool that is not in the tool bar is used from here
            setOnClickListener { editor?.dismiss(); host.pick(id, this@ToolRail, host.isActive(id)) }
        }
        row.addView(ic, LayoutParams(38.dp, 38.dp).apply { marginStart = if (inBar) 2.dp else 6.dp })
        val texts = LinearLayout(context).apply { orientation = VERTICAL; setPadding(10.dp, 0, 6.dp, 0) }
        texts.addView(Ui.text(context, slot.label, 14f))
        if (slot.tools.size > 1) texts.addView(Ui.text(context, slot.tools.mapNotNull { RailTools.def(it)?.label?.let { l -> L10n.tr(l) } }.joinToString(", "), 11.5f, Ui.TEXT_DIM).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(texts, LayoutParams(0, -2, 1f))
        row.addView(Ui.text(context, if (inBar) "Take out" else "Add", 12.5f, if (inBar) Ui.TEXT else Ui.ON_ACCENT, true).apply {
            gravity = Gravity.CENTER
            setPadding(12.dp, 7.dp, 12.dp, 7.dp)
            background = Ui.rounded(if (inBar) Ui.CARD else Ui.ACCENT, 9f)
            setOnClickListener { toggle() }
        })
        return row.also { it.layoutParams = LayoutParams(-1, -2).apply { topMargin = 2.dp } }
    }

    fun dismissEditor() { editor?.dismiss() }
}
