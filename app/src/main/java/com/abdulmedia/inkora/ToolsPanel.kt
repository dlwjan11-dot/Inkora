package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Creative & Editing tools: a two-tab panel with a category list on the left and
 * big visual cards on the right. Every card with a ☆ can be added to your toolbar.
 */
@SuppressLint("ViewConstructor")
class ToolsPanel(
    context: Context,
    private var creative: Boolean,
    private val host: Host
) : LinearLayout(context) {

    interface Host {
        /** runs a tool from [ToolCatalog] (or "feather_N") */
        fun run(id: String)
        fun isActive(id: String): Boolean
        fun inDock(id: String): Boolean
        fun toggleDock(id: String)
        val guide: GuideSettings
        fun guideChanged()
        fun openGuideSettings()
        var symmetry: Symmetry
        val quickShape: Boolean
        val hasSelection: Boolean
        val isVectorLayer: Boolean
        /** a small picture of the artwork for the adjustment previews */
        fun thumbnail(): Bitmap?
        fun close()
    }

    private class Cat(val key: String, val label: String, val icon: Icon, val hint: String)

    private val creativeCats = listOf(
        Cat("create", "Create", Icon.SPARKLE, "Add new things to your artwork"),
        Cat("guides", "Guides", Icon.GRID, "Grids and perspective help you draw straight"),
        Cat("shapes", "Shapes", Icon.SHAPE, "Perfect lines, circles and vector shapes"),
        Cat("symmetry", "Symmetry", Icon.SYMMETRY, "Every stroke is mirrored live"),
        Cat("view", "View", Icon.FIT, "See your canvas differently")
    )
    private val editingCats = listOf(
        Cat("select", "Select", Icon.SELECT, "Pick a part of the layer to work on"),
        Cat("transform", "Transform", Icon.TRANSFORM, "Move, scale, rotate and flip"),
        Cat("adjust", "Adjust", Icon.ADJUST, "Color and tone, with a live preview"),
        Cat("filters", "Filters", Icon.BLUR, "Blur, sharpen and texture")
    )

    private val tabs = LinearLayout(context)
    private val catColumn = LinearLayout(context)
    private val content = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var thumb: Bitmap? = null
    private var thumbDone = false

    init {
        orientation = VERTICAL
        background = Ui.rounded(Ui.PANEL, 20f, 0x14FFFFFF, 1)
        setPadding(0, 0, 0, 0)

        // ---- header: segmented tabs + close ----
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 12.dp, 8.dp, 10.dp)
        }
        tabs.orientation = HORIZONTAL
        tabs.background = Ui.rounded(0xFF121215.toInt(), 14f)
        tabs.setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        head.addView(tabs, LayoutParams(300.dp, 42.dp))
        head.addView(View(context), LayoutParams(0, 1, 1f))
        head.addView(IconView(context, Icon.CLOSE).apply {
            tint = Ui.TEXT_DIM
            setOnClickListener { host.close() }
        }, LayoutParams(40.dp, 40.dp))
        addView(head)
        addView(Ui.divider(context), LayoutParams(-1, 1.dp))

        // ---- body ----
        val body = LinearLayout(context).apply { orientation = HORIZONTAL }
        catColumn.orientation = VERTICAL
        catColumn.setPadding(8.dp, 10.dp, 8.dp, 10.dp)
        body.addView(catColumn, LayoutParams(150.dp, -1))
        body.addView(Ui.divider(context), LayoutParams(1.dp, -1))
        content.orientation = VERTICAL
        content.setPadding(16.dp, 14.dp, 16.dp, 18.dp)
        scroll.addView(content)
        scroll.isVerticalScrollBarEnabled = false
        body.addView(scroll, LayoutParams(0, -1, 1f))
        addView(body, LayoutParams(-1, 0, 1f))

        rebuild()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        exec.shutdownNow()
    }

    private fun cats() = if (creative) creativeCats else editingCats
    private fun currentCat(): String {
        val k = if (creative) lastCreative else lastEditing
        return if (cats().any { it.key == k }) k else cats().first().key
    }

    private fun rebuild() {
        // tabs
        tabs.removeAllViews()
        for (c in listOf(true, false)) {
            val on = c == creative
            val t = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                background = if (on) Ui.rounded(Ui.ACCENT, 11f) else null
                setOnClickListener { if (creative != c) { creative = c; rebuild() } }
            }
            t.addView(IconView(context, if (c) Icon.SPARKLE else Icon.WAND).apply {
                isClickable = false; tint = if (on) 0xFFFFFFFF.toInt() else Ui.TEXT_DIM
            }, LayoutParams(28.dp, 28.dp))
            t.addView(Ui.text(context, if (c) "Creative" else "Editing", 14f, if (on) 0xFFFFFFFF.toInt() else Ui.TEXT_DIM, on))
            tabs.addView(t, LayoutParams(0, -1, 1f))
        }
        // categories
        catColumn.removeAllViews()
        val cur = currentCat()
        for (c in cats()) {
            val on = c.key == cur
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(6.dp, 0, 6.dp, 0)
                background = if (on) Ui.rounded(Ui.ACCENT_SOFT, 12f) else null
                setOnClickListener {
                    if (creative) lastCreative = c.key else lastEditing = c.key
                    rebuild()
                }
            }
            row.addView(IconView(context, c.icon).apply { isClickable = false; tint = if (on) Ui.ACCENT else Ui.TEXT }, LayoutParams(34.dp, 34.dp))
            row.addView(Ui.text(context, c.label, 14f, if (on) Ui.ACCENT else Ui.TEXT, on), LayoutParams(0, -2, 1f).apply { leftMargin = 4.dp })
            catColumn.addView(row, LayoutParams(-1, 46.dp).apply { bottomMargin = 2.dp })
        }
        // content
        content.removeAllViews()
        val cat = cats().first { it.key == cur }
        content.addView(Ui.text(context, cat.label, 20f, bold = true))
        content.addView(Ui.text(context, cat.hint, 12.5f, Ui.TEXT_DIM).apply { setPadding(0, 2.dp, 0, 12.dp) })
        when (cur) {
            "create" -> buildCreate()
            "guides" -> buildGuides()
            "shapes" -> buildShapes()
            "symmetry" -> buildSymmetry()
            "view" -> buildView()
            "select" -> buildSelect()
            "transform" -> buildTransform()
            "adjust" -> buildAdjust(listOf(AdjustKind.HSB, AdjustKind.BRIGHT_CONTRAST, AdjustKind.COLOR_BALANCE,
                AdjustKind.POSTERIZE, AdjustKind.INVERT, AdjustKind.GRAYSCALE))
            "filters" -> buildAdjust(listOf(AdjustKind.BLUR, AdjustKind.SHARPEN, AdjustKind.NOISE))
        }
        scroll.scrollTo(0, 0)
    }

    // =========================================================
    // building blocks
    // =========================================================

    private fun grid(cards: List<View>, columns: Int = 3) {
        val g = GridLayout(context).apply { columnCount = columns }
        for (c in cards) g.addView(c, GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(4.dp, 4.dp, 4.dp, 4.dp)
        })
        content.addView(g, LayoutParams(-1, -2))
    }

    private fun caption(s: String) = content.addView(Ui.caption(context, s).apply { setPadding(4.dp, 14.dp, 0, 4.dp) })

    /**
     * A tool card. [id] = toolbar tool (shows the ☆ button), [art] replaces the icon picture.
     */
    private fun card(id: String?, title: String, sub: String, icon: Icon?, active: Boolean = false,
                     art: View? = null, artHeight: Int = 74, onClick: () -> Unit): View {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            background = Ui.rounded(if (active) Ui.PANEL_ROW_ACTIVE else Ui.CARD, 14f, if (active) Ui.ACCENT else 0x0FFFFFFF, 1)
            setPadding(6.dp, 6.dp, 6.dp, 9.dp)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val top = FrameLayout(context).apply {
            background = Ui.rounded(if (active) 0x224C8DFF else 0xFF1C1C21.toInt(), 10f)
        }
        if (art != null) top.addView(art, FrameLayout.LayoutParams(-1, -1))
        else if (icon != null) top.addView(IconView(context, icon).apply {
            isClickable = false
            this.active = false
            tint = if (active) Ui.ACCENT else Ui.TEXT
            iconScale = 0.75f
        }, FrameLayout.LayoutParams(46.dp, 46.dp, Gravity.CENTER))
        if (id != null) {
            val star = IconView(context, if (host.inDock(id)) Icon.STAR_FILLED else Icon.STAR).apply {
                tint = if (host.inDock(id)) Ui.ACCENT else 0x99FFFFFF.toInt()
                iconScale = 0.55f
                contentDescription = "Add to toolbar"
            }
            star.setOnClickListener {
                host.toggleDock(id)
                val on = host.inDock(id)
                star.icon = if (on) Icon.STAR_FILLED else Icon.STAR
                star.tint = if (on) Ui.ACCENT else 0x99FFFFFF.toInt()
            }
            top.addView(star, FrameLayout.LayoutParams(34.dp, 34.dp, Gravity.TOP or Gravity.END))
        }
        if (active) top.addView(Ui.text(context, "ON", 9.5f, 0xFFFFFFFF.toInt(), true).apply {
            background = Ui.rounded(Ui.ACCENT, 7f)
            setPadding(6.dp, 1.dp, 6.dp, 1.dp)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = 6.dp; topMargin = 6.dp })
        box.addView(top, LayoutParams(-1, artHeight.dp))
        box.addView(Ui.text(context, title, 13f, if (active) Ui.ACCENT else Ui.TEXT, true).apply {
            maxLines = 1; setPadding(4.dp, 7.dp, 4.dp, 0)
        })
        if (sub.isNotEmpty()) box.addView(Ui.text(context, sub, 11f, Ui.TEXT_DIM).apply { maxLines = 2; setPadding(4.dp, 1.dp, 4.dp, 0) })
        return box
    }

    private fun toolCard(id: String, active: Boolean = host.isActive(id), closeAfter: Boolean = true): View {
        val d = ToolCatalog.byId.getValue(id)
        return card(id, d.label, d.hint, d.icon, active) {
            if (closeAfter) host.close()
            host.run(id)
            if (!closeAfter) rebuild()
        }
    }

    private fun switchRow(icon: Icon, title: String, sub: String, checked: Boolean, dockId: String?, onToggle: (Boolean) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.CARD, 14f, 0x0FFFFFFF, 1)
            setPadding(10.dp, 8.dp, 12.dp, 8.dp)
        }
        row.addView(IconView(context, icon).apply { isClickable = false; this.active = checked }, LayoutParams(42.dp, 42.dp))
        val texts = LinearLayout(context).apply { orientation = VERTICAL; setPadding(8.dp, 0, 8.dp, 0) }
        texts.addView(Ui.text(context, title, 14f, bold = true))
        texts.addView(Ui.text(context, sub, 11.5f, Ui.TEXT_DIM))
        row.addView(texts, LayoutParams(0, -2, 1f))
        if (dockId != null) {
            val star = IconView(context, if (host.inDock(dockId)) Icon.STAR_FILLED else Icon.STAR).apply {
                tint = if (host.inDock(dockId)) Ui.ACCENT else 0x99FFFFFF.toInt(); iconScale = 0.55f
            }
            star.setOnClickListener {
                host.toggleDock(dockId)
                val on = host.inDock(dockId)
                star.icon = if (on) Icon.STAR_FILLED else Icon.STAR
                star.tint = if (on) Ui.ACCENT else 0x99FFFFFF.toInt()
            }
            row.addView(star, LayoutParams(36.dp, 36.dp))
        }
        row.addView(SwitchView(context).apply { this.checked = checked; this.onToggle = { onToggle(it); rebuild() } })
        return row
    }

    // =========================================================
    // Creative
    // =========================================================

    private fun buildCreate() {
        grid(listOf(toolCard("text"), toolCard("photo"), toolCard("vector"), toolCard("fill"), toolCard("eyedropper")))
        content.addView(Ui.text(context, "Tip: drag the color circle (top right) onto an area to fill it with ColorDrop.", 12f, Ui.TEXT_DIM)
            .apply { setPadding(4.dp, 12.dp, 4.dp, 0) })
    }

    private fun buildGuides() {
        val g = host.guide
        caption("Guide type")
        grid(GuideType.values().map { t ->
            card(null, if (t == GuideType.OFF) "Off" else t.label.replace(" Perspective", " persp."), "", null, g.type == t,
                art = GuidePreview(context, t, g.type == t), artHeight = 70) {
                g.type = t
                host.guideChanged()
                rebuild()
            }
        }, 4)
        content.addView(switchRow(Icon.RULER, "Assisted drawing", "Your strokes snap to the guide", g.assisted, "assisted") {
            if (g.type == GuideType.OFF && it) g.type = GuideType.SQUARE
            g.assisted = it
            host.guideChanged()
        }, LayoutParams(-1, -2).apply { topMargin = 12.dp })
        content.addView(Ui.pill(context, "Size, color & vanishing points…") { host.openGuideSettings() },
            LayoutParams(-1, -2).apply { topMargin = 10.dp })
    }

    private fun buildShapes() {
        content.addView(switchRow(Icon.SHAPE, "QuickShape", "Hold the pen still at the end of a stroke", host.quickShape, "quickshape") {
            host.run("quickshape")
        })
        caption("Vector")
        grid(listOf(
            card("vector", "Pen & shapes", "Bézier pen, rectangles, stars…", Icon.V_PEN, host.isVectorLayer) { host.close(); host.run("vector") }
        ))
    }

    private fun buildSymmetry() {
        grid(Symmetry.values().map { s ->
            card(null, s.label, when (s) {
                Symmetry.OFF -> "Normal drawing"
                Symmetry.VERTICAL -> "Left ⇄ right"
                Symmetry.HORIZONTAL -> "Top ⇄ bottom"
                Symmetry.QUADRANT -> "Four ways"
                Symmetry.RADIAL -> "Mandala, 8 ways"
                Symmetry.MANDALA_12 -> "Mandala, 12 ways"
                Symmetry.MANDALA_16 -> "Mandala, 16 ways"
            }, null, host.symmetry == s, art = SymmetryPreview(context, s, host.symmetry == s), artHeight = 84) {
                host.symmetry = s
                rebuild()
            }
        })
        content.addView(switchRow(Icon.SYMMETRY, "Symmetry in your toolbar", "One tap to turn it on or off", host.inDock("symmetry"), null) {
            host.toggleDock("symmetry")
        }, LayoutParams(-1, -2).apply { topMargin = 12.dp })
    }

    private fun buildView() {
        grid(listOf(toolCard("fit"), toolCard("flip"), toolCard("focus")))
        caption("Floating windows")
        grid(listOf(toolCard("navigator"), toolCard("reference"), toolCard("lighting")))
    }

    // =========================================================
    // Editing
    // =========================================================

    private fun buildSelect() {
        caption("Selection mode")
        grid(listOf(toolCard("sel_free"), toolCard("sel_rect"), toolCard("sel_ellipse")))
        caption("Actions")
        grid(listOf(toolCard("sel_all", false), toolCard("sel_invert", false), toolCard("deselect", false), toolCard("copy_paste", false)), 4)
        if (host.hasSelection) {
            caption("Feather (soft edge)")
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (r in listOf(4, 12, 30, 60)) {
                row.addView(Ui.pill(context, "$r px") { host.run("feather_$r") }, LayoutParams(0, -2, 1f).apply { rightMargin = 6.dp })
            }
            content.addView(row, LayoutParams(-1, -2))
        }
    }

    private fun buildTransform() {
        grid(listOf(toolCard("transform"), toolCard("copy_paste", false), toolCard("flip", false)))
        content.addView(Ui.text(context,
            "In Transform: drag to move · corners to scale · the green dot to rotate · two fingers to pinch. " +
                "Flip, rotate 90° and fit are in the bar at the bottom.", 12f, Ui.TEXT_DIM).apply { setPadding(4.dp, 12.dp, 4.dp, 0) })
    }

    private fun buildAdjust(kinds: List<AdjustKind>) {
        if (host.isVectorLayer) {
            content.addView(Ui.text(context, "Adjustments work on drawing layers. Pick a drawing layer first.", 13f, Ui.TEXT_DIM))
            return
        }
        if (host.hasSelection) content.addView(Ui.text(context, "Only the selected area will change.", 12f, Ui.ACCENT).apply { setPadding(4.dp, 0, 0, 8.dp) })
        val previews = ArrayList<Pair<AdjustKind, ImageView>>()
        grid(kinds.map { k ->
            val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; background = Ui.rounded(0xFF1C1C21.toInt(), 10f) }
            previews.add(k to img)
            val art = FrameLayout(context)
            art.addView(img, FrameLayout.LayoutParams(-1, -1))
            art.addView(IconView(context, ToolCatalog.iconFor(k)).apply {
                isClickable = false; iconScale = 0.6f
                background = Ui.rounded(0xB0141417.toInt(), 9f)
            }, FrameLayout.LayoutParams(28.dp, 28.dp, Gravity.BOTTOM or Gravity.START).apply { leftMargin = 5.dp; bottomMargin = 5.dp })
            card("adj_${k.name}", k.label, sliderHint(k), null, art = art, artHeight = 86) { host.close(); host.run("adj_${k.name}") }
        })
        loadPreviews(previews)
    }

    private fun sliderHint(k: AdjustKind) = if (k.sliders.isEmpty()) "One tap" else k.sliders.joinToString(" · ") { it.name }

    private fun loadPreviews(list: List<Pair<AdjustKind, ImageView>>) {
        if (!thumbDone) { thumb = host.thumbnail(); thumbDone = true }
        val src = thumb ?: return
        for ((k, iv) in list) {
            iv.setImageBitmap(src)
            exec.execute {
                val out = runCatching { Adjust.apply(k, src, previewValues(k, src.width)) }.getOrNull() ?: return@execute
                main.post { if (iv.isAttachedToWindow) iv.setImageBitmap(out) }
            }
        }
    }

    /** strong-enough sample values so every card shows its effect */
    private fun previewValues(k: AdjustKind, w: Int): FloatArray = when (k) {
        AdjustKind.HSB -> floatArrayOf(80f, 150f, 0f, 0f)
        AdjustKind.BRIGHT_CONTRAST -> floatArrayOf(10f, 45f, 0f, 0f)
        AdjustKind.COLOR_BALANCE -> floatArrayOf(45f, 0f, -35f, 0f)
        AdjustKind.BLUR -> floatArrayOf(w / 40f + 1.5f, 0f, 0f, 0f)
        AdjustKind.SHARPEN -> floatArrayOf(250f, 0f, 0f, 0f)
        AdjustKind.NOISE -> floatArrayOf(45f, 0f, 0f, 0f)
        AdjustKind.POSTERIZE -> floatArrayOf(4f, 0f, 0f, 0f)
        else -> FloatArray(4)
    }

    companion object {
        private var lastCreative = "create"
        private var lastEditing = "select"

        /** open the panel on this category next time */
        fun select(creative: Boolean, category: String) {
            if (creative) lastCreative = category else lastEditing = category
        }
    }
}

/** Mini picture of a drawing guide. */
@SuppressLint("ViewConstructor")
class GuidePreview(context: Context, private val type: GuideType, private val selected: Boolean) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.1f.dp }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pad = 8f.dp
        val r = RectF(pad, pad, w - pad, h - pad)
        p.color = if (selected) Ui.ACCENT else 0xB3FFFFFF.toInt()
        canvas.save()
        canvas.clipRect(r)
        val step = r.height() / 4f
        when (type) {
            GuideType.OFF -> {
                p.color = 0x55FFFFFF
                canvas.drawRect(r, p)
                canvas.drawLine(r.left, r.bottom, r.right, r.top, p)
            }
            GuideType.SQUARE -> {
                var x = r.left; while (x <= r.right + 0.5f) { canvas.drawLine(x, r.top, x, r.bottom, p); x += step }
                var y = r.top; while (y <= r.bottom + 0.5f) { canvas.drawLine(r.left, y, r.right, y, p); y += step }
            }
            GuideType.DOTS -> {
                p.style = Paint.Style.FILL
                var x = r.left; while (x <= r.right + 0.5f) {
                    var y = r.top; while (y <= r.bottom + 0.5f) { canvas.drawCircle(x, y, 1.5f.dp, p); y += step }
                    x += step
                }
                p.style = Paint.Style.STROKE
            }
            GuideType.ISOMETRIC, GuideType.TRIANGLE -> {
                val ang = if (type == GuideType.ISOMETRIC) 30.0 else 60.0
                val t = Math.tan(Math.toRadians(ang)).toFloat()
                val span = r.width() + r.height() * 2
                var k = -span; while (k < span) {
                    canvas.drawLine(r.left + k, r.bottom, r.left + k + r.height() / t, r.top, p)
                    canvas.drawLine(r.left + k, r.top, r.left + k + r.height() / t, r.bottom, p)
                    k += step * 1.4f
                }
                if (type == GuideType.ISOMETRIC) { var x = r.left; while (x <= r.right) { canvas.drawLine(x, r.top, x, r.bottom, p); x += step * 1.2f } }
                else { var y = r.top; while (y <= r.bottom) { canvas.drawLine(r.left, y, r.right, y, p); y += step * 1.2f } }
            }
            GuideType.HEXAGON -> {
                val s = step * 0.75f
                val hw = s * sqrt(3f)
                var row = 0
                var y = r.top
                while (y < r.bottom + s * 2) {
                    var x = r.left + (if (row % 2 == 1) hw / 2 else 0f)
                    while (x < r.right + hw) { hex(canvas, x, y, s); x += hw }
                    y += s * 1.5f; row++
                }
            }
            GuideType.POLAR -> {
                val cx = r.centerX(); val cy = r.centerY()
                for (i in 1..5) canvas.drawCircle(cx, cy, i * step * 0.6f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    canvas.drawLine(cx, cy, cx + cos(a).toFloat() * w, cy + sin(a).toFloat() * w, p)
                }
            }
            GuideType.THIRDS -> {
                for (i in 1..2) {
                    canvas.drawLine(r.left + r.width() * i / 3, r.top, r.left + r.width() * i / 3, r.bottom, p)
                    canvas.drawLine(r.left, r.top + r.height() * i / 3, r.right, r.top + r.height() * i / 3, p)
                }
                canvas.drawRect(r, p)
            }
            GuideType.ELLIPSE_RULER -> {
                val rr = min(r.width(), r.height()) / 2f
                canvas.drawCircle(r.centerX(), r.centerY(), rr * .85f, p)
                canvas.drawCircle(r.centerX(), r.centerY(), rr * .45f, p)
            }
            GuideType.PERSPECTIVE_1 -> rays(canvas, r.centerX(), r.centerY() - step * 0.3f, r, 14)
            GuideType.PERSPECTIVE_2 -> {
                val y = r.top + r.height() * 0.42f
                rays(canvas, r.left + 2f.dp, y, r, 9); rays(canvas, r.right - 2f.dp, y, r, 9)
            }
            GuideType.PERSPECTIVE_3 -> {
                val y = r.top + r.height() * 0.25f
                rays(canvas, r.left + 2f.dp, y, r, 7); rays(canvas, r.right - 2f.dp, y, r, 7); rays(canvas, r.centerX(), r.bottom - 1f.dp, r, 7)
            }
        }
        canvas.restore()
    }

    private fun rays(canvas: Canvas, x: Float, y: Float, r: RectF, n: Int) {
        val far = r.width() + r.height()
        for (i in 0 until n) {
            val a = Math.toRadians(i * 360.0 / n + 7)
            canvas.drawLine(x, y, x + cos(a).toFloat() * far, y + sin(a).toFloat() * far, p)
        }
        p.style = Paint.Style.FILL
        canvas.drawCircle(x, y, 2.5f.dp, p)
        p.style = Paint.Style.STROKE
    }

    private fun hex(canvas: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        for (k in 0 until 6) {
            val a = Math.toRadians(30.0 + 60.0 * k)
            val px = x + s * cos(a).toFloat(); val py = y + s * sin(a).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        canvas.drawPath(path, p)
    }
}

/** Mini picture of a symmetry mode: the axes and a mirrored stroke. */
@SuppressLint("ViewConstructor")
class SymmetryPreview(context: Context, private val sym: Symmetry, private val selected: Boolean) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f.dp; pathEffect = DashPathEffect(floatArrayOf(4f.dp, 3f.dp), 0f)
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2
        val s = min(w, h) / 2 - 8f.dp
        axis.color = if (selected) 0xAA4C8DFF.toInt() else 0x66FFFFFF
        when (sym) {
            Symmetry.VERTICAL -> canvas.drawLine(cx, cy - s, cx, cy + s, axis)
            Symmetry.HORIZONTAL -> canvas.drawLine(cx - s * 1.4f, cy, cx + s * 1.4f, cy, axis)
            Symmetry.QUADRANT -> { canvas.drawLine(cx, cy - s, cx, cy + s, axis); canvas.drawLine(cx - s * 1.4f, cy, cx + s * 1.4f, cy, axis) }
            Symmetry.RADIAL, Symmetry.MANDALA_12, Symmetry.MANDALA_16 -> {
                val n = sym.axes / 2
                for (i in 0 until n) {
                    val a = Math.toRadians(i * (180.0 / n))
                    canvas.drawLine(cx - cos(a).toFloat() * s, cy - sin(a).toFloat() * s, cx + cos(a).toFloat() * s, cy + sin(a).toFloat() * s, axis)
                }
            }
            Symmetry.OFF -> {}
        }
        // a little curl in the top-left quarter
        path.reset()
        path.moveTo(cx - s * 0.15f, cy - s * 0.2f)
        path.cubicTo(cx - s * 0.9f, cy - s * 0.1f, cx - s * 0.4f, cy - s * 0.95f, cx - s * 0.75f, cy - s * 0.7f)
        line.strokeWidth = 2.6f.dp
        line.shader = LinearGradient(cx - s, cy - s, cx, cy, if (selected) Ui.ACCENT else 0xFFFFFFFF.toInt(), 0xFFFF8A5B.toInt(), Shader.TileMode.CLAMP)
        val copies: List<(Canvas) -> Unit> = when (sym) {
            Symmetry.OFF -> listOf({ _ -> })
            Symmetry.VERTICAL -> listOf({ _ -> }, { c -> c.scale(-1f, 1f, cx, cy) })
            Symmetry.HORIZONTAL -> listOf({ _ -> }, { c -> c.scale(1f, -1f, cx, cy) })
            Symmetry.QUADRANT -> listOf({ _ -> }, { c -> c.scale(-1f, 1f, cx, cy) }, { c -> c.scale(1f, -1f, cx, cy) }, { c -> c.scale(-1f, -1f, cx, cy) })
            Symmetry.RADIAL, Symmetry.MANDALA_12, Symmetry.MANDALA_16 -> (0 until sym.axes).map { i -> { c: Canvas -> c.rotate(i * (360f / sym.axes), cx, cy) } }
        }
        for (t in copies) {
            canvas.save()
            t(canvas)
            canvas.drawPath(path, line)
            canvas.restore()
        }
        line.shader = null
    }
}
