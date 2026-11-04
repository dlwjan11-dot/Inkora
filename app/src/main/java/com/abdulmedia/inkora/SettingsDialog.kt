package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Modern, professional multi-category Settings dialog for Inkora.
 */
@SuppressLint("ViewConstructor")
class SettingsDialog(
    context: Context,
    private val prefs: SharedPreferences,
    private val cv: CanvasView,
    private val onLeftHanded: (Boolean) -> Unit,
    private val onToolbarPlacement: (String) -> Unit,
    private val onSlidersPlacement: (String) -> Unit,
    private val onLanguage: (String) -> Unit,
    private val onShowTips: () -> Unit,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    private enum class Category(val title: String, val icon: Icon) {
        CANVAS("Canvas & Project", Icon.PHOTO),
        STYLUS("Stylus & S Pen", Icon.BRUSH),
        INTERFACE("Interface & Layout", Icon.GRID),
        GESTURES("Gestures & Touch", Icon.FOCUS),
        ABOUT("About & Hotkeys", Icon.SETTINGS)
    }

    private var activeCat = Category.CANVAS
    private val catContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val contentScroll = ScrollView(context)
    private val contentBox = LinearLayout(context)

    init {
        background = Ui.rounded(0xF018181C.toInt(), 20f, 0x33FFFFFF, 1)
        elevation = 20f.dp

        val rootBox = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        // Left Navigation Sidebar
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 16.dp, 12.dp, 16.dp)
            background = Ui.rounded(0xFF121215.toInt(), 20f, 0, 0)
        }
        nav.addView(Ui.text(context, "Settings", 18f, bold = true).apply { setPadding(8.dp, 0, 0, 16.dp) })

        Category.entries.forEach { cat ->
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10.dp, 10.dp, 12.dp, 10.dp)
                background = Ui.rounded(if (activeCat == cat) Ui.ACCENT_SOFT else 0, 12f)
                setOnClickListener {
                    activeCat = cat
                    rebuildNav()
                    rebuildContent()
                }
            }
            tab.addView(IconView(context, cat.icon).apply {
                isClickable = false
                tint = if (activeCat == cat) Ui.ACCENT else Ui.TEXT_DIM
            }, LinearLayout.LayoutParams(24.dp, 24.dp))
            tab.addView(Ui.text(context, cat.title, 13.5f, if (activeCat == cat) Ui.TEXT else Ui.TEXT_DIM),
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 10.dp })

            catContainer.addView(tab, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4.dp })
        }
        nav.addView(catContainer, LinearLayout.LayoutParams(210.dp, 0, 1f))

        val closeBtn = Ui.pill(context, "Done") { onClose() }
        nav.addView(closeBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })

        rootBox.addView(nav, LinearLayout.LayoutParams(-2, -1))

        // Right Content Area
        contentBox.orientation = LinearLayout.VERTICAL
        contentBox.setPadding(20.dp, 18.dp, 20.dp, 20.dp)
        contentScroll.addView(contentBox)
        rootBox.addView(contentScroll, LinearLayout.LayoutParams(0, -1, 1f))

        addView(rootBox, LayoutParams(-1, -1))

        rebuildNav()
        rebuildContent()
    }

    private fun rebuildNav() {
        catContainer.removeAllViews()
        Category.entries.forEach { cat ->
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10.dp, 10.dp, 12.dp, 10.dp)
                background = Ui.rounded(if (activeCat == cat) Ui.ACCENT_SOFT else 0, 12f)
                setOnClickListener {
                    activeCat = cat
                    rebuildNav()
                    rebuildContent()
                }
            }
            tab.addView(IconView(context, cat.icon).apply {
                isClickable = false
                tint = if (activeCat == cat) Ui.ACCENT else Ui.TEXT_DIM
            }, LinearLayout.LayoutParams(24.dp, 24.dp))
            tab.addView(Ui.text(context, cat.title, 13.5f, if (activeCat == cat) Ui.TEXT else Ui.TEXT_DIM),
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 10.dp })

            catContainer.addView(tab, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4.dp })
        }
    }

    private fun rebuildContent() {
        contentBox.removeAllViews()
        contentBox.addView(Ui.text(context, activeCat.title, 18f, bold = true))
        contentBox.addView(Ui.divider(context), LinearLayout.LayoutParams(-1, 1.dp).apply { topMargin = 8.dp; bottomMargin = 14.dp })

        when (activeCat) {
            Category.CANVAS -> buildCanvasSettings()
            Category.STYLUS -> buildStylusSettings()
            Category.INTERFACE -> buildInterfaceSettings()
            Category.GESTURES -> buildGesturesSettings()
            Category.ABOUT -> buildAboutSettings()
        }
    }

    private fun cardBox(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            background = Ui.rounded(0xFF222228.toInt(), 14f)
        }
    }

    private fun buildCanvasSettings() {
        val box = cardBox()
        box.addView(Ui.text(context, "Document & Fill Defaults", 14f, bold = true))
        box.addView(Ui.text(context, "Configure document properties, fill tolerance and time-lapse", 11.5f, Ui.TEXT_DIM).apply { setPadding(0, 2.dp, 0, 8.dp) })

        val docInfo = "Dimensions: ${cv.doc.width} × ${cv.doc.height} px  |  Layers: ${cv.doc.layers.size} / ${cv.doc.maxLayers()}"
        box.addView(Ui.text(context, docInfo, 12.5f, Ui.TEXT_DIM).apply { setPadding(0, 0, 0, 8.dp) })

        box.addView(Ui.sliderRow(context, "ColorDrop Fill Sensitivity", cv.fillThreshold, { "${(it * 100).roundToInt()}%" }) {
            cv.fillThreshold = it
            prefs.edit().putFloat("fillThreshold", it).apply()
        })

        val rowTl = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        rowTl.addView(Ui.text(context, "Time-lapse Recording", 13.5f), LinearLayout.LayoutParams(0, -2, 1f))
        val timeLapseOn = prefs.getBoolean("timelapse", true)
        val switchTl = SwitchView(context).apply {
            checked = timeLapseOn
            onToggle = { on -> prefs.edit().putBoolean("timelapse", on).apply() }
        }
        rowTl.addView(switchTl)
        box.addView(rowTl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })

        contentBox.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp })
    }

    private fun buildStylusSettings() {
        val box = cardBox()
        box.addView(Ui.text(context, "S Pen Pressure Response Curve", 14f, bold = true))
        box.addView(Ui.text(context, "Select preset pressure curve for soft or firm stroke feel", 11.5f, Ui.TEXT_DIM).apply { setPadding(0, 2.dp, 0, 8.dp) })

        val currentCurve = prefs.getString("pressureCurve", "normal") ?: "normal"
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        val softBtn = Ui.button(context, "Soft") { setCurve("soft") }
        val normBtn = Ui.button(context, "Normal") { setCurve("normal") }
        val hardBtn = Ui.button(context, "Firm / Hard") { setCurve("hard") }

        softBtn.background = Ui.rounded(if (currentCurve == "soft") Ui.ACCENT_SOFT else Ui.PANEL_ROW, 10f)
        normBtn.background = Ui.rounded(if (currentCurve == "normal") Ui.ACCENT_SOFT else Ui.PANEL_ROW, 10f)
        hardBtn.background = Ui.rounded(if (currentCurve == "hard") Ui.ACCENT_SOFT else Ui.PANEL_ROW, 10f)

        row.addView(softBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(normBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 6.dp })
        row.addView(hardBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 6.dp })
        box.addView(row, LinearLayout.LayoutParams(-1, -2))

        // fine control: same scale as the presets (0.4 soft … 2.0 firm)
        val pv = ((2f - cv.pressureGamma) / 1.6f).coerceIn(0f, 1f)
        box.addView(Ui.sliderRow(context, "Fine-tune pressure", 1f - pv, {
            val g = 2f - (1f - it) * 1.6f
            when { g < 0.8f -> L10n.tr("Soft"); g > 1.25f -> L10n.tr("Firm / Hard"); else -> L10n.tr("Normal") }
        }) {
            cv.pressureGamma = 2f - (1f - it) * 1.6f
            prefs.edit().putFloat("pressureGamma", cv.pressureGamma).remove("pressureCurve").apply()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })

        val holdSec = (cv.penHoldMs - 400L) / 1600f
        val sliderRow = Ui.sliderRow(context, "S Pen Button Hold Duration", holdSec, { String.format(Locale.US, "%.2f sec", it * 1.6f + 0.4f) }) {
            val ms = ((it * 1.6f + 0.4f) * 1000).toLong()
            cv.penHoldMs = ms
            prefs.edit().putLong("penHoldMs", ms).apply()
        }
        box.addView(sliderRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })

        contentBox.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp })
    }

    private fun setCurve(curve: String) {
        val gamma = when (curve) {
            "soft" -> 0.7f
            "hard" -> 1.5f
            else -> 1.0f
        }
        cv.pressureGamma = gamma
        prefs.edit().putString("pressureCurve", curve).putFloat("pressureGamma", gamma).apply()
        rebuildContent()
    }

    private fun buildInterfaceSettings() {
        val box = cardBox()
        box.addView(Ui.text(context, "Toolbar & Sliders Placement", 14f, bold = true))
        box.addView(Ui.text(context, "Toolbar on any edge, sliders on the left or right", 11.5f, Ui.TEXT_DIM).apply { setPadding(0, 2.dp, 0, 10.dp) })

        val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row1.addView(Ui.text(context, "Toolbar Edge", 13.5f), LinearLayout.LayoutParams(0, -2, 1f))

        val edges = listOf("Right", "Left", "Top", "Bottom")
        val edgeBtn = Ui.button(context, prefs.getString("dock_edge", null) ?: (if (prefs.getBoolean("leftHanded", false)) "Left" else "Right")) {}
        edgeBtn.setOnClickListener {
            val pm = android.widget.PopupMenu(context, edgeBtn)
            edges.forEachIndexed { i, e -> pm.menu.add(0, i, i, e) }
            pm.setOnMenuItemClickListener { item ->
                val chosen = edges[item.itemId]
                edgeBtn.text = chosen
                onToolbarPlacement(chosen)
                true
            }
            L10n.menu(pm.menu); pm.show()
        }
        row1.addView(edgeBtn, LinearLayout.LayoutParams(120.dp, -2))
        box.addView(row1, LinearLayout.LayoutParams(-1, -2))

        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row2.addView(Ui.text(context, "Sliders Edge", 13.5f), LinearLayout.LayoutParams(0, -2, 1f))
        val sideEdges = listOf("Left", "Right")
        val sliderEdgeBtn = Ui.button(context, prefs.getString("sliders_edge", null) ?: (if (prefs.getBoolean("leftHanded", false)) "Right" else "Left")) {}
        sliderEdgeBtn.setOnClickListener {
            val pm = android.widget.PopupMenu(context, sliderEdgeBtn)
            sideEdges.forEachIndexed { i, e -> pm.menu.add(0, i, i, e) }
            pm.setOnMenuItemClickListener { item ->
                val chosen = sideEdges[item.itemId]
                sliderEdgeBtn.text = chosen
                onSlidersPlacement(chosen)
                true
            }
            L10n.menu(pm.menu); pm.show()
        }
        row2.addView(sliderEdgeBtn, LinearLayout.LayoutParams(120.dp, -2))
        box.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })

        val langRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val langTexts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        langTexts.addView(Ui.text(context, "Language", 13.5f))
        langTexts.addView(Ui.text(context, "The app restarts in the new language", 11f, Ui.TEXT_DIM))
        langRow.addView(langTexts, LinearLayout.LayoutParams(0, -2, 1f))
        val langs = listOf(L10n.EN to "English", L10n.AR to "العربية", L10n.CKB to "کوردی")
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((code, name) in langs) {
            val on = L10n.lang == code
            chips.addView(android.widget.TextView(context).apply {
                text = name
                textSize = 13f
                setTextColor(if (on) 0xFFFFFFFF.toInt() else Ui.TEXT)
                gravity = Gravity.CENTER
                setPadding(12.dp, 7.dp, 12.dp, 7.dp)
                background = Ui.rounded(if (on) Ui.ACCENT else Ui.PANEL_ROW, 16f)
                setOnClickListener { if (!on) onLanguage(code) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 4.dp })
        }
        langRow.addView(chips)
        box.addView(langRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14.dp })

        val isLeft = prefs.getBoolean("leftHanded", false)
        val row3 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row3.addView(Ui.text(context, "Left-Handed Mode", 13.5f), LinearLayout.LayoutParams(0, -2, 1f))
        val switchLeft = SwitchView(context).apply {
            checked = isLeft
            onToggle = { on ->
                prefs.edit().putBoolean("leftHanded", on).apply()
                onLeftHanded(on)
            }
        }
        row3.addView(switchLeft)
        box.addView(row3, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })

        contentBox.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp })
    }

    private fun buildGesturesSettings() {
        val box = cardBox()
        box.addView(Ui.text(context, "Touch & Multi-Finger Gestures", 14f, bold = true))

        val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row1.addView(Ui.text(context, "Draw with Finger", 13.5f), LinearLayout.LayoutParams(0, -2, 1f))
        val switchFinger = SwitchView(context).apply {
            checked = cv.fingerDrawing
            onToggle = {
                cv.fingerDrawing = it
                cv.fingerDrawingAuto = false // the user decided: don't switch it off when a pen is seen
                prefs.edit().putBoolean("fingerDrawing", it).apply()
            }
        }
        row1.addView(switchFinger)
        box.addView(row1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })

        val rowQs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        rowQs.addView(Ui.text(context, "QuickShape (hold at the end of a stroke)", 13.5f), LinearLayout.LayoutParams(0, -2, 1f))
        rowQs.addView(SwitchView(context).apply {
            checked = cv.quickShapeEnabled
            onToggle = { cv.quickShapeEnabled = it; prefs.edit().putBoolean("quickShape", it).apply() }
        })
        box.addView(rowQs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })

        box.addView(Ui.text(context, "• 2-Finger Tap: Undo last action", 12.5f, Ui.TEXT_DIM).apply { setPadding(0, 10.dp, 0, 0) })
        box.addView(Ui.text(context, "• 3-Finger Tap: Redo action", 12.5f, Ui.TEXT_DIM).apply { setPadding(0, 4.dp, 0, 0) })
        box.addView(Ui.text(context, "• 2-Finger Pinch / Rotate: Zoom and rotate canvas", 12.5f, Ui.TEXT_DIM).apply { setPadding(0, 4.dp, 0, 0) })

        contentBox.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp })
    }

    private fun buildAboutSettings() {
        val box = cardBox()
        val ver = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
        box.addView(Ui.text(context, "Inkora — version $ver", 15f, bold = true))
        box.addView(Ui.text(context, "Designed for Samsung Galaxy Tab S11 Ultra & S Pen", 12f, Ui.TEXT_DIM).apply { setPadding(0, 2.dp, 0, 10.dp) })

        box.addView(Ui.text(context, "S Pen shortcuts:", 13.5f, bold = true))
        box.addView(Ui.text(context, "• Click the pen button: brush ⇄ eraser\n• Hold or double-click it: quick menu with the color wheel\n• Hold the pen tip still on the canvas: quick menu\n• Draw and pause: QuickShape\n• Hold the button while drawing: erase", 12f, Ui.TEXT_DIM).apply { setPadding(0, 4.dp, 0, 0) })

        box.addView(Ui.pill(context, "Show the tips again") { onShowTips() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 14.dp })

        contentBox.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp })
    }
}
