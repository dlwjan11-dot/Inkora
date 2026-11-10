package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the Brush Studio asks of the screen it opens on. */
interface BrushStudioHost {
    /** the color strokes on the drawing pad are made with */
    val padColor: Int
    /** closed: [changed] when a setting was kept */
    fun onStudioClosed(brush: Brush, changed: Boolean)
    fun duplicate(brush: Brush)
    fun export(brush: Brush)
    fun rename(brush: Brush, name: String)
    /** pick an image for the brush's shape ("shape.png"), grain ("grain.png") or dual shape ("dual.png") */
    fun pickImage(brush: Brush, file: String)
}

/**
 * The Brush Studio, full screen: the settings by tab on the left, and a drawing pad on the right
 * where the brush can be tried as it is set (the pad redraws its strokes on every change).
 */
@SuppressLint("ViewConstructor")
class BrushStudioView(
    context: Context,
    private val brush: Brush,
    private val host: BrushStudioHost
) : FrameLayout(context) {

    enum class Tab(val title: String, val icon: Icon) {
        STROKE("Stroke path", Icon.V_PEN),
        STABILIZE("Stabilization", Icon.WAND),
        TAPER("Taper", Icon.BRUSH),
        SHAPE("Shape", Icon.SHAPE),
        GRAIN("Grain", Icon.TEXTURE),
        RENDER("Rendering", Icon.LAYERS),
        WET("Wet mix", Icon.DROP),
        COLOR("Color dynamics", Icon.PALETTE),
        DYNAMICS("Dynamics", Icon.PENCIL),
        DUAL("Dual brush", Icon.COPY),
        PROPS("Properties", Icon.SETTINGS),
        ABOUT("About this brush", Icon.HELP)
    }

    private var tab = Tab.STROKE
    private val snapshot: JSONObject = BrushSettings.toJson(brush)
    private val startSize = brush.size
    private val startOpacity = brush.opacity
    private var changed = false
    private val rail = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val bodyScroll = ScrollView(context)
    private val pad = DrawingPad(context, brush) { host.padColor }

    init {
        setBackgroundColor(0xFF18191E.toInt())
        isClickable = true
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // top: title, brush name, Cancel, Done
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp, 10.dp, 16.dp, 10.dp)
        }
        top.addView(Ui.text(context, "Brush Studio", 22f, bold = true))
        top.addView(Ui.text(context, "  ·  " + brush.preset.name, 16f, Ui.TEXT_DIM).apply { maxLines = 1 },
            LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Ui.pill(context, "Reset") { confirmReset() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8.dp })
        top.addView(Ui.pill(context, "Cancel") { close(keep = false) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8.dp })
        top.addView(Ui.pill(context, "Done", primary = true) { close(keep = true) })
        column.addView(top)

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val railScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        railScroll.addView(rail)
        row.addView(railScroll, LinearLayout.LayoutParams(220.dp, -1).apply { marginStart = 10.dp })
        body.setPadding(18.dp, 4.dp, 18.dp, 40.dp)
        bodyScroll.addView(body)
        row.addView(bodyScroll, LinearLayout.LayoutParams(0, -1, 0.9f))

        val padBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp, 0, 14.dp, 14.dp)
        }
        val padHead = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        padHead.addView(Ui.text(context, "Drawing pad", 15f, Ui.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        padHead.addView(Ui.button(context, "Clear") { pad.clear() }.apply { background = null; textSize = 14f })
        padBox.addView(padHead)
        pad.background = Ui.rounded(0xFFF4F1EA.toInt(), 18f)
        pad.clipToOutline = true
        padBox.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 6.dp })
        row.addView(padBox, LinearLayout.LayoutParams(0, -1, 1.25f))

        column.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(column, LayoutParams(-1, -1))
        rebuild()
    }

    /** true once it is closing (Done, Cancel or Back) */
    var closing = false
        private set

    /** Back or Esc: keeps the changes, as Done does */
    fun finish() = close(keep = true)

    private fun close(keep: Boolean) {
        if (closing) return
        closing = true
        if (!keep) {
            BrushSettings.apply(brush, snapshot)
            brush.size = startSize
            brush.opacity = startOpacity
        }
        pad.release()
        host.onStudioClosed(brush, keep && changed)
    }

    /** every setting back to how the brush came */
    private fun confirmReset() {
        android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Reset this brush?"))
            .setMessage(L10n.tr("All of its settings go back to how the brush came."))
            .setPositiveButton(L10n.tr("Reset")) { _, _ ->
                brush.resetSettings()
                brush.size = brush.preset.defaultSize
                brush.opacity = brush.preset.defaultOpacity
                touched()
                rebuild()
            }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    /** a setting changed: try it on the pad */
    private fun touched() {
        changed = true
        pad.redrawSoon()
    }

    // ---------- layout ----------

    private fun rebuild() {
        rail.removeAllViews()
        for (t in Tab.entries) {
            val on = t == tab
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10.dp, 0, 10.dp, 0)
                background = Ui.rounded(if (on) Ui.ACCENT else 0, 14f)
                setOnClickListener { tab = t; rebuild(); bodyScroll.scrollTo(0, 0) }
            }
            item.addView(IconView(context, t.icon).apply {
                isClickable = false
                tint = if (on) 0xFFFFFFFF.toInt() else 0xFFAAAAB2.toInt()
            }, LinearLayout.LayoutParams(32.dp, 32.dp))
            item.addView(Ui.text(context, t.title, 14.5f, if (on) 0xFFFFFFFF.toInt() else Ui.TEXT, on).apply { maxLines = 1 },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8.dp })
            rail.addView(item, LinearLayout.LayoutParams(-1, 46.dp).apply { bottomMargin = 3.dp })
        }
        body.removeAllViews()
        body.addView(Ui.text(context, tab.title, 20f, bold = true).apply { setPadding(4.dp, 6.dp, 0, 12.dp) })
        when (tab) {
            Tab.STROKE -> stroke()
            Tab.STABILIZE -> stabilize()
            Tab.TAPER -> taper()
            Tab.SHAPE -> shape()
            Tab.GRAIN -> grain()
            Tab.RENDER -> render()
            Tab.WET -> wet()
            Tab.COLOR -> color()
            Tab.DYNAMICS -> dynamics()
            Tab.DUAL -> dual()
            Tab.PROPS -> props()
            Tab.ABOUT -> about()
        }
    }

    private var card: LinearLayout? = null

    private fun group(title: String? = null, fill: () -> Unit) {
        if (title != null) body.addView(Ui.text(context, title, 13f, Ui.TEXT_DIM, true).apply { setPadding(6.dp, 10.dp, 0, 6.dp) })
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(0xFF27282F.toInt(), 16f)
            setPadding(14.dp, 6.dp, 14.dp, 6.dp)
        }
        card = c
        fill()
        card = null
        body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
    }

    private fun add(v: View) { (card ?: body).addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp; bottomMargin = 4.dp }) }

    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

    /** a 0..1 slider over [lo]..[hi] */
    private fun slider(label: String, value: Float, lo: Float, hi: Float, fmt: (Float) -> String = ::pct, set: (Float) -> Unit) {
        val t = ((value - lo) / (hi - lo)).coerceIn(0f, 1f)
        add(Ui.sliderRow(context, label, t, { fmt(lo + it * (hi - lo)) }) { set(lo + it * (hi - lo)); touched() })
    }

    /** a -1..1 slider, shown as −100%..+100% */
    private fun signed(label: String, value: Float, set: (Float) -> Unit) =
        slider(label, value, -1f, 1f, { v -> (if (v > 0.005f) "+" else "") + "${(v * 100).roundToInt()}%" }, set)

    private fun switch(label: String, on: Boolean, set: (Boolean) -> Unit) {
        val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 6.dp, 0, 6.dp) }
        r.addView(Ui.text(context, label, 14.5f), LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(SwitchView(context).apply { checked = on; onToggle = { set(it); touched() } })
        add(r)
    }

    /** choices in rows of [perRow] */
    private fun chips(options: List<Pair<String, String>>, chosen: String, perRow: Int = 3, pick: (String) -> Unit) {
        var row: LinearLayout? = null
        options.forEachIndexed { i, (key, label) ->
            if (i % perRow == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                add(row!!)
            }
            val on = key == chosen
            row!!.addView(TextView(context).apply {
                text = L10n.tr(label)
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(if (on) 0xFFFFFFFF.toInt() else Ui.TEXT)
                setPadding(4.dp, 9.dp, 4.dp, 9.dp)
                background = Ui.rounded(if (on) Ui.ACCENT else Ui.PANEL_ROW, 10f)
                setOnClickListener { if (!on) { pick(key); touched(); rebuild() } }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = if (i % perRow < perRow - 1) 6.dp else 0 })
        }
        // fill the last row so the chips keep their width
        val rest = (perRow - options.size % perRow) % perRow
        repeat(rest) { row?.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = 6.dp }) }
    }

    private fun note(text: String) {
        (card ?: body).addView(Ui.text(context, text, 12.5f, Ui.TEXT_DIM).apply { setPadding(4.dp, 6.dp, 4.dp, 6.dp) })
    }

    private val own get() = brush.preset.imported

    // ---------- tabs ----------

    private fun stroke() {
        group {
            slider("Spacing", brush.spacing, 0.01f, 1.5f) { brush.spacing = it }
            slider("Jitter (scatter)", brush.scatter, 0f, 1.5f) { brush.scatter = it }
        }
        note("Spacing is the gap between the brush's dabs. Jitter scatters them sideways from the stroke.")
    }

    private fun stabilize() {
        group("StreamLine") {
            slider("Amount", brush.streamline, 0f, 1f) { brush.streamline = it }
        }
        group("Stabilization") {
            slider("Amount", brush.dyn.stabilization, 0f, 1f) { brush.dyn.stabilization = it }
        }
        group("Motion filtering") {
            slider("Amount", brush.dyn.motionFilter, 0f, 1f) { brush.dyn.motionFilter = it }
        }
        note("StreamLine pulls the stroke behind the pen for smooth curves. Stabilization averages the pen's path, the faster you draw the less it acts. Motion filtering removes small shakes.")
    }

    private fun taper() {
        group {
            slider("Length", brush.dyn.taperLength, 0f, 10f, { String.format(Locale.US, "%.1f×", it) }) { brush.dyn.taperLength = it }
            slider("Size", brush.dyn.taperSize, 0f, 1f) { brush.dyn.taperSize = it }
            slider("Opacity", brush.dyn.taperOpacity, 0f, 1f) { brush.dyn.taperOpacity = it }
        }
        note("Taper makes the start of a stroke thin (Size) and faint (Opacity). Length is in brush widths.")
    }

    private fun shape() {
        val img = ImageView(context).apply {
            setImageBitmap(tipPreview(brush.tip()))
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = Ui.rounded(0xFF101115.toInt(), 14f)
            setPadding(10.dp, 10.dp, 10.dp, 10.dp)
        }
        (card ?: body).addView(img, LinearLayout.LayoutParams(-1, 150.dp).apply { bottomMargin = 10.dp })
        if (own) body.addView(Ui.pill(context, "Change the shape image…") { host.pickImage(brush, "shape.png") },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
        else note("To change the shape image, duplicate the brush first (About this brush).")
        group("Rotation") {
            chips(RotationMode.entries.map { it.name to it.label }, brush.rotation.name, 2) { brush.rotation = RotationMode.valueOf(it) }
            if (brush.rotation == RotationMode.FIXED || brush.rotation == RotationMode.FOLLOW)
                slider("Angle", brush.angle, -180f, 180f, { "${it.roundToInt()}°" }) { brush.angle = it }
        }
        group {
            slider("Roundness", brush.dyn.roundness, 0.05f, 1f) { brush.dyn.roundness = it }
            switch("Flip X at random", brush.dyn.flipX) { brush.dyn.flipX = it }
            switch("Flip Y at random", brush.dyn.flipY) { brush.dyn.flipY = it }
        }
    }

    private fun grain() {
        if (brush.preset.grain == GrainType.NONE) {
            note("This brush has no grain.")
            if (own) body.addView(Ui.pill(context, "Add a grain image…") { host.pickImage(brush, "grain.png") })
            else note("To give it a grain, duplicate the brush first (About this brush).")
            return
        }
        brush.grain()?.let { g ->
            (card ?: body).addView(ImageView(context).apply {
                setImageBitmap(tipPreview(g))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.rounded(0xFF101115.toInt(), 14f)
                clipToOutline = true
            }, LinearLayout.LayoutParams(-1, 110.dp).apply { bottomMargin = 10.dp })
        }
        group {
            switch("Grain", brush.grainOn) { brush.grainOn = it }
            slider("Scale", brush.grainScale, 0.2f, 4f, { String.format(Locale.US, "%.1f×", it) }) { brush.grainScale = it }
            slider("Depth", brush.dyn.grainDepth, 0f, 1f) { brush.dyn.grainDepth = it }
            slider("Granulation", brush.dyn.granulation, 0f, 1f) { brush.dyn.granulation = it }
        }
        if (own) body.addView(Ui.pill(context, "Change the grain image…") { host.pickImage(brush, "grain.png") },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp })
        note("The grain is the texture the paint sits on. Less depth lets the paint fill the gaps too.")
    }

    private fun render() {
        group {
            slider("Flow", brush.flow, 0.02f, 1f) { brush.flow = it }
            slider("Opacity", brush.opacity, 0.01f, 1f) { brush.opacity = it }
        }
        group("Blend mode") {
            chips(LayerBlend.entries.map { it.name to it.label }, brush.dyn.blend.name, 3) { brush.dyn.blend = LayerBlend.valueOf(it) }
        }
        note("Flow is how much paint each dab lays down. The blend mode is how the stroke mixes with the layer under it.")
    }

    private fun wet() {
        group {
            slider("Dilution", brush.dyn.wetDilution, 0f, 1f) { brush.dyn.wetDilution = it }
            slider("Charge", brush.dyn.wetCharge, 0f, 1f) { brush.dyn.wetCharge = it }
            slider("Pull", brush.dyn.wetPull, 0f, 1f) { brush.dyn.wetPull = it }
        }
        group("Watercolor") {
            slider("Wet edge", brush.dyn.wetEdge, 0f, 1f) { brush.dyn.wetEdge = it }
            slider("Watercolor drying", brush.dyn.watercolor, 0f, 1f) { brush.dyn.watercolor = it }
            slider("Wet bleed", brush.dyn.bloom, 0f, 1f) { brush.dyn.bloom = it }
            slider("Uneven flow", brush.dyn.flowNoise, 0f, 1f) { brush.dyn.flowNoise = it }
        }
        note("Watercolor drying: when you lift the pen the pigment runs to the edges, the edge wanders like water and the paper grain shows. Wet bleed: painting on a stroke that is still wet (the last 30 seconds) makes the colors bleed into each other. Wet mix makes the brush behave like real paint. Dilution adds water, Charge is how long the paint lasts along a stroke, and Pull is how much of the colors under the brush it picks up and drags along. Try it over other colors on the pad.")
    }

    private fun color() {
        group("Each dab") {
            slider("Hue", brush.dyn.hueJitter, 0f, 1f) { brush.dyn.hueJitter = it }
            slider("Saturation", brush.dyn.satJitter, 0f, 1f) { brush.dyn.satJitter = it }
            slider("Brightness", brush.dyn.lightJitter, 0f, 1f) { brush.dyn.lightJitter = it }
        }
        group("Each stroke") {
            slider("Hue", brush.dyn.strokeHueJitter, 0f, 1f) { brush.dyn.strokeHueJitter = it }
            slider("Brightness", brush.dyn.strokeLightJitter, 0f, 1f) { brush.dyn.strokeLightJitter = it }
        }
        group("With pressure") {
            signed("Hue", brush.dyn.pressureHue) { brush.dyn.pressureHue = it }
            signed("Saturation", brush.dyn.pressureSat) { brush.dyn.pressureSat = it }
            signed("Brightness", brush.dyn.pressureBright) { brush.dyn.pressureBright = it }
        }
        group("With tilt") {
            signed("Brightness", brush.dyn.tiltBright) { brush.dyn.tiltBright = it }
        }
        note("Color dynamics vary the color as you paint. With pressure and tilt, the color moves this far when you press lightly or tilt the pen.")
    }

    private fun dynamics() {
        group("Pen pressure") {
            slider("Size", brush.sizePressure, 0f, 1f) { brush.sizePressure = it }
            slider("Opacity", brush.flowPressure, 0f, 1f) { brush.flowPressure = it }
            // pressure curve: softer (less force for full size) to firmer, on a log scale
            val t = ((ln(brush.dyn.pressureCurve) - ln(0.3f)) / (ln(3f) - ln(0.3f))).coerceIn(0f, 1f)
            add(Ui.sliderRow(context, "Pressure response", t, { v ->
                val c = exp(ln(0.3f) + v * (ln(3f) - ln(0.3f)))
                when { c < 0.8f -> L10n.tr("Soft"); c > 1.25f -> L10n.tr("Firm / Hard"); else -> L10n.tr("Normal") }
            }) { v -> brush.dyn.pressureCurve = exp(ln(0.3f) + v * (ln(3f) - ln(0.3f))); touched() })
        }
        group("Speed") {
            slider("Speed thins the line", brush.dyn.speedSize, 0f, 1f) { brush.dyn.speedSize = it }
        }
        group("Pen tilt") {
            switch("Tilt shading (wider and lighter when tilted)", brush.dyn.tiltShading) { brush.dyn.tiltShading = it }
        }
        group("Jitter") {
            slider("Size", brush.dyn.sizeJitter, 0f, 1f) { brush.dyn.sizeJitter = it }
            slider("Opacity", brush.dyn.opacityJitter, 0f, 1f) { brush.dyn.opacityJitter = it }
        }
    }

    private fun dual() {
        group {
            switch("Dual brush", brush.dyn.dualOn) { brush.dyn.dualOn = it; rebuild() }
        }
        if (!brush.dyn.dualOn) {
            note("A dual brush cuts the stroke with a second shape, for broken, textured edges like dry paint, chalk or sponge.")
            return
        }
        val shapes = ArrayList<Pair<String, String>>()
        if (brush.preset.dualTip != null) shapes.add("CUSTOM" to "Its own")
        listOf(TipShape.CHARCOAL to "Charcoal", TipShape.SPRAY to "Spray", TipShape.SPLATTER to "Splatter",
            TipShape.BRISTLE to "Bristle", TipShape.DRY to "Dry", TipShape.HALFTONE to "Halftone",
            TipShape.CLOUD to "Cloud", TipShape.HATCH to "Hatch", TipShape.SOFT to "Soft", TipShape.PENCIL to "Pencil")
            .forEach { shapes.add(it.first.name to it.second) }
        group("Second shape") {
            chips(shapes, brush.dyn.dualShape, 3) { brush.dyn.dualShape = it }
        }
        group {
            slider("Size", brush.dyn.dualSize, 0.1f, 4f, { String.format(Locale.US, "%.1f×", it) }) { brush.dyn.dualSize = it }
            slider("Scatter", brush.dyn.dualScatter, 0f, 2f) { brush.dyn.dualScatter = it }
        }
    }

    private fun props() {
        if (own) {
            group("Name") {
                val input = EditText(context).apply {
                    setText(brush.preset.name)
                    setTextColor(Ui.TEXT)
                    setSingleLine()
                }
                add(input)
                add(Ui.pill(context, "Rename") {
                    val n = input.text.toString().trim()
                    if (n.isNotEmpty() && n != brush.preset.name) host.rename(brush, n)
                })
            }
        }
        group("Size") {
            slider("Brush size", brush.size, 1f, 500f, { "${it.roundToInt()} px" }) { brush.size = it }
        }
        body.addView(Ui.pill(context, "Reset all settings") {
            brush.resetSettings()
            brush.size = brush.preset.defaultSize
            brush.opacity = brush.preset.defaultOpacity
            touched()
            rebuild()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.dp })
    }

    private fun about() {
        group {
            add(Ui.text(context, brush.preset.name, 18f, bold = true))
            add(Ui.text(context, L10n.tr(brush.preset.category), 14f, Ui.TEXT_DIM))
            add(Ui.text(context, if (own) "Imported or made by you" else "Made for Inkora", 13f, Ui.TEXT_DIM))
        }
        body.addView(Ui.pill(context, "Duplicate brush") { close(keep = true); host.duplicate(brush) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
        body.addView(Ui.pill(context, "Export brush (.brush)…") { host.export(brush) },
            LinearLayout.LayoutParams(-1, -2))
        note("A copy becomes a brush of your own: you can then change its shape and grain images and its name. Exported brushes use Procreate's .brush file format and open in Inkora with every setting.")
    }

    /** an alpha image shown light on dark */
    private fun tipPreview(alpha: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(alpha.width, alpha.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(alpha, 0f, 0f, Paint().apply { color = 0xFFE8E9ED.toInt() })
        return out
    }

    /** the brush's shape or grain image changed: show it again */
    fun refresh() {
        pad.redrawSoon()
        rebuild()
    }
}

/**
 * Paper to try a brush on. Strokes are kept and drawn again (with the brush as it is set now)
 * whenever a setting changes; a new stroke is drawn live, as on the canvas.
 */
@SuppressLint("ViewConstructor")
class DrawingPad(context: Context, private val brush: Brush, private val colorOf: () -> Int) : View(context) {

    private val strokes = ArrayList<List<InputPoint>>()
    private var current: ArrayList<InputPoint>? = null
    private var layer: Bitmap? = null
    private var strokeBmp: Bitmap? = null
    private var mask: Bitmap? = null
    private val engine = StrokeEngine()
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val commitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskDstIn = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = android.graphics.BlendMode.DST_IN }
    private var seed = 11
    private val redraw = Runnable { redrawAll() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        release()
        layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        strokeBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        if (strokes.isEmpty()) strokes.add(sample(w, h))
        redrawAll()
    }

    /** an S-shaped stroke, light → heavy → light pressure */
    private fun sample(w: Int, h: Int): List<InputPoint> {
        val pts = ArrayList<InputPoint>()
        val n = 140
        for (i in 0..n) {
            val t = i / n.toFloat()
            val x = w * 0.12f + t * w * 0.76f
            val y = h * 0.5f + sin(t * 2 * PI).toFloat() * h * 0.18f
            pts.add(InputPoint(x, y, 0.15f + 0.85f * sin(t * PI).toFloat(), 0f))
        }
        return pts
    }

    /** the brush on the pad: its own size, so a huge or tiny brush still shows */
    private fun padBrush(): Brush {
        return brush
    }

    private fun color(): Int {
        val c = colorOf()
        // white paint wouldn't show on the paper
        val lum = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)) / 255
        return if (lum > 0.9) 0xFF1F2026.toInt() else c or 0xFF000000.toInt()
    }

    fun clear() {
        strokes.clear()
        layer?.eraseColor(0)
        invalidate()
    }

    fun redrawSoon() {
        removeCallbacks(redraw)
        postDelayed(redraw, 40)
    }

    fun release() {
        removeCallbacks(redraw)
        layer?.recycle(); strokeBmp?.recycle(); mask?.recycle()
        layer = null; strokeBmp = null; mask = null
    }

    private fun beginStroke(p: InputPoint) {
        val sb = strokeBmp ?: return
        sb.eraseColor(0)
        mask?.eraseColor(0)
        val b = padBrush()
        val savedSize = b.size
        b.size = savedSize.coerceIn(3f, height / 4f)
        engine.brush = b
        engine.color = color()
        engine.wetSource = layer
        engine.maskTarget = if (b.dualTip() != null) mask else null
        engine.begin(sb, p, smooth = true, seed = seed++)
        b.size = savedSize
    }

    private fun withPadSize(block: () -> Unit) {
        val b = padBrush()
        val saved = b.size
        b.size = saved.coerceIn(3f, height / 4f)
        try { block() } finally { b.size = saved }
    }

    private fun commit() {
        val sb = strokeBmp ?: return
        val l = layer ?: return
        val c = Canvas(l)
        commitPaint.alpha = (brush.opacity * 255).roundToInt().coerceIn(0, 255)
        commitPaint.blendMode = brush.dyn.blend.mode
        val grain = grainPaintFor(brush)
        val look = strokeLookFor(brush)
        val m = engine.maskTarget
        val all = RectF(0f, 0f, l.width.toFloat(), l.height.toFloat())
        val s = c.saveLayer(all, commitPaint)
        val gi = if (look?.layer != null) c.saveLayer(all, look.layer) else -1
        c.drawBitmap(sb, 0f, 0f, null)
        if (m != null) c.drawBitmap(m, 0f, 0f, maskDstIn)
        if (grain != null) c.drawRect(0f, 0f, l.width.toFloat(), l.height.toFloat(), grain)
        if (gi >= 0) c.restoreToCount(gi)
        look?.tone?.let { c.drawBitmap(sb, 0f, 0f, it) }
        look?.core?.let { c.drawBitmap(sb, 0f, 0f, it) }
        c.restoreToCount(s)
        sb.eraseColor(0)
    }

    private fun redrawAll() {
        val l = layer ?: return
        l.eraseColor(0)
        seed = 11
        for (pts in strokes) {
            if (pts.isEmpty()) continue
            beginStroke(pts[0])
            withPadSize {
                for (i in 1 until pts.size) engine.move(pts[i])
                engine.end()
            }
            commit()
        }
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val stylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        fun pt(i: Int, h: Int) = InputPoint(
            if (h < 0) e.x else e.getHistoricalX(h),
            if (h < 0) e.y else e.getHistoricalY(h),
            if (!stylus) 0.7f else (if (h < 0) e.pressure else e.getHistoricalPressure(h)).coerceIn(0f, 1f),
            e.getAxisValue(MotionEvent.AXIS_TILT))
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = pt(0, -1)
                current = arrayListOf(p)
                beginStroke(p)
            }
            MotionEvent.ACTION_MOVE -> {
                val cur = current ?: return true
                withPadSize {
                    for (h in 0 until e.historySize) { val p = pt(0, h); cur.add(p); engine.move(p) }
                    val p = pt(0, -1); cur.add(p); engine.move(p)
                }
            }
            MotionEvent.ACTION_UP -> {
                val cur = current ?: return true
                withPadSize { engine.end() }
                commit()
                strokes.add(cur)
                if (strokes.size > 12) strokes.removeAt(0)
                current = null
            }
            MotionEvent.ACTION_CANCEL -> { current = null; strokeBmp?.eraseColor(0) }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        layer?.let { canvas.drawBitmap(it, 0f, 0f, drawPaint) }
        if (current != null) {
            val sb = strokeBmp ?: return
            drawPaint.alpha = (brush.opacity * 255).roundToInt().coerceIn(0, 255)
            val m = engine.maskTarget
            if (m == null) canvas.drawBitmap(sb, 0f, 0f, drawPaint)
            else {
                val s = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), drawPaint)
                canvas.drawBitmap(sb, 0f, 0f, null)
                canvas.drawBitmap(m, 0f, 0f, maskDstIn)
                canvas.restoreToCount(s)
            }
            drawPaint.alpha = 255
        }
    }
}
