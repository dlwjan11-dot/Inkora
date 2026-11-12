package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).roundToInt()
/** a phone held upright (or any window this narrow): panels and bars use their small layout */
val android.content.Context.narrow: Boolean get() = (if (Screen.wDp > 0) Screen.wDp else resources.configuration.screenWidthDp) < 600
/** a phone on its side: not much room from top to bottom */
val android.content.Context.short: Boolean get() = (if (Screen.hDp > 0) Screen.hDp else resources.configuration.screenHeightDp) < 560

/** the size of the app's window as it is laid out right now (dp); MainActivity keeps it up to date */
object Screen { var wDp = 0; var hDp = 0 }

val Float.dp: Float get() = this * Resources.getSystem().displayMetrics.density

object Ui {
    /** the light interface (Preferences); the colors below follow it. Set before the screen is built. */
    var light = false

    private fun pick(dark: Long, bright: Long) = (if (light) bright else dark).toInt()

    val PANEL get() = pick(0xF51E1F25, 0xF7F7F8FB)
    val PANEL_ROW get() = pick(0xFF2B2C33, 0xFFE8EAF0)
    val PANEL_ROW_ACTIVE get() = pick(0xFF2B3A55, 0xFFD3E1FA)
    val BAR get() = pick(0xF0141519, 0xF5FFFFFF)
    val ACCENT get() = pick(0xFF6E9FF5, 0xFF2F6FE0)
    val ACCENT_SOFT get() = pick(0x2E6E9FF5, 0x2E2F6FE0)
    val TEXT get() = pick(0xFFE8E9ED, 0xFF1C1E24)
    val TEXT_DIM get() = pick(0xFF8D92A0, 0xFF5F6573)
    val CARD get() = pick(0xFF27282F, 0xFFEEF0F4)
    val CARD_HI get() = pick(0xFF30333A, 0xFFE2E5EB)
    val DIVIDER get() = pick(0x16FFFFFF, 0x1A000000)
    val DANGER get() = pick(0xFFF07178, 0xFFD64550)
    /** a whole page (gallery, brush studio, settings pages) */
    val SURFACE get() = pick(0xFF101115, 0xFFF2F3F6)
    /** a page laid over the canvas */
    val SCRIM get() = pick(0xF2101115, 0xF4F2F3F6)
    /** sunken fields: tabs, thumbnails, small windows */
    val SUNKEN get() = pick(0xFF14151A, 0xFFDDE0E7)
    /** around the canvas */
    val CANVAS_BG get() = pick(0xFF2A2B31, 0xFFCDD0D6)
    /** thin outlines of cards */
    val OUTLINE get() = pick(0x1AFFFFFF, 0x1F000000)
    /** the empty part of a slider */
    val TRACK get() = pick(0x55FFFFFF, 0x33000000)
    /** text on the accent color */
    val ON_ACCENT = 0xFFFFFFFF.toInt()

    fun rounded(color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp.dp
        if (strokeDp > 0) setStroke(strokeDp.dp, strokeColor)
    }

    /**
     * The soft flash a button gives when it is touched, as a foreground: it stays when the
     * button's background is changed later (accent buttons, chips).
     */
    fun ripple(radiusDp: Float = 10f) = android.graphics.drawable.RippleDrawable(
        ColorStateList.valueOf(0x30FFFFFF), null, rounded(0xFFFFFFFF.toInt(), radiusDp))

    fun text(ctx: Context, s: String, sp: Float = 15f, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = L10n.tr(s)
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun button(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = L10n.tr(label)
        textSize = 14f
        setTextColor(TEXT)
        gravity = Gravity.CENTER
        setPadding(12.dp, 8.dp, 12.dp, 8.dp)
        background = rounded(PANEL_ROW, 10f)
        foreground = ripple(10f)
        setOnClickListener { onClick() }
    }

    fun seek(ctx: Context, value: Float, onChange: (Float) -> Unit) = SeekBar(ctx).apply {
        max = 1000
        progress = (value * 1000).roundToInt()
        progressTintList = ColorStateList.valueOf(ACCENT)
        thumbTintList = ColorStateList.valueOf(TEXT)
        progressBackgroundTintList = ColorStateList.valueOf(TRACK)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p / 1000f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    fun toast(ctx: Context, s: String) = android.widget.Toast.makeText(ctx, L10n.tr(s), android.widget.Toast.LENGTH_SHORT).show()

    /** small section label */
    fun caption(ctx: Context, s: String) = text(ctx, s, 12.5f, TEXT_DIM, true)

    /** thin divider line */
    fun divider(ctx: Context) = View(ctx).apply { setBackgroundColor(DIVIDER) }

    /** pill-shaped button; [primary] = filled with the accent color */
    fun pill(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit) = button(ctx, label, onClick).apply {
        textSize = 13f
        setPadding(14.dp, 8.dp, 14.dp, 8.dp)
        background = rounded(if (primary) ACCENT else PANEL_ROW, 18f)
        if (primary) setTextColor(ON_ACCENT)
        foreground = ripple(18f)
    }

    /** "Label ......... [slider]" row with a live value. */
    fun sliderRow(ctx: Context, label: String, value: Float, format: (Float) -> String, onChange: (Float) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp, 6.dp, 4.dp, 2.dp)
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val name = text(ctx, label, 13f, TEXT_DIM)
        val v = text(ctx, format(value), 13f, TEXT)
        head.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(v)
        row.addView(head)
        row.addView(seek(ctx, value) { f -> v.text = format(f); onChange(f) }, LinearLayout.LayoutParams(-1, -2))
        return row
    }
}

enum class Icon { BRUSH, ERASER, LAYERS, ACTIONS, UNDO, REDO, SYMMETRY, EYEDROPPER, PLUS, EYE, EYE_OFF, UP, DOWN, LOCK, GRID,
    WAND, SELECT, TRANSFORM, SMUDGE, TEXT,
    V_DIRECT, V_PEN, V_PENCIL, V_RECT, V_ELLIPSE, V_POLYGON, V_STAR, V_LINE, PIN, VECTOR,
    HOME, MENU, FILL, ADJUST, SPARKLE, EDIT, STAR, STAR_FILLED, CLOSE, CHECK, SETTINGS, SHARE, PHOTO, FLIP, FIT, FOCUS,
    RULER, SHAPE, BLUR, INVERT, CONTRAST, NOISE, POSTERIZE, BW, SHARPEN, HUE, BALANCE,
    SEL_RECT, SEL_ELLIPSE, SEL_ALL, DESELECT, SEL_INVERT, FEATHER, ROTATE, CUSTOMIZE, DRAG, EXPORT, CLOCK, COPY,
    NAVIGATOR, ORB, REFERENCE, GLOBE, LIGHTBULB,
    WHEEL, HAND, KEYBOARD, VIDEO, HELP, MODIFY, INK, PENCIL, NIB, PALETTE, DROP, SPRAY, TEXTURE, PIXEL, FOLDER, CLEAR, WRENCH, CURSOR, CUBE,
    GRADIENT, RECOLOR, MEASURE, QUICKSHAPE, TRACE, CROP, ACTUAL, SEL_COLOR, SEL_BRUSH, LIQUIFY, COMIC, BUBBLE, SNAPSHOT, BACKUP, IMPORT, NEW_CANVAS, INFO, PRESSURE, CHEV_LEFT, CHEV_RIGHT, EXPAND, SWAP_SIDE, HIDE, TOUCH, LAYOUT, SUN }


/** Toolbar button showing one icon. */

/** A small label that explains a control when the pen or a mouse rests over it for two seconds. */
object HoverTip {
    private const val WAIT_MS = 2000L
    private var popup: android.widget.PopupWindow? = null
    private var pending: Runnable? = null
    private var owner: View? = null

    /** [text] gives the title and what it does, or null for no tip */
    fun attach(v: View, text: () -> Pair<String, String?>?) {
        v.setOnHoverListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    cancel()
                    owner = view
                    val r = Runnable { if (owner === view && view.isAttachedToWindow) text()?.let { show(view, it.first, it.second) } }
                    pending = r
                    view.postDelayed(r, WAIT_MS)
                }
                MotionEvent.ACTION_HOVER_EXIT -> if (owner === view) cancel()
            }
            false
        }
    }

    fun cancel() {
        pending?.let { owner?.removeCallbacks(it) }
        pending = null; owner = null
        runCatching { popup?.dismiss() }
        popup = null
    }

    private fun show(anchor: View, title: String, body: String?) {
        val ctx = anchor.context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 9.dp, 12.dp, 10.dp)
            background = Ui.rounded(0xF526282F.toInt(), 12f, 0x24FFFFFF, 1)
        }
        if (title.isNotBlank()) box.addView(Ui.text(ctx, title, 13.5f, bold = true))
        if (!body.isNullOrBlank()) box.addView(Ui.text(ctx, body, 12.5f, 0xFFC5C8D2.toInt()).apply { setPadding(0, 2.dp, 0, 0) })
        if (box.childCount == 0) return
        val root = anchor.rootView
        val maxW = minOf(280.dp, root.width - 24.dp)
        box.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val w = box.measuredWidth; val h = box.measuredHeight
        val loc = IntArray(2); anchor.getLocationInWindow(loc)
        val cx = loc[0] + anchor.width / 2; val cy = loc[1] + anchor.height / 2
        val x: Int; val y: Int
        if (loc[1] < 70.dp) {                      // in the top bar: under it
            x = cx - w / 2; y = loc[1] + anchor.height + 6.dp
        } else if (loc[1] + anchor.height > root.height - 70.dp) { // at the bottom: over it
            x = cx - w / 2; y = loc[1] - h - 6.dp
        } else {                                    // at a side: beside it
            x = if (cx < root.width / 2) loc[0] + anchor.width + 8.dp else loc[0] - w - 8.dp
            y = cy - h / 2
        }
        val pw = android.widget.PopupWindow(box, w, h, false)
        pw.isTouchable = false
        pw.elevation = 12f.dp
        pw.animationStyle = android.R.style.Animation_Toast
        runCatching {
            pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x.coerceIn(8.dp, (root.width - w - 8.dp).coerceAtLeast(8.dp)), y.coerceIn(8.dp, (root.height - h - 8.dp).coerceAtLeast(8.dp)))
            popup = pw
        }
    }
}

/** What each control does, shown when the pen (or a mouse) rests over it. By the control's name. */
object TipTexts {
    val byName = mapOf(
        "Gallery" to "All your artworks. Your work is saved on the way",
        "Actions" to "Add things, canvas settings, share and export, preferences, help",
        "Adjustments" to "Change colors and sharpness of the layer: hue, brightness, blur and more",
        "Selection" to "Mark a part of the artwork; painting and edits then stay inside it",
        "Transform" to "Move, scale, turn or bend the layer or the selection",
        "More tools" to "Creative tools (text, shapes, 3D, gradient) and editing tools (liquify, crop, filters)",
        "Brush" to "Paint. Tap again for the brush library; hold to bring the brush from another tool",
        "Smudge" to "Push and blend paint that is already there. Hold: use the brush you paint with",
        "Eraser" to "Rub paint out. Hold: erase with the brush you paint with",
        "Layers" to "The layers of the artwork: add, hide, reorder, group, blend, mask",
        "Color" to "The color you paint with. Tap for the wheel and palettes; drag it onto the canvas to fill",
        "Brush size" to "Drag up or down. Slide away for fine control; double-tap the handle to leave a mark; hold it for a number",
        "Brush opacity" to "How see-through the stroke is. Works like the size slider",
        "Modify" to "Tap, then touch the canvas to pick a color. Held down, it changes what touches do (Gesture controls)",
        "Undo" to "Take back the last step. Also: tap with two fingers",
        "Redo" to "Bring back the step you undid. Also: tap with three fingers",
        "Brush preview" to "The brush in hand, at its size. Tap for the brush library",
        "Show interface" to "Brings the bars and tools back",
        "Customize toolbar" to "Choose the tools on this toolbar and their order"
    )
}

class IconView(context: Context, var icon: Icon) : View(context) {
    /** what the pen sees when it rests over this button (else its name says it, see [TipTexts]) */
    var tip: String? = null

    var active = false
        set(v) { field = v; invalidate() }
    var tint = Ui.TEXT
        set(v) { field = v; invalidate() }
    /** small accent dot in the corner (e.g. "this setting is on") */
    var dot = false
        set(v) { field = v; invalidate() }
    /** icon size relative to the view */
    var iconScale = 0.58f
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressed = false

    init {
        isClickable = true
        HoverTip.attach(this) {
            val name = contentDescription?.toString()
            val body = tip ?: name?.let { TipTexts.byName[it] }
            if (name == null && body == null) null else (name ?: "") to body
        }
    }

    override fun setPressed(p: Boolean) {
        super.setPressed(p)
        if (p != pressed) { pressed = p; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val inset = 3f.dp
        val r = RectF(inset, inset, width - inset, height - inset)
        val rad = minOf(r.width(), r.height()) * 0.3f
        if (active) {
            bg.color = Ui.ACCENT_SOFT
            canvas.drawRoundRect(r, rad, rad, bg)
        } else if (pressed) {
            bg.color = 0x1AFFFFFF
            canvas.drawRoundRect(r, rad, rad, bg)
        }
        IconPainter.draw(canvas, icon, width / 2f, height / 2f, minOf(width, height) * iconScale,
            if (active) Ui.ACCENT else tint, if (isEnabled) 255 else 80)
        if (dot) {
            bg.color = Ui.ACCENT
            canvas.drawCircle(width - inset - 4f.dp, inset + 4f.dp, 3f.dp, bg)
        }
    }
}

/** Live preview of the brush: its size (scaled down when big), color and opacity. */
class BrushDotView(context: Context) : View(context) {
    var sizePx = 10f
        set(v) { field = v; invalidate() }
    var color = 0xFF000000.toInt()
        set(v) { field = v; invalidate() }
    var opacity = 1f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val maxR = minOf(width, height) / 2f - 2f.dp
        // 1 px → tiny dot, 500 px → full circle (log scale, like the slider)
        val t = (Math.log10(sizePx.coerceIn(1f, 500f).toDouble()) / Math.log10(500.0)).toFloat()
        val r = 1.5f.dp + (maxR - 1.5f.dp) * t
        p.style = Paint.Style.FILL
        p.color = color
        p.alpha = (255 * opacity.coerceIn(0.08f, 1f)).toInt()
        canvas.drawCircle(width / 2f, height / 2f, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f.dp
        p.color = 0x99FFFFFF.toInt()
        canvas.drawCircle(width / 2f, height / 2f, r, p)
    }
}

/** Round button showing the current color. */
class ColorSwatchView(context: Context) : View(context) {
    var color = 0xFF000000.toInt()
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f - 7f.dp
        p.style = Paint.Style.FILL
        p.color = color
        canvas.drawCircle(width / 2f, height / 2f, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f.dp
        p.color = 0xCCFFFFFF.toInt()
        canvas.drawCircle(width / 2f, height / 2f, r + 1f.dp, p)
    }
}

/** Vertical slider like the side bar of most painting apps. Value 0..1, top = 1. */
class VerticalSlider(context: Context) : View(context) {
    var value = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onChange: ((Float) -> Unit)? = null
    var onDragState: ((Boolean) -> Unit)? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var lastTapAt = 0L
    private var raw = 0f

    /** marks you leave on the bar (double-tap the handle): tap one to come back to that value */
    val pins = ArrayList<Float>()
    /** a mark was added (true) or removed (false) */
    var onPins: ((Boolean) -> Unit)? = null
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** the handle was held still: ask for an exact number */
    var onExact: (() -> Unit)? = null
    private var snapped: Float? = null
    private var held = false
    private val holdRun = Runnable {
        if (!moved && onExact != null) {
            held = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onDragState?.invoke(false)
            onExact?.invoke()
        }
    }

    private fun yOf(v: Float): Float {
        val top = 19f.dp
        val bottom = height - 19f.dp
        return bottom - (bottom - top) * v
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val tw = 8f.dp
        track.color = 0x55FFFFFF
        canvas.drawRoundRect(RectF(w / 2 - tw / 2, 10f.dp, w / 2 + tw / 2, h - 10f.dp), tw / 2, tw / 2, track)
        val y = thumbY()
        track.color = 0xAAFFFFFF.toInt()
        canvas.drawRoundRect(RectF(w / 2 - tw / 2, y, w / 2 + tw / 2, h - 10f.dp), tw / 2, tw / 2, track)
        pinPaint.color = Ui.ACCENT
        for (p in pins) {
            val py = yOf(p)
            canvas.drawRoundRect(RectF(w / 2 - 15f.dp, py - 2.5f.dp, w / 2 + 15f.dp, py + 2.5f.dp), 2.5f.dp, 2.5f.dp, pinPaint)
        }
        thumb.color = Ui.TEXT
        thumb.setShadowLayer(4f.dp, 0f, 1f.dp, 0x88000000.toInt())
        canvas.drawRoundRect(RectF(w / 2 - 12f.dp, y - 9f.dp, w / 2 + 12f.dp, y + 9f.dp), 6f.dp, 6f.dp, thumb)
    }

    private fun tapped(y: Float, time: Long) {
        val onThumb = kotlin.math.abs(y - thumbY()) < 16f.dp
        if (onThumb && time - lastTapAt < 350) {
            // double tap on the handle: leave a mark here, or take away the one that is here
            lastTapAt = 0L
            val here = pins.firstOrNull { kotlin.math.abs(it - value) < 0.02f }
            if (here != null) pins.remove(here)
            else { if (pins.size >= 5) pins.removeAt(0); pins.add(value) }
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            onPins?.invoke(here == null)
            return
        }
        lastTapAt = if (onThumb) time else 0L
        if (onThumb) return
        // a tap on a mark: back to that value
        val p = pins.minByOrNull { kotlin.math.abs(yOf(it) - y) } ?: return
        if (kotlin.math.abs(yOf(p) - y) > 14f.dp) return
        value = p
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        onChange?.invoke(value)
    }

    private fun thumbY(): Float {
        val top = 19f.dp
        val bottom = height - 19f.dp
        return bottom - (bottom - top) * value
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val usable = max(1f, height - 38f.dp)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastY = e.y; downX = e.x; downY = e.y; downAt = e.eventTime
                moved = false; held = false; snapped = null
                raw = value
                onDragState?.invoke(true)
                if (kotlin.math.abs(e.y - thumbY()) < 16f.dp) postDelayed(holdRun, 550)
            }
            MotionEvent.ACTION_MOVE -> {
                if (held) return true
                if (!moved && kotlin.math.hypot(e.x - downX, e.y - downY) > 8f.dp) { moved = true; lastY = e.y; removeCallbacks(holdRun) }
                if (moved) {
                    // slide sideways, away from the bar, and it moves slower: fine control
                    val away = max(0f, max(-e.x, e.x - width))
                    val speed = (1f / (1f + away / 45f.dp)).coerceAtLeast(0.06f)
                    raw = (raw - (e.y - lastY) / usable * speed).coerceIn(0f, 1f)
                    lastY = e.y
                    // near a mark the handle catches on it (less so in fine control, where you want every step)
                    val near = pins.minByOrNull { kotlin.math.abs(it - raw) }?.takeIf { kotlin.math.abs(it - raw) < 0.022f * speed }
                    if (near != null && near != snapped) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    snapped = near
                    value = near ?: raw
                    onChange?.invoke(value)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRun)
                if (held) return true
                if (!moved && e.eventTime - downAt < 350) tapped(e.y, e.eventTime)
                onDragState?.invoke(false)
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(holdRun); if (!held) onDragState?.invoke(false) }
        }
        return true
    }
}

/** Small message pill + optional brush size preview, drawn over the canvas. */
class HudView(context: Context) : View(context) {
    private var message: String? = null
    private var circle = 0f
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT
        textSize = 15f.dp
        textAlign = Paint.Align.CENTER
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0141416.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hide = Runnable { message = null; circle = 0f; invalidate() }

    fun show(text: String, circleDiameterPx: Float = 0f, sticky: Boolean = false) {
        message = L10n.tr(text)
        circle = circleDiameterPx
        removeCallbacks(hide)
        // a short word goes quickly; a sentence stays long enough to read
        if (!sticky) postDelayed(hide, (1100L + 38L * (message?.length ?: 0)).coerceAtMost(4200L))
        invalidate()
    }

    fun hideSoon() {
        removeCallbacks(hide)
        postDelayed(hide, 500)
    }

    override fun onDraw(canvas: Canvas) {
        val m = message ?: return
        // a long message gets smaller letters rather than running off the screen
        textPaint.textSize = 15f.dp
        val room = width - 64f.dp
        var tw = textPaint.measureText(m)
        if (tw > room && room > 0f) {
            textPaint.textSize = (15f.dp * room / tw).coerceAtLeast(10f.dp)
            tw = textPaint.measureText(m)
        }
        val cx = width / 2f
        val top = 76f.dp
        canvas.drawRoundRect(RectF(cx - tw / 2 - 16f.dp, top, cx + tw / 2 + 16f.dp, top + 36f.dp), 18f.dp, 18f.dp, pill)
        canvas.drawText(m, cx, top + 24f.dp, textPaint)
        if (circle > 0f) {
            val r = circle / 2f
            ring.strokeWidth = 3f.dp
            ring.color = 0x66000000
            canvas.drawCircle(cx, height / 2f, r, ring)
            ring.strokeWidth = 1.5f.dp
            ring.color = 0xEEFFFFFF.toInt()
            canvas.drawCircle(cx, height / 2f, r, ring)
        }
    }
}
