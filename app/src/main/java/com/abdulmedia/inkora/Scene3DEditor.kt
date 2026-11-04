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
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

interface Scene3DHost {
    /** the artwork under the 3D layer, small, for the preview's background */
    fun backdrop(maxSide: Int): Bitmap?
    val docWidth: Int
    val docHeight: Int
    val brushColor: Int
    /** asks for a .obj / .stl file; the answer comes back through [Scene3DEditor.addModel] */
    fun pickModelFile()
    /** [scene] = null: cancelled */
    fun onScene3DClosed(scene: Scene3D?, shadow: Boolean)
}

/**
 * Full-screen page to set up figures, poses and 3D objects. The preview has the canvas's shape and
 * shows the artwork behind; "Place on canvas" draws the scene into a 3D layer.
 *
 * One finger turns the camera around, two fingers zoom and move it, a tap picks an object
 * (on a figure: the body part to bend).
 */
@SuppressLint("ViewConstructor")
class Scene3DEditor(
    context: Context,
    start: Scene3D,
    private var shadow: Boolean,
    private val host: Scene3DHost
) : FrameLayout(context) {

    private val scene = start.copy()
    private var sel = -1
    private var joint = -1
    var closing = false
        private set

    private val preview = Preview(context)
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp, 12.dp, 16.dp, 24.dp) }
    private val hint = Ui.text(context, "", 12.5f, Ui.TEXT_DIM)

    init {
        setBackgroundColor(0xFF19191D.toInt())
        isClickable = true
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp, 10.dp, 12.dp, 6.dp)
        }
        top.addView(Ui.text(context, "3D & Poses", 19f, bold = true), LinearLayout.LayoutParams(-2, -2))
        top.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(Ui.pill(context, "Cancel") { close(false) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8.dp })
        top.addView(Ui.pill(context, "Place on canvas", primary = true) { close(true) })
        left.addView(top, LinearLayout.LayoutParams(-1, -2))
        left.addView(hint.apply { setPadding(20.dp, 0, 20.dp, 4.dp) })
        left.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        row.addView(left, LinearLayout.LayoutParams(0, -1, 1f))

        val scroll = ScrollView(context).apply { setBackgroundColor(0xFF1E1E23.toInt()) }
        scroll.addView(panel)
        row.addView(scroll, LinearLayout.LayoutParams(min(380.dp, (resources.displayMetrics.widthPixels * 0.42f).toInt()), -1))
        addView(row, LayoutParams(-1, -1))

        if (scene.objects.isEmpty()) add(Shape3D.FIGURE) else rebuild()
    }

    fun finish() = close(true)

    private fun close(keep: Boolean) {
        if (closing) return
        closing = true
        preview.release()
        host.onScene3DClosed(if (keep) scene else null, shadow)
    }

    // =========================================================
    // objects
    // =========================================================

    private fun add(kind: Shape3D, mesh: Mesh? = null, name: String? = null) {
        val o = Obj3D(kind)
        if (mesh != null) o.mesh = mesh
        if (name != null) o.name = name
        // next to what is already there, in front of the camera
        val c = scene.camera
        o.x = c.tx + (if (scene.objects.isEmpty()) 0f else ((scene.objects.size + 1) / 2) * 0.9f * (if (scene.objects.size % 2 == 0) -1 else 1))
        o.z = c.tz
        when (kind) {
            Shape3D.PLANE -> { o.sx = 4f; o.sz = 4f; o.rx = 90f; o.z = c.tz - 1.2f; o.x = c.tx; o.y = 0f; o.sy = 1f }
            Shape3D.FIGURE -> {}
            Shape3D.MODEL -> {}
            else -> o.size = 0.6f
        }
        if (kind == Shape3D.PLANE) o.y = 2f
        scene.objects.add(o)
        sel = scene.objects.size - 1
        joint = -1
        rebuild()
        preview.requestRender()
    }

    /** a model file was read (called by the host) */
    fun addModel(mesh: Mesh, name: String) = add(Shape3D.MODEL, mesh, name)

    private val current get() = scene.objects.getOrNull(sel)

    private fun select(obj: Int, part: Int) {
        val o = scene.objects.getOrNull(obj)
        val newJoint = if (o?.kind == Shape3D.FIGURE) part else -1
        if (obj == sel && newJoint == joint) return
        sel = obj; joint = newJoint
        rebuild()
        preview.requestRender()
    }

    // =========================================================
    // side panel
    // =========================================================

    private fun rebuild() {
        panel.removeAllViews()
        hint.text = L10n.tr(when {
            current?.kind == Shape3D.FIGURE && joint < 0 -> "Tap a body part to bend it · drag to turn the view · two fingers zoom and move"
            current != null -> "Drag to turn the view · two fingers zoom and move · tap empty space to deselect"
            else -> "Tap an object to change it · drag to turn the view · two fingers zoom and move"
        })

        section("Add")
        val g = GridLayout(context).apply { columnCount = 3 }
        for (k in Shape3D.values()) {
            if (k == Shape3D.MODEL) continue
            g.addView(Ui.button(context, k.label) { add(k) }.apply { textSize = 12.5f }, cell())
        }
        g.addView(Ui.button(context, "Import model…") { host.pickModelFile() }.apply { textSize = 12.5f }, cell())
        panel.addView(g, LinearLayout.LayoutParams(-1, -2))
        panel.addView(Ui.text(context, "Models: .obj or .stl files, for example from free 3D model sites.", 11.5f, Ui.TEXT_DIM)
            .apply { setPadding(4.dp, 4.dp, 4.dp, 0) })

        val o = current
        if (o != null) {
            section(o.name)
            val acts = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            acts.addView(Ui.pill(context, "Duplicate") {
                val c = o.copy(); c.x += 0.6f; scene.objects.add(c); sel = scene.objects.size - 1; joint = -1; rebuild(); preview.requestRender()
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8.dp })
            acts.addView(Ui.pill(context, "Delete") {
                scene.objects.removeAt(sel); sel = -1; joint = -1; rebuild(); preview.requestRender()
            }.apply { setTextColor(Ui.DANGER) })
            panel.addView(acts)

            if (o.kind == Shape3D.FIGURE) figureControls(o)

            section("Place")
            slider("Left / right", o.x, -6f, 6f, ::meters) { o.x = it }
            slider("Up / down", o.y, -2f, 5f, ::meters) { o.y = it }
            slider("Front / back", o.z, -6f, 6f, ::meters) { o.z = it }
            slider("Turn", o.ry, -180f, 180f, ::deg) { o.ry = it }
            slider("Tilt", o.rx, -180f, 180f, ::deg) { o.rx = it }
            slider("Roll", o.rz, -180f, 180f, ::deg) { o.rz = it }
            slider("Size", o.size, 0.1f, 4f, { "${(it * 100).roundToInt()}%" }) { o.size = it }
            if (o.kind != Shape3D.FIGURE) {
                slider("Width", o.sx, 0.1f, 8f, { "${(it * 100).roundToInt()}%" }) { o.sx = it }
                slider("Height", o.sy, 0.1f, 8f, { "${(it * 100).roundToInt()}%" }) { o.sy = it }
                slider("Depth", o.sz, 0.1f, 8f, { "${(it * 100).roundToInt()}%" }) { o.sz = it }
            }
            section("Color")
            val colors = intArrayOf(0xFFD9D6D0.toInt(), 0xFFD8C8B4.toInt(), 0xFFB98B6E.toInt(), 0xFF8C6A55.toInt(), 0xFF9AA7B8.toInt(),
                0xFF7FA38C.toInt(), 0xFFC97B6B.toInt(), 0xFF6F6A80.toInt(), 0xFF3A3A40.toInt(), host.brushColor or 0xFF000000.toInt())
            val cr = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in colors) {
                val sw = ColorSwatchView(context)
                sw.color = c
                sw.setOnClickListener { o.color = c; preview.requestRender() }
                cr.addView(sw, LinearLayout.LayoutParams(30.dp, 30.dp).apply { rightMargin = 4.dp })
            }
            panel.addView(HorizontalScrollView(context).apply { addView(cr) })
            panel.addView(Ui.text(context, "The last swatch is your brush color.", 11.5f, Ui.TEXT_DIM).apply { setPadding(4.dp, 4.dp, 0, 0) })
        }

        section("Camera")
        val c = scene.camera
        slider("Lens (perspective)", c.fov, 12f, 100f, { "${it.roundToInt()}°" }) { c.fov = it }
        slider("Distance", c.dist, 0.6f, 25f, ::meters) { c.dist = it }
        val views = GridLayout(context).apply { columnCount = 3 }
        for ((label, yaw, pitch) in listOf(Triple("Front", 0f, 4f), Triple("3/4 view", 35f, 10f), Triple("Side", 90f, 4f),
                Triple("Back", 180f, 6f), Triple("From above", 25f, 55f), Triple("From below", 20f, -25f))) {
            views.addView(Ui.button(context, label) { c.yaw = yaw; c.pitch = pitch; rebuild(); preview.requestRender() }.apply { textSize = 12.5f }, cell())
        }
        panel.addView(views, LinearLayout.LayoutParams(-1, -2))
        panel.addView(Ui.button(context, "Frame everything") { frameAll(); rebuild(); preview.requestRender() }.apply { textSize = 12.5f },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.dp })

        section("Light")
        slider("Direction", scene.lightAz, -180f, 180f, ::deg) { scene.lightAz = it }
        slider("Height", scene.lightEl, -10f, 90f, ::deg) { scene.lightEl = it }
        slider("Shadow brightness", scene.ambient, 0f, 0.9f, { "${(it * 100).roundToInt()}%" }) { scene.ambient = it }

        section("Look")
        val looks = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (l in Look3D.values()) {
            looks.addView(Ui.pill(context, l.label, primary = scene.look == l) { scene.look = l; rebuild(); preview.requestRender() },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 6.dp })
        }
        panel.addView(looks)
        toggle("Outlines", scene.outline) { scene.outline = it }
        toggle("Floor", scene.floor) { scene.floor = it }
        toggle("Shadow layer (stays out of the time-lapse and exports)", shadow) { shadow = it }
    }

    private fun figureControls(o: Obj3D) {
        val pose = o.pose ?: Pose().also { o.pose = it }
        section("Poses")
        val chips = GridLayout(context).apply { columnCount = 3 }
        for ((name, p) in Pose.PRESETS) {
            chips.addView(Ui.button(context, name) { pose.set(p); rebuild(); preview.requestRender() }.apply { textSize = 12.5f }, cell())
        }
        panel.addView(chips, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6.dp, 0, 0) }
        row.addView(Ui.pill(context, "Mirror left → right") { pose.mirror(true); rebuild(); preview.requestRender() },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 6.dp })
        row.addView(Ui.pill(context, "Mirror right → left") { pose.mirror(false); rebuild(); preview.requestRender() })
        panel.addView(HorizontalScrollView(context).apply { addView(row) })

        if (joint >= 0) {
            val j = Mannequin.joints[joint]
            section(j.label)
            val r = pose.rot[joint]
            slider("Bend", r[0], -180f, 180f, ::deg) { r[0] = it }
            slider("Twist", r[1], -180f, 180f, ::deg) { r[1] = it }
            slider("Side", r[2], -180f, 180f, ::deg) { r[2] = it }
            val jr = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            jr.addView(Ui.pill(context, "Reset this part") { r.fill(0f); rebuild(); preview.requestRender() },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 6.dp })
            val other = Mannequin.mirrorOf(joint)
            if (other != joint) jr.addView(Ui.pill(context, "Copy to other side") {
                val t = pose.rot[other]; t[0] = r[0]; t[1] = -r[1]; t[2] = -r[2]; preview.requestRender()
            })
            panel.addView(jr)
        } else {
            panel.addView(Ui.text(context, "Tap a body part in the preview to bend it.", 12.5f, Ui.TEXT_DIM).apply { setPadding(4.dp, 8.dp, 4.dp, 0) })
        }
        section("Body")
        slider("Hips height", -pose.drop, -0.9f, 0.6f, ::meters) { pose.drop = -it }
        slider("Build", o.build, 0.7f, 1.6f, { "${(it * 100).roundToInt()}%" }) { o.build = it }
    }

    private fun frameAll() {
        val c = scene.camera
        if (scene.objects.isEmpty()) { c.tx = 0f; c.ty = 0.9f; c.tz = 0f; c.dist = 4.6f; return }
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = 0f; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (o in scene.objects) {
            val h = if (o.kind == Shape3D.FIGURE) 1.8f * o.size else o.size * max(o.sy, 0.5f)
            val w = if (o.kind == Shape3D.FIGURE) 0.6f * o.size else o.size * max(o.sx, o.sz) / 2
            x0 = min(x0, o.x - w); x1 = max(x1, o.x + w); z0 = min(z0, o.z - w); z1 = max(z1, o.z + w); y1 = max(y1, o.y + h)
        }
        c.tx = (x0 + x1) / 2; c.tz = (z0 + z1) / 2; c.ty = y1 / 2
        val span = max(y1, max(x1 - x0, z1 - z0) * 0.7f)
        c.dist = (span / (2 * kotlin.math.tan(c.fov * Math.PI / 360).toFloat()) * 1.25f).coerceIn(0.8f, 25f)
    }

    private fun cell() = GridLayout.LayoutParams().apply {
        width = 0
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(3.dp, 3.dp, 3.dp, 3.dp)
    }

    private fun section(title: String) {
        panel.addView(Ui.caption(context, L10n.tr(title)).apply { setPadding(4.dp, 18.dp, 0, 6.dp) })
    }

    private fun slider(label: String, value: Float, lo: Float, hi: Float, fmt: (Float) -> String, set: (Float) -> Unit) {
        val t = ((value - lo) / (hi - lo)).coerceIn(0f, 1f)
        panel.addView(Ui.sliderRow(context, label, t, { f -> fmt(lo + (hi - lo) * f) }) { f ->
            set(lo + (hi - lo) * f)
            preview.requestRender()
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp, 10.dp, 4.dp, 0)
        }
        row.addView(Ui.text(context, label, 13f), LinearLayout.LayoutParams(0, -2, 1f))
        val sw = SwitchView(context)
        sw.checked = on
        sw.onToggle = { v -> set(v); preview.requestRender() }
        row.addView(sw, LinearLayout.LayoutParams(48.dp, 28.dp))
        panel.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun meters(v: Float) = String.format(java.util.Locale.US, "%.2f m", v)
    private fun deg(v: Float) = "${v.roundToInt()}°"

    // =========================================================
    // preview
    // =========================================================

    private inner class Preview(ctx: Context) : View(ctx) {
        private var img: Bitmap? = null
        private var ids: IntArray? = null
        private var backdrop: Bitmap? = null
        private val rect = RectF()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f.dp; color = 0x55FFFFFF }
        private var pending = false

        fun requestRender() {
            if (pending) return
            pending = true
            postOnAnimation { pending = false; renderNow(); invalidate() }
        }

        fun release() { img?.recycle(); img = null; backdrop?.recycle(); backdrop = null }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            layoutRect()
            if (backdrop == null) backdrop = host.backdrop(1400)
            requestRender()
        }

        private fun layoutRect() {
            val m = 16f.dp
            val aw = width - 2 * m; val ah = height - 2 * m
            if (aw <= 0 || ah <= 0) return
            val s = min(aw / host.docWidth, ah / host.docHeight)
            val w = host.docWidth * s; val h = host.docHeight * s
            rect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        }

        private fun renderNow() {
            if (rect.width() < 2) return
            // render at screen size (capped for speed)
            val k = min(1f, 1100f / max(rect.width(), rect.height()))
            val w = (rect.width() * k).roundToInt().coerceAtLeast(2)
            val h = (rect.height() * k).roundToInt().coerceAtLeast(2)
            val r = Renderer3D(scene, w, h)
            r.highlightObject = sel
            r.highlightJoint = joint
            val px = IntArray(w * h)
            val idb = IntArray(w * h)
            r.render(px, 1, idb)
            val b = img?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { img?.recycle() }
            b.setPixels(px, 0, w, 0, 0, w, h)
            img = b
            ids = idb
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xFF2B2B30.toInt())
            if (rect.width() < 2) return
            paint.alpha = 255
            backdrop?.let { canvas.drawBitmap(it, null, rect, paint) } ?: run {
                paint.color = 0xFFFFFFFF.toInt(); canvas.drawRect(rect, paint)
            }
            img?.let { canvas.drawBitmap(it, null, rect, paint) }
            canvas.drawRect(rect, frame)
        }

        // ---------- touch ----------
        private var lastX = 0f; private var lastY = 0f
        private var downX = 0f; private var downY = 0f
        private var moved = false
        private var twoDist = 0f; private var twoX = 0f; private var twoY = 0f
        private var multi = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val cam = scene.camera
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastX = e.x; lastY = e.y; downX = e.x; downY = e.y; moved = false; multi = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    multi = true
                    twoDist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)).coerceAtLeast(1f)
                    twoX = (e.getX(0) + e.getX(1)) / 2; twoY = (e.getY(0) + e.getY(1)) / 2
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val d = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)).coerceAtLeast(1f)
                        val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                        cam.dist = (cam.dist * twoDist / d).coerceIn(0.6f, 25f)
                        cam.pan((mx - twoX) / rect.height(), (my - twoY) / rect.height())
                        twoDist = d; twoX = mx; twoY = my
                        moved = true
                        requestRender()
                    } else if (!multi) {
                        val dx = e.x - lastX; val dy = e.y - lastY
                        if (abs(e.x - downX) + abs(e.y - downY) > 10f.dp) moved = true
                        if (moved) {
                            cam.yaw -= dx * 0.35f
                            cam.pitch = (cam.pitch + dy * 0.3f).coerceIn(-85f, 85f)
                            requestRender()
                        }
                        lastX = e.x; lastY = e.y
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && !multi) pick(e.x, e.y)
                }
            }
            return true
        }

        private fun pick(x: Float, y: Float) {
            val b = img ?: return
            val idb = ids ?: return
            if (!rect.contains(x, y)) { select(-1, -1); return }
            val px = ((x - rect.left) / rect.width() * b.width).toInt().coerceIn(0, b.width - 1)
            val py = ((y - rect.top) / rect.height() * b.height).toInt().coerceIn(0, b.height - 1)
            // look around the touch a little: fingers and pens are not exact
            var id = 0
            val rr = 6
            loop@ for (d in 0..rr) for (dy in -d..d) for (dx in -d..d) {
                val qx = px + dx; val qy = py + dy
                if (qx !in 0 until b.width || qy !in 0 until b.height) continue
                val v = idb[qy * b.width + qx]
                if (Renderer3D.pickObject(v) >= 0) { id = v; break@loop }
            }
            if (id == 0 || Renderer3D.pickObject(id) < 0) select(-1, -1)
            else select(Renderer3D.pickObject(id), Renderer3D.pickPart(id))
        }
    }
}
