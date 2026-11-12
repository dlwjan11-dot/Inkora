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
    /** start with the eraser used last, after closing the app and in new artworks */
    var rememberEraser: Boolean
    /** watercolor strokes dry (crisp edge, pigment line, blots) when the pen lifts */
    var watercolorDrying: Boolean
    var wetBleed: Boolean
    var lightInterface: Boolean
    /** the accent color theme, an index into Ui.ACCENTS */
    var accent: Int
    /** the islands step back while the pen draws */
    var fadeIslands: Boolean
    /** the stroke goes to the screen in tiles (test) */
    var gpuTiles: Boolean
    /** the graphics chip paints the brush dabs (test) */
    var gpuBrushes: Boolean
    fun useWorkspace(w: Workspaces.Workspace)
    fun saveWorkspace()
    // Share
    fun export(kind: String)
    /** PNG or JPEG, shared or saved to Pictures, without the background color or smaller */
    fun exportPicture(jpeg: Boolean, share: Boolean, transparent: Boolean, scale: Float)
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
    /** "dock", "wide" or "float" (see [ToolRail]) */
    var railMode: String
    /** runs a tool by its id in [ToolCatalog] */
    fun runTool(id: String)
    /** the full-screen Settings (on a page: "fill", "pressure") */
    fun openSettings(page: String? = null)
    /** the drawing guide's settings and the symmetry picker, shown as pages of Canvas */
    fun guideView(): View
    fun symmetryView(picked: () -> Unit): View
}

/**
 * Three of the top bar's panels, all lists of rows grouped on cards:
 * Actions (tabs File, Share, Video, Settings, Help), Add (things to put in the artwork) and
 * Canvas (drawing help, windows, views, the canvas itself).
 */
@SuppressLint("ViewConstructor")
class ActionsPanel(
    context: Context,
    private val host: ActionsHost,
    private val mode: String = MODE_ACTIONS,
    /** the Settings screen's back arrow */
    private val onBack: () -> Unit = {}
) : LinearLayout(context) {

    companion object {
        const val MODE_ACTIONS = "actions"
        const val MODE_ADD = "add"
        const val MODE_CANVAS = "canvas"
        /** the full-screen Settings: sections on the side, search on top */
        const val MODE_SETTINGS = "settings"
    }

    enum class Tab(val label: String, val icon: Icon) {
        ADD("Add", Icon.PLUS), CANVAS("Canvas", Icon.GRID),
        FILE("File", Icon.FOLDER), SHARE("Share", Icon.SHARE),
        VIDEO("Video", Icon.VIDEO), PREFS("Settings", Icon.SETTINGS), HELP("Help", Icon.HELP)
    }

    private val tabsRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val cv get() = host.canvas
    /** this is the full-screen Settings */
    val isSettings get() = mode == MODE_SETTINGS
    /** an open sub-page of a tab (for now: "pressure" in Prefs) */
    private var page: String? = null
    private var tab: Tab = runCatching { Tab.valueOf(host.prefs.getString("actions_tab", "FILE")!!) }.getOrDefault(Tab.FILE)

    // ---- the Settings screen ----
    /** a section: its key, its name, and words that find it in the search */
    private class Section(val key: String, val label: String, val words: String)
    private val sections = listOf(
        Section("canvas", "Canvas", "canvas selection mask shade"),
        Section("brushes", "Brushes and Pen", "pressure pen heavy light smoothing stabilization motion eraser cursor fill colordrop gap s pen hold watercolor wet bleed faster gpu graphics"),
        Section("gestures", "Gestures and Shortcuts", "gesture finger touch tap pinch keyboard shortcut s pen button quick menu modify"),
        Section("appearance", "Appearance", "dark light theme color colour accent left right hand left hand mirror tool bar toolbar side docked floating fade island sidebar slider"),
        Section("workspaces", "Workspaces", "workspace layout sketching painting lettering frame mine"),
        Section("language", "Language", "language arabic english kurdish sorani"),
        Section("files", "Files and Backup", "backup back up restore files drive save"),
        Section("about", "About", "about version licence license open source tour guide help")
    )
    private var section: String = host.prefs.getString("settings_section", "brushes") ?: "brushes"
    private var query = ""
    private var advanced = false
    private val sectionList = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        if (mode == MODE_SETTINGS) buildSettingsFrame() else buildPopupFrame()
        rebuild()
    }

    /** Settings: a whole screen with the sections on the start side */
    private fun buildSettingsFrame() {
        orientation = VERTICAL
        setBackgroundColor(Ui.SURFACE)
        isClickable = true
        val phone = context.narrow
        setPadding(if (phone) 12.dp else 28.dp, if (phone) 12.dp else 20.dp, if (phone) 12.dp else 28.dp, 0)
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(IconView(context, if (L10n.isRtl) Icon.CHEV_RIGHT else Icon.CHEV_LEFT).apply {
            iconScale = 0.5f
            contentDescription = L10n.tr("Back")
            setOnClickListener { onBack() }
        }, LayoutParams(44.dp, 44.dp))
        head.addView(Ui.text(context, "Settings", 28f, bold = true), LayoutParams(0, -2, 1f).apply { marginStart = 4.dp })
        val field = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 0, 8.dp, 0)
            background = Ui.rounded(Ui.CARD, 12f)
        }
        field.addView(IconView(context, Icon.SEARCH).apply { isClickable = false; tint = Ui.TEXT_DIM; iconScale = 0.6f }, LayoutParams(24.dp, 24.dp))
        field.addView(android.widget.EditText(context).apply {
            hint = L10n.tr("Search settings")
            setHintTextColor(Ui.TEXT_DIM)
            setTextColor(Ui.TEXT)
            textSize = 14f
            typeface = Fonts.get(400)
            background = null
            setSingleLine()
            setPadding(8.dp, 0, 4.dp, 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    query = e?.toString()?.trim() ?: ""
                    page = null
                    rebuild()
                }
            })
        }, LayoutParams(0, -1, 1f))
        if (phone) {
            addView(head)
            addView(field, LayoutParams(-1, 44.dp).apply { topMargin = 8.dp })
        } else {
            head.addView(field, LayoutParams(300.dp, 44.dp))
            addView(head)
        }
        val row = LinearLayout(context).apply { orientation = if (phone) VERTICAL else HORIZONTAL }
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; setPadding(0, 0, 0, 32.dp) }
        scroll.addView(body)
        if (phone) {
            sectionList.orientation = HORIZONTAL
            row.addView(android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(sectionList) },
                LayoutParams(-1, -2).apply { topMargin = 10.dp; bottomMargin = 8.dp })
            row.addView(scroll, LayoutParams(-1, 0, 1f))
        } else {
            row.addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(sectionList) }, LayoutParams(240.dp, -1))
            row.addView(View(context).apply { setBackgroundColor(Ui.DIVIDER) }, LayoutParams(1, -1).apply { marginStart = 12.dp; marginEnd = 28.dp })
            // a readable column, not the whole width of the tablet
            row.addView(scroll, LayoutParams(0, -1, 1f))
        }
        addView(row, LayoutParams(-1, 0, 1f).apply { topMargin = 14.dp })
    }

    private fun fillSectionList() {
        sectionList.removeAllViews()
        val phone = context.narrow
        for (sec in sections) {
            val on = query.isEmpty() && sec.key == section
            sectionList.addView(Ui.text(context, sec.label, 14.5f, if (on) Ui.ACCENT else Ui.TEXT, on).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dp, 0, 14.dp, 0)
                background = if (on) Ui.rounded(Ui.ACCENT_SOFT, 12f) else null
                foreground = Ui.ripple(12f)
                setOnClickListener {
                    section = sec.key; page = null; advanced = false
                    host.prefs.edit().putString("settings_section", sec.key).apply()
                    rebuild()
                }
            }, if (phone) LayoutParams(-2, 40.dp).apply { marginEnd = 4.dp } else LayoutParams(-1, 46.dp).apply { bottomMargin = 2.dp })
        }
    }

    private fun buildSection(key: String) = when (key) {
        "canvas" -> setCanvas()
        "brushes" -> setBrushes()
        "gestures" -> setGestures()
        "appearance" -> setAppearance()
        "workspaces" -> setWorkspaces()
        "language" -> setLanguage()
        "files" -> setFiles()
        else -> setAbout()
    }

    private fun matches(sec: Section): Boolean {
        val q = query.lowercase()
        return sec.words.contains(q) || sec.label.lowercase().contains(q) || L10n.tr(sec.label).lowercase().contains(q) ||
            searchLabels(sec.key).any { it.lowercase().contains(q) || L10n.tr(it).lowercase().contains(q) }
    }

    /** the row names of a section, so a search finds them in any language */
    private fun searchLabels(key: String): List<String> = when (key) {
        "canvas" -> listOf("Selection mask visibility")
        "brushes" -> listOf("Pressure and Smoothing", "Brush cursor", "Keep my last eraser", "Fill and ColorDrop", "S Pen button hold time", "Faster drawing (test)", "GPU brushes (test)", "Watercolor drying", "Wet paint runs together")
        "gestures" -> listOf("Gesture controls", "Shortcuts, shown", "Keyboard shortcuts")
        "appearance" -> listOf("Light interface", "Accent color", "Side", "Style", "Hand island", "Fade the islands while drawing")
        "workspaces" -> listOf("Workspace", "Save this layout as mine")
        "language" -> listOf("Language")
        "files" -> listOf("Back up all artworks…", "Restore a backup…")
        else -> listOf("Guide", "Take the tour again", "Open-source licenses")
    }

    private fun rebuildSettings() {
        fillSectionList()
        if (query.isNotEmpty()) {
            val found = sections.filter { matches(it) }
            if (found.isEmpty()) note("Nothing found. Try another word, like \"pressure\" or \"left hand\".")
            for (sec in found) {
                body.addView(Ui.text(context, sec.label, 20f, bold = true).apply { setPadding(4.dp, 10.dp, 0, 8.dp) })
                buildSection(sec.key)
            }
            return
        }
        body.addView(Ui.text(context, sections.firstOrNull { it.key == section }?.label ?: "", 22f, bold = true).apply { setPadding(4.dp, 0, 0, 12.dp) })
        buildSection(section)
    }

    /** the old popup frame (Actions, Add, Canvas) */
    private fun buildPopupFrame() {
        orientation = VERTICAL
        background = Ui.rounded(Ui.PANEL, 22f, Ui.OUTLINE, 1)
        setPadding(14.dp, 16.dp, 14.dp, 12.dp)
        isClickable = true
        val (title, icon) = when (mode) { MODE_ADD -> "Add" to Icon.PLUS; MODE_CANVAS -> "Canvas" to Icon.GRID; else -> "Actions" to Icon.WRENCH }
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(6.dp, 0, 0, 10.dp) }
        head.addView(IconView(context, icon).apply { isClickable = false; tint = Ui.ACCENT; iconScale = 0.62f }, LayoutParams(32.dp, 32.dp))
        head.addView(Ui.text(context, title, 20f, bold = true).apply { setPadding(8.dp, 0, 0, 0) })
        addView(head)
        if (mode == MODE_ACTIONS) addView(tabsRow, LayoutParams(-1, -2))
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(body)
        addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = 10.dp })
    }

    private fun rebuild() {
        tabsRow.removeAllViews()
        if (mode == MODE_ACTIONS) for (t in Tab.entries) {
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
        if (page == "fill") { buildFill(); return }
        if (page == "guide") { subPage("Drawing guide", host.guideView()); return }
        if (page == "symmetry") { subPage("Symmetry", host.symmetryView { rebuild() }); return }
        if (mode == MODE_SETTINGS) { rebuildSettings(); return }
        if (mode == MODE_ADD) { buildAdd(); return }
        if (mode == MODE_CANVAS) { buildCanvas(); return }
        when (tab) {
            Tab.ADD -> buildAdd()
            Tab.CANVAS -> buildCanvas()
            Tab.FILE -> buildFile()
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
                typeface = Fonts.get(if (on) 600 else 400)
                maxLines = 1
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

    /** Accent color: a row of color dots; the chosen one has a ring and its name shows */
    private fun accentRow(): View {
        val r = LinearLayout(context).apply { orientation = VERTICAL; setPadding(16.dp, 12.dp, 16.dp, 14.dp) }
        val h = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(-6.dp, 0, 0, 0) }
        h.addView(IconView(context, Icon.PALETTE).apply { isClickable = false; tint = Ui.TEXT_DIM; iconScale = 0.56f }, LayoutParams(34.dp, 34.dp).apply { marginEnd = 10.dp })
        h.addView(Ui.text(context, "Accent color", 15.5f), LayoutParams(0, -2, 1f))
        h.addView(Ui.text(context, Ui.ACCENTS[Ui.accent].first, 13f, Ui.TEXT_DIM))
        r.addView(h)
        val dots = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        Ui.ACCENTS.forEachIndexed { i, (name, dark, bright) ->
            val c = (if (Ui.light) bright else dark).toInt()
            val on = i == Ui.accent
            val dot = object : View(context) {
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: android.graphics.Canvas) {
                    val cx = width / 2f; val cy = height / 2f
                    val rr = minOf(width, height) / 2f - 7f.dp
                    p.style = android.graphics.Paint.Style.FILL; p.color = c
                    canvas.drawCircle(cx, cy, rr, p)
                    if (on) {
                        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 2f.dp; p.color = Ui.TEXT
                        canvas.drawCircle(cx, cy, rr + 4f.dp, p)
                    }
                }
            }
            dot.contentDescription = L10n.tr(name)
            dot.isClickable = true
            dot.foreground = Ui.ripple(22f)
            dot.setOnClickListener { if (!on) host.accent = i }
            dots.addView(dot, LayoutParams(44.dp, 44.dp).apply { marginEnd = 6.dp })
        }
        r.addView(dots, LayoutParams(-2, -2).apply { topMargin = 8.dp })
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
            row("Photo", "As a new layer", icon = Icon.PHOTO) { host.insertPhoto() },
            row("Text", "Any language, 9 fonts", icon = Icon.TEXT) { host.addText() },
            row("Vector layer", "Pen and shapes, sharp at any size", icon = Icon.VECTOR) { host.runTool("vector") },
            row("3D figure", "A pose to draw from", icon = Icon.CUBE) { host.runTool("pose3d") }
        )
        group(
            row("Comic panels…", "Frames for a comic page, on a new layer", icon = Icon.COMIC) { host.comicPanels() },
            row("Speech bubble…", "Speech, thought, shout or a caption box", icon = Icon.BUBBLE) { host.speechBubble() }
        )
        group(
            row("Photo to trace", "Shown under your drawing, kept out of exports", icon = Icon.TRACE) { host.runTool("shadow_photo") },
            row("Palette from a photo", "Its colors, ready to paint with", icon = Icon.PALETTE) { host.runTool("palette_photo") }
        )
    }

    private fun buildFile() {
        val d = cv.doc
        group(
            row("New canvas…", icon = Icon.NEW_CANVAS) { host.newCanvas() },
            row("Rename artwork…", d.title.ifBlank { L10n.tr("Untitled Artwork") }, icon = Icon.EDIT) { host.renameArtwork() },
            row("Open a project file", ".inkora from Inkora, or .psd from Photoshop", icon = Icon.FOLDER) { host.openProjectFile() }
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
        section("Drawing help")
        group(
            switchRow("Drawing guide", "Grids and perspective", g.type != GuideType.OFF, icon = Icon.GRID) { on ->
                if (on) { page = "guide"; rebuild() } else { g.type = GuideType.OFF; d.revision++; cv.invalidate(); rebuild() }
            },
            row("Edit drawing guide", enabled = true, closes = false, icon = Icon.EDIT) { page = "guide"; rebuild() },
            switchRow("Assisted drawing", "Strokes follow the drawing guide", g.assisted && g.type != GuideType.OFF, icon = Icon.RULER) { on ->
                if (g.type == GuideType.OFF) { page = "guide"; rebuild() }
                else { g.assisted = on; d.revision++; cv.invalidate() }
            },
            row("Symmetry", L10n.tr(cv.symmetry.label), closes = false, icon = Icon.SYMMETRY) { page = "symmetry"; rebuild() }
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
        section("This canvas")
        group(
            row("Crop & Resize", "Canvas size, resize, crop, rotate", icon = Icon.CROP) { host.cropResize() },
            row("Snapshots", "Keep this moment of the artwork, and come back to it later", icon = Icon.SNAPSHOT) { host.openSnapshots() },
            row("Canvas information", icon = Icon.INFO) { host.canvasInfo() }
        )
        val layers = d.layers.size
        note(String.format(Locale.US, "%d × %d px · %s %d / %d", d.width, d.height, L10n.tr("Layers"), layers, d.maxLayers()))
    }

    /** the export formats: key, name, what it is for, icon */
    private class Format(val key: String, val name: String, val about: String, val icon: Icon)
    private val formats = listOf(
        Format("png", "PNG", "Full quality picture, can be see-through", Icon.PHOTO),
        Format("jpeg", "JPEG", "Small file, for messages and the web", Icon.PHOTO),
        Format("psd", "Photoshop", "With your layers", Icon.LAYERS),
        Format("pdf", "PDF", "For printing and sending", Icon.EXPORT),
        Format("tiff", "TIFF", "Full quality, for print shops", Icon.EXPORT),
        Format("project", "Inkora", "Every layer, to open again here", Icon.FOLDER),
        Format("svg", "SVG", "Vector layers, for logos", Icon.VECTOR)
    )

    /** Share: pick a format, its few options, then Share or Save */
    private fun buildShare() {
        val prefs = host.prefs
        val fmt = formats.firstOrNull { it.key == prefs.getString("export_format", "png") } ?: formats[0]
        // format cards, three across
        val grid = android.widget.GridLayout(context).apply { columnCount = 3 }
        body.addView(grid, LayoutParams(-1, -2))
        grid.post {
            val w = (grid.width - 4 * 6.dp) / 3
            for (f in formats) {
                val on = f.key == fmt.key
                val card = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(10.dp, 10.dp, 8.dp, 10.dp)
                    background = if (on) Ui.rounded(Ui.ACCENT_SOFT, 14f, Ui.ACCENT, 2) else Ui.rounded(Ui.PANEL_ROW, 14f)
                    foreground = Ui.ripple(14f)
                    setOnClickListener { prefs.edit().putString("export_format", f.key).apply(); rebuild() }
                }
                card.addView(IconView(context, f.icon).apply { isClickable = false; iconScale = 0.6f; tint = if (on) Ui.ACCENT else Ui.TEXT_DIM }, LayoutParams(28.dp, 28.dp))
                card.addView(Ui.text(context, f.name, 14f, if (on) Ui.ACCENT else Ui.TEXT, true).apply { setPadding(0, 4.dp, 0, 0) })
                card.addView(Ui.text(context, f.about, 11f, Ui.TEXT_DIM).apply { maxLines = 2 })
                grid.addView(card, android.widget.GridLayout.LayoutParams().apply { width = w; setMargins(3.dp, 3.dp, 3.dp, 3.dp) })
            }
        }
        // its options
        val picture = fmt.key == "png" || fmt.key == "jpeg"
        var transparent = prefs.getBoolean("export_transparent", false)
        var scale = prefs.getFloat("export_scale", 1f)
        val d = cv.doc
        if (picture) {
            val rows = ArrayList<View>()
            rows.add(choiceRow("Size", listOf("1" to "100%", "0.5" to "50%", "0.25" to "25%"),
                when { scale < 0.3f -> "0.25"; scale < 0.75f -> "0.5"; else -> "1" }) { v ->
                scale = v.toFloat(); prefs.edit().putFloat("export_scale", scale).apply(); rebuild()
            })
            if (fmt.key == "png") rows.add(switchRow("See-through background", "Leaves out the background color", transparent) {
                transparent = it; prefs.edit().putBoolean("export_transparent", it).apply()
            })
            body.addView(View(context), LayoutParams(1, 10.dp))
            group(*rows.toTypedArray())
            note(String.format(Locale.US, "%d × %d px", (d.width * scale).roundToInt(), (d.height * scale).roundToInt()))
        } else body.addView(View(context), LayoutParams(1, 10.dp))
        // Share and Save
        val buttons = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun button(label: String, primary: Boolean, run: () -> Unit) = Ui.text(context, label, 15f, if (primary) Ui.ON_ACCENT else Ui.TEXT, true).apply {
            gravity = Gravity.CENTER
            background = Ui.rounded(if (primary) Ui.ACCENT else Ui.PANEL_ROW, 14f)
            foreground = Ui.ripple(14f)
            setOnClickListener { run() }
        }
        if (picture) {
            buttons.addView(button("Save to Pictures", false) { host.exportPicture(fmt.key == "jpeg", false, transparent, scale) }, LayoutParams(0, 48.dp, 1f))
            buttons.addView(button("Share…", true) { host.exportPicture(fmt.key == "jpeg", true, transparent, scale) }, LayoutParams(0, 48.dp, 1f).apply { marginStart = 8.dp })
        } else {
            buttons.addView(button(L10n.tr("Save") + " " + L10n.tr(fmt.name) + "…", true) { host.close(); host.export(fmt.key) }, LayoutParams(0, 48.dp, 1f))
        }
        body.addView(buttons, LayoutParams(-1, -2).apply { topMargin = 4.dp; bottomMargin = 14.dp })

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

    /** Actions › Settings: the settings changed most often; the rest is one tap away */
    private fun buildPrefs() {
        val spaces = Workspaces.presets + listOfNotNull(Workspaces.mine(host.prefs))
        group(
            switchRow("Light interface", "Bright panels and bars instead of dark ones", host.lightInterface, icon = Icon.SUN) { host.lightInterface = it },
            accentRow()
        )
        group(
            choiceRow("Workspace", spaces.map { it.key to it.name }, Workspaces.current(host.prefs), icon = Icon.LAYOUT) { k ->
                spaces.firstOrNull { it.key == k }?.let { host.useWorkspace(it) }; rebuild()
            },
            choiceRow("Style", listOf(ToolRail.TOP to "On top", ToolRail.DOCK to "Docked", ToolRail.WIDE to "Wide", ToolRail.FLOAT to "Floating"), host.railMode, icon = Icon.DOCK) { m ->
                host.railMode = m; rebuild()
            },
            switchRow("Fade the islands while drawing", null, host.fadeIslands, icon = Icon.HIDE) { host.fadeIslands = it }
        )
        group(
            row("Pressure and Smoothing", closes = false, icon = Icon.PRESSURE) { page = "pressure"; rebuild() },
            switchRow("Brush cursor", null, host.brushCursor, icon = Icon.BRUSH) { host.brushCursor = it },
            switchRow("Keep my last eraser", null, host.rememberEraser, icon = Icon.ERASER) { host.rememberEraser = it }
        )
        group(row("All settings", "Every setting, with search", icon = Icon.SETTINGS) { host.openSettings() })
    }

    private fun setCanvas() {
        group(
            sliderRow("Selection mask visibility", cv.selectionShade / 0.8f, { "${(it * 100).roundToInt()}%" }) { v ->
                cv.selectionShade = v * 0.8f
                cv.invalidate()
                host.prefs.edit().putFloat("selectionShade", cv.selectionShade).apply()
            }
        )
        note("Canvas size, crop and guides are in Actions › Canvas, with an artwork open.")
    }

    private fun setBrushes() {
        group(
            row("Pressure and Smoothing", "The pressure curve, stabilization and motion filtering", closes = false, icon = Icon.PRESSURE) { page = "pressure"; rebuild() },
            row("Fill and ColorDrop", "How far the color goes, and closing gaps in the lines", closes = false, icon = Icon.FILL) { page = "fill"; rebuild() },
            switchRow("Brush cursor", "Shows the brush outline where the pen hovers", host.brushCursor, icon = Icon.BRUSH) { host.brushCursor = it },
            switchRow("Keep my last eraser", "The eraser you used last (its size too) is ready next time, in new artworks as well", host.rememberEraser, icon = Icon.ERASER) { host.rememberEraser = it },
            sliderRow("S Pen button hold time", ((cv.penHoldMs - 400L) / 1600f).coerceIn(0f, 1f),
                { String.format(Locale.US, "%.2f s", it * 1.6f + 0.4f) }) { v ->
                val ms = ((v * 1.6f + 0.4f) * 1000).toLong()
                cv.penHoldMs = ms
                host.prefs.edit().putLong("penHoldMs", ms).apply()
            }
        )
        advancedRow {
            group(
                switchRow("Faster drawing (test)", "Sends only the part of the stroke that changed to the graphics chip. Turn it off if strokes look wrong", host.gpuTiles, icon = Icon.SPARKLE) { host.gpuTiles = it },
                switchRow("GPU brushes (test)", "The graphics chip paints the stroke while you draw; best for big brushes. Not for smudge, living paint, wet mix or dual brushes. Turn it off if strokes look wrong", host.gpuBrushes, icon = Icon.SPARKLE) { host.gpuBrushes = it },
                switchRow("Watercolor drying", "When you lift the pen, a watercolor stroke dries with a crisp edge. Off: it stays as you painted it", host.watercolorDrying, icon = Icon.DROP) { host.watercolorDrying = it },
                switchRow("Wet paint runs together", "A watery stroke over paint that is still wet (about half a minute) bleeds softly into it", host.wetBleed, icon = Icon.INK) { host.wetBleed = it }
            )
        }
    }

    /** one "Advanced" row at the end of a section: it opens the settings few people need */
    private fun advancedRow(content: () -> Unit) {
        group(row(if (advanced) "Advanced  ▾" else "Advanced  ▸", null, closes = false, icon = Icon.WRENCH) { advanced = !advanced; rebuild() })
        if (advanced || query.isNotEmpty()) content()
    }

    private fun setGestures() {
        group(
            row("Gesture controls", "What fingers, the S Pen and □ do", icon = Icon.TOUCH) { host.openGestureControls() },
            row("Shortcuts, shown", "Every gesture as a small moving picture", icon = Icon.VIDEO) { host.openDemos() },
            row("Keyboard shortcuts", "Or hold Ctrl on a keyboard", icon = Icon.KEYBOARD) { host.openShortcuts() }
        )
    }

    private fun setAppearance() {
        val dock = host.dockEdge()
        group(
            switchRow("Light interface", "Bright panels and bars instead of dark ones", host.lightInterface, icon = Icon.SUN) { host.lightInterface = it },
            accentRow()
        )
        group(
            choiceRow("Side", listOf("Left" to "Left", "Right" to "Right"), dock, icon = Icon.LAYOUT) { e ->
                host.setEdges(e, if (e == "Left") "Right" else "Left"); rebuild()
            },
            choiceRow("Style", listOf(ToolRail.TOP to "On top", ToolRail.DOCK to "Docked", ToolRail.WIDE to "Wide", ToolRail.FLOAT to "Floating"), host.railMode, icon = Icon.DOCK) { m ->
                host.railMode = m; rebuild()
            },
            switchRow("Hand island", "Brush, size, opacity, colors and undo by your free hand. Off: change them with gestures", host.sidebarShown, icon = Icon.EYE) { host.sidebarShown = it },
            switchRow("Fade the islands while drawing", "They step back 300 ms into a stroke and come back 700 ms after the pen lifts", host.fadeIslands, icon = Icon.HIDE) { host.fadeIslands = it }
        )
        note("Left-handed? Put the side on Right: the hand island moves to your right hand.")
    }

    private fun setWorkspaces() {
        val spaces = Workspaces.presets + listOfNotNull(Workspaces.mine(host.prefs))
        val cur = Workspaces.current(host.prefs)
        group(
            choiceRow("Workspace", spaces.map { it.key to it.name }, cur, icon = Icon.LAYOUT) { k ->
                spaces.firstOrNull { it.key == k }?.let { host.useWorkspace(it) }; rebuild()
            },
            row("Save this layout as mine", spaces.firstOrNull { it.key == cur }?.hint ?: "Your tool bar as it is now, to come back to", closes = false, icon = Icon.PIN) {
                host.saveWorkspace(); rebuild()
            }
        )
        for (w in Workspaces.presets) note(L10n.tr(w.name) + ": " + L10n.tr(w.hint))
    }

    private fun setLanguage() {
        val langs = listOf(L10n.EN to "English", L10n.AR to "العربية", L10n.CKB to "کوردی")
        group(choiceRow("Language", langs, L10n.lang, icon = Icon.GLOBE) { code -> host.close(); host.setLanguage(code) })
        note("Arabic and Kurdish turn the screen right to left.")
    }

    private fun setFiles() {
        group(
            row("Back up all artworks…", host.backupNote(), icon = Icon.BACKUP) { host.backupAll() },
            row("Restore a backup…", "Brings artworks back from a backup file; nothing here is lost", icon = Icon.SNAPSHOT) { host.restoreBackup() }
        )
        note("Inkora saves your artwork by itself while you work and when you leave.")
    }

    private fun setAbout() {
        val ver = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
        group(
            row("Guide", "Everything Inkora can do, and the gestures that get you there", icon = Icon.LIGHTBULB) { host.openGuide() },
            row("Take the tour again", icon = Icon.HELP) { host.showTips() },
            row("Open-source licenses", "The MyPaint brush engine, json-c, Inter and Noto Sans Arabic", icon = Icon.INFO) { host.openLicenses() }
        )
        note("Inkora $ver")
    }

    /** a page inside this panel: ‹ back to the tab, its title, then [content] (made to sit flat) */
    private fun subPage(title: String, content: View) {
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 8.dp) }
        head.addView(IconView(context, if (L10n.isRtl) Icon.CHEV_RIGHT else Icon.CHEV_LEFT).apply {
            iconScale = 0.5f
            contentDescription = L10n.tr("Back")
            setOnClickListener { page = null; rebuild() }
        }, LayoutParams(40.dp, 40.dp))
        head.addView(Ui.text(context, title, 20f, bold = true).apply { setPadding(4.dp, 0, 0, 0) })
        body.addView(head)
        (content.parent as? android.view.ViewGroup)?.removeView(content)
        content.background = null
        content.elevation = 0f
        if (content is LinearLayout) content.setPadding(0, 0, 0, content.paddingBottom)
        // a title of its own inside the content repeats ours: hide it
        if (content is LinearLayout && content.childCount > 0) {
            val first = content.getChildAt(0)
            if (first is android.widget.TextView && first.text.toString() == L10n.tr(title)) first.visibility = View.GONE
        }
        body.addView(content, LayoutParams(-1, -2))
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

    /** Settings › Fill and ColorDrop: for the Fill tool and for dragging the color onto the artwork */
    /** opens straight on a settings page ("fill", "pressure") */
    /** opens on one tab (Add, Canvas, ...) */
    fun openTab(t: Tab) {
        tab = t
        page = null
        host.prefs.edit().putString("actions_tab", t.name).apply()
        rebuild()
    }

    fun openPage(p: String) {
        tab = Tab.PREFS
        page = p
        rebuild()
    }

    private fun buildFill() {
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 8.dp) }
        head.addView(Ui.text(context, "‹", 30f).apply {
            setPadding(10.dp, 0, 14.dp, 0)
            setOnClickListener { page = null; rebuild() }
        })
        head.addView(Ui.text(context, "Fill and ColorDrop", 20f, bold = true))
        body.addView(head)
        group(
            sliderRow("Sensitivity", cv.fillThreshold, { "${(it * 100).roundToInt()}%" }) { v ->
                cv.fillThreshold = v
                host.prefs.edit().putFloat("fillThreshold", v).apply()
            },
            sliderRow("Close gaps", (cv.fillGap / 30f).coerceIn(0f, 1f), { if ((it * 30).roundToInt() == 0) L10n.tr("Off") else "${(it * 30).roundToInt()} px" }) { v ->
                cv.fillGap = (v * 30).roundToInt()
                host.prefs.edit().putInt("fillGap", cv.fillGap).apply()
            }
        )
        note("Sensitivity: how different a color can be and still be filled. Higher fills over soft edges and shading.")
        note("Close gaps: lines that do not quite meet still hold the color in, for gaps up to about twice this size. 0 = off. The same for the Fill tool and for dragging the color circle onto the artwork.")
        group(row("Reset", closes = false) {
            cv.fillThreshold = 0.22f; cv.fillGap = 0
            host.prefs.edit().putFloat("fillThreshold", cv.fillThreshold).putInt("fillGap", 0).apply()
            rebuild()
        })
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
