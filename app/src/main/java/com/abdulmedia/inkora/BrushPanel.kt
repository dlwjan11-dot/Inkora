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
    private val onExportSet: ((String, List<Brush>) -> Unit)? = null
) : LinearLayout(context) {

    private val setsColumn = LinearLayout(context)
    private val list = LinearLayout(context)
    private val listScroll = ScrollView(context)
    private var currentSet: String = selected.preset.category

    init {
        orientation = VERTICAL
        setPadding(14.dp, 14.dp, 14.dp, 14.dp)
        background = Ui.rounded(Ui.PANEL, 22f, 0x14FFFFFF, 1)
        isClickable = true
        setsColumn.orientation = VERTICAL
        list.orientation = VERTICAL
        listScroll.isVerticalScrollBarEnabled = false
        listScroll.addView(list)
        rebuild()
    }

    private fun sets(): List<String> {
        val seen = LinkedHashSet<String>()
        for (b in brushes) if (!b.preset.imported) seen.add(b.preset.category)
        for (b in brushes) if (b.preset.imported) seen.add(b.preset.category)
        return seen.toList()
    }

    private fun iconFor(set: String, imported: Boolean): Icon = when {
        imported -> Icon.FOLDER
        set.startsWith("Ink") -> Icon.INK
        set.startsWith("Callig") -> Icon.NIB
        set.startsWith("Sketch") -> Icon.PENCIL
        set.startsWith("Paint") -> Icon.PALETTE
        set.startsWith("Water") -> Icon.DROP
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

    private fun rebuild() {
        removeAllViews()
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(6.dp, 0, 0, 10.dp) }
        head.addView(Ui.text(context, title, 22f, bold = true), LayoutParams(0, -2, 1f))
        if (onPin != null) {
            head.addView(IconView(context, Icon.PIN).apply {
                contentDescription = L10n.tr("Pin")
                setOnClickListener { onPin.invoke() }
            }, LayoutParams(40.dp, 40.dp))
        }
        val plus = IconView(context, Icon.PLUS).apply { contentDescription = L10n.tr("Import brushes…") }
        plus.setOnClickListener {
            val pm = android.widget.PopupMenu(context, plus)
            pm.menu.add(0, 1, 1, "Import file…")
            pm.menu.add(0, 2, 2, "Import from link…")
            if (onNewBrush != null) pm.menu.add(0, 3, 3, "New brush from this one")
            pm.setOnMenuItemClickListener {
                when (it.itemId) { 1 -> onImportFile(); 2 -> onImportLink(); else -> onNewBrush?.invoke() }
                true
            }
            L10n.menu(pm.menu); pm.show()
        }
        head.addView(plus, LayoutParams(44.dp, 44.dp))
        addView(head)

        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        val setScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        setsColumn.removeAllViews()
        (setsColumn.parent as? android.view.ViewGroup)?.removeView(setsColumn)
        setScroll.addView(setsColumn)
        row.addView(setScroll, LayoutParams(172.dp, -1))
        row.addView(View(context).apply { setBackgroundColor(Ui.DIVIDER) }, LayoutParams(1, -1).apply { marginStart = 8.dp; marginEnd = 8.dp })
        (listScroll.parent as? android.view.ViewGroup)?.removeView(listScroll)
        row.addView(listScroll, LayoutParams(0, -1, 1f))
        addView(row, LayoutParams(-1, 0, 1f))

        val all = sets()
        if (currentSet !in all) currentSet = all.firstOrNull() ?: ""
        for (set in all) {
            val imported = brushes.any { it.preset.category == set && it.preset.imported } && brushes.none { it.preset.category == set && !it.preset.imported }
            val on = set == currentSet
            val hasSelected = selected.preset.category == set
            val item = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10.dp, 0, 8.dp, 0)
                background = Ui.rounded(if (on) Ui.ACCENT else Ui.PANEL_ROW, 14f)
                setOnClickListener { currentSet = set; buildList(); refreshSets() }
                // long-press a set: export it (any set), or delete it (yours)
                setOnLongClickListener { v ->
                    val list = brushes.filter { it.preset.category == set }
                    val pm = android.widget.PopupMenu(context, v)
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
            item.addView(IconView(context, iconFor(set, imported)).apply {
                isClickable = false
                tint = if (on) 0xFFFFFFFF.toInt() else Ui.TEXT_DIM
            }, LayoutParams(30.dp, 30.dp))
            item.addView(Ui.text(context, set, 14.5f, if (on) 0xFFFFFFFF.toInt() else Ui.TEXT, on).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LayoutParams(0, -2, 1f).apply { marginStart = 8.dp })
            if (hasSelected && !on) item.addView(View(context).apply { background = Ui.rounded(Ui.ACCENT, 4f) }, LayoutParams(7.dp, 7.dp))
            setsColumn.addView(item, LayoutParams(-1, 50.dp).apply { bottomMargin = 6.dp })
        }
        buildList()
    }

    private fun refreshSets() {
        for (i in 0 until setsColumn.childCount) {
            val item = setsColumn.getChildAt(i) as? LinearLayout ?: continue
            val on = item.tag == currentSet
            item.background = Ui.rounded(if (on) Ui.ACCENT else Ui.PANEL_ROW, 14f)
            (item.getChildAt(0) as? IconView)?.let { it.tint = if (on) 0xFFFFFFFF.toInt() else Ui.TEXT_DIM; it.invalidate() }
            (item.getChildAt(1) as? android.widget.TextView)?.let {
                it.setTextColor(if (on) 0xFFFFFFFF.toInt() else Ui.TEXT)
                it.paint.isFakeBoldText = on
                it.invalidate()
            }
        }
    }

    private fun buildList() {
        list.removeAllViews()
        val items = brushes.filter { it.preset.category == currentSet }
        for (b in items) {
            val on = b === selected
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(14.dp, 9.dp, 12.dp, 4.dp)
                background = Ui.rounded(if (on) Ui.ACCENT else 0, 14f)
            }
            card.addView(Ui.text(context, b.preset.name, 14.5f, if (on) 0xFFFFFFFF.toInt() else Ui.TEXT, on).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val img = ImageView(context)
            img.setImageBitmap(preview(b))
            img.scaleType = ImageView.ScaleType.FIT_XY
            card.addView(img, LayoutParams(-1, 42.dp))
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
            list.addView(card, LayoutParams(-1, -2).apply { bottomMargin = 2.dp })
        }
        val hint = when {
            brushes.none { it.preset.imported } ->
                "Tap + to import Procreate brushes (.brush, .brushset), zip files or PNG stamps. You can also open a brush file with Inkora from your Files app."
            items.any { it.preset.imported } -> "Long-press a brush to delete it. Long-press the set to export or delete it."
            else -> "Tap the brush you're using again to change its settings."
        }
        list.addView(Ui.text(context, hint, 12f, Ui.TEXT_DIM).apply { setPadding(14.dp, 10.dp, 8.dp, 8.dp) })
    }

    companion object {
        private val cache = HashMap<String, Bitmap>()

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
            engine.color = 0xFFF2F2F5.toInt()
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
            grainPaintFor(brush)?.let { Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), it) }
            return bmp
        }
    }
}
