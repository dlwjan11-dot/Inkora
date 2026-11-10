package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Pen quick menu (S Pen button hold / double-click, or pen held still on the canvas).
 *
 *  center ......... standard color wheel: hue ring + saturation/brightness square — drag to pick
 *  inner arc ...... your palette / recent colors + "+" to save the current color
 *  top bubbles .... tools
 *  bottom bubbles . recent brushes
 *  left / right ... curved Size and Opacity sliders
 *
 * With the pen button held: move over a bubble or a color and release the button to choose it.
 * Or simply tap. Tap outside to close.
 */
class QuickMenuView(context: Context) : View(context) {

    class Item(
        val label: String,
        val icon: Icon? = null,
        val preview: Bitmap? = null,
        val active: Boolean = false,
        val action: () -> Unit
    )

    var isOpen = false
        private set
    /** live color changes (wheel or swatch) */
    var onColor: ((Int) -> Unit)? = null
    /** "+" pressed: save this color to the palette */
    var onSaveColor: ((Int) -> Unit)? = null
    /** slider values 0..1 */
    var onSize: ((Float) -> Unit)? = null
    var onOpacity: ((Float) -> Unit)? = null
    var sizeLabel: (Float) -> String = { "${(it * 100).toInt()}" }
    var opacityLabel: (Float) -> String = { "${(it * 100).toInt()}%" }
    var onClosed: (() -> Unit)? = null

    // ---- geometry (dp) ----
    private val rDisc = 80f.dp
    private val rSwatch = 101f.dp
    private val swatchR = 9.5f.dp
    private val rBubbles = 148f.dp
    private val bubbleR = 21f.dp
    private val rBase = rBubbles + bubbleR + 12f.dp
    private val sliderW = 12f.dp

    private val toolStep = 18f
    private val brushStep = 22f
    private val sizeArc = 158f to 202f        // canvas degrees, bottom → top
    private val opacityArc = 22f to -22f      // bottom → top

    private class Bubble(val item: Item, val angle: Float, var x: Float = 0f, var y: Float = 0f)
    private class Swatch(val color: Int?, val angle: Float, var x: Float = 0f, var y: Float = 0f) // null = "+"

    private val bubbles = ArrayList<Bubble>()
    private val swatches = ArrayList<Swatch>()
    private val colors = ArrayList<Int>()
    private val disc = ColorDisc()

    private var cx = 0f
    private var cy = 0f
    private var openX = 0f
    private var openY = 0f
    private var size = 0.5f
    private var opacity = 1f

    private var hotBubble: Bubble? = null
    private var hotSwatch: Swatch? = null

    private enum class Drag { NONE, DISC, SIZE, OPACITY, BUBBLE, SWATCH }
    private var drag = Drag.NONE
    private var downBubble: Bubble? = null
    private var downSwatch: Swatch? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT; textAlign = Paint.Align.CENTER }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF0161619.toInt()
        setShadowLayer(22f.dp, 0f, 6f.dp, 0x99000000.toInt())
    }
    private val clip = Path()

    // =========================================================
    // open / close
    // =========================================================

    fun open(x: Float, y: Float, color: Int, tools: List<Item>, brushes: List<Item>, swatchColors: List<Int>,
             size: Float, opacity: Float) {
        val mh = rBase + 8f.dp
        // the first time it opens it has not been laid out yet (it starts hidden): its size is then
        // the screen's, as it will be. Without this it opened in the top corner once.
        val w = if (width > 0) width else (parent as? android.view.View)?.width ?: 0
        val h = if (height > 0) height else (parent as? android.view.View)?.height ?: 0
        cx = x.coerceIn(mh, (w - mh).coerceAtLeast(mh))
        cy = y.coerceIn(rBase + 46f.dp, (h - mh).coerceAtLeast(rBase + 46f.dp))
        openX = x; openY = y
        this.size = size.coerceIn(0f, 1f)
        this.opacity = opacity.coerceIn(0f, 1f)
        disc.set(color)
        disc.layout(cx, cy, rDisc, 17f.dp)

        bubbles.clear()
        tools.forEachIndexed { i, it -> bubbles.add(Bubble(it, 270f + (i - (tools.size - 1) / 2f) * toolStep)) }
        brushes.forEachIndexed { i, it -> bubbles.add(Bubble(it, 90f - (i - (brushes.size - 1) / 2f) * brushStep)) }
        for (b in bubbles) place(b.angle, rBubbles).let { (px, py) -> b.x = px; b.y = py }

        colors.clear()
        colors.addAll(swatchColors.map { it or 0xFF000000.toInt() }.distinct().take(10))
        layoutSwatches()

        hotBubble = null; hotSwatch = null; drag = Drag.NONE
        isOpen = true
        animate().cancel()
        pivotX = cx; pivotY = cy
        scaleX = 0.86f; scaleY = 0.86f; alpha = 0f
        visibility = VISIBLE
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(170).setInterpolator(DecelerateInterpolator(1.6f)).start()
        invalidate()
    }

    private fun layoutSwatches() {
        swatches.clear()
        val n = colors.size + 1
        // spread along the bottom of the inner ring, the "+" last
        val span = min(130f, 13f * n)
        colors.forEachIndexed { i, c -> swatches.add(Swatch(c, 90f + span / 2 - i * span / (n - 1).coerceAtLeast(1))) }
        swatches.add(Swatch(null, if (n == 1) 90f else 90f - span / 2))
        for (s in swatches) place(s.angle, rSwatch).let { (px, py) -> s.x = px; s.y = py }
    }

    private fun place(deg: Float, r: Float): Pair<Float, Float> {
        val a = Math.toRadians(deg.toDouble())
        return (cx + (cos(a) * r).toFloat()) to (cy + (sin(a) * r).toFloat())
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        hotBubble = null; hotSwatch = null; drag = Drag.NONE
        animate().cancel()
        animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(90).withEndAction {
            if (!isOpen) visibility = GONE
        }.start()
        onClosed?.invoke()
    }

    // =========================================================
    // pen hover + button release
    // =========================================================

    fun hoverAt(x: Float, y: Float) {
        if (!isOpen || drag != Drag.NONE) return
        val b = bubbleAt(x, y)
        val s = if (b == null) swatchAt(x, y) else null
        if (b !== hotBubble || s !== hotSwatch) { hotBubble = b; hotSwatch = s; invalidate() }
    }

    /** pen button released after a hold: choose what is under the pen (if the pen moved there) */
    fun releaseAt(x: Float, y: Float) {
        if (!isOpen) return
        if (hypot(x - openX, y - openY) < 28f.dp) return
        bubbleAt(x, y)?.let { choose(it); return }
        swatchAt(x, y)?.let { pickSwatch(it) }
    }

    private fun choose(b: Bubble) {
        close()
        b.item.action()
    }

    private fun pickSwatch(s: Swatch) {
        val c = s.color
        if (c == null) {
            val cur = disc.color
            onSaveColor?.invoke(cur)
            colors.remove(cur); colors.add(0, cur)
            while (colors.size > 10) colors.removeAt(colors.size - 1)
            layoutSwatches()
            invalidate()
            return
        }
        disc.set(c)
        onColor?.invoke(c)
        close()
    }

    // =========================================================
    // hit tests
    // =========================================================

    private fun bubbleAt(x: Float, y: Float) = bubbles.firstOrNull { hypot(x - it.x, y - it.y) <= bubbleR + 5f.dp }
    private fun swatchAt(x: Float, y: Float) = swatches.firstOrNull { hypot(x - it.x, y - it.y) <= swatchR + 4f.dp }

    private fun angleOf(x: Float, y: Float) = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()

    /** which slider is under (x, y), if any */
    private fun sliderAt(x: Float, y: Float): Drag {
        val d = hypot(x - cx, y - cy)
        if (d < rBubbles - 22f.dp || d > rBubbles + 22f.dp) return Drag.NONE
        var a = angleOf(x, y) // -180..180
        if (a in -30f..30f) return Drag.OPACITY
        if (a < 0) a += 360f
        if (a in (sizeArc.first - 6f)..(sizeArc.second + 6f)) return Drag.SIZE
        return Drag.NONE
    }

    private fun sliderValue(which: Drag, x: Float, y: Float): Float {
        val a = angleOf(x, y)
        return if (which == Drag.SIZE) {
            val aa = if (a < 0) a + 360f else a
            ((aa - sizeArc.first) / (sizeArc.second - sizeArc.first)).coerceIn(0f, 1f)
        } else ((opacityArc.first - a) / (opacityArc.first - opacityArc.second)).coerceIn(0f, 1f)
    }

    // =========================================================
    // touch
    // =========================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isOpen) return false
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downBubble = null; downSwatch = null
                drag = when {
                    disc.touch(x, y, MotionEvent.ACTION_DOWN) -> { onColor?.invoke(disc.color); Drag.DISC }
                    bubbleAt(x, y) != null -> { downBubble = bubbleAt(x, y); hotBubble = downBubble; Drag.BUBBLE }
                    swatchAt(x, y) != null -> { downSwatch = swatchAt(x, y); hotSwatch = downSwatch; Drag.SWATCH }
                    sliderAt(x, y) != Drag.NONE -> sliderAt(x, y)
                    hypot(x - cx, y - cy) > rBase -> { close(); return true }
                    else -> Drag.NONE
                }
                applySlider(x, y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                when (drag) {
                    Drag.DISC -> if (disc.touch(x, y, MotionEvent.ACTION_MOVE)) onColor?.invoke(disc.color)
                    Drag.SIZE, Drag.OPACITY -> applySlider(x, y)
                    Drag.BUBBLE -> hotBubble = bubbleAt(x, y)
                    Drag.SWATCH -> hotSwatch = swatchAt(x, y)
                    Drag.NONE -> {}
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val d = drag
                drag = Drag.NONE
                when (d) {
                    Drag.DISC -> disc.touch(x, y, MotionEvent.ACTION_UP)
                    Drag.BUBBLE -> { val b = bubbleAt(x, y); if (b != null && b === downBubble) { choose(b); return true } }
                    Drag.SWATCH -> { val s = swatchAt(x, y); if (s != null && s === downSwatch) pickSwatch(s) }
                    else -> {}
                }
                hotBubble = null; hotSwatch = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { drag = Drag.NONE; disc.touch(x, y, MotionEvent.ACTION_CANCEL); invalidate() }
        }
        return true
    }

    private fun applySlider(x: Float, y: Float) {
        when (drag) {
            Drag.SIZE -> { size = sliderValue(Drag.SIZE, x, y); onSize?.invoke(size) }
            Drag.OPACITY -> { opacity = sliderValue(Drag.OPACITY, x, y); onOpacity?.invoke(opacity) }
            else -> {}
        }
    }

    // =========================================================
    // drawing
    // =========================================================

    override fun onDraw(canvas: Canvas) {
        if (!isOpen && alpha == 0f) return
        canvas.drawColor(0x26000000)
        // base plate
        canvas.drawCircle(cx, cy, rBase, shadow)
        line.color = 0x16FFFFFF; line.strokeWidth = 1f.dp
        canvas.drawCircle(cx, cy, rBase, line)
        // a quiet ring separating the colors from the tools
        paint.color = 0xFF0F0F12.toInt()
        canvas.drawCircle(cx, cy, rSwatch + swatchR + 9f.dp, paint)

        disc.draw(canvas)
        drawSwatches(canvas)
        drawSlider(canvas, sizeArc, size, "SIZE", sizeLabel(size), drag == Drag.SIZE)
        drawSlider(canvas, opacityArc, opacity, "OPACITY", opacityLabel(opacity), drag == Drag.OPACITY)
        for (b in bubbles) drawBubble(canvas, b)
        drawTooltip(canvas)
    }

    private fun drawSwatches(canvas: Canvas) {
        val current = disc.color
        for (s in swatches) {
            val hot = s === hotSwatch
            val r = if (hot) swatchR + 2.5f.dp else swatchR
            val c = s.color
            if (c == null) {
                paint.color = if (hot) Ui.ACCENT else Ui.PANEL_ROW
                canvas.drawCircle(s.x, s.y, r, paint)
                IconPainter.draw(canvas, Icon.PLUS, s.x, s.y, r * 1.25f, Ui.TEXT)
                continue
            }
            paint.color = c
            canvas.drawCircle(s.x, s.y, r, paint)
            line.strokeWidth = if (c == current || hot) 2.5f.dp else 1f.dp
            line.color = if (c == current || hot) Color.WHITE else 0x40FFFFFF
            canvas.drawCircle(s.x, s.y, r + 1f.dp, line)
        }
    }

    private fun drawSlider(canvas: Canvas, arc: Pair<Float, Float>, v: Float, name: String, value: String, active: Boolean) {
        val rect = RectF(cx - rBubbles, cy - rBubbles, cx + rBubbles, cy + rBubbles)
        val sweep = arc.second - arc.first
        line.strokeWidth = sliderW
        line.color = 0x2EFFFFFF
        canvas.drawArc(rect, arc.first, sweep, false, line)
        line.color = if (active) Ui.ACCENT else 0xD9FFFFFF.toInt()
        if (v > 0.001f) canvas.drawArc(rect, arc.first, sweep * v, false, line)
        val (kx, ky) = place(arc.first + sweep * v, rBubbles)
        paint.color = Color.WHITE
        canvas.drawCircle(kx, ky, if (active) 11f.dp else 9f.dp, paint)
        line.strokeWidth = 1f.dp; line.color = 0x55000000
        canvas.drawCircle(kx, ky, if (active) 11f.dp else 9f.dp, line)

        // while the size is being dragged, the brush's size shows in the middle of the wheel
        if (name == "SIZE" && active) {
            val previewR = (v * (rDisc - 8f.dp)).coerceAtLeast(2f.dp)
            paint.color = 0x99FFFFFF.toInt()
            canvas.drawCircle(cx, cy, previewR, paint)
            line.strokeWidth = 1.5f.dp
            line.color = Ui.ACCENT
            canvas.drawCircle(cx, cy, previewR, line)
        }

        // caption + value, inside the arc
        val mid = (arc.first + arc.second) / 2f
        val (tx, ty) = place(mid, rBubbles - 34f.dp)
        text.textSize = 8.5f.dp
        text.color = Ui.TEXT_DIM
        canvas.drawText(L10n.tr(name), tx, ty - 5f.dp, text)
        text.textSize = 12.5f.dp
        text.color = if (active) Ui.ACCENT else Ui.TEXT
        canvas.drawText(value, tx, ty + 10f.dp, text)
    }

    private fun drawBubble(canvas: Canvas, b: Bubble) {
        val hot = b === hotBubble
        val r = if (hot) bubbleR + 3f.dp else bubbleR
        paint.color = when {
            hot -> Ui.ACCENT
            b.item.active -> Ui.PANEL_ROW_ACTIVE
            else -> 0xFF2B2C33.toInt()
        }
        canvas.drawCircle(b.x, b.y, r, paint)
        if (b.item.active && !hot) {
            line.color = Ui.ACCENT; line.strokeWidth = 2f.dp
            canvas.drawCircle(b.x, b.y, r, line)
        }
        val icon = b.item.icon
        val pv = b.item.preview
        if (icon != null) {
            IconPainter.draw(canvas, icon, b.x, b.y, r * 1.0f, if (b.item.active && !hot) Ui.ACCENT else Ui.TEXT)
        } else if (pv != null) {
            canvas.save()
            clip.reset(); clip.addCircle(b.x, b.y, r - 2f.dp, Path.Direction.CW)
            canvas.clipPath(clip)
            // the stroke is drawn in light ink, so it sits on a dark bubble
            paint.color = if (hot) 0xFF2F5FB8.toInt() else 0xFF34343C.toInt()
            canvas.drawCircle(b.x, b.y, r, paint)
            val w = r * 2.6f
            val h = w * pv.height / pv.width
            canvas.drawBitmap(pv, null, RectF(b.x - w / 2, b.y - h / 2, b.x + w / 2, b.y + h / 2), null)
            canvas.restore()
            if (hot) { line.color = Ui.ACCENT; line.strokeWidth = 3f.dp; canvas.drawCircle(b.x, b.y, r, line) }
        }
    }

    private fun drawTooltip(canvas: Canvas) {
        val label = hotBubble?.item?.label?.let { L10n.tr(it) } ?: when {
            hotSwatch != null && hotSwatch?.color == null -> L10n.tr("Save color to palette")
            else -> null
        } ?: return
        text.textSize = 13f.dp
        text.color = Color.WHITE
        val w = text.measureText(label) + 28f.dp
        val y = cy - rBase - 22f.dp
        paint.color = 0xF0101013.toInt()
        canvas.drawRoundRect(RectF(cx - w / 2, y - 16f.dp, cx + w / 2, y + 16f.dp), 16f.dp, 16f.dp, paint)
        canvas.drawText(label, cx, y + 4.5f.dp, text)
    }
}
