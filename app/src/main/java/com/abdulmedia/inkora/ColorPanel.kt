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
    /** the eyedropper button in the header: pick a color from the artwork */
    private val onEyedropper: (() -> Unit)? = null,
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
    private val hexChip = android.widget.TextView(context)
    private val harmonyRow = LinearLayout(context)

    /** the harmony's colors as swatches: each group is lighter, the color, darker */
    private var harmonyBuiltFor: HarmonyMode? = null
    private val harmonySwatches = ArrayList<ColorSwatchView>()

    private fun harmonyVariants(): List<Int> {
        val hues = listOf(hsv[0]) + disc.disc.getHarmonyHues()
        return hues.flatMap { h ->
            listOf(
                floatArrayOf(h, (hsv[1] * 0.6f).coerceIn(0f, 1f), (hsv[2] + (1f - hsv[2]) * 0.45f).coerceIn(0f, 1f)),
                floatArrayOf(h, hsv[1], hsv[2]),
                floatArrayOf(h, (hsv[1] + (1f - hsv[1]) * 0.25f).coerceIn(0f, 1f), (hsv[2] * 0.6f).coerceIn(0f, 1f))
            ).map { Color.HSVToColor(it) }
        }
    }

    private fun refreshHarmony() {
        val m = disc.disc.harmony
        // while a color is dragged only the swatches' colors change
        if (m == harmonyBuiltFor && m != HarmonyMode.NONE) {
            harmonyVariants().forEachIndexed { i, c -> harmonySwatches.getOrNull(i)?.color = c }
            return
        }
        harmonyBuiltFor = m
        harmonySwatches.clear()
        harmonyRow.removeAllViews()
        if (m == HarmonyMode.NONE) {
            harmonyRow.addView(Ui.text(context, "Pick a harmony to see colors that go with this one", 11.5f, Ui.TEXT_DIM))
            return
        }
        val all = harmonyVariants()
        all.chunked(3).forEachIndexed { gi, three ->
            val group = LinearLayout(context).apply {
                orientation = HORIZONTAL
                background = Ui.rounded(Ui.PANEL_ROW, 10f)
                setPadding(2.dp, 2.dp, 2.dp, 2.dp)
            }
            for (c0 in three) {
                val sw = ColorSwatchView(context)
                sw.color = c0
                sw.setOnClickListener { setColor(sw.color, true); commitRecent() }
                harmonySwatches.add(sw)
                group.addView(sw, LayoutParams(34.dp, 34.dp))
            }
            harmonyRow.addView(group, LayoutParams(-2, -2).apply { if (gi > 0) marginStart = 8.dp })
        }
    }

    private val pages = LinkedHashMap<String, LinearLayout>()
    private val tabRow = LinearLayout(context)

    private fun showTab(t: String) {
        lastTab = t
        for ((k, v) in pages) v.visibility = if (k == t) View.VISIBLE else View.GONE
        for (i in 0 until tabRow.childCount) {
            val tv = tabRow.getChildAt(i) as? android.widget.TextView ?: continue
            val on = tv.tag == t
            tv.background = if (on) Ui.rounded(Ui.PANEL, 8f) else null
            tv.setTextColor(if (on) Ui.TEXT else Ui.TEXT_DIM)
            tv.typeface = Fonts.get(if (on) 600 else 400)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(14.dp, 8.dp, 14.dp, 14.dp)
        background = Ui.rounded(Ui.PANEL, 20f)
        Color.colorToHSV(initial, hsv)

        // ---- header: name, eyedropper, swatch wheel ----
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(4.dp, 0, 0, 8.dp) }
        head.addView(Ui.text(context, "Color", 20f, bold = true), LayoutParams(0, -2, 1f))
        fun headButton(icon: Icon, label: String, run: () -> Unit) = IconView(context, icon).apply {
            iconScale = 0.46f
            background = Ui.rounded(Ui.CARD, 12f)
            contentDescription = L10n.tr(label)
            setOnClickListener { run() }
        }
        if (onEyedropper != null) head.addView(headButton(Icon.EYEDROPPER, "Eyedropper") { onEyedropper.invoke() }, LayoutParams(44.dp, 44.dp))
        if (onWheel != null) head.addView(headButton(Icon.WHEEL, "Swatch wheel") { onWheel.invoke() }, LayoutParams(44.dp, 44.dp).apply { marginStart = 6.dp })
        addView(head)
        addView(View(context).apply { setBackgroundColor(Ui.DIVIDER) }, LayoutParams(-1, 1))

        // ---- tabs: Disc, Sliders, Palettes ----
        tabRow.orientation = HORIZONTAL
        tabRow.background = Ui.rounded(Ui.CARD, 10f)
        tabRow.setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        for ((key, label) in listOf("disc" to "Disc", "sliders" to "Sliders", "palettes" to "Palettes")) {
            tabRow.addView(Ui.text(context, label, 13f).apply {
                tag = key
                gravity = Gravity.CENTER
                setPadding(0, 7.dp, 0, 7.dp)
                setOnClickListener { showTab(key) }
            }, LayoutParams(0, -2, 1f))
            pages[key] = LinearLayout(context).apply { orientation = VERTICAL }
        }
        addView(tabRow, LayoutParams(-1, -2).apply { topMargin = 10.dp })
        val discPage = pages["disc"]!!; val sliderPage = pages["sliders"]!!; val palPage = pages["palettes"]!!

        // ---- Disc: the disc, the color and the one before it, hex, harmony ----
        disc.onColor = { c -> Color.colorToHSV(c, hsv); emit() }
        disc.onCommit = { commitRecent() }
        discPage.addView(disc, LayoutParams(-1, -2).apply { topMargin = 8.dp })
        val pair = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        previewOld.color = initial
        previewOld.contentDescription = L10n.tr("The color before")
        previewOld.setOnClickListener { setColor(previewOld.color, true) }
        pair.addView(previewNew, LayoutParams(44.dp, 44.dp))
        pair.addView(previewOld, LayoutParams(34.dp, 34.dp).apply { marginStart = 2.dp })
        pair.addView(View(context), LayoutParams(0, 1, 1f))
        hexChip.apply {
            textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Ui.TEXT)
            setPadding(12.dp, 7.dp, 12.dp, 7.dp)
            background = Ui.rounded(Ui.CARD, 9f)
            contentDescription = L10n.tr("Hex")
            // tap: type a hex code (on the Sliders page)
            setOnClickListener { showTab("sliders"); hex.requestFocus() }
        }
        pair.addView(hexChip)
        discPage.addView(pair, LayoutParams(-1, -2).apply { topMargin = 4.dp })

        // color harmony: pick a kind, its colors (each a little lighter and darker too) are a tap away
        disc.disc.harmony = lastHarmony
        val modes = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun fillModes() {
            modes.removeAllViews()
            for (m in HarmonyMode.values()) {
                val on = m == disc.disc.harmony
                modes.addView(Ui.text(context, m.label, 12.5f, if (on) Ui.ACCENT else Ui.TEXT_DIM, on).apply {
                    gravity = Gravity.CENTER
                    setPadding(12.dp, 7.dp, 12.dp, 7.dp)
                    background = Ui.rounded(if (on) Ui.ACCENT_SOFT else Ui.CARD, 14f)
                    setOnClickListener {
                        disc.disc.harmony = m; lastHarmony = m
                        fillModes(); refreshHarmony(); disc.invalidate()
                    }
                }, LayoutParams(-2, -2).apply { marginEnd = 6.dp })
            }
        }
        fillModes()
        discPage.addView(Ui.caption(context, "Harmony"), LayoutParams(-2, -2).apply { topMargin = 12.dp })
        discPage.addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(modes)
        }, LayoutParams(-1, -2).apply { topMargin = 6.dp })
        harmonyRow.orientation = HORIZONTAL
        harmonyRow.gravity = Gravity.CENTER_VERTICAL
        discPage.addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(harmonyRow)
        }, LayoutParams(-1, -2).apply { topMargin = 6.dp })

        // ---- Sliders: hue, saturation, brightness, hex ----
        for ((ch, label) in listOf(0 to "Hue", 1 to "Saturation", 2 to "Brightness")) {
            sliderPage.addView(Ui.caption(context, label), LayoutParams(-2, -2).apply { topMargin = if (ch == 0) 14.dp else 8.dp })
            val sl = HsvSlider(context, hsv, ch)
            sl.onChange = { disc.setColor(Color.HSVToColor(hsv)); emit() }
            sl.onCommit = { commitRecent() }
            sliders.add(sl)
            sliderPage.addView(sl, LayoutParams(-1, -2))
        }
        hex.apply {
            setTextColor(Ui.TEXT)
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = Ui.rounded(Ui.CARD, 10f)
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            setOnEditorActionListener { _, _, _ ->
                parseHex(text.toString())?.let { setColor(it, true) }
                false
            }
        }
        val hexRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hexRow.addView(Ui.text(context, "Hex  #", 14f, Ui.TEXT_DIM))
        hexRow.addView(hex, LayoutParams(0, -2, 1f))
        sliderPage.addView(hexRow, LayoutParams(-1, -2).apply { topMargin = 14.dp })

        // ---- Palettes ----
        val palHead = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        paletteBtn.textSize = 13f
        paletteBtn.background = Ui.rounded(Ui.CARD, 10f)
        paletteBtn.setOnClickListener { paletteMenu() }
        palHead.addView(paletteBtn, LayoutParams(0, -2, 1f))
        palHead.addView(Ui.button(context, "+ Add color") {
            if (!PaletteStore.addColor(Color.HSVToColor(hsv))) Ui.toast(context, "This palette is full")
            refreshPalette()
        }.apply { textSize = 12.5f; background = Ui.rounded(Ui.CARD, 10f) }, LayoutParams(-2, -2).apply { leftMargin = 6.dp })
        palPage.addView(palHead, LayoutParams(-1, -2).apply { topMargin = 14.dp })
        palPage.addView(paletteGrid, LayoutParams(-2, -2).apply { topMargin = 8.dp })
        palPage.addView(Ui.text(context, "Tap a color to use it · long-press to remove it", 11f, Ui.TEXT_DIM), LayoutParams(-2, -2).apply { topMargin = 4.dp })
        refreshPalette()

        for (pg in pages.values) addView(pg, LayoutParams(-1, -2))

        // ---- History, on every page ----
        addView(Ui.caption(context, "History"), LayoutParams(-2, -2).apply { topMargin = 14.dp })
        recentRow.orientation = HORIZONTAL
        addView(recentRow, LayoutParams(-1, 30.dp).apply { topMargin = 6.dp })
        refreshRecent()
        showTab(lastTab)
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
                val pm = InkMenu(context, sw)
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
        val pm = InkMenu(context, paletteBtn)
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
                    else PaletteMaker.show(context, b, "From artwork") { refreshPalette() }
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
        hexChip.text = String.format(Locale.US, "#%06X", c and 0xFFFFFF)
        refreshHarmony()
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

        /** the page chosen last: disc, sliders or palettes */
        var lastTab = "disc"
        /** the harmony chosen last (kept while the app runs) */
        var lastHarmony = HarmonyMode.COMPLEMENTARY

        fun addRecent(list: MutableList<Int>, c: Int) {
            list.remove(c)
            list.add(0, c)
            while (list.size > 10) list.removeAt(list.size - 1)
        }
    }
}
