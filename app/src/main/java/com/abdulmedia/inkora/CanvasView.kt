package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import android.graphics.RectF

enum class Tool { BRUSH, SMUDGE, ERASER }

/** What touches on the canvas do right now. */
enum class Interaction { PAINT, SELECT, TRANSFORM, LOCKED, VECTOR, LIQUIFY, GRADIENT, MEASURE, SHAPE }

enum class ShapeKind(val label: String) {
    RECT("Rectangle"), ELLIPSE("Ellipse"), TRIANGLE("Triangle"), STAR("Star"), POLYGON("Hexagon"),
    HEART("Heart"), LINE("Line"), ARROW("Arrow")
}

enum class MeasureUnit(val label: String) { PX("px"), CM("cm"), IN("in") }

enum class GradientKind(val label: String) { LINEAR("Linear"), RADIAL("Radial") }

@SuppressLint("ViewConstructor")
class CanvasView(context: Context) : View(context) {

    interface Listener {
        fun onHistoryChanged()
        fun onColorPicked(color: Int)
        fun onMessage(text: String)
        fun onSelectionChanged() {}
        fun onVectorSelectionChanged() {}
        fun onDrawingChanged(drawing: Boolean) {}
        /** pen pressed and held still on the canvas */
        fun onPenLongPress(x: Float, y: Float) {}
        /** a gesture asked for something the screen around the canvas does (quick menu, full screen…) */
        fun onGesture(purpose: GesturePurpose, x: Float, y: Float) {}
        /** four fingers held still on the canvas */
        fun onFourFingerHold(x: Float, y: Float) {}
        /** the brush's size or opacity changed from the canvas (hover + finger) */
        fun onBrushAdjusted() {}
        /** a different layer became the active one */
        fun onLayerPicked() {}
    }

    var listener: Listener? = null

    lateinit var doc: Document
        private set
    var history = History()
        private set

    private var strokeBitmap: Bitmap? = null

    /** Settings › Faster drawing: the stroke goes to the screen in tiles (see [TileMirror]) */
    var gpuTiles = false
    private var strokeTiles: TileMirror? = null
    /** the stroke picture as the tiles last saw it (a change the brush engine did not report → copy all) */
    private var tilesGen = -1

    /** the stroke picture was emptied: so are the tiles */
    private fun strokeErased() {
        strokeTiles?.clear()
        engine.frameDirty.setEmpty()
        tilesGen = strokeBitmap?.generationId ?: -1
    }

    /** brings the tiles up to date with the stroke picture; false: draw the picture itself */
    private fun syncTiles(sb: Bitmap): Boolean {
        val t = strokeTiles ?: return false
        if (!gpuTiles) return false
        val gen = sb.generationId
        if (gen != tilesGen) {
            if (engine.frameDirty.isEmpty) t.updateAll(sb) else t.update(sb, engine.frameDirty)
            engine.frameDirty.setEmpty()
            tilesGen = sb.generationId
        }
        return true
    }

    private fun drawStrokePicture(c: Canvas, sb: Bitmap, paint: Paint?, tiled: Boolean) {
        if (tiled) strokeTiles?.draw(c, paint) else c.drawBitmap(sb, 0f, 0f, paint)
    }
    private val engine = StrokeEngine()

    // ---- tool state (set by MainActivity) ----
    var tool = Tool.BRUSH
    var paintBrush = Brush(Brushes.presets[1])
    var eraserBrush = Brush(Brushes.presets[9]).apply { size = 60f }
    var color = 0xFF1F2026.toInt()
        set(v) {
            val changed = field != v
            field = v
            // Recolor: the area you tapped last follows the new color
            if (changed && recolorMode && recolorPoint != null && !recolorQueued) {
                recolorQueued = true
                postDelayed({ recolorQueued = false; recolorUpdate() }, 90)
            }
        }

    /** Recolor tool: a tap fills an area, and changing the color then changes that area again */
    var recolorMode = false
        set(v) { field = v; if (!v) { recolorPoint = null; recolorCmd = null }; invalidate() }
    private var recolorPoint: PointF? = null
    private var recolorCmd: Command? = null
    private var recolorUpdating = false
    private var recolorQueued = false
    var symmetry = Symmetry.OFF
        set(v) { field = v; invalidate() }
    var quickShapeEnabled = true
    var fingerDrawing = true
    /** when the user has not chosen, finger drawing turns off once a stylus is seen */
    var fingerDrawingAuto = true
    var fillThreshold = 0.22f
    var eyedropperArmed = false
    /** Fill tool: every tap fills the area under it (ColorDrop) */
    var fillMode = false
        set(v) { field = v; invalidate() }

    var smudgeBrush = Brush(Brushes.presets[0]).apply { size = 60f; opacity = 0.75f }

    /** gesture controls (set by MainActivity) */
    var gestures: GestureSettings? = null
    /** the sidebar's □ modify button is held down */
    var modifyHeld = false
    /** a canvas touch happened while □ was held, so letting go of □ is not a tap */
    var modifyUsed = false
    /** grey view: the artwork shown in greys, to check values */
    var greyView = false
        set(v) { field = v; invalidate() }
    private val greyPaint = Paint().apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
    }
    /** the dual brush's mask for the current stroke (null: no dual brush) */
    private var strokeMask: Bitmap? = null
    private var maskBitmap: Bitmap? = null
    private val maskDstIn = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_IN }

    /** what the current stroke was told to do by a gesture (erase, smudge, follow the guide) */
    private var strokeOverride: GesturePurpose? = null

    val currentBrush get() = when (tool) {
        Tool.ERASER -> eraserBrush
        Tool.SMUDGE -> smudgeBrush
        Tool.BRUSH -> paintBrush
    }

    var interaction = Interaction.PAINT
        set(v) { field = v; invalidate() }
    var selectShape = SelectShape.FREEHAND
    /** new selection, add to it or take away from it */
    var selectCombine = SelectCombine.NEW
    /** how different a color may be for the magic wand and color range (0..1) */
    var wandTolerance = 0.18f
    /** width of the selection brush, in screen pixels (it follows the zoom) */
    var selectBrushPx = 40f
    var selection: Selection? = null
        set(v) {
            field = v
            invalidate()
            listener?.onSelectionChanged()
        }
    var transform: TransformSession? = null
        private set

    /** layers picked in the Layers panel (swipe right), besides the active one */
    val pickedLayers = LinkedHashSet<Layer>()

    /** the active layer plus the picked ones still in the document, bottom first */
    fun targetLayers(): List<Layer> {
        pickedLayers.retainAll(doc.layers.toSet())
        if (pickedLayers.isEmpty()) return listOf(doc.active)
        val set = pickedLayers + doc.active
        return doc.layers.filter { it in set }
    }

    // ---- view transform: document -> screen ----
    val viewMatrix = Matrix()
    private val inverse = Matrix()
    private var flipped = false

    // ---- paints ----
    private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plainBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** texture paint (DST_IN) for the current stroke, null = no grain */
    private var strokeGrain: Paint? = null
    /** the current stroke's watercolor look: granulation, wet edge (null: none) */
    private var strokeLook: StrokeLook? = null

    /** 1 = linear; <1 = soft pen (less force needed); >1 = firm */
    var pressureGamma = 1f
    /** the brush outline where the pen hovers (Actions › Prefs › Brush cursor) */
    var showBrushCursor = true
    /** watercolor strokes dry when the pen lifts (Actions › Prefs › Watercolor drying) */
    var watercolorDrying = false
    private val commitPaint = Paint()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val checkerPaint = Paint().apply {
        val tile = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val c = Canvas(tile)
        c.drawColor(0xFFFFFFFF.toInt())
        val p = Paint().apply { color = 0xFFDADADA.toInt() }
        c.drawRect(0f, 0f, 16f, 16f, p)
        c.drawRect(16f, 16f, 32f, 32f, p)
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC6E9FF5.toInt()
        style = Paint.Style.STROKE
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- input state ----
    private enum class Mode { NONE, DRAW, GESTURE, PICK, FINGER_WAIT, IGNORE, VP_DRAG, SELECT_DRAW, TRANSFORM_DRAG, TRANSFORM_PINCH, VECTOR_DRAG, PAN, HOVER_ADJ, LIQUIFY_DRAG, GRADIENT_DRAG, MEASURE_DRAG, SHAPE_DRAG, PATH_DRAW }

    // ---- vector layers ----
    val vectorEditor: VectorEditor by lazy {
        VectorEditor(object : VectorEditor.Host {
            override val doc: Document get() = this@CanvasView.doc
            override fun toDoc(x: Float, y: Float) = this@CanvasView.toDoc(x, y)
            override fun toScreen(x: Float, y: Float): PointF {
                val p = floatArrayOf(x, y)
                viewMatrix.mapPoints(p)
                return PointF(p[0], p[1])
            }
            override fun viewMatrix() = viewMatrix
            override fun zoom() = this@CanvasView.zoom()
            override fun density() = resources.displayMetrics.density
            override fun redraw() = invalidate()
            override fun push(cmd: Command) = pushCommand(cmd)
            override fun message(s: String) { listener?.onMessage(s) }
            override fun currentColor() = color
            override fun selectionChanged() { listener?.onVectorSelectionChanged() }
        })
    }

    // ---- selection drawing ----
    private val selPts = ArrayList<FloatArray>()
    private var selX0 = 0f
    private var selY0 = 0f
    private var selX1 = 0f
    private var selY1 = 0f
    private var selDrawing = false
    private val antsWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val antsBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }

    // ---- transform ----
    private enum class Drag { MOVE, SCALE, ROTATE, SIDE, ALIGN }
    private val tBoxStart = Matrix()
    private var tSide = 0
    private var tAlignStart = 0f
    /** snap a moved piece to the canvas (center, edges) and to what is on the other layers */
    var transformSnap = true
    private var snapX: Float? = null
    private var snapY: Float? = null
    private var snapXCanvas = true
    private var snapYCanvas = true
    private var snapTargets: Pair<List<Float>, List<Float>>? = null
    /** a tap (no drag) on the green dot (true) or on a corner or side (false): ask for an exact number */
    var onTransformExact: ((angle: Boolean) -> Unit)? = null
    private var tDrag = Drag.MOVE
    private val tStart = Matrix()
    private var tDown = PointF()
    private var tCenter = PointF()
    private var tP0 = PointF()
    private var tP1 = PointF()
    private val transformPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.ACCENT }

    // ---- smudge ----
    private var smudging = false
    /** this stroke is painted by the living-brush engine (MyPaint), straight onto the layer */
    private var livingStroke = false
    private val living = LivingPainter()
    /** the time (ms) of the pen position being added */
    private var pointTime = 0L
    private var smudger: Smudger? = null
    private var smudgeBefore: Bitmap? = null

    // ---- adjustments preview ----
    private var adjustLayer: Layer? = null
    private var adjustOrig: Bitmap? = null
    private var adjustRegion: Rect? = null

    // ---- drawing guide ----
    /** true while the user drags the vanishing points */
    var editingGuide = false
        set(v) { field = v; invalidate() }
    private var guideGeom: GuideGeometry? = null
    private var guideGeomVersion = -1
    private var guideGeomDoc: Document? = null
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragVp = -1
    // Assisted Drawing
    private var snapPending = false
    private val pendingPts = ArrayList<InputPoint>()
    private var constraint: Constraint? = null
    private val projBuf = FloatArray(2)
    private var mode = Mode.NONE
    private var drawPointerId = -1
    private var drawIsStylus = false
    private var strokeActive = false
    private var strokeErasing = false
    private val strokePoints = ArrayList<InputPoint>()
    private var downTime = 0L
    private val density = resources.displayMetrics.density

    // QuickShape
    private var holdAnchorX = 0f
    private var holdAnchorY = 0f
    private var shape: Shape? = null
    private var shapeAnchor = PointF()
    private var shapeSeed = 0
    private var shapePressure = 1f
    private val holdRunnable = Runnable { onHold() }

    // pen held still right where it touched down = color wheel
    private var pressX = 0f
    private var pressY = 0f
    private var pressMoved = false
    private val longPressRunnable = Runnable {
        if (strokeActive && !pressMoved && shape == null) {
            val purpose = purposeOf(if (drawIsStylus) GestureTrigger.PEN_STILL else GestureTrigger.HOLD)
            if (purpose != null) {
                cancelStroke() // removes the dot that was drawn on touch-down
                when (purpose) {
                    GesturePurpose.QUICKMENU -> if (drawIsStylus) listener?.onPenLongPress(pressX, pressY)
                        else listener?.onGesture(purpose, pressX, pressY)
                    GesturePurpose.EYEDROPPER -> { mode = Mode.PICK; pickAt(pressX, pressY) }
                    else -> { runPurpose(purpose, pressX, pressY); if (mode != Mode.PICK) mode = Mode.IGNORE }
                }
            }
        }
    }

    private fun purposeOf(t: GestureTrigger): GesturePurpose? = gestures?.purposeOf(t)

    /** what a pen stroke is set to do instead of the tool, if anything (erase, smudge, follow the guide) */
    private fun penOverride(): GesturePurpose? =
        purposeOf(if (modifyHeld) GestureTrigger.MODIFY_PEN else GestureTrigger.PEN)
            ?.takeIf { it == GesturePurpose.SMUDGE || it == GesturePurpose.ERASE || it == GesturePurpose.ASSISTED }
    private fun gOn(p: GesturePurpose, key: String, default: Boolean) = gestures?.isOn(p, key) ?: default
    private fun delayMs(p: GesturePurpose?, fallback: Long) = if (p == null) fallback else gestures?.delayMs(p) ?: fallback

    /** does what a gesture asks: on the canvas when it can, else the screen around it */
    fun runPurpose(p: GesturePurpose, sx: Float, sy: Float) {
        when (p) {
            GesturePurpose.EYEDROPPER -> armEyedropper()
            GesturePurpose.LAYER_SELECT -> selectLayerAt(sx, sy)
            GesturePurpose.QUICKSHAPE -> snapLastStroke()
            else -> listener?.onGesture(p, sx, sy)
        }
    }

    // gestures
    private val gestureStart = Matrix()
    private var gStartMidX = 0f
    private var gStartMidY = 0f
    private var gStartDist = 1f
    private var gStartAngle = 0f
    private var gRotating = false
    private var gMaxPointers = 0
    private var gStartTime = 0L
    private var gMoved = 0f
    private val gDown = HashMap<Int, PointF>()
    private val tapStartMatrix = Matrix()
    private var gLastScale = 1f
    /** where the two fingers of the last pinch were (for a quick pinch out to 100%) */
    private var gPinchX = 0f
    private var gPinchY = 0f
    /**
     * Total zoom of the whole two-finger gesture. Unlike [gLastScale] it survives one finger
     * lifting before the other (fingers rarely leave the screen at the same moment).
     */
    private var gPinchTotal = 1f
    private var gPinchBase = 1f
    private var gPointers = 0
    /** a three-finger swipe or scrub, or rapid undo, already did something in this gesture */
    private var gFired = false
    private var scrubDir = 0
    private var scrubTurns = 0
    private var scrubRefX = 0f
    private var lastHoverAt = 0L
    // one finger moving the canvas
    private var panX = 0f
    private var panY = 0f
    // hover + finger: opacity and size
    private var hoverStartX = 0f
    private var hoverStartY = 0f
    private var hoverStartOpacity = 1f
    private var hoverStartSize = 10f
    private var hoverStartDist = 0f
    /** four fingers held still: the workspaces to switch to */
    private val fourHoldRunnable = Runnable {
        if (mode != Mode.GESTURE || gMoved > 24f * density || gPointers != 4 || gFired) return@Runnable
        gFired = true
        viewMatrix.set(tapStartMatrix)
        invalidate()
        var x = 0f; var y = 0f
        for (d in gDown.values) { x += d.x; y += d.y }
        val n = gDown.size.coerceAtLeast(1)
        listener?.onFourFingerHold(x / n, y / n)
    }

    private val rapidRunnable = object : Runnable {
        override fun run() {
            if (mode != Mode.GESTURE || gMoved > 24f * density) return
            val n = gPointers
            if (n == 2) undo() else if (n == 3) redo() else return
            gFired = true
            postDelayed(this, 120)
        }
    }

    // eyedropper
    private var pickX = 0f
    private var pickY = 0f
    private var pickColor = 0
    private val fingerHoldRunnable = Runnable {
        if (mode == Mode.FINGER_WAIT) {
            when (val p = purposeOf(GestureTrigger.HOLD)) {
                null -> {}
                GesturePurpose.EYEDROPPER -> { mode = Mode.PICK; pickAt(pickX, pickY) }
                else -> { mode = Mode.IGNORE; runPurpose(p, pickX, pickY) }
            }
        }
    }

    // hover cursor (S Pen)
    private var hovering = false
    private var hoverX = 0f
    private var hoverY = 0f
    private var hoverTilt = 0f
    private var hoverOrientation = 0f

    private val tmpPts = FloatArray(2)

    companion object {
        /** Inkora's own clipboard: shared by every artwork while the app runs */
        private var clipboard: Bitmap? = null
        private var clipboardAt = android.graphics.Point(0, 0)
    }

    // =========================================================
    // document
    // =========================================================

    /** the layers' visibility before "this layer alone" (Layers: hold a checkbox), to put back */
    var soloSaved: Map<Layer, Boolean>? = null

    fun setDocument(d: Document) {
        doc = d
        pickedLayers.clear()
        soloSaved = null
        // nothing of the artwork before stays behind: an open liquify, wet paint, the last stroke
        liquify = null
        liqShowX = -1f
        wetMaps.clear()
        lastStroke = null
        selDrawing = false
        mode = Mode.NONE
        history = History()
        strokeBitmap?.recycle()
        strokeBitmap = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
        strokeTiles = TileMirror(d.width, d.height)
        strokeErased()
        strokeActive = false
        if (width > 0) fitToScreen() else post { fitToScreen() }
        listener?.onHistoryChanged()
        invalidate()
    }

    fun isDocReady() = ::doc.isInitialized

    fun fitToScreen() {
        if (!isDocReady() || width == 0) return
        val s = min(width / doc.width.toFloat(), height / doc.height.toFloat()) * 0.9f
        viewMatrix.reset()
        viewMatrix.postTranslate(-doc.width / 2f, -doc.height / 2f)
        viewMatrix.postScale(if (flipped) -s else s, s)
        viewMatrix.postTranslate(width / 2f, height / 2f)
        invalidate()
    }

    /**
     * 100% zoom: one canvas pixel = one screen pixel, upright, keeping the canvas point under
     * ([fx], [fy]) where it is (the screen center by default).
     */
    fun actualSize(fx: Float = width / 2f, fy: Float = height / 2f) {
        if (!isDocReady() || width == 0) return
        val inv = Matrix()
        viewMatrix.invert(inv)
        val p = floatArrayOf(fx, fy)
        inv.mapPoints(p)
        viewMatrix.reset()
        viewMatrix.postTranslate(-p[0], -p[1])
        if (flipped) viewMatrix.postScale(-1f, 1f)
        viewMatrix.postTranslate(fx, fy)
        invalidate()
        listener?.onMessage("Actual size · 100%")
    }

    fun flipHorizontal() {
        flipped = !flipped
        viewMatrix.postScale(-1f, 1f, width / 2f, height / 2f)
        invalidate()
    }

    /** current zoom (screen px per document px) */
    fun zoom(): Float {
        val v = floatArrayOf(0f, 0f, 1f, 0f)
        viewMatrix.mapPoints(v)
        return hypot(v[2] - v[0], v[3] - v[1])
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 || oldh == 0) fitToScreen()
        else {
            viewMatrix.postTranslate((w - oldw) / 2f, (h - oldh) / 2f)
            invalidate()
        }
    }

    /** a transform, an adjustment or liquify is open: the layers must not change under it */
    val isEditing get() = transform != null || adjustOrig != null || liquify != null

    private fun busy(): Boolean {
        if (isEditing) {
            listener?.onMessage("Finish or cancel the current edit first")
            return true
        }
        return false
    }

    fun historyMark(): Long = history.mark

    /** takes back every change made since [mark] (a tool's Cancel) */
    fun undoTo(mark: Long) {
        if (strokeActive) cancelStroke()
        var n = 0
        while (history.mark > mark && history.canUndo && n < 500) { history.undo(doc); n++ }
        if (n > 0) {
            vectorEditor.reset()
            listener?.onHistoryChanged()
            invalidate()
        }
    }

    val undoSteps get() = history.undoCount
    val redoSteps get() = history.redoCount
    fun historyLabel(i: Int) = history.labelAt(i)

    /** goes back or forward through the history until [pos] steps are done (the history scrubber) */
    fun stepTo(pos: Int) {
        if (busy()) return
        if (strokeActive) cancelStroke()
        var changed = false
        var n = 0
        while (history.undoCount > pos && n < 1000) { if (!history.undo(doc)) break; changed = true; n++ }
        while (history.undoCount < pos && n < 1000) { if (!history.redo(doc)) break; changed = true; n++ }
        if (changed) {
            vectorEditor.reset()
            listener?.onHistoryChanged()
            invalidate()
        }
    }

    fun undo() {
        if (busy()) return
        if (strokeActive) cancelStroke()
        val what = history.undoLabel
        if (history.undo(doc)) {
            vectorEditor.reset()
            listener?.onMessage("Undo: " + L10n.tr(what ?: "drawing"))
            listener?.onHistoryChanged()
            invalidate()
        }
    }

    fun redo() {
        if (busy()) return
        if (strokeActive) cancelStroke()
        val what = history.redoLabel
        if (history.redo(doc)) {
            vectorEditor.reset()
            listener?.onMessage("Redo: " + L10n.tr(what ?: "drawing"))
            listener?.onHistoryChanged()
            invalidate()
        }
    }

    /** Records a pixel change of a whole layer (used by layer actions like Clear). */
    fun pushPixelEdit(layer: Layer, rect: Rect, before: Bitmap) {
        val after = Bitmap.createBitmap(layer.bitmap, rect.left, rect.top, rect.width(), rect.height())
        history.push(PixelEdit(layer, Rect(rect), before, after), doc)
        listener?.onHistoryChanged()
        invalidate()
    }

    fun pushCommand(cmd: Command) {
        history.push(cmd, doc)
        listener?.onHistoryChanged()
        invalidate()
    }

    // =========================================================
    // drawing
    // =========================================================

    override fun onDraw(canvas: Canvas) {
        if (!Perf.on) { drawAll(canvas); return }
        val t = System.nanoTime()
        drawAll(canvas)
        Perf.draws++; Perf.drawNs += System.nanoTime() - t
    }

    private fun drawAll(canvas: Canvas) {
        canvas.drawColor(Ui.CANVAS_BG)
        if (!isDocReady()) return
        val z = zoom()
        canvas.save()
        canvas.concat(viewMatrix)

        // drop shadow + transparent checkerboard
        shadowPaint.setShadowLayer(18f / z, 0f, 4f / z, 0x88000000.toInt())
        canvas.drawRect(doc.bounds, shadowPaint)
        if (!doc.backgroundVisible) {
            canvas.save()
            canvas.scale(1f / z, 1f / z)
            canvas.drawRect(0f, 0f, doc.width * z, doc.height * z, checkerPaint)
            canvas.restore()
        }

        val filter = z < 2f
        val sb = strokeBitmap
        val t = transform
        val greySave = if (greyView) canvas.saveLayer(doc.bounds, greyPaint) else -1
        if (strokeActive && !smudging && !livingStroke && sb != null) {
            overlayPaint.alpha = (engine.brush.opacity * 255).toInt().coerceIn(0, 255)
            overlayPaint.blendMode = strokeBlend()
            overlayPaint.isFilterBitmap = filter
            val grain = strokeGrain
            val look = strokeLook
            val sel = selection
            val mask = strokeMask
            // the dual brush's mask changes every frame too: then the plain picture (no tiles)
            val tiled = mask == null && syncTiles(sb)
            doc.draw(canvas, filter, { c ->
                if (grain == null && sel == null && mask == null && look == null) drawStrokePicture(c, sb, overlayPaint, tiled)
                else {
                    // stroke × dual brush × paper texture × selection, then blended into the layer
                    val s = c.saveLayer(doc.bounds, overlayPaint)
                    val gi = if (look?.layer != null) c.saveLayer(doc.bounds, look.layer) else -1
                    drawStrokePicture(c, sb, plainBitmapPaint, tiled)
                    if (mask != null) c.drawBitmap(mask, 0f, 0f, maskDstIn)
                    if (grain != null) c.drawRect(doc.bounds, grain)
                    if (gi >= 0) c.restoreToCount(gi)
                    look?.tone?.let { drawStrokePicture(c, sb, it, tiled) }
                    look?.core?.let { drawStrokePicture(c, sb, it, tiled) }
                    if (sel != null) c.drawBitmap(sel.mask, 0f, 0f, Selections.dstIn)
                    c.restoreToCount(s)
                }
            })
        } else if (interaction == Interaction.GRADIENT && gradientShader() != null) {
            // the gradient as it will be, on the layer, while the finger drags
            val sh = gradientShader()
            val sel = selection
            doc.draw(canvas, filter, { c ->
                val s = c.saveLayer(doc.bounds, null)
                gradPaint.shader = sh
                c.drawRect(doc.bounds, gradPaint)
                if (sel != null) c.drawBitmap(sel.mask, 0f, 0f, Selections.dstIn)
                c.restoreToCount(s)
            })
        } else if (t != null) {
            val ov = HashMap<Layer, ActiveOverlay>()
            ov[t.layer] = ActiveOverlay { c -> t.drawPiece(c, t.floating, transformPaint) }
            for (p in t.extras) ov[p.layer] = ActiveOverlay { c -> t.drawPiece(c, p.floating, transformPaint) }
            doc.draw(canvas, filter, null, extra = ov)
        } else {
            doc.draw(canvas, filter)
        }
        if (greySave >= 0) canvas.restoreToCount(greySave)

        drawGuide(canvas, z)
        if (symmetry != Symmetry.OFF) drawSymmetryGuides(canvas, z)
        drawSelection(canvas, z)
        canvas.restore()

        if (t != null) drawTransformBox(canvas, t)
        if (capturePts.size > 1) {
            val pts = FloatArray(capturePts.size * 2)
            capturePts.forEachIndexed { i, q -> pts[i * 2] = q.x; pts[i * 2 + 1] = q.y }
            viewMatrix.mapPoints(pts)
            val path = android.graphics.Path(); path.moveTo(pts[0], pts[1])
            for (i in 1 until capturePts.size) path.lineTo(pts[i * 2], pts[i * 2 + 1])
            boxPaint.style = Paint.Style.STROKE; boxPaint.strokeWidth = 2f * density
            canvas.drawPath(path, boxPaint)
        }
        if (interaction == Interaction.VECTOR && doc.active.vector != null) vectorEditor.drawOverlay(canvas)
        if (editingGuide) drawVpHandles(canvas)

        if (hovering && !strokeActive && showBrushCursor) {
            val r = max(3f, currentBrush.size * z / 2f)
            cursorPaint.style = Paint.Style.STROKE
            cursorPaint.strokeWidth = 3f
            cursorPaint.color = 0x66000000
            canvas.drawCircle(hoverX, hoverY, r, cursorPaint)
            cursorPaint.strokeWidth = 1.5f
            cursorPaint.color = 0xDDFFFFFF.toInt()
            canvas.drawCircle(hoverX, hoverY, r, cursorPaint)

            // Center dot
            cursorPaint.style = Paint.Style.FILL
            cursorPaint.color = if (tool == Tool.ERASER) 0xFFFF5555.toInt() else color
            canvas.drawCircle(hoverX, hoverY, 2.5f, cursorPaint)

            // Stylus tilt orientation vector
            if (hoverTilt > 0.1f) {
                val tiltLen = (hoverTilt * 24f).coerceIn(8f, 36f)
                // orientation 0 = pen pointing up, clockwise positive
                val ox = sin(hoverOrientation); val oy = -cos(hoverOrientation)
                val tx = hoverX + ox * (r + tiltLen)
                val ty = hoverY + oy * (r + tiltLen)
                cursorPaint.style = Paint.Style.STROKE
                cursorPaint.strokeWidth = 2f
                cursorPaint.color = 0xAA4C8DFF.toInt()
                canvas.drawLine(hoverX + ox * r, hoverY + oy * r, tx, ty, cursorPaint)
            }
        }

        drawGradientLine(canvas)
        drawMeasure(canvas)
        if (mode == Mode.PICK) drawLoupe(canvas)
        drawLiquifyBrush(canvas)
    }

    /** how dark what is outside the selection looks (0 = only the marching ants) */
    /** fill gap closing, in canvas pixels (0 = off): a shape with small gaps in its lines still fills inside */
    var fillGap = 0
    var selectionShade = 0.3f
    private val shadePaint = Paint()

    private fun drawSelection(canvas: Canvas, z: Float) {
        val sel = selection
        if (sel != null && !selDrawing && transform == null && selectionShade > 0.01f) {
            val s = canvas.saveLayer(doc.bounds, null)
            shadePaint.color = Color.argb((selectionShade * 255).toInt(), 10, 12, 18)
            canvas.drawRect(doc.bounds, shadePaint)
            canvas.drawBitmap(sel.mask, 0f, 0f, Selections.dstOut)
            canvas.restoreToCount(s)
        }
        val path: android.graphics.Path? = when {
            selDrawing && selectShape == SelectShape.BRUSH -> Selections.brushPath(selPts, selectBrushPx / zoom())
            selDrawing -> Selections.shapePath(selectShape, selPts, selX0, selY0, selX1, selY1)
            selection != null && transform == null -> selection!!.outline
            else -> null
        }
        if (path == null) return
        antsWhite.strokeWidth = 1.6f * density / z
        antsBlack.strokeWidth = 1.6f * density / z
        val dash = 6f * density / z
        val phase = (System.currentTimeMillis() / 45 % 1000) * (density / z)
        antsBlack.pathEffect = DashPathEffect(floatArrayOf(dash, dash), phase)
        canvas.drawPath(path, antsWhite)
        canvas.drawPath(path, antsBlack)
        // keep the ants marching
        if (!selDrawing) postInvalidateDelayed(70)
    }

    /** screen-space box with corner handles and a rotation handle */
    /** Distort: the four free corners joined; Warp: the 4 × 4 grid of handles */
    private fun drawShapeHandles(canvas: Canvas, t: TransformSession, doc: FloatArray) {
        val h = doc.copyOf(); viewMatrix.mapPoints(h)
        boxPaint.strokeWidth = 1.5f * density
        boxPaint.style = Paint.Style.STROKE
        if (t.shape == TransformSession.Shape.DISTORT) {
            for (i in 0 until 4) { val j = (i + 1) % 4; canvas.drawLine(h[i * 2], h[i * 2 + 1], h[j * 2], h[j * 2 + 1], boxPaint) }
        } else {
            for (j in 0 until 4) for (i in 0 until 4) {
                val k = j * 4 + i
                if (i < 3) canvas.drawLine(h[k * 2], h[k * 2 + 1], h[(k + 1) * 2], h[(k + 1) * 2 + 1], boxPaint)
                if (j < 3) canvas.drawLine(h[k * 2], h[k * 2 + 1], h[(k + 4) * 2], h[(k + 4) * 2 + 1], boxPaint)
            }
        }
        for (k in 0 until h.size / 2) {
            handlePaint.style = Paint.Style.FILL; handlePaint.color = Color.WHITE
            canvas.drawCircle(h[k * 2], h[k * 2 + 1], 7f * density, handlePaint)
            handlePaint.style = Paint.Style.STROKE; handlePaint.color = Ui.ACCENT; handlePaint.strokeWidth = 2f * density
            canvas.drawCircle(h[k * 2], h[k * 2 + 1], 7f * density, handlePaint)
        }
        handlePaint.style = Paint.Style.FILL
    }

    private fun drawTransformBox(canvas: Canvas, t: TransformSession) {
        drawSnapGuides(canvas)
        t.shapeHandles()?.let { drawShapeHandles(canvas, t, it); return }
        val c = t.corners()
        viewMatrix.mapPoints(c)
        boxPaint.strokeWidth = 1.5f * density
        boxPaint.style = Paint.Style.STROKE
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            canvas.drawLine(c[i * 2], c[i * 2 + 1], c[j * 2], c[j * 2 + 1], boxPaint)
        }
        val rh = rotateHandle(c)
        canvas.drawLine((c[0] + c[2]) / 2, (c[1] + c[3]) / 2, rh.x, rh.y, boxPaint)
        val ah = alignHandle(c)
        canvas.drawLine((c[4] + c[6]) / 2, (c[5] + c[7]) / 2, ah.x, ah.y, boxPaint)
        handlePaint.style = Paint.Style.FILL
        // the sides: pull one to stretch along the box
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val mx = (c[i * 2] + c[j * 2]) / 2; val my = (c[i * 2 + 1] + c[j * 2 + 1]) / 2
            val ang = Math.toDegrees(atan2((c[j * 2 + 1] - c[i * 2 + 1]).toDouble(), (c[j * 2] - c[i * 2]).toDouble())).toFloat()
            canvas.save(); canvas.rotate(ang, mx, my)
            handlePaint.color = Color.WHITE
            canvas.drawRoundRect(mx - 10f * density, my - 5f * density, mx + 10f * density, my + 5f * density, 4f * density, 4f * density, handlePaint)
            handlePaint.color = Ui.ACCENT
            canvas.drawRoundRect(mx - 7.5f * density, my - 2.5f * density, mx + 7.5f * density, my + 2.5f * density, 2.5f * density, 2.5f * density, handlePaint)
            canvas.restore()
        }
        handlePaint.color = Color.WHITE
        canvas.drawCircle(ah.x, ah.y, 9f * density, handlePaint)
        handlePaint.color = 0xFFFFC53D.toInt()
        canvas.drawCircle(ah.x, ah.y, 6.5f * density, handlePaint)
        for (i in 0 until 4) {
            handlePaint.color = Color.WHITE
            canvas.drawCircle(c[i * 2], c[i * 2 + 1], 9f * density, handlePaint)
            handlePaint.color = Ui.ACCENT
            canvas.drawCircle(c[i * 2], c[i * 2 + 1], 6f * density, handlePaint)
        }
        handlePaint.color = Color.WHITE
        canvas.drawCircle(rh.x, rh.y, 10f * density, handlePaint)
        handlePaint.color = 0xFF2ECC71.toInt()
        canvas.drawCircle(rh.x, rh.y, 7f * density, handlePaint)
    }

    /** yellow dot below the middle of the bottom edge: turns the box only */
    private fun alignHandle(c: FloatArray): PointF {
        val mx = (c[4] + c[6]) / 2; val my = (c[5] + c[7]) / 2
        val cx = (c[0] + c[4]) / 2; val cy = (c[1] + c[5]) / 2
        var dx = mx - cx; var dy = my - cy
        val l = max(1f, hypot(dx, dy))
        dx /= l; dy /= l
        return PointF(mx + dx * 34f * density, my + dy * 34f * density)
    }

    /** orange: lines up with the canvas; blue: with something on another layer */
    private fun drawSnapGuides(canvas: Canvas) {
        if (mode != Mode.TRANSFORM_DRAG || tDrag != Drag.MOVE) return
        val sx = snapX; val sy = snapY
        if (sx == null && sy == null) return
        handlePaint.style = Paint.Style.STROKE
        handlePaint.strokeWidth = 1.2f * density
        if (sx != null) {
            handlePaint.color = if (snapXCanvas) 0xFFFF9F43.toInt() else 0xFF4FC3F7.toInt()
            val a = floatArrayOf(sx, 0f, sx, doc.height.toFloat()); viewMatrix.mapPoints(a)
            canvas.drawLine(a[0], a[1], a[2], a[3], handlePaint)
        }
        if (sy != null) {
            handlePaint.color = if (snapYCanvas) 0xFFFF9F43.toInt() else 0xFF4FC3F7.toInt()
            val a = floatArrayOf(0f, sy, doc.width.toFloat(), sy); viewMatrix.mapPoints(a)
            canvas.drawLine(a[0], a[1], a[2], a[3], handlePaint)
        }
        handlePaint.style = Paint.Style.FILL
    }

    /** green dot above the middle of the top edge */
    private fun rotateHandle(c: FloatArray): PointF {
        val mx = (c[0] + c[2]) / 2; val my = (c[1] + c[3]) / 2
        val cx = (c[0] + c[4]) / 2; val cy = (c[1] + c[5]) / 2
        var dx = mx - cx; var dy = my - cy
        val l = max(1f, hypot(dx, dy))
        dx /= l; dy /= l
        return PointF(mx + dx * 40f * density, my + dy * 40f * density)
    }

    private fun drawSymmetryGuides(canvas: Canvas, z: Float) {
        guidePaint.strokeWidth = 1.5f / z
        guidePaint.pathEffect = DashPathEffect(floatArrayOf(10f / z, 6f / z), 0f)
        val cx = doc.width / 2f
        val cy = doc.height / 2f
        when (symmetry) {
            Symmetry.VERTICAL -> canvas.drawLine(cx, 0f, cx, doc.height.toFloat(), guidePaint)
            Symmetry.HORIZONTAL -> canvas.drawLine(0f, cy, doc.width.toFloat(), cy, guidePaint)
            Symmetry.QUADRANT -> {
                canvas.drawLine(cx, 0f, cx, doc.height.toFloat(), guidePaint)
                canvas.drawLine(0f, cy, doc.width.toFloat(), cy, guidePaint)
            }
            Symmetry.RADIAL, Symmetry.MANDALA_12, Symmetry.MANDALA_16 -> {
                val n = symmetry.axes
                val r = hypot(cx, cy)
                for (k in 0 until n) {
                    val a = Math.PI * 2 * k / n
                    canvas.drawLine(cx, cy, cx + (Math.cos(a) * r).toFloat(), cy + (Math.sin(a) * r).toFloat(), guidePaint)
                }
            }
            Symmetry.KALEIDOSCOPE -> {
                val n = Symmetry.segments.coerceIn(2, 32)
                val r = hypot(cx, cy)
                for (k in 0 until n) {
                    val a = Math.PI * 2 * k / n
                    canvas.drawLine(cx, cy, cx + (Math.cos(a) * r).toFloat(), cy + (Math.sin(a) * r).toFloat(), guidePaint)
                }
            }
            Symmetry.OFF -> {}
        }
        guidePaint.pathEffect = null
    }

    private fun drawGuide(canvas: Canvas, z: Float) {
        val g = doc.guide
        if (g.type == GuideType.OFF) return
        var geo = guideGeom
        if (geo == null || guideGeomVersion != g.version || guideGeomDoc !== doc) {
            geo = Guides.build(g, doc.width, doc.height)
            guideGeom = geo
            guideGeomVersion = g.version
            guideGeomDoc = doc
        }
        gridPaint.color = g.color
        gridPaint.alpha = (g.opacity * 255).toInt().coerceIn(0, 255)
        gridPaint.strokeWidth = g.thicknessDp.dp / z
        gridPaint.strokeCap = Paint.Cap.BUTT
        canvas.save()
        canvas.clipRect(doc.bounds)
        if (geo.lines.isNotEmpty()) canvas.drawLines(geo.lines, gridPaint)
        var i = 0
        while (i + 2 < geo.circles.size) {
            canvas.drawCircle(geo.circles[i], geo.circles[i + 1], geo.circles[i + 2], gridPaint)
            i += 3
        }
        if (geo.points.isNotEmpty()) {
            gridPaint.strokeCap = Paint.Cap.ROUND
            gridPaint.strokeWidth = (g.thicknessDp * 3.5f).dp / z
            canvas.drawPoints(geo.points, gridPaint)
            gridPaint.strokeCap = Paint.Cap.BUTT
        }
        canvas.restore()
    }

    private fun vpScreen(i: Int): PointF {
        tmpPts[0] = doc.guide.vpX(i, doc.width)
        tmpPts[1] = doc.guide.vpY(i, doc.height)
        viewMatrix.mapPoints(tmpPts)
        return PointF(tmpPts[0], tmpPts[1])
    }

    private fun drawVpHandles(canvas: Canvas) {
        val ids = doc.guide.type.vanishingPoints
        ids.forEachIndexed { n, id ->
            val p = vpScreen(id)
            handlePaint.style = Paint.Style.FILL
            handlePaint.color = Ui.ACCENT
            canvas.drawCircle(p.x, p.y, 16f * density, handlePaint)
            handlePaint.style = Paint.Style.STROKE
            handlePaint.strokeWidth = 3f * density
            handlePaint.color = Color.WHITE
            canvas.drawCircle(p.x, p.y, 16f * density, handlePaint)
            handlePaint.style = Paint.Style.FILL
            handlePaint.textSize = 15f * density
            handlePaint.textAlign = Paint.Align.CENTER
            canvas.drawText("${n + 1}", p.x, p.y + 5f * density, handlePaint)
        }
    }

    private fun hitVp(sx: Float, sy: Float): Int {
        var best = -1
        var bestD = 44f * density
        for (id in doc.guide.type.vanishingPoints) {
            val p = vpScreen(id)
            val d = hypot(p.x - sx, p.y - sy)
            if (d < bestD) { bestD = d; best = id }
        }
        return best
    }

    private fun assistedActive() = doc.guide.assisted && doc.guide.type != GuideType.OFF

    private fun snapped(p: InputPoint): InputPoint {
        val c = constraint ?: return p
        c.project(p.x, p.y, projBuf)
        return InputPoint(projBuf[0], projBuf[1], p.pressure, p.tilt)
    }

    private fun drawLoupe(canvas: Canvas) {
        val r = 56f * density
        val y = pickY - r - 30f * density
        loupePaint.style = Paint.Style.STROKE
        loupePaint.strokeWidth = 22f * density
        loupePaint.color = pickColor or 0xFF000000.toInt()
        canvas.drawArc(pickX - r, y - r, pickX + r, y + r, 180f, 180f, false, loupePaint)
        loupePaint.color = color or 0xFF000000.toInt()
        canvas.drawArc(pickX - r, y - r, pickX + r, y + r, 0f, 180f, false, loupePaint)
        loupePaint.strokeWidth = 2f * density
        loupePaint.color = Color.WHITE
        val outer = r + 11f * density
        canvas.drawCircle(pickX, y, outer, loupePaint)
        canvas.drawCircle(pickX, y, r - 11f * density, loupePaint)
    }

    private fun strokeBlend(): BlendMode = when {
        strokeErasing -> BlendMode.DST_OUT
        doc.active.alphaLock -> BlendMode.SRC_ATOP
        // on a mask paint is white ("show"): a glazing brush would multiply it away
        doc.active.isLayerMask -> BlendMode.SRC_OVER
        else -> engine.brush.dyn.blend.mode
    }

    // =========================================================
    // input
    // =========================================================

    private fun toDoc(x: Float, y: Float): PointF {
        viewMatrix.invert(inverse)
        tmpPts[0] = x; tmpPts[1] = y
        inverse.mapPoints(tmpPts)
        return PointF(tmpPts[0], tmpPts[1])
    }

    private fun isStylusTool(t: Int) = t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (!isStylusTool(e.getToolType(0))) return super.onHoverEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hovering = true
                lastHoverAt = android.os.SystemClock.uptimeMillis()
                hoverX = e.x
                hoverY = e.y
                hoverTilt = e.getAxisValue(MotionEvent.AXIS_TILT)
                hoverOrientation = e.getAxisValue(MotionEvent.AXIS_ORIENTATION)
                // some pens only report the side button through buttonState
                if (isPenButton(e.buttonState)) penPress() else penRelease()
                penListener?.onPenHover(e.x, e.y)
                if (interaction == Interaction.VECTOR) vectorEditor.onHover(e.x, e.y)
            }
            MotionEvent.ACTION_HOVER_EXIT -> hovering = false
        }
        invalidate()
        return true
    }

    // =========================================================
    // S Pen side button: click = brush/eraser, hold = quick menu
    // =========================================================

    interface PenListener {
        fun onPenClick()
        fun onPenDoubleClick(x: Float, y: Float)
        fun onPenHold(x: Float, y: Float)
        fun onPenHover(x: Float, y: Float)
        fun onPenRelease(x: Float, y: Float)
    }

    var penListener: PenListener? = null
    var penHoldMs = 600L
    private var penButtonDown = false
    private var penHoldFired = false
    private var penConsumed = false
    private val penHoldRunnable = Runnable {
        if (penButtonDown && !penConsumed) {
            penHoldFired = true
            penListener?.onPenHold(hoverX, hoverY)
        }
    }

    private var lastPenClick = 0L
    private val penSingleClick = Runnable { penListener?.onPenClick() }

    private fun isPenButton(state: Int) =
        state and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_SECONDARY) != 0

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (isStylusTool(e.getToolType(0))) {
            when (e.actionMasked) {
                MotionEvent.ACTION_BUTTON_PRESS -> { hoverX = e.x; hoverY = e.y; penPress(); return true }
                MotionEvent.ACTION_BUTTON_RELEASE -> { hoverX = e.x; hoverY = e.y; penRelease(); return true }
            }
        }
        return super.onGenericMotionEvent(e)
    }

    private fun penPress() {
        if (penButtonDown) return
        penButtonDown = true
        penHoldFired = false
        // pressed while drawing (a stroke, or a shape: then it makes the shape even) it does nothing else
        penConsumed = mode == Mode.DRAW || mode == Mode.SHAPE_DRAG
        removeCallbacks(penHoldRunnable)
        postDelayed(penHoldRunnable, penHoldMs)
    }

    private fun penRelease() {
        if (!penButtonDown) return
        penButtonDown = false
        removeCallbacks(penHoldRunnable)
        when {
            penHoldFired -> penListener?.onPenRelease(hoverX, hoverY)
            !penConsumed -> {
                // two quick clicks = quick menu; one click = brush ⇄ eraser (after a short wait)
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastPenClick < 330) {
                    removeCallbacks(penSingleClick)
                    lastPenClick = 0L
                    penListener?.onPenDoubleClick(hoverX, hoverY)
                } else {
                    lastPenClick = now
                    removeCallbacks(penSingleClick)
                    postDelayed(penSingleClick, 330)
                }
            }
        }
        penHoldFired = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isDocReady()) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onFirstDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> onLastUp(e)
            MotionEvent.ACTION_CANCEL -> {
                if (strokeActive) cancelStroke()
                if (mode == Mode.VECTOR_DRAG) vectorEditor.onCancel()
                selDrawing = false
                gradA = null; gradB = null
                removeCallbacks(fingerHoldRunnable)
                removeCallbacks(rapidRunnable); removeCallbacks(fourHoldRunnable)
                mode = Mode.NONE
                invalidate()
            }
        }
        return true
    }

    private fun onFirstDown(e: MotionEvent) {
        val penWasHovering = hovering && android.os.SystemClock.uptimeMillis() - lastHoverAt < 400
        hovering = false
        downTime = e.eventTime
        strokeOverride = null
        gFired = false
        if (pathCapture != null) {
            mode = Mode.PATH_DRAW
            capturePts.clear(); capturePts.add(toDoc(e.x, e.y))
            return
        }
        if (editingGuide) {
            dragVp = hitVp(e.x, e.y)
            mode = if (dragVp >= 0) Mode.VP_DRAG else Mode.IGNORE
            return
        }
        when (interaction) {
            Interaction.SELECT -> { startSelect(e); return }
            Interaction.TRANSFORM -> { startTransformDrag(e); return }
            Interaction.LOCKED -> { mode = Mode.IGNORE; return } // a second finger still zooms
            Interaction.VECTOR -> {
                if (doc.active.vector == null) { mode = Mode.IGNORE; return }
                val stylus = isStylusTool(e.getToolType(0))
                if (stylus && fingerDrawingAuto && fingerDrawing) fingerDrawing = false
                if (!stylus && !fingerDrawing) {
                    // fingers only zoom / pan on vector layers when a pen is used
                    mode = Mode.IGNORE
                    return
                }
                mode = Mode.VECTOR_DRAG
                vectorEditor.onDown(e.x, e.y)
                return
            }
            Interaction.LIQUIFY -> { startLiquify(e); return }
            Interaction.SHAPE -> {
                downTime = e.eventTime
                shapePointerId = e.getPointerId(0)
                shapePen = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                shapeFingerEven = false
                if (shapeDown(toDoc(e.x, e.y))) mode = Mode.SHAPE_DRAG else mode = Mode.IGNORE
                return
            }
            Interaction.MEASURE -> {
                mode = Mode.MEASURE_DRAG
                downTime = e.eventTime
                measureDown(e.x, e.y)
                return
            }
            Interaction.GRADIENT -> {
                mode = Mode.GRADIENT_DRAG
                downTime = e.eventTime
                gradA = toDoc(e.x, e.y); gradB = null
                return
            }
            Interaction.PAINT -> {}
        }
        val tt = e.getToolType(0)
        val stylus = isStylusTool(tt)
        if (stylus && (penButtonDown || isPenButton(e.buttonState))) {
            // drawing with the button held = erase; this is not a click
            penConsumed = true
            removeCallbacks(penHoldRunnable)
        }
        if (stylus && fingerDrawingAuto && fingerDrawing) {
            fingerDrawing = false
            listener?.onMessage("Pen detected: fingers now zoom & pan")
        }
        if (eyedropperArmed) {
            eyedropperArmed = false
            mode = Mode.PICK
            pickAt(e.x, e.y)
            return
        }
        // the pen hovering and a finger comes down: the finger sets opacity, two fingers the size
        if (!stylus && penWasHovering && gOn(GesturePurpose.HOVER, "HOVER_TOUCH", false)) {
            mode = Mode.HOVER_ADJ
            hoverStartX = e.x; hoverStartY = e.y
            hoverStartOpacity = currentBrush.opacity
            hoverStartSize = currentBrush.size
            hoverStartDist = 0f
            return
        }
        // what this way of touching is set to do (gesture controls)
        val trigger = when {
            modifyHeld -> if (stylus) GestureTrigger.MODIFY_PEN else GestureTrigger.MODIFY_TOUCH
            stylus -> GestureTrigger.PEN
            else -> GestureTrigger.TOUCH
        }
        if (modifyHeld) modifyUsed = true
        when (val purpose = purposeOf(trigger)) {
            null -> {}
            GesturePurpose.EYEDROPPER -> { mode = Mode.PICK; pickAt(e.x, e.y); return }
            GesturePurpose.SMUDGE, GesturePurpose.ERASE, GesturePurpose.ASSISTED -> {
                strokeOverride = purpose
                startStroke(e, 0, stylus)
                return
            }
            else -> { mode = Mode.IGNORE; runPurpose(purpose, e.x, e.y); return }
        }
        if (recolorMode && (stylus || fingerDrawing)) {
            mode = Mode.IGNORE
            recolorAt(e.x, e.y)
            return
        }
        if (fillMode && (stylus || fingerDrawing)) {
            mode = Mode.IGNORE
            colorDrop(e.x, e.y, color)
            return
        }
        val onePan = !stylus && gOn(GesturePurpose.GENERAL, "ONE_FINGER_PAN", false)
        if (stylus || (fingerDrawing && !onePan)) {
            startStroke(e, 0, stylus)
        } else {
            mode = Mode.FINGER_WAIT
            pickX = e.x; pickY = e.y
            panX = e.x; panY = e.y
            gDown.clear()
            gDown[e.getPointerId(0)] = PointF(e.x, e.y)
            val hold = purposeOf(GestureTrigger.HOLD)
            if (hold != null) postDelayed(fingerHoldRunnable, delayMs(hold, 450))
        }
    }

    private fun onPointerDown(e: MotionEvent) {
        val idx = e.actionIndex
        val newIsStylus = isStylusTool(e.getToolType(idx))
        when (mode) {
            Mode.PATH_DRAW -> { capturePts.clear(); startGesture(e, -1); invalidate() }
            Mode.DRAW -> {
                // QuickShape holds a shape: a finger on the screen makes it perfect (circle, square…)
                val s = shape
                if (s != null && !newIsStylus) {
                    val p = s.perfected()
                    if (p != s) {
                        shape = p
                        renderShape(p)
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        listener?.onMessage(when (p) { is Shape.Ellipse -> "Circle"; is Shape.Poly -> if (p.pts.size == 4) "Square" else "Even shape"; else -> "Straight line" })
                    }
                    return
                }
                if (drawIsStylus) return // palm/fingers while drawing with pen: ignore
                if (e.eventTime - downTime < 300 || strokePoints.size < 6) {
                    cancelStroke()
                    startGesture(e, -1)
                }
            }
            Mode.FINGER_WAIT, Mode.NONE, Mode.IGNORE -> {
                removeCallbacks(fingerHoldRunnable)
                if (newIsStylus) {
                    strokeOverride = penOverride()
                    startStroke(e, idx, true)
                } else startGesture(e, -1)
            }
            Mode.GESTURE -> {
                if (newIsStylus) {
                    removeCallbacks(rapidRunnable); removeCallbacks(fourHoldRunnable)
                    strokeOverride = penOverride()
                    startStroke(e, idx, true)
                } else startGesture(e, -1, keepTapInfo = true)
            }
            Mode.LIQUIFY_DRAG -> startGesture(e, -1)
            Mode.SHAPE_DRAG -> if (!shapePen && e.eventTime - downTime < 300) {
                // a second finger right away: zoom or move the view instead
                shapeCancel()
                startGesture(e, -1)
            } else {
                // a finger down while drawing the shape: it becomes even (a square, a circle, an equal star…)
                makeShapeEven()
            }
            Mode.MEASURE_DRAG -> if (e.eventTime - downTime < 300) {
                measureUndoDown()
                startGesture(e, -1)
            }
            Mode.GRADIENT_DRAG -> if (e.eventTime - downTime < 300) {
                // a second finger right away: zoom or move the view instead
                gradA = null; gradB = null
                startGesture(e, -1)
            }
            Mode.SELECT_DRAW -> {
                // second finger right away = zoom / pan instead of selecting
                if (e.eventTime - downTime < 300) {
                    selDrawing = false
                    startGesture(e, -1)
                }
            }
            Mode.TRANSFORM_DRAG -> startTransformPinch(e)
            Mode.VECTOR_DRAG -> {
                if (e.eventTime - downTime < 300) {
                    vectorEditor.onCancel()
                    startGesture(e, -1)
                }
            }
            Mode.HOVER_ADJ -> if (e.pointerCount >= 2) {
                hoverStartDist = max(1f, hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)))
                hoverStartSize = currentBrush.size
            }
            Mode.PAN -> startGesture(e, -1)
            Mode.PICK, Mode.VP_DRAG, Mode.TRANSFORM_PINCH -> {}
        }
    }

    private fun onMove(e: MotionEvent) {
        if (!Perf.on || mode != Mode.DRAW) { onMoveInner(e); return }
        val t = System.nanoTime()
        onMoveInner(e)
        Perf.moves++; Perf.moveNs += System.nanoTime() - t
        // how old the newest pen position is when it is handled
        Perf.lagNs += (android.os.SystemClock.uptimeMillis() - e.eventTime) * 1_000_000L
    }

    private fun onMoveInner(e: MotionEvent) {
        when (mode) {
            Mode.DRAW -> {
                val idx = e.findPointerIndex(drawPointerId)
                if (idx < 0) return
                for (h in 0 until e.historySize) {
                    pointTime = e.getHistoricalEventTime(h)
                    addPoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h),
                        pressureOf(e, idx, h), e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h))
                }
                pointTime = e.eventTime
                addPoint(e.getX(idx), e.getY(idx), pressureOf(e, idx, -1), e.getAxisValue(MotionEvent.AXIS_TILT, idx))
                flushLiving()
                val idx2 = idx
                // QuickShape: restart the hold timer whenever the pen really moves
                val ex = e.getX(idx2); val ey = e.getY(idx2)
                if (!pressMoved && hypot(ex - pressX, ey - pressY) > 8f * density) {
                    pressMoved = true
                    removeCallbacks(longPressRunnable)
                }
                if (shape != null) {
                    updateShape(ex, ey)
                } else if (hypot(ex - holdAnchorX, ey - holdAnchorY) > 14f * density) {
                    // the S Pen is very precise: small hand tremor while holding still must not reset the timer
                    holdAnchorX = ex; holdAnchorY = ey
                    removeCallbacks(holdRunnable)
                    if (quickShapeEnabled && !smudging && !livingStroke) postDelayed(holdRunnable, delayMs(GesturePurpose.QUICKSHAPE, 500))
                }
                invalidate()
            }
            Mode.GESTURE -> updateGesture(e)
            Mode.FINGER_WAIT -> {
                if (hypot(e.x - pickX, e.y - pickY) > 10f * density) {
                    removeCallbacks(fingerHoldRunnable)
                    mode = if (gOn(GesturePurpose.GENERAL, "ONE_FINGER_PAN", false)) Mode.PAN else Mode.IGNORE
                    panX = e.x; panY = e.y
                }
            }
            Mode.PAN -> {
                viewMatrix.postTranslate(e.x - panX, e.y - panY)
                panX = e.x; panY = e.y
                invalidate()
            }
            Mode.HOVER_ADJ -> {
                val b = currentBrush
                if (e.pointerCount >= 2 && hoverStartDist > 0f) {
                    val d = max(1f, hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)))
                    b.size = (hoverStartSize * d / hoverStartDist).coerceIn(Brushes.MIN_SIZE, Brushes.MAX_SIZE)
                } else if (e.pointerCount == 1) {
                    b.opacity = (hoverStartOpacity - (e.y - hoverStartY) / (260f * density)).coerceIn(0.01f, 1f)
                }
                listener?.onBrushAdjusted()
                invalidate()
            }
            Mode.PICK -> pickAt(e.x, e.y)
            Mode.VP_DRAG -> {
                val p = toDoc(e.x, e.y)
                doc.guide.setVp(dragVp, p.x / doc.width, p.y / doc.height)
                invalidate()
            }
            Mode.SELECT_DRAW -> moveSelect(e)
            Mode.LIQUIFY_DRAG -> moveLiquify(e)
            Mode.GRADIENT_DRAG -> { gradB = toDoc(e.x, e.y); invalidate() }
            Mode.MEASURE_DRAG -> measureMove(e.x, e.y)
            Mode.PATH_DRAW -> {
                for (h in 0 until e.historySize) capturePts.add(toDoc(e.getHistoricalX(h), e.getHistoricalY(h)))
                capturePts.add(toDoc(e.x, e.y)); invalidate()
            }
            Mode.SHAPE_DRAG -> {
                val i = e.findPointerIndex(shapePointerId)
                if (shapePen) shapeButton(e)
                if (i >= 0) shapeMove(toDoc(e.getX(i), e.getY(i)))
            }
            Mode.VECTOR_DRAG -> vectorEditor.onMove(e.x, e.y)
            Mode.TRANSFORM_DRAG -> updateTransformDrag(e)
            Mode.TRANSFORM_PINCH -> updateTransformPinch(e)
            else -> {}
        }
    }

    private fun onPointerUp(e: MotionEvent) {
        when (mode) {
            Mode.DRAW -> {
                if (e.getPointerId(e.actionIndex) == drawPointerId) {
                    finishStroke()
                    mode = Mode.IGNORE
                }
            }
            Mode.GESTURE -> {
                trackMove(e)
                startGesture(e, e.actionIndex, keepTapInfo = true)
            }
            Mode.TRANSFORM_PINCH -> mode = Mode.IGNORE
            Mode.SHAPE_DRAG -> {
                if (e.getPointerId(e.actionIndex) == shapePointerId) {
                    // the pen lifted first: the shape is done, as it is now
                    shapeUp()
                    mode = Mode.IGNORE
                } else if (e.pointerCount <= 2) {
                    shapeFingerEven = false
                    if (shapeA != null) renderShapeTool()
                }
            }
            else -> {}
        }
    }

    private fun onLastUp(e: MotionEvent) {
        removeCallbacks(fingerHoldRunnable)
        when (mode) {
            Mode.DRAW -> finishStroke()
            Mode.TRANSFORM_DRAG -> endTransformDrag()
            Mode.GESTURE -> {
                trackMove(e)
                removeCallbacks(rapidRunnable); removeCallbacks(fourHoldRunnable)
                val quick = e.eventTime - gStartTime < 320 && gMoved < 24f * density
                val noUndo = gOn(GesturePurpose.GENERAL, "NO_UNDO", false)
                if (quick && !gFired && gMaxPointers >= 2) {
                    viewMatrix.set(tapStartMatrix) // a tap must not nudge the canvas
                    when (gMaxPointers) {
                        2 -> if (!noUndo) undo()
                        3 -> if (!noUndo) redo()
                        4 -> purposeOf(GestureTrigger.FOUR_TAP)?.let { runPurpose(it, e.x, e.y) }
                    }
                } else if (!gFired && gMaxPointers == 2 && e.eventTime - gStartTime < 280 && gPinchTotal < 0.72f && gPinchTotal > 0.2f) {
                    // a short, quick pinch in (a flick of two fingers) fits the canvas; slower zooming stays as it is
                    fitToScreen()
                }
            }
            Mode.PICK -> {
                listener?.onColorPicked(pickColor)
            }
            Mode.VP_DRAG -> doc.revision++
            Mode.SELECT_DRAW -> finishSelect()
            Mode.GRADIENT_DRAG -> commitGradient()
            Mode.MEASURE_DRAG -> measureUp()
            Mode.PATH_DRAW -> {
                val f = pathCapture
                // a tap is not a path: keep waiting for one
                if (f != null && capturePts.size >= 4) { pathCapture = null; f(ArrayList(capturePts)) }
                capturePts.clear()
            }
            Mode.SHAPE_DRAG -> shapeUp()
            Mode.VECTOR_DRAG -> vectorEditor.onUp(e.x, e.y)
            else -> {}
        }
        mode = Mode.NONE
        invalidate()
    }

    private fun pressureOf(e: MotionEvent, idx: Int, h: Int): Float {
        if (!drawIsStylus) return 1f
        val p = if (h < 0) e.getPressure(idx) else e.getHistoricalPressure(idx, h)
        return PressureCurve.map(p.coerceIn(0f, 1f))
    }

    // app-wide stroke smoothing (Actions › Prefs › Pressure and Smoothing)
    private var stabX = 0f
    private var stabY = 0f
    private var mfX = 0f
    private var mfY = 0f

    private fun resetSmoothing(sx: Float, sy: Float) { stabX = sx; stabY = sy; mfX = sx; mfY = sy }

    /** the pen position after Stabilization and Motion Filtering (screen pixels) */
    private fun smoothed(sx: Float, sy: Float): Pair<Float, Float> {
        val st = PressureCurve.stabilization
        val mf = PressureCurve.motionFiltering
        if (st <= 0f && mf <= 0f) { resetSmoothing(sx, sy); return sx to sy }
        val k = 1f - st.coerceIn(0f, 1f) * 0.9f
        stabX += (sx - stabX) * k; stabY += (sy - stabY) * k
        val km = 1f - mf.coerceIn(0f, 1f) * 0.8f
        mfX += (stabX - mfX) * km; mfY += (stabY - mfY) * km
        val ex = PressureCurve.motionExpression.coerceIn(0f, 1f)
        return (mfX + (stabX - mfX) * ex) to (mfY + (stabY - mfY) * ex)
    }

    // ---------------- strokes ----------------

    private fun startStroke(e: MotionEvent, idx: Int, stylus: Boolean) {
        val layer = doc.active
        mode = Mode.DRAW
        drawPointerId = e.getPointerId(idx)
        drawIsStylus = stylus
        if (!layer.visible) {
            listener?.onMessage("This layer is hidden")
            mode = Mode.IGNORE
            return
        }
        if (doc.groupOf(layer)?.visible == false) {
            listener?.onMessage("This layer's group is hidden")
            mode = Mode.IGNORE
            return
        }
        if (layer.vector != null) {
            listener?.onMessage("Vector layer: use the vector tools, or pick a drawing layer")
            mode = Mode.IGNORE
            return
        }
        if (layer.scene != null) {
            listener?.onMessage("3D layer: change it with Edit 3D scene in Layers, or draw on a new layer")
            mode = Mode.IGNORE
            return
        }
        val sb = strokeBitmap ?: return
        val ov = strokeOverride
        strokeErasing = (tool == Tool.ERASER && ov != GesturePurpose.SMUDGE) ||
            ov == GesturePurpose.ERASE ||
            e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER ||
            (stylus && isPenButton(e.buttonState))
        sb.eraseColor(0); strokeErased()
        smudging = !strokeErasing && (tool == Tool.SMUDGE || ov == GesturePurpose.SMUDGE)
        engine.brush = when {
            strokeErasing -> eraserBrush
            smudging -> smudgeBrush
            else -> paintBrush
        }
        // on a layer mask any paint means "show": it is painted white
        engine.color = if (strokeErasing) Color.BLACK else if (layer.isLayerMask) Color.WHITE else color
        strokeGrain = if (smudging) null else grainPaintFor(engine.brush)
        strokeLook = if (smudging || strokeErasing) null else strokeLookFor(engine.brush)
        if (smudging) {
            // smudge works directly on the layer; keep a copy for undo
            val copy = try { layer.bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (e: OutOfMemoryError) { null }
            if (copy == null) {
                listener?.onMessage("Not enough memory for this right now")
                smudging = false
                mode = Mode.IGNORE
                return
            }
            smudgeBefore = copy
            val sm = Smudger(layer.bitmap, smudgeBrush)
            smudger = sm
            engine.dabHandler = { x, y, d, a, ang -> sm.dab(x, y, d, a, ang) }
        } else engine.dabHandler = null
        // wet mix picks up the layer's colors; the dual brush stamps its second shape into a mask
        engine.wetSource = if (!smudging && !strokeErasing && engine.brush.dyn.hasWetMix) layer.bitmap else null
        strokeMask = if (!smudging && engine.brush.dualTip() != null) {
            val mb = maskBitmap?.takeIf { it.width == doc.width && it.height == doc.height }
                ?: run { maskBitmap?.recycle(); runCatching { Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ALPHA_8) }.getOrNull() }
            maskBitmap = mb
            mb?.eraseColor(0)
            mb
        } else null
        // a living brush paints with the MyPaint engine, straight onto the layer
        pointTime = e.eventTime
        livingStroke = !smudging && engine.brush.preset.living != null &&
            living.begin(engine.brush, engine.color, strokeErasing, layer.alphaLock, doc.width, doc.height,
                symmetry, doc.width / 2f, doc.height / 2f, e.eventTime)
        if (livingStroke) {
            living.viewZoom = zoom()
            strokeGrain = null
            strokeLook = null
            strokeMask = null
            engine.wetSource = null
        }
        engine.maskTarget = strokeMask
        engine.symmetry = symmetry
        engine.centerX = doc.width / 2f
        engine.centerY = doc.height / 2f
        strokePoints.clear()
        shape = null
        val p = toDoc(e.getX(idx), e.getY(idx))
        val ip = InputPoint(p.x, p.y, pressureOf(e, idx, -1), e.getAxisValue(MotionEvent.AXIS_TILT, idx))
        resetSmoothing(e.getX(idx), e.getY(idx))
        strokePoints.add(ip)
        shapeSeed = (System.nanoTime() and 0xFFFFFF).toInt()
        strokeActive = true
        listener?.onDrawingChanged(true)
        holdAnchorX = e.getX(idx); holdAnchorY = e.getY(idx)
        removeCallbacks(holdRunnable)
        constraint = null
        if (assistedActive() || (ov == GesturePurpose.ASSISTED && doc.guide.type != GuideType.OFF)) {
            // wait until the pen moves a little, then lock to the closest guide direction
            snapPending = true
            pendingPts.clear()
            pendingPts.add(ip)
        } else {
            snapPending = false
            eBegin(sb, ip, smooth = true)
            flushLiving()
        }
        // QuickShape works with or without the grid's Assisted Drawing
        if (quickShapeEnabled && !smudging && !livingStroke) postDelayed(holdRunnable, delayMs(GesturePurpose.QUICKSHAPE, 500))
        pressX = e.getX(idx); pressY = e.getY(idx)
        pressMoved = false
        removeCallbacks(longPressRunnable)
        val still = purposeOf(if (stylus) GestureTrigger.PEN_STILL else GestureTrigger.HOLD)
        if (still != null) postDelayed(longPressRunnable, delayMs(still, 420))
        invalidate()
    }

    private fun endSmudge(keep: Boolean) {
        val before = smudgeBefore
        val layer = doc.active
        engine.dabHandler = null
        smudger?.recycle()
        smudger = null
        smudging = false
        smudgeBefore = null
        if (before == null) return
        if (!keep) {
            Canvas(layer.bitmap).drawBitmap(before, 0f, 0f, Paint().apply { blendMode = BlendMode.SRC })
            before.recycle()
            return
        }
        val r = Rect()
        engine.dirty.roundOut(r)
        if (r.intersect(0, 0, doc.width, doc.height) && !r.isEmpty) {
            val beforeRegion = Bitmap.createBitmap(before, r.left, r.top, r.width(), r.height())
            selection?.let { Selections.restrict(layer.bitmap, beforeRegion, r, it) }
            pushPixelEdit(layer, r, beforeRegion)
        }
        before.recycle()
    }

    private fun addPoint(rawX: Float, rawY: Float, pressure: Float, tilt: Float) {
        if (!strokeActive || shape != null) return
        val (sx, sy) = smoothed(rawX, rawY)
        val p = toDoc(sx, sy)
        val ip = InputPoint(p.x, p.y, pressure, tilt)
        strokePoints.add(ip)
        if (snapPending) {
            pendingPts.add(ip)
            val first = pendingPts[0]
            val threshold = 12f * density / max(0.01f, zoom())
            if (hypot(ip.x - first.x, ip.y - first.y) >= threshold) {
                constraint = Guides.constraintFor(doc.guide, doc.width, doc.height,
                    first.x, first.y, ip.x - first.x, ip.y - first.y)
                snapPending = false
                val sb = strokeBitmap ?: return
                eBegin(sb, snapped(first), smooth = constraint !is Constraint.Circle)
                for (i in 1 until pendingPts.size) eMove(snapped(pendingPts[i]))
            }
            return
        }
        eMove(snapped(ip))
    }

    // the stroke goes to the living-brush engine or to Inkora's own
    private fun eBegin(sb: Bitmap, p: InputPoint, smooth: Boolean) {
        if (livingStroke) living.add(p.x, p.y, p.pressure, pointTime)
        else engine.begin(sb, p, smooth = smooth, seed = shapeSeed)
    }

    private fun eMove(p: InputPoint) {
        if (livingStroke) living.add(p.x, p.y, p.pressure, pointTime) else engine.move(p)
    }

    /** paints the pen positions gathered so far (living brushes, once per pen event) */
    private fun flushLiving() {
        if (!livingStroke) return
        if (living.flush(doc.active.bitmap) != null) invalidate()
    }

    /** the living stroke is over: kept (with undo) or taken back */
    private fun endLiving(keep: Boolean) {
        val layer = doc.active
        if (keep) flushLiving()
        val r = Rect(living.dirty)
        livingStroke = false
        if (r.intersect(0, 0, doc.width, doc.height) && !r.isEmpty) {
            val before = living.original(layer.bitmap, r)
            if (before != null) {
                if (!keep) {
                    Canvas(layer.bitmap).drawBitmap(before, r.left.toFloat(), r.top.toFloat(), Paint().apply { blendMode = BlendMode.SRC })
                    before.recycle()
                } else {
                    selection?.let { Selections.restrict(layer.bitmap, before, r, it) }
                    pushPixelEdit(layer, r, before)
                }
            }
        }
        living.end()
        lastStroke = null
    }

    private fun flushPending() {
        if (!snapPending) return
        snapPending = false
        val sb = strokeBitmap ?: return
        if (pendingPts.isEmpty()) return
        eBegin(sb, pendingPts[0], smooth = true)
        for (i in 1 until pendingPts.size) eMove(pendingPts[i])
    }

    private fun finishStroke() {
        removeCallbacks(holdRunnable)
        removeCallbacks(longPressRunnable)
        if (!strokeActive) return
        flushPending()
        if (livingStroke) endLiving(true)
        else {
            if (shape == null) engine.end()
            if (smudging) endSmudge(true) else commitStroke()
        }
        if (shape != null) listener?.onMessage(shape!!.label)
        shape = null
        strokeActive = false
        listener?.onDrawingChanged(false)
        invalidate()
    }

    private fun cancelStroke() {
        removeCallbacks(holdRunnable)
        removeCallbacks(longPressRunnable)
        snapPending = false
        constraint = null
        strokeActive = false
        listener?.onDrawingChanged(false)
        shape = null
        if (livingStroke) endLiving(false) else engine.end()
        if (smudging) endSmudge(false)
        strokeBitmap?.eraseColor(0); strokeErased()
        mode = Mode.NONE
        invalidate()
    }

    /** where each layer's watercolor is still wet, by layer id */
    private val wetMaps = HashMap<Int, WetMap>()

    /**
     * A watercolor stroke dries: dark rims, uneven wash, grain, and bleeding into paint that is
     * still wet. Works on the stroke's pixels (and a margin for the bleed); [r] grows to match.
     */
    private fun dryWatercolor(sb: Bitmap, r: Rect, layer: Layer) {
        val dyn = engine.brush.dyn
        val size = engine.brush.size
        val margin = (size * 0.7f).toInt().coerceIn(8, 90)
        r.inset(-margin, -margin)
        if (!r.intersect(0, 0, doc.width, doc.height) || r.isEmpty) return
        val w = r.width(); val h = r.height()
        // a big stroke is dried as a smaller copy and scaled back up: lifting the pen stays quick
        val k = max(1f, kotlin.math.sqrt(w.toFloat() * h / 600_000f))
        val lw = (w / k).toInt().coerceAtLeast(1); val lh = (h / k).toInt().coerceAtLeast(1)
        var region: Bitmap? = null; var small: Bitmap? = null; var dried: Bitmap? = null
        try {
            region = Bitmap.createBitmap(sb, r.left, r.top, w, h)
            small = if (lw == w && lh == h) region else Bitmap.createScaledBitmap(region, lw, lh, true)
            val px = IntArray(lw * lh)
            small.getPixels(px, 0, lw, 0, 0, lw, lh)
            // wet maps of layers that are gone are let go
            if (wetMaps.size > 6) wetMaps.keys.retainAll(doc.layers.map { it.id }.toSet())
            val wm = wetMaps.getOrPut(layer.id) { WetMap(doc.width, doc.height) }
            val now = android.os.SystemClock.uptimeMillis()
            val sx = w / lw.toFloat(); val sy = h / lh.toFloat()
            val ox = (r.left / sx).toInt(); val oy = (r.top / sy).toInt()
            val alpha = Watercolor.dry(px, lw, lh, ox, oy, dyn.watercolor, dyn.bloom, size / k, engine.color) { x, y ->
                wm.wetness((x * sx).toInt(), (y * sy).toInt(), now)
            }
            dried = Bitmap.createBitmap(px, lw, lh, Bitmap.Config.ARGB_8888)
            val c = Canvas(sb)
            c.save()
            c.clipRect(r)
            c.drawBitmap(dried, null, r, dryPaint)
            // pigment settles into the paper's grain, most where the wash is watery
            c.drawRect(RectF(r), grainPaint(dyn.watercolor))
            c.restore()
            wm.paintScaled(r.left, r.top, w, h, lw, lh, alpha, now)
        } catch (e: OutOfMemoryError) {
            // not enough memory to dry it: the stroke stays as it was painted
        } finally {
            if (small !== region) small?.recycle()
            if (region !== sb) region?.recycle()
            dried?.recycle()
        }
    }

    private val dryPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.SRC }
    private var grainShader: BitmapShader? = null
    private var grainStrength = -1f
    private val grainDstIn = Paint().apply { blendMode = BlendMode.DST_IN }

    /** the paper grain as a paint that thins the stroke a little where the paper dips ([strength] 0..1) */
    private fun grainPaint(strength: Float): Paint {
        val st = (strength.coerceIn(0f, 1f) * 20).toInt() / 20f
        if (grainShader == null || st != grainStrength) {
            val t = Watercolor.GRAIN_TILE
            val g = Watercolor.grainTile()
            val px = IntArray(t * t) { ((255 * (1f - st * 0.30f * g[it])).toInt().coerceIn(0, 255) shl 24) }
            val tile = Bitmap.createBitmap(px, t, t, Bitmap.Config.ARGB_8888)
            grainShader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            grainStrength = st
        }
        grainDstIn.shader = grainShader
        return grainDstIn
    }

    /** wet paint runs into wet paint (Preferences can turn it off) */
    var wetBleed = true
    private val bleedOut = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_OUT }
    private val bleedIn = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_IN }
    private val bleedPlus = Paint().apply { blendMode = BlendMode.PLUS }
    private val bleedSoft = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Notes where this stroke left wet paint and, when it touched paint that was still wet, returns
     * how much each spot should run (a small alpha map over [r], which grows by a margin). Null: nothing to do.
     */
    private fun wetBleedMask(sb: Bitmap, r: Rect, layer: Layer): Bitmap? {
        val dyn = engine.brush.dyn
        var small: Bitmap? = null
        try {
            if (wetMaps.size > 6) wetMaps.keys.retainAll(doc.layers.map { it.id }.toSet())
            val wm = wetMaps.getOrPut(layer.id) { WetMap(doc.width, doc.height) }
            val now = android.os.SystemClock.uptimeMillis()
            val grown = Rect(r)
            val margin = (engine.brush.size * 0.5f).toInt().coerceIn(6, 70)
            grown.inset(-margin, -margin)
            if (!grown.intersect(0, 0, doc.width, doc.height)) return null
            val w = grown.width(); val h = grown.height()
            val k = max(2f, kotlin.math.sqrt(w.toFloat() * h / 160_000f))
            val lw = (w / k).toInt().coerceAtLeast(2); val lh = (h / k).toInt().coerceAtLeast(2)
            small = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
            Canvas(small).drawBitmap(sb, grown, Rect(0, 0, lw, lh), bleedSoft)
            val px = IntArray(lw * lh)
            small.getPixels(px, 0, lw, 0, 0, lw, lh)
            val a = FloatArray(lw * lh) { (px[it] ushr 24) / 255f }
            val sx = w / lw.toFloat(); val sy = h / lh.toFloat()
            var m: FloatArray? = null
            val bloom = if (wetBleed) max(dyn.bloom, 0f) else 0f
            if (bloom > 0f && !layer.alphaLock) {
                // the stroke's reach: its own shape, spread out a little
                val rad = (margin / k).toInt().coerceIn(1, 24)
                val spread = boxBlur(boxBlur(a, lw, lh, rad), lw, lh, rad)
                var any = false
                val mm = FloatArray(lw * lh)
                val ns = 9f / max(lw, lh)
                for (y in 0 until lh) for (x in 0 until lw) {
                    val i = y * lw + x
                    val reach = (spread[i] * 2.2f).coerceAtMost(1f)
                    if (reach < 0.02f) continue
                    val wet = wm.wetness(grown.left + (x * sx).toInt(), grown.top + (y * sy).toInt(), now)
                    if (wet <= 0f) continue
                    // it creeps unevenly, as water does
                    val n = 0.45f + 0.75f * Watercolor.pnoise(x * ns * 6f, y * ns * 6f, shapeSeed, 4096)
                    val v = (reach * wet * bloom * n * 0.85f).coerceIn(0f, 0.6f)
                    if (v > 0.03f) any = true
                    mm[i] = v
                }
                if (any) m = boxBlur(mm, lw, lh, 1)
            }
            wm.paintScaled(grown.left, grown.top, w, h, lw, lh, a, now)
            if (m == null) return null
            r.set(grown)
            for (i in px.indices) px[i] = (m[i] * 255f).toInt().coerceIn(0, 255) shl 24
            small.setPixels(px, 0, lw, 0, 0, lw, lh)
            val out = small
            small = null
            return out
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            small?.recycle()
        }
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, rad: Int): FloatArray {
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        val n = 2 * rad + 1
        for (y in 0 until h) {
            var acc = 0f
            for (x in -rad..rad) acc += src[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = acc / n
                acc += src[y * w + (x + rad + 1).coerceAtMost(w - 1)] - src[y * w + (x - rad).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (y in -rad..rad) acc += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc / n
                acc += tmp[(y + rad + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - rad).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    /** the layer inside [r] turns, by [mask], into a soft, run-together copy of itself */
    private fun bleedInto(layer: Layer, r: Rect, mask: Bitmap, sel: Selection?) {
        var soft: Bitmap? = null
        try {
            val w = r.width(); val h = r.height()
            // softened by going small and back: the smaller, the further the paint runs
            val f = (engine.brush.size * 0.16f).coerceIn(3f, 14f)
            val sw = (w / f).toInt().coerceAtLeast(2); val sh = (h / f).toInt().coerceAtLeast(2)
            soft = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            Canvas(soft).drawBitmap(layer.bitmap, r, Rect(0, 0, sw, sh), bleedSoft)
            val lc = Canvas(layer.bitmap)
            val rf = RectF(r)
            lc.save()
            lc.clipRect(rf)
            if (sel != null) {
                // only inside the selection
                val s = lc.saveLayer(rf, bleedOut)
                lc.drawBitmap(mask, null, rf, bleedSoft)
                lc.drawBitmap(sel.mask, r, r, Selections.dstIn)
                lc.restoreToCount(s)
            } else lc.drawBitmap(mask, null, rf, bleedOut)
            val s = lc.saveLayer(rf, bleedPlus)
            lc.drawBitmap(soft, null, rf, bleedSoft)
            lc.drawBitmap(mask, null, rf, bleedIn)
            if (sel != null) lc.drawBitmap(sel.mask, r, r, Selections.dstIn)
            lc.restoreToCount(s)
            lc.restore()
        } catch (e: OutOfMemoryError) {
            // no memory to let it run: the stroke stays as painted
        } finally {
            soft?.recycle()
        }
    }

    private fun commitStroke() {
        val sb = strokeBitmap ?: return
        val r = Rect()
        engine.dirty.roundOut(r)
        if (!r.intersect(0, 0, doc.width, doc.height) || r.isEmpty) return
        val layer = doc.active
        if (watercolorDrying && engine.brush.dyn.watercolor > 0f && !strokeErasing && !layer.isLayerMask) dryWatercolor(sb, r, layer)
        // paint put down on paint that is still wet runs into it
        val bleed = if (!watercolorDrying && !strokeErasing && !smudging && !layer.isLayerMask &&
            (engine.brush.dyn.bloom > 0f || engine.brush.dyn.watercolor > 0f)) wetBleedMask(sb, r, layer) else null
        val before = try { Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height()) }
            catch (e: OutOfMemoryError) { bleed?.recycle(); sb.eraseColor(0); strokeErased(); listener?.onMessage("Not enough memory for this right now"); return }
        commitPaint.alpha = (engine.brush.opacity * 255).toInt().coerceIn(0, 255)
        commitPaint.blendMode = strokeBlend()
        val lc = Canvas(layer.bitmap)
        val grain = strokeGrain
        val look = strokeLook
        val sel = selection
        val mask = strokeMask
        if (grain == null && sel == null && mask == null && look == null) lc.drawBitmap(sb, r, r, commitPaint)
        else {
            val rf = RectF(r)
            val s = lc.saveLayer(rf, commitPaint)
            val gi = if (look?.layer != null) lc.saveLayer(rf, look.layer) else -1
            lc.drawBitmap(sb, r, r, null)
            if (mask != null) lc.drawBitmap(mask, r, r, maskDstIn)
            if (grain != null) lc.drawRect(rf, grain)
            if (gi >= 0) lc.restoreToCount(gi)
            look?.tone?.let { lc.drawBitmap(sb, r, r, it) }
            look?.core?.let { lc.drawBitmap(sb, r, r, it) }
            if (sel != null) lc.drawBitmap(sel.mask, r, r, Selections.dstIn)
            lc.restoreToCount(s)
        }
        if (bleed != null) { bleedInto(layer, r, bleed, sel); bleed.recycle() }
        val after = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        val cmd = PixelEdit(layer, Rect(r), before, after)
        history.push(cmd, doc)
        sb.eraseColor(0); strokeErased()
        lastStroke = if (shape == null && strokePoints.size >= 4)
            LastStroke(cmd, layer, ArrayList(strokePoints), engine.brush, engine.color, strokeErasing, strokeGrain, shapeSeed) else null
        listener?.onHistoryChanged()
    }

    private class LastStroke(val cmd: Command, val layer: Layer, val points: List<InputPoint>, val brush: Brush, val color: Int,
                             val erasing: Boolean, val grain: Paint?, val seed: Int)
    private var lastStroke: LastStroke? = null

    /** QuickShape after the fact: the last stroke redrawn as the line, circle or box it looks like. */
    fun snapLastStroke() {
        val ls = lastStroke
        if (ls == null || history.top() !== ls.cmd || strokeActive || ls.layer !== doc.active) {
            listener?.onMessage("Draw a stroke first, then turn it into a shape")
            return
        }
        val detected = QuickShape.detect(ls.points)
        if (detected == null) {
            listener?.onMessage("No shape recognised — try again a bit more carefully")
            return
        }
        val sb = strokeBitmap ?: return
        history.undo(doc)
        sb.eraseColor(0); strokeErased()
        engine.brush = ls.brush
        engine.color = ls.color
        engine.dabHandler = null
        engine.symmetry = symmetry
        engine.wetSource = null
        strokeMask?.eraseColor(0)
        engine.maskTarget = strokeMask
        val wasErasing = strokeErasing
        strokeErasing = ls.erasing
        strokeGrain = ls.grain
        strokeLook = if (ls.erasing) null else strokeLookFor(ls.brush)
        val pressure = ls.points.map { it.pressure }.average().toFloat().coerceIn(0.2f, 1f)
        engine.renderPoints(sb, QuickShape.toPoints(detected), pressure, ls.seed)
        commitStroke()
        strokeErasing = wasErasing
        lastStroke = null
        listener?.onMessage(detected.label)
        invalidate()
    }

    private fun currentBrushFor(erasing: Boolean) = if (erasing) eraserBrush else paintBrush

    // ---------------- QuickShape ----------------

    private fun onHold() {
        if (!strokeActive || shape != null || !quickShapeEnabled || smudging || livingStroke) return
        if (!pressMoved) return // pen still resting where it landed: that's a long press, not a shape
        flushPending()
        // drop the cloud of tremor points collected while the pen was held still
        val r = 16f * density / max(0.01f, zoom())
        val end = strokePoints.last()
        var k = strokePoints.size - 1
        while (k > 1 && hypot(strokePoints[k - 1].x - end.x, strokePoints[k - 1].y - end.y) < r) k--
        val pts = ArrayList(strokePoints.subList(0, k + 1))
        // same at the start (the pen often rests a moment before moving)
        val start = pts.first()
        var j = 0
        while (j < pts.size - 2 && hypot(pts[j + 1].x - start.x, pts[j + 1].y - start.y) < r * 0.5f) j++
        val clean = if (j > 0) ArrayList(pts.subList(j, pts.size)).also { it.add(0, start) } else pts
        val detected = QuickShape.detect(clean)
        if (detected == null) {
            listener?.onMessage("No shape recognised — try again a bit more carefully")
            return
        }
        shape = detected
        shapeAnchor = toDoc(holdAnchorX, holdAnchorY)
        shapePressure = strokePoints.map { it.pressure }.average().toFloat().coerceIn(0.2f, 1f)
        renderShape(detected)
        listener?.onMessage(detected.label)
    }

    private fun updateShape(sx: Float, sy: Float) {
        val s = shape ?: return
        val p = toDoc(sx, sy)
        val updated: Shape = when (s) {
            is Shape.Line -> Shape.Line(s.a, p)
            else -> {
                val c = s.center()
                val d0 = hypot(shapeAnchor.x - c.x, shapeAnchor.y - c.y)
                val d1 = hypot(p.x - c.x, p.y - c.y)
                if (d0 < 1f) return
                val a0 = atan2(shapeAnchor.y - c.y, shapeAnchor.x - c.x)
                val a1 = atan2(p.y - c.y, p.x - c.x)
                var rot = a1 - a0
                if (abs(rot) < 0.06f) rot = 0f
                shapeAnchor = p
                s.transformed(d1 / d0, rot)
            }
        }
        shape = updated
        renderShape(updated)
    }

    private fun renderShape(s: Shape) {
        val sb = strokeBitmap ?: return
        sb.eraseColor(0); strokeErased()
        strokeMask?.eraseColor(0)
        engine.renderPoints(sb, QuickShape.toPoints(s), shapePressure, shapeSeed)
        invalidate()
    }

    // ---------------- gestures ----------------

    private fun startGesture(e: MotionEvent, excludeIdx: Int, keepTapInfo: Boolean = false) {
        val pts = ArrayList<PointF>()
        for (i in 0 until e.pointerCount) if (i != excludeIdx) pts.add(PointF(e.getX(i), e.getY(i)))
        if (!keepTapInfo) {
            gPinchTotal = 1f
            gPinchBase = 1f
        } else gPinchBase = gPinchTotal
        if (!keepTapInfo) {
            gMaxPointers = 0
            gStartTime = e.eventTime
            gMoved = 0f
            gDown.clear()
            tapStartMatrix.set(viewMatrix)
        }
        for (i in 0 until e.pointerCount) {
            if (i == excludeIdx) continue
            val id = e.getPointerId(i)
            if (!gDown.containsKey(id)) gDown[id] = PointF(e.getX(i), e.getY(i))
        }
        gMaxPointers = max(gMaxPointers, pts.size)
        mode = Mode.GESTURE
        gestureStart.set(viewMatrix)
        gRotating = false
        gLastScale = 1f
        gPointers = pts.size
        if (!keepTapInfo) { scrubDir = 0; scrubTurns = 0; scrubRefX = 0f }
        removeCallbacks(rapidRunnable); removeCallbacks(fourHoldRunnable)
        if (!gFired && (pts.size == 2 || pts.size == 3) && gMoved < 24f * density &&
            gOn(GesturePurpose.GENERAL, "RAPID_UNDO", true) && !gOn(GesturePurpose.GENERAL, "NO_UNDO", false)) {
            postDelayed(rapidRunnable, delayMs(GesturePurpose.GENERAL, 650))
        }
        if (!gFired && pts.size == 4 && gMoved < 24f * density) postDelayed(fourHoldRunnable, 600)
        if (pts.size >= 2) {
            gStartMidX = (pts[0].x + pts[1].x) / 2
            gStartMidY = (pts[0].y + pts[1].y) / 2
            gStartDist = max(1f, hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y))
            gStartAngle = Math.toDegrees(atan2(pts[1].y - pts[0].y, pts[1].x - pts[0].x).toDouble()).toFloat()
        } else if (pts.size == 1) {
            gStartMidX = pts[0].x; gStartMidY = pts[0].y
            gStartDist = 0f
        }
    }

    private fun trackMove(e: MotionEvent) {
        for (i in 0 until e.pointerCount) {
            val d = gDown[e.getPointerId(i)] ?: continue
            gMoved = max(gMoved, hypot(e.getX(i) - d.x, e.getY(i) - d.y))
        }
    }

    private fun updateGesture(e: MotionEvent) {
        trackMove(e)
        if (gMoved > 24f * density) removeCallbacks(rapidRunnable); removeCallbacks(fourHoldRunnable)
        if (gFired) return
        if (e.pointerCount == 3 && gMaxPointers == 3) {
            var dx = 0f; var dy = 0f; var n = 0
            var mx = 0f
            for (i in 0 until 3) {
                val d = gDown[e.getPointerId(i)] ?: continue
                dx += e.getX(i) - d.x; dy += e.getY(i) - d.y; mx += e.getX(i); n++
            }
            if (n == 3) {
                dx /= 3; dy /= 3; mx /= 3
                // swiped down, mostly straight
                if (dy > 70f * density && abs(dx) < dy * 0.6f) {
                    purposeOf(GestureTrigger.THREE_SWIPE)?.let { p ->
                        gFired = true
                        viewMatrix.set(tapStartMatrix)
                        runPurpose(p, mx, e.getY(0))
                        invalidate()
                        return
                    }
                }
                // scrubbed: back and forth sideways
                if (scrubRefX == 0f) scrubRefX = mx
                val step = 24f * density
                val dir = if (mx > scrubRefX + step) 1 else if (mx < scrubRefX - step) -1 else 0
                if (dir != 0) {
                    if (scrubDir != 0 && dir != scrubDir) scrubTurns++
                    scrubDir = dir
                    scrubRefX = mx
                    if (scrubTurns >= 3) {
                        purposeOf(GestureTrigger.SCRUB)?.let { p ->
                            gFired = true
                            viewMatrix.set(tapStartMatrix)
                            runPurpose(p, mx, e.getY(0))
                            invalidate()
                            return
                        }
                    }
                }
            }
        }
        if (e.pointerCount < 2) return // one finger left: wait
        val x0 = e.getX(0); val y0 = e.getY(0)
        val x1 = e.getX(1); val y1 = e.getY(1)
        val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
        val dist = max(1f, hypot(x1 - x0, y1 - y0))
        val angle = Math.toDegrees(atan2(y1 - y0, x1 - x0).toDouble()).toFloat()
        if (gStartDist <= 0f) {
            startGesture(e, -1, keepTapInfo = true)
            return
        }
        var sc = dist / gStartDist
        var rot = angle - gStartAngle
        while (rot > 180f) rot -= 360f
        while (rot < -180f) rot += 360f
        if (!gRotating && abs(rot) > 9f) gRotating = true
        if (!gRotating || !gOn(GesturePurpose.GENERAL, "ROTATE", true)) rot = 0f
        gLastScale = sc
        gPinchTotal = gPinchBase * sc
        gPinchX = mx; gPinchY = my

        // clamp total zoom between 3% and 6400%
        viewMatrix.set(gestureStart)
        val startZoom = zoom()
        val target = (startZoom * sc).coerceIn(0.03f, 64f)
        sc = target / startZoom

        viewMatrix.set(gestureStart)
        viewMatrix.postTranslate(-gStartMidX, -gStartMidY)
        viewMatrix.postScale(sc, sc)
        viewMatrix.postRotate(rot)
        viewMatrix.postTranslate(mx, my)
        invalidate()
    }

    // ---------------- eyedropper ----------------

    private fun pickAt(sx: Float, sy: Float) {
        pickX = sx; pickY = sy
        val p = toDoc(sx, sy)
        val x = p.x.toInt().coerceIn(0, doc.width - 1)
        val y = p.y.toInt().coerceIn(0, doc.height - 1)
        pickColor = doc.sampleColor(x, y) or 0xFF000000.toInt()
        invalidate()
    }

    fun armEyedropper() {
        eyedropperArmed = true
        listener?.onMessage("Touch the canvas to pick a color")
    }

    // ---------------- ColorDrop fill ----------------

    /** [sx],[sy] are in this view's coordinates. */
    fun colorDrop(sx: Float, sy: Float, fillColor: Int): Boolean {
        val p = toDoc(sx, sy)
        val x = p.x.toInt()
        val y = p.y.toInt()
        if (x !in 0 until doc.width || y !in 0 until doc.height) return false
        val layer = doc.active
        if (!layer.visible) {
            listener?.onMessage("This layer is hidden")
            return false
        }
        if (layer.vector != null) return vectorEditor.dropColor(sx, sy, fillColor)
        if (layer.scene != null) { listener?.onMessage("3D layer: change it with Edit 3D scene in Layers, or draw on a new layer"); return false }
        if (!colorDropAt(x, y, fillColor)) return false
        listener?.onMessage("ColorDrop")
        return true
    }

    /** ColorDrop at a document pixel of the active (drawing) layer, as one undo step */
    private fun colorDropAt(x: Int, y: Int, fillColor: Int): Boolean {
        val layer = doc.active
        val result = try {
            val ref = doc.fillReference()
            try { FloodFill.compute(ref, x, y, if (layer.isLayerMask) Color.WHITE else fillColor, fillThreshold, fillGap) } finally { ref.recycle() }
        } catch (e: OutOfMemoryError) {
            listener?.onMessage("Not enough memory for this right now")
            return false
        }
        if (result == null) return false
        val r = result.rect
        selection?.let { Canvas(result.bitmap).drawBitmap(it.mask, r, Rect(0, 0, r.width(), r.height()), Selections.dstIn) }
        val before = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        val paint = Paint().apply { blendMode = if (layer.alphaLock) BlendMode.SRC_ATOP else BlendMode.SRC_OVER }
        Canvas(layer.bitmap).drawBitmap(result.bitmap, r.left.toFloat(), r.top.toFloat(), paint)
        result.bitmap.recycle()
        pushPixelEdit(layer, r, before)
        return true
    }

    private fun recolorAt(sx: Float, sy: Float) {
        val p = toDoc(sx, sy)
        val layer = doc.active
        when {
            p.x < 0 || p.y < 0 || p.x >= doc.width || p.y >= doc.height -> return
            !layer.visible -> { listener?.onMessage("This layer is hidden"); return }
            layer.vector != null || layer.scene != null -> { listener?.onMessage("Recolor works on drawing layers"); return }
        }
        if (colorDropAt(p.x.toInt(), p.y.toInt(), color)) {
            recolorPoint = p
            recolorCmd = history.top()
            listener?.onMessage("Recolor: change the color, this area follows · tap another area to move on")
        }
    }

    /** the color changed: the last recolored area is filled again with it (one undo step still) */
    private fun recolorUpdate() {
        val p = recolorPoint ?: return
        if (recolorUpdating) return
        if (recolorCmd == null || history.top() !== recolorCmd) { recolorPoint = null; return }
        recolorUpdating = true
        try {
            history.undo(doc)
            if (colorDropAt(p.x.toInt(), p.y.toInt(), color)) recolorCmd = history.top()
            else { recolorPoint = null; recolorCmd = null }
            listener?.onHistoryChanged()
            invalidate()
        } finally { recolorUpdating = false }
    }

    // =========================================================
    // Selection
    // =========================================================

    private fun startSelect(e: MotionEvent) {
        mode = Mode.SELECT_DRAW
        downTime = e.eventTime
        val p = toDoc(e.x, e.y)
        selPts.clear()
        selPts.add(floatArrayOf(p.x, p.y))
        selX0 = p.x; selY0 = p.y; selX1 = p.x; selY1 = p.y
        selDrawing = true
        invalidate()
    }

    private fun moveSelect(e: MotionEvent) {
        if (!selDrawing) return
        for (h in 0 until e.historySize) {
            val p = toDoc(e.getHistoricalX(0, h), e.getHistoricalY(0, h))
            selPts.add(floatArrayOf(p.x, p.y))
        }
        val p = toDoc(e.x, e.y)
        selPts.add(floatArrayOf(p.x, p.y))
        selX1 = p.x; selY1 = p.y
        invalidate()
    }

    private fun finishSelect() {
        if (!selDrawing) return
        selDrawing = false
        if (selectShape.isTap) {
            // magic wand / color range: one tap on the artwork
            val fresh = try {
                val ref = doc.flatten(withBackground = true)
                try { Selections.byColor(ref, selX1.toInt(), selY1.toInt(), wandTolerance, selectShape == SelectShape.WAND) } finally { ref.recycle() }
            } catch (e: OutOfMemoryError) {
                listener?.onMessage("Not enough memory for this right now")
                return
            }
            if (fresh == null && selectCombine == SelectCombine.NEW) listener?.onMessage("Nothing to select there")
            selection = Selections.combine(selection, fresh, selectCombine, doc.width, doc.height)
            return
        }
        val tiny = hypot(selX1 - selX0, selY1 - selY0) * zoom() < 8f * density && selPts.size < 6
        if (tiny && selectShape != SelectShape.BRUSH) {
            // a tap clears the selection (in New mode)
            if (selectCombine == SelectCombine.NEW) selection = null
            return
        }
        val path = if (selectShape == SelectShape.BRUSH) Selections.brushPath(selPts, selectBrushPx / zoom())
            else Selections.shapePath(selectShape, selPts, selX0, selY0, selX1, selY1)
        val fresh = Selections.fromPath(doc.width, doc.height, path)
        // the brush adds to what is there unless you chose Subtract
        val mode = if (selectShape == SelectShape.BRUSH && selectCombine == SelectCombine.NEW && selection != null) SelectCombine.ADD else selectCombine
        selection = Selections.combine(selection, fresh, mode, doc.width, doc.height)
    }

    // ---------------- Measure ----------------

    var measureUnit = MeasureUnit.PX
    /** the two ends of the measured line (document pixels), or null before the first drag */
    var measureA: PointF? = null
        private set
    var measureB: PointF? = null
        private set
    /** what the finger holds: 0 = a new line (its end), 1 = start, 2 = end, 3 = the whole line */
    private var measurePart = 0
    private var measureGrab = PointF()
    private var measureBefore: Pair<PointF?, PointF?> = null to null
    private var measureSnapped = -1
    private val measureText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
    private val measureBox = RectF()
    /** the measurement changed (MainActivity shows the numbers in its bar) */
    var onMeasure: (() -> Unit)? = null

    fun clearMeasure() { measureA = null; measureB = null; invalidate(); onMeasure?.invoke() }

    private fun screenOf(p: PointF): PointF { val a = floatArrayOf(p.x, p.y); viewMatrix.mapPoints(a); return PointF(a[0], a[1]) }

    private fun measureDown(sx: Float, sy: Float) {
        measureBefore = measureA?.let { PointF(it.x, it.y) } to measureB?.let { PointF(it.x, it.y) }
        val a = measureA; val b = measureB
        val p = toDoc(sx, sy)
        measurePart = 0
        if (a != null && b != null) {
            val sa = screenOf(a); val sb = screenOf(b)
            val grab = 26f * density
            measurePart = when {
                hypot(sx - sb.x, sy - sb.y) < grab -> 2
                hypot(sx - sa.x, sy - sa.y) < grab -> 1
                distToSegment(sx, sy, sa, sb) < 16f * density -> 3
                else -> 0
            }
        }
        measureGrab = p
        if (measurePart == 0) { measureA = p; measureB = PointF(p.x, p.y) }
        measureSnapped = -1
        invalidate()
    }

    /** a second finger came at once: that was a zoom, not a measurement */
    private fun measureUndoDown() {
        measureA = measureBefore.first; measureB = measureBefore.second
        invalidate()
    }

    private fun distToSegment(x: Float, y: Float, a: PointF, b: PointF): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-3f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (a.x + dx * t), y - (a.y + dy * t))
    }

    private fun measureMove(sx: Float, sy: Float) {
        val a = measureA ?: return
        val b = measureB ?: return
        val p = toDoc(sx, sy)
        when (measurePart) {
            3 -> {
                val dx = p.x - measureGrab.x; val dy = p.y - measureGrab.y
                a.offset(dx, dy); b.offset(dx, dy)
                measureGrab = p
            }
            1 -> { a.set(p); snapEnd(b, a) }
            else -> { b.set(p); snapEnd(a, b) }
        }
        invalidate()
        onMeasure?.invoke()
    }

    /** near a multiple of 15° the free end catches on it, with a small tick */
    private fun snapEnd(fixed: PointF, free: PointF) {
        val dx = free.x - fixed.x; val dy = free.y - fixed.y
        val len = hypot(dx, dy)
        if (len < 4f) return
        val deg = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat()
        val near = (Math.round(deg / 15f) * 15)
        if (abs(deg - near) < 2.5f) {
            val r = Math.toRadians(near.toDouble())
            free.set(fixed.x + (len * cos(r)).toFloat(), fixed.y - (len * sin(r)).toFloat())
            if (near != measureSnapped) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            measureSnapped = near
        } else measureSnapped = -1
    }

    private fun measureUp() {
        val a = measureA; val b = measureB
        // a tap without a drag: no line
        if (a != null && b != null && measurePart == 0 && hypot(b.x - a.x, b.y - a.y) * zoom() < 6f * density) {
            measureA = measureBefore.first; measureB = measureBefore.second
        }
        invalidate()
        onMeasure?.invoke()
    }

    /** length in the chosen unit (cm and inches at 300 dpi) */
    fun measureLength(): Float {
        val a = measureA ?: return 0f
        val b = measureB ?: return 0f
        val px = hypot(b.x - a.x, b.y - a.y)
        return when (measureUnit) {
            MeasureUnit.PX -> px
            MeasureUnit.CM -> px / 300f * 2.54f
            MeasureUnit.IN -> px / 300f
        }
    }

    /** the angle from the horizontal, counter-clockwise, -180..180 */
    fun measureAngle(): Float {
        val a = measureA ?: return 0f
        val b = measureB ?: return 0f
        return Math.toDegrees(atan2(-(b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
    }

    fun measureLabel(): String {
        val v = measureLength()
        val len = if (measureUnit == MeasureUnit.PX) "${v.roundToInt()} px" else String.format(java.util.Locale.US, "%.2f %s", v, measureUnit.label)
        val a = measureAngle()
        val ang = if (abs(a - a.roundToInt()) < 0.05f) "${a.roundToInt()}°" else String.format(java.util.Locale.US, "%.1f°", a)
        return "$len  ·  $ang"
    }

    private fun drawMeasure(canvas: Canvas) {
        if (interaction != Interaction.MEASURE) return
        val a = measureA ?: return
        val b = measureB ?: return
        val sa = screenOf(a); val sb = screenOf(b)
        val d = density
        // a faint horizontal and the arc of the angle
        handlePaint.style = Paint.Style.STROKE
        val len = hypot(sb.x - sa.x, sb.y - sa.y)
        if (len > 30f * d) {
            handlePaint.color = 0x66FFFFFF; handlePaint.strokeWidth = 1f * d
            handlePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * d, 4f * d), 0f)
            val h = min(len, 70f * d)
            canvas.drawLine(sa.x, sa.y, sa.x + h, sa.y, handlePaint)
            handlePaint.pathEffect = null
            val ang = measureAngle()
            val rr = min(len * 0.45f, 36f * d)
            measureBox.set(sa.x - rr, sa.y - rr, sa.x + rr, sa.y + rr)
            handlePaint.color = 0xCCFFBE3D.toInt(); handlePaint.strokeWidth = 1.6f * d
            canvas.drawArc(measureBox, 0f, -ang, false, handlePaint)
        }
        // the line: dark under light, so it shows on any color
        handlePaint.strokeCap = Paint.Cap.ROUND
        handlePaint.color = 0x99000000.toInt(); handlePaint.strokeWidth = 4f * d
        canvas.drawLine(sa.x, sa.y, sb.x, sb.y, handlePaint)
        handlePaint.color = Ui.ACCENT; handlePaint.strokeWidth = 2f * d
        canvas.drawLine(sa.x, sa.y, sb.x, sb.y, handlePaint)
        // ticks across the ends
        if (len > 1f) {
            val nx = -(sb.y - sa.y) / len * 7f * d; val ny = (sb.x - sa.x) / len * 7f * d
            handlePaint.color = Color.WHITE; handlePaint.strokeWidth = 1.6f * d
            canvas.drawLine(sa.x - nx, sa.y - ny, sa.x + nx, sa.y + ny, handlePaint)
            canvas.drawLine(sb.x - nx, sb.y - ny, sb.x + nx, sb.y + ny, handlePaint)
        }
        handlePaint.style = Paint.Style.FILL
        for (p in listOf(sa, sb)) {
            handlePaint.color = 0x99000000.toInt(); canvas.drawCircle(p.x, p.y, 7.5f * d, handlePaint)
            handlePaint.color = Color.WHITE; canvas.drawCircle(p.x, p.y, 5.5f * d, handlePaint)
        }
        // the numbers, beside the middle of the line
        val label = measureLabel()
        measureText.textSize = 13f * d
        val tw = measureText.measureText(label)
        val mx = (sa.x + sb.x) / 2; val my = (sa.y + sb.y) / 2
        var ox = 0f; var oy = -22f * d
        if (len > 1f) { ox = -(sb.y - sa.y) / len * 24f * d; oy = (sb.x - sa.x) / len * 24f * d; if (oy > 0) { ox = -ox; oy = -oy } }
        val cx = (mx + ox).coerceIn(tw / 2 + 12f * d, width - tw / 2 - 12f * d)
        val cy = (my + oy).coerceIn(20f * d, height - 20f * d)
        measureBox.set(cx - tw / 2 - 9f * d, cy - 13f * d, cx + tw / 2 + 9f * d, cy + 11f * d)
        handlePaint.color = 0xE6202228.toInt()
        canvas.drawRoundRect(measureBox, 12f * d, 12f * d, handlePaint)
        canvas.drawText(label, cx - tw / 2, cy + 4.5f * d, measureText)
        handlePaint.strokeCap = Paint.Cap.BUTT
    }

    /** the measured line becomes the Ruler guide: strokes then run along it */
    fun measureToRuler(): Boolean {
        val a = measureA ?: return false
        val b = measureB ?: return false
        if (hypot(b.x - a.x, b.y - a.y) < 4f) return false
        val g = doc.guide
        g.setVp(5, a.x / doc.width, a.y / doc.height)
        g.setVp(6, b.x / doc.width, b.y / doc.height)
        g.type = GuideType.RULER
        doc.revision++
        invalidate()
        return true
    }

    /** draws the measured line with the brush in hand (or erases along it with the eraser) */
    fun drawMeasuredLine(): Boolean {
        val a = measureA ?: return false
        val b = measureB ?: return false
        if (hypot(b.x - a.x, b.y - a.y) < 2f) return false
        val sb = prepareDrawnStroke("Living brushes: use Make it a ruler, then draw along it") ?: return false
        engine.renderPoints(sb, QuickShape.toPoints(Shape.Line(PointF(a.x, a.y), PointF(b.x, b.y))), 0.8f, shapeSeed)
        commitStroke()
        lastStroke = null
        invalidate()
        return true
    }

    /**
     * Gets the stroke layer ready for a stroke Inkora draws itself (a measured line, a shape) with
     * the brush in hand, or the eraser. Null (with a message) when this layer can't take one.
     */
    private fun prepareDrawnStroke(livingMessage: String?): Bitmap? {
        val layer = doc.active
        when {
            !layer.visible || doc.groupOf(layer)?.visible == false -> { listener?.onMessage("This layer is hidden"); return null }
            layer.vector != null -> { listener?.onMessage("Vector layer: use the vector tools, or pick a drawing layer"); return null }
            layer.scene != null -> { listener?.onMessage("3D layer: change it with Edit 3D scene in Layers, or draw on a new layer"); return null }
        }
        val erasing = tool == Tool.ERASER
        val brush = if (erasing) eraserBrush else paintBrush
        if (brush.preset.living != null && livingMessage != null) { listener?.onMessage(livingMessage); return null }
        val sb = strokeBitmap ?: return null
        sb.eraseColor(0); strokeErased()
        strokeErasing = erasing
        smudging = false
        engine.brush = brush
        engine.color = if (erasing) Color.BLACK else if (layer.isLayerMask) Color.WHITE else color
        engine.dabHandler = null
        engine.symmetry = symmetry
        engine.wetSource = null
        strokeGrain = grainPaintFor(brush)
        strokeLook = if (erasing) null else strokeLookFor(brush)
        strokeMask = if (brush.dualTip() != null) {
            val mb = maskBitmap?.takeIf { it.width == doc.width && it.height == doc.height }
                ?: run { maskBitmap?.recycle(); runCatching { Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ALPHA_8) }.getOrNull() }
            maskBitmap = mb
            mb?.eraseColor(0)
            mb
        } else null
        engine.maskTarget = strokeMask
        return sb
    }

    // ---------------- Shapes (on a drawing layer) ----------------

    var shapeKind = ShapeKind.RECT
    /** filled with the color, or an outline drawn with the brush */
    var shapeFilled = false
    /** square, circle, even star; a line keeps to 15° steps */
    var shapeEven = false
    /** a finger is down while the shape is drawn: it is even for as long as the finger stays */
    private var shapeFingerEven = false
    private var shapeButtonEven = false
    private var shapePointerId = -1
    private var shapePen = false
    private var shapeA: PointF? = null
    private var shapeB: PointF? = null
    private val shapeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapePath = android.graphics.Path()

    private fun shapeDown(p: PointF): Boolean {
        val outlineOnly = !shapeFilled || shapeKind == ShapeKind.LINE || shapeKind == ShapeKind.ARROW
        if (prepareDrawnStroke(if (outlineOnly) "Living brushes can't trace shapes: pick another brush, or Filled" else null) == null) return false
        shapeA = p; shapeB = PointF(p.x, p.y)
        strokeActive = true
        return true
    }

    private fun shapeMove(p: PointF) {
        if (shapeA == null) return
        shapeB = p
        renderShapeTool()
    }

    /** the S Pen button held while drawing a shape: even while it is held, free again when let go */
    private fun shapeButton(e: MotionEvent) {
        val held = (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_SECONDARY)) != 0
        if (held == shapeButtonEven) return
        shapeButtonEven = held
        if (held) evenFeedback()
        if (shapeA != null) renderShapeTool()
    }

    private fun makeShapeEven() {
        if (shapeFingerEven) return
        shapeFingerEven = true
        evenFeedback()
        if (shapeA != null) renderShapeTool()
    }

    private fun evenFeedback() {
        if (shapeEven) return
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        listener?.onMessage(when (shapeKind) { ShapeKind.RECT -> "Square"; ShapeKind.ELLIPSE -> "Circle"; ShapeKind.LINE, ShapeKind.ARROW -> "Straight line"; else -> "Even shape" })
    }

    private fun shapeUp() {
        shapeButtonEven = false
        val a = shapeA; val b = shapeB
        shapeA = null; shapeB = null
        strokeActive = false
        if (a == null || b == null || hypot(b.x - a.x, b.y - a.y) * zoom() < 6f * density) {
            strokeBitmap?.eraseColor(0); strokeErased(); invalidate(); return
        }
        commitStroke()
        lastStroke = null
        invalidate()
    }

    private fun shapeCancel() {
        shapeButtonEven = false
        shapeA = null; shapeB = null
        strokeActive = false
        strokeBitmap?.eraseColor(0); strokeErased()
        invalidate()
    }

    /** the corners (or the run of points) of the shape between where the finger came down and where it is */
    private fun shapeOutline(a: PointF, b0: PointF): Pair<List<PointF>, Boolean> {
        var b = b0
        val even = shapeEven || shapeFingerEven || shapeButtonEven
        val kind = shapeKind
        if (kind == ShapeKind.LINE || kind == ShapeKind.ARROW) {
            if (even) b = (Shape.Line(a, b).perfected() as Shape.Line).b
            if (kind == ShapeKind.LINE) return listOf(a, b) to false
            val len = hypot(b.x - a.x, b.y - a.y)
            val ang = atan2(b.y - a.y, b.x - a.x)
            val head = max(10f, len * 0.2f)
            val w = 0.5f
            val l = PointF(b.x - head * cos(ang - w), b.y - head * sin(ang - w))
            val r = PointF(b.x - head * cos(ang + w), b.y - head * sin(ang + w))
            return listOf(a, b, l, b, r) to false
        }
        var dx = b.x - a.x; var dy = b.y - a.y
        if (even) { val s = max(abs(dx), abs(dy)); dx = if (dx < 0) -s else s; dy = if (dy < 0) -s else s }
        // an even triangle has three equal sides
        if (even && kind == ShapeKind.TRIANGLE) dy = (if (dy < 0) -1f else 1f) * abs(dx) * 0.866f
        val x0 = min(a.x, a.x + dx); val x1 = max(a.x, a.x + dx)
        val y0 = min(a.y, a.y + dy); val y1 = max(a.y, a.y + dy)
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
        val rx = (x1 - x0) / 2; val ry = (y1 - y0) / 2
        fun ring(n: Int, inner: Float?, start: Double): List<PointF> {
            val k = if (inner != null) n * 2 else n
            return List(k) { i ->
                val t = start + 2 * PI * i / k
                val f = if (inner != null && i % 2 == 1) inner else 1f
                PointF(cx + (rx * f * cos(t)).toFloat(), cy + (ry * f * sin(t)).toFloat())
            }
        }
        val pts = when (kind) {
            ShapeKind.RECT -> listOf(PointF(x0, y0), PointF(x1, y0), PointF(x1, y1), PointF(x0, y1))
            ShapeKind.TRIANGLE -> listOf(PointF(cx, y0), PointF(x1, y1), PointF(x0, y1))
            ShapeKind.STAR -> ring(5, 0.42f, -PI / 2)
            ShapeKind.POLYGON -> ring(6, null, 0.0)
            ShapeKind.HEART -> List(72) { i ->
                val t = 2 * PI * i / 72
                val hx = 16 * sin(t).pow(3)
                val hy = 13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)
                PointF(cx + (hx / 16.0 * rx).toFloat(), cy - ((hy + 2.5) / 15.0 * ry).toFloat())
            }
            else -> ring(96, null, 0.0) // ellipse
        }
        return pts to true
    }

    private fun renderShapeTool() {
        val a = shapeA ?: return
        val b = shapeB ?: return
        val sb = strokeBitmap ?: return
        sb.eraseColor(0); strokeErased()
        strokeMask?.eraseColor(0)
        val (pts, closed) = shapeOutline(a, b)
        if (pts.size < 2) return
        val fill = shapeFilled && closed
        if (fill) {
            shapePath.rewind()
            shapePath.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) shapePath.lineTo(pts[i].x, pts[i].y)
            shapePath.close()
            shapeFill.color = engine.color or 0xFF000000.toInt()
            Canvas(sb).drawPath(shapePath, shapeFill)
            strokeMask?.let { Canvas(it).drawPath(shapePath, shapeFill) }
            val r = RectF(); shapePath.computeBounds(r, true); r.inset(-2f, -2f)
            engine.dirty.set(r)
        } else {
            val shape: Shape = if (closed) Shape.Poly(pts, true) else Shape.Poly(pts, false)
            engine.renderPoints(sb, QuickShape.toPoints(shape), 0.8f, shapeSeed)
        }
        invalidate()
    }

    // ---------------- Gradient ----------------

    var gradientKind = GradientKind.LINEAR
    /** the color the gradient ends in; 0 = it fades out to nothing */
    var gradientTo = 0
    var gradientReverse = false
    private var gradA: PointF? = null
    private var gradB: PointF? = null
    private val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    /** the gradient between the two points being dragged, or null when there is none yet */
    private fun gradientShader(): Shader? {
        val a = gradA ?: return null
        val b = gradB ?: return null
        val d = hypot(b.x - a.x, b.y - a.y)
        if (d < 3f) return null
        val from = color or 0xFF000000.toInt()
        // fading out keeps the color and only lets go of its strength (no grey fringe)
        val to = if (gradientTo == 0) from and 0x00FFFFFF else gradientTo or 0xFF000000.toInt()
        val c0 = if (gradientReverse) to else from
        val c1 = if (gradientReverse) from else to
        return when (gradientKind) {
            GradientKind.LINEAR -> android.graphics.LinearGradient(a.x, a.y, b.x, b.y, c0, c1, Shader.TileMode.CLAMP)
            GradientKind.RADIAL -> android.graphics.RadialGradient(a.x, a.y, d, c0, c1, Shader.TileMode.CLAMP)
        }
    }

    private fun drawGradientLine(canvas: Canvas) {
        if (interaction != Interaction.GRADIENT) return
        val a = gradA ?: return
        val b = gradB ?: return
        val p = floatArrayOf(a.x, a.y, b.x, b.y)
        viewMatrix.mapPoints(p)
        handlePaint.style = Paint.Style.STROKE
        handlePaint.strokeWidth = 3f * density
        handlePaint.color = 0x88000000.toInt()
        canvas.drawLine(p[0], p[1], p[2], p[3], handlePaint)
        handlePaint.strokeWidth = 1.5f * density
        handlePaint.color = Color.WHITE
        canvas.drawLine(p[0], p[1], p[2], p[3], handlePaint)
        handlePaint.style = Paint.Style.FILL
        canvas.drawCircle(p[0], p[1], 6f * density, handlePaint)
        canvas.drawCircle(p[2], p[3], 4f * density, handlePaint)
    }

    /** the dragged gradient goes onto the active layer (inside the selection when there is one) */
    private fun commitGradient() {
        val sh = gradientShader()
        gradA = null; gradB = null
        if (sh == null) { invalidate(); return }
        val layer = doc.active
        if (!layer.visible) { listener?.onMessage("This layer is hidden"); return }
        if (!plainLayer(layer)) return
        val sel = selection
        val r = if (sel != null) Rect(sel.bounds) else Rect(0, 0, doc.width, doc.height)
        if (!r.intersect(0, 0, doc.width, doc.height) || r.isEmpty) return
        val before = try { Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height()) } catch (e: OutOfMemoryError) { null }
        if (before == null) { listener?.onMessage("Not enough memory for this right now"); return }
        val c = Canvas(layer.bitmap)
        // on a mask the gradient is the mask itself: from "show" to "hide"
        val lp = Paint().apply { blendMode = if (layer.alphaLock) BlendMode.SRC_ATOP else BlendMode.SRC_OVER }
        val s = c.saveLayer(RectF(r), lp)
        gradPaint.shader = sh
        c.drawRect(RectF(r), gradPaint)
        if (sel != null) c.drawBitmap(sel.mask, 0f, 0f, Selections.dstIn)
        c.restoreToCount(s)
        pushPixelEdit(layer, r, before)
    }

    // ---------------- Liquify ----------------

    var liquify: LiquifySession? = null
        private set
    /** liquify brush size on screen (it follows the zoom) */
    var liquifyBrushPx = 140f
    private var liqX = 0f
    private var liqY = 0f
    private var liqShowX = -1f
    private var liqShowY = -1f

    fun beginLiquify(): Boolean {
        if (busy()) return false
        val layer = doc.active
        if (!layer.visible) { listener?.onMessage("This layer is hidden"); return false }
        if (layer.vector != null) { listener?.onMessage("Liquify works on drawing layers (rasterize this vector layer first)"); return false }
        if (layer.scene != null) { listener?.onMessage("Liquify works on drawing layers (rasterize this 3D layer first)"); return false }
        liquify = try { LiquifySession(layer) } catch (e: OutOfMemoryError) { null }
        if (liquify == null) { listener?.onMessage("Not enough memory for this right now"); return false }
        interaction = Interaction.LIQUIFY
        return true
    }

    fun endLiquify(apply: Boolean) {
        val s = liquify ?: return
        if (apply && s.changed) pushPixelEdit(s.layer, Rect(0, 0, doc.width, doc.height), s.original)
        else s.restore()
        liquify = null
        liqShowX = -1f
        interaction = Interaction.PAINT
        doc.revision++
        invalidate()
    }

    private fun startLiquify(e: MotionEvent) {
        val s = liquify ?: run { mode = Mode.IGNORE; return }
        mode = Mode.LIQUIFY_DRAG
        downTime = e.eventTime
        val p = toDoc(e.x, e.y)
        liqX = p.x; liqY = p.y
        liqShowX = e.x; liqShowY = e.y
        if (s.mode != LiquifyMode.PUSH) s.dab(p.x, p.y, liquifyBrushPx / zoom() / 2, 0f, 0f)
        invalidate()
    }

    private fun moveLiquify(e: MotionEvent) {
        val s = liquify ?: return
        val r = liquifyBrushPx / zoom() / 2
        val step = max(2f, r * 0.12f)
        fun to(sx: Float, sy: Float) {
            val p = toDoc(sx, sy)
            var dx = p.x - liqX; var dy = p.y - liqY
            val d = hypot(dx, dy)
            if (d < step && s.mode == LiquifyMode.PUSH) return
            // even dabs along the way, so a fast drag bends smoothly
            val n = max(1, (d / step).toInt())
            dx /= n; dy /= n
            for (k in 1..n) s.dab(liqX + dx * k, liqY + dy * k, r, dx, dy)
            liqX = p.x; liqY = p.y
        }
        for (h in 0 until e.historySize) to(e.getHistoricalX(0, h), e.getHistoricalY(0, h))
        to(e.x, e.y)
        liqShowX = e.x; liqShowY = e.y
        invalidate()
    }

    private fun drawLiquifyBrush(canvas: Canvas) {
        if (liquify == null || liqShowX < 0) return
        handlePaint.style = Paint.Style.STROKE
        handlePaint.strokeWidth = 1.5f * density
        handlePaint.color = 0xCCFFFFFF.toInt()
        canvas.drawCircle(liqShowX, liqShowY, liquifyBrushPx / 2, handlePaint)
        handlePaint.color = 0x88000000.toInt()
        canvas.drawCircle(liqShowX, liqShowY, liquifyBrushPx / 2 + 1.5f * density, handlePaint)
    }

    fun selectAll() { selection = Selections.all(doc.width, doc.height) }

    fun invertSelection() {
        val s = selection ?: return
        selection = Selections.invert(s, doc.width, doc.height)
    }

    /** Softens the selection edge by [radius] px. */
    fun featherSelection(radius: Float) {
        val s = selection ?: return
        val r = Rect(s.bounds)
        val grow = (radius * 2).toInt() + 2
        r.inset(-grow, -grow)
        if (!r.intersect(0, 0, doc.width, doc.height)) return
        val w = r.width(); val h = r.height()
        val argb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(argb).drawBitmap(s.mask, r, Rect(0, 0, w, h), Paint().apply { color = Color.BLACK })
        val px = IntArray(w * h)
        argb.getPixels(px, 0, w, 0, 0, w, h)
        Adjust.blur(px, w, h, radius)
        argb.setPixels(px, 0, w, 0, 0, w, h)
        val mask = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ALPHA_8)
        Canvas(mask).drawBitmap(argb, r.left.toFloat(), r.top.toFloat(), null)
        argb.recycle()
        selection = Selection(mask, s.outline, r)
        listener?.onMessage("Feathered ${radius.toInt()} px")
    }

    // =========================================================
    // layer actions used by gestures and keyboard shortcuts
    // =========================================================

    /** The top visible layer with paint under screen point (sx, sy) becomes the active one. */
    fun selectLayerAt(sx: Float, sy: Float) {
        val p = toDoc(sx, sy)
        val x = p.x.toInt(); val y = p.y.toInt()
        if (x !in 0 until doc.width || y !in 0 until doc.height) return
        for (i in doc.layers.indices.reversed()) {
            val l = doc.layers[i]
            if (!l.visible || l.isLayerMask || doc.groupOf(l)?.visible == false) continue
            if ((l.bitmap.getPixel(x, y) ushr 24) > 12) {
                if (doc.activeIndex != i) {
                    doc.activeIndex = i
                    vectorEditor.reset()
                    listener?.onLayerPicked()
                }
                listener?.onMessage(l.name)
                return
            }
        }
        listener?.onMessage("No paint here")
    }

    fun clearActiveLayer() {
        if (busy()) return
        val layer = doc.active
        layer.vector?.let { v ->
            val before = v.copyShapes()
            v.shapes.clear()
            v.render(layer.bitmap)
            vectorEditor.reset()
            pushCommand(VectorEdit(layer, before, emptyList()))
            listener?.onMessage("Layer cleared")
            return
        }
        if (selection != null) { clearSelection(); listener?.onMessage("Selection cleared"); return }
        val r = Rect(0, 0, doc.width, doc.height)
        val before = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        layer.bitmap.eraseColor(0)
        pushPixelEdit(layer, r, before)
        listener?.onMessage("Layer cleared")
    }

    fun duplicateActiveLayer() {
        if (busy()) return
        if (doc.layers.size >= doc.maxLayers()) { listener?.onMessage("Layer limit reached"); return }
        val src = doc.active
        val index = doc.activeIndex
        val copy = try { src.bitmap.copy(Bitmap.Config.ARGB_8888, true) } catch (e: OutOfMemoryError) { null }
        if (copy == null) { listener?.onMessage("Not enough memory for another layer right now"); return }
        val l = Layer(copy, src.name + " copy")
        l.copySettingsFrom(src)
        l.vector = src.vector?.copy()
        val cmd = AddLayerCmd(l, index + 1, index)
        cmd.redo(doc)
        pushCommand(cmd)
        listener?.onLayerPicked()
        listener?.onMessage("Layer duplicated")
    }

    /** Copies the selection (or the whole layer) to Inkora's clipboard; [cut] also removes it. */
    fun copyToClipboard(cut: Boolean) {
        if (busy()) return
        val layer = doc.active
        if (layer.vector != null) { listener?.onMessage("On a vector layer use the vector tools"); return }
        val sel = selection
        val bmp = try { layer.bitmap.copy(Bitmap.Config.ARGB_8888, true) } catch (e: OutOfMemoryError) { null }
        if (bmp == null) { listener?.onMessage("Not enough memory for this right now"); return }
        if (sel != null) Canvas(bmp).drawBitmap(sel.mask, 0f, 0f, Selections.dstIn)
        val bounds = Selections.contentBounds(bmp)
        if (bounds == null || bounds.isEmpty) { bmp.recycle(); listener?.onMessage("Nothing to copy here"); return }
        clipboard?.recycle()
        clipboard = Bitmap.createBitmap(bmp, bounds.left, bounds.top, bounds.width(), bounds.height())
        clipboardAt = android.graphics.Point(bounds.left, bounds.top)
        bmp.recycle()
        if (cut) {
            if (sel != null) clearSelection()
            else {
                val r = Rect(0, 0, doc.width, doc.height)
                val before = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                layer.bitmap.eraseColor(0)
                pushPixelEdit(layer, r, before)
            }
            listener?.onMessage("Cut")
        } else listener?.onMessage("Copied")
    }

    val hasClipboard get() = clipboard != null

    /** Pastes the clipboard onto a new layer, where it was copied from. */
    fun pasteClipboard() {
        if (busy()) return
        val clip = clipboard ?: run { listener?.onMessage("Nothing copied yet"); return }
        if (doc.layers.size >= doc.maxLayers()) { listener?.onMessage("Layer limit reached"); return }
        val layer = try { doc.createLayer() } catch (e: OutOfMemoryError) {
            listener?.onMessage("Not enough memory for another layer right now"); return
        }
        layer.name = L10n.tr("Pasted")
        val at = clipboardAt
        Canvas(layer.bitmap).drawBitmap(clip, at.x.toFloat().coerceAtMost(doc.width - 1f), at.y.toFloat().coerceAtMost(doc.height - 1f), null)
        val cmd = AddLayerCmd(layer, doc.activeIndex + 1, doc.activeIndex)
        cmd.redo(doc)
        pushCommand(cmd)
        listener?.onLayerPicked()
        listener?.onMessage("Pasted")
    }

    /** Turns the view around the screen's center (keyboard R). */
    fun rotateViewBy(degrees: Float) {
        viewMatrix.postRotate(degrees, width / 2f, height / 2f)
        invalidate()
    }

    /** Zooms the view around the screen's center (keyboard Ctrl + / −). */
    fun zoomViewBy(factor: Float) {
        val z = zoom()
        val f = (z * factor).coerceIn(0.03f, 64f) / z
        viewMatrix.postScale(f, f, width / 2f, height / 2f)
        invalidate()
    }

    /** Moves what is being transformed by (dx, dy) document pixels (arrow keys). */
    fun nudgeTransform(dx: Float, dy: Float): Boolean {
        val t = transform ?: return false
        t.matrix.postTranslate(dx, dy)
        invalidate()
        return true
    }

    /** Copies the selected pixels of the active layer onto a new layer. */
    fun copySelectionToNewLayer(): Boolean {
        val s = selection ?: run { listener?.onMessage("Select something first"); return false }
        if (doc.layers.size >= doc.maxLayers()) { listener?.onMessage("Layer limit reached"); return false }
        val src = doc.active
        val layer = try { doc.createLayer() } catch (e: OutOfMemoryError) {
            listener?.onMessage("Not enough memory for another layer right now"); return false
        }
        layer.name = src.name + " (copy)"
        val c = Canvas(layer.bitmap)
        c.drawBitmap(src.bitmap, 0f, 0f, null)
        c.drawBitmap(s.mask, 0f, 0f, Selections.dstIn)
        val cmd = AddLayerCmd(layer, doc.activeIndex + 1, doc.activeIndex)
        cmd.redo(doc)
        pushCommand(cmd)
        listener?.onMessage("Copied to a new layer")
        return true
    }

    /** what is selected on the current layer, as its own picture (for "Make a brush"); null: nothing there */
    fun selectedPixels(): Bitmap? {
        val s = selection ?: return null
        val r = Rect(s.bounds)
        if (!r.intersect(0, 0, doc.width, doc.height) || r.isEmpty) return null
        val out = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(doc.active.bitmap, Rect(r), Rect(0, 0, r.width(), r.height()), null)
        c.drawBitmap(s.mask, Rect(r), Rect(0, 0, r.width(), r.height()), Selections.dstIn)
        return out
    }

    /** vector and 3D layers are drawn from their shapes: their pixels are not edited directly */
    private fun plainLayer(layer: Layer): Boolean {
        if (layer.vector != null) { listener?.onMessage("On a vector layer use the vector tools"); return false }
        if (layer.scene != null) { listener?.onMessage("3D layer: change it with Edit 3D scene in Layers, or draw on a new layer"); return false }
        return true
    }

    fun clearSelection() {
        val s = selection ?: run { listener?.onMessage("Select something first"); return }
        val layer = doc.active
        if (!plainLayer(layer)) return
        val r = s.bounds
        val before = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        Canvas(layer.bitmap).drawBitmap(s.mask, 0f, 0f, Selections.dstOut)
        pushPixelEdit(layer, r, before)
    }

    fun fillSelection(fill: Int) {
        val s = selection ?: run { listener?.onMessage("Select something first"); return }
        val layer = doc.active
        if (!plainLayer(layer)) return
        val r = s.bounds
        val before = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        val c = Canvas(layer.bitmap)
        val lp = Paint().apply { blendMode = if (layer.alphaLock) BlendMode.SRC_ATOP else BlendMode.SRC_OVER }
        val save = c.saveLayer(RectF(r), lp)
        c.drawColor(fill or 0xFF000000.toInt())
        c.drawBitmap(s.mask, 0f, 0f, Selections.dstIn)
        c.restoreToCount(save)
        pushPixelEdit(layer, r, before)
    }

    // =========================================================
    // Transform
    // =========================================================

    /** Lifts the selection (or the whole layer content) so it can be moved, scaled, rotated. */
    fun beginTransform(): Boolean {
        if (transform != null) return true
        if (strokeActive) cancelStroke()
        val targets = targetLayers()
        if (targets.size > 1) return beginMultiTransform(targets)
        val layer = doc.active
        if (!layer.visible) { listener?.onMessage("This layer is hidden"); return false }
        if (layer.vector != null) { listener?.onMessage("On a vector layer use the vector Select tool"); return false }
        if (layer.scene != null) { listener?.onMessage("Move a 3D layer in its 3D editor (Layers › Edit 3D scene), or rasterize it"); return false }
        val sel = selection
        val region = (if (sel != null) Rect(sel.bounds) else Selections.contentBounds(layer.bitmap))
        if (region == null || !region.intersect(0, 0, doc.width, doc.height) || region.isEmpty) {
            listener?.onMessage("This layer is empty")
            return false
        }
        val (floating, before) = lift(layer, region, sel) ?: return false
        transform = TransformSession(layer, floating, region, before, sel)
        interaction = Interaction.TRANSFORM
        return true
    }

    /** cuts [region] (or the selection inside it) out of [layer]: the piece and a copy of the layer before */
    private fun lift(layer: Layer, region: Rect, sel: Selection?): Pair<Bitmap, Bitmap>? {
        val before = try { layer.bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (e: OutOfMemoryError) { null }
            ?: run { listener?.onMessage("Not enough memory for this right now"); return null }
        val floating = Bitmap.createBitmap(layer.bitmap, region.left, region.top, region.width(), region.height())
            .copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(layer.bitmap)
        if (sel != null) {
            Canvas(floating).drawBitmap(sel.mask, region, Rect(0, 0, region.width(), region.height()), Selections.dstIn)
            c.drawBitmap(sel.mask, 0f, 0f, Selections.dstOut)
        } else {
            c.drawRect(RectF(region), Paint().apply { blendMode = BlendMode.CLEAR })
        }
        return floating to before
    }

    /** moves several layers (picked ones, or a group) together */
    private fun beginMultiTransform(targets: List<Layer>): Boolean {
        val usable = targets.filter { it.visible && it.vector == null && it.scene == null }
        if (usable.isEmpty()) { listener?.onMessage("These layers are hidden or vector layers"); return false }
        if (usable.size < targets.size) listener?.onMessage("Hidden and vector layers stay where they are")
        val sel = selection
        val region = if (sel != null) Rect(sel.bounds) else {
            var u: Rect? = null
            for (l in usable) Selections.contentBounds(l.bitmap)?.let { r -> if (u == null) u = Rect(r) else u!!.union(r) }
            u
        }
        if (region == null || !region.intersect(0, 0, doc.width, doc.height) || region.isEmpty) {
            listener?.onMessage("These layers are empty")
            return false
        }
        val primary = if (doc.active in usable) doc.active else usable.last()
        val pieces = ArrayList<TransformSession.Piece>()
        var main: Pair<Bitmap, Bitmap>? = null
        for (l in usable) {
            val p = lift(l, region, sel)
            if (p == null) {
                // not enough memory: put back what was lifted
                for (pc in pieces) { Canvas(pc.layer.bitmap).drawBitmap(pc.before, 0f, 0f, Paint().apply { blendMode = BlendMode.SRC }); pc.floating.recycle(); pc.before.recycle() }
                main?.let { (f, b) -> Canvas(primary.bitmap).drawBitmap(b, 0f, 0f, Paint().apply { blendMode = BlendMode.SRC }); f.recycle(); b.recycle() }
                return false
            }
            if (l === primary) main = p else pieces.add(TransformSession.Piece(l, p.first, p.second))
        }
        val (floating, before) = main!!
        transform = TransformSession(primary, floating, region, before, sel, pieces)
        interaction = Interaction.TRANSFORM
        return true
    }

    fun transformAction(action: String) {
        val t = transform ?: return
        when (action) {
            "flipH" -> t.flipH()
            "flipV" -> t.flipV()
            "rotate" -> t.rotate90()
            "fit" -> t.fitTo(doc.width, doc.height)
            "reset" -> t.reset()
            "free" -> t.free()
            "distort" -> t.distort()
            "warp" -> t.warpMode()
        }
        invalidate()
    }

    fun finishTransform(apply: Boolean) {
        val t = transform ?: return
        val all = listOf(TransformSession.Piece(t.layer, t.floating, t.before)) + t.extras
        if (apply) {
            val r = t.resultBounds(doc.width, doc.height)
            val edits = ArrayList<Command>()
            for (p in all) {
                t.drawPiece(Canvas(p.layer.bitmap), p.floating, transformPaint)
                if (!r.isEmpty) {
                    val beforeRegion = Bitmap.createBitmap(p.before, r.left, r.top, r.width(), r.height())
                    val after = Bitmap.createBitmap(p.layer.bitmap, r.left, r.top, r.width(), r.height())
                    edits.add(PixelEdit(p.layer, Rect(r), beforeRegion, after))
                }
            }
            transform = null
            if (edits.size == 1) pushCommand(edits[0]) else if (edits.isNotEmpty()) pushCommand(MultiCmd(edits, "move"))
            // a warped selection has no straight outline to follow: it is let go
            selection = if (t.shape == TransformSession.Shape.WARP) null
                else t.selection?.let { Selections.transformed(it, t.movement(), doc.width, doc.height) }
        } else {
            for (p in all) Canvas(p.layer.bitmap).drawBitmap(p.before, 0f, 0f, Paint().apply { blendMode = BlendMode.SRC })
        }
        transform = null
        snapTargets = null
        for (p in all) { p.floating.recycle(); p.before.recycle() }
        interaction = Interaction.PAINT
        invalidate()
    }

    /**
     * When set, the next line drawn on the canvas is not painted: its points (in the document) are
     * handed to this once, for things that follow a line (text on a path).
     */
    var pathCapture: ((List<PointF>) -> Unit)? = null
        set(v) { field = v; capturePts.clear(); invalidate() }
    private val capturePts = ArrayList<PointF>()

    /** Distort/Warp: the handle being dragged (-1: the whole piece) */
    private var shapeGrab = -1
    private var shapeLast = PointF()

    private fun startTransformDrag(e: MotionEvent) {
        val t = transform ?: run { mode = Mode.IGNORE; return }
        val handles = t.shapeHandles()
        if (handles != null) {
            mode = Mode.TRANSFORM_DRAG
            downTime = e.eventTime
            tDrag = Drag.MOVE
            tMoved = false
            tDownScreen = PointF(e.x, e.y)
            val scr = handles.copyOf(); viewMatrix.mapPoints(scr)
            var best = -1; var bd = 30f * density
            for (k in 0 until scr.size / 2) { val d = hypot(e.x - scr[k * 2], e.y - scr[k * 2 + 1]); if (d < bd) { bd = d; best = k } }
            shapeGrab = best
            shapeLast = toDoc(e.x, e.y)
            return
        }
        shapeGrab = -1
        mode = Mode.TRANSFORM_DRAG
        downTime = e.eventTime
        tStart.set(t.matrix)
        tBoxStart.set(t.box)
        tDown = toDoc(e.x, e.y)
        tDownScreen = PointF(e.x, e.y)
        tMoved = false
        val cc = t.center()
        tCenter = PointF(cc[0], cc[1])
        // which handle was touched? (screen space)
        val c = t.corners()
        viewMatrix.mapPoints(c)
        val rh = rotateHandle(c)
        val ah = alignHandle(c)
        val reach = 28f * density
        val side = (0 until 4).firstOrNull { i ->
            val j = (i + 1) % 4
            hypot(e.x - (c[i * 2] + c[j * 2]) / 2, e.y - (c[i * 2 + 1] + c[j * 2 + 1]) / 2) < reach * 0.85f
        }
        tDrag = when {
            hypot(e.x - rh.x, e.y - rh.y) < reach -> Drag.ROTATE
            hypot(e.x - ah.x, e.y - ah.y) < reach -> Drag.ALIGN
            (0 until 4).any { hypot(e.x - c[it * 2], e.y - c[it * 2 + 1]) < reach } -> Drag.SCALE
            side != null -> { tSide = side; Drag.SIDE }
            else -> Drag.MOVE
        }
        tAlignStart = t.boxAngle()
        snapX = null; snapY = null
        if (tDrag == Drag.MOVE && transformSnap && snapTargets == null) snapTargets = collectSnapTargets(t)
    }

    private var tDownScreen = PointF()
    private var tMoved = false

    /** where things line up: the canvas (center, edges) and the edges and middles of what is on the other visible layers */
    private fun collectSnapTargets(t: TransformSession): Pair<List<Float>, List<Float>> {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        val moving = setOf(t.layer) + t.extras.map { it.layer }
        for (l in doc.layers) {
            if (l in moving || !l.visible || l.isLayerMask || l.shadow) continue
            val r = Selections.contentBounds(l.bitmap) ?: continue
            if (r.width() >= doc.width - 2 && r.height() >= doc.height - 2) continue // a full background tells nothing
            xs += listOf(r.left.toFloat(), r.exactCenterX(), r.right.toFloat())
            ys += listOf(r.top.toFloat(), r.exactCenterY(), r.bottom.toFloat())
        }
        return xs to ys
    }

    private fun updateTransformDrag(e: MotionEvent) {
        val t = transform ?: return
        if (!tMoved && hypot(e.x - tDownScreen.x, e.y - tDownScreen.y) > 8f * density) tMoved = true
        if (!tMoved) return
        val p = toDoc(e.x, e.y)
        if (t.shape != TransformSession.Shape.FREE) {
            when {
                shapeGrab < 0 -> t.shift(p.x - shapeLast.x, p.y - shapeLast.y)
                t.shape == TransformSession.Shape.DISTORT -> t.moveCorner(shapeGrab, p.x, p.y)
                else -> t.moveWarp(shapeGrab, p.x, p.y)
            }
            shapeLast = p
            invalidate()
            return
        }
        t.matrix.set(tStart)
        t.box.set(tBoxStart)
        when (tDrag) {
            Drag.MOVE -> {
                var dx = p.x - tDown.x; var dy = p.y - tDown.y
                snapX = null; snapY = null
                if (transformSnap) {
                    // the piece's outline (what you see), after this move
                    val c = t.corners()
                    var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                    for (i in 0 until 4) { x0 = min(x0, c[i * 2]); x1 = max(x1, c[i * 2]); y0 = min(y0, c[i * 2 + 1]); y1 = max(y1, c[i * 2 + 1]) }
                    val reach = 8f * density / zoom()
                    val canvasX = listOf(0f, doc.width / 2f, doc.width.toFloat())
                    val canvasY = listOf(0f, doc.height / 2f, doc.height.toFloat())
                    val (ox, oy) = snapTargets ?: (emptyList<Float>() to emptyList())
                    fun best(edges: List<Float>, d: Float, canvasT: List<Float>, objT: List<Float>): Triple<Float, Float, Boolean>? {
                        var bestD = reach; var res: Triple<Float, Float, Boolean>? = null
                        for (ed in edges) {
                            for (tg in canvasT) { val g = tg - (ed + d); if (abs(g) < bestD) { bestD = abs(g); res = Triple(g, tg, true) } }
                            for (tg in objT) { val g = tg - (ed + d); if (abs(g) < bestD) { bestD = abs(g); res = Triple(g, tg, false) } }
                        }
                        return res
                    }
                    best(listOf(x0, (x0 + x1) / 2, x1), dx, canvasX, ox)?.let { (g, line, cvs) -> dx += g; snapX = line; snapXCanvas = cvs }
                    best(listOf(y0, (y0 + y1) / 2, y1), dy, canvasY, oy)?.let { (g, line, cvs) -> dy += g; snapY = line; snapYCanvas = cvs }
                }
                t.apply(Matrix().apply { setTranslate(dx, dy) })
            }
            Drag.SCALE -> {
                val d0 = max(1f, hypot(tDown.x - tCenter.x, tDown.y - tCenter.y))
                val s = (hypot(p.x - tCenter.x, p.y - tCenter.y) / d0).coerceIn(0.02f, 50f)
                t.apply(Matrix().apply { setScale(s, s, tCenter.x, tCenter.y) })
            }
            Drag.SIDE -> {
                // in the box's own frame: pull this side, the opposite one stays
                val inv = Matrix(); t.box.invert(inv)
                val a = floatArrayOf(tDown.x, tDown.y, p.x, p.y); inv.mapPoints(a)
                val bw = t.bw; val bh = t.bh
                val m = Matrix()
                when (tSide) {
                    0 -> { val s = ((bh - a[3]) / max(1f, bh - a[1])).coerceIn(0.02f, 50f); m.setScale(1f, s, 0f, bh) }     // top
                    1 -> { val s = (a[2] / max(1f, a[0])).coerceIn(0.02f, 50f); m.setScale(s, 1f, 0f, 0f) }                  // right
                    2 -> { val s = (a[3] / max(1f, a[1])).coerceIn(0.02f, 50f); m.setScale(1f, s, 0f, 0f) }                  // bottom
                    else -> { val s = ((bw - a[2]) / max(1f, bw - a[0])).coerceIn(0.02f, 50f); m.setScale(s, 1f, bw, 0f) }  // left
                }
                t.apply(t.inBox(m))
            }
            Drag.ROTATE -> {
                val a0 = Math.toDegrees(atan2((tDown.y - tCenter.y).toDouble(), (tDown.x - tCenter.x).toDouble()))
                val a1 = Math.toDegrees(atan2((p.y - tCenter.y).toDouble(), (p.x - tCenter.x).toDouble()))
                var a = (a1 - a0).toFloat()
                // snap to 45° steps when close
                val total = a
                val snapped = Math.round(total / 45f) * 45f
                if (abs(total - snapped) < 3f) a = snapped
                t.apply(Matrix().apply { setRotate(a, tCenter.x, tCenter.y) })
            }
            Drag.ALIGN -> {
                val a0 = Math.toDegrees(atan2((tDown.y - tCenter.y).toDouble(), (tDown.x - tCenter.x).toDouble()))
                val a1 = Math.toDegrees(atan2((p.y - tCenter.y).toDouble(), (p.x - tCenter.x).toDouble()))
                var ang = tAlignStart + (a1 - a0).toFloat()
                val snapped = Math.round(ang / 15f) * 15f
                if (abs(ang - snapped) < 2.5f) ang = snapped
                t.alignBox(ang)
            }
        }
        invalidate()
    }

    /** the finger left a handle: a tap without a drag asks for an exact angle or size */
    private fun endTransformDrag() {
        val t = transform
        snapX = null; snapY = null
        if (t != null && !tMoved) when (tDrag) {
            Drag.ROTATE -> onTransformExact?.invoke(true)
            Drag.SCALE, Drag.SIDE -> onTransformExact?.invoke(false)
            else -> {}
        }
        invalidate()
    }

    /** "free", "distort" or "warp" while transforming */
    val transformShape: String get() = transform?.shape?.name?.lowercase() ?: "free"

    fun transformAngle(): Float = transform?.boxAngle() ?: 0f
    fun transformSize(): Pair<Float, Float> = transform?.boxSize() ?: (0f to 0f)
    fun setTransformAngle(deg: Float) { transform?.setAngle(deg); invalidate() }
    fun setTransformSize(w: Float, h: Float) { transform?.setSize(w, h); invalidate() }

    private fun startTransformPinch(e: MotionEvent) {
        val t = transform ?: return
        if (e.pointerCount < 2) return
        // Distort and Warp are shaped by their handles; two fingers do nothing there
        if (t.shape != TransformSession.Shape.FREE) { mode = Mode.IGNORE; return }
        mode = Mode.TRANSFORM_PINCH
        tStart.set(t.matrix)
        tBoxStart.set(t.box)
        tP0 = toDoc(e.getX(0), e.getY(0))
        tP1 = toDoc(e.getX(1), e.getY(1))
    }

    private fun updateTransformPinch(e: MotionEvent) {
        val t = transform ?: return
        if (e.pointerCount < 2) return
        val q0 = toDoc(e.getX(0), e.getY(0))
        val q1 = toDoc(e.getX(1), e.getY(1))
        val d0 = max(1f, hypot(tP1.x - tP0.x, tP1.y - tP0.y))
        val s = (hypot(q1.x - q0.x, q1.y - q0.y) / d0).coerceIn(0.02f, 50f)
        val a0 = atan2(tP1.y - tP0.y, tP1.x - tP0.x)
        val a1 = atan2(q1.y - q0.y, q1.x - q0.x)
        t.matrix.set(tStart)
        t.box.set(tBoxStart)
        t.apply(Matrix().apply {
            setTranslate(-(tP0.x + tP1.x) / 2, -(tP0.y + tP1.y) / 2)
            postScale(s, s)
            postRotate(Math.toDegrees((a1 - a0).toDouble()).toFloat())
            postTranslate((q0.x + q1.x) / 2, (q0.y + q1.y) / 2)
        })
        invalidate()
    }

    // =========================================================
    // Adjustments (live preview on the active layer)
    // =========================================================

    /** Returns the untouched pixels to compute from, or null if the layer can't be adjusted. */
    fun beginAdjust(): Bitmap? {
        if (adjustOrig != null) return adjustOrig
        if (transform != null) return null
        val layer = doc.active
        if (!layer.visible) { listener?.onMessage("This layer is hidden"); return null }
        if (layer.vector != null) { listener?.onMessage("Adjustments work on drawing layers (or rasterize this vector layer)"); return null }
        if (layer.scene != null) { listener?.onMessage("Adjustments work on drawing layers (rasterize this 3D layer first)"); return null }
        val content = Selections.contentBounds(layer.bitmap)
        if (content == null) { listener?.onMessage("This layer is empty"); return null }
        val orig = layer.bitmap
        val work = try { orig.copy(Bitmap.Config.ARGB_8888, true) } catch (e: OutOfMemoryError) { null }
        if (work == null) { listener?.onMessage("Not enough memory for this right now"); return null }
        layer.bitmap = work
        adjustLayer = layer
        adjustOrig = orig
        adjustRegion = content
        interaction = Interaction.LOCKED
        return orig
    }

    /** Area worth computing: layer content (∩ selection), grown by [grow] px for blurs. */
    fun adjustArea(grow: Int): Rect {
        val r = Rect(adjustRegion ?: Rect(0, 0, doc.width, doc.height))
        selection?.let { if (!r.intersect(it.bounds)) r.set(it.bounds) }
        r.inset(-grow, -grow)
        r.intersect(0, 0, doc.width, doc.height)
        return r
    }

    private val adjustSrcPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.SRC }
    private val adjustMatrixPaint = Paint().apply { blendMode = BlendMode.SRC }

    /** what was shown last was a smaller, quicker preview: Apply must compute it at full size first */
    var adjustShownPreview = false
        private set

    /** puts back the untouched pixels where the last preview was, when that was outside [r] */
    private fun restoreAdjustOutside(c: Canvas, orig: Bitmap, r: Rect) {
        val last = lastAdjustRect ?: return
        if (r.contains(last)) return
        c.drawBitmap(orig, last, last, adjustSrcPaint)
    }

    /**
     * A color-matrix adjustment, shown straight away: one pass over the layer, on this thread,
     * with no copies (called at most once a frame).
     */
    /**
     * [fast]: while the slider moves, the change is only a filter on the screen (no pixels are
     * worked out, so it follows the finger); the pixels change when it is applied.
     */
    fun showAdjustedMatrix(r: Rect, m: android.graphics.ColorMatrix, fast: Boolean = false) {
        val layer = adjustLayer ?: return
        val orig = adjustOrig ?: return
        if (fast && selection == null) {
            if (adjustPixelsChanged) { Canvas(layer.bitmap).drawBitmap(orig, 0f, 0f, adjustSrcPaint); adjustPixelsChanged = false }
            layer.previewFilter = android.graphics.ColorMatrixColorFilter(m)
            lastAdjustRect = Rect(r)
            adjustShownPreview = false
            invalidate()
            return
        }
        layer.previewFilter = null
        adjustPixelsChanged = true
        val c = Canvas(layer.bitmap)
        restoreAdjustOutside(c, orig, r)
        adjustMatrixPaint.colorFilter = android.graphics.ColorMatrixColorFilter(m)
        c.drawBitmap(orig, r, r, adjustMatrixPaint)
        selection?.let {
            val beforeRegion = Bitmap.createBitmap(orig, r.left, r.top, r.width(), r.height())
            Selections.restrict(layer.bitmap, beforeRegion, r, it)
            beforeRegion.recycle()
        }
        lastAdjustRect = Rect(r)
        adjustShownPreview = false
        invalidate()
    }

    /** Shows [result] (pixels of rectangle [r], or a smaller preview of them) on the layer. */
    fun showAdjusted(r: Rect, result: Bitmap) {
        val layer = adjustLayer ?: return
        val orig = adjustOrig ?: return
        layer.previewFilter = null
        adjustPixelsChanged = true
        val c = Canvas(layer.bitmap)
        restoreAdjustOutside(c, orig, r)
        adjustShownPreview = result.width != r.width() || result.height != r.height()
        if (adjustShownPreview) c.drawBitmap(result, null, RectF(r), adjustSrcPaint)
        else c.drawBitmap(result, r.left.toFloat(), r.top.toFloat(), adjustSrcPaint)
        selection?.let {
            val beforeRegion = Bitmap.createBitmap(orig, r.left, r.top, r.width(), r.height())
            Selections.restrict(layer.bitmap, beforeRegion, r, it)
        }
        lastAdjustRect = Rect(r)
        invalidate()
    }

    private var lastAdjustRect: Rect? = null

    private var adjustPixelsChanged = false

    fun finishAdjust(apply: Boolean) {
        val layer = adjustLayer ?: return
        val orig = adjustOrig ?: return
        layer.previewFilter = null
        adjustPixelsChanged = false
        val r = lastAdjustRect
        if (apply && r != null && !r.isEmpty) {
            val before = Bitmap.createBitmap(orig, r.left, r.top, r.width(), r.height())
            adjustOrig = null
            pushPixelEdit(layer, r, before)
            orig.recycle()
        } else {
            layer.bitmap.recycle()
            layer.bitmap = orig
        }
        adjustLayer = null
        adjustOrig = null
        adjustRegion = null
        lastAdjustRect = null
        interaction = Interaction.PAINT
        invalidate()
    }
}
