package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity(), CanvasView.Listener {

    private lateinit var root: FrameLayout
    private lateinit var cv: CanvasView
    private lateinit var hud: HudView
    private lateinit var brushBtn: IconView
    private lateinit var eraserBtn: IconView
    private lateinit var layersBtn: IconView
    private lateinit var symmetryBtn: IconView
    private lateinit var guideBtn: IconView
    private lateinit var doneBtn: android.widget.TextView
    private lateinit var colorBtn: ColorSwatchView
    private lateinit var undoBtn: IconView
    private lateinit var redoBtn: IconView
    private lateinit var sizeSlider: VerticalSlider
    private lateinit var opacitySlider: VerticalSlider
    private lateinit var dropDot: ColorSwatchView

    private val paintBrushes = Brushes.presets.map { Brush(it) }.toMutableList()
    private val eraserBrushes = Brushes.presets.map { Brush(it) }.toMutableList()
    private val smudgeBrushes = Brushes.presets.map { Brush(it) }.toMutableList()
    private lateinit var smudgeBtn: IconView
    private lateinit var creativeBtn: View
    private lateinit var editingBtn: View
    private lateinit var topBar: LinearLayout
    private lateinit var sideBar: LinearLayout
    private lateinit var dock: ToolDockView
    private lateinit var titleView: android.widget.TextView
    private lateinit var focusExit: IconView
    private var dockIds = mutableListOf<String>()
    private var leftHanded = false
    private var focusMode = false
    private lateinit var brushDot: BrushDotView
    private lateinit var opacityLabel: android.widget.TextView
    private lateinit var layerName: android.widget.TextView
    private lateinit var selectBtn: IconView
    private lateinit var transformBtn: IconView
    private lateinit var adjustBtn: IconView
    private var bottomBar: View? = null
    private var gallery: GalleryView? = null
    private var timeLapseOn = true
    // adjustments
    private var adjustKind: AdjustKind? = null
    private var adjustSrc: Bitmap? = null
    private var adjustValues = FloatArray(4)
    private var adjustBusy = false
    private var adjustPending = false
    private var adjustFinish: Boolean? = null
    private val adjustExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val recentColors = mutableListOf<Int>()
    /** last used (tool, brush) pairs for the quick menu, newest first */
    private val recentTools = mutableListOf<Pair<Tool, Brush>>()
    private lateinit var quickMenu: QuickMenuView
    private lateinit var colorWheel: ColorWheelView
    private val prefs by lazy { getSharedPreferences("inkora", MODE_PRIVATE) }
    private val gestures by lazy { GestureSettings(prefs) }
    private lateinit var modifyBtn: IconView
    private lateinit var actionsBtn: View
    private var popup: PopupWindow? = null
    private var savedRevision = -1
    private val main = Handler(Looper.getMainLooper())

    // =========================================================
    // lifecycle
    // =========================================================

    /** Arabic and Kurdish switch the whole interface to right-to-left */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(L10n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReporter()
        LivingBrushes.init(this)
        // the layer limit follows this device's memory
        runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            Document.setDeviceMemory(mi.totalMem)
        }
        PaletteStore.load(prefs)
        PressureCurve.load(prefs)
        Symmetry.segments = prefs.getInt("kaleidoSegments", 6)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(0xFF2A2B31.toInt()))
        buildUi()
        goFullscreen()

        eraserBrushes.forEach { it.size = 40f }
        // brushes the user imported earlier
        for (p in BrushImport.loadAll(this)) {
            paintBrushes.add(Brush(p))
            eraserBrushes.add(Brush(p).apply { size = 40f })
            smudgeBrushes.add(Brush(p).apply { size = 50f; opacity = 0.75f })
        }
        cv.paintBrush = paintBrushes.first { it.preset.name == "Studio Ink" }
        cv.eraserBrush = eraserBrushes.first { it.preset.name == "Soft Airbrush" }
        cv.eraserBrush.size = 80f
        cv.penHoldMs = prefs.getLong("penHoldMs", 600L)
        cv.showBrushCursor = prefs.getBoolean("brushCursor", true)
        if (!prefs.getBoolean("sidebar", true)) sideBar.visibility = View.GONE
        cv.pressureGamma = prefs.getFloat("pressureGamma", 1f)
        timeLapseOn = prefs.getBoolean("timelapse", true)
        cv.fillThreshold = prefs.getFloat("fillThreshold", cv.fillThreshold)
        cv.quickShapeEnabled = prefs.getBoolean("quickShape", true)
        if (prefs.contains("fingerDrawing")) {
            cv.fingerDrawing = prefs.getBoolean("fingerDrawing", true)
            cv.fingerDrawingAuto = false
        }
        smudgeBrushes.forEach { it.size = 50f; it.opacity = 0.75f }
        // each brush's Brush Studio settings, as the user left them
        for (t in Tool.entries) for (b in brushListFor(t)) BrushSettings.load(prefs, t, b)
        cv.smudgeBrush = smudgeBrushes.first { it.preset.name == "Soft Airbrush" }
        noteRecent()

        // open the last artwork (older versions' single autosave moves into the gallery)
        val migrated = Gallery.migrateAutosave(this)
        val lastId = prefs.getString("current", null) ?: migrated
        val doc = lastId?.let { Gallery.load(this, it) }
            ?: Gallery.list(this).firstOrNull()?.let { Gallery.load(this, it.id) }
        if (doc != null) {
            openDocument(doc)
            savedDoc = doc
            savedRevision = doc.revision
        } else {
            val (w, h) = screenSize()
            openDocument(newDocument(w, h))
        }
        refreshAll()
        restorePins()
        // the app always starts on the home screen
        showGallery(fromCanvas = false)
        // a brush file, project or image opened with Inkora from another app
        handleIncoming(intent)
    }

    private fun newDocument(w: Int, h: Int) = Document.blank(w, h).apply {
        galleryId = Gallery.newId()
        title = "Untitled Artwork"
        revision = 1
    }

    private fun openDocument(d: Document) {
        if (cv.transform != null) cv.finishTransform(false)
        if (adjustKind != null) endAdjust(false)
        if (cv.liquify != null) cv.endLiquify(false)
        if (cv.editingGuide) { cv.editingGuide = false; doneBtn.visibility = View.GONE }
        cv.selection = null
        cv.interaction = Interaction.PAINT
        hideBottomBar()
        if (d.galleryId == null) d.galleryId = Gallery.newId()
        cv.setDocument(d)
        // opening an artwork is not a step of its time-lapse
        lapseDoc = d; lapseRevision = d.revision
        prefs.edit().putString("current", d.galleryId).apply()
        refreshAll()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            quickMenu.isOpen -> quickMenu.close()
            root.findViewWithTag<View>("tips") != null -> root.findViewWithTag<View>("tips")?.let { root.removeView(it); prefs.edit().putBoolean("tips_done", true).apply() }
            root.findViewWithTag<View>("overlay") != null -> closeSettings()
            focusMode -> setFocus(false)
            colorWheel.isOpen -> colorWheel.close()
            adjustKind != null -> endAdjust(false)
            cv.transform != null -> leaveTransform(false)
            cv.isDocReady() && cv.liquify != null -> leaveLiquify(false)
            cv.editingGuide -> finishGuideEdit()
            cv.interaction == Interaction.SELECT -> { cv.interaction = Interaction.PAINT; hideBottomBar(); refreshAll() }
            gallery != null && galleryFromCanvas -> closeGallery()
            else -> {
                autosave()
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    // ---------- crash report: shown on the next start so it can be sent to the developer ----------

    private fun crashFile() = java.io.File(filesDir, "last_crash.txt")

    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
                crashFile().writeText("Inkora $version · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n\n$sw")
            }
            previous?.uncaughtException(t, e)
        }
        val f = crashFile()
        if (f.exists()) {
            val text = runCatching { f.readText() }.getOrDefault("")
            f.delete()
            if (text.isNotBlank()) main.post { showCrashReport(text) }
        }
    }

    private fun showCrashReport(text: String) {
        val tv = android.widget.TextView(this).apply {
            this.text = text.take(4000)
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(24.dp, 8.dp, 24.dp, 8.dp)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Inkora closed unexpectedly last time"))
            .setMessage(L10n.tr("Tap Copy and send this text to the developer so it can be fixed."))
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton(L10n.tr("Copy")) { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Inkora crash", text))
                toast("Copied")
            }
            .setNegativeButton(L10n.tr("Close"), null)
            .show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(periodicSave)
        autosave()
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(periodicSave)
        main.postDelayed(periodicSave, SAVE_EVERY_MS)
    }

    private var drawingNow = false
    private var lastDrawEnd = 0L

    /**
     * Saves now and then while you work, so a crash or a dead battery costs minutes, not the
     * session. Waits for a pause in the drawing; very heavy artworks (which would need a lot of
     * memory for the copy) are still saved when you leave the canvas or the app.
     */
    private val periodicSave = object : Runnable {
        override fun run() {
            val idle = !drawingNow && android.os.SystemClock.uptimeMillis() - lastDrawEnd > 2500
            if (!idle) { main.postDelayed(this, 3000); return }
            if (cv.isDocReady() && gallery == null && !cv.isEditing) {
                val d = cv.doc
                val bytes = d.layers.size.toLong() * d.width * d.height * 4
                if (bytes <= 700_000_000L) autosave(lastResort = false)
            }
            main.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    private var savedDoc: Document? = null

    /** Saves the current artwork into the gallery (in the background) if it changed. */
    private fun autosave(force: Boolean = false, lastResort: Boolean = true) {
        if (!cv.isDocReady()) return
        // never save half-finished edits
        if (cv.transform != null || adjustKind != null) return
        val doc = cv.doc
        if (!force && doc === savedDoc && doc.revision == savedRevision) return
        val id = doc.galleryId ?: Gallery.newId().also { doc.galleryId = it }
        val title = doc.title
        val origin = doc.origin
        val rev = doc.revision
        val thumb = try { Gallery.makeThumb(doc) } catch (e: OutOfMemoryError) { null }
        val snap = try { ProjectIO.snapshot(doc) } catch (e: OutOfMemoryError) { null }
        if (snap == null) {
            // no memory for a copy of every layer: write straight from the layers, waiting here
            // until it is done (nothing can paint meanwhile). Slower, but the work is not lost.
            if (!lastResort) { thumb?.recycle(); return }
            hud.show("Saving…", sticky = true)
            val live = ProjectIO.snapshot(doc, copy = false)
            val ok = runCatching { saver.submit { Gallery.save(applicationContext, id, title, live, thumb, origin) }.get() }.isSuccess
            thumb?.recycle()
            hud.hideSoon()
            if (ok) { savedDoc = doc; savedRevision = rev } else toast("Could not save the artwork: not enough memory or storage")
            return
        }
        saver.execute {
            runCatching { Gallery.save(applicationContext, id, title, snap, thumb, origin) }
                .onSuccess { main.post { savedDoc = doc; savedRevision = rev } }
                .onFailure { main.post { toast("Could not save the artwork: not enough memory or storage") } }
            thumb?.recycle()
            snap.layers.forEach { it.bitmap.recycle() }
        }
    }

    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Waits until pending saves are written (used before showing the gallery). */
    private fun afterSaves(block: () -> Unit) {
        saver.execute { main.post(block) }
    }

    @Suppress("DEPRECATION")
    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.currentWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels; h = dm.heightPixels
        }
        return w to h
    }

    // =========================================================
    // UI
    // =========================================================

    private fun buildUi() {
        root = FrameLayout(this)
        cv = CanvasView(this)
        cv.listener = this
        root.addView(cv, FrameLayout.LayoutParams(-1, -1))

        hud = HudView(this)
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))

        dockIds = ToolCatalog.decode(prefs.getString("dock", null))
        leftHanded = prefs.getBoolean("leftHanded", false)

        // ---------- top bar ----------
        //  [Home] [Menu] | [Creative] [Editing]      artwork title      [Brush] [Smudge] [Eraser] | [Layers] (Color)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 0, 10.dp, 0)
            setBackgroundColor(Ui.BAR)
            elevation = 6f.dp
        }
        topBar = bar
        val homeBtn = Ui.text(this, "Gallery", 16f).apply {
            contentDescription = "Gallery"
            gravity = Gravity.CENTER
            setPadding(10.dp, 0, 12.dp, 0)
            setOnClickListener { if (!busy()) showGallery() }
        }
        bar.addView(homeBtn, LinearLayout.LayoutParams(-2, 48.dp))
        val actionsBtn = IconView(this, Icon.WRENCH).apply { contentDescription = "Actions" }
        actionsBtn.setOnClickListener { if (!busy()) showActions(actionsBtn) }
        bar.addView(actionsBtn, LinearLayout.LayoutParams(44.dp, 48.dp))
        this.actionsBtn = actionsBtn

        // the Creative and Editing panels open from the "•••" in the middle of the bar
        creativeBtn = barTab(Icon.SPARKLE, "Creative") { if (!busy()) showToolsMenu(true) }
        editingBtn = barTab(Icon.WAND, "Editing") { if (!busy()) showToolsMenu(false) }

        // one tap to the editing tools painters use most: adjustments, selection, transform
        adjustBtn = IconView(this, Icon.ADJUST).apply { contentDescription = "Adjustments" }
        adjustBtn.setOnClickListener { if (adjustKind == null && !busy()) showToolsMenu(false, "adjust") }
        selectBtn = IconView(this, Icon.SELECT).apply { contentDescription = "Selection" }
        selectBtn.setOnClickListener {
            if (cv.interaction == Interaction.SELECT) { cv.interaction = Interaction.PAINT; hideBottomBar(); refreshAll() }
            else if (!busy()) enterSelect()
        }
        transformBtn = IconView(this, Icon.TRANSFORM).apply { contentDescription = "Transform" }
        transformBtn.setOnClickListener {
            if (cv.interaction == Interaction.TRANSFORM) leaveTransform(true)
            else if (!busy()) runTool("transform", transformBtn)
        }
        bar.addView(adjustBtn, LinearLayout.LayoutParams(44.dp, 48.dp))
        bar.addView(selectBtn, LinearLayout.LayoutParams(44.dp, 48.dp))
        bar.addView(transformBtn, LinearLayout.LayoutParams(44.dp, 48.dp))
        symmetryBtn = IconView(this, Icon.SYMMETRY)
        guideBtn = IconView(this, Icon.GRID)

        titleView = Ui.text(this, "", 13.5f, Ui.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(12.dp, 0, 12.dp, 0)
            setOnClickListener {
                if (cv.isDocReady() && !busy()) renameDialog(cv.doc.title) { t ->
                    cv.doc.title = t; cv.doc.revision++; autosave(force = true); refreshAll()
                }
            }
        }
        // the middle of the bar: "•••" opens the Creative and Editing tool panels
        val more = Ui.text(this, "•••", 18f, Ui.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            contentDescription = "More tools"
            setOnClickListener { v ->
                if (busy()) return@setOnClickListener
                val pm = PopupMenu(this@MainActivity, v)
                pm.menu.add(0, 1, 0, "Creative tools")
                pm.menu.add(0, 2, 1, "Editing tools")
                pm.menu.add(0, 3, 2, "Rename artwork…")
                pm.setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> showToolsMenu(true)
                        2 -> showToolsMenu(false)
                        else -> titleView.performClick()
                    }
                    true
                }
                L10n.menu(pm.menu); pm.show()
            }
        }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(more, LinearLayout.LayoutParams(64.dp, 48.dp))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        // painting tools as one group
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(3.dp, 0, 3.dp, 0)
        }
        brushBtn = IconView(this, Icon.BRUSH).apply { contentDescription = "Brush" }
        brushBtn.setOnClickListener {
            if (busy()) return@setOnClickListener
            if (cv.tool == Tool.BRUSH && cv.interaction == Interaction.PAINT && !cv.fillMode) showBrushes(Tool.BRUSH) else selectTool(Tool.BRUSH)
        }
        brushBtn.setOnLongClickListener { if (!busy()) { selectTool(Tool.BRUSH); showBrushes(Tool.BRUSH) }; true }
        tools.addView(brushBtn, LinearLayout.LayoutParams(46.dp, 44.dp))

        smudgeBtn = IconView(this, Icon.SMUDGE).apply { contentDescription = "Smudge" }
        smudgeBtn.setOnClickListener {
            if (busy()) return@setOnClickListener
            if (cv.tool == Tool.SMUDGE && cv.interaction == Interaction.PAINT && !cv.fillMode) showBrushes(Tool.SMUDGE) else selectTool(Tool.SMUDGE)
        }
        smudgeBtn.setOnLongClickListener { if (!busy()) { selectTool(Tool.SMUDGE); showBrushes(Tool.SMUDGE) }; true }
        tools.addView(smudgeBtn, LinearLayout.LayoutParams(46.dp, 44.dp))

        eraserBtn = IconView(this, Icon.ERASER).apply { contentDescription = "Eraser" }
        eraserBtn.setOnClickListener {
            if (busy()) return@setOnClickListener
            if (cv.tool == Tool.ERASER && cv.interaction == Interaction.PAINT && !cv.fillMode) showBrushes(Tool.ERASER) else selectTool(Tool.ERASER)
        }
        eraserBtn.setOnLongClickListener { if (!busy()) { selectTool(Tool.ERASER); showBrushes(Tool.ERASER) }; true }
        tools.addView(eraserBtn, LinearLayout.LayoutParams(46.dp, 44.dp))
        bar.addView(tools, LinearLayout.LayoutParams(-2, 44.dp))

        layersBtn = IconView(this, Icon.LAYERS).apply { contentDescription = "Layers" }
        layersBtn.setOnClickListener { if (!busy()) showLayers() }
        val layersBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        layersBox.addView(layersBtn, LinearLayout.LayoutParams(44.dp, 36.dp))
        layerName = Ui.text(this, "", 9f, Ui.TEXT_DIM).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setOnClickListener { if (!busy()) showLayers() }
        }
        layerName.visibility = View.GONE // the Layers panel shows the names
        bar.addView(layersBox, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 4.dp })

        colorBtn = ColorSwatchView(this)
        setupColorButton()
        bar.addView(colorBtn, LinearLayout.LayoutParams(52.dp, 52.dp))

        root.addView(bar, FrameLayout.LayoutParams(-1, 56.dp, Gravity.TOP))
        // hairline under the bar
        root.addView(View(this).apply { setBackgroundColor(0x14FFFFFF) }, FrameLayout.LayoutParams(-1, 1.dp, Gravity.TOP).apply { topMargin = 56.dp })

        // ---------- side bar: size, eyedropper, opacity, undo, redo ----------
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(4.dp, 10.dp, 4.dp, 8.dp)
            background = Ui.rounded(Ui.BAR, 16f, 0x14FFFFFF, 1)
            elevation = 8f.dp
        }
        sideBar = side
        brushDot = BrushDotView(this).apply { setOnClickListener { if (!busy()) showBrushes(cv.tool) } }
        side.addView(brushDot, LinearLayout.LayoutParams(40.dp, 40.dp))
        sizeSlider = VerticalSlider(this)
        sizeSlider.onChange = { v ->
            cv.currentBrush.size = Brushes.sizeFromSlider(v)
            brushDot.sizePx = cv.currentBrush.size
            showSizeHud(true)
        }
        sizeSlider.onDragState = { dragging -> if (dragging) showSizeHud(true) else hud.hideSoon() }
        side.addView(sizeSlider, LinearLayout.LayoutParams(44.dp, 180.dp))

        // the □ modify button: tapped, it does what "Tap □" is set to (the eyedropper at first);
        // held down, touches on the canvas do what "□ + Touch" and "□ + Pen" are set to
        modifyBtn = IconView(this, Icon.EYEDROPPER).apply { contentDescription = "Modify" }
        var modifyDownAt = 0L
        modifyBtn.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    modifyDownAt = e.eventTime
                    cv.modifyHeld = true
                    cv.modifyUsed = false
                    v.isPressed = true
                    (v as IconView).active = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cv.modifyHeld = false
                    v.isPressed = false
                    (v as IconView).active = false
                    if (e.actionMasked == MotionEvent.ACTION_UP && !cv.modifyUsed && e.eventTime - modifyDownAt < 700) {
                        v.performClick()
                        gestures.purposeOf(GestureTrigger.TAP_MODIFY)?.let { p ->
                            val loc = IntArray(2); v.getLocationInWindow(loc)
                            runGesture(p, loc[0] + v.width / 2f, loc[1] + v.height / 2f)
                        }
                    }
                }
            }
            true
        }
        side.addView(modifyBtn, LinearLayout.LayoutParams(44.dp, 44.dp).apply { topMargin = 4.dp; bottomMargin = 4.dp })

        opacitySlider = VerticalSlider(this)
        opacitySlider.onChange = { v ->
            cv.currentBrush.opacity = v.coerceIn(0.01f, 1f)
            brushDot.opacity = cv.currentBrush.opacity
            opacityLabel.text = "${(cv.currentBrush.opacity * 100).roundToInt()}%"
            hud.show("Opacity ${(cv.currentBrush.opacity * 100).roundToInt()}%", sticky = true)
        }
        opacitySlider.onDragState = { dragging ->
            if (dragging) hud.show("Opacity ${(cv.currentBrush.opacity * 100).roundToInt()}%", sticky = true) else hud.hideSoon()
        }
        side.addView(opacitySlider, LinearLayout.LayoutParams(44.dp, 180.dp))
        opacityLabel = Ui.text(this, "100%", 10f, Ui.TEXT_DIM, true).apply { gravity = Gravity.CENTER }
        side.addView(opacityLabel, LinearLayout.LayoutParams(-1, -2))

        side.addView(Ui.divider(this), LinearLayout.LayoutParams(28.dp, 1.dp).apply { topMargin = 8.dp; bottomMargin = 4.dp })
        undoBtn = IconView(this, Icon.UNDO).apply { contentDescription = "Undo" }
        undoBtn.setOnClickListener { cv.undo() }
        side.addView(undoBtn, LinearLayout.LayoutParams(44.dp, 44.dp))
        redoBtn = IconView(this, Icon.REDO).apply { contentDescription = "Redo" }
        redoBtn.setOnClickListener { cv.redo() }
        side.addView(redoBtn, LinearLayout.LayoutParams(44.dp, 44.dp))
        root.addView(side, FrameLayout.LayoutParams(-2, -2))

        // ---------- customizable toolbar (dock) ----------
        dock = ToolDockView(this,
            onTool = { id, anchor -> runTool(id, anchor) },
            isActive = { id -> isToolActive(id) },
            onCustomize = { if (!busy()) showToolbarEditor() })
        dock.setTools(dockIds)
        root.addView(dock, FrameLayout.LayoutParams(-2, -2))
        applySides()

        // focus mode: a single small button brings the interface back
        focusExit = IconView(this, Icon.FOCUS).apply {
            background = Ui.rounded(0xCC131316.toInt(), 14f)
            contentDescription = "Show interface"
            visibility = View.GONE
            setOnClickListener { setFocus(false) }
        }
        root.addView(focusExit, FrameLayout.LayoutParams(48.dp, 48.dp, Gravity.TOP or Gravity.END).apply { topMargin = 12.dp; rightMargin = 12.dp })

        // "Done" button shown while moving vanishing points
        doneBtn = Ui.button(this, "Done moving points") { finishGuideEdit() }.apply {
            textSize = 15f
            background = Ui.rounded(Ui.ACCENT, 20f)
            setPadding(22.dp, 10.dp, 22.dp, 10.dp)
            visibility = View.GONE
        }
        root.addView(doneBtn, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = 68.dp
        })

        // dot that follows the finger during ColorDrop
        dropDot = ColorSwatchView(this)
        dropDot.visibility = View.GONE
        root.addView(dropDot, FrameLayout.LayoutParams(44.dp, 44.dp, Gravity.TOP or Gravity.LEFT))

        // pen quick menu (S Pen button) — on top of everything
        quickMenu = QuickMenuView(this)
        quickMenu.visibility = View.GONE
        root.addView(quickMenu, FrameLayout.LayoutParams(-1, -1))
        colorWheel = ColorWheelView(this)
        colorWheel.visibility = View.GONE
        root.addView(colorWheel, FrameLayout.LayoutParams(-1, -1))
        cv.penListener = penListener
        cv.gestures = gestures
        gestures.fingerPaintNow = { cv.fingerDrawing }
        syncModifyIcon()

        setContentView(root)
    }

    private fun barDivider() = View(this).apply { setBackgroundColor(0x16FFFFFF) }.also {
        it.layoutParams = LinearLayout.LayoutParams(1.dp, 24.dp).apply { leftMargin = 6.dp; rightMargin = 4.dp }
    }

    /** icon + label button for the top bar */
    private fun barTab(icon: Icon, label: String, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6.dp, 0, 12.dp, 0)
            background = Ui.rounded(0x00000000, 12f)
            isClickable = true
            setOnClickListener { onClick() }
        }
        box.addView(IconView(this, icon).apply { isClickable = false; iconScale = 0.62f }, LinearLayout.LayoutParams(32.dp, 32.dp))
        box.addView(Ui.text(this, label, 14f))
        return box
    }

    private fun setTabActive(tab: View, on: Boolean) {
        tab.background = Ui.rounded(if (on) Ui.ACCENT_SOFT else 0, 12f)
    }

    /** sliders on one side, toolbar on the other (swapped for left-handed people or customized placement) */
    private fun applySides() {
        // sliders live on the left or right; the toolbar goes anywhere else
        val slidersEdge = slidersEdge()
        val dockEdge = dockEdge()

        // Sliders sidebar
        (sideBar.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = (if (slidersEdge == "Right") Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
            leftMargin = 8.dp; rightMargin = 8.dp; topMargin = 56.dp
            sideBar.layoutParams = this
        }

        // Toolbar
        (dock.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = when (dockEdge) {
                "Left" -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                "Top" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                "Bottom" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                else -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
            }
            val isHoriz = (dockEdge == "Top" || dockEdge == "Bottom")
            dock.setDockOrientation(if (isHoriz) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL)
            leftMargin = 8.dp; rightMargin = 8.dp; topMargin = if (dockEdge == "Top") 56.dp else 70.dp; bottomMargin = 14.dp
            dock.layoutParams = this
        }
    }

    /** closes a full-screen overlay (gesture controls, keyboard shortcuts) */
    private fun closeSettings(): Boolean {
        val v = root.findViewWithTag<View>("overlay") ?: return false
        // the Brush Studio saves its changes on the way out (it calls back here to be removed)
        if (v is BrushStudioView && !v.closing) { v.finish(); return true }
        if (v is Scene3DEditor && !v.closing) { v.finish(); return true }
        root.removeView(v)
        return true
    }

    private fun showOverlay(v: View) {
        closeSettings()
        v.tag = "overlay"
        v.alpha = 0f
        // above the bars, the toolbar, the sliders and pinned windows (they are raised too)
        v.elevation = 60f.dp
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        v.animate().alpha(1f).setDuration(160).start()
    }

    private fun showGestureControls() {
        popup?.dismiss()
        showOverlay(GestureControlsView(this, gestures,
            onChanged = {
                cv.fingerDrawing = gestures.isOn(GesturePurpose.GENERAL, "FINGER_PAINT")
                cv.fingerDrawingAuto = false
                cv.quickShapeEnabled = gestures.isOn(GesturePurpose.QUICKSHAPE, "DRAW_HOLD")
                syncModifyIcon()
            },
            onDone = { closeSettings() }))
    }

    private var shortcutsHeld = false

    private fun showShortcuts(closable: Boolean = true) {
        popup?.dismiss()
        showOverlay(ShortcutsView(this, closable) { closeSettings() })
    }

    /** the effective sides (the toolbar never shares one with the sliders) */
    private fun slidersEdge() = when (prefs.getString("sliders_edge", null)) {
        "Left" -> "Left"; "Right" -> "Right"
        else -> if (leftHanded) "Right" else "Left"
    }

    private fun dockEdge(): String {
        val sl = slidersEdge()
        var d = prefs.getString("dock_edge", null) ?: (if (sl == "Left") "Right" else "Left")
        if (d == sl) d = if (sl == "Left") "Right" else "Left"
        return d
    }

    // =========================================================
    // customizable toolbar
    // =========================================================

    private fun saveDock() {
        prefs.edit().putString("dock", ToolCatalog.encode(dockIds)).apply()
        dock.setTools(dockIds)
    }

    private fun toggleDock(id: String) {
        if (id in dockIds) {
            dockIds.remove(id)
            hud.show("Removed from your toolbar")
        } else {
            if (dockIds.size >= ToolCatalog.MAX) { hud.show("Your toolbar is full (${ToolCatalog.MAX} tools)"); return }
            dockIds.add(id)
            hud.show("Added to your toolbar")
        }
        saveDock()
    }

    private fun showToolbarEditor() {
        val ed = ToolbarEditor(this, dockIds, leftHanded,
            onChange = { list -> dockIds = list.toMutableList(); saveDock() },
            onLeftHanded = { on ->
                leftHanded = on
                prefs.edit().putBoolean("leftHanded", on).remove("dock_edge").remove("sliders_edge").apply()
                popup?.dismiss()
                applySides()
                root.postDelayed({ showToolbarEditor() }, 150)
            })
        showBeside(dock, ed, 380, 640)
    }

    private fun setFocus(on: Boolean) {
        focusMode = on
        val v = if (on) View.GONE else View.VISIBLE
        topBar.visibility = v
        (root.getChildAt(root.indexOfChild(topBar) + 1))?.visibility = v // hairline
        sideBar.visibility = if (on || !prefs.getBoolean("sidebar", true)) View.GONE else View.VISIBLE
        dock.visibility = v
        focusExit.visibility = if (on) View.VISIBLE else View.GONE
        if (on) hud.show("Focus mode · tap the corner button to bring the tools back")
    }

    /** runs a tool from the toolbar or the Creative / Editing panel */
    private fun runTool(id: String, anchor: View? = null) {
        if (!cv.isDocReady()) return
        // looking around is always fine; "liquify" itself is how you leave liquify
        val free = id in setOf("fit", "flip", "focus", "actual_size") || (id == "liquify" && cv.liquify != null)
        if (!free && busy()) return
        val isVector = cv.doc.active.vector != null
        when {
            id in setOf("sel_free", "sel_rect", "sel_ellipse", "sel_wand", "sel_color", "sel_brush") -> {
                val shape = when (id) {
                    "sel_rect" -> SelectShape.RECTANGLE; "sel_ellipse" -> SelectShape.ELLIPSE
                    "sel_wand" -> SelectShape.WAND; "sel_color" -> SelectShape.COLOR; "sel_brush" -> SelectShape.BRUSH
                    else -> SelectShape.FREEHAND
                }
                if (cv.interaction == Interaction.SELECT && cv.selectShape == shape) {
                    cv.interaction = Interaction.PAINT; hideBottomBar(); refreshAll()
                } else enterSelect(shape)
            }
            id == "sel_all" -> if (isVector) cv.vectorEditor.selectAll() else { cv.selectAll(); enterSelect() }
            id == "sel_invert" -> if (cv.selection == null) hud.show("Nothing selected yet") else cv.invertSelection()
            id == "deselect" -> { cv.selection = null; if (isVector) cv.vectorEditor.deselect() }
            id == "copy_paste" -> if (cv.selection == null) hud.show("Select something first") else cv.copySelectionToNewLayer()
            id == "transform" -> if (isVector) { cv.vectorEditor.tool = VTool.SELECT; refreshAll() } else enterTransform()
            id == "eyedropper" -> cv.armEyedropper()
            id == "fill" -> {
                cv.fillMode = !cv.fillMode
                if (cv.fillMode && cv.interaction == Interaction.SELECT) { cv.interaction = Interaction.PAINT; hideBottomBar() }
                hud.show(if (cv.fillMode) "Fill: tap an area to fill it with your color" else "Fill off")
            }
            id == "text" -> textDialog()
            id == "photo" -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            }, REQ_IMAGE)
            id == "vector" -> if (isVector) { cv.vectorEditor.tool = VTool.PEN; refreshAll() } else addVectorLayer()
            id == "pose3d" -> open3D(cv.doc.active.takeIf { it.scene != null })
            id == "shadow_photo" -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            }, REQ_SHADOW_IMAGE)
            id == "actual_size" -> cv.actualSize()
            id == "liquify" -> if (cv.liquify != null) leaveLiquify(true) else enterLiquify()
            id == "canvas" -> showCanvasSize()
            id == "guides" -> showGuide(anchor)
            id == "assisted" -> {
                val g = cv.doc.guide
                if (g.type == GuideType.OFF) { hud.show("Choose a grid first (Grid & perspective)"); showGuide(anchor) }
                else { g.assisted = !g.assisted; cv.doc.revision++; cv.invalidate(); hud.show(if (g.assisted) "Strokes snap to the grid" else "Free drawing") }
            }
            id == "quickshape" -> {
                cv.quickShapeEnabled = !cv.quickShapeEnabled
                prefs.edit().putBoolean("quickShape", cv.quickShapeEnabled).apply()
                hud.show(if (cv.quickShapeEnabled) "QuickShape on: hold the pen at the end of a stroke" else "QuickShape off")
            }
            id == "symmetry" -> showSymmetry(anchor)
            id == "adjust" -> showToolsMenu(false, "adjust")
            id.startsWith("adj_") -> AdjustKind.values().firstOrNull { "adj_${it.name}" == id }?.let { startAdjust(it) }
            id == "fit" -> cv.fitToScreen()
            id == "flip" -> cv.flipHorizontal()
            id == "reference" -> toggleReference()
            id == "navigator" -> toggleNavigator()
            id == "lighting" -> toggleLighting()
            id == "focus" -> setFocus(!focusMode)
            id == "export" -> exportImage(jpeg = false, share = false)
            id == "share" -> exportImage(jpeg = false, share = true)
            id == "timelapse" -> exportTimeLapse()
            id.startsWith("feather_") -> {
                if (cv.selection == null) hud.show("Select something first")
                else id.removePrefix("feather_").toFloatOrNull()?.let { cv.featherSelection(it); hud.show("Feathered") }
            }
        }
        refreshAll()
    }

    private fun isToolActive(id: String): Boolean {
        if (!cv.isDocReady()) return false
        val sel = cv.interaction == Interaction.SELECT
        return when (id) {
            "sel_free" -> sel && cv.selectShape == SelectShape.FREEHAND
            "sel_rect" -> sel && cv.selectShape == SelectShape.RECTANGLE
            "sel_ellipse" -> sel && cv.selectShape == SelectShape.ELLIPSE
            "sel_wand" -> sel && cv.selectShape == SelectShape.WAND
            "sel_color" -> sel && cv.selectShape == SelectShape.COLOR
            "sel_brush" -> sel && cv.selectShape == SelectShape.BRUSH
            "transform" -> cv.interaction == Interaction.TRANSFORM
            "liquify" -> cv.liquify != null
            "fill" -> cv.fillMode
            "guides" -> cv.doc.guide.type != GuideType.OFF
            "assisted" -> cv.doc.guide.assisted && cv.doc.guide.type != GuideType.OFF
            "quickshape" -> cv.quickShapeEnabled
            "symmetry" -> cv.symmetry != Symmetry.OFF
            "adjust" -> adjustKind != null
            "focus" -> focusMode
            "vector" -> cv.doc.active.vector != null
            "reference" -> pinned.containsKey("reference")
            "navigator" -> pinned.containsKey("navigator")
            "lighting" -> pinned.containsKey("lighting")
            else -> if (id.startsWith("adj_")) adjustKind?.let { "adj_${it.name}" == id } ?: false else false
        }
    }

    /** opens [content] next to [anchor]: to its side when the anchor sits on a screen edge, below it otherwise */
    private fun showBeside(anchor: View?, content: View, widthDp: Int, heightDp: Int?) {
        val a = anchor ?: actionsBtn
        popup?.dismiss()
        val loc = IntArray(2); a.getLocationInWindow(loc)
        val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
        val ax = loc[0] - rootLoc[0]; val ay = loc[1] - rootLoc[1]
        val w = min(widthDp.dp, root.width - 24.dp)
        val maxH = root.height - 80.dp
        val h = if (heightDp == null) ViewGroup.LayoutParams.WRAP_CONTENT else min(heightDp.dp, maxH)
        val sideAnchor = ay > 60.dp // not in the top bar
        val x: Int
        val y: Int
        if (sideAnchor) {
            x = if (ax > root.width / 2) ax - w - 10.dp else ax + a.width + 10.dp
            val hh = if (h > 0) h else min(560.dp, maxH)
            y = (ay + a.height / 2 - hh / 2).coerceIn(64.dp, (root.height - hh - 12.dp).coerceAtLeast(64.dp))
        } else {
            x = ax.coerceIn(8.dp, (root.width - w - 8.dp).coerceAtLeast(8.dp))
            y = ay + a.height + 6.dp
        }
        val pw = PopupWindow(content, w, h, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        pw.elevation = 16f.dp
        pw.setOnDismissListener { refreshAll() }
        pw.animationStyle = android.R.style.Animation_Dialog
        popup = pw
        pw.showAtLocation(root, Gravity.TOP or Gravity.LEFT, x + rootLoc[0], y + rootLoc[1])
    }

    // =========================================================
    // S Pen button
    // =========================================================

    private var penX = 0f
    private var penY = 0f

    private val penListener = object : CanvasView.PenListener {
        override fun onPenClick() {
            if (quickMenu.isOpen) { quickMenu.close(); return }
            if (gallery != null) return
            gestures.purposeOf(GestureTrigger.PEN_CLICK)?.let { runGesture(it, penX, penY, fromPenButton = true) }
        }

        override fun onPenDoubleClick(x: Float, y: Float) {
            if (gallery != null || cv.interaction == Interaction.LOCKED || cv.interaction == Interaction.TRANSFORM) return
            if (quickMenu.isOpen) { quickMenu.close(); return }
            popup?.dismiss()
            gestures.purposeOf(GestureTrigger.PEN_DOUBLE)?.let { runGesture(it, x, y, fromPenButton = true) }
        }

        override fun onPenHold(x: Float, y: Float) {
            if (gallery != null || cv.interaction == Interaction.LOCKED || cv.interaction == Interaction.TRANSFORM) return
            popup?.dismiss()
            gestures.purposeOf(GestureTrigger.PEN_HOLD)?.let { runGesture(it, x, y, fromPenButton = true) }
        }

        override fun onPenHover(x: Float, y: Float) {
            penX = x; penY = y
            quickMenu.hoverAt(x, y)
        }

        override fun onPenRelease(x: Float, y: Float) = quickMenu.releaseAt(x, y)
    }

    /** Does what a gesture (or the S Pen button, or □) is set to do. */
    private fun runGesture(p: GesturePurpose, x: Float, y: Float, fromPenButton: Boolean = false) {
        if (!cv.isDocReady() || gallery != null) return
        when (p) {
            GesturePurpose.QUICKMENU -> {
                if (quickMenu.isOpen) { quickMenu.close(); return }
                popup?.dismiss()
                openQuickMenu(x, y)
            }
            GesturePurpose.ERASE -> {
                if (cv.interaction != Interaction.PAINT) return
                selectTool(if (cv.tool == Tool.ERASER) Tool.BRUSH else Tool.ERASER)
                hud.show(if (cv.tool == Tool.ERASER) "Eraser · ${cv.eraserBrush.preset.name}" else "Brush · ${cv.paintBrush.preset.name}")
            }
            GesturePurpose.SMUDGE -> {
                if (cv.interaction != Interaction.PAINT) return
                selectTool(if (cv.tool == Tool.SMUDGE) Tool.BRUSH else Tool.SMUDGE)
            }
            GesturePurpose.FULL_SCREEN -> setFocus(!focusMode)
            GesturePurpose.CLEAR_LAYER -> { if (!busy()) cv.clearActiveLayer() }
            GesturePurpose.COPY_PASTE -> showCopyPasteMenu(x, y)
            GesturePurpose.ASSISTED -> runTool("assisted")
            GesturePurpose.EYEDROPPER -> cv.armEyedropper()
            GesturePurpose.LAYER_SELECT -> cv.selectLayerAt(x, y)
            GesturePurpose.QUICKSHAPE -> cv.snapLastStroke()
            GesturePurpose.HOVER, GesturePurpose.GENERAL -> {}
        }
        refreshAll()
    }

    /** The modify button shows the eyedropper while that is what tapping it does. */
    private fun syncModifyIcon() {
        if (!::modifyBtn.isInitialized) return
        val tap = gestures.purposeOf(GestureTrigger.TAP_MODIFY)
        modifyBtn.icon = if (tap == GesturePurpose.EYEDROPPER) Icon.EYEDROPPER else Icon.MODIFY
        modifyBtn.invalidate()
    }

    /** Cut, copy, paste and duplicate, as a small menu where the gesture happened. */
    private fun showCopyPasteMenu(x: Float, y: Float) {
        if (busy()) return
        popup?.dismiss()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(0xF2202026.toInt(), 22f, 0x22FFFFFF, 1)
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            elevation = 12f.dp
        }
        val pw = PopupWindow(box, -2, -2, true)
        fun item(label: String, enabled: Boolean = true, run: () -> Unit) {
            box.addView(Ui.button(this, label) { pw.dismiss(); run(); refreshAll() }.apply {
                background = null
                textSize = 14.5f
                setPadding(16.dp, 10.dp, 16.dp, 10.dp)
                alpha = if (enabled) 1f else 0.4f
                isEnabled = enabled
            })
        }
        item("Cut") { cv.copyToClipboard(cut = true) }
        item("Copy") { cv.copyToClipboard(cut = false) }
        item("Copy All") { val s = cv.selection; cv.selection = null; cv.copyToClipboard(cut = false); cv.selection = s }
        item("Duplicate") { cv.duplicateActiveLayer() }
        item("Paste", cv.hasClipboard) { cv.pasteClipboard() }
        pw.isOutsideTouchable = true
        box.measure(0, 0)
        val px = (x - box.measuredWidth / 2f).toInt().coerceIn(8.dp, (root.width - box.measuredWidth - 8.dp).coerceAtLeast(8.dp))
        val py = (y - box.measuredHeight - 24.dp).toInt().coerceAtLeast(64.dp)
        pw.showAtLocation(root, Gravity.TOP or Gravity.LEFT, px, py)
        popup = pw
    }

    /** remembers the tool + brush in use, for "last 3 used" in the quick menu */
    private fun noteRecent() {
        val entry = cv.tool to cv.currentBrush
        recentTools.removeAll { it.first == entry.first && it.second === entry.second }
        recentTools.add(0, entry)
        while (recentTools.size > 4) recentTools.removeAt(recentTools.size - 1)
    }

    private fun useBrush(tool: Tool, b: Brush) {
        when (tool) {
            Tool.ERASER -> cv.eraserBrush = b
            Tool.SMUDGE -> cv.smudgeBrush = b
            Tool.BRUSH -> cv.paintBrush = b
        }
        selectTool(tool)
        hud.show(toolPrefix(tool) + b.preset.name)
    }

    private fun toolPrefix(t: Tool) = when (t) {
        Tool.ERASER -> "Eraser · "
        Tool.SMUDGE -> "Smudge · "
        Tool.BRUSH -> ""
    }

    private fun openQuickMenu(x: Float, y: Float) {
        if (!cv.isDocReady()) return
        val vec = cv.interaction == Interaction.VECTOR
        val painting = cv.interaction == Interaction.PAINT && !cv.fillMode
        val tools = listOf(
            QuickMenuView.Item("Brush", Icon.BRUSH, active = painting && cv.tool == Tool.BRUSH) { selectTool(Tool.BRUSH) },
            QuickMenuView.Item("Smudge", Icon.SMUDGE, active = painting && cv.tool == Tool.SMUDGE) { selectTool(Tool.SMUDGE) },
            QuickMenuView.Item("Eraser", Icon.ERASER, active = painting && cv.tool == Tool.ERASER) { selectTool(Tool.ERASER) },
            QuickMenuView.Item("Eyedropper", Icon.EYEDROPPER) { cv.armEyedropper() },
            QuickMenuView.Item("Undo", Icon.UNDO) { cv.undo() },
            QuickMenuView.Item("Redo", Icon.REDO) { cv.redo() },
            QuickMenuView.Item("Layers", Icon.LAYERS) { showLayers() }
        )
        // the 3 most recent brushes that are not the one in hand right now
        val current = cv.tool to cv.currentBrush
        val recent = recentTools.filter { !(it.first == current.first && it.second === current.second) }.take(3)
        val brushes = recent.map { (tool, b) ->
            QuickMenuView.Item(toolPrefix(tool) + b.preset.name, preview = BrushPanel.preview(b)) { useBrush(tool, b) }
        }
        // your saved palette, then recent colors
        val swatches = (palette() + recentColors).distinct().ifEmpty { ColorPanel.PALETTE.take(8).toList() }

        val startColor = cv.color
        val recolor = vec && cv.vectorEditor.selected.isNotEmpty()
        if (recolor) cv.vectorEditor.beginStyle()
        quickMenu.onColor = { c ->
            cv.color = c
            colorBtn.color = c
            if (recolor) cv.vectorEditor.recolorLive(c)
            else if (!vec && cv.tool == Tool.ERASER) selectTool(Tool.BRUSH)
        }
        quickMenu.onSaveColor = { c -> savePaletteColor(c) }
        quickMenu.sizeLabel = { v ->
            val px = Brushes.sizeFromSlider(v)
            if (px < 10) String.format("%.1f", px) else px.roundToInt().toString()
        }
        quickMenu.opacityLabel = { v -> "${(v.coerceIn(0.01f, 1f) * 100).roundToInt()}%" }
        quickMenu.onSize = { v -> cv.currentBrush.size = Brushes.sizeFromSlider(v); sizeSlider.value = v }
        quickMenu.onOpacity = { v -> cv.currentBrush.opacity = v.coerceIn(0.01f, 1f); opacitySlider.value = v }
        quickMenu.onClosed = {
            if (recolor) cv.vectorEditor.endStyle()
            if (cv.color != startColor) ColorPanel.addRecent(recentColors, cv.color)
            refreshAll()
        }
        quickMenu.open(x, y, cv.color, tools, brushes, swatches,
            Brushes.sliderFromSize(cv.currentBrush.size), cv.currentBrush.opacity)
    }

    /** The big spinning swatch wheel (from the color panel). */
    private fun openColorWheel(x: Float, y: Float) {
        val start = cv.color
        val recolor = cv.interaction == Interaction.VECTOR && cv.vectorEditor.selected.isNotEmpty()
        if (recolor) cv.vectorEditor.beginStyle()
        colorWheel.onColor = { c ->
            cv.color = c
            colorBtn.color = c
            if (recolor) cv.vectorEditor.recolorLive(c)
            else if (cv.tool == Tool.ERASER) selectTool(Tool.BRUSH)
        }
        colorWheel.onSave = { c -> savePaletteColor(c) }
        colorWheel.onHsl = { px, py, _ -> openQuickMenu(px, py) }
        colorWheel.onClosed = {
            if (recolor) cv.vectorEditor.endStyle()
            if (cv.color != start) ColorPanel.addRecent(recentColors, cv.color)
        }
        colorWheel.open(x, y, cv.color)
    }

    /** the active palette's colors (the quick menu shows the first ones) */
    private fun palette(): List<Int> = PaletteStore.current.colors.take(11)

    private fun savePaletteColor(c: Int) {
        if (PaletteStore.addColor(c)) hud.show("Color saved: " + PaletteStore.current.name)
        else hud.show("This palette is full")
    }

    private var colorPanel: ColorPanel? = null

    private fun showSizeHud(sticky: Boolean) {
        val px = cv.currentBrush.size
        hud.show("Size ${if (px < 10) String.format("%.1f", px) else px.roundToInt().toString()} px",
            circleDiameterPx = min(px * cv.zoom(), 600f.dp), sticky = sticky)
    }

    private fun selectTool(t: Tool) {
        if (busy()) return
        if (cv.isDocReady() && cv.doc.active.vector != null) {
            hud.show("This is a vector layer — pick a drawing layer to paint")
            return
        }
        // picking a painting tool leaves selection mode (the selection itself stays and limits painting)
        if (cv.interaction == Interaction.SELECT) {
            cv.interaction = Interaction.PAINT
            hideBottomBar()
        }
        cv.tool = t
        cv.fillMode = false
        noteRecent()
        refreshAll()
    }

    /** true (and a message) while a transform or adjustment is open */
    private fun busy(): Boolean {
        if (cv.isDocReady() && (cv.transform != null || adjustKind != null || cv.liquify != null)) {
            hud.show("Tap Done or Cancel first")
            return true
        }
        return false
    }

    private fun refreshAll() {
        val painting = cv.interaction == Interaction.PAINT
        // a floating color window follows the color in hand
        ((pinned["color"]?.getChildAt(1) as? ScrollView)?.getChildAt(0) as? ColorPanel)?.let { cp ->
            if (cv.isDocReady()) cp.setColor(cv.color, false)
        }
        brushBtn.active = painting && cv.tool == Tool.BRUSH
        smudgeBtn.active = painting && cv.tool == Tool.SMUDGE
        eraserBtn.active = painting && cv.tool == Tool.ERASER
        selectBtn.active = cv.interaction == Interaction.SELECT || (cv.isDocReady() && cv.selection != null)
        transformBtn.active = cv.interaction == Interaction.TRANSFORM
        adjustBtn.active = adjustKind != null
        syncVectorMode()
        symmetryBtn.active = cv.symmetry != Symmetry.OFF
        guideBtn.active = cv.isDocReady() && cv.doc.guide.type != GuideType.OFF
        colorBtn.color = cv.color
        sizeSlider.value = Brushes.sliderFromSize(cv.currentBrush.size)
        opacitySlider.value = cv.currentBrush.opacity
        if (cv.fillMode) { brushBtn.active = false; smudgeBtn.active = false; eraserBtn.active = false }
        if (::dock.isInitialized) dock.refresh()
        if (::titleView.isInitialized && cv.isDocReady()) titleView.text = cv.doc.title
        if (::brushDot.isInitialized) {
            brushDot.sizePx = cv.currentBrush.size
            brushDot.opacity = cv.currentBrush.opacity
            brushDot.color = if (cv.tool == Tool.ERASER) 0xFFFFFFFF.toInt() else cv.color
            opacityLabel.text = "${(cv.currentBrush.opacity * 100).roundToInt()}%"
        }
        onHistoryChanged()
    }

    // ---------- ColorDrop: drag the color button onto the canvas ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupColorButton() {
        var downX = 0f
        var downY = 0f
        var dragging = false
        colorBtn.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > 24f.dp) {
                        dragging = true
                        dropDot.color = cv.color
                        dropDot.visibility = View.VISIBLE
                    }
                    if (dragging) moveDropDot(e.rawX, e.rawY)
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        dropDot.visibility = View.GONE
                        val loc = IntArray(2)
                        cv.getLocationOnScreen(loc)
                        cv.colorDrop(e.rawX - loc[0], e.rawY - loc[1], cv.color)
                    } else showColors()
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    dropDot.visibility = View.GONE
                    dragging = false
                }
            }
            true
        }
    }

    private fun moveDropDot(rawX: Float, rawY: Float) {
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val lp = dropDot.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = (rawX - loc[0] - 22.dp).toInt()
        lp.topMargin = (rawY - loc[1] - 22.dp).toInt()
        dropDot.layoutParams = lp
    }

    // ---------- popups ----------

    private fun showPanel(anchor: View, content: View, widthDp: Int, heightDp: Int?, alignLeft: Boolean = false) {
        popup?.dismiss()
        val maxH = root.height - 80.dp
        val h = if (heightDp == null) ViewGroup.LayoutParams.WRAP_CONTENT else min(heightDp.dp, maxH)
        val pw = PopupWindow(content, widthDp.dp, h, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        pw.elevation = 16f.dp
        pw.setOnDismissListener { refreshAll() }
        pw.animationStyle = android.R.style.Animation_Dialog
        popup = pw
        // absolute position, so it is right in both left-to-right and right-to-left layouts
        val loc = IntArray(2); anchor.getLocationInWindow(loc)
        val w = widthDp.dp
        val alignStart = alignLeft != L10n.isRtl
        val x = (if (alignStart) loc[0] else loc[0] + anchor.width - w).coerceIn(8.dp, (root.width - w - 8.dp).coerceAtLeast(8.dp))
        pw.showAtLocation(root, Gravity.TOP or Gravity.LEFT, x, loc[1] + anchor.height + 6.dp)
    }

    private fun brushListFor(t: Tool) = when (t) {
        Tool.ERASER -> eraserBrushes
        Tool.SMUDGE -> smudgeBrushes
        Tool.BRUSH -> paintBrushes
    }

    private fun showBrushes(tool: Tool) {
        val anchor = when (tool) {
            Tool.ERASER -> eraserBtn
            Tool.SMUDGE -> smudgeBtn
            Tool.BRUSH -> brushBtn
        }
        showPanel(anchor, brushPanelFor(tool, pinned = false), 540, 700)
    }

    private fun brushPanelFor(tool: Tool, pinned: Boolean): BrushPanel {
        val list = brushListFor(tool)
        val current = when (tool) {
            Tool.ERASER -> cv.eraserBrush
            Tool.SMUDGE -> cv.smudgeBrush
            Tool.BRUSH -> cv.paintBrush
        }
        val title = when (tool) {
            Tool.ERASER -> "Erase with…"
            Tool.SMUDGE -> "Smudge with…"
            Tool.BRUSH -> "Brush Library"
        }
        return BrushPanel(this, title, list, current,
            onSelect = { b ->
                when (tool) {
                    Tool.ERASER -> cv.eraserBrush = b
                    Tool.SMUDGE -> cv.smudgeBrush = b
                    Tool.BRUSH -> cv.paintBrush = b
                }
                noteRecent()
                refreshAll()
            },
            onImportFile = {
                popup?.dismiss()
                pickBrushFiles()
            },
            onImportLink = {
                popup?.dismiss()
                importLinkDialog()
            },
            onDelete = { b -> confirmDeleteBrush(b, tool) },
            onPin = if (pinned) null else ({ popup?.dismiss(); pinPanel("brushes") }),
            onDeleteSet = { set, list -> confirmDeleteSet(set, list) },
            onEdit = { b -> popup?.dismiss(); openStudio(tool, b) },
            onNewBrush = { popup?.dismiss(); duplicateBrush(tool, current) },
            onExportSet = { set, list -> popup?.dismiss(); exportBrushSet(set, list) })
    }

    // ---------- pinned panels ----------

    private val pinned = HashMap<String, FloatingPanel>()
    private var pinnedBrushTool: Tool? = null

    private fun toggleReference() {
        if (pinned.containsKey("reference")) unpin("reference") else pinPanel("reference")
    }

    private fun toggleNavigator() {
        if (pinned.containsKey("navigator")) unpin("navigator") else pinPanel("navigator")
    }

    private fun toggleLighting() {
        if (pinned.containsKey("lighting")) unpin("lighting") else pinPanel("lighting")
    }

    private fun pinPanel(kind: String, quiet: Boolean = false) {
        if (!cv.isDocReady()) return
        unpinView(kind)
        val content: View = when (kind) {
            "layers" -> LayersPanel(this, cv, { cv.color }, onAddVector = { addVectorLayer() }, onPin = null, onEdit3D = { open3D(it) }) { onHistoryChanged() }
            "reference" -> ReferenceWindow(this, onImport = {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                }, REQ_REF_IMAGE)
            }, onColorPicked = { c ->
                cv.color = c or 0xFF000000.toInt()
                colorBtn.color = cv.color
                ColorPanel.addRecent(recentColors, cv.color)
                hud.show("Color picked from reference")
            })
            "navigator" -> NavigatorWindow(this, cv)
            "lighting" -> LightingOrbView(this)
            "color" -> ScrollView(this).apply {
                addView(ColorPanel(this@MainActivity, cv.color, recentColors, onWheel = null,
                    artwork = { runCatching { Gallery.makeThumb(cv.doc) }.getOrNull() }) { c ->
                    cv.color = c
                    colorBtn.color = c
                    if (cv.tool == Tool.ERASER) selectTool(Tool.BRUSH)
                }.also { colorPanel = it })
            }
            else -> { pinnedBrushTool = cv.tool; brushPanelFor(cv.tool, pinned = true) }
        }
        val title = when (kind) {
            "layers" -> "Layers"
            "reference" -> "Reference"
            "navigator" -> "Navigator"
            "lighting" -> "Lighting Orb"
            "color" -> "Color"
            else -> "Brushes"
        }
        val fp = FloatingPanel(this, title, content,
            onClose = { unpin(kind) },
            onMoved = { x, y -> prefs.edit().putInt("pin_${kind}_x", x).putInt("pin_${kind}_y", y).apply() })
        val w = when (kind) {
            "reference", "navigator", "lighting" -> 240.dp
            "brushes" -> min(470.dp, root.width - 16.dp)
            "color" -> 340.dp
            else -> 320.dp
        }
        val h = if (kind == "reference" || kind == "navigator" || kind == "lighting") FrameLayout.LayoutParams.WRAP_CONTENT else min(560.dp, root.height - 120.dp).coerceAtLeast(300.dp)
        val defX = if ((kind == "layers") != leftHanded) (root.width - w - 72.dp).coerceAtLeast(0) else 72.dp
        val lp = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = prefs.getInt("pin_${kind}_x", defX).coerceIn(0, (root.width - w).coerceAtLeast(0))
            topMargin = prefs.getInt("pin_${kind}_y", 68.dp).coerceIn(56.dp, (root.height - 120.dp).coerceAtLeast(56.dp))
        }
        // below the quick menu, the bars and the home screen
        root.addView(fp, root.indexOfChild(hud) + 1, lp)
        pinned[kind] = fp
        prefs.edit().putBoolean("pinned_$kind", true).apply()
        if (!quiet) hud.show("Pinned · drag it by its top strip")
        if (gallery != null) fp.visibility = View.GONE
    }

    /** the grab strip of the color popover: pull it to keep the colors open as a floating window */
    @SuppressLint("ClickableViewAccessibility")
    private fun colorGrabber(): View {
        val v = object : View(this) {
            private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt() }
            override fun onDraw(c: Canvas) {
                val w = 44.dp.toFloat(); val h = 5.dp.toFloat()
                c.drawRoundRect(width / 2f - w / 2, height / 2f - h / 2, width / 2f + w / 2, height / 2f + h / 2, h / 2, h / 2, p)
            }
        }
        v.background = Ui.rounded(Ui.PANEL, 18f)
        v.contentDescription = "Drag to make the color panel a floating window"
        var x0 = 0f; var y0 = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY }
                MotionEvent.ACTION_MOVE -> if (hypot(e.rawX - x0, e.rawY - y0) > 28.dp) {
                    // pulled away: becomes a floating window where the finger is
                    popup?.dismiss()
                    val loc = IntArray(2); root.getLocationOnScreen(loc)
                    val px = (e.rawX - loc[0] - 170.dp).toInt().coerceIn(0, (root.width - 340.dp).coerceAtLeast(0))
                    val py = (e.rawY - loc[1] - 14.dp).toInt().coerceIn(56.dp, (root.height - 200.dp).coerceAtLeast(56.dp))
                    prefs.edit().putInt("pin_color_x", px).putInt("pin_color_y", py).apply()
                    pinPanel("color")
                    return@setOnTouchListener false
                }
                MotionEvent.ACTION_UP -> if (hypot(e.rawX - x0, e.rawY - y0) < 10.dp) {
                    popup?.dismiss(); pinPanel("color")
                }
            }
            true
        }
        return v
    }

    private fun unpinView(kind: String) {
        pinned.remove(kind)?.let { root.removeView(it) }
    }

    private fun unpin(kind: String) {
        unpinView(kind)
        prefs.edit().putBoolean("pinned_$kind", false).apply()
    }

    private fun restorePins() {
        root.post {
            for (k in listOf("layers", "brushes", "navigator", "reference", "lighting", "color"))
                if (prefs.getBoolean("pinned_$k", false)) pinPanel(k, quiet = true)
        }
    }

    private val refreshPinnedLayers = Runnable {
        (pinned["layers"]?.getChildAt(1) as? LayersPanel)?.rebuild()
    }

    /** keeps pinned panels in sync with what changed */
    private fun refreshPins() {
        if (pinned.isEmpty()) return
        main.removeCallbacks(refreshPinnedLayers)
        main.postDelayed(refreshPinnedLayers, 350)
        if (pinned.containsKey("brushes") && pinnedBrushTool != cv.tool && cv.interaction == Interaction.PAINT) pinPanel("brushes")
    }

    override fun onDrawingChanged(drawing: Boolean) {
        drawingNow = drawing
        if (!drawing) lastDrawEnd = android.os.SystemClock.uptimeMillis()
        pinned.values.forEach { it.setDrawing(drawing) }
    }

    // ---------- brush import ----------

    private fun importLinkDialog() {
        val input = EditText(this).apply {
            hint = "https://… (.brush, .brushset, .zip or .png)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val box = LinearLayout(this).apply { setPadding(24.dp, 8.dp, 24.dp, 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Import brushes from a link"))
            .setMessage(L10n.tr("Paste a direct download link. Google Drive and Dropbox share links work too."))
            .setView(box)
            .setPositiveButton(L10n.tr("Download")) { _, _ ->
                val link = input.text.toString().trim()
                if (link.startsWith("http")) downloadBrushes(link) else toast("That doesn't look like a link")
            }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    private fun downloadBrushes(link: String) {
        hud.show("Downloading…", sticky = true)
        thread(name = "inkora-brush-download") {
            val result = runCatching {
                val (bytes, name) = BrushImport.download(link)
                BrushImport.importBytes(applicationContext, bytes, name)
            }
            main.post {
                hud.hideSoon()
                result.onSuccess { addImported(it) }
                    .onFailure { toast("Download failed: ${it.message ?: "unknown error"}") }
            }
        }
    }

    private fun importBrushFiles(uris: List<Uri>) {
        hud.show("Importing brushes…", sticky = true)
        thread(name = "inkora-brush-import") {
            val all = ArrayList<BrushPreset>()
            val messages = ArrayList<String>()
            for (uri in uris) {
                runCatching {
                    val name = displayName(uri)
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                    val r = BrushImport.importBytes(applicationContext, bytes, name)
                    all.addAll(r.presets)
                    if (r.presets.isEmpty()) messages.add(r.message)
                }.onFailure { messages.add(it.message ?: "Could not read a file") }
            }
            main.post {
                hud.hideSoon()
                val msg = if (all.isNotEmpty()) (if (all.size == 1) "Imported \"${all[0].name}\"" else "Imported ${all.size} brushes")
                else messages.firstOrNull() ?: "No brushes found"
                addImported(BrushImport.Result(all, msg))
            }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun addImported(r: BrushImport.Result) {
        for (p in r.presets) {
            paintBrushes.add(Brush(p))
            eraserBrushes.add(Brush(p).apply { size = 40f })
            smudgeBrushes.add(Brush(p).apply { size = 50f; opacity = 0.75f })
        }
        if (r.presets.isNotEmpty()) {
            // switch straight to the first new brush
            cv.paintBrush = paintBrushes.first { it.preset === r.presets[0] }
            selectTool(Tool.BRUSH)
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(r.message)
                .setMessage(L10n.tr("You'll find them in the Brush Library, after Inkora's own sets."))
                .setPositiveButton(L10n.tr("Open library")) { _, _ -> showBrushes(Tool.BRUSH) }
                .setNegativeButton(L10n.tr("Draw"), null)
                .show()
        } else toast(r.message)
    }

    // =========================================================
    // Brush Studio
    // =========================================================

    private var studioTool = Tool.BRUSH

    private fun openStudio(tool: Tool, b: Brush) {
        if (busy()) return
        if (b.preset.living != null) {
            // a living brush is tuned as a whole; its size and opacity are on the side bar
            Ui.toast(this, "Living brushes: change size and opacity on the side bar")
            return
        }
        studioTool = tool
        showOverlay(BrushStudioView(this, b, studioHost))
    }

    private val studioHost = object : BrushStudioHost {
        override val padColor get() = cv.color

        override fun onStudioClosed(brush: Brush, changed: Boolean) {
            closeSettings()
            if (changed) BrushSettings.save(prefs, studioTool, brush)
            refreshAll()
        }

        override fun duplicate(brush: Brush) = duplicateBrush(studioTool, brush)

        override fun export(brush: Brush) {
            pendingExport = listOf(brush) to null
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, BrushExport.fileName(brush.preset.name, ".brush"))
            }, REQ_BRUSH_EXPORT)
        }

        override fun rename(brush: Brush, name: String) {
            BrushImport.rename(this@MainActivity, brush.preset, name)
            val fresh = BrushImport.reload(this@MainActivity, brush.preset) ?: return
            val nb = replacePreset(brush.preset, fresh, studioTool)
            closeSettings()
            if (nb != null) openStudio(studioTool, nb)
            hud.show("Renamed")
        }

        override fun pickImage(brush: Brush, file: String) {
            pendingImage = Triple(studioTool, brush, file)
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            }, REQ_BRUSH_IMAGE)
        }
    }

    /** a copy of [b] as a brush of the user's own, in "My Brushes", then its studio */
    private fun duplicateBrush(tool: Tool, b: Brush) {
        val p = runCatching { BrushImport.saveCustom(this, b, b.preset.name + " " + L10n.tr("copy"), "My Brushes") }
            .getOrElse { toast("Could not copy the brush"); return }
        val made = HashMap<Tool, Brush>()
        for (t in Tool.entries) {
            val nb = Brush(p)
            if (t == tool) { nb.size = b.size; nb.opacity = b.opacity }
            else if (t == Tool.ERASER) nb.size = 40f
            else if (t == Tool.SMUDGE) { nb.size = 50f; nb.opacity = 0.75f }
            brushListFor(t).add(nb)
            made[t] = nb
        }
        val nb = made[tool]!!
        useBrush(tool, nb)
        hud.show("Duplicated · " + p.name)
        openStudio(tool, nb)
    }

    /** a brush's preset was made again (renamed, new image): every tool's copy follows it */
    private fun replacePreset(old: BrushPreset, fresh: BrushPreset, keepTool: Tool): Brush? {
        var result: Brush? = null
        BrushPanel.forget(old.id)
        for (t in Tool.entries) {
            val list = brushListFor(t)
            val i = list.indexOfFirst { it.preset === old }
            if (i < 0) continue
            val ob = list[i]
            val nb = Brush(fresh)
            BrushSettings.apply(nb, BrushSettings.toJson(ob))
            nb.size = ob.size; nb.opacity = ob.opacity
            if (fresh.grain != GrainType.NONE && old.grain == GrainType.NONE) nb.grainOn = true
            list[i] = nb
            when (t) {
                Tool.BRUSH -> if (cv.paintBrush === ob) cv.paintBrush = nb
                Tool.ERASER -> if (cv.eraserBrush === ob) cv.eraserBrush = nb
                Tool.SMUDGE -> if (cv.smudgeBrush === ob) cv.smudgeBrush = nb
            }
            for (k in recentTools.indices) if (recentTools[k].second === ob) recentTools[k] = recentTools[k].first to nb
            if (t == keepTool) result = nb
        }
        refreshAll()
        return result
    }

    private var pendingExport: Pair<List<Brush>, String?>? = null
    private var pendingImage: Triple<Tool, Brush, String>? = null

    private fun exportBrushSet(name: String, list: List<Brush>) {
        if (list.isEmpty()) return
        pendingExport = list to name
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, BrushExport.fileName(L10n.tr(name), ".brushset"))
        }, REQ_BRUSH_EXPORT)
    }

    private fun writeBrushFile(uri: Uri) {
        val (list, setName) = pendingExport ?: return
        pendingExport = null
        hud.show("Exporting…", sticky = true)
        thread(name = "inkora-brush-export") {
            val ok = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    if (setName == null) BrushExport.writeBrush(list[0], out) else BrushExport.writeSet(L10n.tr(setName), list, out)
                } != null
            }.getOrDefault(false)
            main.post {
                hud.hideSoon()
                toast(if (ok) (if (setName == null) "Brush exported" else "Brush set exported") else "Could not export")
            }
        }
    }

    /** a new shape, grain or dual image for a brush of the user's own */
    private fun setBrushImage(uri: Uri) {
        val (tool, b, file) = pendingImage ?: return
        pendingImage = null
        val bmp = runCatching { ProjectIO.decodeImage(this, uri, 1024, 1024) }.getOrNull()
        if (bmp == null) { toast("Could not open that image"); return }
        val alpha = if (file == "grain.png") BrushImport.grainFromImage(bmp) else BrushImport.tipFromImage(bmp)
        bmp.recycle()
        BrushImport.replaceImage(this, b.preset, file, alpha)
        val fresh = BrushImport.reload(this, b.preset) ?: return
        val nb = replacePreset(b.preset, fresh, tool)
        closeSettings()
        if (nb != null) openStudio(tool, nb)
    }

    private fun confirmDeleteSet(set: String, list: List<Brush>) {
        val presets = list.map { it.preset }.filter { it.imported }.toSet()
        if (presets.isEmpty()) return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Delete this set?") + "  \u2068$set\u2069")
            .setMessage(L10n.tr("This removes all of its brushes from Inkora.") + " (${presets.size})")
            .setPositiveButton(L10n.tr("Delete")) { _, _ ->
                for (p in presets) { BrushImport.delete(this, p); BrushPanel.forget(p.id) }
                paintBrushes.removeAll { it.preset in presets }
                eraserBrushes.removeAll { it.preset in presets }
                smudgeBrushes.removeAll { it.preset in presets }
                recentTools.removeAll { it.second.preset in presets }
                if (cv.paintBrush.preset in presets) cv.paintBrush = paintBrushes.first()
                if (cv.eraserBrush.preset in presets) cv.eraserBrush = eraserBrushes.first()
                if (cv.smudgeBrush.preset in presets) cv.smudgeBrush = smudgeBrushes.first()
                popup?.dismiss()
                refreshAll()
                hud.show("Set deleted")
            }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    private fun confirmDeleteBrush(b: Brush, tool: Tool) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Delete \"${b.preset.name}\"?"))
            .setMessage(L10n.tr("This removes the imported brush from Inkora."))
            .setPositiveButton(L10n.tr("Delete")) { _, _ ->
                val p = b.preset
                BrushImport.delete(this, p)
                BrushPanel.forget(p.id)
                paintBrushes.removeAll { it.preset === p }
                eraserBrushes.removeAll { it.preset === p }
                smudgeBrushes.removeAll { it.preset === p }
                recentTools.removeAll { it.second.preset === p }
                if (cv.paintBrush.preset === p) cv.paintBrush = paintBrushes.first()
                if (cv.eraserBrush.preset === p) cv.eraserBrush = eraserBrushes.first()
                if (cv.smudgeBrush.preset === p) cv.smudgeBrush = smudgeBrushes.first()
                popup?.dismiss()
                refreshAll()
                showBrushes(tool)
            }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    private fun showLayers() {
        if (busy()) return
        val panel = LayersPanel(this, cv, { cv.color }, onAddVector = { popup?.dismiss(); addVectorLayer() }, onPin = { popup?.dismiss(); pinPanel("layers") },
            onEdit3D = { popup?.dismiss(); open3D(it) }) { onHistoryChanged() }
        showPanel(layersBtn, panel, 360, 600)
    }

    private fun showColors() {
        // with vector shapes selected, the color goes straight onto them
        val recolor = cv.interaction == Interaction.VECTOR && cv.vectorEditor.selected.isNotEmpty()
        if (recolor) cv.vectorEditor.beginStyle()
        val panel = ColorPanel(this, cv.color, recentColors, onWheel = {
            popup?.dismiss()
            openColorWheel(root.width * 0.62f, root.height * 0.5f)
        }, artwork = { runCatching { Gallery.makeThumb(cv.doc) }.getOrNull() },
            onPalettePhoto = {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                }, REQ_PALETTE_PHOTO)
            }) { c ->
            cv.color = c
            colorBtn.color = c
            if (recolor) cv.vectorEditor.recolorLive(c)
        }
        colorPanel = panel
        // a grab strip on top: drag it and the color panel becomes a floating window
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(colorGrabber(), LinearLayout.LayoutParams(-1, 26.dp))
        holder.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(-1, 0, 1f))
        showPanel(colorBtn, holder, 340, 846)
        if (recolor) popup?.setOnDismissListener { cv.vectorEditor.endStyle(); refreshAll() }
    }

    private fun showGuide(anchor: View? = null) {
        val g = cv.doc.guide
        val panel = GuidePanel(this, g,
            onChange = {
                cv.doc.revision++
                cv.invalidate()
                guideBtn.active = g.type != GuideType.OFF
                if (::dock.isInitialized) dock.refresh()
            },
            onEditPoints = {
                popup?.dismiss()
                cv.editingGuide = true
                doneBtn.visibility = View.VISIBLE
                hud.show("Drag the numbered points")
            })
        lastGuideAnchor = anchor
        showBeside(anchor, panel, 360, 620)
    }

    private var lastGuideAnchor: View? = null

    private fun finishGuideEdit() {
        cv.editingGuide = false
        doneBtn.visibility = View.GONE
        showGuide(lastGuideAnchor)
    }

    /** symmetry modes as little pictures */
    private fun showSymmetry(anchor: View?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            background = Ui.rounded(Ui.PANEL, 18f, 0x14FFFFFF, 1)
        }
        box.addView(Ui.text(this, "Symmetry", 15f, bold = true).apply { setPadding(4.dp, 0, 0, 8.dp) })
        val grid = android.widget.GridLayout(this).apply { columnCount = 3 }
        for (sym in Symmetry.values()) {
            val on = cv.symmetry == sym
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = Ui.rounded(if (on) Ui.PANEL_ROW_ACTIVE else Ui.CARD, 12f, if (on) Ui.ACCENT else 0, if (on) 1 else 0)
                setPadding(4.dp, 4.dp, 4.dp, 6.dp)
                setOnClickListener { cv.symmetry = sym; popup?.dismiss(); refreshAll(); hud.show("Symmetry: ${sym.label}") }
            }
            cell.addView(SymmetryPreview(this, sym, on), LinearLayout.LayoutParams(-1, 64.dp))
            cell.addView(Ui.text(this, sym.label, 12f, if (on) Ui.ACCENT else Ui.TEXT))
            grid.addView(cell, android.widget.GridLayout.LayoutParams().apply { width = 96.dp; setMargins(3.dp, 3.dp, 3.dp, 3.dp) })
        }
        box.addView(grid)
        showBeside(anchor, box, 340, null)
    }

    private fun showActions(anchor: View) {
        if (!cv.isDocReady()) return
        val panel = ActionsPanel(this, actionsHost)
        showPanel(anchor, panel, 420, 660, alignLeft = true)
    }

    private val actionsHost = object : ActionsHost {
        override val canvas get() = cv
        override val prefs get() = this@MainActivity.prefs
        override var timeLapseOn: Boolean
            get() = this@MainActivity.timeLapseOn
            set(v) { this@MainActivity.timeLapseOn = v }

        override fun close() { popup?.dismiss() }
        override fun insertPhoto() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
        }, REQ_IMAGE)
        override fun openProjectFile() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_OPEN)
        override fun addText() = textDialog()
        override fun importBrushes() = pickBrushFiles()
        override fun importBrushesFromLink() = importLinkDialog()
        override fun copyPaste(action: String) {
            if (busy()) return
            when (action) {
                "cut" -> cv.copyToClipboard(cut = true)
                "copy" -> cv.copyToClipboard(cut = false)
                "paste" -> cv.pasteClipboard()
                "duplicate" -> cv.duplicateActiveLayer()
            }
            refreshAll()
        }
        override fun newCanvas() = newCanvasDialog()
        override fun renameArtwork() = renameDialog(cv.doc.title) { t ->
            cv.doc.title = t
            cv.doc.revision++
            autosave(force = true)
            refreshAll()
        }
        override fun editGuide() = showGuide(actionsBtn)
        override fun showSymmetry() = showSymmetry(actionsBtn)
        override fun toggleWindow(id: String) = runTool(id)
        override fun isWindowOpen(id: String) = pinned.containsKey(id)
        override fun flipCanvas() = cv.flipHorizontal()
        override fun fitCanvas() = cv.fitToScreen()
        override fun cropResize() = showCanvasSize()
        override fun flipCanvasContent(horizontal: Boolean) {
            if (cv.liquify != null || cv.transform != null || adjustKind != null) { hud.show("Finish or cancel the current edit first"); return }
            val d = cv.doc
            runCanvasOp("Flipped") { CanvasOps.flip(d, horizontal) }
        }
        override fun openLicenses() {
            val text = runCatching { assets.open("licenses.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
            AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L10n.tr("Open-source licenses"))
                .setMessage(text)
                .setPositiveButton(L10n.tr("OK"), null)
                .show()
        }
        override fun canvasInfo() {
            val d = cv.doc
            val mb = d.width.toLong() * d.height * 4 / 1_000_000.0
            val text = listOf(
                L10n.tr("Canvas size") + ": ${d.width} × ${d.height} px",
                String.format(java.util.Locale.US, "%.1f MP", d.width.toLong() * d.height / 1_000_000.0),
                L10n.tr("Layers") + ": ${d.layers.size} / ${d.maxLayers()}",
                String.format(java.util.Locale.US, "%s: %.0f MB", L10n.tr("Memory per layer"), mb),
                L10n.tr("Groups") + ": ${d.groups.size}",
                L10n.tr("Print size at 300 dpi") + String.format(java.util.Locale.US, ": %.1f × %.1f cm", d.width / 300f * 2.54f, d.height / 300f * 2.54f)
            ).joinToString("\n")
            AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(d.title.ifBlank { L10n.tr("Untitled Artwork") })
                .setMessage(text)
                .setPositiveButton(L10n.tr("OK"), null)
                .show()
        }
        override var sidebarShown: Boolean
            get() = prefs.getBoolean("sidebar", true)
            set(v) { prefs.edit().putBoolean("sidebar", v).apply(); sideBar.visibility = if (v && !focusMode) View.VISIBLE else View.GONE }
        override var brushCursor: Boolean
            get() = cv.showBrushCursor
            set(v) { cv.showBrushCursor = v; prefs.edit().putBoolean("brushCursor", v).apply() }
        override fun export(kind: String) {
            when (kind) {
                "share" -> exportImage(jpeg = false, share = true)
                "png" -> exportImage(jpeg = false, share = false)
                "jpeg" -> exportImage(jpeg = true, share = false)
                "svg" -> exportSvg()
                "project" -> saveProjectAs()
                "psd", "pdf", "tiff" -> exportFile(kind)
            }
        }
        override fun exportTimeLapse() = this@MainActivity.exportTimeLapse()
        override fun dockEdge() = this@MainActivity.dockEdge()
        override fun slidersEdge() = this@MainActivity.slidersEdge()
        override fun setEdges(dock: String, sliders: String) {
            prefs.edit().putString("dock_edge", dock).putString("sliders_edge", sliders).apply()
            leftHanded = sliders == "Right"
            prefs.edit().putBoolean("leftHanded", leftHanded).apply()
            applySides()
        }
        override fun setLanguage(code: String) {
            // save the artwork, then start again in the new language
            autosave(force = true)
            L10n.save(this@MainActivity, code)
            afterSaves { recreate() }
        }
        override fun openGestureControls() { popup?.dismiss(); showGestureControls() }
        override fun openShortcuts() { popup?.dismiss(); showShortcuts() }
        override fun showTips() { popup?.dismiss(); this@MainActivity.showTips() }
    }

    private fun pickBrushFiles() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, REQ_BRUSH)
    }

    private fun saveProjectAs() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, (cv.doc.title.ifBlank { "Artwork" }) + ".inkora")
        }, REQ_SAVE)
    }

    // =========================================================
    // actions
    // =========================================================

    private fun newCanvasDialog() {
        val (sw, sh) = screenSize()
        val options = arrayOf(
            "Screen size ($sw × $sh)",
            "Square (2048 × 2048)",
            "4K landscape (3840 × 2160)",
            "A4 portrait, 300 dpi (2480 × 3508)",
            "Custom size…"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("New canvas"))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> newCanvas(sw, sh)
                    1 -> newCanvas(2048, 2048)
                    2 -> newCanvas(3840, 2160)
                    3 -> newCanvas(2480, 3508)
                    4 -> customSizeDialog()
                }
            }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    private fun customSizeDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24.dp, 8.dp, 24.dp, 0) }
        val w = EditText(this).apply { hint = "Width"; inputType = InputType.TYPE_CLASS_NUMBER }
        val h = EditText(this).apply { hint = "Height"; inputType = InputType.TYPE_CLASS_NUMBER }
        box.addView(w, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(h, LinearLayout.LayoutParams(0, -2, 1f))
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Custom size (pixels)"))
            .setView(box)
            .setPositiveButton(L10n.tr("Create")) { _, _ ->
                val ww = w.text.toString().toIntOrNull() ?: 0
                val hh = h.text.toString().toIntOrNull() ?: 0
                // Android cannot draw a single bitmap larger than ~100 MB (25 megapixels)
                if (ww < 64 || hh < 64 || ww > 8192 || hh > 8192 || ww.toLong() * hh > 24_000_000L) {
                    toast("Size must be 64–8192 px per side and at most 24 megapixels")
                } else newCanvas(ww, hh)
            }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    private fun newCanvas(w: Int, h: Int) {
        autosave()
        closeGallery()
        openDocument(newDocument(w, h))
        autosave(force = true)
        toast("New canvas $w × $h")
    }

    private fun exportImage(jpeg: Boolean, share: Boolean) {
        if (busy()) return
        val bmp = try { cv.doc.flatten(withBackground = true, forceOpaque = jpeg) } catch (e: OutOfMemoryError) { null }
        if (bmp == null) { toast("Not enough memory to export it"); return }
        toast("Exporting…")
        thread(name = "inkora-export") {
            val uri = ProjectIO.exportToGallery(applicationContext, bmp, jpeg)
            bmp.recycle()
            main.post {
                if (uri == null) toast("Export failed")
                else if (share) {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(i, "Share artwork"))
                } else toast("Saved to Pictures/Inkora")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQ_BRUSH) {
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            else data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) importBrushFiles(uris)
            return
        }
        val uri: Uri = data.data ?: return
        when (requestCode) {
            REQ_SAVE -> saveProject(uri)
            REQ_OPEN -> openProject(uri)
            REQ_IMAGE -> insertImage(uri)
            REQ_SHADOW_IMAGE -> insertImage(uri, shadow = true)
            REQ_MODEL -> readModel(uri)
            REQ_PALETTE_PHOTO -> {
                val bmp = runCatching { ProjectIO.decodeImage(this, uri, 600, 600) }.getOrNull()
                if (bmp == null) toast("Could not open that image")
                else {
                    PaletteStore.create("From photo", PaletteStore.extract(bmp))
                    bmp.recycle()
                    colorPanel?.paletteAdded()
                    toast("New palette made from the photo")
                }
            }
            REQ_PHOTO_NEW -> photoAsArtwork(uri)
            REQ_SVG -> writeSvg(uri)
            REQ_BRUSH_EXPORT -> writeBrushFile(uri)
            REQ_EXPORT_FILE -> writeExportFile(uri)
            REQ_BRUSH_IMAGE -> setBrushImage(uri)
            REQ_REF_IMAGE -> {
                val bmp = runCatching { ProjectIO.decodeImage(this, uri, 2048, 2048) }.getOrNull()
                if (bmp == null) toast("Could not open that image")
                else (pinned["reference"]?.getChildAt(1) as? ReferenceWindow)?.setImage(bmp)
            }
        }
    }

    private fun saveProject(uri: Uri) {
        val snap = try { ProjectIO.snapshot(cv.doc) } catch (e: OutOfMemoryError) { null }
        if (snap == null) { toast("Not enough memory to export it"); return }
        toast("Saving…")
        thread(name = "inkora-save") {
            val ok = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { ProjectIO.write(snap, it) } != null
            }.getOrDefault(false)
            snap.layers.forEach { it.bitmap.recycle() }
            main.post { toast(if (ok) "Project saved" else "Could not save the project") }
        }
    }

    private fun openProject(uri: Uri) {
        val head = runCatching { contentResolver.openInputStream(uri)?.use { s -> ByteArray(4).also { s.read(it) } } }.getOrNull()
        if (head != null && PsdIO.isPsd(head)) { openPsd(uri); return }
        val d = runCatching { contentResolver.openInputStream(uri)?.use { ProjectIO.read(it) } }.getOrNull()
        if (d == null) {
            toast("This is not an Inkora project file")
            return
        }
        // an opened project becomes a new artwork in the gallery
        autosave()
        closeGallery()
        d.galleryId = Gallery.newId()
        d.title = displayName(uri)?.substringBeforeLast('.') ?: "Imported artwork"
        d.origin = "imported"
        d.revision = 1
        openDocument(d)
        autosave(force = true)
        toast("Project opened")
    }

    /** A Photoshop file becomes a new artwork, with its layers. Read on a background thread. */
    private fun openPsd(uri: Uri) {
        hud.show("Opening…", sticky = true)
        thread(name = "inkora-psd-open") {
            val r = runCatching {
                contentResolver.openInputStream(uri)?.use { PsdIO.read(it) { w, h -> Document(w, h).maxLayers() } }
            }
            main.post {
                hud.hideSoon()
                val res = r.getOrNull()
                if (res == null) {
                    val why = r.exceptionOrNull()?.let { if (it is OutOfMemoryError) "Not enough memory to open it" else it.message }
                    toast(why ?: "Could not open this Photoshop file")
                    return@post
                }
                autosave()
                closeGallery()
                val d = res.doc
                d.galleryId = Gallery.newId()
                d.title = displayName(uri)?.substringBeforeLast('.') ?: "Photoshop file"
                d.origin = "imported"
                d.revision = 1
                openDocument(d)
                autosave(force = true)
                toast(res.note?.let { L10n.tr(it) } ?: (L10n.tr("Opened") + " · ${d.layers.size} " + L10n.tr("Layers")))
            }
        }
    }

    // ---------- PSD, PDF and TIFF export ----------

    private var pendingFormat = "psd"

    private fun exportFile(kind: String) {
        pendingFormat = kind
        val (mime, ext) = when (kind) {
            "psd" -> "image/vnd.adobe.photoshop" to ".psd"
            "pdf" -> "application/pdf" to ".pdf"
            else -> "image/tiff" to ".tif"
        }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime
            putExtra(Intent.EXTRA_TITLE, BrushExport.fileName(cv.doc.title.ifBlank { "Artwork" }, ext))
        }, REQ_EXPORT_FILE)
    }

    private fun writeExportFile(uri: Uri) {
        val kind = pendingFormat
        val doc = cv.doc
        // copies of the layers (or the flattened image), so painting can go on while it writes
        val snap = runCatching { if (kind == "psd") ProjectIO.snapshot(doc) else null }.getOrNull()
        val flat = if (kind == "psd") null else runCatching { doc.flatten(withBackground = true, forceOpaque = kind == "pdf") }.getOrNull()
        if ((kind == "psd" && snap == null) || (kind != "psd" && flat == null)) { toast("Not enough memory to export it"); return }
        hud.show("Exporting…", sticky = true)
        thread(name = "inkora-export-file") {
            val ok = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    when (kind) {
                        "psd" -> {
                            PsdIO.write(snap!!.toDocument(), out, cacheDir)
                        }
                        "pdf" -> {
                            val b = flat!!
                            // printed at 300 dpi: a page of the artwork's size
                            val k = (72f / 300f).coerceAtMost(14400f / maxOf(b.width, b.height))
                            val pw = (b.width * k).toInt().coerceAtLeast(1); val ph = (b.height * k).toInt().coerceAtLeast(1)
                            val pdf = android.graphics.pdf.PdfDocument()
                            val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pw, ph, 1).create())
                            page.canvas.drawBitmap(b, null, android.graphics.RectF(0f, 0f, pw.toFloat(), ph.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                            pdf.finishPage(page)
                            pdf.writeTo(out)
                            pdf.close()
                        }
                        else -> TiffIO.write(flat!!, out)
                    }
                } != null
            }.getOrDefault(false)
            snap?.layers?.forEach { it.bitmap.recycle() }
            flat?.recycle()
            main.post {
                hud.hideSoon()
                toast(if (ok) "Exported" else "Could not export")
            }
        }
    }

    /** A photo becomes a new artwork of the same size (max 24 megapixels). */
    private fun photoAsArtwork(uri: Uri) {
        val img = runCatching { ProjectIO.decodeImage(this, uri, 8192, 8192) }.getOrNull()
        if (img == null) { toast("Could not open that image"); return }
        var w = img.width
        var h = img.height
        val maxPx = 24_000_000.0
        if (w.toDouble() * h > maxPx) {
            val s = Math.sqrt(maxPx / (w.toDouble() * h))
            w = (w * s).toInt(); h = (h * s).toInt()
        }
        autosave()
        val d = Document.blank(w, h)
        d.galleryId = Gallery.newId()
        d.title = displayName(uri)?.substringBeforeLast('.') ?: "Photo"
        d.origin = "imported"
        d.revision = 1
        d.layers[0].name = "Photo"
        Canvas(d.layers[0].bitmap).drawBitmap(img, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        img.recycle()
        // a fresh layer on top, ready to draw on
        d.layers.add(d.createLayer())
        d.activeIndex = 1
        closeGallery()
        openDocument(d)
        autosave(force = true)
        toast("Photo opened as a new artwork")
    }

    private fun insertImage(uri: Uri, shadow: Boolean = false) {
        val doc = cv.doc
        if (doc.layers.size >= doc.maxLayers()) {
            toast("Layer limit reached for this canvas size")
            return
        }
        val img = runCatching { ProjectIO.decodeImage(this, uri, doc.width, doc.height) }.getOrNull()
        if (img == null) {
            toast("Could not open that image")
            return
        }
        val layer = try { doc.createLayer() } catch (e: OutOfMemoryError) {
            img.recycle(); toast("Not enough memory for another layer right now"); return
        }
        layer.name = if (shadow) "Shadow photo" else "Photo"
        layer.shadow = shadow
        if (shadow) layer.opacity = 0.6f
        val s = min(1f, min(doc.width / img.width.toFloat(), doc.height / img.height.toFloat()))
        val w = img.width * s
        val h = img.height * s
        val c = Canvas(layer.bitmap)
        c.drawBitmap(img, null, android.graphics.RectF((doc.width - w) / 2, (doc.height - h) / 2, (doc.width + w) / 2, (doc.height + h) / 2),
            Paint(Paint.FILTER_BITMAP_FLAG))
        img.recycle()
        val cmd = AddLayerCmd(layer, doc.activeIndex + 1, doc.activeIndex)
        cmd.redo(doc)
        cv.pushCommand(cmd)
        toast(if (shadow) "Shadow photo added: trace it, it stays out of the time-lapse and exports" else "Photo added as a new layer")
    }

    // =========================================================
    // 3D & poses
    // =========================================================

    private var scene3d: Scene3DEditor? = null
    /** the 3D layer being edited (null = a new one) */
    private var scene3dLayer: Layer? = null

    private fun open3D(layer: Layer?) {
        if (!cv.isDocReady()) return
        popup?.dismiss()
        val doc = cv.doc
        if (layer == null && doc.layers.size >= doc.maxLayers()) { toast("Layer limit reached for this canvas size"); return }
        scene3dLayer = layer
        val start = layer?.scene ?: Scene3D()
        val ed = Scene3DEditor(this, start, layer?.shadow ?: true, scene3dHost)
        scene3d = ed
        showOverlay(ed)
    }

    private val scene3dHost = object : Scene3DHost {
        override fun backdrop(maxSide: Int): Bitmap? = runCatching {
            val doc = cv.doc
            val s = min(1f, maxSide / maxOf(doc.width, doc.height).toFloat())
            val b = Bitmap.createBitmap((doc.width * s).toInt().coerceAtLeast(1), (doc.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.scale(s, s)
            // the artwork without the 3D layer being edited
            val l = scene3dLayer
            val vis = l?.visible
            if (l != null) l.visible = false
            doc.draw(c, true)
            if (l != null && vis != null) l.visible = vis
            b
        }.getOrNull()
        override val docWidth get() = cv.doc.width
        override val docHeight get() = cv.doc.height
        override val brushColor get() = cv.color
        override fun pickModelFile() {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, REQ_MODEL)
        }
        override fun onScene3DClosed(scene: Scene3D?, shadow: Boolean) {
            root.findViewWithTag<View>("overlay")?.let { root.removeView(it) }
            scene3d = null
            if (scene != null) place3D(scene, shadow, scene3dLayer)
            scene3dLayer = null
        }
    }

    private fun readModel(uri: Uri) {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "Model"
        toast("Reading the model…")
        thread(name = "inkora-model") {
            val result = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalArgumentException("Could not open that file")
                if (bytes.size > 60_000_000) throw IllegalArgumentException("This file is too big")
                MeshIO.read(bytes, name)
            }
            main.post {
                result.onSuccess { m -> scene3d?.addModel(m, name.substringBeforeLast('.')) }
                result.onFailure { e -> toast(e.message ?: "Could not read this model (use .obj or .stl)") }
            }
        }
    }

    /** draws [scene] into its layer (a new one or [target]) in the background */
    private fun place3D(scene: Scene3D, shadow: Boolean, target: Layer?) {
        val doc = cv.doc
        hud.show("Drawing the 3D scene…", sticky = true)
        val w = doc.width; val h = doc.height
        thread(name = "inkora-3d") {
            val px = runCatching {
                IntArray(w * h).also { Renderer3D(scene, w, h).render(it, if (w.toLong() * h <= 9_000_000L) 2 else 1) }
            }.getOrNull()
            main.post {
                hud.hideSoon()
                if (px == null) { toast("Not enough memory for this right now"); return@post }
                if (target != null && target in doc.layers) {
                    val before = target.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    val after = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    after.setPixels(px, 0, w, 0, 0, w, h)
                    val cmd = Scene3DCmd(target, before, target.scene, target.shadow, after.copy(Bitmap.Config.ARGB_8888, false), scene, shadow)
                    cmd.redo(doc)
                    after.recycle()
                    cv.pushCommand(cmd)
                } else {
                    val layer = doc.createLayer()
                    layer.name = "3D " + (scene.objects.firstOrNull()?.name?.let { L10n.tr(it) } ?: "scene")
                    layer.bitmap.setPixels(px, 0, w, 0, 0, w, h)
                    layer.scene = scene
                    layer.shadow = shadow
                    val cmd = AddLayerCmd(layer, doc.activeIndex + 1, doc.activeIndex)
                    cmd.redo(doc)
                    cv.pushCommand(cmd)
                }
                doc.revision++
                refreshAll()
                hud.show(if (shadow) "3D layer placed · shadow layer: it stays out of the time-lapse and exports" else "3D layer placed")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, L10n.tr(s), Toast.LENGTH_SHORT).show()

    // =========================================================
    // CanvasView.Listener
    // =========================================================

    // =========================================================
    // bottom bars (selection / transform / adjustments)
    // =========================================================

    private fun showBottomBar(v: View) {
        hideBottomBar()
        bottomBar = v
        root.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = 18.dp
        })
    }

    private fun hideBottomBar() {
        bottomBar?.let { root.removeView(it) }
        bottomBar = null
    }

    private fun barRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(10.dp, 8.dp, 10.dp, 8.dp)
        background = Ui.rounded(Ui.PANEL, 16f)
        elevation = 12f.dp
    }

    private fun barButton(label: String, highlighted: Boolean = false, onClick: () -> Unit) =
        Ui.button(this, label, onClick).apply {
            textSize = 13f
            setPadding(12.dp, 9.dp, 12.dp, 9.dp)
            if (highlighted) background = Ui.rounded(Ui.ACCENT, 10f)
        }

    private fun LinearLayout.addBar(v: View) = addView(v, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 4.dp; rightMargin = 4.dp })

    // ---------- Selection ----------

    private fun enterSelect(shape: SelectShape? = null) {
        shape?.let { cv.selectShape = it }
        popup?.dismiss()
        if (cv.doc.active.vector != null) { hud.show("On a vector layer use the vector Select tool"); return }
        cv.interaction = Interaction.SELECT
        showSelectBar()
        refreshAll()
        hud.show(when (cv.selectShape) {
            SelectShape.WAND -> "Magic wand: tap an area to select it"
            SelectShape.COLOR -> "Color range: tap a color to select it everywhere"
            SelectShape.BRUSH -> "Selection brush: paint what you want to select"
            else -> "Draw around what you want to select"
        })
    }

    private fun showSelectBar() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val shapes = barRow()
        SelectShape.values().forEach { s ->
            shapes.addBar(barButton(s.label, cv.selectShape == s) { cv.selectShape = s; showSelectBar() })
        }
        col.addView(shapes)
        // new / add / subtract, and the wand's tolerance or the selection brush's size
        val modes = barRow()
        SelectCombine.values().forEach { m ->
            modes.addBar(barButton(m.label, cv.selectCombine == m) { cv.selectCombine = m; showSelectBar() })
        }
        if (cv.selectShape.isTap) {
            modes.addBar(barButton("−") { cv.wandTolerance = (cv.wandTolerance - 0.04f).coerceAtLeast(0f); showSelectBar() })
            modes.addBar(barButton("Tolerance ${(cv.wandTolerance * 100).roundToInt()}%") {})
            modes.addBar(barButton("+") { cv.wandTolerance = (cv.wandTolerance + 0.04f).coerceAtMost(1f); showSelectBar() })
        } else if (cv.selectShape == SelectShape.BRUSH) {
            modes.addBar(barButton("−") { cv.selectBrushPx = (cv.selectBrushPx / 1.3f).coerceAtLeast(6f); showSelectBar() })
            modes.addBar(barButton("Brush size ${cv.selectBrushPx.roundToInt()} px") {})
            modes.addBar(barButton("+") { cv.selectBrushPx = (cv.selectBrushPx * 1.3f).coerceAtMost(400f); showSelectBar() })
        }
        modes.addBar(barButton("Select all") { cv.selectAll() })
        modes.addBar(barButton("Invert") { cv.invertSelection() })
        col.addView(modes, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.dp })
        val actions = barRow()
        actions.addBar(barButton("Transform") { if (cv.selection != null) enterTransform() else hud.show("Select something first") })
        actions.addBar(barButton("Copy & Paste") { cv.copySelectionToNewLayer() })
        actions.addBar(barButton("Fill") { cv.fillSelection(cv.color) })
        actions.addBar(barButton("Clear") { cv.clearSelection() })
        actions.addBar(barButton("Feather") {
            val pm = PopupMenu(this, actions)
            listOf(4, 12, 30, 60).forEachIndexed { i, r -> pm.menu.add(0, r, i, "Feather $r px") }
            pm.setOnMenuItemClickListener { cv.featherSelection(it.itemId.toFloat()); true }
            L10n.menu(pm.menu); pm.show()
        })
        actions.addBar(barButton("Deselect") { cv.selection = null })
        actions.addBar(barButton("Done", true) {
            cv.interaction = Interaction.PAINT
            hideBottomBar()
            refreshAll()
        })
        col.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.dp })
        showBottomBar(col)
    }

    // ---------- Canvas size, crop, rotate ----------

    private fun showCanvasSize() {
        popup?.dismiss()
        if (cv.liquify != null || cv.transform != null || adjustKind != null) { hud.show("Finish or cancel the current edit first"); return }
        val doc = cv.doc
        val items = mutableListOf(
            "Canvas size (crop or add space)…" to { canvasSizeDialog(false) },
            "Resize image…" to { canvasSizeDialog(true) }
        )
        cv.selection?.let { sel -> items.add("Crop to selection" to { runCanvasOp("Cropped") { CanvasOps.crop(doc, android.graphics.RectF(sel.bounds)) } }) }
        items.add("Rotate 90° right" to { runCanvasOp("Turned") { CanvasOps.rotate90(doc, true) } })
        items.add("Rotate 90° left" to { runCanvasOp("Turned") { CanvasOps.rotate90(doc, false) } })
        items.add("Flip canvas horizontally" to { runCanvasOp("Flipped") { CanvasOps.flip(doc, true) } })
        items.add("Flip canvas vertically" to { runCanvasOp("Flipped") { CanvasOps.flip(doc, false) } })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Canvas") + " · ${doc.width} × ${doc.height}")
            .setItems(items.map { L10n.tr(it.first) }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    /** [resample] = resize the image; otherwise change the canvas around it (crop / add space) */
    private fun canvasSizeDialog(resample: Boolean) {
        val doc = cv.doc
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24.dp, 8.dp, 24.dp, 0) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val w = EditText(this).apply { setText(doc.width.toString()); inputType = InputType.TYPE_CLASS_NUMBER; setSelectAllOnFocus(true) }
        val h = EditText(this).apply { setText(doc.height.toString()); inputType = InputType.TYPE_CLASS_NUMBER; setSelectAllOnFocus(true) }
        row.addView(w, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(android.widget.TextView(this).apply { text = " × " })
        row.addView(h, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row)
        val keep = android.widget.CheckBox(this).apply { text = L10n.tr("Keep proportions"); isChecked = resample }
        box.addView(keep)
        // width and height follow each other while proportions are kept
        var syncing = false
        fun watch(src: EditText, dst: EditText, ratio: () -> Float) = src.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (syncing || !keep.isChecked || !src.hasFocus()) return
                val v = s.toString().toIntOrNull() ?: return
                syncing = true
                dst.setText((v * ratio()).roundToInt().coerceAtLeast(1).toString())
                syncing = false
            }
        })
        watch(w, h) { doc.height / doc.width.toFloat() }
        watch(h, w) { doc.width / doc.height.toFloat() }
        // where the artwork sits in a new canvas size: a 3 × 3 grid of anchors
        var ax = 0.5f; var ay = 0.5f
        if (!resample) {
            box.addView(android.widget.TextView(this).apply { text = L10n.tr("Artwork position"); setPadding(0, 12.dp, 0, 4.dp) })
            val grid = android.widget.GridLayout(this).apply { columnCount = 3 }
            val cells = ArrayList<android.widget.TextView>()
            for (j in 0..2) for (i in 0..2) {
                val cell = android.widget.TextView(this).apply {
                    gravity = Gravity.CENTER
                    textSize = 18f
                    text = if (i == 1 && j == 1) "●" else "○"
                    setPadding(14.dp, 6.dp, 14.dp, 6.dp)
                }
                cell.setOnClickListener {
                    ax = i / 2f; ay = j / 2f
                    cells.forEach { it.text = "○" }
                    cell.text = "●"
                }
                cells.add(cell)
                grid.addView(cell)
            }
            box.addView(grid)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr(if (resample) "Resize image" else "Canvas size"))
            .setView(box)
            .setPositiveButton(L10n.tr("OK")) { _, _ ->
                val ww = w.text.toString().toIntOrNull() ?: 0
                val hh = h.text.toString().toIntOrNull() ?: 0
                if (ww < 16 || hh < 16) { toast("Too small"); return@setPositiveButton }
                if (ww.toLong() * hh > CanvasOps.MAX_PIXELS) { toast("Too big: at most 25 megapixels"); return@setPositiveButton }
                if (ww == doc.width && hh == doc.height) return@setPositiveButton
                runCanvasOp(if (resample) "Resized" else "Canvas size changed") {
                    if (resample) CanvasOps.resize(doc, ww, hh) else CanvasOps.canvasSize(doc, ww, hh, ax, ay)
                }
            }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    /** builds the changed document in the background, then shows it (undo history starts again) */
    private fun runCanvasOp(done: String, make: () -> Document) {
        if (busy()) return
        hud.show("Working…", sticky = true)
        val was = cv.interaction
        cv.interaction = Interaction.LOCKED
        thread(name = "inkora-canvas") {
            val d = try { make() } catch (e: OutOfMemoryError) { null } catch (e: Exception) { null }
            main.post {
                hud.hideSoon()
                if (d == null) { cv.interaction = was; toast("Not enough memory for this right now"); return@post }
                openDocument(d)
                d.revision++
                hud.show(L10n.tr(done) + " · ${d.width} × ${d.height}")
            }
        }
    }

    // ---------- Liquify ----------

    private fun enterLiquify() {
        popup?.dismiss()
        if (!cv.beginLiquify()) return
        showLiquifyBar()
        refreshAll()
        hud.show("Liquify: drag on the layer · two fingers move the view")
    }

    private fun showLiquifyBar() {
        val s = cv.liquify ?: return
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val modes = barRow()
        LiquifyMode.values().forEach { m ->
            modes.addBar(barButton(m.label, s.mode == m) { s.mode = m; showLiquifyBar() })
        }
        col.addView(modes)
        val row = barRow()
        row.addBar(barButton("−") { cv.liquifyBrushPx = (cv.liquifyBrushPx / 1.25f).coerceAtLeast(20f); showLiquifyBar() })
        row.addBar(barButton("Size ${cv.liquifyBrushPx.roundToInt()} px") {})
        row.addBar(barButton("+") { cv.liquifyBrushPx = (cv.liquifyBrushPx * 1.25f).coerceAtMost(900f); showLiquifyBar() })
        row.addBar(barButton("−") { s.strength = (s.strength - 0.1f).coerceAtLeast(0.1f); showLiquifyBar() })
        row.addBar(barButton("Strength ${(s.strength * 100).roundToInt()}%") {})
        row.addBar(barButton("+") { s.strength = (s.strength + 0.1f).coerceAtMost(1f); showLiquifyBar() })
        row.addBar(barButton("Reset") { s.reset(); cv.invalidate() })
        row.addBar(barButton("Cancel") { leaveLiquify(false) })
        row.addBar(barButton("Done", true) { leaveLiquify(true) })
        col.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.dp })
        showBottomBar(col)
    }

    private fun leaveLiquify(apply: Boolean) {
        cv.endLiquify(apply)
        hideBottomBar()
        refreshAll()
    }

    // ---------- Transform ----------

    private fun enterTransform() {
        popup?.dismiss()
        if (!cv.beginTransform()) return
        val bar = barRow()
        bar.addBar(barButton("Flip ↔") { cv.transformAction("flipH") })
        bar.addBar(barButton("Flip ↕") { cv.transformAction("flipV") })
        bar.addBar(barButton("Rotate 90°") { cv.transformAction("rotate") })
        bar.addBar(barButton("Fit to canvas") { cv.transformAction("fit") })
        bar.addBar(barButton("Reset") { cv.transformAction("reset") })
        bar.addBar(barButton("Cancel") { leaveTransform(false) })
        bar.addBar(barButton("Done", true) { leaveTransform(true) })
        showBottomBar(bar)
        refreshAll()
        hud.show("Drag to move · corners to scale · green dot to rotate · two fingers to pinch")
    }

    private fun leaveTransform(apply: Boolean) {
        cv.finishTransform(apply)
        hideBottomBar()
        refreshAll()
    }

    // ---------- Adjustments ----------

    private fun startAdjust(kind: AdjustKind) {
        val src = cv.beginAdjust() ?: return
        adjustKind = kind
        adjustSrc = src
        adjustFinish = null
        adjustValues = FloatArray(4) { i -> kind.sliders.getOrNull(i)?.default ?: 0f }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 12.dp, 18.dp, 12.dp)
            background = Ui.rounded(Ui.PANEL, 16f)
            elevation = 12f.dp
        }
        val note = if (cv.selection != null) "  (selection only)" else ""
        panel.addView(Ui.text(this, kind.label + note, 15f, bold = true))
        kind.sliders.forEachIndexed { i, sl ->
            val start = (sl.default - sl.min) / (sl.max - sl.min)
            panel.addView(Ui.sliderRow(this, sl.name, start, { v ->
                val value = sl.min + v * (sl.max - sl.min)
                "${value.roundToInt()}${sl.unit}"
            }) { v ->
                adjustValues[i] = sl.min + v * (sl.max - sl.min)
                requestAdjust()
            }, LinearLayout.LayoutParams(420.dp, -2))
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(barButton("Cancel") { endAdjust(false) })
        buttons.addView(barButton("Apply", true) { endAdjust(true) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 8.dp })
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
        showBottomBar(panel)
        refreshAll()
        requestAdjust()
    }

    private fun requestAdjust() {
        if (adjustBusy) { adjustPending = true; return }
        runAdjust()
    }

    private fun runAdjust() {
        val kind = adjustKind ?: return
        val src = adjustSrc ?: return
        adjustBusy = true
        val vals = adjustValues.copyOf()
        val grow = when (kind) {
            AdjustKind.BLUR -> (vals[0] * 2).toInt() + 2
            AdjustKind.SHARPEN -> 8
            else -> 0
        }
        val r = cv.adjustArea(grow)
        if (r.isEmpty) { adjustBusy = false; return }
        val slow = kind == AdjustKind.BLUR || kind == AdjustKind.SHARPEN
        if (slow) hud.show("Working…", sticky = true)
        adjustExec.execute {
            val result = runCatching {
                val crop = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
                Adjust.apply(kind, crop, vals)
            }.getOrNull()
            main.post {
                adjustBusy = false
                if (slow) hud.hideSoon()
                val finish = adjustFinish
                if (result != null && adjustKind == kind) {
                    cv.showAdjusted(r, result)
                    result.recycle()
                }
                when {
                    finish != null -> completeAdjust(finish)
                    adjustPending -> { adjustPending = false; runAdjust() }
                }
            }
        }
    }

    private fun endAdjust(apply: Boolean) {
        // a calculation may still be running: finish when it's done
        if (adjustBusy) {
            adjustFinish = apply
            adjustPending = false
            return
        }
        completeAdjust(apply)
    }

    private fun completeAdjust(apply: Boolean) {
        cv.finishAdjust(apply)
        adjustKind = null
        adjustSrc = null
        adjustFinish = null
        adjustPending = false
        hideBottomBar()
        refreshAll()
    }

    // ---------- Creative / Editing panel ----------

    private val toolsHost = object : ToolsPanel.Host {
        override fun run(id: String) = runTool(id, null)
        override fun isActive(id: String) = isToolActive(id)
        override fun inDock(id: String) = id in dockIds
        override fun toggleDock(id: String) = this@MainActivity.toggleDock(id)
        override val guide: GuideSettings get() = cv.doc.guide
        override fun guideChanged() {
            cv.doc.revision++
            cv.invalidate()
            dock.refresh()
        }
        override fun openGuideSettings() = showGuide(null)
        override var symmetry: Symmetry
            get() = cv.symmetry
            set(v) {
                cv.symmetry = v; cv.invalidate(); dock.refresh()
                prefs.edit().putInt("kaleidoSegments", Symmetry.segments).apply()
            }
        override val quickShape get() = cv.quickShapeEnabled
        override val hasSelection get() = cv.selection != null
        override val isVectorLayer get() = cv.doc.active.vector != null
        override fun thumbnail(): Bitmap? = runCatching {
            val t = Gallery.makeThumb(cv.doc)
            val s = 220f / maxOf(t.width, t.height)
            val small = Bitmap.createScaledBitmap(t, (t.width * s).toInt().coerceAtLeast(1), (t.height * s).toInt().coerceAtLeast(1), true)
            if (small !== t) t.recycle()
            small
        }.getOrNull()
        override fun close() { popup?.dismiss() }
    }

    /** Creative (make things) and Editing (change things), like professional painting apps. */
    private fun showToolsMenu(creative: Boolean, category: String? = null) {
        if (!cv.isDocReady()) return
        category?.let { ToolsPanel.select(creative, it) }
        val panel = ToolsPanel(this, creative, toolsHost)
        popup?.dismiss()
        val w = min(640.dp, root.width - sideBar.width - 40.dp)
        val h = min(600.dp, root.height - 84.dp)
        val pw = PopupWindow(panel, w, h, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        pw.elevation = 18f.dp
        pw.animationStyle = android.R.style.Animation_Dialog
        setTabActive(if (creative) creativeBtn else editingBtn, true)
        pw.setOnDismissListener {
            setTabActive(creativeBtn, false); setTabActive(editingBtn, false)
            refreshAll()
        }
        popup = pw
        val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
        // opens under the Creative / Editing tabs (they sit on the right in Arabic and Kurdish),
        // beside the sliders rather than over them
        val onRight = leftHanded != L10n.isRtl
        val sl = slidersEdge()
        val gap = if ((sl == "Left" && !onRight) || (sl == "Right" && onRight)) sideBar.width + 16.dp else 12.dp
        val x = (if (onRight) root.width - w - gap else gap).coerceIn(8.dp, (root.width - w - 8.dp).coerceAtLeast(8.dp))
        pw.showAtLocation(root, Gravity.TOP or Gravity.LEFT, x + rootLoc[0], 64.dp + rootLoc[1])
    }

    // ---------- Vector layers ----------

    private var vectorBar: View? = null

    /** A vector layer switches the canvas into vector mode (and back). */
    private fun syncVectorMode() {
        if (!cv.isDocReady() || !::quickMenu.isInitialized) return
        val isVec = cv.doc.active.vector != null
        if (isVec && cv.interaction == Interaction.PAINT) {
            cv.interaction = Interaction.VECTOR
            showVectorBar()
        } else if (isVec && cv.interaction == Interaction.VECTOR && bottomBar !== vectorBar) {
            showVectorBar()
        } else if (!isVec && cv.interaction == Interaction.VECTOR) {
            cv.vectorEditor.finishPen()
            cv.vectorEditor.reset()
            cv.interaction = Interaction.PAINT
            if (bottomBar === vectorBar) hideBottomBar()
            vectorBar = null
        }
    }

    private fun toolButton(icon: Icon, label: String, active: Boolean, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(4.dp, 2.dp, 4.dp, 2.dp)
            if (active) background = Ui.rounded(0x2E6E9FF5, 10f)
            setOnClickListener { onClick() }
        }
        val iv = IconView(this, icon).apply { this.active = active; isClickable = false }
        box.addView(iv, LinearLayout.LayoutParams(38.dp, 34.dp))
        box.addView(Ui.text(this, label, 10f, if (active) Ui.ACCENT else Ui.TEXT_DIM))
        return box
    }

    private fun showVectorBar() {
        val ed = cv.vectorEditor
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val tools = barRow()
        tools.addView(Ui.text(this, "VECTOR", 10f, Ui.ACCENT, true).apply { setPadding(4.dp, 0, 8.dp, 0) })
        VTool.values().forEach { t ->
            tools.addView(toolButton(t.icon, t.label, ed.tool == t) { ed.tool = t; showVectorBar() })
        }
        col.addView(android.widget.HorizontalScrollView(this).apply { addView(tools); isHorizontalScrollBarEnabled = false })

        val acts = barRow()
        val sel = ed.selected.size
        acts.addBar(barButton("Style…") { showStylePanel() })
        when (ed.tool) {
            VTool.RECT, VTool.POLYGON, VTool.STAR -> acts.addBar(barButton("Shape options…") { shapeOptionsDialog() })
            VTool.PEN -> if (ed.penActive) acts.addBar(barButton("Finish path", true) { ed.finishPen(); showVectorBar() })
            VTool.DIRECT -> {
                acts.addBar(barButton("Smooth ⇄ Corner") { ed.toggleSmooth() })
                acts.addBar(barButton("Delete point") { ed.deletePoint() })
            }
            else -> {}
        }
        acts.addBar(barButton(if (ed.constrain) "Constrain: ON" else "Constrain", ed.constrain) { ed.constrain = !ed.constrain; showVectorBar() })
        if (ed.tool == VTool.SELECT) {
            acts.addBar(barButton(if (ed.multi) "Multi-select: ON" else "Multi-select", ed.multi) { ed.multi = !ed.multi; showVectorBar() })
            acts.addBar(barButton("Select all") { ed.selectAll() })
        }
        if (sel > 0) {
            val combine = barButton("Combine ▾") {}
            combine.setOnClickListener {
                val pm = PopupMenu(this, combine)
                BoolOp.values().forEachIndexed { i, o -> pm.menu.add(0, i, i, o.label) }
                pm.setOnMenuItemClickListener { ed.booleanOp(BoolOp.values()[it.itemId]); true }
                L10n.menu(pm.menu); pm.show()
            }
            acts.addBar(combine)
            val arrange = barButton("Arrange ▾") {}
            arrange.setOnClickListener {
                val pm = PopupMenu(this, arrange)
                listOf("Bring to front" to "front", "Bring forward" to "forward", "Send backward" to "backward", "Send to back" to "back")
                    .forEachIndexed { i, (l, _) -> pm.menu.add(0, i, i, l) }
                pm.setOnMenuItemClickListener { ed.arrange(listOf("front", "forward", "backward", "back")[it.itemId]); true }
                L10n.menu(pm.menu); pm.show()
            }
            acts.addBar(arrange)
            val align = barButton("Align ▾") {}
            align.setOnClickListener {
                val pm = PopupMenu(this, align)
                val opts = listOf("Left" to "left", "Center" to "hcenter", "Right" to "right", "Top" to "top", "Middle" to "vcenter", "Bottom" to "bottom")
                opts.forEachIndexed { i, (l, _) -> pm.menu.add(0, i, i, "Align $l") }
                pm.setOnMenuItemClickListener { ed.align(opts[it.itemId].second); true }
                L10n.menu(pm.menu); pm.show()
            }
            acts.addBar(align)
            acts.addBar(barButton("Flip ↔") { ed.flip(true) })
            acts.addBar(barButton("Flip ↕") { ed.flip(false) })
            acts.addBar(barButton("Duplicate") { ed.duplicateSelected() })
            acts.addBar(barButton("Delete") { ed.deleteSelected() }.apply { setTextColor(0xFFF07178.toInt()) })
            acts.addBar(barButton("Deselect") { ed.deselect() })
        }
        col.addView(android.widget.HorizontalScrollView(this).apply { addView(acts); isHorizontalScrollBarEnabled = false },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.dp })
        vectorBar = col
        showBottomBar(col)
    }

    override fun onVectorSelectionChanged() {
        if (cv.interaction == Interaction.VECTOR) showVectorBar()
    }

    private fun shapeOptionsDialog() {
        val ed = cv.vectorEditor
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24.dp, 8.dp, 24.dp, 0) }
        box.addView(Ui.sliderRow(this, "Rectangle corner radius", (ed.cornerRadius / 300f).coerceIn(0f, 1f), { "${(it * 300).roundToInt()} px" }) { ed.cornerRadius = it * 300f })
        box.addView(Ui.sliderRow(this, "Polygon sides", (ed.polygonSides - 3) / 17f, { "${3 + (it * 17).roundToInt()}" }) { ed.polygonSides = 3 + (it * 17).roundToInt() })
        box.addView(Ui.sliderRow(this, "Star points", (ed.starPoints - 3) / 17f, { "${3 + (it * 17).roundToInt()}" }) { ed.starPoints = 3 + (it * 17).roundToInt() })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Shape options")).setView(box).setPositiveButton(L10n.tr("Done"), null).show()
    }

    /** Fill + stroke settings: change the selected shapes, and the style of new ones. */
    private fun showStylePanel() {
        val ed = cv.vectorEditor
        val targets = ed.selected.toList()
        val sample = ed.sample()
        // working copies of the style
        val fill = sample?.fill?.copy() ?: VPaint(ed.fillKind, cv.color or 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        val stroke = sample?.stroke?.copy() ?: VPaint(if (ed.strokeOn) FillKind.SOLID else FillKind.NONE, ed.strokeColor)
        var width = sample?.strokeWidth ?: ed.strokeWidth
        var cap = sample?.cap ?: Paint.Cap.ROUND
        var join = sample?.join ?: Paint.Join.ROUND
        var dashed = sample?.dashed ?: false
        var opacity = sample?.opacity ?: 1f
        ed.beginStyle()

        fun apply() {
            for (s in targets) {
                val b = s.bounds()
                val keepGradient = s.fill.kind == fill.kind && (fill.kind == FillKind.LINEAR || fill.kind == FillKind.RADIAL)
                val nf = fill.copy()
                if (keepGradient) { nf.x1 = s.fill.x1; nf.y1 = s.fill.y1; nf.x2 = s.fill.x2; nf.y2 = s.fill.y2 } else nf.fitTo(b)
                s.fill = nf
                s.stroke = stroke.copy().also { it.fitTo(b) }
                s.strokeWidth = width; s.cap = cap; s.join = join; s.dashed = dashed; s.opacity = opacity
            }
            // new shapes use the same style
            ed.fillKind = fill.kind
            ed.strokeOn = stroke.kind != FillKind.NONE
            ed.strokeColor = stroke.color
            ed.strokeWidth = width
            if (fill.kind != FillKind.NONE) { cv.color = fill.color; colorBtn.color = fill.color }
            ed.styleChanged()
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 16.dp)
            background = Ui.rounded(Ui.PANEL, 18f)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(box) }

        fun chips(options: List<String>, selectedIndex: Int, onPick: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            options.forEachIndexed { i, l ->
                row.addView(Ui.button(this, l) { onPick(i) }.apply {
                    textSize = 12f
                    setPadding(10.dp, 7.dp, 10.dp, 7.dp)
                    background = Ui.rounded(if (i == selectedIndex) Ui.PANEL_ROW_ACTIVE else Ui.PANEL_ROW, 8f,
                        if (i == selectedIndex) Ui.ACCENT else 0, if (i == selectedIndex) 1 else 0)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 4.dp })
            }
            return row
        }

        fun swatch(initial: Int, onPick: (Int) -> Unit): ColorSwatchView = ColorSwatchView(this).apply {
            color = initial
            setOnClickListener { pickColor(this, color) { c -> color = c; onPick(c) } }
        }

        lateinit var rebuild: () -> Unit
        rebuild = {
            box.removeAllViews()
            box.addView(Ui.text(this, "Style", 17f, bold = true))
            box.addView(Ui.text(this, if (targets.isEmpty()) "For new shapes" else "${targets.size} selected shape(s) + new shapes", 12f, Ui.TEXT_DIM))

            box.addView(Ui.text(this, "FILL", 11f, Ui.TEXT_DIM, true).apply { setPadding(0, 14.dp, 0, 4.dp) })
            box.addView(chips(FillKind.values().map { it.label }, fill.kind.ordinal) { fill.kind = FillKind.values()[it]; apply(); rebuild() })
            if (fill.kind != FillKind.NONE) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(swatch(fill.color) { fill.color = it; apply() }, LinearLayout.LayoutParams(44.dp, 44.dp))
                if (fill.kind == FillKind.LINEAR || fill.kind == FillKind.RADIAL) {
                    row.addView(Ui.text(this, "  →  ", 14f, Ui.TEXT_DIM))
                    row.addView(swatch(fill.color2) { fill.color2 = it; apply() }, LinearLayout.LayoutParams(44.dp, 44.dp))
                    row.addView(Ui.button(this, "Swap") { val t = fill.color; fill.color = fill.color2; fill.color2 = t; apply(); rebuild() }
                        .apply { textSize = 12f }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 10.dp })
                }
                box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.dp })
            }

            box.addView(Ui.text(this, "STROKE", 11f, Ui.TEXT_DIM, true).apply { setPadding(0, 14.dp, 0, 4.dp) })
            box.addView(chips(listOf("None", "Color"), if (stroke.kind == FillKind.NONE) 0 else 1) {
                stroke.kind = if (it == 0) FillKind.NONE else FillKind.SOLID; apply(); rebuild()
            })
            if (stroke.kind != FillKind.NONE) {
                box.addView(swatch(stroke.color) { stroke.color = it; apply() }, LinearLayout.LayoutParams(44.dp, 44.dp).apply { topMargin = 6.dp })
                box.addView(Ui.sliderRow(this, "Width", Math.sqrt((width / 150f).toDouble()).toFloat().coerceIn(0f, 1f),
                    { "${String.format("%.1f", 150f * it * it)} px" }) { width = (150f * it * it).coerceAtLeast(0.25f); apply() })
                box.addView(Ui.text(this, "Line ends", 12f, Ui.TEXT_DIM).apply { setPadding(0, 6.dp, 0, 2.dp) })
                box.addView(chips(listOf("Flat", "Round", "Square"), listOf(Paint.Cap.BUTT, Paint.Cap.ROUND, Paint.Cap.SQUARE).indexOf(cap)) {
                    cap = listOf(Paint.Cap.BUTT, Paint.Cap.ROUND, Paint.Cap.SQUARE)[it]; apply(); rebuild()
                })
                box.addView(Ui.text(this, "Corners", 12f, Ui.TEXT_DIM).apply { setPadding(0, 6.dp, 0, 2.dp) })
                box.addView(chips(listOf("Sharp", "Round", "Bevel"), listOf(Paint.Join.MITER, Paint.Join.ROUND, Paint.Join.BEVEL).indexOf(join)) {
                    join = listOf(Paint.Join.MITER, Paint.Join.ROUND, Paint.Join.BEVEL)[it]; apply(); rebuild()
                })
                box.addView(chips(listOf("Solid line", "Dashed"), if (dashed) 1 else 0) { dashed = it == 1; apply(); rebuild() },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
            }
            if (targets.isNotEmpty()) {
                box.addView(Ui.sliderRow(this, "Opacity", opacity, { "${(it * 100).roundToInt()}%" }) { opacity = it.coerceAtLeast(0.02f); apply() },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })
            }
        }
        rebuild()

        popup?.dismiss()
        val pw = PopupWindow(scroll, 360.dp, min(600.dp, root.height - 120.dp), true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        pw.elevation = 16f.dp
        pw.setOnDismissListener { ed.endStyle(); refreshAll() }
        popup = pw
        pw.animationStyle = android.R.style.Animation_Dialog
        pw.showAtLocation(root, Gravity.RIGHT or Gravity.CENTER_VERTICAL, 16.dp, 0)
    }

    /** a color picker popup next to [anchor] */
    private fun pickColor(anchor: View, initial: Int, onPick: (Int) -> Unit) {
        val panel = ColorPanel(this, initial, recentColors) { c -> onPick(c) }
        val pw = PopupWindow(panel, 340.dp, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        pw.elevation = 20f.dp
        pw.animationStyle = android.R.style.Animation_Dialog
        pw.showAtLocation(root, Gravity.CENTER, 0, 0)
    }

    private fun addVectorLayer() {
        val doc = cv.doc
        if (doc.layers.size >= doc.maxLayers()) { toast("Layer limit reached"); return }
        val layer = doc.createLayer()
        layer.name = "Vector ${doc.layers.count { it.vector != null } + 1}"
        layer.vector = VectorData()
        val cmd = AddLayerCmd(layer, doc.activeIndex + 1, doc.activeIndex)
        cmd.redo(doc)
        cv.pushCommand(cmd)
        cv.vectorEditor.reset()
        cv.vectorEditor.tool = VTool.PEN
        refreshAll()
        hud.show("Vector layer: draw with the Pen, shapes or Pencil")
    }

    private fun exportSvg() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/svg+xml"
            putExtra(Intent.EXTRA_TITLE, cv.doc.title.replace(Regex("[^A-Za-z0-9 _-]"), "").ifBlank { "Artwork" } + ".svg")
        }, REQ_SVG)
    }

    private fun writeSvg(uri: Uri) {
        val svg = runCatching { SvgExport.export(cv.doc) }.getOrNull() ?: run { toast("Could not make the SVG"); return }
        thread(name = "inkora-svg") {
            val ok = runCatching { contentResolver.openOutputStream(uri, "wt")?.use { it.write(svg.toByteArray()) } != null }.getOrDefault(false)
            main.post { toast(if (ok) "SVG saved" else "Could not save the SVG") }
        }
    }

    // ---------- Text ----------

    private fun textDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24.dp, 8.dp, 24.dp, 0) }
        val input = EditText(this).apply {
            hint = "Type your text (any language)"
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.addView(input, LinearLayout.LayoutParams(-1, -2))
        var font = TextFont.SANS_BOLD
        val fontBtn = Ui.button(this, "Font: ${font.label}") {}
        fontBtn.setOnClickListener {
            val pm = PopupMenu(this, fontBtn)
            TextFont.values().forEachIndexed { i, f -> pm.menu.add(0, i, i, f.label) }
            pm.setOnMenuItemClickListener { font = TextFont.values()[it.itemId]; fontBtn.text = "Font: ${font.label}"; true }
            L10n.menu(pm.menu); pm.show()
        }
        box.addView(fontBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
        var align = android.text.Layout.Alignment.ALIGN_CENTER
        val alignBtn = Ui.button(this, "Align: Center") {}
        alignBtn.setOnClickListener {
            align = when (align) {
                android.text.Layout.Alignment.ALIGN_CENTER -> android.text.Layout.Alignment.ALIGN_NORMAL
                android.text.Layout.Alignment.ALIGN_NORMAL -> android.text.Layout.Alignment.ALIGN_OPPOSITE
                else -> android.text.Layout.Alignment.ALIGN_CENTER
            }
            alignBtn.text = "Align: " + when (align) {
                android.text.Layout.Alignment.ALIGN_NORMAL -> "Start"
                android.text.Layout.Alignment.ALIGN_OPPOSITE -> "End"
                else -> "Center"
            }
        }
        box.addView(alignBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.dp })
        var sizePx = cv.doc.height / 10f
        box.addView(Ui.sliderRow(this, "Size", 0.3f, { "${(cv.doc.height * (0.02f + it * 0.3f)).roundToInt()} px" }) {
            sizePx = cv.doc.height * (0.02f + it * 0.3f)
        })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Add text"))
            .setView(box)
            .setPositiveButton(L10n.tr("Add")) { _, _ -> addText(input.text.toString(), font, sizePx, align) }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    private fun addText(text: String, font: TextFont, sizePx: Float, align: android.text.Layout.Alignment) {
        val doc = cv.doc
        if (doc.layers.size >= doc.maxLayers()) { toast("Layer limit reached"); return }
        val bmp = TextTool.render(text, font, sizePx, cv.color or 0xFF000000.toInt(), align,
            (doc.width * 0.95f).toInt(), (doc.height * 0.95f).toInt()) ?: return
        val layer = doc.createLayer()
        layer.name = "Text: " + text.trim().take(18)
        Canvas(layer.bitmap).drawBitmap(bmp, (doc.width - bmp.width) / 2f, (doc.height - bmp.height) / 2f, null)
        bmp.recycle()
        val cmd = AddLayerCmd(layer, doc.activeIndex + 1, doc.activeIndex)
        cmd.redo(doc)
        cv.pushCommand(cmd)
        cv.selection = null
        // place it right away
        enterTransform()
    }

    // ---------- Gallery ----------

    /** Home screen. [fromCanvas] = show a "Back to canvas" button. */
    private fun showGallery(fromCanvas: Boolean = true) {
        popup?.dismiss()
        quickMenu.close()
        val unsaved = cv.isDocReady() && !(cv.doc === savedDoc && cv.doc.revision == savedRevision)
        autosave()
        // a big artwork takes a moment to write: say so instead of looking stuck
        if (unsaved) hud.show("Saving…", sticky = true)
        afterSaves {
            if (unsaved) hud.hideSoon()
            if (gallery != null) return@afterSaves
            galleryFromCanvas = fromCanvas
            val g = GalleryView(this, if (cv.isDocReady()) cv.doc.galleryId else null, fromCanvas,
                onOpen = { id -> openFromGallery(id) },
                onNew = { newCanvasDialog() },
                onImport = { anchor ->
                    val pm = PopupMenu(this, anchor)
                    pm.menu.add(0, 1, 1, "Photo as a new artwork…")
                    pm.menu.add(0, 2, 2, "Inkora project or Photoshop file…")
                    pm.setOnMenuItemClickListener {
                        if (it.itemId == 1) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                        }, REQ_PHOTO_NEW)
                        else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                        }, REQ_OPEN)
                        true
                    }
                    L10n.menu(pm.menu); pm.show()
                },
                onRename = { e -> renameDialog(e.title) { t ->
                    Gallery.rename(this, e.id, t)
                    if (e.id == cv.doc.galleryId) cv.doc.title = t
                    gallery?.refresh()
                } },
                onDuplicate = { e -> Gallery.duplicate(this, e.id); gallery?.refresh() },
                onDelete = { e -> confirmDeleteArtwork(e) },
                onClose = { closeGallery() })
            gallery = g
            topBar.visibility = View.GONE
            sideBar.visibility = View.GONE
            dock.visibility = View.GONE
            pinned.values.forEach { it.visibility = View.GONE }
            root.addView(g, FrameLayout.LayoutParams(-1, -1))
            g.refresh()
        }
    }

    private var galleryFromCanvas = true

    private fun showTips() {
        if (root.findViewWithTag<View>("tips") != null) return
        val tips = TipsOverlay(this) { v ->
            root.removeView(v)
            prefs.edit().putBoolean("tips_done", true).apply()
        }
        tips.tag = "tips"
        root.addView(tips, FrameLayout.LayoutParams(-1, -1))
    }

    private fun closeGallery() {
        val wasOpen = gallery != null
        gallery?.let { root.removeView(it) }
        gallery = null
        if (wasOpen && !prefs.getBoolean("tips_done", false)) root.post { showTips() }
        if (!focusMode) {
            topBar.visibility = View.VISIBLE
            // the size and opacity sliders stay hidden when they were turned off in Prefs
            sideBar.visibility = if (prefs.getBoolean("sidebar", true)) View.VISIBLE else View.GONE
            dock.visibility = View.VISIBLE
            pinned.values.forEach { it.visibility = View.VISIBLE }
        }
    }

    private fun openFromGallery(id: String) {
        if (id == cv.doc.galleryId) { closeGallery(); return }
        val d = Gallery.load(this, id)
        if (d == null) { toast("Could not open this artwork"); return }
        openDocument(d)
        savedDoc = d
        savedRevision = d.revision
        closeGallery()
    }

    private fun confirmDeleteArtwork(e: Gallery.Entry) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Delete \"${e.title}\"?"))
            .setMessage(L10n.tr("The artwork and its time-lapse will be deleted. This can't be undone."))
            .setPositiveButton(L10n.tr("Delete")) { _, _ ->
                val wasCurrent = e.id == cv.doc.galleryId
                Gallery.delete(this, e.id)
                if (wasCurrent) {
                    // keep working on a fresh canvas that is not saved yet
                    val (w, h) = screenSize()
                    val d = newDocument(w, h)
                    openDocument(d)
                    savedDoc = d
                    savedRevision = d.revision
                    gallery?.let { root.removeView(it) }
                    gallery = null
                    showGallery()
                } else gallery?.refresh()
            }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    private fun renameDialog(current: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply { setText(current); setSelectAllOnFocus(true); setSingleLine() }
        val box = LinearLayout(this).apply { setPadding(24.dp, 8.dp, 24.dp, 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Name"))
            .setView(box)
            .setPositiveButton(L10n.tr("OK")) { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onDone) }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    // ---------- Time-lapse export ----------

    private fun exportTimeLapse() {
        val id = cv.doc.galleryId ?: return
        val count = TimeLapse.frameCount(this, id)
        if (count < 2) {
            toast("Nothing recorded yet for this artwork. Draw a bit first!")
            return
        }
        hud.show("Making video… 0%", sticky = true)
        val title = cv.doc.title
        thread(name = "inkora-timelapse") {
            val uri = runCatching {
                TimeLapse.export(applicationContext, id, title) { p ->
                    main.post { hud.show("Making video… ${(p * 100).roundToInt()}%", sticky = true) }
                }
            }.getOrNull()
            main.post {
                hud.hideSoon()
                if (uri == null) {
                    toast("Could not make the video on this device")
                    return@post
                }
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(L10n.tr("Time-lapse saved"))
                    .setMessage(L10n.tr("$count steps · saved to Movies/Inkora"))
                    .setPositiveButton(L10n.tr("Share")) { _, _ ->
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share time-lapse"))
                    }
                    .setNegativeButton(L10n.tr("OK"), null)
                    .show()
            }
        }
    }

    override fun onHistoryChanged() {
        if (!::undoBtn.isInitialized || !cv.isDocReady()) return
        undoBtn.isEnabled = cv.history.canUndo
        redoBtn.isEnabled = cv.history.canRedo
        undoBtn.invalidate()
        redoBtn.invalidate()
        if (::layerName.isInitialized) layerName.text = cv.doc.active.name
        syncVectorMode()
        refreshPins()
        // time-lapse: one frame shortly after each change
        main.removeCallbacks(captureFrame)
        if (timeLapseOn && cv.history.canUndo) main.postDelayed(captureFrame, 450)
    }

    private var lapseDoc: Document? = null
    private var lapseRevision = -1

    private val captureFrame = Runnable {
        if (cv.isDocReady() && !cv.isEditing && adjustKind == null && gallery == null) {
            val d = cv.doc
            // only when the artwork changed since the last frame
            if (d !== lapseDoc || d.revision != lapseRevision) {
                lapseDoc = d; lapseRevision = d.revision
                try { TimeLapse.capture(this, d) } catch (e: Throwable) { /* a frame less */ }
            }
        }
    }

    // =========================================================
    // keyboard shortcuts (a keyboard cover or a Bluetooth keyboard)
    // =========================================================

    private val ctrlHold = Runnable {
        if (root.findViewWithTag<View>("overlay") == null && gallery == null) {
            showShortcuts(closable = false)
            shortcutsHeld = true
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (handleKey(e)) return true
        return super.dispatchKeyEvent(e)
    }

    private fun handleKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        // holding Ctrl by itself for a second shows the list of shortcuts until it is let go
        if (code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT ||
            code == KeyEvent.KEYCODE_META_LEFT || code == KeyEvent.KEYCODE_META_RIGHT) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) main.postDelayed(ctrlHold, 1000)
            if (e.action == KeyEvent.ACTION_UP) {
                main.removeCallbacks(ctrlHold)
                if (shortcutsHeld) { shortcutsHeld = false; closeSettings() }
            }
            return false
        }
        if (e.action != KeyEvent.ACTION_DOWN) return false
        main.removeCallbacks(ctrlHold)
        if (currentFocus is EditText) return false
        if (!cv.isDocReady()) return false
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        val shift = e.isShiftPressed
        val alt = e.isAltPressed
        if (code == KeyEvent.KEYCODE_ESCAPE) {
            when {
                quickMenu.isOpen -> quickMenu.close()
                colorWheel.isOpen -> colorWheel.close()
                popup?.isShowing == true -> popup?.dismiss()
                closeSettings() -> {}
                adjustKind != null -> endAdjust(false)
                cv.transform != null -> leaveTransform(false)
                cv.liquify != null -> leaveLiquify(false)
                cv.editingGuide -> finishGuideEdit()
                else -> cv.fitToScreen() // upright and centered
            }
            return true
        }
        if (gallery != null || root.findViewWithTag<View>("overlay") != null) return false
        // only the first press acts, except keys that make sense held down
        val repeats = code == KeyEvent.KEYCODE_LEFT_BRACKET || code == KeyEvent.KEYCODE_RIGHT_BRACKET ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            (ctrl && (code == KeyEvent.KEYCODE_Z || code == KeyEvent.KEYCODE_Y))
        if (e.repeatCount > 0 && !repeats) return true
        val step = if (shift) 10f else 1f
        when (code) {
            KeyEvent.KEYCODE_Z -> if (ctrl) { if (shift) cv.redo() else cv.undo() } else return false
            KeyEvent.KEYCODE_Y -> if (ctrl) cv.redo() else return false
            KeyEvent.KEYCODE_C -> if (ctrl) cv.copyToClipboard(cut = false) else showColors()
            KeyEvent.KEYCODE_X -> if (ctrl) cv.copyToClipboard(cut = true) else swapColor()
            KeyEvent.KEYCODE_V -> if (ctrl) cv.pasteClipboard() else runTool("transform")
            KeyEvent.KEYCODE_A -> if (ctrl) runTool("sel_all") else return false
            KeyEvent.KEYCODE_D -> runTool("deselect")
            KeyEvent.KEYCODE_J -> cv.duplicateActiveLayer()
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> if (!busy()) cv.clearActiveLayer()
            KeyEvent.KEYCODE_B -> selectTool(Tool.BRUSH)
            KeyEvent.KEYCODE_N -> if (ctrl) { if (!busy()) newCanvasDialog() } else selectTool(Tool.SMUDGE)
            KeyEvent.KEYCODE_E -> if (ctrl) exportImage(jpeg = false, share = false) else selectTool(Tool.ERASER)
            KeyEvent.KEYCODE_M -> cv.armEyedropper()
            KeyEvent.KEYCODE_S -> when {
                ctrl && shift -> if (!busy()) saveProjectAs()
                ctrl -> { autosave(force = true); hud.show("Saved") }
                else -> runTool("sel_free")
            }
            KeyEvent.KEYCODE_F -> runTool("fill")
            KeyEvent.KEYCODE_T -> runTool("text")
            KeyEvent.KEYCODE_U -> runTool("adj_HSB")
            KeyEvent.KEYCODE_K -> if (!busy()) showActions(actionsBtn)
            KeyEvent.KEYCODE_L -> showLayers()
            KeyEvent.KEYCODE_O -> if (ctrl) { if (!busy()) actionsHost.openProjectFile() } else return false
            KeyEvent.KEYCODE_G -> if (ctrl) { cv.greyView = !cv.greyView; hud.show(if (cv.greyView) "Grey view on" else "Grey view off") } else return false
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> if (ctrl) cv.fitToScreen() else setFocus(!focusMode)
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> if (ctrl) cv.zoomViewBy(1.25f) else return false
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> if (ctrl) cv.zoomViewBy(0.8f) else return false
            KeyEvent.KEYCODE_R -> cv.rotateViewBy(if (shift) -15f else 15f)
            KeyEvent.KEYCODE_SLASH -> if (ctrl) showShortcuts() else return false
            KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET -> {
                val by = (if (alt) 0.10f else if (shift) 0.05f else 0.01f) * (if (code == KeyEvent.KEYCODE_LEFT_BRACKET) -1 else 1)
                val v = (Brushes.sliderFromSize(cv.currentBrush.size) + by).coerceIn(0f, 1f)
                cv.currentBrush.size = Brushes.sizeFromSlider(v)
                sizeSlider.value = v
                brushDot.sizePx = cv.currentBrush.size
                showSizeHud(false)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!cv.nudgeTransform(-step, 0f)) return false
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!cv.nudgeTransform(step, 0f)) return false
            KeyEvent.KEYCODE_DPAD_UP -> if (!cv.nudgeTransform(0f, -step)) return false
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!cv.nudgeTransform(0f, step)) return false
            else -> return false
        }
        refreshAll()
        return true
    }

    /** X: the color before this one comes back (and this one goes into the recent list) */
    private fun swapColor() {
        val prev = recentColors.firstOrNull { it != cv.color } ?: return
        ColorPanel.addRecent(recentColors, cv.color)
        cv.color = prev
        colorBtn.color = prev
        if (cv.tool == Tool.ERASER) selectTool(Tool.BRUSH)
    }

    // =========================================================
    // files opened with Inkora (Procreate brushes, .inkora projects, images)
    // =========================================================

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uris.add(it) }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
            else -> return
        }
        // handled once, even if the activity is created again (e.g. a language change)
        intent.action = null
        if (uris.isEmpty()) return
        main.post {
            if (gallery != null) closeGallery()
            val first = uris[0]
            val name = displayName(first)?.lowercase() ?: ""
            val type = contentResolver.getType(first) ?: ""
            when {
                name.endsWith(".inkora") || name.endsWith(".zerin") || name.endsWith(".psd") -> openProject(first)
                type.startsWith("image/") || name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp") ->
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(L10n.tr("What should Inkora do with this image?"))
                        .setItems(arrayOf(L10n.tr("Insert it as a layer"), L10n.tr("Make it a brush"))) { _, which ->
                            if (which == 0) insertImage(first) else importBrushFiles(uris)
                        }
                        .setNegativeButton(L10n.tr("Cancel"), null)
                        .show()
                else -> importBrushFiles(uris)
            }
        }
    }

    override fun onPenLongPress(x: Float, y: Float) {
        if (gallery != null || quickMenu.isOpen || colorWheel.isOpen) return
        popup?.dismiss()
        openQuickMenu(x, y)
    }

    override fun onGesture(purpose: GesturePurpose, x: Float, y: Float) {
        if (quickMenu.isOpen || colorWheel.isOpen) return
        runGesture(purpose, x, y)
    }

    override fun onBrushAdjusted() {
        val b = cv.currentBrush
        sizeSlider.value = Brushes.sliderFromSize(b.size)
        opacitySlider.value = b.opacity
        brushDot.sizePx = b.size
        brushDot.opacity = b.opacity
        opacityLabel.text = "${(b.opacity * 100).roundToInt()}%"
        hud.show("Size ${b.size.roundToInt()} px · Opacity ${(b.opacity * 100).roundToInt()}%", sticky = false)
    }

    override fun onLayerPicked() {
        refreshAll()
        onHistoryChanged()
    }

    override fun onSelectionChanged() {
        if (::selectBtn.isInitialized) refreshAll()
    }

    override fun onColorPicked(color: Int) {
        cv.color = color
        colorBtn.color = color
        ColorPanel.addRecent(recentColors, color)
    }

    override fun onMessage(text: String) {
        hud.show(text)
    }

    companion object {
        /** how often the artwork is saved while you work */
        private const val SAVE_EVERY_MS = 150_000L
        private const val REQ_OPEN = 101
        private const val REQ_SAVE = 102
        private const val REQ_IMAGE = 103
        private const val REQ_BRUSH = 104
        private const val REQ_PHOTO_NEW = 105
        private const val REQ_SVG = 106
        private const val REQ_REF_IMAGE = 109
        private const val REQ_BRUSH_EXPORT = 110
        private const val REQ_BRUSH_IMAGE = 111
        private const val REQ_EXPORT_FILE = 112
        private const val REQ_SHADOW_IMAGE = 113
        private const val REQ_MODEL = 114
        private const val REQ_PALETTE_PHOTO = 115
    }
}
