package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Drawing Guide settings: grid type, size, look, Assisted Drawing, vanishing points. */
@SuppressLint("ViewConstructor")
class GuidePanel(
    context: Context,
    private val g: GuideSettings,
    private val onChange: () -> Unit,
    private val onEditPoints: () -> Unit
) : ScrollView(context) {

    private val box = LinearLayout(context)

    init {
        background = Ui.rounded(Ui.PANEL, 18f)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(16.dp, 14.dp, 16.dp, 16.dp)
        addView(box)
        rebuild()
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        Ui.button(context, label, onClick).apply {
            textSize = 13f
            background = Ui.rounded(if (selected) Ui.PANEL_ROW_ACTIVE else Ui.PANEL_ROW, 10f,
                if (selected) Ui.ACCENT else 0, if (selected) 1 else 0)
        }

    private fun changed(rebuildUi: Boolean = false) {
        onChange()
        if (rebuildUi) rebuild()
    }

    private fun rebuild() {
        box.removeAllViews()
        box.addView(Ui.text(context, "Drawing Guide", 17f, bold = true))
        box.addView(Ui.text(context, "Grids and perspective, saved with this canvas", 12f, Ui.TEXT_DIM))

        // ---- type ----
        val grid = GridLayout(context).apply { columnCount = 2 }
        GuideType.values().forEach { t ->
            val c = chip(t.label, g.type == t) {
                g.type = t
                changed(true)
            }
            grid.addView(c, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(3.dp, 3.dp, 3.dp, 3.dp)
            })
        }
        box.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })

        if (g.type == GuideType.OFF) return

        // ---- size + look ----
        if (g.type.usesSize) {
            val minS = GuideSettings.MIN_SIZE
            val maxS = GuideSettings.MAX_SIZE
            val v = sqrt(((g.size - minS) / (maxS - minS)).coerceIn(0f, 1f))
            box.addView(Ui.sliderRow(context, if (g.type.isPerspective) "Line spacing" else "Grid size", v,
                { val s = minS + (maxS - minS) * it * it; "${s.roundToInt()} px" }) {
                g.size = minS + (maxS - minS) * it * it
                changed()
            })
        }
        box.addView(Ui.sliderRow(context, "Thickness", (g.thicknessDp - 0.5f) / 4f, { String.format("%.1f", 0.5f + it * 4f) }) {
            g.thicknessDp = 0.5f + it * 4f
            changed()
        })
        box.addView(Ui.sliderRow(context, "Opacity", g.opacity, { "${(it * 100).roundToInt()}%" }) {
            g.opacity = it.coerceAtLeast(0.05f)
            changed()
        })

        box.addView(Ui.text(context, "Guide color", 13f, Ui.TEXT_DIM), LinearLayout.LayoutParams(-2, -2).apply { topMargin = 8.dp })
        val colors = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in GUIDE_COLORS) {
            val sw = ColorSwatchView(context)
            sw.color = c
            sw.alpha = if (c == g.color) 1f else 0.55f
            sw.setOnClickListener { g.color = c; changed(true) }
            colors.addView(sw, LinearLayout.LayoutParams(40.dp, 40.dp))
        }
        box.addView(colors)

        // ---- assisted drawing ----
        val assisted = chip(if (g.assisted) "Assisted Drawing: ON" else "Assisted Drawing: off", g.assisted) {
            g.assisted = !g.assisted
            changed(true)
        }
        box.addView(assisted, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })
        box.addView(Ui.text(context, assistHint(), 12f, Ui.TEXT_DIM).apply { setPadding(4.dp, 4.dp, 4.dp, 0) })

        // ---- vanishing points ----
        if (g.type.isPerspective) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(chip("Move vanishing points", false) { onEditPoints() }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(chip("Reset", false) { g.resetVps(); changed() },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 6.dp })
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })
        }
    }

    private fun assistHint() = when (g.type) {
        GuideType.SQUARE, GuideType.DOTS, GuideType.THIRDS -> "Strokes snap to horizontal or vertical."
        GuideType.ISOMETRIC, GuideType.HEXAGON -> "Strokes snap to vertical or the two 30° directions."
        GuideType.TRIANGLE -> "Strokes snap to horizontal or the two 60° directions."
        GuideType.POLAR -> "Strokes snap to a circle around the center, or a line through it."
        GuideType.PERSPECTIVE_1 -> "Strokes snap toward the vanishing point, or horizontal / vertical."
        GuideType.PERSPECTIVE_2 -> "Strokes snap toward either vanishing point, or vertical."
        GuideType.PERSPECTIVE_3 -> "Strokes snap toward one of the three vanishing points."
        GuideType.FISHEYE -> "Strokes follow the curved horizontals and verticals, or go straight toward the center. Point 1 is the center, point 2 sets the size and tilt of the fisheye."
        GuideType.ELLIPSE_RULER -> "Strokes follow circles around the center of the canvas."
        GuideType.OFF -> ""
    }

    companion object {
        val GUIDE_COLORS = intArrayOf(
            0xFF4C8DFF.toInt(), 0xFF00C2A8.toInt(), 0xFFFF5A7A.toInt(),
            0xFFFFB020.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()
        )
    }
}
