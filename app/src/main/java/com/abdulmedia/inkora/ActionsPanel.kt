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
    fun cropResize()
    fun flipCanvasContent(horizontal: Boolean)
    fun canvasInfo()
    fun openLicenses()
    var sidebarShown: Boolean
    var brushCursor: Boolean
    /** watercolor strokes dry (crisp edge, pigment line, blots) when the pen lifts */
    var watercolorDrying: Boolean
    var wetBleed: Boolean
    var lightInterface: Boolean
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
    /** the guide: every feature and gesture on one screen */
    fun openGuide()
    fun openDemos()
    fun openSnapshots()
    fun backupAll()
    fun restoreBackup()
    fun backupNote(): String
    fun comicPanels()
    fun speechBubble()
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
    /** an open sub-page of a tab (for now: "pressure" in Prefs) */
    private var page: String? = null
    private var tab: Tab = runCatching { Tab.valueOf(host.prefs.getString("actions_tab", "CANVAS")!!) }.getOrDefault(Tab.CANVAS)

    init {
        orientation = VERTICAL
        background = Ui.rounded(Ui.PANEL, 22f, Ui.OUTLINE, 1)
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
                    page = null
                    host.prefs.edit().putString("actions_tab", t.name).apply()
                    rebuild()
                }
            }
            item.addView(IconView(context, t.icon).apply {
                isClickable = false
                tint = if (on) Ui.ACCENT else Ui.TEXT_DIM
            }, LayoutParams(32.dp, 32.dp))
            item.addView(Ui.text(context, t.label, 12f, if (on) Ui.TEXT else Ui.TEXT_DIM, on).apply { gravity = Gravity.CENTER })
            tabsRow.addView(item, LayoutParams(0, -2, 1f))
        }
        body.removeAllViews()
        if (page == "pressure") { buildPressure(); return }
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
            background = Ui.rounded(Ui.PANEL_ROW, 16f)
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) card.addView(View(context).apply { setBackgroundColor(Ui.DIVIDER) }, LayoutParams(-1, 1).apply { marginStart = 16.dp })
            card.addView(r)
        }
        body.addView(card, LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
    }

    private fun row(label: String, detail: String? = null, enabled: Boolean = true, closes: Boolean = true, icon: Icon? = null, run: () -> Unit): View {
        val r = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            alpha = if (enabled) 1f else 0.4f
            isEnabled = enabled
            if (enabled) setOnClickListener { if (closes) host.close(); run() }
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, Ui.rounded(0xFFFFFFFF.toInt(), 16f))
        }
        r.addView(Ui.text(context, label, 15.5f))
        if (detail != null) r.addView(Ui.text(context, detail, 12f, Ui.TEXT_DIM))
        return withIcon(r, icon, enabled)
    }

    /** puts a small icon in front of a row (the row keeps its click and ripple) */
    private fun withIcon(r: LinearLayout, icon: Icon?, enabled: Boolean = true): View {
        if (icon == null) return r
        val texts = LinearLayout(context).apply { orientation = r.orientation; gravity = r.gravity }
        while (r.childCount > 0) { val c = r.getChildAt(0); val lp = c.layoutParams; r.removeViewAt(0); texts.addView(c, lp) }
        r.orientation = HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(10.dp, r.paddingTop, r.paddingRight, r.paddingBottom)
        r.addView(IconView(context, icon).apply { isClickable = false; tint = Ui.TEXT_DIM; iconScale = 0.56f; isEnabled = enabled },
            LayoutParams(34.dp, 34.dp))
        r.addView(texts, LayoutParams(0, -2, 1f).apply { marginStart = 10.dp })
        return r
    }

    /** a small heading over a group of cards */
    private fun section(title: String) {
        body.addView(Ui.caption(context, title).apply { setPadding(12.dp, 8.dp, 0, 6.dp) })
    }

    private fun switchRow(label: String, detail: String? = null, on: Boolean, icon: Icon? = null, onChange: (Boolean) -> Unit): View {
        val r = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (icon != null) 10.dp else 16.dp, 10.dp, 14.dp, 10.dp)
        }
        if (icon != null) r.addView(IconView(context, icon).apply { isClickable = false; tint = Ui.TEXT_DIM; iconScale = 0.56f },
            LayoutParams(34.dp, 34.dp).apply { marginEnd = 10.dp })
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(Ui.text(context, label, 15.5f))
        if (detail != null) texts.addView(Ui.text(context, detail, 12f, Ui.TEXT_DIM))
        r.addView(texts, LayoutParams(0, -2, 1f))
        val sw = SwitchView(context).apply { checked = on; onToggle = onChange }
        r.addView(sw)
        r.setOnClickListener { sw.performClick() }
        return r
    }

    /** a label over a row of choices, one of them chosen */
    private fun choiceRow(label: String, options: List<Pair<String, String>>, chosen: String, icon: Icon? = null, onPick: (String) -> Unit): View {
        val r = LinearLayout(context).apply { orientation = VERTICAL; setPadding(16.dp, 12.dp, 16.dp, 12.dp) }
        if (icon != null) {
            val h = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(-6.dp, 0, 0, 0) }
            h.addView(IconView(context, icon).apply { isClickable = false; tint = Ui.TEXT_DIM; iconScale = 0.56f }, LayoutParams(34.dp, 34.dp).apply { marginEnd = 10.dp })
            h.addView(Ui.text(context, label, 15.5f))
            r.addView(h)
        } else r.addView(Ui.text(context, label, 15.5f))
        val seg = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = Ui.rounded(Ui.SUNKEN, 12f)
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
            row("Insert a photo", "As a new layer", icon = Icon.PHOTO) { host.insertPhoto() },
            row("Add text", "Any language, 9 fonts", icon = Icon.TEXT) { host.addText() },
            row("Open a project file", ".inkora from Inkora, or .psd from Photoshop", icon = Icon.FOLDER) { host.openProjectFile() }
        )
        group(
            row("Comic panels…", "Frames for a comic page, on a new layer", icon = Icon.COMIC) { host.comicPanels() },
            row("Speech bubble…", "Speech, thought, shout or a caption box", icon = Icon.BUBBLE) { host.speechBubble() }
        )
        group(
            row("Import brushes…", "Procreate .brush and .brushset, .zip, or PNG stamps", icon = Icon.IMPORT) { host.importBrushes() },
            row("Import brushes from a link…", "Google Drive and Dropbox links work too", icon = Icon.GLOBE) { host.importBrushesFromLink() }
        )
        val hasSel = cv.selection != null
        section("Copy and paste")
        group(
            row("Cut", if (hasSel) "The selection" else "The whole layer", icon = Icon.CLOSE) { host.copyPaste("cut") },
            row("Copy", if (hasSel) "The selection" else "The whole layer", icon = Icon.COPY) { host.copyPaste("copy") },
            row("Paste", "As a new layer", enabled = cv.hasClipboard, icon = Icon.EXPORT) { host.copyPaste("paste") },
            row("Duplicate layer", icon = Icon.LAYERS) { host.copyPaste("duplicate") }
        )
    }

    private fun buildCanvas() {
        val d = cv.doc
        val g = d.guide
        group(
            row("Crop & Resize", "Canvas size, resize, crop, rotate", icon = Icon.CROP) { host.cropResize() },
            row("New canvas…", icon = Icon.NEW_CANVAS) { host.newCanvas() },
            row("Rename artwork…", d.title.ifBlank { L10n.tr("Untitled Artwork") }, icon = Icon.EDIT) { host.renameArtwork() },
            row("Snapshots", "Keep this moment of the artwork, and come back to it later", icon = Icon.SNAPSHOT) { host.openSnapshots() }
        )
        section("Drawing help")
        group(
            switchRow("Drawing guide", "Grids and perspective", g.type != GuideType.OFF, icon = Icon.GRID) { on ->
                if (on) { host.close(); host.editGuide() } else { g.type = GuideType.OFF; d.revision++; cv.invalidate(); rebuild() }
            },
            row("Edit drawing guide", enabled = true, icon = Icon.EDIT) { host.editGuide() },
            switchRow("Assisted drawing", "Strokes follow the drawing guide", g.assisted && g.type != GuideType.OFF, icon = Icon.RULER) { on ->
                if (g.type == GuideType.OFF) { host.close(); host.editGuide() }
                else { g.assisted = on; d.revision++; cv.invalidate() }
            },
            row("Symmetry…", icon = Icon.SYMMETRY) { host.showSymmetry() }
        )
        section("Windows and views")
        group(
            switchRow("Reference", "A window with a photo to look at", host.isWindowOpen("reference"), icon = Icon.REFERENCE) { host.toggleWindow("reference") },
            switchRow("Navigator", "The whole artwork in a small window", host.isWindowOpen("navigator"), icon = Icon.NAVIGATOR) { host.toggleWindow("navigator") },
            switchRow("Lighting", "A light to check your shading", host.isWindowOpen("lighting"), icon = Icon.ORB) { host.toggleWindow("lighting") },
            switchRow("Grey view", "Shows the artwork in greys, to check values", cv.greyView, icon = Icon.BW) { cv.greyView = it }
        )
        section("Flip and fit")
        group(
            row("Flip horizontal", icon = Icon.FLIP) { host.flipCanvasContent(true) },
            row("Flip vertical", icon = Icon.FLIP) { host.flipCanvasContent(false) },
            row("Flip the view", "Only how you see it, to check your drawing", closes = false, icon = Icon.EYE) { host.flipCanvas() },
            row("Fit canvas to screen", icon = Icon.FIT) { host.fitCanvas() }
        )
        group(row("Canvas information", icon = Icon.INFO) { host.canvasInfo() })
        val layers = d.layers.size
        note(String.format(Locale.US, "%d × %d px · %s %d / %d", d.width, d.height, L10n.tr("Layers"), layers, d.maxLayers()))
    }

    private fun buildShare() {
        group(
            row("Share image…", "PNG, to any app", icon = Icon.SHARE) { host.export("share") },
            row("Save PNG to Gallery", icon = Icon.PHOTO) { host.export("png") },
            row("Save JPEG to Gallery", icon = Icon.PHOTO) { host.export("jpeg") },
            row("Export SVG…", "Vector layers, for logos", icon = Icon.VECTOR) { host.export("svg") }
        )
        section("With layers")
        group(
            row("Save project (.inkora)…", "Keeps every layer, to open again in Inkora", icon = Icon.FOLDER) { host.export("project") },
            row("Photoshop (.psd)…", "With your layers, for Photoshop and other apps", icon = Icon.LAYERS) { host.export("psd") },
            row("PDF…", "For printing and sending", icon = Icon.EXPORT) { host.export("pdf") },
            row("TIFF…", "Full quality, with transparency, for print shops", icon = Icon.EXPORT) { host.export("tiff") }
        )
        section("Keep your artworks safe")
        group(
            row("Back up all artworks…", host.backupNote(), icon = Icon.BACKUP) { host.backupAll() },
            row("Restore a backup…", "Brings artworks back from a backup file; nothing here is lost", icon = Icon.SNAPSHOT) { host.restoreBackup() }
        )
    }

    private fun buildVideo() {
        group(
            switchRow("Time-lapse recording", "Records every stroke of this artwork", host.timeLapseOn, icon = Icon.CLOCK) { on ->
                host.timeLapseOn = on
                host.prefs.edit().putBoolean("timelapse", on).apply()
            },
            row("Export time-lapse video…", "An MP4 of how the artwork was made", icon = Icon.VIDEO) { host.exportTimeLapse() }
        )
    }

    private fun buildPrefs() {
        val langs = listOf(L10n.EN to "English", L10n.AR to "العربية", L10n.CKB to "کوردی")
        val dock = host.dockEdge()
        val sliders = host.slidersEdge()

        section("Interface")
        group(
            choiceRow("Language", langs, L10n.lang, icon = Icon.GLOBE) { code -> host.close(); host.setLanguage(code) },
            switchRow("Light interface", "Bright panels and bars instead of dark ones", host.lightInterface, icon = Icon.SUN) { host.lightInterface = it }
        )
        section("Layout")
        group(
            choiceRow("Toolbar", listOf("Left" to "Left", "Right" to "Right", "Top" to "Top", "Bottom" to "Bottom"), dock, icon = Icon.LAYOUT) { e ->
                // the toolbar and the sliders never share a side
                val s = if (e == sliders) (if (e == "Left") "Right" else "Left") else sliders
                host.setEdges(e, s); rebuild()
            },
            choiceRow("Size and opacity sliders", listOf("Left" to "Left", "Right" to "Right"), sliders, icon = Icon.ADJUST) { e ->
                val dk = if (dock == e) (if (e == "Left") "Right" else "Left") else dock
                host.setEdges(dk, e); rebuild()
            },
            switchRow("Size & Opacity sidebar", "Hide it if you only change them with gestures", host.sidebarShown, icon = Icon.EYE) { host.sidebarShown = it }
        )
        section("Painting")
        group(
            switchRow("Brush cursor", "Shows the brush outline where the pen hovers", host.brushCursor, icon = Icon.BRUSH) { host.brushCursor = it },
            switchRow("Watercolor drying", "When you lift the pen, a watercolor stroke dries with a crisp edge. Off: it stays as you painted it", host.watercolorDrying, icon = Icon.DROP) { host.watercolorDrying = it },
            switchRow("Wet paint runs together", "A watery stroke over paint that is still wet (about half a minute) bleeds softly into it", host.wetBleed, icon = Icon.INK) { host.wetBleed = it },
            sliderRow("ColorDrop fill sensitivity", cv.fillThreshold, { "${(it * 100).roundToInt()}%" }) { v ->
                cv.fillThreshold = v
                host.prefs.edit().putFloat("fillThreshold", v).apply()
            },
            sliderRow("Selection mask visibility", cv.selectionShade / 0.8f, { "${(it * 100).roundToInt()}%" }) { v ->
                cv.selectionShade = v * 0.8f
                cv.invalidate()
                host.prefs.edit().putFloat("selectionShade", cv.selectionShade).apply()
            }
        )
        section("Pen and touch")
        group(
            row("Pressure and Smoothing", closes = false, icon = Icon.PRESSURE) { page = "pressure"; rebuild() },
            row("Gesture controls", "What fingers, the S Pen and □ do", icon = Icon.TOUCH) { host.openGestureControls() },
            sliderRow("S Pen button hold time", ((cv.penHoldMs - 400L) / 1600f).coerceIn(0f, 1f),
                { String.format(Locale.US, "%.2f s", it * 1.6f + 0.4f) }) { v ->
                val ms = ((v * 1.6f + 0.4f) * 1000).toLong()
                cv.penHoldMs = ms
                host.prefs.edit().putLong("penHoldMs", ms).apply()
            }
        )
    }

    /** Prefs › Pressure and Smoothing */
    private fun buildPressure() {
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 8.dp) }
        head.addView(Ui.text(context, "‹", 30f).apply {
            setPadding(10.dp, 0, 14.dp, 0)
            setOnClickListener { page = null; rebuild() }
        })
        head.addView(Ui.text(context, "Pressure and Smoothing", 20f, bold = true))
        body.addView(head)
        val pc = PressureCurve
        group(
            sliderRow("Stabilization", pc.stabilization, { "${(it * 100).roundToInt()}%" }) { pc.stabilization = it; pc.save() },
            sliderRow("Motion filtering", pc.motionFiltering, { if (it < 0.005f) L10n.tr("None") else "${(it * 100).roundToInt()}%" }) { pc.motionFiltering = it; pc.save() },
            sliderRow("Motion filtering expression", pc.motionExpression, { if (it < 0.005f) L10n.tr("None") else "${(it * 100).roundToInt()}%" }) { pc.motionExpression = it; pc.save() }
        )
        body.addView(Ui.text(context, "App pressure sensitivity", 14f, Ui.TEXT, true).apply { setPadding(10.dp, 6.dp, 0, 6.dp) })
        val curve = PressureCurveView(context) {}
        body.addView(curve, LayoutParams(-1, -2).apply { leftMargin = 6.dp; rightMargin = 6.dp })
        note("Drag the points: up = more ink for a light touch, down = you need to press harder.")
        group(row("Reset all", closes = false) { pc.reset(); pc.save(); rebuild() })
    }

    private fun buildHelp() {
        group(
            row("Guide", "Everything Inkora can do, and the gestures that get you there", icon = Icon.LIGHTBULB) { host.openGuide() },
            row("Shortcuts, shown", "Every gesture as a small moving picture", icon = Icon.VIDEO) { host.openDemos() },
            row("Keyboard shortcuts", "Or hold Ctrl on a keyboard", icon = Icon.KEYBOARD) { host.openShortcuts() },
            row("Take the tour again", icon = Icon.HELP) { host.showTips() }
        )
        group(
            row("Open-source licenses", "The MyPaint brush engine and json-c", icon = Icon.INFO) { host.openLicenses() }
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
            background = Ui.rounded(Ui.PANEL_ROW, 16f)
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
        note("Inkora $ver")
    }
}
