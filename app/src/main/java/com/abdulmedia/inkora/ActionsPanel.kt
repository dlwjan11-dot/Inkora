package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/** What the Actions panel asks of the screen it opens on. */
interface ActionsHost {
    val canvas: CanvasView
    val prefs: android.content.SharedPreferences
    var timeLapseOn: Boolean

    fun close()
    // Add
    fun insertPhoto()
    fun openProjectFile()
    fun addText()
    fun importBrushes()
    fun importBrushesFromLink()
    fun copyPaste(action: String)
    // Canvas
    fun newCanvas()
    fun renameArtwork()
    fun editGuide()
    fun showSymmetry()
    fun toggleWindow(id: String)
    fun isWindowOpen(id: String): Boolean
    fun flipCanvas()
    fun fitCanvas()
    // Share
    fun export(kind: String)
    // Video
    fun exportTimeLapse()
    // Prefs
    fun dockEdge(): String
    fun slidersEdge(): String
    fun setEdges(dock: String, sliders: String)
    fun setLanguage(code: String)
    fun openGestureControls()
    fun openShortcuts()
    fun showTips()
}

/**
 * Actions: everything that isn't painting, in one panel with tabs along the top
 * (Add, Canvas, Share, Video, Prefs, Help), each a list of rows grouped on cards.
 */
@SuppressLint("ViewConstructor")
class ActionsPanel(context: Context, private val host: ActionsHost) : LinearLayout(context) {

    enum class Tab(val label: String, val icon: Icon) {
        ADD("Add", Icon.PLUS), CANVAS("Canvas", Icon.PHOTO), SHARE("Share", Icon.SHARE),
        VIDEO("Video", Icon.VIDEO), PREFS("Prefs", Icon.SETTINGS), HELP("Help", Icon.HELP)
    }

    private val tabsRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val cv get() = host.canvas
    private var tab: Tab = runCatching { Tab.valueOf(host.prefs.getString("actions_tab", "CANVAS")!!) }.getOrDefault(Tab.CANVAS)

    init {
        orientation = VERTICAL
        background = Ui.rounded(0xF7202025.toInt(), 22f, 0x1AFFFFFF, 1)
        setPadding(14.dp, 16.dp, 14.dp, 12.dp)
        isClickable = true
        addView(Ui.text(context, "Actions", 24f, bold = true).apply { setPadding(10.dp, 0, 0, 10.dp) })
        addView(tabsRow, LayoutParams(-1, -2))
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(body)
        addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = 10.dp })
        rebuild()
    }

    private fun rebuild() {
        tabsRow.removeAllViews()
        for (t in Tab.entries) {
            val on = t == tab
            val item = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 4.dp, 0, 4.dp)
                setOnClickListener {
                    tab = t
                    host.prefs.edit().putString("actions_tab", t.name).apply()
                    rebuild()
                }
            }
            item.addView(IconView(context, t.icon).apply {
                isClickable = false
                tint = if (on) Ui.ACCENT else 0xFFB8B8C0.toInt()
            }, LayoutParams(32.dp, 32.dp))
            item.addView(Ui.text(context, t.label, 12f, if (on) Ui.TEXT else Ui.TEXT_DIM, on).apply { gravity = Gravity.CENTER })
            tabsRow.addView(item, LayoutParams(0, -2, 1f))
        }
        body.removeAllViews()
        when (tab) {
            Tab.ADD -> buildAdd()
            Tab.CANVAS -> buildCanvas()
            Tab.SHARE -> buildShare()
            Tab.VIDEO -> buildVideo()
            Tab.PREFS -> buildPrefs()
            Tab.HELP -> buildHelp()
        }
    }

    // ---------- building blocks ----------

    private fun group(vararg rows: View) {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = Ui.rounded(0xFF2A2A30.toInt(), 16f)
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) card.addView(View(context).apply { setBackgroundColor(0x14FFFFFF) }, LayoutParams(-1, 1).apply { marginStart = 16.dp })
            card.addView(r)
        }
        body.addView(card, LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
    }

    private fun row(label: String, detail: String? = null, enabled: Boolean = true, closes: Boolean = true, run: () -> Unit): View {
        val r = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(16.dp, 13.dp, 16.dp, 13.dp)
            alpha = if (enabled) 1f else 0.4f
            isEnabled = enabled
            if (enabled) setOnClickListener { if (closes) host.close(); run() }
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, Ui.rounded(0xFFFFFFFF.toInt(), 16f))
        }
        r.addView(Ui.text(context, label, 16f))
        if (detail != null) r.addView(Ui.text(context, detail, 12f, Ui.TEXT_DIM))
        return r
    }

    private fun switchRow(label: String, detail: String? = null, on: Boolean, onChange: (Boolean) -> Unit): View {
        val r = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 10.dp, 14.dp, 10.dp)
        }
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(Ui.text(context, label, 16f))
        if (detail != null) texts.addView(Ui.text(context, detail, 12f, Ui.TEXT_DIM))
        r.addView(texts, LayoutParams(0, -2, 1f))
        val sw = SwitchView(context).apply { checked = on; onToggle = onChange }
        r.addView(sw)
        r.setOnClickListener { sw.performClick() }
        return r
    }

    /** a label over a row of choices, one of them chosen */
    private fun choiceRow(label: String, options: List<Pair<String, String>>, chosen: String, onPick: (String) -> Unit): View {
        val r = LinearLayout(context).apply { orientation = VERTICAL; setPadding(16.dp, 12.dp, 16.dp, 12.dp) }
        r.addView(Ui.text(context, label, 16f))
        val seg = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = Ui.rounded(0xFF1E1E23.toInt(), 12f)
            setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        }
        for ((key, text) in options) {
            val on = key == chosen
            seg.addView(TextView(context).apply {
                this.text = L10n.tr(text)
                textSize = 13.5f
                gravity = Gravity.CENTER
                setTextColor(if (on) 0xFFFFFFFF.toInt() else Ui.TEXT_DIM)
                setPadding(4.dp, 8.dp, 4.dp, 8.dp)
                background = if (on) Ui.rounded(Ui.ACCENT, 10f) else null
                setOnClickListener { if (!on) onPick(key) }
            }, LayoutParams(0, -2, 1f))
        }
        r.addView(seg, LayoutParams(-1, -2).apply { topMargin = 8.dp })
        return r
    }

    private fun sliderRow(label: String, value: Float, format: (Float) -> String, onChange: (Float) -> Unit): View =
        Ui.sliderRow(context, label, value, format, onChange).apply { setPadding(16.dp, 10.dp, 16.dp, 10.dp) }

    private fun note(text: String) {
        body.addView(Ui.text(context, text, 12.5f, Ui.TEXT_DIM).apply { setPadding(10.dp, 0, 10.dp, 12.dp) })
    }

    // ---------- tabs ----------

    private fun buildAdd() {
        group(
            row("Insert a photo", "As a new layer") { host.insertPhoto() },
            row("Add text", "Any language, 9 fonts") { host.addText() },
            row("Open a project file", ".inkora, made with this app") { host.openProjectFile() }
        )
        group(
            row("Import brushes…", "Procreate .brush and .brushset, .zip, or PNG stamps") { host.importBrushes() },
            row("Import brushes from a link…", "Google Drive and Dropbox links work too") { host.importBrushesFromLink() }
        )
        val hasSel = cv.selection != null
        group(
            row("Cut", if (hasSel) "The selection" else "The whole layer") { host.copyPaste("cut") },
            row("Copy", if (hasSel) "The selection" else "The whole layer") { host.copyPaste("copy") },
            row("Paste", "As a new layer", enabled = cv.hasClipboard) { host.copyPaste("paste") },
            row("Duplicate layer") { host.copyPaste("duplicate") }
        )
    }

    private fun buildCanvas() {
        val d = cv.doc
        val g = d.guide
        group(
            row("New canvas…") { host.newCanvas() },
            row("Rename artwork…", d.title.ifBlank { L10n.tr("Untitled Artwork") }) { host.renameArtwork() }
        )
        group(
            switchRow("Drawing guide", "Grids and perspective", g.type != GuideType.OFF) { on ->
                if (on) { host.close(); host.editGuide() } else { g.type = GuideType.OFF; d.revision++; cv.invalidate(); rebuild() }
            },
            row("Edit drawing guide", enabled = true) { host.editGuide() },
            switchRow("Assisted drawing", "Strokes follow the drawing guide", g.assisted && g.type != GuideType.OFF) { on ->
                if (g.type == GuideType.OFF) { host.close(); host.editGuide() }
                else { g.assisted = on; d.revision++; cv.invalidate() }
            },
            row("Symmetry…") { host.showSymmetry() }
        )
        group(
            switchRow("Reference", "A window with a photo to look at", host.isWindowOpen("reference")) { host.toggleWindow("reference") },
            switchRow("Navigator", "The whole artwork in a small window", host.isWindowOpen("navigator")) { host.toggleWindow("navigator") },
            switchRow("Lighting", "A light to check your shading", host.isWindowOpen("lighting")) { host.toggleWindow("lighting") },
            switchRow("Grey view", "Shows the artwork in greys, to check values", cv.greyView) { cv.greyView = it }
        )
        group(
            row("Flip canvas horizontally", closes = false) { host.flipCanvas() },
            row("Fit canvas to screen") { host.fitCanvas() }
        )
        val layers = d.layers.size
        note(String.format(Locale.US, "%d × %d px · %s %d / %d", d.width, d.height, L10n.tr("Layers"), layers, d.maxLayers()))
    }

    private fun buildShare() {
        group(
            row("Share image…", "PNG, to any app") { host.export("share") },
            row("Save PNG to Gallery") { host.export("png") },
            row("Save JPEG to Gallery") { host.export("jpeg") },
            row("Export SVG…", "Vector layers, for logos") { host.export("svg") }
        )
        group(
            row("Save project (.inkora)…", "Keeps every layer, to open again in Inkora") { host.export("project") }
        )
    }

    private fun buildVideo() {
        group(
            switchRow("Time-lapse recording", "Records every stroke of this artwork", host.timeLapseOn) { on ->
                host.timeLapseOn = on
                host.prefs.edit().putBoolean("timelapse", on).apply()
            },
            row("Export time-lapse video…", "An MP4 of how the artwork was made") { host.exportTimeLapse() }
        )
    }

    private fun buildPrefs() {
        val langs = listOf(L10n.EN to "English", L10n.AR to "العربية", L10n.CKB to "کوردی")
        group(choiceRow("Language", langs, L10n.lang) { code -> host.close(); host.setLanguage(code) })

        val dock = host.dockEdge()
        val sliders = host.slidersEdge()
        group(
            choiceRow("Toolbar", listOf("Left" to "Left", "Right" to "Right", "Top" to "Top", "Bottom" to "Bottom"), dock) { e ->
                // the toolbar and the sliders never share a side
                val s = if (e == sliders) (if (e == "Left") "Right" else "Left") else sliders
                host.setEdges(e, s); rebuild()
            },
            choiceRow("Size and opacity sliders", listOf("Left" to "Left", "Right" to "Right"), sliders) { e ->
                val dk = if (dock == e) (if (e == "Left") "Right" else "Left") else dock
                host.setEdges(dk, e); rebuild()
            }
        )

        val gamma = cv.pressureGamma
        val curve = when { gamma < 0.85f -> "soft"; gamma > 1.2f -> "hard"; else -> "normal" }
        group(
            choiceRow("Pen pressure", listOf("soft" to "Soft", "normal" to "Normal", "hard" to "Firm / Hard"), curve) { c ->
                val gm = when (c) { "soft" -> 0.7f; "hard" -> 1.5f; else -> 1f }
                cv.pressureGamma = gm
                host.prefs.edit().putFloat("pressureGamma", gm).putString("pressureCurve", c).apply()
                rebuild()
            },
            sliderRow("Fine-tune pressure", 1f - ((2f - gamma) / 1.6f).coerceIn(0f, 1f), { v ->
                val gm = 2f - (1f - v) * 1.6f
                when { gm < 0.8f -> L10n.tr("Soft"); gm > 1.25f -> L10n.tr("Firm / Hard"); else -> L10n.tr("Normal") }
            }) { v ->
                cv.pressureGamma = 2f - (1f - v) * 1.6f
                host.prefs.edit().putFloat("pressureGamma", cv.pressureGamma).remove("pressureCurve").apply()
            },
            sliderRow("S Pen button hold time", ((cv.penHoldMs - 400L) / 1600f).coerceIn(0f, 1f),
                { String.format(Locale.US, "%.2f s", it * 1.6f + 0.4f) }) { v ->
                val ms = ((v * 1.6f + 0.4f) * 1000).toLong()
                cv.penHoldMs = ms
                host.prefs.edit().putLong("penHoldMs", ms).apply()
            },
            sliderRow("ColorDrop fill sensitivity", cv.fillThreshold, { "${(it * 100).roundToInt()}%" }) { v ->
                cv.fillThreshold = v
                host.prefs.edit().putFloat("fillThreshold", v).apply()
            }
        )
        group(
            row("Gesture controls", "What fingers, the S Pen and □ do") { host.openGestureControls() },
            row("Keyboard shortcuts") { host.openShortcuts() }
        )
    }

    private fun buildHelp() {
        group(
            row("Gesture controls", "Change what each gesture does") { host.openGestureControls() },
            row("Keyboard shortcuts", "Or hold Ctrl on a keyboard") { host.openShortcuts() },
            row("Show the tips again") { host.showTips() }
        )
        val lines = listOf(
            "Two-finger pinch" to "Zoom, move and turn the canvas",
            "Quick pinch in" to "Fit the canvas to the screen",
            "Two-finger tap" to "Undo (hold to keep undoing)",
            "Three-finger tap" to "Redo (hold to keep redoing)",
            "Three-finger swipe down" to "Copy & paste menu",
            "Three-finger scrub" to "Clear the layer",
            "Four-finger tap" to "Hide or show the interface",
            "Touch and hold" to "Eyedropper",
            "Draw and hold" to "QuickShape",
            "S Pen button" to "Brush ⇄ eraser",
            "S Pen button hold or double-click" to "Quick menu with the color wheel",
            "Pen held still" to "Quick menu",
            "Draw with the S Pen button held" to "Erase",
            "□ (under the size slider)" to "Tap: eyedropper. Hold and touch: eyedropper too",
            "Drag the color circle onto the canvas" to "ColorDrop fill"
        )
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = Ui.rounded(0xFF2A2A30.toInt(), 16f)
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
        }
        card.addView(Ui.text(context, "Gestures", 16f, bold = true).apply { setPadding(0, 0, 0, 6.dp) })
        for ((a, b) in lines) {
            val r = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, 5.dp, 0, 5.dp) }
            r.addView(Ui.text(context, a, 13.5f, Ui.TEXT, true), LayoutParams(0, -2, 1f))
            r.addView(Ui.text(context, b, 13.5f, Ui.TEXT_DIM), LayoutParams(0, -2, 1.1f).apply { marginStart = 10.dp })
            card.addView(r)
        }
        body.addView(card, LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
        val ver = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
        note("Inkora $ver · Abdulmedia")
    }
}
