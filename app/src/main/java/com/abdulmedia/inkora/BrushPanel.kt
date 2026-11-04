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
    return Paint(Paint.FILTER_BITMAP_FLAG).apply {
        this.shader = shader
        blendMode = BlendMode.DST_IN
    }
}

/** Brush library: every brush with a preview, import, and a small Brush Studio for the selected one. */
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
    private val onPin: (() -> Unit)? = null
) : LinearLayout(context) {

    private val scroll = ScrollView(context)
    private val list = LinearLayout(context)
    private var studio = false

    init {
        orientation = VERTICAL
        setPadding(14.dp, 14.dp, 14.dp, 14.dp)
        background = Ui.rounded(Ui.PANEL, 18f)
        list.orientation = VERTICAL
        scroll.addView(list)
        rebuild()
    }

    private fun rebuild() {
        removeAllViews()
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(context, if (studio) "Brush Studio" else title, 17f, bold = true), LayoutParams(0, -2, 1f))
        if (studio) {
            head.addView(Ui.button(context, "‹ Library") { studio = false; rebuild() })
        } else {
            if (onPin != null) {
                val pin = IconView(context, Icon.PIN)
                pin.setOnClickListener { onPin.invoke() }
                head.addView(pin, LayoutParams(40.dp, 40.dp))
            }
            head.addView(Ui.button(context, "Edit brush") { studio = true; rebuild() })
        }
        addView(head)
        if (!studio) {
            val imports = LinearLayout(context).apply { orientation = HORIZONTAL }
            imports.addView(Ui.button(context, "Import file…") { onImportFile() }.apply { textSize = 13f }, LayoutParams(0, -2, 1f))
            imports.addView(Ui.button(context, "Import from link…") { onImportLink() }.apply { textSize = 13f },
                LayoutParams(0, -2, 1f).apply { leftMargin = 6.dp })
            addView(imports, LayoutParams(-1, -2).apply { topMargin = 8.dp })
            buildList()
            addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = 6.dp })
        } else {
            val s = ScrollView(context)
            s.addView(buildStudio())
            addView(s, LayoutParams(-1, 0, 1f).apply { topMargin = 6.dp })
        }
    }

    // a getter (no stored field): buildList() runs from init, before fields declared down here exist
    private val prefs: android.content.SharedPreferences
        get() = context.getSharedPreferences("inkora", Context.MODE_PRIVATE)

    private fun isCollapsed(cat: String) = prefs.getBoolean("collapsed_$cat", false)
    private fun setCollapsed(cat: String, v: Boolean) = prefs.edit().putBoolean("collapsed_$cat", v).apply()

    private fun buildList() {
        list.removeAllViews()
        // groups in their natural order; the group with the selected brush is opened automatically
        val groups = LinkedHashMap<String, MutableList<Brush>>()
        for (b in brushes) groups.getOrPut(b.preset.category) { mutableListOf() }.add(b)

        val bar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.END }
        bar.addView(Ui.button(context, "Expand all") { groups.keys.forEach { setCollapsed(it, false) }; buildList() }.apply { textSize = 11f; background = null })
        bar.addView(Ui.button(context, "Collapse all") { groups.keys.forEach { setCollapsed(it, true) }; buildList() }.apply { textSize = 11f; background = null })
        list.addView(bar)

        for ((cat, items) in groups) {
            val collapsed = isCollapsed(cat)
            val header = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(6.dp, 10.dp, 6.dp, 6.dp)
                setOnClickListener {
                    setCollapsed(cat, !collapsed)
                    buildList()
                }
            }
            header.addView(Ui.text(context, if (collapsed) "▸" else "▾", 14f, Ui.ACCENT, true), LayoutParams(22.dp, -2))
            val hasSelected = items.any { it === selected }
            header.addView(Ui.text(context, cat.uppercase() + if (collapsed && hasSelected) "  •" else "", 12f,
                if (hasSelected) Ui.ACCENT else Ui.TEXT, true), LayoutParams(0, -2, 1f))
            header.addView(Ui.text(context, "${items.size}", 11f, Ui.TEXT_DIM).apply {
                setPadding(8.dp, 2.dp, 8.dp, 2.dp)
                background = Ui.rounded(Ui.PANEL_ROW, 8f)
            })
            list.addView(header, LayoutParams(-1, -2))
            if (collapsed) continue

            for (b in items) {
                val row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(10.dp, 6.dp, 10.dp, 6.dp)
                    background = Ui.rounded(if (b === selected) Ui.PANEL_ROW_ACTIVE else Ui.PANEL_ROW, 10f)
                }
                row.addView(Ui.text(context, b.preset.name, 14f).apply { maxLines = 2 }, LayoutParams(118.dp, -2))
                val img = ImageView(context)
                img.setImageBitmap(preview(b))
                img.scaleType = ImageView.ScaleType.FIT_CENTER
                row.addView(img, LayoutParams(0, 46.dp, 1f))
                row.setOnClickListener {
                    selected = b
                    onSelect(b)
                    buildList()
                }
                if (b.preset.imported) {
                    row.setOnLongClickListener {
                        onDelete(b)
                        true
                    }
                }
                list.addView(row, LayoutParams(-1, -2).apply { bottomMargin = 4.dp; leftMargin = 6.dp })
            }
        }
        if (brushes.none { it.preset.imported }) {
            list.addView(Ui.text(context,
                "Tip: download Procreate brushes (.brush / .brushset) or PNG stamps, then tap Import. Long-press an imported brush to delete it.",
                12f, Ui.TEXT_DIM).apply { setPadding(4.dp, 12.dp, 4.dp, 4.dp) })
        }
    }

    private fun buildStudio(): View {
        val b = selected
        val box = LinearLayout(context).apply { orientation = VERTICAL }
        val livePreview = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = Ui.rounded(Ui.PANEL_ROW, 10f)
        }
        fun refreshPreview() = livePreview.setImageBitmap(render(b))
        refreshPreview()
        box.addView(Ui.text(context, b.preset.name, 14f, Ui.TEXT, true))
        box.addView(livePreview, LayoutParams(-1, 70.dp).apply { topMargin = 6.dp; bottomMargin = 4.dp })

        fun pct(v: Float) = "${(v * 100).roundToInt()}%"
        box.addView(Ui.sliderRow(context, "StreamLine (smoothing)", b.streamline, ::pct) { b.streamline = it })
        box.addView(Ui.sliderRow(context, "Spacing", ((b.spacing - 0.01f) / 1.5f).coerceIn(0f, 1f), { pct(0.01f + it * 1.5f) }) {
            b.spacing = 0.01f + it * 1.5f; refreshPreview()
        })
        box.addView(Ui.sliderRow(context, "Scatter", (b.scatter / 1.5f).coerceIn(0f, 1f), { pct(it * 1.5f) }) {
            b.scatter = it * 1.5f; refreshPreview()
        })
        box.addView(Ui.sliderRow(context, "Flow", b.flow, ::pct) { b.flow = it.coerceAtLeast(0.02f); refreshPreview() })
        box.addView(Ui.sliderRow(context, "Pressure → Size", b.sizePressure, ::pct) { b.sizePressure = it; refreshPreview() })
        box.addView(Ui.sliderRow(context, "Pressure → Opacity", b.flowPressure, ::pct) { b.flowPressure = it; refreshPreview() })

        box.addView(Ui.text(context, "Tip rotation", 13f, Ui.TEXT_DIM), LayoutParams(-2, -2).apply { topMargin = 8.dp })
        val rot = LinearLayout(context).apply { orientation = HORIZONTAL }
        RotationMode.values().forEach { m ->
            val chip = Ui.button(context, m.label) {}.apply {
                textSize = 11.5f
                setPadding(6.dp, 8.dp, 6.dp, 8.dp)
                background = Ui.rounded(if (b.rotation == m) Ui.PANEL_ROW_ACTIVE else Ui.PANEL_ROW, 8f)
            }
            chip.setOnClickListener { b.rotation = m; rebuild() }
            rot.addView(chip, LayoutParams(0, -2, 1f).apply { leftMargin = 3.dp })
        }
        box.addView(rot, LayoutParams(-1, -2).apply { topMargin = 4.dp })
        if (b.rotation == RotationMode.FIXED || b.rotation == RotationMode.FOLLOW) {
            box.addView(Ui.sliderRow(context, "Angle", (b.angle + 180f) / 360f, { "${(it * 360f - 180f).roundToInt()}°" }) {
                b.angle = it * 360f - 180f; refreshPreview()
            })
        }

        if (b.preset.grain != GrainType.NONE) {
            val g = Ui.button(context, if (b.grainOn) "Texture (grain): ON" else "Texture (grain): off") {}
            g.setOnClickListener { b.grainOn = !b.grainOn; rebuild() }
            box.addView(g, LayoutParams(-1, -2).apply { topMargin = 10.dp })
            if (b.grainOn) {
                box.addView(Ui.sliderRow(context, "Texture scale", (b.grainScale - 0.2f) / 3.8f, { String.format("%.1f×", 0.2f + it * 3.8f) }) {
                    b.grainScale = 0.2f + it * 3.8f; refreshPreview()
                })
            }
        }

        box.addView(Ui.button(context, "Reset brush settings") { b.resetSettings(); rebuild() },
            LayoutParams(-1, -2).apply { topMargin = 12.dp })
        return box
    }

    companion object {
        private val cache = HashMap<String, Bitmap>()

        fun preview(b: Brush): Bitmap = cache.getOrPut(b.preset.id) {
            // a broken brush must never take the whole app down
            runCatching { render(Brush(b.preset)) }.getOrElse { Bitmap.createBitmap(300, 64, Bitmap.Config.ARGB_8888) }
        }

        fun forget(id: String) { cache.remove(id) }

        /** Draws a sample S-curve stroke (light → heavy → light pressure). */
        fun render(source: Brush): Bitmap {
            val w = 300
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
            }
            val engine = StrokeEngine()
            engine.brush = brush
            engine.color = 0xFFF2F2F5.toInt()
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
            grainPaintFor(brush)?.let { Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), it) }
            return bmp
        }
    }
}
