package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import java.util.Locale

/** HSV color picker: saturation/brightness square, hue strip, hex, palette and recent colors. */
@SuppressLint("ViewConstructor")
class ColorPanel(
    context: Context,
    initial: Int,
    private val recent: MutableList<Int>,
    private val onWheel: (() -> Unit)? = null,
    private val onColor: (Int) -> Unit
) : LinearLayout(context) {

    private val hsv = FloatArray(3)
    private val square = SVSquare(context)
    private val hue = HueStrip(context)
    private val previewNew = ColorSwatchView(context)
    private val previewOld = ColorSwatchView(context)
    private val hex = EditText(context)
    private val recentRow = LinearLayout(context)

    init {
        orientation = VERTICAL
        setPadding(16.dp, 14.dp, 16.dp, 16.dp)
        background = Ui.rounded(Ui.PANEL, 18f)
        Color.colorToHSV(initial, hsv)

        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(context, "Color", 17f, bold = true), LayoutParams(0, -2, 1f))
        if (onWheel != null) head.addView(Ui.button(context, "◐ Wheel") { onWheel.invoke() }.apply { textSize = 12f },
            LayoutParams(-2, -2).apply { rightMargin = 8.dp })
        previewOld.color = initial
        previewOld.setOnClickListener { setColor(previewOld.color, true) }
        head.addView(previewOld, LayoutParams(40.dp, 40.dp))
        head.addView(previewNew, LayoutParams(40.dp, 40.dp))
        addView(head)

        square.onPick = { s, v -> hsv[1] = s; hsv[2] = v; emit() }
        addView(square, LayoutParams(-1, 220.dp).apply { topMargin = 10.dp })
        hue.onPick = { h -> hsv[0] = h; square.invalidate(); emit() }
        addView(hue, LayoutParams(-1, 30.dp).apply { topMargin = 12.dp })

        hex.apply {
            setTextColor(Ui.TEXT)
            textSize = 15f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = Ui.rounded(Ui.PANEL_ROW, 8f)
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            setOnEditorActionListener { _, _, _ ->
                parseHex(text.toString())?.let { setColor(it, true) }
                false
            }
        }
        val hexRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hexRow.addView(Ui.text(context, "Hex  #", 14f, Ui.TEXT_DIM))
        hexRow.addView(hex, LayoutParams(0, -2, 1f))
        addView(hexRow, LayoutParams(-1, -2).apply { topMargin = 12.dp })

        addView(Ui.text(context, "Palette", 13f, Ui.TEXT_DIM), LayoutParams(-2, -2).apply { topMargin = 12.dp })
        val grid = GridLayout(context).apply { columnCount = 10 }
        for (c in PALETTE) {
            val sw = ColorSwatchView(context)
            sw.color = c
            sw.setOnClickListener { setColor(c, true) }
            grid.addView(sw, GridLayout.LayoutParams().apply { width = 30.dp; height = 30.dp })
        }
        addView(grid, LayoutParams(-2, -2).apply { topMargin = 4.dp })

        addView(Ui.text(context, "Recent", 13f, Ui.TEXT_DIM), LayoutParams(-2, -2).apply { topMargin = 10.dp })
        recentRow.orientation = HORIZONTAL
        addView(recentRow, LayoutParams(-1, 30.dp).apply { topMargin = 4.dp })
        refreshRecent()
        sync()
    }

    private fun refreshRecent() {
        recentRow.removeAllViews()
        for (c in recent) {
            val sw = ColorSwatchView(context)
            sw.color = c
            sw.setOnClickListener { setColor(c, true) }
            recentRow.addView(sw, LayoutParams(30.dp, 30.dp))
        }
    }

    fun setColor(c: Int, notify: Boolean) {
        Color.colorToHSV(c, hsv)
        sync()
        if (notify) onColor(Color.HSVToColor(hsv))
    }

    private fun emit() {
        sync()
        onColor(Color.HSVToColor(hsv))
    }

    private fun sync() {
        val c = Color.HSVToColor(hsv)
        previewNew.color = c
        square.invalidate()
        hue.invalidate()
        if (!hex.hasFocus()) hex.setText(String.format(Locale.US, "%06X", c and 0xFFFFFF))
    }

    private fun parseHex(s: String): Int? {
        val t = s.trim().removePrefix("#")
        if (t.length != 6) return null
        return t.toLongOrNull(16)?.let { (it or 0xFF000000).toInt() }
    }

    // ---------- sub views ----------

    inner class SVSquare(ctx: Context) : View(ctx) {
        var onPick: ((Float, Float) -> Unit)? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
            val sat = LinearGradient(0f, 0f, w, 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
            val v = LinearGradient(0f, 0f, 0f, h, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
            p.shader = ComposeShader(sat, v, PorterDuff.Mode.MULTIPLY)
            canvas.drawRoundRect(RectF(0f, 0f, w, h), 10f.dp, 10f.dp, p)
            p.shader = null
            val x = hsv[1] * w
            val y = (1f - hsv[2]) * h
            ring.strokeWidth = 3f.dp
            ring.color = Color.WHITE
            canvas.drawCircle(x, y, 10f.dp, ring)
            ring.strokeWidth = 1f.dp
            ring.color = 0x88000000.toInt()
            canvas.drawCircle(x, y, 12f.dp, ring)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            val s = (e.x / width).coerceIn(0f, 1f)
            val v = 1f - (e.y / height).coerceIn(0f, 1f)
            onPick?.invoke(s, v)
            if (e.actionMasked == MotionEvent.ACTION_UP) commitRecent()
            return true
        }
    }

    inner class HueStrip(ctx: Context) : View(ctx) {
        var onPick: ((Float) -> Unit)? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val colors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
            p.shader = LinearGradient(0f, 0f, w, 0f, colors, null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, p)
            p.shader = null
            val x = (hsv[0] / 360f * w).coerceIn(h / 2, w - h / 2)
            ring.strokeWidth = 3f.dp
            ring.color = Color.WHITE
            canvas.drawCircle(x, h / 2, h / 2 - 2f.dp, ring)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            onPick?.invoke((e.x / width).coerceIn(0f, 0.9999f) * 360f)
            if (e.actionMasked == MotionEvent.ACTION_UP) commitRecent()
            return true
        }
    }

    private fun commitRecent() {
        addRecent(recent, Color.HSVToColor(hsv))
        refreshRecent()
    }

    companion object {
        val PALETTE = intArrayOf(
            0xFF000000.toInt(), 0xFF3A3A3C.toInt(), 0xFF6E6E73.toInt(), 0xFFAEAEB2.toInt(), 0xFFFFFFFF.toInt(),
            0xFF5B3A29.toInt(), 0xFF8B5A3C.toInt(), 0xFFC68B59.toInt(), 0xFFF1C27D.toInt(), 0xFFFFE0BD.toInt(),
            0xFFB71C1C.toInt(), 0xFFE53935.toInt(), 0xFFFF7043.toInt(), 0xFFFFA726.toInt(), 0xFFFFD54F.toInt(),
            0xFF2E7D32.toInt(), 0xFF66BB6A.toInt(), 0xFF26A69A.toInt(), 0xFF00ACC1.toInt(), 0xFF29B6F6.toInt(),
            0xFF1565C0.toInt(), 0xFF3949AB.toInt(), 0xFF5E35B1.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt(),
            0xFF0D1B2A.toInt(), 0xFF1B263B.toInt(), 0xFF415A77.toInt(), 0xFF778DA9.toInt(), 0xFFE0E1DD.toInt()
        )

        fun addRecent(list: MutableList<Int>, c: Int) {
            list.remove(c)
            list.add(0, c)
            while (list.size > 10) list.removeAt(list.size - 1)
        }
    }
}
