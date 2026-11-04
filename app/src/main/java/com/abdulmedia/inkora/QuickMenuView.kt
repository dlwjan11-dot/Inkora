package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Radial quick menu around the pen (S Pen button double-click or hold).
 *  - center: current color — tap it to turn the menu into a color wheel
 *  - inner ring: tools
 *  - upper arc: last used brushes
 *  - lower arc: your saved colors + "Save"
 * It stays open until you choose something or tap outside it.
 */
class QuickMenuView(context: Context) : View(context) {

    class Item(
        val label: String,
        val icon: Icon? = null,
        val color: Int? = null,
        val preview: Bitmap? = null,
        val active: Boolean = false,
        val action: () -> Unit
    )

    private class Slot(val item: Item, val r0: Float, val r1: Float, val start: Float, val sweep: Float)

    var isOpen = false
        private set
    /** live color changes from the color wheel */
    var onColor: ((Int) -> Unit)? = null
    /** "+ Save" in the color wheel */
    var onSaveColor: ((Int) -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    /** center tapped: open the big color wheel there instead of the small one */
    var onCenter: ((Float, Float) -> Unit)? = null

    private var cx = 0f
    private var cy = 0f
    private var openX = 0f
    private var openY = 0f
    private val slots = ArrayList<Slot>()
    private var center: Item? = null
    private var hot: Item? = null

    // color wheel mode
    private var colorMode = false
    private val hsv = FloatArray(3)
    private var downPill = -1

    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT
        textAlign = Paint.Align.CENTER
        textSize = 11f.dp
    }
    private val path = Path()

    private val rCenter = 38f.dp
    private val rInner = 104f.dp
    private val rOuter = 166f.dp
    private val gap = 5f.dp

    fun open(x: Float, y: Float, centerItem: Item, tools: List<Item>, brushes: List<Item>, colors: List<Item>) {
        val margin = rOuter + 8f.dp
        cx = x.coerceIn(margin, (width - margin).coerceAtLeast(margin))
        cy = y.coerceIn(margin, (height - margin).coerceAtLeast(margin))
        openX = x; openY = y
        center = centerItem
        setColor(centerItem.color ?: Color.BLACK, notify = false)
        slots.clear()
        if (tools.isNotEmpty()) {
            val sweep = 360f / tools.size
            tools.forEachIndexed { i, it -> slots.add(Slot(it, rCenter + gap, rInner, -90f - sweep / 2 + i * sweep, sweep)) }
        }
        if (brushes.isNotEmpty()) {
            val sweep = 150f / brushes.size
            brushes.forEachIndexed { i, it -> slots.add(Slot(it, rInner + gap, rOuter, 195f + i * sweep, sweep)) }
        }
        if (colors.isNotEmpty()) {
            val sweep = 150f / colors.size
            colors.forEachIndexed { i, it -> slots.add(Slot(it, rInner + gap, rOuter, 15f + i * sweep, sweep)) }
        }
        hot = null
        colorMode = false
        isOpen = true
        visibility = VISIBLE
        invalidate()
    }

    /** opens straight into the color picker (used by the big wheel's HSL button) */
    fun openColor(x: Float, y: Float, current: Int, hslMode: Boolean) {
        open(x, y, Item("Color", color = current) {}, emptyList(), emptyList(), emptyList())
        colorMode = true
        wheel = if (hslMode) Wheel.HSL else Wheel.SWATCHES
        cy = cy.coerceAtMost(height - rOuter - 52f.dp)
        invalidate()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        hot = null
        colorMode = false
        visibility = GONE
        onClosed?.invoke()
    }

    fun hoverAt(x: Float, y: Float) {
        if (!isOpen || colorMode) return
        val h = hit(x, y)
        if (h !== hot) { hot = h; invalidate() }
    }

    /**
     * Pen button released after a hold: choose the item under the pen — but only if the pen
     * actually moved to it. Releasing where the menu opened keeps the menu open.
     */
    fun releaseAt(x: Float, y: Float) {
        if (!isOpen || colorMode) return
        if (hypot(x - openX, y - openY) < 28f.dp) return
        val h = hit(x, y)
        if (h != null && h !== center) choose(h)
    }

    private fun choose(item: Item) {
        close()
        item.action()
    }

    private fun hit(x: Float, y: Float): Item? {
        val d = hypot(x - cx, y - cy)
        if (d <= rCenter) return center
        val a = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
        for (s in slots) {
            if (d < s.r0 || d > s.r1) continue
            var rel = a - s.start
            while (rel < 0) rel += 360f
            while (rel >= 360f) rel -= 360f
            if (rel <= s.sweep) return s.item
        }
        return null
    }

    // ---------------- color wheel (Swatches + HSL modes) ----------------

    private enum class Wheel { SWATCHES, HSL }
    private var wheel = Wheel.SWATCHES
    /** HSL state (hue kept separately so grays don't lose it) */
    private var hue = 0f
    private var sat = 0.8f
    private var light = 0.5f
    private var color = Color.BLACK

    private val rChipOut = rOuter
    private val rChipIn = 121f.dp
    private val rGrayOut = 117f.dp
    private val rGrayIn = 86f.dp
    private val rToneOut = 82f.dp
    private val rToneIn = 36f.dp

    private fun pillY() = cy + rOuter + 30f.dp
    private fun pill(i: Int): RectF {
        val w = 70f.dp; val gapX = 6f.dp
        val total = 4 * w + 3 * gapX
        val x = cx - total / 2 + i * (w + gapX)
        return RectF(x, pillY() - 15f.dp, x + w, pillY() + 15f.dp)
    }

    private fun setColor(c: Int, notify: Boolean = true) {
        color = c or 0xFF000000.toInt()
        val hsl = toHsl(color)
        if (hsl[1] > 0.02f) hue = hsl[0]
        sat = hsl[1]; light = hsl[2]
        Color.colorToHSV(color, hsv)
        if (notify) onColor?.invoke(color)
        invalidate()
    }

    /** angle 0..360 measured clockwise from the top */
    private fun topAngle(x: Float, y: Float): Float {
        var a = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat() + 90f
        while (a < 0) a += 360f
        while (a >= 360f) a -= 360f
        return a
    }

    // ---- swatch rings ----

    private fun chipColor(band: Int, i: Int): Int {
        val h = i * 15f
        return when (band) {
            0 -> Color.HSVToColor(floatArrayOf(h, 0.32f, 1f))     // outer: light tints
            1 -> Color.HSVToColor(floatArrayOf(h, 0.85f, 0.95f))  // middle: pure
            else -> Color.HSVToColor(floatArrayOf(h, 0.9f, 0.55f)) // inner: deep shades
        }
    }

    private fun grayColor(i: Int): Int {
        val step = i % 8
        val v = 0.96f - step * 0.11f
        return when (i / 8) {
            0 -> Color.HSVToColor(floatArrayOf(215f, 0.14f, v))  // cool grays
            1 -> Color.HSVToColor(floatArrayOf(35f, 0.16f, v))   // warm grays
            else -> Color.HSVToColor(floatArrayOf(0f, 0f, v))    // neutral grays
        }
    }

    private fun toneColor(i: Int): Int = when (i) {
        0 -> Color.WHITE
        11 -> Color.BLACK
        in 1..5 -> Color.HSVToColor(floatArrayOf(hue, 0.12f + (i - 1) * 0.19f, 1f))
        else -> Color.HSVToColor(floatArrayOf(hue, 0.9f, 1f - (i - 5) * 0.14f))
    }

    private fun swatchAt(x: Float, y: Float): Int? {
        val d = hypot(x - cx, y - cy)
        val a = topAngle(x, y)
        return when {
            d in rChipIn..rChipOut -> {
                val band = ((rChipOut - d) / ((rChipOut - rChipIn) / 3f)).toInt().coerceIn(0, 2)
                chipColor(band, (a / 15f).toInt().coerceIn(0, 23))
            }
            d in rGrayIn..rGrayOut -> grayColor((a / 15f).toInt().coerceIn(0, 23))
            d in rToneIn..rToneOut -> toneColor((a / 30f).toInt().coerceIn(0, 11))
            else -> null
        }
    }

    private fun drawRingSectors(canvas: Canvas, r0: Float, r1: Float, count: Int, colorOf: (Int) -> Int) {
        val sweep = 360f / count
        val outer = RectF(cx - r1, cy - r1, cx + r1, cy + r1)
        val inner = RectF(cx - r0, cy - r0, cx + r0, cy + r0)
        for (i in 0 until count) {
            val start = -90f + i * sweep + 0.8f
            val sw = sweep - 1.6f
            path.reset()
            path.arcTo(outer, start, sw, true)
            path.arcTo(inner, start + sw, -sw)
            path.close()
            val c = colorOf(i)
            segPaint.color = c
            canvas.drawPath(path, segPaint)
            if (c == color) {
                ringPaint.color = if (Color.luminance(c) > 0.6f) Color.BLACK else Color.WHITE
                ringPaint.strokeWidth = 2.5f.dp
                canvas.drawPath(path, ringPaint)
            }
        }
    }

    private fun drawSwatches(canvas: Canvas) {
        val band = (rChipOut - rChipIn) / 3f
        for (b in 0 until 3) {
            drawRingSectors(canvas, rChipOut - (b + 1) * band + 0.6f.dp, rChipOut - b * band - 0.6f.dp, 24) { chipColor(b, it) }
        }
        drawRingSectors(canvas, rGrayIn, rGrayOut, 24) { grayColor(it) }
        drawRingSectors(canvas, rToneIn, rToneOut, 12) { toneColor(it) }
        segPaint.color = color
        canvas.drawCircle(cx, cy, rToneIn - 4f.dp, segPaint)
        ringPaint.color = 0xEEFFFFFF.toInt(); ringPaint.strokeWidth = 2f.dp
        canvas.drawCircle(cx, cy, rToneIn - 4f.dp, ringPaint)
    }

    // ---- HSL ----

    private val satStart = 200f   // canvas degrees (0 = right, clockwise)
    private val lightStart = 20f
    private val arcSweep = 140f
    private val rArc = 101f.dp

    private fun drawHsl(canvas: Canvas) {
        val rm = (rChipIn + rChipOut) / 2
        // canvas angle θ shows hue θ + 90 (hue 0 sits at 12 o'clock)
        val hues = IntArray(13) { Color.HSVToColor(floatArrayOf((it * 30f + 90f) % 360f, 1f, 1f)) }
        // SweepGradient starts at 3 o'clock; our hue 0 is at 12 o'clock
        val sg = SweepGradient(cx, cy, hues, null)
        ringPaint.shader = sg
        ringPaint.strokeWidth = rChipOut - rChipIn
        canvas.drawCircle(cx, cy, rm, ringPaint)
        ringPaint.shader = null
        handle(canvas, hue - 90f, rm, (rChipOut - rChipIn) / 2 - 4f.dp)

        // saturation (top arc) and lightness (bottom arc) as curved sliders
        val arcRect = RectF(cx - rArc, cy - rArc, cx + rArc, cy + rArc)
        val steps = 28
        segPaint.style = Paint.Style.STROKE
        segPaint.strokeWidth = 18f.dp
        segPaint.strokeCap = Paint.Cap.BUTT
        for (k in 0 until steps) {
            val t = k / (steps - 1f)
            segPaint.color = fromHsl(hue, t, light.coerceIn(0.25f, 0.75f))
            canvas.drawArc(arcRect, satStart + t * arcSweep, arcSweep / steps + 0.6f, false, segPaint)
            segPaint.color = fromHsl(hue, sat.coerceAtLeast(0.15f), 1f - t)
            canvas.drawArc(arcRect, lightStart + t * arcSweep, arcSweep / steps + 0.6f, false, segPaint)
        }
        segPaint.style = Paint.Style.FILL
        handle(canvas, satStart + sat * arcSweep, rArc, 10f.dp)
        handle(canvas, lightStart + (1f - light) * arcSweep, rArc, 10f.dp)
        textPaint.textSize = 9.5f.dp
        textPaint.color = Ui.TEXT_DIM
        canvas.drawText("SATURATION", cx, cy - rArc + 30f.dp, textPaint)
        canvas.drawText("LIGHTNESS", cx, cy + rArc - 22f.dp, textPaint)

        segPaint.color = color
        canvas.drawCircle(cx, cy, 44f.dp, segPaint)
        ringPaint.color = 0xEEFFFFFF.toInt(); ringPaint.strokeWidth = 2f.dp
        canvas.drawCircle(cx, cy, 44f.dp, ringPaint)
        textPaint.color = if (Color.luminance(color) > 0.5f) Color.BLACK else Color.WHITE
        textPaint.textSize = 11f.dp
        canvas.drawText(String.format("#%06X", color and 0xFFFFFF), cx, cy + 4f.dp, textPaint)
        textPaint.color = Ui.TEXT
    }

    private fun handle(canvas: Canvas, canvasDeg: Float, r: Float, size: Float) {
        val a = Math.toRadians(canvasDeg.toDouble())
        val x = cx + (cos(a) * r).toFloat(); val y = cy + (sin(a) * r).toFloat()
        ringPaint.color = Color.WHITE
        ringPaint.strokeWidth = 3.5f.dp
        canvas.drawCircle(x, y, size, ringPaint)
        ringPaint.color = 0x66000000
        ringPaint.strokeWidth = 1f.dp
        canvas.drawCircle(x, y, size + 2.5f.dp, ringPaint)
    }

    private enum class Zone { NONE, HUE, SAT, LIGHT, SWATCH }
    private var zone = Zone.NONE

    private fun canvasAngle(x: Float, y: Float): Float {
        var a = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
        if (a < 0) a += 360f
        return a
    }

    private fun touchHsl(x: Float, y: Float, down: Boolean) {
        val d = hypot(x - cx, y - cy)
        val a = canvasAngle(x, y)
        if (down) zone = when {
            d >= rChipIn - 6f.dp && d <= rChipOut + 16f.dp -> Zone.HUE
            d in (rArc - 22f.dp)..(rArc + 22f.dp) && a >= satStart - 10f && a <= satStart + arcSweep + 10f -> Zone.SAT
            d in (rArc - 22f.dp)..(rArc + 22f.dp) && a >= lightStart - 10f && a <= lightStart + arcSweep + 10f -> Zone.LIGHT
            else -> Zone.NONE
        }
        when (zone) {
            Zone.HUE -> hue = topAngle(x, y)
            Zone.SAT -> sat = ((a - satStart) / arcSweep).coerceIn(0f, 1f)
            Zone.LIGHT -> light = 1f - ((a - lightStart) / arcSweep).coerceIn(0f, 1f)
            else -> return
        }
        color = fromHsl(hue, sat, light)
        Color.colorToHSV(color, hsv)
        onColor?.invoke(color)
        invalidate()
    }

    private fun drawPills(canvas: Canvas) {
        val labels = listOf("Swatches", "HSL", "+ Save", "Done")
        for (i in 0 until 4) {
            val r = pill(i)
            segPaint.color = when {
                i == 0 && wheel == Wheel.SWATCHES -> Ui.PANEL_ROW_ACTIVE
                i == 1 && wheel == Wheel.HSL -> Ui.PANEL_ROW_ACTIVE
                i == 3 -> Ui.ACCENT
                else -> 0xF0222227.toInt()
            }
            canvas.drawRoundRect(r, 15f.dp, 15f.dp, segPaint)
            textPaint.textSize = 11.5f.dp
            textPaint.color = Color.WHITE
            canvas.drawText(labels[i], r.centerX(), r.centerY() + 4f.dp, textPaint)
        }
        textPaint.color = Ui.TEXT
    }

    private fun pillAt(x: Float, y: Float): Int = (0 until 4).firstOrNull { pill(it).contains(x, y) } ?: -1

    private fun onPill(i: Int) {
        when (i) {
            0 -> { wheel = Wheel.SWATCHES; invalidate() }
            1 -> { wheel = Wheel.HSL; invalidate() }
            2 -> { onSaveColor?.invoke(color); close() }
            3 -> close()
        }
    }

    // ---- HSL math ----

    private fun toHsl(c: Int): FloatArray {
        val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        val l = (mx + mn) / 2
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val dd = mx - mn
        val s = if (l > 0.5f) dd / (2 - mx - mn) else dd / (mx + mn)
        var h = when (mx) {
            r -> (g - b) / dd + (if (g < b) 6 else 0)
            g -> (b - r) / dd + 2
            else -> (r - g) / dd + 4
        } * 60f
        if (h >= 360f) h -= 360f
        return floatArrayOf(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float): Int {
        val c = (1 - Math.abs(2 * l - 1)) * s
        val hp = (h % 360f) / 60f
        val x = c * (1 - Math.abs(hp % 2 - 1))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = l - c / 2
        fun ch(v: Float) = ((v + m) * 255).toInt().coerceIn(0, 255)
        return Color.rgb(ch(r1), ch(g1), ch(b1))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isOpen) return false
        if (colorMode) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downPill = pillAt(e.x, e.y)
                    if (downPill >= 0) return true
                    if (hypot(e.x - cx, e.y - cy) > rOuter + 24f.dp) { close(); return true }
                    if (wheel == Wheel.HSL) touchHsl(e.x, e.y, true)
                    else zone = Zone.SWATCH
                }
                MotionEvent.ACTION_MOVE -> if (wheel == Wheel.HSL && downPill < 0) touchHsl(e.x, e.y, false)
                MotionEvent.ACTION_UP -> {
                    if (downPill >= 0) {
                        if (pillAt(e.x, e.y) == downPill) onPill(downPill)
                    } else if (wheel == Wheel.SWATCHES) {
                        swatchAt(e.x, e.y)?.let { setColor(it) }
                    }
                    zone = Zone.NONE
                    downPill = -1
                }
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> hoverAt(e.x, e.y)
            MotionEvent.ACTION_UP -> {
                val h = hit(e.x, e.y)
                when {
                    h === center && onCenter != null -> {
                        val x = cx; val y = cy
                        close()
                        onCenter?.invoke(x, y)
                    }
                    h === center -> {
                        colorMode = true
                        // keep the pills on screen
                        cy = cy.coerceAtMost(height - rOuter - 52f.dp)
                        invalidate()
                    }
                    h != null -> choose(h)
                    else -> close()
                }
            }
            MotionEvent.ACTION_CANCEL -> close()
        }
        return true
    }

    // ---------------- drawing ----------------

    override fun onDraw(canvas: Canvas) {
        if (!isOpen) return
        segPaint.shader = null
        segPaint.color = 0x66000000
        canvas.drawCircle(cx, cy, rOuter + 12f.dp, segPaint)
        if (colorMode) {
            if (wheel == Wheel.SWATCHES) drawSwatches(canvas) else drawHsl(canvas)
            drawPills(canvas)
            return
        }

        for (s in slots) drawSlot(canvas, s)
        val c = center
        if (c != null) {
            segPaint.color = c.color ?: Ui.PANEL
            canvas.drawCircle(cx, cy, rCenter, segPaint)
            ringPaint.color = if (hot === c) Ui.ACCENT else 0xEEFFFFFF.toInt()
            ringPaint.strokeWidth = 3f.dp
            canvas.drawCircle(cx, cy, rCenter, ringPaint)
            textPaint.textSize = 9.5f.dp
            textPaint.color = if (Color.luminance(c.color ?: 0) > 0.5f) Color.BLACK else Color.WHITE
            canvas.drawText("COLOR", cx, cy + 4f.dp, textPaint)
            textPaint.color = Ui.TEXT
        }
    }

    private fun drawSlot(canvas: Canvas, s: Slot) {
        val isHot = hot === s.item
        val padAngle = 1.2f
        path.reset()
        val outer = RectF(cx - s.r1, cy - s.r1, cx + s.r1, cy + s.r1)
        val inner = RectF(cx - s.r0, cy - s.r0, cx + s.r0, cy + s.r0)
        path.arcTo(outer, s.start + padAngle, s.sweep - 2 * padAngle, true)
        path.arcTo(inner, s.start + s.sweep - padAngle, -(s.sweep - 2 * padAngle))
        path.close()
        segPaint.color = when {
            isHot -> Ui.ACCENT
            s.item.active -> Ui.PANEL_ROW_ACTIVE
            else -> 0xF0222227.toInt()
        }
        canvas.drawPath(path, segPaint)

        val mid = Math.toRadians((s.start + s.sweep / 2).toDouble())
        val rm = (s.r0 + s.r1) / 2
        val px = cx + (cos(mid) * rm).toFloat()
        val py = cy + (sin(mid) * rm).toFloat()
        val item = s.item
        when {
            item.color != null -> {
                // swatch size fits the slot when there are many colors
                val r = min(15f.dp, (Math.toRadians(s.sweep.toDouble()) * rm / 2.4).toFloat())
                segPaint.color = item.color
                canvas.drawCircle(px, py, r, segPaint)
                ringPaint.color = 0xCCFFFFFF.toInt()
                ringPaint.strokeWidth = 1.5f.dp
                canvas.drawCircle(px, py, r, ringPaint)
            }
            item.icon != null -> {
                IconPainter.draw(canvas, item.icon, px, py - 7f.dp, 22f.dp, Ui.TEXT)
                textPaint.textSize = 9.5f.dp
                canvas.drawText(item.label, px, py + 16f.dp, textPaint)
            }
            else -> {
                val pv = item.preview
                if (pv != null) {
                    val w = min(70f.dp, (s.r1 - s.r0) * 1.4f)
                    val h = w * pv.height / pv.width
                    canvas.drawBitmap(pv, null, RectF(px - w / 2, py - h / 2 - 6f.dp, px + w / 2, py + h / 2 - 6f.dp), null)
                }
                textPaint.textSize = 10.5f.dp
                val label = if (item.label.length > 13) item.label.take(12) + "…" else item.label
                canvas.drawText(label, px, py + 16f.dp, textPaint)
            }
        }
    }
}
