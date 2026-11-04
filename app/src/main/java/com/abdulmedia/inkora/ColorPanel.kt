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

/** Color picker: standard color wheel (hue ring + saturation/brightness square), hex, palette and recent colors. */
@SuppressLint("ViewConstructor")
class ColorPanel(
    context: Context,
    initial: Int,
    private val recent: MutableList<Int>,
    private val onWheel: (() -> Unit)? = null,
    private val onColor: (Int) -> Unit
) : LinearLayout(context) {

    private val hsv = FloatArray(3)
    private val disc = ColorDiscView(context, initial)
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
        if (onWheel != null) head.addView(Ui.button(context, "Swatch wheel") { onWheel.invoke() }.apply { textSize = 12f },
            LayoutParams(-2, -2).apply { rightMargin = 8.dp })
        previewOld.color = initial
        previewOld.setOnClickListener { setColor(previewOld.color, true) }
        head.addView(previewOld, LayoutParams(40.dp, 40.dp))
        head.addView(previewNew, LayoutParams(40.dp, 40.dp))
        addView(head)

        disc.onColor = { c -> Color.colorToHSV(c, hsv); emit() }
        disc.onCommit = { commitRecent() }
        addView(disc, LayoutParams(-1, -2).apply { topMargin = 10.dp })

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
        disc.setColor(c)
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
        disc.invalidate()
        if (!hex.hasFocus()) hex.setText(String.format(Locale.US, "%06X", c and 0xFFFFFF))
    }

    private fun parseHex(s: String): Int? {
        val t = s.trim().removePrefix("#")
        if (t.length != 6) return null
        return t.toLongOrNull(16)?.let { (it or 0xFF000000).toInt() }
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
