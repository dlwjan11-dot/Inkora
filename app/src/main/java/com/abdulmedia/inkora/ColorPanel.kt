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
    /** a small picture of the artwork, for "Palette from artwork" */
    private val artwork: (() -> android.graphics.Bitmap?)? = null,
    /** "Palette from photo": the host opens the photo picker */
    private val onPalettePhoto: (() -> Unit)? = null,
    private val onColor: (Int) -> Unit
) : LinearLayout(context) {

    private val hsv = FloatArray(3)
    private val disc = ColorDiscView(context, initial)
    private val previewNew = ColorSwatchView(context)
    private val previewOld = ColorSwatchView(context)
    private val hex = EditText(context)
    private val recentRow = LinearLayout(context)
    private val sliders = ArrayList<HsvSlider>()
    private val paletteGrid = GridLayout(context).apply { columnCount = 10 }
    private val paletteBtn = Ui.button(context, "") {}

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

        val harmBtn = Ui.button(context, "Harmony: ${disc.disc.harmony.label}") {}
        harmBtn.setOnClickListener {
            val pm = android.widget.PopupMenu(context, harmBtn)
            HarmonyMode.values().forEachIndexed { i, m -> pm.menu.add(0, i, i, m.label) }
            pm.setOnMenuItemClickListener { item ->
                disc.disc.harmony = HarmonyMode.values()[item.itemId]
                harmBtn.text = "Harmony: ${disc.disc.harmony.label}"
                disc.invalidate()
                true
            }
            L10n.menu(pm.menu); pm.show()
        }
        addView(harmBtn, LayoutParams(-1, -2).apply { topMargin = 4.dp })

        // Hue / Saturation / Brightness sliders
        for ((ch, label) in listOf(0 to "Hue", 1 to "Saturation", 2 to "Brightness")) {
            addView(Ui.text(context, label, 12f, Ui.TEXT_DIM), LayoutParams(-2, -2).apply { topMargin = if (ch == 0) 10.dp else 2.dp })
            val sl = HsvSlider(context, hsv, ch)
            sl.onChange = { disc.setColor(Color.HSVToColor(hsv)); emit() }
            sl.onCommit = { commitRecent() }
            sliders.add(sl)
            addView(sl, LayoutParams(-1, -2))
        }

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

        // your palettes
        val palHead = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        palHead.addView(Ui.text(context, "Palette", 13f, Ui.TEXT_DIM), LayoutParams(-2, -2).apply { rightMargin = 8.dp })
        paletteBtn.textSize = 13f
        paletteBtn.setOnClickListener { paletteMenu() }
        palHead.addView(paletteBtn, LayoutParams(0, -2, 1f))
        palHead.addView(Ui.button(context, "+ Add color") {
            if (!PaletteStore.addColor(Color.HSVToColor(hsv))) Ui.toast(context, "This palette is full")
            refreshPalette()
        }.apply { textSize = 12.5f }, LayoutParams(-2, -2).apply { leftMargin = 6.dp })
        addView(palHead, LayoutParams(-1, -2).apply { topMargin = 12.dp })
        addView(paletteGrid, LayoutParams(-2, -2).apply { topMargin = 4.dp })
        addView(Ui.text(context, "Tap a color to use it · long-press to remove it", 11f, Ui.TEXT_DIM), LayoutParams(-2, -2).apply { topMargin = 2.dp })
        refreshPalette()

        addView(Ui.text(context, "Recent", 13f, Ui.TEXT_DIM), LayoutParams(-2, -2).apply { topMargin = 10.dp })
        recentRow.orientation = HORIZONTAL
        addView(recentRow, LayoutParams(-1, 30.dp).apply { topMargin = 4.dp })
        refreshRecent()
        sync()
    }

    private fun refreshPalette() {
        val p = PaletteStore.current
        paletteBtn.text = "${L10n.tr(p.name)}  ▾"
        paletteGrid.removeAllViews()
        p.colors.forEachIndexed { i, c ->
            val sw = ColorSwatchView(context)
            sw.color = c
            sw.setOnClickListener { setColor(c, true) }
            sw.setOnLongClickListener {
                val pm = android.widget.PopupMenu(context, sw)
                pm.menu.add(0, 1, 0, "Remove color")
                pm.menu.add(0, 2, 1, "Replace with current color")
                pm.setOnMenuItemClickListener { item ->
                    if (item.itemId == 1) PaletteStore.removeAt(i)
                    else { p.colors[i] = Color.HSVToColor(hsv); PaletteStore.save() }
                    refreshPalette(); true
                }
                L10n.menu(pm.menu); pm.show()
                true
            }
            paletteGrid.addView(sw, GridLayout.LayoutParams().apply { width = 30.dp; height = 30.dp })
        }
        if (p.colors.isEmpty()) paletteGrid.addView(Ui.text(context, "Empty: tap + Add color to fill it", 12f, Ui.TEXT_DIM))
    }

    private fun paletteMenu() {
        val pm = android.widget.PopupMenu(context, paletteBtn)
        PaletteStore.palettes.forEachIndexed { i, p -> pm.menu.add(0, i, i, (if (i == PaletteStore.active) "✓ " else "") + L10n.tr(p.name)) }
        val base = 1000
        pm.menu.add(1, base, 900, "New palette…")
        pm.menu.add(1, base + 1, 901, "New palette from artwork")
        if (onPalettePhoto != null) pm.menu.add(1, base + 2, 902, "New palette from photo…")
        pm.menu.add(1, base + 3, 903, "Rename palette…")
        pm.menu.add(1, base + 4, 904, "Delete palette")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                base -> askName("New palette", "") { PaletteStore.create(it); refreshPalette() }
                base + 1 -> {
                    val b = artwork?.invoke()
                    if (b == null) Ui.toast(context, "Nothing drawn yet")
                    else { PaletteStore.create("From artwork", PaletteStore.extract(b)); b.recycle(); refreshPalette() }
                }
                base + 2 -> onPalettePhoto?.invoke()
                base + 3 -> askName("Rename palette", PaletteStore.current.name) { PaletteStore.current.name = it; PaletteStore.save(); refreshPalette() }
                base + 4 -> {
                    val name = PaletteStore.current.name
                    android.app.AlertDialog.Builder(context)
                        .setMessage(L10n.tr("Delete palette") + ": " + L10n.tr(name) + "?")
                        .setPositiveButton(L10n.tr("Delete")) { _, _ -> PaletteStore.deleteCurrent(); refreshPalette() }
                        .setNegativeButton(L10n.tr("Cancel"), null).show()
                }
                else -> { PaletteStore.select(item.itemId); refreshPalette() }
            }
            true
        }
        L10n.menu(pm.menu); pm.show()
    }

    /** a palette made outside (from a photo) was added */
    fun paletteAdded() = refreshPalette()

    private fun askName(title: String, start: String, done: (String) -> Unit) {
        val input = EditText(context).apply { setText(start); setSingleLine(); setSelection(text.length) }
        android.app.AlertDialog.Builder(context)
            .setTitle(L10n.tr(title))
            .setView(input)
            .setPositiveButton(L10n.tr("OK")) { _, _ -> done(input.text.toString().trim()) }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
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
        sliders.forEach { it.invalidate() }
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
