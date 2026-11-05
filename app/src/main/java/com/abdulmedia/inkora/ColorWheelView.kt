package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Big spinning swatch wheel (original Inkora palette):
 *  hub = current color + Save / HSL / Done,
 *  two rings of grays (neutral, cool + warm),
 *  24 color families as fans of chips: dark inside → light outside, vivid + soft columns.
 * Drag to spin it (it keeps spinning when flicked), tap a chip to pick it.
 */
class ColorWheelView(context: Context) : View(context) {

    var onColor: ((Int) -> Unit)? = null
    var onSave: ((Int) -> Unit)? = null
    var onHsl: ((Float, Float, Int) -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    var isOpen = false
        private set

    private var cx = 0f
    private var cy = 0f
    /** rotation of the whole wheel, degrees */
    private var rot = 0f
    private var color = Color.BLACK
    private var selected: Chip? = null

    private class Chip(val ring: Int, val family: Int, val col: Int, val row: Int, val color: Int, val label: String,
                       val r0: Float, val r1: Float, val a0: Float, val sweep: Float, val short: String = "")

    private val chips = ArrayList<Chip>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()

    private val rHub = 76f.dp
    private val rowH = 34f.dp

    /** one family every 15° of hue: (name, short code) */
    private val families = listOf(
        "Red" to "RD", "Scarlet" to "SC", "Orange" to "OR", "Amber" to "AM", "Yellow" to "YE", "Lemon" to "LM",
        "Lime" to "LI", "Leaf" to "LF", "Green" to "GR", "Emerald" to "EM", "Jade" to "JD", "Mint" to "MT",
        "Teal" to "TL", "Aqua" to "AQ", "Sky" to "SK", "Azure" to "AZ", "Blue" to "BL", "Cobalt" to "CB",
        "Indigo" to "IN", "Violet" to "VI", "Magenta" to "MG", "Fuchsia" to "FU", "Pink" to "PK", "Rose" to "RS")

    init {
        build()
    }

    // =========================================================
    // palette
    // =========================================================

    private fun hsl(h: Float, s: Float, l: Float): Int {
        val c = (1 - abs(2 * l - 1)) * s
        val hp = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1 - abs(hp % 2 - 1))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = l - c / 2
        fun ch(v: Float) = ((v + m) * 255).toInt().coerceIn(0, 255)
        return Color.rgb(ch(r1), ch(g1), ch(b1))
    }

    private fun build() {
        chips.clear()
        // ring 0: neutral grays, ring 1: cool (first half) + warm (second half) grays
        val g0 = rHub + 6f.dp
        for (i in 0 until 16) {
            val l = 0.97f - i * 0.058f
            chips.add(Chip(0, -1, 0, i, hsl(0f, 0f, l), "N$i", g0, g0 + 26f.dp, i * 22.5f, 22.5f))
        }
        val g1 = g0 + 30f.dp
        for (i in 0 until 16) {
            val warm = i >= 8
            val k = i % 8
            val l = 0.93f - k * 0.1f
            val c = if (warm) hsl(35f, 0.16f, l) else hsl(215f, 0.14f, l)
            chips.add(Chip(1, -1, 0, i, c, (if (warm) "W" else "C") + k, g1, g1 + 26f.dp, i * 22.5f, 22.5f))
        }
        // color families: fans of chips
        val start = g1 + 34f.dp
        for (f in 0 until 24) {
            val hue = f * 15f
            // families have different lengths, like a real marker collection
            val rows = 5 + ((f * 7 + 3) % 4)
            for (col in 0 until 2) {
                for (r in 0 until rows) {
                    val t = r / (rows - 1f)
                    val l = 0.26f + t * 0.66f
                    val s = if (col == 0) 0.88f - t * 0.2f else 0.42f - t * 0.12f
                    val r0 = start + r * rowH
                    val (name, code) = families[f]
                    chips.add(Chip(2, f, col, r, hsl(hue, s, l), "$name ${if (col == 0) "" else "Soft "}${r + 1}",
                        r0, r0 + rowH - 2f.dp, f * 15f + col * 7.5f, 7.5f,
                        short = code + (if (col == 0) "" else "s") + (r + 1)))
                }
            }
        }
    }

    // =========================================================
    // open / close
    // =========================================================

    fun open(x: Float, y: Float, current: Int) {
        cx = x.coerceIn(rHub + 40f.dp, (width - rHub - 40f.dp).coerceAtLeast(rHub + 40f.dp))
        cy = y.coerceIn(rHub + 60f.dp, (height - rHub - 60f.dp).coerceAtLeast(rHub + 60f.dp))
        color = current or 0xFF000000.toInt()
        selected = chips.firstOrNull { it.color == color }
        // turn the wheel so the current color's family faces the open side of the screen
        selected?.let { rot = towardScreen() - (it.a0 + it.sweep / 2) }
        isOpen = true
        visibility = VISIBLE
        invalidate()
    }

    /** angle (wheel space 0 = up, clockwise) pointing to the roomiest side of the screen */
    private fun towardScreen(): Float {
        val dx = width / 2f - cx
        val dy = height / 2f - cy
        if (abs(dx) < 1f && abs(dy) < 1f) return 90f
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        flinging = false
        visibility = GONE
        onClosed?.invoke()
    }

    // =========================================================
    // drawing
    // =========================================================

    override fun onDraw(canvas: Canvas) {
        if (!isOpen) return
        canvas.drawColor(0x8C000000.toInt())
        for (c in chips) drawChip(canvas, c)
        drawHub(canvas)
    }

    private fun drawChip(canvas: Canvas, c: Chip) {
        val start = c.a0 + rot - 90f
        val pad = if (c.ring == 2) 0.35f else 0.6f
        val outer = RectF(cx - c.r1, cy - c.r1, cx + c.r1, cy + c.r1)
        val inner = RectF(cx - c.r0, cy - c.r0, cx + c.r0, cy + c.r0)
        path.reset()
        path.arcTo(outer, start + pad, c.sweep - 2 * pad, true)
        path.arcTo(inner, start + c.sweep - pad, -(c.sweep - 2 * pad))
        path.close()
        paint.color = c.color
        canvas.drawPath(path, paint)

        // label, written along the radius
        if (c.ring == 2) {
            val mid = start + c.sweep / 2
            val rm = (c.r0 + c.r1) / 2
            canvas.save()
            // the x axis now points outward, so the text runs along the radius
            canvas.rotate(mid, cx, cy)
            // keep it readable on the left half of the wheel
            if (cos(Math.toRadians(mid.toDouble())) < 0) canvas.rotate(180f, cx + rm, cy)
            text.textSize = 8.5f.dp
            text.color = if (Color.luminance(c.color) > 0.45f) 0xCC000000.toInt() else 0xDDFFFFFF.toInt()
            canvas.drawText(c.short, cx + rm, cy + 3f.dp, text)
            canvas.restore()
        }

        if (c === selected) {
            stroke.color = if (Color.luminance(c.color) > 0.6f) Color.BLACK else Color.WHITE
            stroke.strokeWidth = 2.5f.dp
            canvas.drawPath(path, stroke)
            val mid = Math.toRadians((start + c.sweep / 2).toDouble())
            val rm = c.r1 - 7f.dp
            text.textSize = 12f.dp
            text.color = stroke.color
            canvas.drawText("★", cx + (cos(mid) * rm).toFloat(), cy + (sin(mid) * rm).toFloat() + 4f.dp, text)
        }
    }

    private fun hubButton(i: Int): Pair<Float, Float> {
        // three round buttons under the color disc
        val y = cy + 34f.dp
        return (cx + (i - 1) * 40f.dp) to y
    }

    private fun drawHub(canvas: Canvas) {
        paint.color = 0xFAF4F4F6.toInt()
        canvas.drawCircle(cx, cy, rHub, paint)
        stroke.color = 0x22000000
        stroke.strokeWidth = 1f.dp
        canvas.drawCircle(cx, cy, rHub, stroke)
        // current color
        paint.color = color
        canvas.drawCircle(cx, cy - 14f.dp, 30f.dp, paint)
        text.textSize = 9.5f.dp
        text.color = 0xFF55555C.toInt()
        canvas.drawText(selected?.label ?: String.format("#%06X", color and 0xFFFFFF), cx, cy - 52f.dp, text)

        val labels = listOf("+", "HSL", "✓")
        for (i in 0 until 3) {
            val (x, y) = hubButton(i)
            paint.color = if (i == 2) Ui.ACCENT else 0xFFE4E4EA.toInt()
            canvas.drawCircle(x, y, 16f.dp, paint)
            text.color = if (i == 2) Color.WHITE else 0xFF22232A.toInt()
            text.textSize = if (i == 1) 9.5f.dp else 15f.dp
            canvas.drawText(labels[i], x, y + (if (i == 1) 3.5f else 5f).dp, text)
        }
    }

    // =========================================================
    // touch: spin + tap
    // =========================================================

    private var downX = 0f
    private var downY = 0f
    private var lastAngle = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null
    private var flinging = false
    private var spinSpeed = 0f // degrees per frame
    private var lastAngleTime = 0L
    private var angularVelocity = 0f // deg / ms

    private fun angleAt(x: Float, y: Float) = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()

    private fun chipAt(x: Float, y: Float): Chip? {
        val d = hypot(x - cx, y - cy)
        var a = angleAt(x, y) + 90f - rot
        a = ((a % 360f) + 360f) % 360f
        return chips.firstOrNull { d >= it.r0 && d <= it.r1 && a >= it.a0 && a < it.a0 + it.sweep }
    }

    private fun maxRadius() = chips.maxOf { it.r1 }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isOpen) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                flinging = false
                downX = e.x; downY = e.y
                dragging = false
                lastAngle = angleAt(e.x, e.y)
                lastAngleTime = e.eventTime
                angularVelocity = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.x - downX, e.y - downY) > 8f.dp && hypot(e.x - cx, e.y - cy) > rHub) dragging = true
                if (dragging) {
                    val a = angleAt(e.x, e.y)
                    var d = a - lastAngle
                    if (d > 180f) d -= 360f
                    if (d < -180f) d += 360f
                    rot += d
                    val dt = max(1L, e.eventTime - lastAngleTime)
                    angularVelocity = angularVelocity * 0.5f + (d / dt) * 0.5f
                    lastAngle = a
                    lastAngleTime = e.eventTime
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    // keep spinning, slowing down
                    spinSpeed = (angularVelocity * 16f).coerceIn(-25f, 25f)
                    if (abs(spinSpeed) > 0.4f) { flinging = true; postOnAnimation(spin) }
                } else tap(e.x, e.y)
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private val spin: Runnable = object : Runnable {
        override fun run() {
            if (!flinging || !isOpen) return
            rot += spinSpeed
            spinSpeed *= 0.95f
            invalidate()
            if (abs(spinSpeed) > 0.08f) postOnAnimation(this) else flinging = false
        }
    }

    private fun tap(x: Float, y: Float) {
        // hub buttons
        for (i in 0 until 3) {
            val (bx, by) = hubButton(i)
            if (hypot(x - bx, y - by) < 20f.dp) {
                when (i) {
                    0 -> { onSave?.invoke(color); close() }
                    1 -> { val px = cx; val py = cy; val c = color; close(); onHsl?.invoke(px, py, c) }
                    2 -> close()
                }
                return
            }
        }
        if (hypot(x - cx, y - cy) <= rHub) return
        val c = chipAt(x, y)
        if (c == null) {
            // tap outside the wheel closes it
            if (hypot(x - cx, y - cy) > maxRadius()) close()
            return
        }
        selected = c
        color = c.color
        onColor?.invoke(color)
        invalidate()
    }
}
