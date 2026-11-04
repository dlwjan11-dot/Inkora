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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import android.graphics.RectF

enum class Tool { BRUSH, SMUDGE, ERASER }

/** What touches on the canvas do right now. */
enum class Interaction { PAINT, SELECT, TRANSFORM, LOCKED, VECTOR }

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
    }

    var listener: Listener? = null

    lateinit var doc: Document
        private set
    var history = History()
        private set

    private var strokeBitmap: Bitmap? = null
    private val engine = StrokeEngine()

    // ---- tool state (set by MainActivity) ----
    var tool = Tool.BRUSH
    var paintBrush = Brush(Brushes.presets[1])
    var eraserBrush = Brush(Brushes.presets[9]).apply { size = 60f }
    var color = 0xFF1E1E24.toInt()
    var symmetry = Symmetry.OFF
        set(v) { field = v; invalidate() }
    var quickShapeEnabled = true
    var fingerDrawing = true
    /** when the user has not chosen, finger drawing turns off once a stylus is seen */
    var fingerDrawingAuto = true
    var fillThreshold = 0.22f
    var eyedropperArmed = false

    var smudgeBrush = Brush(Brushes.presets[0]).apply { size = 60f; opacity = 0.75f }

    val currentBrush get() = when (tool) {
        Tool.ERASER -> eraserBrush
        Tool.SMUDGE -> smudgeBrush
        Tool.BRUSH -> paintBrush
    }

    var interaction = Interaction.PAINT
        set(v) { field = v; invalidate() }
    var selectShape = SelectShape.FREEHAND
    var selection: Selection? = null
        set(v) {
            field = v
            invalidate()
            listener?.onSelectionChanged()
        }
    var transform: TransformSession? = null
        private set

    // ---- view transform: document -> screen ----
    private val viewMatrix = Matrix()
    private val inverse = Matrix()
    private var flipped = false

    // ---- paints ----
    private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plainBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** texture paint (DST_IN) for the current stroke, null = no grain */
    private var strokeGrain: Paint? = null

    /** 1 = linear; <1 = soft pen (less force needed); >1 = firm */
    var pressureGamma = 1f
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
        color = 0xCC4C8DFF.toInt()
        style = Paint.Style.STROKE
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- input state ----
    private enum class Mode { NONE, DRAW, GESTURE, PICK, FINGER_WAIT, IGNORE, VP_DRAG, SELECT_DRAW, TRANSFORM_DRAG, TRANSFORM_PINCH, VECTOR_DRAG }

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
    private enum class Drag { MOVE, SCALE, ROTATE }
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
        if (strokeActive && !pressMoved && drawIsStylus && shape == null) {
            cancelStroke() // removes the dot that was drawn on touch-down
            listener?.onPenLongPress(pressX, pressY)
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

    // eyedropper
    private var pickX = 0f
    private var pickY = 0f
    private var pickColor = 0
    private val fingerHoldRunnable = Runnable {
        if (mode == Mode.FINGER_WAIT) {
            mode = Mode.PICK
            pickAt(pickX, pickY)
        }
    }

    // hover cursor (S Pen)
    private var hovering = false
    private var hoverX = 0f
    private var hoverY = 0f

    private val tmpPts = FloatArray(2)

    // =========================================================
    // document
    // =========================================================

    fun setDocument(d: Document) {
        doc = d
        history = History()
        strokeBitmap?.recycle()
        strokeBitmap = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
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

    private fun busy(): Boolean {
        if (transform != null || adjustOrig != null) {
            listener?.onMessage("Finish or cancel the current edit first")
            return true
        }
        return false
    }

    fun undo() {
        if (busy()) return
        if (strokeActive) cancelStroke()
        if (history.undo(doc)) {
            vectorEditor.reset()
            listener?.onMessage("Undo")
            listener?.onHistoryChanged()
            invalidate()
        }
    }

    fun redo() {
        if (busy()) return
        if (strokeActive) cancelStroke()
        if (history.redo(doc)) {
            vectorEditor.reset()
            listener?.onMessage("Redo")
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
        canvas.drawColor(0xFF2B2B30.toInt())
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
        if (strokeActive && !smudging && sb != null) {
            overlayPaint.alpha = (engine.brush.opacity * 255).toInt().coerceIn(0, 255)
            overlayPaint.blendMode = strokeBlend()
            overlayPaint.isFilterBitmap = filter
            val grain = strokeGrain
            val sel = selection
            doc.draw(canvas, filter, { c ->
                if (grain == null && sel == null) c.drawBitmap(sb, 0f, 0f, overlayPaint)
                else {
                    // stroke × paper texture × selection, then blended into the layer
                    val s = c.saveLayer(doc.bounds, overlayPaint)
                    c.drawBitmap(sb, 0f, 0f, plainBitmapPaint)
                    if (grain != null) c.drawRect(doc.bounds, grain)
                    if (sel != null) c.drawBitmap(sel.mask, 0f, 0f, Selections.dstIn)
                    c.restoreToCount(s)
                }
            })
        } else if (t != null && t.layer === doc.active) {
            doc.draw(canvas, filter, { c -> c.drawBitmap(t.floating, t.matrix, transformPaint) })
        } else {
            doc.draw(canvas, filter)
        }

        drawGuide(canvas, z)
        if (symmetry != Symmetry.OFF) drawSymmetryGuides(canvas, z)
        drawSelection(canvas, z)
        canvas.restore()

        if (t != null) drawTransformBox(canvas, t)
        if (interaction == Interaction.VECTOR && doc.active.vector != null) vectorEditor.drawOverlay(canvas)
        if (editingGuide) drawVpHandles(canvas)

        if (hovering && !strokeActive) {
            val r = max(2f, currentBrush.size * z / 2f)
            cursorPaint.strokeWidth = 3f
            cursorPaint.color = 0x66000000
            canvas.drawCircle(hoverX, hoverY, r, cursorPaint)
            cursorPaint.strokeWidth = 1.2f
            cursorPaint.color = 0xDDFFFFFF.toInt()
            canvas.drawCircle(hoverX, hoverY, r, cursorPaint)
        }

        if (mode == Mode.PICK) drawLoupe(canvas)
    }

    private fun drawSelection(canvas: Canvas, z: Float) {
        val path: android.graphics.Path? = when {
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
    private fun drawTransformBox(canvas: Canvas, t: TransformSession) {
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
        handlePaint.style = Paint.Style.FILL
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
            Symmetry.RADIAL -> {
                val r = hypot(cx, cy)
                for (k in 0 until engine.radialCount) {
                    val a = Math.PI * 2 * k / engine.radialCount
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
        else -> BlendMode.SRC_OVER
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
                hovering = true; hoverX = e.x; hoverY = e.y
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
        penConsumed = mode == Mode.DRAW
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
                removeCallbacks(fingerHoldRunnable)
                mode = Mode.NONE
                invalidate()
            }
        }
        return true
    }

    private fun onFirstDown(e: MotionEvent) {
        hovering = false
        downTime = e.eventTime
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
        if (stylus || fingerDrawing) {
            startStroke(e, 0, stylus)
        } else {
            mode = Mode.FINGER_WAIT
            pickX = e.x; pickY = e.y
            gDown.clear()
            gDown[e.getPointerId(0)] = PointF(e.x, e.y)
            postDelayed(fingerHoldRunnable, 450)
        }
    }

    private fun onPointerDown(e: MotionEvent) {
        val idx = e.actionIndex
        val newIsStylus = isStylusTool(e.getToolType(idx))
        when (mode) {
            Mode.DRAW -> {
                if (drawIsStylus) return // palm/fingers while drawing with pen: ignore
                if (e.eventTime - downTime < 300 || strokePoints.size < 6) {
                    cancelStroke()
                    startGesture(e, -1)
                }
            }
            Mode.FINGER_WAIT, Mode.NONE, Mode.IGNORE -> {
                removeCallbacks(fingerHoldRunnable)
                if (newIsStylus) {
                    startStroke(e, idx, true)
                } else startGesture(e, -1)
            }
            Mode.GESTURE -> {
                if (newIsStylus) {
                    startStroke(e, idx, true)
                } else startGesture(e, -1, keepTapInfo = true)
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
            Mode.PICK, Mode.VP_DRAG, Mode.TRANSFORM_PINCH -> {}
        }
    }

    private fun onMove(e: MotionEvent) {
        when (mode) {
            Mode.DRAW -> {
                val idx = e.findPointerIndex(drawPointerId)
                if (idx < 0) return
                for (h in 0 until e.historySize) {
                    addPoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h),
                        pressureOf(e, idx, h), e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h))
                }
                addPoint(e.getX(idx), e.getY(idx), pressureOf(e, idx, -1), e.getAxisValue(MotionEvent.AXIS_TILT, idx))
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
                    if (quickShapeEnabled && !smudging) postDelayed(holdRunnable, 500)
                }
                invalidate()
            }
            Mode.GESTURE -> updateGesture(e)
            Mode.FINGER_WAIT -> {
                if (hypot(e.x - pickX, e.y - pickY) > 10f * density) {
                    removeCallbacks(fingerHoldRunnable)
                    mode = Mode.IGNORE
                }
            }
            Mode.PICK -> pickAt(e.x, e.y)
            Mode.VP_DRAG -> {
                val p = toDoc(e.x, e.y)
                doc.guide.setVp(dragVp, p.x / doc.width, p.y / doc.height)
                invalidate()
            }
            Mode.SELECT_DRAW -> moveSelect(e)
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
            else -> {}
        }
    }

    private fun onLastUp(e: MotionEvent) {
        removeCallbacks(fingerHoldRunnable)
        when (mode) {
            Mode.DRAW -> finishStroke()
            Mode.GESTURE -> {
                trackMove(e)
                val quick = e.eventTime - gStartTime < 320 && gMoved < 24f * density
                if (quick && gMaxPointers >= 2) {
                    viewMatrix.set(tapStartMatrix) // a tap must not nudge the canvas
                    if (gMaxPointers == 2) undo() else redo()
                }
            }
            Mode.PICK -> {
                listener?.onColorPicked(pickColor)
            }
            Mode.VP_DRAG -> doc.revision++
            Mode.SELECT_DRAW -> finishSelect()
            Mode.VECTOR_DRAG -> vectorEditor.onUp(e.x, e.y)
            else -> {}
        }
        mode = Mode.NONE
        invalidate()
    }

    private fun pressureOf(e: MotionEvent, idx: Int, h: Int): Float {
        if (!drawIsStylus) return 1f
        val p = if (h < 0) e.getPressure(idx) else e.getHistoricalPressure(idx, h)
        val c = p.coerceIn(0f, 1f)
        return if (pressureGamma == 1f) c else c.pow(pressureGamma)
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
        if (layer.vector != null) {
            listener?.onMessage("Vector layer: use the vector tools, or pick a drawing layer")
            mode = Mode.IGNORE
            return
        }
        val sb = strokeBitmap ?: return
        strokeErasing = tool == Tool.ERASER ||
            e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER ||
            (stylus && isPenButton(e.buttonState))
        sb.eraseColor(0)
        smudging = !strokeErasing && tool == Tool.SMUDGE
        engine.brush = when {
            strokeErasing -> eraserBrush
            smudging -> smudgeBrush
            else -> paintBrush
        }
        engine.color = if (strokeErasing) Color.BLACK else color
        strokeGrain = if (smudging) null else grainPaintFor(engine.brush)
        if (smudging) {
            // smudge works directly on the layer; keep a copy for undo
            smudgeBefore = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val sm = Smudger(layer.bitmap, smudgeBrush)
            smudger = sm
            engine.dabHandler = { x, y, d, a, ang -> sm.dab(x, y, d, a, ang) }
        } else engine.dabHandler = null
        engine.symmetry = symmetry
        engine.centerX = doc.width / 2f
        engine.centerY = doc.height / 2f
        strokePoints.clear()
        shape = null
        val p = toDoc(e.getX(idx), e.getY(idx))
        val ip = InputPoint(p.x, p.y, pressureOf(e, idx, -1), e.getAxisValue(MotionEvent.AXIS_TILT, idx))
        strokePoints.add(ip)
        shapeSeed = (System.nanoTime() and 0xFFFFFF).toInt()
        strokeActive = true
        listener?.onDrawingChanged(true)
        holdAnchorX = e.getX(idx); holdAnchorY = e.getY(idx)
        removeCallbacks(holdRunnable)
        constraint = null
        if (assistedActive()) {
            // wait until the pen moves a little, then lock to the closest guide direction
            snapPending = true
            pendingPts.clear()
            pendingPts.add(ip)
        } else {
            snapPending = false
            engine.begin(sb, ip, smooth = true, seed = shapeSeed)
        }
        // QuickShape works with or without the grid's Assisted Drawing
        if (quickShapeEnabled && !smudging) postDelayed(holdRunnable, 500)
        pressX = e.getX(idx); pressY = e.getY(idx)
        pressMoved = false
        removeCallbacks(longPressRunnable)
        if (stylus) postDelayed(longPressRunnable, 420)
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

    private fun addPoint(sx: Float, sy: Float, pressure: Float, tilt: Float) {
        if (!strokeActive || shape != null) return
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
                engine.begin(sb, snapped(first), smooth = constraint !is Constraint.Circle, seed = shapeSeed)
                for (i in 1 until pendingPts.size) engine.move(snapped(pendingPts[i]))
            }
            return
        }
        engine.move(snapped(ip))
    }

    private fun flushPending() {
        if (!snapPending) return
        snapPending = false
        val sb = strokeBitmap ?: return
        if (pendingPts.isEmpty()) return
        engine.begin(sb, pendingPts[0], smooth = true, seed = shapeSeed)
        for (i in 1 until pendingPts.size) engine.move(pendingPts[i])
    }

    private fun finishStroke() {
        removeCallbacks(holdRunnable)
        removeCallbacks(longPressRunnable)
        if (!strokeActive) return
        flushPending()
        if (shape == null) engine.end()
        if (smudging) endSmudge(true) else commitStroke()
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
        engine.end()
        if (smudging) endSmudge(false)
        strokeBitmap?.eraseColor(0)
        mode = Mode.NONE
        invalidate()
    }

    private fun commitStroke() {
        val sb = strokeBitmap ?: return
        val r = Rect()
        engine.dirty.roundOut(r)
        if (!r.intersect(0, 0, doc.width, doc.height) || r.isEmpty) return
        val layer = doc.active
        val before = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        commitPaint.alpha = (engine.brush.opacity * 255).toInt().coerceIn(0, 255)
        commitPaint.blendMode = strokeBlend()
        val lc = Canvas(layer.bitmap)
        val grain = strokeGrain
        val sel = selection
        if (grain == null && sel == null) lc.drawBitmap(sb, r, r, commitPaint)
        else {
            val rf = RectF(r)
            val s = lc.saveLayer(rf, commitPaint)
            lc.drawBitmap(sb, r, r, null)
            if (grain != null) lc.drawRect(rf, grain)
            if (sel != null) lc.drawBitmap(sel.mask, r, r, Selections.dstIn)
            lc.restoreToCount(s)
        }
        val after = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        history.push(PixelEdit(layer, Rect(r), before, after), doc)
        sb.eraseColor(0)
        listener?.onHistoryChanged()
    }

    private fun currentBrushFor(erasing: Boolean) = if (erasing) eraserBrush else paintBrush

    // ---------------- QuickShape ----------------

    private fun onHold() {
        if (!strokeActive || shape != null || !quickShapeEnabled || smudging) return
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
        sb.eraseColor(0)
        engine.renderPoints(sb, QuickShape.toPoints(s), shapePressure, shapeSeed)
        invalidate()
    }

    // ---------------- gestures ----------------

    private fun startGesture(e: MotionEvent, excludeIdx: Int, keepTapInfo: Boolean = false) {
        val pts = ArrayList<PointF>()
        for (i in 0 until e.pointerCount) if (i != excludeIdx) pts.add(PointF(e.getX(i), e.getY(i)))
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
        if (!gRotating) rot = 0f

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
        val ref = doc.flatten(withBackground = true)
        val result = FloodFill.compute(ref, x, y, fillColor, fillThreshold)
        ref.recycle()
        if (result == null) return false
        val r = result.rect
        selection?.let { Canvas(result.bitmap).drawBitmap(it.mask, r, Rect(0, 0, r.width(), r.height()), Selections.dstIn) }
        val before = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        val paint = Paint().apply { blendMode = if (layer.alphaLock) BlendMode.SRC_ATOP else BlendMode.SRC_OVER }
        Canvas(layer.bitmap).drawBitmap(result.bitmap, r.left.toFloat(), r.top.toFloat(), paint)
        result.bitmap.recycle()
        pushPixelEdit(layer, r, before)
        listener?.onMessage("ColorDrop")
        return true
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
        val tiny = hypot(selX1 - selX0, selY1 - selY0) * zoom() < 8f * density && selPts.size < 6
        if (tiny) {
            // a tap clears the selection
            selection = null
            return
        }
        val path = Selections.shapePath(selectShape, selPts, selX0, selY0, selX1, selY1)
        selection = Selections.fromPath(doc.width, doc.height, path)
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

    /** Copies the selected pixels of the active layer onto a new layer. */
    fun copySelectionToNewLayer(): Boolean {
        val s = selection ?: return false
        if (doc.layers.size >= doc.maxLayers()) { listener?.onMessage("Layer limit reached"); return false }
        val src = doc.active
        val layer = doc.createLayer()
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

    fun clearSelection() {
        val s = selection ?: return
        val layer = doc.active
        val r = s.bounds
        val before = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
        Canvas(layer.bitmap).drawBitmap(s.mask, 0f, 0f, Selections.dstOut)
        pushPixelEdit(layer, r, before)
    }

    fun fillSelection(fill: Int) {
        val s = selection ?: return
        val layer = doc.active
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
        val layer = doc.active
        if (!layer.visible) { listener?.onMessage("This layer is hidden"); return false }
        if (layer.vector != null) { listener?.onMessage("On a vector layer use the vector Select tool"); return false }
        val sel = selection
        val region = (if (sel != null) Rect(sel.bounds) else Selections.contentBounds(layer.bitmap))
        if (region == null || !region.intersect(0, 0, doc.width, doc.height) || region.isEmpty) {
            listener?.onMessage("This layer is empty")
            return false
        }
        val before = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val floating = Bitmap.createBitmap(layer.bitmap, region.left, region.top, region.width(), region.height())
            .copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(layer.bitmap)
        if (sel != null) {
            Canvas(floating).drawBitmap(sel.mask, region, Rect(0, 0, region.width(), region.height()), Selections.dstIn)
            c.drawBitmap(sel.mask, 0f, 0f, Selections.dstOut)
        } else {
            c.drawRect(RectF(region), Paint().apply { blendMode = BlendMode.CLEAR })
        }
        transform = TransformSession(layer, floating, region, before, sel)
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
        }
        invalidate()
    }

    fun finishTransform(apply: Boolean) {
        val t = transform ?: return
        val c = Canvas(t.layer.bitmap)
        if (apply) {
            c.drawBitmap(t.floating, t.matrix, transformPaint)
            val r = t.resultBounds(doc.width, doc.height)
            if (!r.isEmpty) {
                val beforeRegion = Bitmap.createBitmap(t.before, r.left, r.top, r.width(), r.height())
                transform = null
                pushPixelEdit(t.layer, r, beforeRegion)
            }
            selection = t.selection?.let { Selections.transformed(it, t.movement(), doc.width, doc.height) }
        } else {
            c.drawBitmap(t.before, 0f, 0f, Paint().apply { blendMode = BlendMode.SRC })
        }
        transform = null
        t.floating.recycle()
        t.before.recycle()
        interaction = Interaction.PAINT
        invalidate()
    }

    private fun startTransformDrag(e: MotionEvent) {
        val t = transform ?: run { mode = Mode.IGNORE; return }
        mode = Mode.TRANSFORM_DRAG
        downTime = e.eventTime
        tStart.set(t.matrix)
        tDown = toDoc(e.x, e.y)
        val cc = t.center()
        tCenter = PointF(cc[0], cc[1])
        // which handle was touched? (screen space)
        val c = t.corners()
        viewMatrix.mapPoints(c)
        val rh = rotateHandle(c)
        val reach = 28f * density
        tDrag = when {
            hypot(e.x - rh.x, e.y - rh.y) < reach -> Drag.ROTATE
            (0 until 4).any { hypot(e.x - c[it * 2], e.y - c[it * 2 + 1]) < reach } -> Drag.SCALE
            else -> Drag.MOVE
        }
    }

    private fun updateTransformDrag(e: MotionEvent) {
        val t = transform ?: return
        val p = toDoc(e.x, e.y)
        t.matrix.set(tStart)
        when (tDrag) {
            Drag.MOVE -> t.matrix.postTranslate(p.x - tDown.x, p.y - tDown.y)
            Drag.SCALE -> {
                val d0 = max(1f, hypot(tDown.x - tCenter.x, tDown.y - tCenter.y))
                val s = (hypot(p.x - tCenter.x, p.y - tCenter.y) / d0).coerceIn(0.02f, 50f)
                t.matrix.postScale(s, s, tCenter.x, tCenter.y)
            }
            Drag.ROTATE -> {
                val a0 = Math.toDegrees(atan2((tDown.y - tCenter.y).toDouble(), (tDown.x - tCenter.x).toDouble()))
                val a1 = Math.toDegrees(atan2((p.y - tCenter.y).toDouble(), (p.x - tCenter.x).toDouble()))
                var a = (a1 - a0).toFloat()
                // snap to 45° steps when close
                val total = a
                val snapped = Math.round(total / 45f) * 45f
                if (abs(total - snapped) < 3f) a = snapped
                t.matrix.postRotate(a, tCenter.x, tCenter.y)
            }
        }
        invalidate()
    }

    private fun startTransformPinch(e: MotionEvent) {
        val t = transform ?: return
        if (e.pointerCount < 2) return
        mode = Mode.TRANSFORM_PINCH
        tStart.set(t.matrix)
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
        t.matrix.postTranslate(-(tP0.x + tP1.x) / 2, -(tP0.y + tP1.y) / 2)
        t.matrix.postScale(s, s)
        t.matrix.postRotate(Math.toDegrees((a1 - a0).toDouble()).toFloat())
        t.matrix.postTranslate((q0.x + q1.x) / 2, (q0.y + q1.y) / 2)
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
        val content = Selections.contentBounds(layer.bitmap)
        if (content == null) { listener?.onMessage("This layer is empty"); return null }
        val orig = layer.bitmap
        layer.bitmap = orig.copy(Bitmap.Config.ARGB_8888, true)
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

    /** Shows [result] (pixels of rectangle [r]) on the layer. */
    fun showAdjusted(r: Rect, result: Bitmap) {
        val layer = adjustLayer ?: return
        val orig = adjustOrig ?: return
        val src = Paint().apply { blendMode = BlendMode.SRC }
        val c = Canvas(layer.bitmap)
        c.drawBitmap(orig, 0f, 0f, src)
        c.drawBitmap(result, r.left.toFloat(), r.top.toFloat(), src)
        selection?.let {
            val beforeRegion = Bitmap.createBitmap(orig, r.left, r.top, r.width(), r.height())
            Selections.restrict(layer.bitmap, beforeRegion, r, it)
        }
        lastAdjustRect = Rect(r)
        invalidate()
    }

    private var lastAdjustRect: Rect? = null

    fun finishAdjust(apply: Boolean) {
        val layer = adjustLayer ?: return
        val orig = adjustOrig ?: return
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
