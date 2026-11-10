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
val android.content.Context.narrow: Boolean get() = resources.configuration.screenWidthDp < 600
/** a phone on its side: not much room from top to bottom */
val android.content.Context.short: Boolean get() = resources.configuration.screenHeightDp < 560

val Float.dp: Float get() = this * Resources.getSystem().displayMetrics.density

object Ui {
    val PANEL = 0xF51E1F25.toInt()
    val PANEL_ROW = 0xFF2B2C33.toInt()
    val PANEL_ROW_ACTIVE = 0xFF2B3A55.toInt()
    val BAR = 0xF0141519.toInt()
    val ACCENT = 0xFF6E9FF5.toInt()
    val ACCENT_SOFT = 0x2E6E9FF5
    val TEXT = 0xFFE8E9ED.toInt()
    val TEXT_DIM = 0xFF8D92A0.toInt()
    val CARD = 0xFF27282F.toInt()
    val CARD_HI = 0xFF30333A.toInt()
    val DIVIDER = 0x16FFFFFF
    val DANGER = 0xFFF07178.toInt()

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
        progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p / 1000f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    fun toast(ctx: Context, s: String) = android.widget.Toast.makeText(ctx, L10n.tr(s), android.widget.Toast.LENGTH_SHORT).show()

    /** small uppercase section label */
    fun caption(ctx: Context, s: String) = text(ctx, s.uppercase(), 10.5f, TEXT_DIM, true).apply { letterSpacing = 0.08f }

    /** thin divider line */
    fun divider(ctx: Context) = View(ctx).apply { setBackgroundColor(DIVIDER) }

    /** pill-shaped button; [primary] = filled with the accent color */
    fun pill(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit) = button(ctx, label, onClick).apply {
        textSize = 13f
        setPadding(14.dp, 8.dp, 14.dp, 8.dp)
        background = rounded(if (primary) ACCENT else PANEL_ROW, 18f)
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
    WHEEL, HAND, KEYBOARD, VIDEO, HELP, MODIFY, INK, PENCIL, NIB, PALETTE, DROP, SPRAY, TEXTURE, PIXEL, FOLDER, CLEAR, WRENCH, CURSOR, CUBE }


/** Toolbar button showing one icon. */
class IconView(context: Context, var icon: Icon) : View(context) {
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
