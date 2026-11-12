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
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity(), CanvasView.Listener {

    private lateinit var root: FrameLayout
    private lateinit var cv: CanvasView
    private lateinit var hud: HudView
    private lateinit var brushBtn: IconView
    private lateinit var eraserBtn: IconView
    private lateinit var layersBtn: View
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
    private lateinit var dock: ToolRail
    private lateinit var optionsBar: OptionsBar
    private lateinit var menuBtn: View
    private lateinit var addBtn: View
    private lateinit var adjustMenuBtn: View
    private lateinit var canvasBtn: View
    private lateinit var brushesBtn: View
    private val barButtons = ArrayList<View>()
    private var popupOwner: View? = null
    private lateinit var titleView: android.widget.TextView
    /** under the title: the canvas size and the number of layers */
    private lateinit var docInfo: android.widget.TextView
    private lateinit var docIsland: LinearLayout
    private lateinit var panelsIsland: LinearLayout
    private lateinit var homeChevron: IconView
    private lateinit var focusBtn: View
    /** where the tool bar goes in the top row when it is "on top" */
    private lateinit var toolsSlot: FrameLayout
    private lateinit var colorPair: ColorPairView
    private lateinit var handPaint: List<IconView>
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
        // after a crash the last artwork is not opened behind the gallery at once: if it is what
        // made the app close, the app still starts (it opens when you pick it)
        val crashedLastTime = crashFile().exists()
        installCrashReporter()
        // 0.41.0 kept PDF books here; that bookshelf is gone, so are its copies
        java.io.File(filesDir, "books").takeIf { it.exists() }?.let { d -> thread(name = "inkora-cleanup") { d.deleteRecursively() } }
        Ui.light = prefs.getBoolean("light_ui", false)
        Ui.accent = prefs.getInt("accent", 0)
        Fonts.init(resources)
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
        window.setBackgroundDrawable(ColorDrawable(Ui.CANVAS_BG))
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
        restoreEraser()
        cv.penHoldMs = prefs.getLong("penHoldMs", 600L)
        cv.showBrushCursor = prefs.getBoolean("brushCursor", true)
        // the test switches count only in test builds (they are not shown in the Google Play version)
        val devBuild = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        cv.gpuTiles = devBuild && prefs.getBoolean("gpu_tiles", false)
        if (devBuild && prefs.getBoolean("perf_numbers", false)) root.post { setPerf(true) }
        // off unless asked for: the watercolor look is there while you draw, nothing changes on pen-up
        cv.watercolorDrying = prefs.getBoolean("watercolorDrying", false)
        cv.wetBleed = prefs.getBoolean("wetBleed", true)
        cv.measureUnit = runCatching { MeasureUnit.valueOf(prefs.getString("measure_unit", "PX")!!) }.getOrDefault(MeasureUnit.PX)
        if (!prefs.getBoolean("sidebar", true)) sideBar.visibility = View.GONE
        cv.pressureGamma = prefs.getFloat("pressureGamma", 1f)
        timeLapseOn = prefs.getBoolean("timelapse", true)
        cv.fillThreshold = prefs.getFloat("fillThreshold", cv.fillThreshold)
        cv.fillGap = prefs.getInt("fillGap", 0)
        cv.selectionShade = prefs.getFloat("selectionShade", cv.selectionShade)
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
        val doc = if (crashedLastTime) null else lastId?.let { Gallery.load(this, it) }
            ?: Gallery.list(this).firstOrNull()?.let { Gallery.load(this, it.id) }
        if (crashedLastTime) {
            // an empty canvas that is not kept unless you draw on it
            val (w, h) = screenSize()
            val blank = newDocument(w, h)
            openDocument(blank)
            savedDoc = blank
            savedRevision = blank.revision
        } else if (doc != null) {
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
        // the first time Inkora opens: the tour of what it can do (it can be skipped, and it is
        // always there again under Actions › Help)
        if (!prefs.getBoolean("tour_done", false)) root.postDelayed({ if (!isFinishing) showTips() }, 600)
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
        loadReference()
        refreshAll()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            quickMenu.isOpen -> quickMenu.close()
            root.findViewWithTag<View>("tips") != null -> root.findViewWithTag<View>("tips")?.let { root.removeView(it); prefs.edit().putBoolean("tour_done", true).apply() }
            root.findViewWithTag<View>("overlay") != null -> closeSettings()
            focusMode -> setFocus(false)
            colorWheel.isOpen -> colorWheel.close()
            adjustKind != null -> endAdjust(false)
            cv.transform != null -> leaveTransform(false)
            cv.isDocReady() && cv.liquify != null -> leaveLiquify(false)
            cv.editingGuide -> finishGuideEdit()
            cv.interaction == Interaction.SELECT -> { cv.interaction = Interaction.PAINT; hideBottomBar(); refreshAll() }
            cv.interaction == Interaction.GRADIENT -> leaveGradient()
            cv.interaction == Interaction.MEASURE -> leaveMeasure()
            cv.interaction == Interaction.SHAPE -> leaveShapes()
            cv.recolorMode -> leaveRecolor()
            gallery?.previewOpen() == true -> gallery?.closePreview()
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

    /**
     * Every file picker, save dialog and share sheet opens through here: a device without an app
     * for it (files app turned off, say) gets a message instead of a crash.
     */
    @Deprecated("Activity API")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        try {
            @Suppress("DEPRECATION")
            super.startActivityForResult(intent, requestCode, options)
        } catch (e: android.content.ActivityNotFoundException) {
            toast("No app on this device can do that")
        }
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(periodicSave)
        autosave()
        saveEraser()
    }

    /** the eraser you used last (with its size and opacity) is the one you start with next time */
    private fun saveEraser() {
        if (!::cv.isInitialized || !prefs.getBoolean("remember_eraser", true)) return
        val b = cv.eraserBrush
        prefs.edit().putString("eraser_last", b.preset.name).putFloat("eraser_size", b.size).putFloat("eraser_opacity", b.opacity).apply()
    }

    private fun restoreEraser() {
        if (!prefs.getBoolean("remember_eraser", true)) return
        val name = prefs.getString("eraser_last", null) ?: return
        val b = eraserBrushes.firstOrNull { it.preset.name == name } ?: return
        b.size = prefs.getFloat("eraser_size", b.size)
        b.opacity = prefs.getFloat("eraser_opacity", b.opacity).coerceIn(0.01f, 1f)
        cv.eraserBrush = b
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
        // the window's real size decides the phone layout (turning the phone, split screen, a folded screen)
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val d = resources.displayMetrics.density
            val w = ((r - l) / d).toInt(); val h = ((b - t) / d).toInt()
            if (w > 0 && h > 0 && (w != Screen.wDp || h != Screen.hDp)) {
                val turned = Screen.wDp > 0
                Screen.wDp = w; Screen.hDp = h
                if (::sideBar.isInitialized) root.post {
                    if (turned) popup?.dismiss()
                    applyScreenSize()
                    // the gallery is laid out for the other way round: build it again
                    if (turned && gallery != null) { val fromCanvas = galleryFromCanvas; gallery?.let { root.removeView(it) }; gallery = null; showGallery(fromCanvas) }
                }
            }
        }
        cv = CanvasView(this)
        cv.listener = this
        root.addView(cv, FrameLayout.LayoutParams(-1, -1))

        hud = HudView(this)
        // above the bars, so a message is never hidden behind them
        hud.elevation = 30f.dp
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))

        dockIds = ToolCatalog.decode(prefs.getString("dock", null))
        leftHanded = prefs.getBoolean("leftHanded", false)

        // ---------- the islands at the top ----------
        //  [‹ Gallery | title · size · layers | focus ⋯]  [tools ... ⋯]            [Brushes Layers (Color)]
        // The row itself is see-through: only the islands in it cover the canvas.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 0, 12.dp, 0)
            clipChildren = false
            clipToPadding = false
        }
        topBar = bar

        // DOCUMENT island: the gallery, the artwork's name, focus mode and Actions
        val docIsland = island()
        this.docIsland = docIsland
        val homeBtn = Ui.text(this, "Gallery", 14f, bold = false).apply {
            contentDescription = L10n.tr("Gallery")
            typeface = Fonts.get(500)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6.dp, 0, 12.dp, 0)
            compoundDrawablePadding = 2.dp
            foreground = Ui.ripple(12f)
            setOnClickListener { if (!busy()) showGallery() }
        }
        homeChevron = IconView(this, if (L10n.isRtl) Icon.CHEV_RIGHT else Icon.CHEV_LEFT).apply { isClickable = false; iconScale = 0.5f }
        val home = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            foreground = Ui.ripple(12f)
            setOnClickListener { if (!busy()) showGallery() }
        }
        homeBtn.isClickable = false; homeBtn.foreground = null
        home.addView(homeChevron, LinearLayout.LayoutParams(28.dp, 44.dp))
        home.addView(homeBtn, LinearLayout.LayoutParams(-2, 44.dp))
        HoverTip.attach(home) { L10n.tr("Gallery") to TipTexts.byName["Gallery"] }
        docIsland.addView(home, LinearLayout.LayoutParams(-2, 44.dp))
        this.homeBtn = homeBtn
        docIsland.addView(View(this).apply { setBackgroundColor(Ui.DIVIDER) }, LinearLayout.LayoutParams(1.dp, 24.dp))

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 0, 8.dp, 0)
            isClickable = true
            foreground = Ui.ripple(12f)
            contentDescription = L10n.tr("Rename artwork")
            setOnClickListener {
                if (cv.isDocReady() && !busy()) renameDialog(cv.doc.title) { t ->
                    cv.doc.title = t; cv.doc.revision++; autosave(force = true); refreshAll()
                }
            }
        }
        titleView = Ui.text(this, "", 13.5f, Ui.TEXT, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = false
        }
        docInfo = Ui.text(this, "", 11f, Ui.TEXT_DIM).apply { maxLines = 1; isClickable = false }
        titles.addView(titleView)
        titles.addView(docInfo)
        docIsland.addView(titles, LinearLayout.LayoutParams(-2, 44.dp).apply { weight = 1f })
        focusBtn = islandButton(Icon.FOCUS, "Focus mode") { if (!busy()) setFocus(true) }
        docIsland.addView(focusBtn)
        actionsBtn = islandButton(Icon.MORE, "Actions") { v -> if (!busy()) showActions(v) }
        docIsland.addView(actionsBtn)
        bar.addView(docIsland, LinearLayout.LayoutParams(-2, 52.dp))
        // older code paths open Add, Canvas and Adjust from these: they all live under ⋯ (and Adjust on the tool bar)
        menuBtn = actionsBtn; addBtn = actionsBtn; canvasBtn = actionsBtn; adjustMenuBtn = actionsBtn

        // TOOLS island: the tool bar sits here when it is "on top" (see applySides)
        toolsSlot = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        bar.addView(toolsSlot, LinearLayout.LayoutParams(0, 52.dp, 1f).apply { marginStart = 12.dp; marginEnd = 12.dp })

        // the tools themselves live in the tool bar now; these keep their state for older code paths
        creativeBtn = View(this); editingBtn = View(this); moreBtn = View(this)
        adjustBtn = IconView(this, Icon.ADJUST)
        selectBtn = IconView(this, Icon.SELECT)
        transformBtn = IconView(this, Icon.TRANSFORM)
        symmetryBtn = IconView(this, Icon.SYMMETRY)
        guideBtn = IconView(this, Icon.GRID)
        brushBtn = IconView(this, Icon.BRUSH)
        smudgeBtn = IconView(this, Icon.SMUDGE)
        eraserBtn = IconView(this, Icon.ERASER)
        layerName = Ui.text(this, "", 9f, Ui.TEXT_DIM)

        // PANELS island: brushes, layers, color
        val panelsIsland = island()
        this.panelsIsland = panelsIsland
        brushesBtn = islandButton(Icon.BRUSHES, "Brushes") { if (!busy()) showBrushes(cv.tool) }
        layersBtn = islandButton(Icon.LAYERS, "Layers") { if (!busy()) showLayers() }
        panelsIsland.addView(brushesBtn)
        panelsIsland.addView(layersBtn)
        colorBtn = ColorSwatchView(this)
        colorBtn.contentDescription = L10n.tr("Color")
        setupColorButton()
        HoverTip.attach(colorBtn) { L10n.tr("Color") to TipTexts.byName["Color"] }
        panelsIsland.addView(colorBtn, LinearLayout.LayoutParams(48.dp, 44.dp))
        bar.addView(panelsIsland, LinearLayout.LayoutParams(-2, 52.dp))

        root.addView(bar, FrameLayout.LayoutParams(-1, TOP_H.dp, Gravity.TOP).apply { topMargin = ISLAND_GAP.dp })

        // ---------- side bar: size, eyedropper, opacity, undo, redo ----------
        // HAND island: what the free hand reaches for every few seconds
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(6.dp, 8.dp, 6.dp, 8.dp)
            background = Ui.rounded(Ui.BAR, 20f, Ui.OUTLINE, 1)
            elevation = 8f.dp
        }
        sideBar = side
        // brush, smudge, eraser (here while the tool bar is on top; else they are on the tool bar)
        handPaint = listOf("brush" to Icon.BRUSH, "smudge" to Icon.SMUDGE, "eraser" to Icon.ERASER).map { (id, ic) ->
            IconView(this, ic).apply {
                val def = RailTools.paint[id]
                contentDescription = L10n.tr(def?.label ?: id)
                tip = def?.hint?.let { L10n.tr(it) }
                tag = id
                setOnClickListener { v -> railPick(id, v, isToolActive(id)) }
            }.also { side.addView(it, LinearLayout.LayoutParams(48.dp, 44.dp)) }
        }
        side.addView(View(this).apply { setBackgroundColor(Ui.DIVIDER); tag = "handDivider" }, LinearLayout.LayoutParams(28.dp, 1.dp).apply { topMargin = 6.dp; bottomMargin = 6.dp })
        // the brush preview is kept for older code paths; the brush buttons above stand for it
        brushDot = BrushDotView(this).apply { setOnClickListener { if (!busy()) showBrushes(cv.tool) } }
        sizeSlider = VerticalSlider(this)
        sizeSlider.onChange = { v ->
            cv.currentBrush.size = Brushes.sizeFromSlider(v)
            brushDot.sizePx = cv.currentBrush.size
            showSizeHud(true)
        }
        sizeSlider.onDragState = { dragging -> if (dragging) showSizeHud(true) else hud.hideSoon() }
        sliderPins(sizeSlider, "size_pins")
        sizeSlider.onExact = { exactValue(true) }
        sizeSlider.contentDescription = L10n.tr("Brush size")
        HoverTip.attach(sizeSlider) { L10n.tr("Brush size") to TipTexts.byName["Brush size"] }
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
        sliderPins(opacitySlider, "opacity_pins")
        opacitySlider.onExact = { exactValue(false) }
        opacitySlider.contentDescription = L10n.tr("Brush opacity")
        HoverTip.attach(opacitySlider) { L10n.tr("Brush opacity") to TipTexts.byName["Brush opacity"] }
        side.addView(opacitySlider, LinearLayout.LayoutParams(44.dp, 180.dp))
        opacityLabel = Ui.text(this, "100%", 10f, Ui.TEXT_DIM, true).apply { gravity = Gravity.CENTER }
        side.addView(opacityLabel, LinearLayout.LayoutParams(-1, -2))

        side.addView(Ui.divider(this), LinearLayout.LayoutParams(28.dp, 1.dp).apply { topMargin = 8.dp; bottomMargin = 4.dp })
        // the color in hand and the one before it: tap to go back
        colorPair = ColorPairView(this).apply {
            contentDescription = L10n.tr("Color and previous color")
            setOnClickListener { swapColor(); refreshAll() }
        }
        HoverTip.attach(colorPair) { L10n.tr("Color and previous color") to L10n.tr("Tap to go back to the previous color") }
        side.addView(colorPair, LinearLayout.LayoutParams(44.dp, 44.dp))
        undoBtn = IconView(this, Icon.UNDO).apply { contentDescription = "Undo" }
        undoBtn.setOnClickListener { cv.undo() }
        // hold: the whole history as a line to drag along
        undoBtn.setOnLongClickListener { v -> if (cv.isDocReady() && !busy()) HistoryScrub.show(v, cv) {}; true }
        side.addView(undoBtn, LinearLayout.LayoutParams(44.dp, 44.dp))
        redoBtn = IconView(this, Icon.REDO).apply { contentDescription = "Redo" }
        redoBtn.setOnClickListener { cv.redo() }
        redoBtn.setOnLongClickListener { v -> if (cv.isDocReady() && !busy()) HistoryScrub.show(v, cv) {}; true }
        side.addView(redoBtn, LinearLayout.LayoutParams(44.dp, 44.dp))
        root.addView(side, FrameLayout.LayoutParams(-2, -2))

        // ---------- the tool bar and the bar of the current tool's settings ----------
        dock = ToolRail(this, prefs, object : ToolRail.Host {
            override fun pick(id: String, anchor: View, again: Boolean) = railPick(id, anchor, again)
            override fun isActive(id: String) = isToolActive(id)
            override fun layoutChanged() = applySides()
            override fun side() = railSide()
            override fun setSide(side: String) = setRailSide(side)
        })
        root.addView(dock, FrameLayout.LayoutParams(-2, -2))
        optionsBar = OptionsBar(this)
        root.addView(optionsBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        applySides()
        applyScreenSize()

        // focus mode: a single small button brings the interface back
        focusExit = IconView(this, Icon.FOCUS).apply {
            background = Ui.rounded(Ui.BAR, 14f, Ui.OUTLINE, 1)
            elevation = 8f.dp
            contentDescription = L10n.tr("Show interface")
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
            topMargin = (STRIP_Y + 64).dp
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

    /** "1 layer", "4 layers" in the interface language */
    private fun layerCount(n: Int): String = when {
        n == 1 -> L10n.tr("1 layer")
        L10n.lang == L10n.AR && n > 10 -> L10n.tr("%d layers (11 and more)").replace("%d", n.toString())
        else -> L10n.tr("%d layers").replace("%d", n.toString())
    }

    /** a rounded group of buttons over the canvas */
    private fun island() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(4.dp, 0, 4.dp, 0)
        background = Ui.rounded(Ui.BAR, 18f, Ui.OUTLINE, 1)
        elevation = 8f.dp
    }

    /** an icon button of an island; it opens its panel under itself */
    private fun islandButton(icon: Icon, label: String, onClick: (View) -> Unit): IconView {
        val b = IconView(this, icon)
        b.iconScale = 0.5f
        b.tip = TipTexts.byName[label]
        b.contentDescription = L10n.tr(label)
        b.setOnClickListener { onClick(b) }
        b.layoutParams = LinearLayout.LayoutParams(48.dp, 44.dp)
        barButtons.add(b)
        return b
    }

    private fun barDivider() = View(this).apply { setBackgroundColor(Ui.DIVIDER) }.also {
        it.layoutParams = LinearLayout.LayoutParams(1.dp, 24.dp).apply { leftMargin = 6.dp; rightMargin = 4.dp }
    }

    /** a top-bar button: icon over its name (icon only on a phone); it opens its panel under itself */
    private fun barButton(icon: Icon, label: String, onClick: (View) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = label
            isClickable = true
            foreground = Ui.ripple(12f)
        }
        box.setOnClickListener { onClick(box) }
        box.addView(IconView(this, icon).apply { isClickable = false; iconScale = 0.62f }, LinearLayout.LayoutParams(28.dp, 28.dp))
        box.addView(Ui.text(this, label, 10.5f, Ui.TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 1; tag = "label" },
            LinearLayout.LayoutParams(-2, -2))
        HoverTip.attach(box) { label to TipTexts.byName[label] }
        box.layoutParams = LinearLayout.LayoutParams(76.dp, 52.dp)
        barButtons.add(box)
        return box
    }

    /** phones: Actions, Add, Adjust and Canvas share one ☰ button */
    private fun showPhoneMenu(anchor: View) {
        val m = InkMenu(this, anchor)
        m.menu.add(0, 1, 1, "Actions").icon(Icon.WRENCH)
        m.menu.add(0, 2, 2, "Add").icon(Icon.PLUS)
        m.menu.add(0, 3, 3, "Adjust").icon(Icon.ADJUST).isEnabled = adjustKind == null
        m.menu.add(0, 4, 4, "Canvas").icon(Icon.GRID)
        L10n.menu(m.menu)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showActions(anchor)
                2 -> showAddPanel(anchor)
                3 -> showAdjustPanel(anchor)
                4 -> showCanvasPanel(anchor)
            }
            true
        }
        m.show()
    }

    // Add and Canvas are tabs of Actions (the ⋯ of the document island)
    private fun showAddPanel(anchor: View) = showActions(anchor, tab = ActionsPanel.Tab.ADD)

    private fun showCanvasPanel(anchor: View) = showActions(anchor, tab = ActionsPanel.Tab.CANVAS)

    private fun showAdjustPanel(anchor: View) {
        if (!cv.isDocReady()) return
        showPanel(anchor, ToolsPanel(this, false, toolsHost, adjustOnly = true), 600, 640)
    }

    /** the first time a tool is picked, one line says what it does (never again after that) */
    private fun firstUseHint(id: String) {
        if (id in setOf("brush", "smudge", "eraser")) return
        val key = "hint_seen_$id"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        val def = RailTools.def(id) ?: return
        if (def.hint.isBlank()) return
        root.postDelayed({ hud.show(L10n.tr(def.label) + ": " + L10n.tr(def.hint)) }, 350)
    }

    /** a tool picked on the tool bar: paint tools open their brushes when tapped again */
    private fun railPick(id: String, anchor: View, again: Boolean) {
        if (busy()) return
        firstUseHint(id)
        val paint = when (id) { "brush" -> Tool.BRUSH; "smudge" -> Tool.SMUDGE; "eraser" -> Tool.ERASER; else -> null }
        if (paint != null) {
            if (again) showBrushes(paint) else selectTool(paint)
            return
        }
        runTool(id, anchor)
    }

    private fun railSide(): String = when (prefs.getString("dock_edge", null)) { "Right" -> "Right"; else -> "Left" }

    private fun setRailSide(side: String) {
        val sl = if (side == "Left") "Right" else "Left"
        prefs.edit().putString("dock_edge", side).putString("sliders_edge", sl).putBoolean("leftHanded", sl == "Right").apply()
        leftHanded = sl == "Right"
        applySides()
    }

    /** the name and icon of what the settings bar is for */
    private fun optionsTitle(): Pair<Icon, String> {
        if (bottomBar != null && bottomBar === vectorBar) return Icon.VECTOR to "Vector"
        adjustKind?.let { return Icon.ADJUST to it.label }
        if (cv.liquify != null) return Icon.LIQUIFY to "Liquify"
        return when (cv.interaction) {
            Interaction.SELECT -> Icon.SELECT to "Selection"
            Interaction.TRANSFORM -> Icon.TRANSFORM to "Transform"
            Interaction.GRADIENT -> Icon.GRADIENT to "Gradient"
            Interaction.MEASURE -> Icon.MEASURE to "Measure"
            Interaction.SHAPE -> Icon.SHAPE to "Shapes"
            else -> when {
                cv.recolorMode -> Icon.RECOLOR to "Recolor"
                cv.fillMode -> Icon.FILL to "Fill"
                cv.tool == Tool.SMUDGE -> Icon.SMUDGE to "Smudge"
                cv.tool == Tool.ERASER -> Icon.ERASER to "Eraser"
                else -> Icon.BRUSH to "Brush"
            }
        }
    }

    /** the settings bar when no tool has its own: the brush, its size and opacity */
    /** a value in the settings bar is being dragged: don't rebuild the bar under the finger */
    private var scrubbing = false

    private fun paintOptions(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        /**
         * [scrub]: drag sideways on the chip to change its value (0..1, from where it was); a tap
         * still does [onClick]. Returns the value's text view, to update while dragging.
         */
        fun chip(label: String, value: String, scrub: ((Float) -> String)? = null, start: Float = 0f, onClick: () -> Unit): android.widget.TextView {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10.dp, 0, 10.dp, 0)
                background = Ui.rounded(Ui.SUNKEN, 9f)
                foreground = Ui.ripple(9f)
                isClickable = true
                setOnClickListener { onClick() }
            }
            if (label.isNotEmpty()) c.addView(Ui.text(this, label, 12f, Ui.TEXT_DIM).apply { setPadding(0, 0, 6.dp, 0) })
            val tv = Ui.text(this, value, 13f, Ui.TEXT, true)
            c.addView(tv)
            if (scrub != null) {
                c.addView(Ui.text(this, "‹ ›", 11f, Ui.TEXT_DIM).apply { setPadding(6.dp, 0, 0, 0) })
                val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                var downX = 0f; var dragging = false
                c.setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = e.rawX; dragging = false; v.isPressed = true
                            // the bar must not scroll away while a value is dragged
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = e.rawX - downX
                            if (!dragging && kotlin.math.abs(dx) > slop) {
                                dragging = true; scrubbing = true
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            // a full drag of 300dp goes from one end to the other
                            if (dragging) tv.text = scrub((start + dx / 300f.dp).coerceIn(0f, 1f))
                        }
                        MotionEvent.ACTION_UP -> {
                            v.isPressed = false
                            // rebuilding the bar now would remove this very view while it handles the touch
                            if (dragging) { dragging = false; scrubbing = false; v.post { refreshAll() } } else onClick()
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            if (dragging) { dragging = false; scrubbing = false; v.post { refreshAll() } }
                        }
                    }
                    true
                }
            }
            row.addView(c, LinearLayout.LayoutParams(-2, 32.dp).apply { marginEnd = 8.dp })
            return tv
        }
        if (cv.fillMode) {
            // the numbers live in Settings › Fill and ColorDrop; here they show, and a tap opens them
            chip("Close gaps", if (cv.fillGap == 0) L10n.tr("Off") else "${cv.fillGap} px") { showActions(actionsBtn, page = "fill") }
            chip("Sensitivity", "${(cv.fillThreshold * 100).roundToInt()}%") { showActions(actionsBtn, page = "fill") }
            row.addView(Ui.text(this, "Tap an area to fill it with the color", 13f, Ui.TEXT_DIM).apply { setPadding(4.dp, 0, 0, 0) })
            return row
        }
        chip("", cv.currentBrush.preset.name + "  ▾") { showBrushes(cv.tool) }
        // drag sideways on Size or Opacity to change it; tap for an exact number
        chip("Size", "${(Brushes.sliderFromSize(cv.currentBrush.size) * 100).roundToInt()}%",
            scrub = { v ->
                cv.currentBrush.size = Brushes.sizeFromSlider(v)
                sizeSlider.value = v
                "${(v * 100).roundToInt()}%"
            }, start = Brushes.sliderFromSize(cv.currentBrush.size)) { exactValue(true) }
        chip("Opacity", "${(cv.currentBrush.opacity * 100).roundToInt()}%",
            scrub = { v ->
                val o = v.coerceIn(0.01f, 1f)
                cv.currentBrush.opacity = o
                opacitySlider.value = o
                "${(o * 100).roundToInt()}%"
            }, start = cv.currentBrush.opacity) { exactValue(false) }
        return row
    }

    private fun updateOptions() {
        if (!::optionsBar.isInitialized || !cv.isDocReady()) return
        val (ic, name) = optionsTitle()
        if (bottomBar == null) { if (!scrubbing) optionsBar.show(ic, name, paintOptions(), false) }
        else optionsBar.setTitle(ic, name)
        val hide = focusMode || gallery != null || (dock.mode == ToolRail.FLOAT && bottomBar == null && !narrow)
        optionsBar.visibility = if (hide) View.GONE else View.VISIBLE
    }

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
    /** the marks on a slider are remembered */
    private fun sliderPins(s: VerticalSlider, key: String) {
        s.onPins = { added ->
            prefs.edit().putString(key + "_" + pinsBrush, s.pins.joinToString(",")).apply()
            hud.show(if (added) "Marked · tap the mark to come back to it" else "Mark removed")
        }
    }

    /** the brush whose marks the sliders show: every brush keeps its own */
    private var pinsBrush = ""

    private fun loadSliderPins() {
        val key = cv.tool.name + ":" + cv.currentBrush.preset.id
        if (key == pinsBrush) return
        pinsBrush = key
        for ((s, k) in listOf(sizeSlider to "size_pins", opacitySlider to "opacity_pins")) {
            s.pins.clear()
            prefs.getString(k + "_" + key, "")!!.split(',').mapNotNull { it.toFloatOrNull() }.forEach { s.pins.add(it.coerceIn(0f, 1f)) }
            s.invalidate()
        }
    }

    /** holding a slider's handle: type the exact size (px) or opacity (%) */
    private fun exactValue(size: Boolean) {
        val b = cv.currentBrush
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (size) (if (b.size < 10) String.format(java.util.Locale.US, "%.1f", b.size) else b.size.roundToInt().toString())
                else (b.opacity * 100).roundToInt().toString())
            selectAll()
        }
        val box = FrameLayout(this).apply { setPadding(24.dp, 8.dp, 24.dp, 0); addView(input) }
        val d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr(if (size) "Size (px)" else "Opacity (%)"))
            .setView(box)
            .setPositiveButton(L10n.tr("OK")) { _, _ ->
                val v = input.text.toString().replace(',', '.').toFloatOrNull() ?: return@setPositiveButton
                if (size) b.size = v.coerceIn(Brushes.MIN_SIZE, Brushes.MAX_SIZE)
                else b.opacity = (v / 100f).coerceIn(0.01f, 1f)
                refreshAll()
                if (size) showSizeHud(false) else hud.show("Opacity ${(b.opacity * 100).roundToInt()}%")
            }
            .setNegativeButton(L10n.tr("Cancel"), null).create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        input.requestFocus()
    }

    private lateinit var homeBtn: android.widget.TextView

    private lateinit var moreBtn: View

    /** Phones: one slim row on top, the tools along the bottom; shorter sliders when the screen is low. */
    private fun applyScreenSize() {
        val n = narrow
        // a phone: the chevron alone stands for the gallery; no size line under the title
        homeBtn.visibility = if (n) View.GONE else View.VISIBLE
        docInfo.visibility = if (n) View.GONE else View.VISIBLE
        focusBtn.visibility = if (n) View.GONE else View.VISIBLE
        titleView.maxWidth = if (n) Int.MAX_VALUE else 240.dp
        (docIsland.layoutParams as LinearLayout.LayoutParams).apply { width = if (n) 0 else -2; weight = if (n) 1f else 0f }
        (toolsSlot.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (n) 8.dp else 0; weight = if (n) 0f else 1f
            marginStart = if (n) 0 else 12.dp; marginEnd = if (n) 0 else 12.dp
        }
        topBar.setPadding(if (n) 8.dp else 12.dp, 0, if (n) 8.dp else 12.dp, 0)
        topBar.requestLayout()
        if (::dock.isInitialized) applySides()
        sizeHandIsland()
    }

    /** the sliders of the hand island take what height there is */
    private fun sizeHandIsland() {
        if (!::dock.isInitialized) return
        val n = narrow
        val h = if (Screen.hDp > 0) Screen.hDp else resources.configuration.screenHeightDp
        val paintHere = dock.paintElsewhere
        for (b in handPaint) b.visibility = if (paintHere) View.VISIBLE else View.GONE
        sideBar.findViewWithTag<View>("handDivider")?.visibility = if (paintHere) View.VISIBLE else View.GONE
        val lp = sideBar.layoutParams as FrameLayout.LayoutParams
        val avail = h - (lp.topMargin + lp.bottomMargin) / resources.displayMetrics.density
        var fixed = 16 + 52 + 16 + 13 + 44 + 88 + (if (paintHere) 145 else 0)
        val tight = avail - fixed < 2 * 90
        opacityLabel.visibility = if (tight) View.GONE else View.VISIBLE
        colorPair.visibility = if (tight && !n) View.GONE else View.VISIBLE
        if (tight) fixed -= 16 + 44
        val sh = ((avail - fixed) / 2).toInt().coerceIn(56, 180)
        (sizeSlider.layoutParams as LinearLayout.LayoutParams).height = sh.dp
        (opacitySlider.layoutParams as LinearLayout.LayoutParams).height = sh.dp
        sideBar.requestLayout()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::sideBar.isInitialized) return
        // turning the phone: panels were sized for the other way round
        popup?.dismiss()
        applyScreenSize()
    }

    private fun applySides() {
        val side = railSide()
        val n = narrow
        dock.horizontal = n
        val top = !n && dock.mode == ToolRail.TOP
        // the tool bar goes into the top row when it is "on top", else straight on the screen
        val wantParent: ViewGroup = if (top) toolsSlot else root
        if (dock.parent !== wantParent) {
            (dock.parent as? ViewGroup)?.removeView(dock)
            if (top) wantParent.addView(dock, FrameLayout.LayoutParams(-2, -1, Gravity.START or Gravity.CENTER_VERTICAL))
            else root.addView(dock, root.indexOfChild(optionsBar).coerceAtLeast(0), FrameLayout.LayoutParams(-2, -2))
        }
        val gap = ISLAND_GAP
        val railW = when {
            n || top -> 0
            dock.mode == ToolRail.FLOAT -> 0
            dock.mode == ToolRail.WIDE -> ToolRail.WIDE_W
            else -> ToolRail.DOCK_W
        }
        if (!top) (dock.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = 0; rightMargin = 0; topMargin = 0; bottomMargin = 0
            when {
                n -> { width = -1; height = ToolRail.PHONE_H.dp; gravity = Gravity.BOTTOM; leftMargin = 8.dp; rightMargin = 8.dp; bottomMargin = 8.dp }
                dock.mode == ToolRail.FLOAT -> {
                    width = -2; height = -2; gravity = Gravity.TOP or Gravity.LEFT
                    val dm = resources.displayMetrics
                    leftMargin = prefs.getInt("rail_float_x", if (side == "Left") 16.dp else dm.widthPixels - 86.dp).coerceIn(0, (dm.widthPixels - 70.dp).coerceAtLeast(0))
                    topMargin = prefs.getInt("rail_float_y", 130.dp).coerceIn(STRIP_Y.dp, (dm.heightPixels - 200.dp).coerceAtLeast(STRIP_Y.dp))
                }
                else -> { width = railW.dp; height = -1; gravity = (if (side == "Left") Gravity.LEFT else Gravity.RIGHT) or Gravity.TOP; topMargin = STRIP_Y.dp }
            }
            dock.layoutParams = this
        }
        // the settings strip: a floating island under the top row (a phone: over the tool bar)
        (optionsBar.layoutParams as FrameLayout.LayoutParams).apply {
            if (n) {
                width = -1; gravity = Gravity.BOTTOM; topMargin = 0
                bottomMargin = (ToolRail.PHONE_H + 8 + 8).dp; leftMargin = 8.dp; rightMargin = 8.dp
            } else {
                width = -2; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = STRIP_Y.dp; bottomMargin = 0
                leftMargin = (if (side == "Left") railW else 0).dp + gap.dp
                rightMargin = (if (side == "Right") railW else 0).dp + gap.dp
            }
            optionsBar.layoutParams = this
        }
        // the hand island: on the chosen side when the tool bar is on top, else across from the tool bar
        val handSide = if (top || n) (if (n) slidersEdge() else side) else slidersEdge()
        (sideBar.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = (if (handSide == "Right") Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
            val besideRail = !n && !top && dock.mode != ToolRail.FLOAT && handSide == side
            leftMargin = gap.dp + (if (besideRail && handSide == "Left") railW.dp else 0)
            rightMargin = gap.dp + (if (besideRail && handSide == "Right") railW.dp else 0)
            topMargin = if (n) (TOP_H + gap * 2).dp else (STRIP_Y + 52 + gap).dp
            bottomMargin = if (n) (ToolRail.PHONE_H + 8 + 8 + 56).dp else gap.dp
            sideBar.layoutParams = this
        }
        sizeHandIsland()
        updateOptions()
    }

    // ---------- speed numbers (Settings › Advanced) ----------

    private var perfView: android.widget.TextView? = null
    private val perfTick = object : Runnable {
        override fun run() {
            if (!Perf.on) return
            perfView?.text = Perf.take(0.5f)
            main.postDelayed(this, 500)
        }
    }

    private fun setPerf(on: Boolean) {
        Perf.on = on
        main.removeCallbacks(perfTick)
        perfView?.let { root.removeView(it) }
        perfView = null
        if (!on) return
        val v = android.widget.TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(10.dp, 6.dp, 10.dp, 6.dp)
            background = Ui.rounded(0xCC000000.toInt(), 10f)
            elevation = 40f.dp
            isClickable = false; isFocusable = false
        }
        root.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 12.dp; leftMargin = 12.dp; rightMargin = 12.dp })
        perfView = v
        Perf.take(1f)
        main.postDelayed(perfTick, 500)
    }

    // ---------- the islands step back while you draw ----------

    private val islandsOut = Runnable { islandsAlpha(0.3f) }
    private val islandsBack = Runnable { islandsAlpha(1f) }

    private fun islandsAlpha(a: Float) {
        if (!::dock.isInitialized) return
        for (v in listOf(topBar, sideBar, dock, optionsBar)) {
            if (v.alpha != a) v.animate().alpha(a).setDuration(if (a < 1f) 200 else 150).start()
        }
    }

    /** 300 ms into a stroke the islands fade; they come back 700 ms after the pen lifts (not between strokes) */
    private fun fadeIslands(drawing: Boolean) {
        if (!::dock.isInitialized) return
        root.removeCallbacks(islandsOut); root.removeCallbacks(islandsBack)
        if (drawing) { if (prefs.getBoolean("fade_islands", true)) root.postDelayed(islandsOut, 300) }
        else root.postDelayed(islandsBack, 700)
    }

    /** closes a full-screen overlay (gesture controls, keyboard shortcuts) */
    /** a screen opened from Settings (gesture controls, the guide…): closing it goes back to Settings */
    private var settingsBehind = false
    /** a settings row is opening something right now (set just before, cleared just after) */
    private var leavingSettingsNow = false
    /** the settings page to come back to ("" or null: the first one) */
    private var settingsPage: String? = null
    /** where the settings popup opened last (the gear in the Gallery, or ⋯) */
    private var settingsAnchor: View? = null

    private fun closeSettings(): Boolean {
        val v = root.findViewWithTag<View>("overlay") ?: return false
        // the Brush Studio saves its changes on the way out (it calls back here to be removed)
        if (v is BrushStudioView && !v.closing) { v.finish(); return true }
        if (v is Scene3DEditor && !v.closing) { v.finish(); return true }
        root.removeView(v)
        if (settingsBehind) {
            settingsBehind = false
            val back = settingsPage
            root.post { if (root.findViewWithTag<View>("overlay") == null) openSettingsPopup(null, back) }
        }
        return true
    }

    private fun showOverlay(v: View) {
        // opened from Settings, or from a screen that was: closing this one goes back to Settings
        val keep = settingsBehind || leavingSettingsNow
        leavingSettingsNow = false
        settingsBehind = false
        closeSettings()
        settingsBehind = keep
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

    /** the sliders sit on the side the tool bar is not on */
    private fun slidersEdge() = if (railSide() == "Left") "Right" else "Left"

    private fun dockEdge(): String = railSide()

    private fun setFocus(on: Boolean) {
        focusMode = on
        val v = if (on) View.GONE else View.VISIBLE
        topBar.visibility = v
        if (on) { root.removeCallbacks(islandsOut); root.removeCallbacks(islandsBack); islandsAlpha(1f) }
        sideBar.visibility = if (on || !prefs.getBoolean("sidebar", true)) View.GONE else View.VISIBLE
        dock.visibility = v
        dock.dismissEditor()
        updateOptions()
        focusExit.visibility = if (on) View.VISIBLE else View.GONE
        if (on) hud.show("Focus mode · tap the corner button to bring the tools back")
    }

    /** runs a tool from the toolbar or the Creative / Editing panel */
    private fun runTool(id: String, anchor: View? = null) {
        if (!cv.isDocReady()) return
        // looking around is always fine; "liquify" itself is how you leave liquify
        val free = id in setOf("fit", "flip", "focus", "actual_size", "grey_view") || (id == "liquify" && cv.liquify != null)
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
            id == "recolor" -> if (cv.recolorMode) leaveRecolor() else enterRecolor()
            id == "fill" -> {
                if (cv.recolorMode) leaveRecolor()
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
            id == "comic" -> panelsDialog()
            id == "bubble" -> bubbleDialog()
            id == "snapshots" -> showSnapshots()
            id == "grey_view" -> { cv.greyView = !cv.greyView; cv.invalidate(); hud.show(if (cv.greyView) "Grey view on" else "Grey view off") }
            id == "liquify" -> if (cv.liquify != null) leaveLiquify(true) else enterLiquify()
            id == "gradient" -> if (cv.interaction == Interaction.GRADIENT) leaveGradient() else enterGradient()
            id == "measure" -> if (cv.interaction == Interaction.MEASURE) leaveMeasure() else enterMeasure()
            id == "shapes" -> if (cv.interaction == Interaction.SHAPE) leaveShapes() else enterShapes()
            id == "palette_photo" -> { popup?.dismiss(); startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, REQ_PALETTE_PHOTO) }
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
            id == "adjust" -> if (adjustKind == null) showAdjustPanel(anchor ?: actionsBtn)
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
        val painting = cv.interaction == Interaction.PAINT && !cv.fillMode && !cv.recolorMode
        return when (id) {
            "brush" -> painting && cv.tool == Tool.BRUSH
            "smudge" -> painting && cv.tool == Tool.SMUDGE
            "eraser" -> painting && cv.tool == Tool.ERASER
            "sel_free" -> sel && cv.selectShape == SelectShape.FREEHAND
            "sel_rect" -> sel && cv.selectShape == SelectShape.RECTANGLE
            "sel_ellipse" -> sel && cv.selectShape == SelectShape.ELLIPSE
            "sel_wand" -> sel && cv.selectShape == SelectShape.WAND
            "sel_color" -> sel && cv.selectShape == SelectShape.COLOR
            "sel_brush" -> sel && cv.selectShape == SelectShape.BRUSH
            "transform" -> cv.interaction == Interaction.TRANSFORM
            "liquify" -> cv.liquify != null
            "gradient" -> cv.interaction == Interaction.GRADIENT
            "measure" -> cv.interaction == Interaction.MEASURE
            "shapes" -> cv.interaction == Interaction.SHAPE
            "fill" -> cv.fillMode
            "recolor" -> cv.recolorMode
            "grey_view" -> cv.greyView
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
            background = Ui.rounded(Ui.PANEL, 22f, Ui.OUTLINE, 1)
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
        while (recentTools.size > 7) recentTools.removeAt(recentTools.size - 1)
        BrushPanel.noteRecent(this, entry.second.preset.id)
    }

    /**
     * Holding Brush, Smudge or Eraser: that tool takes the brush you have in hand right now, with
     * the same size, opacity and settings (erase or smudge with the brush you were painting with).
     */
    private fun carryBrush(target: Tool) {
        if (busy()) return
        val from = cv.tool
        val src = cv.currentBrush
        if (from == target) { showBrushes(target); return }
        val nb = brushListFor(target).firstOrNull { it.preset === src.preset }
        if (nb == null) { selectTool(target); return }
        BrushSettings.apply(nb, BrushSettings.toJson(src))
        nb.size = src.size
        nb.opacity = src.opacity
        when (target) {
            Tool.ERASER -> cv.eraserBrush = nb
            Tool.SMUDGE -> cv.smudgeBrush = nb
            Tool.BRUSH -> cv.paintBrush = nb
        }
        selectTool(target)
        hud.show(L10n.tr("Same brush") + " · " + L10n.tr(toolPrefix(target).removeSuffix(" · ").ifEmpty { "Brush" }) + " · " + nb.preset.name)
    }

    /** a brush's settings go back to how it came (the brush library's Reset) */
    private fun resetBrush(tool: Tool, b: Brush) {
        b.resetSettings()
        if (tool == Tool.BRUSH) { b.size = b.preset.defaultSize; b.opacity = b.preset.defaultOpacity }
        BrushSettings.forget(prefs, tool, b)
        BrushPanel.forget(b.preset.id)
        refreshAll()
        hud.show("Brush reset")
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
        // your pinned brushes first (for the tool in hand), then the last ones used; the brush in
        // hand is not repeated. The last bubble opens the whole library.
        val pinnedIds = BrushPanel.pinnedIds(this)
        val pinnedNow = brushListFor(cv.tool).filter { it.preset.id in pinnedIds && it !== current.second }.map { cv.tool to it }
        val recent = recentTools.filter { !(it.first == current.first && it.second === current.second) }
        val picks = (pinnedNow + recent).distinctBy { it.first.name + ":" + it.second.preset.id }.take(4)
        val brushes = picks.map { (tool, b) ->
            QuickMenuView.Item(toolPrefix(tool) + b.preset.name, preview = BrushPanel.preview(b)) { useBrush(tool, b) }
        } + QuickMenuView.Item("Brush Library", Icon.BRUSH) { if (!busy()) showBrushes(cv.tool) }
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
        if (cv.interaction == Interaction.GRADIENT) leaveGradient()
        if (cv.interaction == Interaction.MEASURE) leaveMeasure()
        if (cv.interaction == Interaction.SHAPE) leaveShapes()
        if (cv.recolorMode) leaveRecolor()
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
        loadSliderPins()
        if (cv.fillMode) { brushBtn.active = false; smudgeBtn.active = false; eraserBtn.active = false }
        if (::dock.isInitialized) dock.refresh()
        updateOptions()
        if (::titleView.isInitialized && cv.isDocReady()) {
            titleView.text = cv.doc.title
            docInfo.text = "${cv.doc.width} × ${cv.doc.height} · " + layerCount(cv.doc.layers.size)
            if (::colorPair.isInitialized) {
                colorPair.color = cv.color
                colorPair.previous = recentColors.firstOrNull { it != cv.color }
                for (b in handPaint) b.active = isToolActive(b.tag as String)
            }
        }
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
        var held = false
        // hold the color: back to the color before it (and hold again to go back)
        val hold = Runnable {
            if (!dragging) {
                held = true
                swapColor()
                colorBtn.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                hud.show("Previous color")
                refreshAll()
            }
        }
        colorBtn.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false; held = false
                    colorBtn.postDelayed(hold, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > 12f.dp) colorBtn.removeCallbacks(hold)
                    if (!dragging && !held && hypot(e.rawX - downX, e.rawY - downY) > 24f.dp) {
                        dragging = true
                        dropDot.color = cv.color
                        dropDot.visibility = View.VISIBLE
                    }
                    if (dragging) moveDropDot(e.rawX, e.rawY)
                }
                MotionEvent.ACTION_UP -> {
                    colorBtn.removeCallbacks(hold)
                    if (dragging) {
                        dropDot.visibility = View.GONE
                        val loc = IntArray(2)
                        cv.getLocationOnScreen(loc)
                        cv.colorDrop(e.rawX - loc[0], e.rawY - loc[1], cv.color)
                    } else if (!held) showColors()
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    colorBtn.removeCallbacks(hold)
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

    /**
     * Every panel opens the same way: right under the button that opened it, with a small arrow
     * pointing at it; tapping that button again closes it. On a phone it is a sheet from the bottom.
     */
    private fun showPanel(anchor: View, content: View, widthDp: Int, heightDp: Int?, alignLeft: Boolean = false) {
        if (popup?.isShowing == true && popupOwner === anchor) { popup?.dismiss(); return }
        popup?.dismiss()
        val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
        val loc = IntArray(2); anchor.getLocationInWindow(loc)
        val pw: PopupWindow
        if (narrow) {
            val sheet = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Ui.PANEL)
                    val r = 22f.dp
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
                clipToOutline = true
            }
            sheet.addView(View(this).apply { background = Ui.rounded(Ui.TEXT_DIM, 3f); alpha = 0.6f },
                LinearLayout.LayoutParams(40.dp, 5.dp).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 8.dp; bottomMargin = 4.dp })
            val maxH = (root.height * 0.85f).toInt()
            val h = if (heightDp == null) ViewGroup.LayoutParams.WRAP_CONTENT else min(heightDp.dp, maxH)
            sheet.addView(content, if (heightDp == null) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(-1, 0, 1f))
            pw = PopupWindow(sheet, root.width, h, true)
            pw.animationStyle = android.R.style.Animation_InputMethod
            pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            pw.isOutsideTouchable = true
            pw.elevation = 18f.dp
            popup = pw
            pw.showAtLocation(root, Gravity.BOTTOM or Gravity.LEFT, rootLoc[0], 0)
        } else {
            val maxH = root.height - 80.dp
            val w = min(widthDp.dp, root.width - 16.dp)
            val h = if (heightDp == null) ViewGroup.LayoutParams.WRAP_CONTENT else min(heightDp.dp, maxH)
            val ax = loc[0] - rootLoc[0]
            val center = ax + anchor.width / 2
            // left half of the screen: the panel starts at its button; right half: it ends there
            val alignStart = center < root.width / 2
            val x = (if (alignStart) ax else ax + anchor.width - w).coerceIn(8.dp, (root.width - w - 8.dp).coerceAtLeast(8.dp))
            val wrap = FrameLayout(this)
            val caret = View(this).apply {
                background = Ui.rounded(Ui.PANEL, 2f, Ui.OUTLINE, 1)
                rotation = 45f
            }
            wrap.addView(caret, FrameLayout.LayoutParams(14.dp, 14.dp).apply {
                leftMargin = (center - x - 7.dp).coerceIn(14.dp, (w - 28.dp).coerceAtLeast(14.dp)); topMargin = 1.dp
            })
            wrap.addView(content, FrameLayout.LayoutParams(-1, if (heightDp == null) -2 else -1).apply { topMargin = 7.dp })
            pw = PopupWindow(wrap, w, if (heightDp == null) ViewGroup.LayoutParams.WRAP_CONTENT else h + 7.dp, true)
            pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            pw.isOutsideTouchable = true
            pw.elevation = 16f.dp
            pw.animationStyle = android.R.style.Animation_Dialog
            popup = pw
            pw.showAtLocation(root, Gravity.TOP or Gravity.LEFT, x + rootLoc[0], loc[1] + anchor.height + 1.dp)
        }
        popupOwner = anchor
        if (anchor in barButtons) setTabActive(anchor, true)
        pw.setOnDismissListener {
            if (anchor in barButtons) setTabActive(anchor, false)
            if (popupOwner === anchor) popupOwner = null
            refreshAll()
        }
    }

    private fun brushListFor(t: Tool) = when (t) {
        Tool.ERASER -> eraserBrushes
        Tool.SMUDGE -> smudgeBrushes
        Tool.BRUSH -> paintBrushes
    }

    private fun showBrushes(tool: Tool) {
        showPanel(brushesBtn, brushPanelFor(tool, pinned = false), 580, 700)
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
                    Tool.ERASER -> { cv.eraserBrush = b; saveEraser() }
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
            onExportSet = { set, list -> popup?.dismiss(); exportBrushSet(set, list) },
            onDuplicate = { b -> popup?.dismiss(); duplicateBrush(tool, b) },
            onReset = { b -> resetBrush(tool, b) })
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
            "layers" -> LayersPanel(this, cv, { cv.color }, onAddVector = { addVectorLayer() }, onPin = null, onEdit3D = { open3D(it) }, onEditAdjust = { editAdjustLayer(it) }, onOpacityDrag = { layerOpacityDrag(it) }, onCopyToArtwork = { copyLayerToArtwork(it) }) { onHistoryChanged() }
            "reference" -> ReferenceWindow(this, onImport = {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                }, REQ_REF_IMAGE)
            }, onRemove = {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(L10n.tr("Remove the reference photo?"))
                    .setPositiveButton(L10n.tr("Remove")) { _, _ ->
                        referenceWindow()?.setImage(null)
                        cv.doc.galleryId?.let { id -> saver.execute { Gallery.saveRef(applicationContext, id, null) } }
                    }
                    .setNegativeButton(L10n.tr("Cancel"), null).show()
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
            "color" -> min(340.dp, root.width - 16.dp)
            else -> min(320.dp, root.width - 16.dp)
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
        if (kind == "reference") loadReference()
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

    private fun referenceWindow(): ReferenceWindow? {
        val p = pinned["reference"] ?: return null
        for (i in 0 until p.childCount) (p.getChildAt(i) as? ReferenceWindow)?.let { return it }
        return null
    }

    /** the reference window shows the photo that was saved with the artwork on the canvas */
    private fun loadReference() {
        val w = referenceWindow() ?: return
        val id = cv.doc.galleryId
        w.setImage(null)
        if (id == null) return
        saver.execute {
            val bmp = Gallery.loadRef(applicationContext, id)
            main.post { if (bmp != null && cv.isDocReady() && cv.doc.galleryId == id) referenceWindow()?.setImage(bmp) }
        }
    }

    // ---------- Snapshots ----------

    private fun saveSnapshot(auto: Boolean, then: (Boolean) -> Unit) {
        val doc = cv.doc
        val id = doc.galleryId ?: Gallery.newId().also { doc.galleryId = it }
        val thumb = try { Gallery.makeThumb(doc) } catch (e: OutOfMemoryError) { null }
        val snap = try { ProjectIO.snapshot(doc) } catch (e: OutOfMemoryError) { null }
        if (snap == null) { thumb?.recycle(); toast("Not enough memory for this right now"); then(false); return }
        hud.show("Saving…", sticky = true)
        saver.execute {
            val ok = runCatching { Gallery.saveSnapshot(applicationContext, id, snap, thumb, auto) }.isSuccess
            thumb?.recycle()
            snap.layers.forEach { it.bitmap.recycle() }
            main.post { hud.hideSoon(); then(ok) }
        }
    }

    private fun showSnapshots() {
        if (!cv.isDocReady() || busy()) return
        val id = cv.doc.galleryId ?: Gallery.newId().also { cv.doc.galleryId = it }
        val list = Gallery.snapshots(this, id)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp, 8.dp, 16.dp, 8.dp) }
        var dialog: AlertDialog? = null
        col.addView(Ui.text(this, "A snapshot keeps the artwork as it is now. Inkora keeps the newest twelve.", 13f, Ui.TEXT_DIM))
        col.addView(Ui.button(this, "Take a snapshot now") {
            dialog?.dismiss()
            saveSnapshot(false) { ok -> if (ok) { hud.show("Snapshot saved"); showSnapshots() } else toast("Could not save the snapshot") }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp; bottomMargin = 6.dp })
        if (list.isEmpty()) col.addView(Ui.text(this, "No snapshots yet", 14f, Ui.TEXT_DIM).apply { gravity = Gravity.CENTER; setPadding(0, 18.dp, 0, 18.dp) })
        val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        for (s in list) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 6.dp, 0, 6.dp) }
            val iv = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = Ui.rounded(Ui.SUNKEN, 8f)
                clipToOutline = true
                runCatching { setImageBitmap(android.graphics.BitmapFactory.decodeFile(s.thumb.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })) }
            }
            row.addView(iv, LinearLayout.LayoutParams(64.dp, 64.dp))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(android.widget.TextView(this).apply { text = fmt.format(java.util.Date(s.time)); textSize = 14f; setTextColor(Ui.TEXT) })
            if (s.auto) texts.addView(Ui.text(this, "Made before going back", 12f, Ui.TEXT_DIM))
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 12.dp; rightMargin = 8.dp })
            row.addView(Ui.button(this, "Go back") { dialog?.dismiss(); confirmRestore(s) })
            row.addView(Ui.button(this, "Delete") { Gallery.deleteSnapshot(s); dialog?.dismiss(); showSnapshots() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 6.dp })
            col.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Snapshots"))
            .setView(ScrollView(this).apply { addView(col) })
            .setNegativeButton(L10n.tr("Close"), null).show()
    }

    private fun confirmRestore(s: Gallery.Snap) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Go back to this snapshot?"))
            .setMessage(L10n.tr("The artwork as it is now is kept as a snapshot first, so you can return. Undo history starts fresh."))
            .setPositiveButton(L10n.tr("Go back")) { _, _ ->
                saveSnapshot(true) { ok ->
                    if (!ok) { toast("Could not save the snapshot"); return@saveSnapshot }
                    val old = cv.doc
                    val d = try { Gallery.loadSnapshot(s) } catch (e: OutOfMemoryError) { null }
                    if (d == null) { toast("Could not open that snapshot"); return@saveSnapshot }
                    d.galleryId = old.galleryId; d.title = old.title; d.origin = old.origin
                    openDocument(d)
                    autosave(force = true)
                    hud.show("Back at the snapshot")
                }
            }
            .setNegativeButton(L10n.tr("Cancel"), null).show()
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
        fadeIslands(drawing)
        pinned.values.forEach { it.setDrawing(drawing) }
    }

    // ---------- brush import ----------

    private fun importLinkDialog() {
        val input = EditText(this).apply {
            hint = "https://… (.brush, .brushset, .abr, .zip or .png)"
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
                    val bytes = contentResolver.openInputStream(uri)?.use { BrushImport.readLimited(it) } ?: return@runCatching
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

    /** the selected part of the layer becomes a stamp brush in "My Brushes", ready in the brush */
    private fun brushFromSelection() {
        val pic = cv.selectedPixels() ?: run { hud.show("Select something first"); return }
        val n = paintBrushes.count { it.preset.category == "My Brushes" && it.preset.name.startsWith(L10n.tr("Stamp")) } + 1
        val size = maxOf(pic.width, pic.height).toFloat().coerceIn(Brushes.MIN_SIZE, Brushes.MAX_SIZE)
        val p = runCatching { BrushImport.saveStamp(this, pic, L10n.tr("Stamp") + " $n", "My Brushes", size) }.getOrNull()
        pic.recycle()
        if (p == null) { hud.show("Nothing to make a brush from there"); return }
        val made = HashMap<Tool, Brush>()
        for (t in Tool.entries) {
            val nb = Brush(p)
            if (t == Tool.ERASER) nb.size = 40f
            else if (t == Tool.SMUDGE) { nb.size = 50f; nb.opacity = 0.75f }
            brushListFor(t).add(nb)
            made[t] = nb
        }
        cv.selection = null
        cv.interaction = Interaction.PAINT
        hideBottomBar()
        useBrush(Tool.BRUSH, made[Tool.BRUSH]!!)
        refreshAll()
        hud.show(L10n.tr("New brush in My Brushes") + " · " + p.name)
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
            onEdit3D = { popup?.dismiss(); open3D(it) }, onEditAdjust = { popup?.dismiss(); editAdjustLayer(it) }, onOpacityDrag = { popup?.dismiss(); layerOpacityDrag(it) },
            onCopyToArtwork = { popup?.dismiss(); copyLayerToArtwork(it) }) { onHistoryChanged() }
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
            },
            onEyedropper = { popup?.dismiss(); cv.armEyedropper() }) { c ->
            cv.color = c
            colorBtn.color = c
            if (recolor) cv.vectorEditor.recolorLive(c)
        }
        colorPanel = panel
        // a grab strip on top: drag it and the color panel becomes a floating window
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(colorGrabber(), LinearLayout.LayoutParams(-1, 26.dp))
        holder.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(-1, 0, 1f))
        showPanel(colorBtn, holder, 360, 846)
        if (recolor) popup?.setOnDismissListener { cv.vectorEditor.endStyle(); refreshAll() }
    }

    private fun showGuide(anchor: View? = null) {
        lastGuideAnchor = anchor
        showBeside(anchor, guideContent(), 360, 620)
    }

    /** the drawing guide's settings (in their own window, or as a page of Actions › Canvas) */
    private fun guideContent(): View {
        val g = cv.doc.guide
        return GuidePanel(this, g,
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
    }

    private var lastGuideAnchor: View? = null

    private fun finishGuideEdit() {
        cv.editingGuide = false
        doneBtn.visibility = View.GONE
        showGuide(lastGuideAnchor)
    }

    /** symmetry modes as little pictures */
    private fun showSymmetry(anchor: View?) {
        showBeside(anchor, symmetryContent { popup?.dismiss() }, 340, null)
    }

    /** the kinds of symmetry to pick from; [picked] runs after one is picked */
    private fun symmetryContent(picked: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            background = Ui.rounded(Ui.PANEL, 18f, Ui.OUTLINE, 1)
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
                setOnClickListener { cv.symmetry = sym; picked(); refreshAll(); hud.show("Symmetry: ${sym.label}") }
            }
            cell.addView(SymmetryPreview(this, sym, on), LinearLayout.LayoutParams(-1, 64.dp))
            cell.addView(Ui.text(this, sym.label, 12f, if (on) Ui.ACCENT else Ui.TEXT))
            grid.addView(cell, android.widget.GridLayout.LayoutParams().apply { width = 96.dp; setMargins(3.dp, 3.dp, 3.dp, 3.dp) })
        }
        box.addView(grid)
        return box
    }

    /** Settings, a whole screen (from Actions, the Gallery, or a tool's "settings" chip on a [page]) */
    /**
     * The settings: from ⋯ they are the Settings tab of Actions; elsewhere (the Gallery, no artwork
     * open) a popup of their own. [page]: a section ("sec:brushes") or a page ("fill") to open on.
     */
    private fun openSettingsPopup(anchor: View?, page: String? = null) {
        popup?.dismiss()
        val a = anchor ?: settingsAnchor?.takeIf { it.isAttachedToWindow } ?: actionsBtn
        settingsAnchor = a
        val panel = if (a === actionsBtn && cv.isDocReady()) ActionsPanel(this, actionsHost)
            else ActionsPanel(this, actionsHost, ActionsPanel.MODE_PREFS)
        panel.openPage(page)
        showPanel(a, panel, 460, 680, alignLeft = a === actionsBtn)
    }

    private fun showActions(anchor: View, page: String? = null, tab: ActionsPanel.Tab? = null) {
        if (page != null) { openSettingsPopup(anchor, page); return }
        if (!cv.isDocReady()) return
        val panel = ActionsPanel(this, actionsHost)
        if (tab != null) panel.openTab(tab)
        showPanel(anchor, panel, 460, 680, alignLeft = true)
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
        override fun insertPdfPage() = pickPdf(asArtwork = false)
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
        override fun editGuide() = showGuide(canvasBtn)
        override fun showSymmetry() = showSymmetry(canvasBtn)
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
            set(v) { prefs.edit().putBoolean("sidebar", v).apply(); sideBar.visibility = if (v && !focusMode) View.VISIBLE else View.GONE; dock.rebuild(); applySides() }
        override var watercolorDrying: Boolean
            get() = cv.watercolorDrying
            set(v) { cv.watercolorDrying = v; prefs.edit().putBoolean("watercolorDrying", v).apply() }
        override var lightInterface: Boolean
            get() = Ui.light
            set(v) {
                if (v == Ui.light) return
                prefs.edit().putBoolean("light_ui", v).apply()
                popup?.dismiss()
                // the whole screen is built again in the new colors; the artwork is saved first
                autosave(force = true)
                afterSaves { Ui.light = v; recreate() }
            }
        override var perfNumbers: Boolean
            get() = Perf.on
            set(v) { prefs.edit().putBoolean("perf_numbers", v).apply(); setPerf(v) }
        override var gpuTiles: Boolean
            get() = cv.gpuTiles
            set(v) { cv.gpuTiles = v; prefs.edit().putBoolean("gpu_tiles", v).apply() }
        override var fadeIslands: Boolean
            get() = prefs.getBoolean("fade_islands", true)
            set(v) { prefs.edit().putBoolean("fade_islands", v).apply(); if (!v) { root.removeCallbacks(islandsOut); islandsAlpha(1f) } }
        override fun useWorkspace(w: Workspaces.Workspace) {
            Workspaces.apply(prefs, w)
            dock.reload()
            hud.show(L10n.tr("Workspace") + " · " + L10n.tr(w.name))
        }
        override fun saveWorkspace() {
            Workspaces.saveMine(prefs)
            hud.show("Saved as your workspace")
        }
        override var accent: Int
            get() = Ui.accent
            set(v) {
                if (v == Ui.accent) return
                prefs.edit().putInt("accent", v).apply()
                popup?.dismiss()
                autosave(force = true)
                afterSaves { Ui.accent = v; recreate() }
            }
        override var wetBleed: Boolean
            get() = cv.wetBleed
            set(v) { cv.wetBleed = v; prefs.edit().putBoolean("wetBleed", v).apply() }
        override var rememberEraser: Boolean
            get() = prefs.getBoolean("remember_eraser", true)
            set(v) { prefs.edit().putBoolean("remember_eraser", v).apply(); if (v) saveEraser() }
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
        override fun exportPicture(jpeg: Boolean, share: Boolean, transparent: Boolean, scale: Float) {
            popup?.dismiss(); exportImage(jpeg, share, transparent, scale)
        }
        override fun exportTimeLapse() = this@MainActivity.exportTimeLapse()
        override fun dockEdge() = this@MainActivity.dockEdge()
        override fun slidersEdge() = this@MainActivity.slidersEdge()
        override fun setEdges(dock: String, sliders: String) = setRailSide(dock)
        override var railMode: String
            get() = this@MainActivity.dock.mode
            set(v) { this@MainActivity.dock.setMode(v) }
        override fun runTool(id: String) { this@MainActivity.runTool(id, canvasBtn) }
        override fun openSettings(page: String?) = openSettingsPopup(null, page)
        override fun leavingSettings(page: String?) {
            settingsPage = page
            leavingSettingsNow = true
            // nothing opened over the canvas (a dialog, the language): forget it again
            root.post { leavingSettingsNow = false }
        }
        override fun guideView(): View = guideContent()
        override fun symmetryView(picked: () -> Unit): View = symmetryContent(picked)
        override fun setLanguage(code: String) {
            // save the artwork, then start again in the new language
            autosave(force = true)
            L10n.save(this@MainActivity, code)
            afterSaves { recreate() }
        }
        override fun openGestureControls() { popup?.dismiss(); showGestureControls() }
        override fun openShortcuts() { popup?.dismiss(); showShortcuts() }
        override fun showTips() { popup?.dismiss(); this@MainActivity.showTips() }
        override fun openGuide() { popup?.dismiss(); showGuidePage() }
        override fun openDemos() { popup?.dismiss(); showDemos() }
        override fun openSnapshots() { popup?.dismiss(); showSnapshots() }
        override fun backupAll() { popup?.dismiss(); backupDialog() }
        override fun restoreBackup() { popup?.dismiss(); pickBackup() }
        override fun backupNote(): String = backupNoteText()
        override fun comicPanels() { popup?.dismiss(); panelsDialog() }
        override fun speechBubble() { popup?.dismiss(); bubbleDialog() }
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

    /** [transparent]: PNG without the background color; [scale]: 1 = full size */
    private fun exportImage(jpeg: Boolean, share: Boolean, transparent: Boolean = false, scale: Float = 1f) {
        if (busy()) return
        val full = try { cv.doc.flatten(withBackground = jpeg || !transparent, forceOpaque = jpeg) } catch (e: OutOfMemoryError) { null }
        if (full == null) { toast("Not enough memory to export it"); return }
        val bmp = if (scale >= 0.999f) full else runCatching {
            Bitmap.createScaledBitmap(full, (full.width * scale).roundToInt().coerceAtLeast(1), (full.height * scale).roundToInt().coerceAtLeast(1), true)
        }.getOrNull()?.also { if (it !== full) full.recycle() } ?: full
        toast("Exporting…")
        thread(name = "inkora-export") {
            val uri = ProjectIO.exportToGallery(applicationContext, bmp, jpeg)
            bmp.recycle()
            main.post {
                if (uri == null) toast("Export failed")
                else if (share) {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = if (jpeg) "image/jpeg" else "image/png"
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
            REQ_BACKUP -> writeBackup(uri)
            REQ_RESTORE -> readBackup(uri)
            REQ_OPEN -> openProject(uri)
            REQ_IMAGE -> insertImage(uri)
            REQ_SHADOW_IMAGE -> insertImage(uri, shadow = true)
            REQ_MODEL -> readModel(uri)
            REQ_PALETTE_PHOTO -> {
                val bmp = runCatching { ProjectIO.decodeImage(this, uri, 1200, 1200) }.getOrNull()
                if (bmp == null) toast("Could not open that image")
                else PaletteMaker.show(this, bmp, "From photo") { colorPanel?.paletteAdded(); refreshAll() }
            }
            REQ_PHOTO_NEW -> photoAsArtwork(uri)
            REQ_PDF -> insertPdfPage(uri)
            REQ_PDF_NEW -> pdfPageAsArtwork(uri)
            REQ_SVG -> writeSvg(uri)
            REQ_BRUSH_EXPORT -> writeBrushFile(uri)
            REQ_EXPORT_FILE -> writeExportFile(uri)
            REQ_BRUSH_IMAGE -> setBrushImage(uri)
            REQ_REF_IMAGE -> {
                val bmp = runCatching { ProjectIO.decodeImage(this, uri, 2048, 2048) }.getOrNull()
                if (bmp == null) toast("Could not open that image")
                else {
                    referenceWindow()?.setImage(bmp)
                    // the photo stays with this artwork
                    val id = cv.doc.galleryId ?: Gallery.newId().also { cv.doc.galleryId = it }
                    saver.execute { Gallery.saveRef(applicationContext, id, bmp) }
                }
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
        // a big project takes a moment to read: off the main thread, so the app never stops answering
        hud.show("Opening…", sticky = true)
        val title = displayName(uri)?.substringBeforeLast('.') ?: "Imported artwork"
        thread(name = "inkora-project-open") {
            val d = runCatching { contentResolver.openInputStream(uri)?.use { ProjectIO.read(it) } }.getOrNull()
            main.post {
                hud.hideSoon()
                if (isFinishing || isDestroyed) return@post
                if (d == null) {
                    toast("This is not an Inkora project file")
                    return@post
                }
                // an opened project becomes a new artwork in the gallery
                autosave()
                closeGallery()
                d.galleryId = Gallery.newId()
                d.title = title
                d.origin = "imported"
                d.revision = 1
                openDocument(d)
                autosave(force = true)
                toast("Project opened")
            }
        }
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
        if (kind == "psd" && doc.layers.any { it.adjust != null }) toast("Adjustment layers are left out of the PSD (merge them down first to keep their colors)")
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
        artworkFrom(img, displayName(uri)?.substringBeforeLast('.') ?: "Photo")
        toast("Photo opened as a new artwork")
    }

    /** [img] (recycled here) as a new artwork, with a fresh layer on top to draw on */
    private fun artworkFrom(img: Bitmap, title: String) {
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
        d.title = title
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
    }

    // ---------- one page of a PDF ----------

    private fun pickPdf(asArtwork: Boolean) = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"
    }, if (asArtwork) REQ_PDF_NEW else REQ_PDF)

    /** a page of the PDF at [uri] on a new layer of this drawing, fitted inside the canvas */
    private fun insertPdfPage(uri: Uri) {
        val doc = cv.doc
        if (doc.layers.size >= doc.maxLayers()) { toast("Layer limit reached for this canvas size"); return }
        PdfPage.pick(this, uri, { _, _ -> doc.width to doc.height }) { page, n ->
            if (cv.doc !== doc) { page.recycle(); return@pick }
            insertBitmapLayer(page, L10n.tr("PDF page") + " $n")
            toast("PDF page added as a new layer")
        }
    }

    /** a page of the PDF at [uri] as a new artwork, at print quality */
    private fun pdfPageAsArtwork(uri: Uri) {
        val name = displayName(uri)?.substringBeforeLast('.') ?: "PDF"
        PdfPage.pick(this, uri, { w, h -> w to h }) { page, n -> artworkFrom(page, "$name · $n") }
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
        drawCentered(layer, img)
        val cmd = AddLayerCmd(layer, doc.activeIndex + 1, doc.activeIndex)
        cmd.redo(doc)
        cv.pushCommand(cmd)
        toast(if (shadow) "Shadow photo added: trace it, it stays out of the time-lapse and exports" else "Photo added as a new layer")
    }

    /** [img] (recycled here) on a new layer above the active one */
    private fun insertBitmapLayer(img: Bitmap, name: String) {
        val doc = cv.doc
        if (doc.layers.size >= doc.maxLayers()) { img.recycle(); toast("Layer limit reached for this canvas size"); return }
        val layer = try { doc.createLayer() } catch (e: OutOfMemoryError) {
            img.recycle(); toast("Not enough memory for another layer right now"); return
        }
        layer.name = name
        drawCentered(layer, img)
        val cmd = AddLayerCmd(layer, doc.activeIndex + 1, doc.activeIndex)
        cmd.redo(doc)
        cv.pushCommand(cmd)
        refreshAll()
    }

    /** [img] in the middle of [layer], made smaller when it doesn't fit; recycles it */
    private fun drawCentered(layer: Layer, img: Bitmap) {
        val doc = cv.doc
        val s = min(1f, min(doc.width / img.width.toFloat(), doc.height / img.height.toFloat()))
        val w = img.width * s
        val h = img.height * s
        val c = Canvas(layer.bitmap)
        c.drawBitmap(img, null, android.graphics.RectF((doc.width - w) / 2, (doc.height - h) / 2, (doc.width + w) / 2, (doc.height + h) / 2),
            Paint(Paint.FILTER_BITMAP_FLAG))
        img.recycle()
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
                val bytes = contentResolver.openInputStream(uri)?.use {
                    runCatching { BrushImport.readLimited(it, 60_000_000) }.getOrElse { throw IllegalArgumentException("This file is too big") }
                } ?: throw IllegalArgumentException("Could not open that file")
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

    /** a tool's own settings go in the settings bar under the top bar (on a phone: over the tool bar) */
    private fun showBottomBar(v: View) {
        bottomBar = v
        val (ic, name) = optionsTitle()
        beginSession(name)
        optionsBar.show(ic, name, v, true)
        updateOptions()
    }

    private fun hideBottomBar() {
        if (bottomBar == null) return
        bottomBar = null
        endSession()
        updateOptions()
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
            if (highlighted) { background = Ui.rounded(Ui.ACCENT, 10f); setTextColor(Ui.ON_ACCENT) }
        }

    private fun LinearLayout.addBar(v: View) = addView(v, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 4.dp; rightMargin = 4.dp })

    // ---------- Selection ----------

    // ---------- Cancel: a tool takes back everything done since it was picked ----------
    private var sessionMark = -1L
    private var sessionSelection: Selection? = null
    private var sessionGuide: GuideType? = null
    private var sessionTool: String? = null

    /** a tool's bar opened: remember where things were, unless this tool's session is already on */
    private fun beginSession(tool: String) {
        if (sessionMark >= 0 && sessionTool == tool) return
        sessionTool = tool
        sessionMark = cv.historyMark()
        sessionSelection = cv.selection
        sessionGuide = cv.doc.guide.type
    }

    private fun endSession() { sessionMark = -1L; sessionSelection = null; sessionGuide = null; sessionTool = null }

    private fun cancelSession(leave: () -> Unit) {
        if (sessionMark >= 0) {
            cv.undoTo(sessionMark)
            cv.selection = sessionSelection
            sessionGuide?.let { if (cv.doc.guide.type != it) { cv.doc.guide.type = it; cv.doc.revision++ } }
        }
        endSession()
        leave()
        cv.invalidate()
        hud.show("Cancelled")
    }

    private fun LinearLayout.addCancelDone(leave: () -> Unit) {
        addBar(barButton("Cancel") { cancelSession(leave) })
        addBar(barButton("Done", true) { endSession(); leave() })
    }

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
        actions.addBar(barButton("Make a brush") { brushFromSelection() })
        actions.addBar(barButton("Fill") { cv.fillSelection(cv.color) })
        actions.addBar(barButton("Clear") { cv.clearSelection() })
        actions.addBar(barButton("Feather") {
            val pm = InkMenu(this, actions)
            listOf(4, 12, 30, 60).forEachIndexed { i, r -> pm.menu.add(0, r, i, "Feather $r px") }
            pm.setOnMenuItemClickListener { cv.featherSelection(it.itemId.toFloat()); true }
            L10n.menu(pm.menu); pm.show()
        })
        actions.addBar(barButton("Deselect") { cv.selection = null })
        actions.addCancelDone {
            cv.interaction = Interaction.PAINT
            hideBottomBar()
            refreshAll()
        }
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

    // ---------- Layer opacity with a finger (two fingers tapped a layer) ----------

    private fun layerOpacityDrag(layer: Layer) {
        val shield = object : View(this) {
            var x0 = 0f; var o0 = layer.opacity; var moved = false
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { x0 = e.x; o0 = layer.opacity; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(e.x - x0) > 6.dp) moved = true
                        if (moved) {
                            layer.opacity = (o0 + (e.x - x0) / (width * 0.6f)).coerceIn(0f, 1f)
                            cv.doc.revision++
                            cv.invalidate()
                            hud.show(L10n.tr("Layer opacity") + " ${(layer.opacity * 100).roundToInt()}%", sticky = true)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        root.removeView(this)
                        hud.hideSoon()
                        refreshAll()
                    }
                }
                return true
            }
        }
        root.addView(shield, FrameLayout.LayoutParams(-1, -1))
        hud.show(L10n.tr("Layer opacity") + " ${(layer.opacity * 100).roundToInt()}% · " + L10n.tr("slide left or right"), sticky = true)
    }

    // ---------- A layer into another artwork ----------

    private fun copyLayerToArtwork(layer: Layer) {
        val cur = cv.doc.galleryId
        val others = Gallery.list(this).filter { it.id != cur }
        if (others.isEmpty()) { toast("No other artworks yet"); return }
        val names = others.map { "${it.title}   ${it.width} × ${it.height}" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Copy the layer into…"))
            .setItems(names) { _, k ->
                val e = others[k]
                val copy = try { layer.bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (er: OutOfMemoryError) { null }
                    ?: run { toast("Not enough memory for this right now"); return@setItems }
                val name = layer.name; val op = layer.opacity; val bl = layer.blend
                hud.show("Copying…", sticky = true)
                saver.execute {
                    val res = runCatching {
                        val d = Gallery.load(applicationContext, e.id) ?: error("open")
                        try {
                            if (d.layers.size >= d.maxLayers()) error("full")
                            val nl = d.createLayer()
                            nl.name = name; nl.opacity = op; nl.blend = bl
                            // centered: a different canvas size crops it or leaves room around it
                            Canvas(nl.bitmap).drawBitmap(copy, (d.width - copy.width) / 2f, (d.height - copy.height) / 2f, null)
                            d.layers.add(nl)
                            val thumb = Gallery.makeThumb(d)
                            Gallery.save(applicationContext, e.id, e.title, ProjectIO.snapshot(d, copy = false), thumb, e.origin)
                            thumb.recycle()
                        } finally { d.layers.forEach { it.bitmap.recycle() } }
                    }
                    main.post {
                        copy.recycle()
                        hud.hideSoon()
                        val m = res.exceptionOrNull()?.message
                        when {
                            res.isSuccess -> toast(L10n.tr("Layer copied to") + " " + e.title)
                            m == "full" -> toast("That artwork has as many layers as it can take")
                            else -> toast("Could not copy the layer")
                        }
                    }
                }
            }
            .setNegativeButton(L10n.tr("Cancel"), null).show()
    }

    // ---------- Back up / restore ----------

    private var backupSnaps = true
    private var backupBusy = false

    private fun backupNoteText(): String {
        val d = Backup.daysSince(this)
        return when {
            d < 0 -> L10n.tr("Never backed up. One file you can keep on Google Drive or a computer")
            d == 0 -> L10n.tr("Last backup: today")
            d == 1 -> L10n.tr("Last backup: yesterday")
            else -> L10n.tr("Last backup") + ": " + String.format(L10n.tr("%d days ago"), d)
        }
    }

    private fun backupDialog() {
        if (backupBusy) { hud.show("A backup is already running"); return }
        val (n, bytes) = Backup.size(this, true)
        val (_, light) = Backup.size(this, false)
        if (n == 0) { toast("No artworks to back up yet"); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24.dp, 8.dp, 24.dp, 0) }
        fun mb(b: Long) = String.format(java.util.Locale.US, "%.1f MB", b / 1048576f)
        box.addView(Ui.text(this, L10n.tr("Every artwork with its layers, reference photo, your palettes and imported brushes, in one file.") + "\n\n" +
            L10n.tr("Artworks") + ": $n", 14f, Ui.TEXT))
        val sizeText = Ui.text(this, "", 13f, Ui.TEXT_DIM).apply { setPadding(0, 6.dp, 0, 0) }
        fun upd() { sizeText.text = L10n.tr("About") + " " + mb(if (backupSnaps) bytes else light) }
        val snaps = android.widget.CheckBox(this).apply {
            text = L10n.tr("Include snapshots"); isChecked = backupSnaps; setTextColor(Ui.TEXT)
            setOnCheckedChangeListener { _, c -> backupSnaps = c; upd() }
        }
        box.addView(snaps); box.addView(sizeText); upd()
        box.addView(Ui.text(this, "Tip: choose Google Drive in the next screen and the backup is safe even if the tablet is lost.", 12.5f, Ui.TEXT_DIM).apply { setPadding(0, 10.dp, 0, 0) })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Back up all artworks"))
            .setView(box)
            .setPositiveButton(L10n.tr("Back up")) { _, _ ->
                val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, "Inkora backup $day.zip")
                }, REQ_BACKUP)
            }
            .setNegativeButton(L10n.tr("Cancel"), null).show()
    }

    private fun writeBackup(uri: Uri) {
        backupBusy = true
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        // the artwork on the canvas goes in as it is now
        autosave(force = true)
        hud.show("Backing up…", sticky = true)
        afterSaves {
            saver.execute {
                var last = 0L
                val ok = runCatching {
                    contentResolver.openOutputStream(uri, "w")!!.use { out ->
                        Backup.write(applicationContext, out, backupSnaps, ver) { f ->
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - last > 250) { last = now; main.post { hud.show(L10n.tr("Backing up…") + " ${(f * 100).roundToInt()}%", sticky = true) } }
                        }
                    }
                }
                main.post {
                    backupBusy = false
                    hud.hideSoon()
                    if (ok.isSuccess) toast("Backup saved") else toast("Could not write the backup: " + (ok.exceptionOrNull()?.message ?: ""))
                }
            }
        }
    }

    private fun pickBackup() {
        if (backupBusy) { hud.show("A backup is already running"); return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }, REQ_RESTORE)
    }

    private fun readBackup(uri: Uri) {
        backupBusy = true
        autosave(force = true)
        hud.show("Restoring…", sticky = true)
        val openId = if (cv.isDocReady()) cv.doc.galleryId else null
        afterSaves {
            saver.execute {
                val res = runCatching {
                    contentResolver.openInputStream(uri)!!.use { inp ->
                        Backup.restore(applicationContext, inp) { n -> if (n % 20 == 0) main.post { hud.show(L10n.tr("Restoring…") + " $n", sticky = true) } }
                    }
                }
                main.post {
                    backupBusy = false
                    hud.hideSoon()
                    val r = res.getOrNull()
                    if (r == null) {
                        val m = res.exceptionOrNull()?.message
                        toast(if (m == "not a backup") "This file is not an Inkora backup" else if (m == "newer") "This backup is from a newer Inkora: update the app first" else "Could not read the backup")
                        return@post
                    }
                    PaletteStore.load(prefs)
                    colorPanel?.paletteAdded()
                    gallery?.refresh()
                    // the artwork on the canvas came back newer from the backup: show that one
                    if (openId != null && r.updated > 0 && gallery == null) Gallery.load(this, openId)?.let { d -> openDocument(d); savedDoc = d; savedRevision = d.revision }
                    toast(L10n.tr("Restored") + ": " + String.format(L10n.tr("%1\$d new, %2\$d updated, %3\$d already here"), r.added, r.updated, r.kept))
                }
            }
        }
    }

    /** once a session, in the gallery: a nudge when there has been no backup for a while */
    private var backupNudged = false

    private fun maybeNudgeBackup() {
        if (backupNudged) return
        val n = Gallery.list(this).size
        val d = Backup.daysSince(this)
        if (n >= 3 && (d < 0 || d >= 14)) {
            backupNudged = true
            main.postDelayed({ hud.show(backupNoteText() + " · " + L10n.tr("Import… › Back up all artworks")) }, 1200)
        }
    }

    // ---------- Shapes ----------

    private fun enterShapes() {
        popup?.dismiss()
        val a = cv.doc.active
        if (a.vector != null) { cv.vectorEditor.tool = VTool.RECT; refreshAll(); hud.show("Vector layer: its shape tools are in the bar"); return }
        if (cv.interaction != Interaction.PAINT) { hideBottomBar(); cv.interaction = Interaction.PAINT }
        cv.fillMode = false
        if (cv.recolorMode) cv.recolorMode = false
        cv.shapeKind = runCatching { ShapeKind.valueOf(prefs.getString("shape_kind", "RECT")!!) }.getOrDefault(ShapeKind.RECT)
        cv.shapeFilled = prefs.getBoolean("shape_filled", false)
        cv.shapeEven = prefs.getBoolean("shape_even", false)
        cv.interaction = Interaction.SHAPE
        showShapesBar()
        refreshAll()
        hud.show("Shapes: drag on the canvas · drawn with your brush and color")
    }

    private fun showShapesBar() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val kinds = barRow()
        ShapeKind.values().forEach { k ->
            kinds.addBar(barButton(k.label, cv.shapeKind == k) {
                cv.shapeKind = k; prefs.edit().putString("shape_kind", k.name).apply(); showShapesBar()
            })
        }
        col.addView(kinds)
        val opts = barRow()
        opts.addBar(barButton("Outline", !cv.shapeFilled) { cv.shapeFilled = false; prefs.edit().putBoolean("shape_filled", false).apply(); showShapesBar() })
        opts.addBar(barButton("Filled", cv.shapeFilled) { cv.shapeFilled = true; prefs.edit().putBoolean("shape_filled", true).apply(); showShapesBar() })
        opts.addBar(barButton(if (cv.shapeEven) "Even: on" else "Even: off", cv.shapeEven) {
            cv.shapeEven = !cv.shapeEven; prefs.edit().putBoolean("shape_even", cv.shapeEven).apply(); showShapesBar()
        })
        opts.addCancelDone { leaveShapes() }
        col.addView(opts, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.dp })
        showBottomBar(col)
    }

    private fun leaveShapes() {
        if (cv.interaction == Interaction.SHAPE) cv.interaction = Interaction.PAINT
        hideBottomBar()
        refreshAll()
    }

    // ---------- Recolor ----------

    private fun enterRecolor() {
        popup?.dismiss()
        if (cv.interaction != Interaction.PAINT) { hideBottomBar(); cv.interaction = Interaction.PAINT }
        cv.fillMode = false
        cv.recolorMode = true
        val bar = barRow()
        bar.addBar(barButton("Colors") { showColors() })
        bar.addCancelDone { leaveRecolor() }
        showBottomBar(bar)
        refreshAll()
        hud.show("Recolor: tap an area, then change the color: the area follows")
    }

    private fun leaveRecolor() {
        cv.recolorMode = false
        hideBottomBar()
        refreshAll()
    }

    // ---------- Measure ----------

    private var measureInfo: android.widget.TextView? = null

    private fun enterMeasure() {
        popup?.dismiss()
        if (cv.interaction == Interaction.SELECT || cv.interaction == Interaction.GRADIENT) hideBottomBar()
        cv.fillMode = false
        cv.interaction = Interaction.MEASURE
        cv.onMeasure = { measureInfo?.text = if (cv.measureA != null) cv.measureLabel() else L10n.tr("Drag across the canvas") }
        showMeasureBar()
        refreshAll()
        hud.show("Measure: drag across the canvas. Near 0°, 45°, 90° it snaps")
    }

    private fun showMeasureBar() {
        val bar = barRow()
        val info = android.widget.TextView(this).apply {
            textSize = 14f; setTextColor(Ui.TEXT); typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(10.dp, 0, 10.dp, 0); gravity = Gravity.CENTER_VERTICAL; minWidth = 120.dp
        }
        measureInfo = info
        bar.addView(info, LinearLayout.LayoutParams(-2, 40.dp))
        cv.onMeasure?.invoke()
        MeasureUnit.values().forEach { u ->
            bar.addBar(barButton(u.label, cv.measureUnit == u) {
                cv.measureUnit = u; prefs.edit().putString("measure_unit", u.name).apply(); cv.invalidate(); showMeasureBar()
            })
        }
        bar.addBar(barButton("Make it a ruler") {
            if (cv.measureToRuler()) { hud.show("Ruler set: your strokes follow it. Grid & perspective › Off removes it"); leaveMeasure() }
            else hud.show("Measure a line first")
        })
        bar.addBar(barButton("Draw this line") { if (cv.measureA == null) hud.show("Measure a line first") else if (cv.drawMeasuredLine()) refreshAll() })
        bar.addCancelDone { leaveMeasure() }
        showBottomBar(bar)
    }

    private fun leaveMeasure() {
        if (cv.interaction == Interaction.MEASURE) cv.interaction = Interaction.PAINT
        cv.onMeasure = null
        measureInfo = null
        hideBottomBar()
        refreshAll()
    }

    // ---------- Gradient ----------

    private var gradientSecond = 0xFFFFFFFF.toInt()

    private fun enterGradient() {
        popup?.dismiss()
        if (cv.doc.active.vector != null) { hud.show("On a vector layer use Style › Fill for gradients"); return }
        if (cv.interaction == Interaction.SELECT) hideBottomBar()
        cv.fillMode = false
        cv.interaction = Interaction.GRADIENT
        // the second color starts as the one you used before this one
        recentColors.firstOrNull { (it or 0xFF000000.toInt()) != (cv.color or 0xFF000000.toInt()) }?.let { gradientSecond = it or 0xFF000000.toInt() }
        showGradientBar()
        refreshAll()
        hud.show("Gradient: drag across the canvas, from your color")
    }

    private fun showGradientBar() {
        val bar = barRow()
        GradientKind.values().forEach { k ->
            bar.addBar(barButton(k.label, cv.gradientKind == k) { cv.gradientKind = k; showGradientBar() })
        }
        bar.addBar(barButton("Fade out", cv.gradientTo == 0) { cv.gradientTo = 0; showGradientBar() })
        bar.addBar(barButton("Two colors", cv.gradientTo != 0) { cv.gradientTo = gradientSecond; showGradientBar() })
        if (cv.gradientTo != 0) {
            val sw = ColorSwatchView(this)
            sw.color = gradientSecond
            sw.setOnClickListener {
                pickColor(sw, gradientSecond) { c ->
                    gradientSecond = c or 0xFF000000.toInt()
                    cv.gradientTo = gradientSecond
                    sw.color = gradientSecond
                }
            }
            bar.addView(sw, LinearLayout.LayoutParams(40.dp, 40.dp))
        }
        bar.addBar(barButton("Reverse", cv.gradientReverse) { cv.gradientReverse = !cv.gradientReverse; showGradientBar() })
        bar.addCancelDone { leaveGradient() }
        showBottomBar(bar)
    }

    private fun leaveGradient() {
        if (cv.interaction == Interaction.GRADIENT) cv.interaction = Interaction.PAINT
        hideBottomBar()
        refreshAll()
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
        cv.transformSnap = prefs.getBoolean("transform_snap", true)
        cv.onTransformExact = { angle -> transformExactDialog(angle) }
        showTransformBar()
        refreshAll()
        hud.show("Drag to move · corners scale · sides stretch · green turns · yellow turns the box · tap a handle for a number")
    }

    private fun showTransformBar() {
        val bar = barRow()
        bar.addBar(barButton(if (cv.transformSnap) "Snapping: on" else "Snapping: off", cv.transformSnap) {
            cv.transformSnap = !cv.transformSnap
            prefs.edit().putBoolean("transform_snap", cv.transformSnap).apply()
            showTransformBar()
        })
        val shape = cv.transformShape
        // Free · Distort (each corner on its own) · Warp (bend with a grid)
        if (shape != "warp") bar.addBar(barButton("Free", shape == "free") { cv.transformAction("free"); showTransformBar() })
        bar.addBar(barButton("Distort", shape == "distort") {
            if (shape == "warp") { hud.show("Tap Reset to leave Warp"); return@barButton }
            cv.transformAction("distort"); showTransformBar(); hud.show("Drag a corner on its own to give the piece perspective")
        })
        bar.addBar(barButton("Warp", shape == "warp") {
            cv.transformAction("warp"); showTransformBar(); hud.show("Drag the dots to bend the piece; drag elsewhere to move it")
        })
        if (shape == "free") {
            bar.addBar(barButton("Flip ↔") { cv.transformAction("flipH") })
            bar.addBar(barButton("Flip ↕") { cv.transformAction("flipV") })
            bar.addBar(barButton("Rotate 90°") { cv.transformAction("rotate") })
            bar.addBar(barButton("Fit to canvas") { cv.transformAction("fit") })
        }
        bar.addBar(barButton("Reset") { cv.transformAction("reset"); showTransformBar() })
        bar.addBar(barButton("Cancel") { leaveTransform(false) })
        bar.addBar(barButton("Done", true) { leaveTransform(true) })
        showBottomBar(bar)
    }

    /** a tap on the green dot: an exact angle; on a corner or side: an exact size in pixels */
    private fun transformExactDialog(angle: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24.dp, 8.dp, 24.dp, 0) }
        fun field(label: String, v: Float, signed: Boolean): EditText {
            box.addView(Ui.text(this, label, 13f, Ui.TEXT_DIM).apply { setPadding(0, 8.dp, 0, 0) })
            return EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
                setText(if (abs(v - v.roundToInt()) < 0.05f) v.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", v))
                setSelectAllOnFocus(true)
                box.addView(this)
            }
        }
        val parse = { s: String -> s.replace(',', '.').toFloatOrNull() }
        val d: AlertDialog
        if (angle) {
            var a = cv.transformAngle()
            if (abs(a) < 0.05f) a = 0f
            val f = field(L10n.tr("Angle (degrees)"), a, true)
            d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L10n.tr("Rotate"))
                .setView(box)
                .setPositiveButton(L10n.tr("OK")) { _, _ -> parse(f.text.toString())?.let { cv.setTransformAngle(it) } }
                .setNegativeButton(L10n.tr("Cancel"), null).create()
        } else {
            val (w, h) = cv.transformSize()
            val fw = field(L10n.tr("Width (px)"), w, false)
            val fh = field(L10n.tr("Height (px)"), h, false)
            val keep = android.widget.CheckBox(this).apply { text = L10n.tr("Keep proportions"); isChecked = true; setTextColor(Ui.TEXT) }
            box.addView(keep)
            var syncing = false
            fun link(src: EditText, dst: EditText, ratio: () -> Float) = src.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (syncing || !keep.isChecked || !src.hasFocus()) return
                    val v = parse(s.toString()) ?: return
                    syncing = true; dst.setText((v * ratio()).roundToInt().toString()); syncing = false
                }
            })
            link(fw, fh) { h / max(1f, w) }
            link(fh, fw) { w / max(1f, h) }
            d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L10n.tr("Size"))
                .setView(box)
                .setPositiveButton(L10n.tr("OK")) { _, _ ->
                    val nw = parse(fw.text.toString()); val nh = parse(fh.text.toString())
                    if (nw != null && nh != null && nw >= 1f && nh >= 1f && nw <= 30000f && nh <= 30000f) cv.setTransformSize(nw, nh)
                }
                .setNegativeButton(L10n.tr("Cancel"), null).create()
        }
        d.show()
    }

    private fun leaveTransform(apply: Boolean) {
        cv.finishTransform(apply)
        hideBottomBar()
        refreshAll()
    }

    // ---------- Adjustments ----------

    /** sliders one under the other, beside the tool's name in the settings strip */
    private fun adjustPanelWidth() = min(380.dp, root.width - 190.dp).coerceAtLeast(200.dp)

    /** the settings of an adjustment layer, changed live on the canvas; Done keeps them (one undo step) */
    private fun editAdjustLayer(layer: Layer) {
        val a = layer.adjust ?: return
        val before = a.copy()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 12.dp, 18.dp, 12.dp)
            background = Ui.rounded(Ui.PANEL, 16f)
            elevation = 12f.dp
            tag = OptionsBar.STACKED
        }
        val w = adjustPanelWidth()
        panel.addView(Ui.text(this, a.kind.label, 15f, bold = true))
        fun changed() { cv.invalidate() }
        val kind = a.kind.sliders
        if (a.kind == LayerAdjust.CURVES) {
            lateinit var curve: CurvesView
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val names = listOf("All", "Red", "Green", "Blue")
            val buttons = ArrayList<android.widget.TextView>()
            names.forEachIndexed { i, n ->
                val b = barButton(n, i == 0) {}
                b.setOnClickListener {
                    curve.channel = i
                    buttons.forEachIndexed { k, v ->
                        v.background = Ui.rounded(if (k == i) Ui.ACCENT else Ui.PANEL_ROW, 10f)
                        v.setTextColor(if (k == i) Ui.ON_ACCENT else Ui.TEXT)
                    }
                }
                buttons.add(b)
                chips.addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 6.dp })
            }
            panel.addView(android.widget.HorizontalScrollView(this).apply { addView(chips) }, LinearLayout.LayoutParams(w, -2).apply { topMargin = 6.dp })
            curve = CurvesView(this, a.curves) { changed() }
            panel.addView(curve, LinearLayout.LayoutParams(w, -2).apply { topMargin = 6.dp })
            panel.addView(barButton("Reset curve") {
                val o = curve.channel * 8
                Curves.straight().copyInto(a.curves, o, o, o + 8)
                curve.invalidate(); changed()
            })
        } else kind?.sliders?.forEachIndexed { i, sl ->
            val start = (a.values[i] - sl.min) / (sl.max - sl.min)
            panel.addView(Ui.sliderRow(this, sl.name, start, { v ->
                val value = sl.min + v * (sl.max - sl.min)
                sl.names?.let { L10n.tr(it[value.roundToInt().coerceIn(0, it.size - 1)]) } ?: "${value.roundToInt()}${sl.unit}"
            }) { v ->
                a.values[i] = sl.min + v * (sl.max - sl.min)
                changed()
            }, LinearLayout.LayoutParams(w, -2))
        }
        if (kind != null && kind.sliders.isEmpty()) panel.addView(Ui.text(this, "Erase parts of this layer to keep the colors there as they were", 12.5f, Ui.TEXT_DIM))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(barButton("Cancel") {
            layer.adjust = before
            cv.invalidate(); hideBottomBar(); refreshAll()
        })
        buttons.addView(barButton("Done", true) {
            if (!a.sameAs(before)) { cv.pushCommand(AdjustCmd(layer, before, a.copy())); cv.doc.revision++ }
            hideBottomBar(); refreshAll(); onHistoryChanged()
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 8.dp })
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
        showBottomBar(panel)
        cv.invalidate()
    }

    private fun startAdjust(kind: AdjustKind) {
        val src = cv.beginAdjust() ?: return
        GradientMaps.userColor = cv.color
        adjustKind = kind
        adjustSrc = src
        adjustFinish = null
        adjustValues = FloatArray(4) { i -> kind.sliders.getOrNull(i)?.default ?: 0f }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 12.dp, 18.dp, 12.dp)
            background = Ui.rounded(Ui.PANEL, 16f)
            elevation = 12f.dp
            tag = OptionsBar.STACKED
        }
        val note = if (cv.selection != null) "  (selection only)" else ""
        panel.addView(Ui.text(this, kind.label + note, 15f, bold = true))
        kind.sliders.forEachIndexed { i, sl ->
            val start = (sl.default - sl.min) / (sl.max - sl.min)
            panel.addView(Ui.sliderRow(this, sl.name, start, { v ->
                val value = sl.min + v * (sl.max - sl.min)
                sl.names?.let { L10n.tr(it[value.roundToInt().coerceIn(0, it.size - 1)]) } ?: "${value.roundToInt()}${sl.unit}"
            }) { v ->
                adjustValues[i] = sl.min + v * (sl.max - sl.min)
                requestAdjust()
            }, LinearLayout.LayoutParams(adjustPanelWidth(), -2))
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(barButton("Cancel") { endAdjust(false) })
        buttons.addView(barButton("Apply", true) { endAdjust(true) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 8.dp })
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
        showBottomBar(panel)
        refreshAll()
        requestAdjust()
    }

    private var adjustChangedAt = 0L
    private var adjustFrameQueued = false
    private val adjustFullRun = Runnable { if (adjustKind != null && !adjustBusy && cv.adjustShownPreview) runAdjust(full = true) }

    private fun requestAdjust() {
        adjustChangedAt = android.os.SystemClock.uptimeMillis()
        val kind = adjustKind ?: return
        // color-only adjustments: shown at once, at most once a frame
        if (Adjust.matrixFor(kind, adjustValues) != null) {
            if (!adjustFrameQueued) {
                adjustFrameQueued = true
                cv.postOnAnimation {
                    adjustFrameQueued = false
                    val k = adjustKind ?: return@postOnAnimation
                    val m = Adjust.matrixFor(k, adjustValues) ?: return@postOnAnimation
                    val r = cv.adjustArea(0)
                    if (!r.isEmpty) cv.showAdjustedMatrix(r, m, fast = true)
                }
            }
            return
        }
        if (adjustBusy) { adjustPending = true; return }
        runAdjust()
    }

    private fun runAdjust(full: Boolean = false) {
        val kind = adjustKind ?: return
        val src = adjustSrc ?: return
        main.removeCallbacks(adjustFullRun)
        adjustBusy = true
        val vals = adjustValues.copyOf()
        val grow = when (kind) {
            AdjustKind.BLUR -> (vals[0] * 2).toInt() + 2
            AdjustKind.SHARPEN -> 8
            else -> 0
        }
        val r = cv.adjustArea(grow)
        if (r.isEmpty) { adjustBusy = false; return }
        // while a slider moves, a big layer is worked out smaller (quick) and at full size when it stops
        val area = r.width().toLong() * r.height()
        val moving = android.os.SystemClock.uptimeMillis() - adjustChangedAt < 350
        val scale = if (!full && adjustFinish == null && moving && area > 1_200_000L) kotlin.math.sqrt(1_200_000.0 / area).toFloat() else 1f
        val slow = scale == 1f && area > 1_200_000L && (kind == AdjustKind.BLUR || kind == AdjustKind.SHARPEN)
        if (slow) hud.show("Working…", sticky = true)
        adjustExec.execute {
            val result = runCatching {
                if (scale < 1f) {
                    val sw = (r.width() * scale).toInt().coerceAtLeast(1); val sh = (r.height() * scale).toInt().coerceAtLeast(1)
                    val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                    Canvas(small).drawBitmap(src, r, android.graphics.Rect(0, 0, sw, sh), Paint(Paint.FILTER_BITMAP_FLAG))
                    val v = vals.copyOf()
                    if (kind == AdjustKind.BLUR) v[0] *= scale
                    Adjust.apply(kind, small, v).also { small.recycle() }
                } else {
                    val crop = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
                    Adjust.apply(kind, crop, vals).also { if (it !== crop) crop.recycle() }
                }
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
                    // Apply never keeps a preview: work it out at full size first
                    finish == true && cv.adjustShownPreview -> runAdjust(full = true)
                    finish != null -> completeAdjust(finish)
                    adjustPending -> { adjustPending = false; runAdjust() }
                    cv.adjustShownPreview -> main.postDelayed(adjustFullRun, 300)
                }
            }
        }
    }

    private fun endAdjust(apply: Boolean) {
        main.removeCallbacks(adjustFullRun)
        // a calculation may still be running: finish when it's done
        if (adjustBusy) {
            adjustFinish = apply
            adjustPending = false
            return
        }
        // the slider's last value (color matrix: maybe still waiting for its frame), at full size
        val kind = adjustKind
        if (apply && kind != null) {
            val m = Adjust.matrixFor(kind, adjustValues)
            if (m != null) { val r = cv.adjustArea(0); if (!r.isEmpty) cv.showAdjustedMatrix(r, m) }
            else if (cv.adjustShownPreview) { adjustFinish = true; runAdjust(full = true); return }
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
        override fun inDock(id: String) = false
        override fun toggleDock(id: String) {}
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
    /**
     * The ••• in the middle of the bar: Creative and Editing side by side, each with its sections
     * as chips that open the panel right there, and the artwork's name underneath.
     */
    private fun showMoreMenu(anchor: View) {
        popup?.dismiss()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp, 10.dp, 10.dp, 8.dp)
            background = Ui.rounded(Ui.PANEL, 20f, Ui.OUTLINE, 1)
            layoutDirection = if (L10n.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        var pw: PopupWindow? = null
        val tiles = LinearLayout(this).apply { orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        fun tile(creative: Boolean): View {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(Ui.CARD, 16f, Ui.OUTLINE, 1)
                setPadding(12.dp, 12.dp, 12.dp, 12.dp)
                foreground = Ui.ripple(16f)
                isClickable = true
                setOnClickListener { pw?.dismiss(); showToolsMenu(creative) }
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(IconView(this, if (creative) Icon.SPARKLE else Icon.WAND).apply {
                isClickable = false; tint = Ui.ACCENT; iconScale = 0.58f
                background = Ui.rounded(Ui.ACCENT_SOFT, 12f)
            }, LinearLayout.LayoutParams(44.dp, 44.dp))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10.dp, 0, 0, 0) }
            texts.addView(Ui.text(this, if (creative) "Creative" else "Editing", 17f, bold = true))
            texts.addView(Ui.text(this, if (creative) "Make and add things" else "Change what is there", 12f, Ui.TEXT_DIM))
            head.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            t.addView(head)
            val chips = android.widget.GridLayout(this).apply { columnCount = 3 }
            val cats = if (creative) listOf("create" to "Create", "color" to "Color", "shapes" to "Shapes", "guides" to "Guides", "symmetry" to "Symmetry", "view" to "View")
                else listOf("select" to "Select", "transform" to "Transform", "adjust" to "Adjust", "filters" to "Filters")
            for ((key, label) in cats) {
                chips.addView(Ui.text(this, label, 12.5f, Ui.TEXT).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(6.dp, 7.dp, 6.dp, 7.dp)
                    background = Ui.rounded(Ui.SUNKEN, 10f)
                    foreground = Ui.ripple(10f)
                    setOnClickListener { pw?.dismiss(); showToolsMenu(creative, key) }
                }, android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(2.dp, 2.dp, 2.dp, 2.dp)
                })
            }
            t.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })
            return t
        }
        if (narrow) {
            tiles.addView(tile(true), LinearLayout.LayoutParams(-1, -2))
            tiles.addView(tile(false), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
        } else {
            tiles.addView(tile(true), LinearLayout.LayoutParams(0, -2, 1f))
            tiles.addView(tile(false), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8.dp })
        }
        box.addView(tiles)
        val rename = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6.dp, 0, 10.dp, 0)
            foreground = Ui.ripple(12f)
            isClickable = true
            setOnClickListener { pw?.dismiss(); titleView.performClick() }
        }
        rename.addView(IconView(this, Icon.EDIT).apply { isClickable = false; tint = Ui.TEXT_DIM; iconScale = 0.5f }, LinearLayout.LayoutParams(36.dp, 36.dp))
        rename.addView(Ui.text(this, "Rename artwork…", 14.5f), LinearLayout.LayoutParams(-2, -2).apply { marginStart = 4.dp })
        rename.addView(Ui.text(this, cv.doc.title.ifBlank { L10n.tr("Untitled Artwork") }, 13f, Ui.TEXT_DIM).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 10.dp })
        box.addView(rename, LinearLayout.LayoutParams(-1, 44.dp).apply { topMargin = 6.dp })
        val w = if (narrow) root.width - 16.dp else min(520.dp, root.width - 16.dp)
        val p = PopupWindow(box, w, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        p.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        p.isOutsideTouchable = true
        p.elevation = 18f.dp
        p.animationStyle = android.R.style.Animation_Dialog
        pw = p
        popup = p
        val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
        val x = ((root.width - w) / 2).coerceAtLeast(8.dp)
        p.showAtLocation(root, Gravity.TOP or Gravity.LEFT, x + rootLoc[0], 60.dp + rootLoc[1])
    }

    private fun showToolsMenu(creative: Boolean, category: String? = null) {
        if (!cv.isDocReady()) return
        category?.let { ToolsPanel.select(creative, it) }
        val panel = ToolsPanel(this, creative, toolsHost)
        popup?.dismiss()
        val w = if (narrow) root.width - 16.dp else min(640.dp, root.width - sideBar.width - 40.dp)
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
        // under the ••• in the middle of the bar
        val x = ((root.width - w) / 2).coerceIn(8.dp, (root.width - w - 8.dp).coerceAtLeast(8.dp))
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
            if (active) background = Ui.rounded(Ui.ACCENT_SOFT, 10f)
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
                val pm = InkMenu(this, combine)
                BoolOp.values().forEachIndexed { i, o -> pm.menu.add(0, i, i, o.label) }
                pm.setOnMenuItemClickListener { ed.booleanOp(BoolOp.values()[it.itemId]); true }
                L10n.menu(pm.menu); pm.show()
            }
            acts.addBar(combine)
            val arrange = barButton("Arrange ▾") {}
            arrange.setOnClickListener {
                val pm = InkMenu(this, arrange)
                listOf("Bring to front" to "front", "Bring forward" to "forward", "Send backward" to "backward", "Send to back" to "back")
                    .forEachIndexed { i, (l, _) -> pm.menu.add(0, i, i, l) }
                pm.setOnMenuItemClickListener { ed.arrange(listOf("front", "forward", "backward", "back")[it.itemId]); true }
                L10n.menu(pm.menu); pm.show()
            }
            acts.addBar(arrange)
            val align = barButton("Align ▾") {}
            align.setOnClickListener {
                val pm = InkMenu(this, align)
                val opts = listOf("Left" to "left", "Center" to "hcenter", "Right" to "right", "Top" to "top", "Middle" to "vcenter", "Bottom" to "bottom")
                opts.forEachIndexed { i, (l, _) -> pm.menu.add(0, i, i, "Align $l") }
                pm.setOnMenuItemClickListener { ed.align(opts[it.itemId].second); true }
                L10n.menu(pm.menu); pm.show()
            }
            acts.addBar(align)
            acts.addBar(barButton("Flip ↔") { ed.flip(true) })
            acts.addBar(barButton("Flip ↕") { ed.flip(false) })
            acts.addBar(barButton("Duplicate") { ed.duplicateSelected() })
            acts.addBar(barButton("Delete") { ed.deleteSelected() }.apply { setTextColor(Ui.DANGER) })
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
            val pm = InkMenu(this, fontBtn)
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
        // Shape: in a block, on an arc, round a circle, or along a line drawn on the canvas
        var shape = TextTool.STRAIGHT
        var bend = 0.5f
        val bendRow = Ui.sliderRow(this, "Bend", bend, { "${(20 + it * 160).roundToInt()}°" }) { bend = it }
        val shapeBtn = Ui.button(this, L10n.tr("Shape") + ": " + L10n.tr(TextTool.shapeNames[shape])) {}
        fun showShape() {
            shapeBtn.text = L10n.tr("Shape") + ": " + L10n.tr(TextTool.shapeNames[shape])
            alignBtn.visibility = if (shape == TextTool.STRAIGHT) View.VISIBLE else View.GONE
            bendRow.visibility = if (shape == TextTool.ARC) View.VISIBLE else View.GONE
        }
        shapeBtn.setOnClickListener {
            val pm = InkMenu(this, shapeBtn)
            TextTool.shapeNames.forEachIndexed { i, n -> pm.menu.add(0, i, i, n) }
            pm.setOnMenuItemClickListener { shape = it.itemId; showShape(); true }
            L10n.menu(pm.menu); pm.show()
        }
        box.addView(shapeBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.dp })
        box.addView(bendRow)
        showShape()
        var sizePx = cv.doc.height / 10f
        box.addView(Ui.sliderRow(this, "Size", 0.3f, { "${(cv.doc.height * (0.02f + it * 0.3f)).roundToInt()} px" }) {
            sizePx = cv.doc.height * (0.02f + it * 0.3f)
        })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Add text"))
            .setView(box)
            .setPositiveButton(L10n.tr("Add")) { _, _ ->
                val text = input.text.toString()
                when (shape) {
                    TextTool.STRAIGHT -> addText(text, font, sizePx, align)
                    TextTool.LINE -> if (text.isNotBlank()) textAlongLine(text, font, sizePx)
                    else -> if (text.isNotBlank()) {
                        val d = cv.doc
                        val path = if (shape == TextTool.ARC) TextTool.arcPath(d.width / 2f, d.height / 2f, d.width * 0.7f, bend)
                            else TextTool.circlePath(d.width / 2f, d.height / 2f, min(d.width, d.height) * 0.3f)
                        addTextOnPath(text, font, sizePx, path, place = true)
                    }
                }
            }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    // ---------- Comics ----------

    private fun panelsDialog() {
        val doc = cv.doc
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24.dp, 8.dp, 24.dp, 0) }
        var layout = Comics.layouts[3]
        var gutter = 0.3f; var border = 0.3f; var margin = 0.3f
        val short = min(doc.width, doc.height).toFloat()
        fun gutterPx() = short * (0.005f + gutter * 0.05f)
        fun borderPx() = max(1f, short * (0.001f + border * 0.012f))
        fun marginPx() = short * margin * 0.12f
        val preview = object : View(this) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val s = min(width / doc.width.toFloat(), height / doc.height.toFloat())
                c.translate((width - doc.width * s) / 2f, (height - doc.height * s) / 2f)
                c.scale(s, s)
                p.style = Paint.Style.FILL; p.color = Color.WHITE
                c.drawRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat(), p)
                Comics.drawPanels(c, Comics.rects(layout, doc.width, doc.height, marginPx(), gutterPx()), max(borderPx(), 1.5f / s), Color.BLACK)
            }
        }
        box.addView(preview, LinearLayout.LayoutParams(-1, 190.dp))
        val layoutBtn = Ui.button(this, layout.label) {}
        layoutBtn.setOnClickListener {
            val pm = InkMenu(this, layoutBtn)
            Comics.layouts.forEachIndexed { i, l -> pm.menu.add(0, i, i, l.label) }
            pm.setOnMenuItemClickListener { layout = Comics.layouts[it.itemId]; layoutBtn.text = L10n.tr(layout.label); preview.invalidate(); true }
            L10n.menu(pm.menu); pm.show()
        }
        box.addView(layoutBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
        box.addView(Ui.sliderRow(this, "Space between", gutter, { "${gutterPx().roundToInt()} px" }) { gutter = it; preview.invalidate() })
        box.addView(Ui.sliderRow(this, "Line", border, { "${borderPx().roundToInt()} px" }) { border = it; preview.invalidate() })
        box.addView(Ui.sliderRow(this, "Page margin", margin, { "${marginPx().roundToInt()} px" }) { margin = it; preview.invalidate() })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Comic panels"))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(L10n.tr("Add")) { _, _ ->
                if (doc.layers.size >= doc.maxLayers()) { toast("Layer limit reached"); return@setPositiveButton }
                val layer = try { doc.createLayer() } catch (e: OutOfMemoryError) { toast("Not enough memory for this right now"); return@setPositiveButton }
                layer.name = "Panels"
                Comics.drawPanels(Canvas(layer.bitmap), Comics.rects(layout, doc.width, doc.height, marginPx(), gutterPx()), borderPx(), Color.BLACK)
                // on top of everything, so the frames stay over the drawing
                val cmd = AddLayerCmd(layer, doc.layers.size, doc.activeIndex)
                cmd.redo(doc)
                cv.pushCommand(cmd)
                refreshAll()
                hud.show("Panels added on a new layer")
            }
            .setNegativeButton(L10n.tr("Cancel"), null).show()
    }

    private fun bubbleDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24.dp, 8.dp, 24.dp, 0) }
        val input = EditText(this).apply {
            hint = "Type your text (any language)"
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.addView(input, LinearLayout.LayoutParams(-1, -2))
        var kind = Comics.Bubble.SPEECH
        val kindBtn = Ui.button(this, kind.label) {}
        kindBtn.setOnClickListener {
            val pm = InkMenu(this, kindBtn)
            Comics.Bubble.values().forEachIndexed { i, k -> pm.menu.add(0, i, i, k.label) }
            pm.setOnMenuItemClickListener { kind = Comics.Bubble.values()[it.itemId]; kindBtn.text = L10n.tr(kind.label); true }
            L10n.menu(pm.menu); pm.show()
        }
        box.addView(kindBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
        var font = TextFont.SANS_BOLD
        val fontBtn = Ui.button(this, "Font: ${font.label}") {}
        fontBtn.setOnClickListener {
            val pm = InkMenu(this, fontBtn)
            TextFont.values().forEachIndexed { i, f -> pm.menu.add(0, i, i, f.label) }
            pm.setOnMenuItemClickListener { font = TextFont.values()[it.itemId]; fontBtn.text = "Font: ${font.label}"; true }
            L10n.menu(pm.menu); pm.show()
        }
        box.addView(fontBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.dp })
        var sizePx = cv.doc.height * (0.012f + 0.25f * 0.1f)
        box.addView(Ui.sliderRow(this, "Size", 0.25f, { "${(cv.doc.height * (0.012f + it * 0.1f)).roundToInt()} px" }) {
            sizePx = cv.doc.height * (0.012f + it * 0.1f)
        })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Speech bubble"))
            .setView(box)
            .setPositiveButton(L10n.tr("Add")) { _, _ -> addBubble(input.text.toString(), kind, font, sizePx) }
            .setNegativeButton(L10n.tr("Cancel"), null).show()
    }

    private fun addBubble(text: String, kind: Comics.Bubble, font: TextFont, sizePx: Float) {
        val doc = cv.doc
        if (text.isBlank()) return
        if (doc.layers.size >= doc.maxLayers()) { toast("Layer limit reached"); return }
        val txt = TextTool.render(text, font, sizePx, Color.BLACK, android.text.Layout.Alignment.ALIGN_CENTER,
            (doc.width * 0.55f).toInt(), (doc.height * 0.55f).toInt()) ?: return
        val bmp = Comics.bubble(txt, kind, max(2f, sizePx * 0.07f))
        txt.recycle()
        if (bmp == null) { toast("Not enough memory for this right now"); return }
        val layer = try { doc.createLayer() } catch (e: OutOfMemoryError) { bmp.recycle(); toast("Not enough memory for this right now"); return }
        layer.name = "Bubble: " + text.trim().take(18)
        Canvas(layer.bitmap).drawBitmap(bmp, (doc.width - bmp.width) / 2f, (doc.height - bmp.height) / 2f, null)
        bmp.recycle()
        val cmd = AddLayerCmd(layer, doc.activeIndex + 1, doc.activeIndex)
        cmd.redo(doc)
        cv.pushCommand(cmd)
        cv.selection = null
        // place it right away
        enterTransform()
    }

    /** text on its own layer along [path] (document coordinates); [place] = then move it with Transform */
    private fun addTextOnPath(text: String, font: TextFont, sizePx: Float, path: android.graphics.Path, place: Boolean) {
        val doc = cv.doc
        if (doc.layers.size >= doc.maxLayers()) { toast("Layer limit reached"); return }
        val layer = try { doc.createLayer() } catch (e: OutOfMemoryError) { toast("Not enough memory for this right now"); return }
        layer.name = "Text: " + text.trim().take(18)
        TextTool.drawOnPath(Canvas(layer.bitmap), text, font, sizePx, cv.color or 0xFF000000.toInt(), path)
        val cmd = AddLayerCmd(layer, doc.activeIndex + 1, doc.activeIndex)
        cmd.redo(doc)
        cv.pushCommand(cmd)
        cv.selection = null
        if (place) enterTransform() else refreshAll()
    }

    /** waits for one line drawn on the canvas, then writes the text along it */
    private fun textAlongLine(text: String, font: TextFont, sizePx: Float) {
        val bar = barRow()
        bar.addBar(barButton("Cancel") { cv.pathCapture = null; hideBottomBar(); hud.hideSoon() })
        showBottomBar(bar)
        hud.show("Draw the line for the text to follow", sticky = true)
        cv.pathCapture = { pts ->
            hideBottomBar(); hud.hideSoon()
            addTextOnPath(text, font, sizePx, TextTool.pathThrough(pts), place = false)
            hud.show("Text added on a new layer")
        }
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
            maybeNudgeBackup()
            val g = GalleryView(this, if (cv.isDocReady()) cv.doc.galleryId else null, fromCanvas,
                onOpen = { id -> openFromGallery(id) },
                onNew = { newCanvasDialog() },
                onImport = { anchor ->
                    val pm = InkMenu(this, anchor)
                    pm.menu.add(0, 1, 1, "Photo as a new artwork…")
                    pm.menu.add(0, 5, 2, "PDF page as a new artwork…")
                    pm.menu.add(0, 2, 3, "Inkora project or Photoshop file…")
                    pm.menu.add(0, 3, 3, "Restore a backup…")
                    pm.menu.add(0, 4, 4, "Back up all artworks…")
                    pm.setOnMenuItemClickListener {
                        if (it.itemId == 3) { pickBackup(); return@setOnMenuItemClickListener true }
                        if (it.itemId == 4) { backupDialog(); return@setOnMenuItemClickListener true }
                        if (it.itemId == 5) { pickPdf(asArtwork = true); return@setOnMenuItemClickListener true }
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
                onClose = { closeGallery() },
                onNewSize = { w, h -> newCanvas(w, h) },
                onCustomSize = { customSizeDialog() },
                onSettings = { v -> openSettingsPopup(v) },
                screen = screenSize())
            gallery = g
            topBar.visibility = View.GONE
            sideBar.visibility = View.GONE
            dock.visibility = View.GONE
            optionsBar.visibility = View.GONE
            dock.dismissEditor()
            pinned.values.forEach { it.visibility = View.GONE }
            root.addView(g, FrameLayout.LayoutParams(-1, -1))
            g.refresh()
        }
    }

    private var galleryFromCanvas = true

    /** the guide: every page of the tour on one screen */
    private fun showGuidePage() {
        popup?.dismiss()
        showOverlay(GuideView(this,
            onTour = { closeSettings(); showTips() },
            onShortcuts = { showShortcuts() },
            onGestures = { showGestureControls() },
            onDemos = { showDemos() },
            onClose = { closeSettings() }))
    }

    private fun showDemos() {
        popup?.dismiss()
        showOverlay(DemosPage(this, onKeyboard = { showShortcuts() }, onClose = { closeSettings() }))
    }

    /** [welcome]: the three first-run pages (with "See everything"); else the whole tour */
    private fun showTips(welcome: Boolean = !prefs.getBoolean("tour_done", false)) {
        if (root.findViewWithTag<View>("tips") != null) return
        val tips = TipsOverlay(this, if (welcome) Tour.welcome else Tour.pages,
            onMore = if (welcome) ({ root.postDelayed({ showTips(false) }, 260) }) else null) { v ->
            root.removeView(v)
            prefs.edit().putBoolean("tour_done", true).apply()
        }
        tips.tag = "tips"
        // above the Gallery and everything else
        tips.elevation = 80f.dp
        root.addView(tips, FrameLayout.LayoutParams(-1, -1))
    }

    private fun closeGallery() {
        val wasOpen = gallery != null
        gallery?.let { root.removeView(it) }
        gallery = null
        if (wasOpen && !prefs.getBoolean("tour_done", false)) root.post { showTips() }
        if (!focusMode) {
            topBar.visibility = View.VISIBLE
            // the size and opacity sliders stay hidden when they were turned off in Prefs
            sideBar.visibility = if (prefs.getBoolean("sidebar", true)) View.VISIBLE else View.GONE
            dock.visibility = View.VISIBLE
            pinned.values.forEach { it.visibility = View.VISIBLE }
            updateOptions()
        }
    }

    private fun openFromGallery(id: String) {
        if (id == cv.doc.galleryId) { closeGallery(); return }
        if (openingArtwork) return
        // read off the main thread: a big artwork takes a moment
        openingArtwork = true
        hud.show("Opening…", sticky = true)
        // on the saving thread: never while that artwork is still being written
        saver.execute {
            val d = runCatching { Gallery.load(applicationContext, id) }.getOrNull()
            main.post {
                openingArtwork = false
                hud.hideSoon()
                if (isFinishing || isDestroyed) return@post
                if (d == null) { toast("Could not open this artwork"); return@post }
                openDocument(d)
                savedDoc = d
                savedRevision = d.revision
                closeGallery()
            }
        }
    }
    private var openingArtwork = false

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
        val prev = recentColors.firstOrNull { it != cv.color } ?: run { hud.show("No previous color yet"); return }
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
                name.endsWith(".pdf") || type == "application/pdf" -> pdfPageAsArtwork(first)
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

    /** four fingers held on the canvas: switch the workspace */
    override fun onFourFingerHold(x: Float, y: Float) {
        if (busy()) return
        popup?.dismiss()
        val spaces = Workspaces.presets + listOfNotNull(Workspaces.mine(prefs))
        val cur = Workspaces.current(prefs)
        // a dot where the fingers were, for the menu to hang from (one, kept)
        val spot = holdSpot ?: View(this).also { holdSpot = it; root.addView(it, FrameLayout.LayoutParams(1, 1)) }
        (spot.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = x.toInt(); topMargin = y.toInt() }
        spot.requestLayout()
        spot.post {
            val m = InkMenu(this, spot)
            spaces.forEachIndexed { i, w -> m.menu.add(0, i, i, w.name).apply { icon(Icon.LAYOUT); isChecked = w.key == cur } }
            m.setOnMenuItemClickListener { item ->
                spaces.getOrNull(item.itemId)?.let { actionsHost.useWorkspace(it) }
                true
            }
            L10n.menu(m.menu)
            m.show()
        }
    }

    private var holdSpot: View? = null

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
        /** the row of islands at the top, and the space around the islands */
        const val TOP_H = 52
        const val ISLAND_GAP = 12
        /** where the settings strip starts (under the top row) */
        const val STRIP_Y = TOP_H + ISLAND_GAP * 2
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
        private const val REQ_BACKUP = 116
        private const val REQ_RESTORE = 117
        private const val REQ_PDF = 118
        private const val REQ_PDF_NEW = 119
    }
}
