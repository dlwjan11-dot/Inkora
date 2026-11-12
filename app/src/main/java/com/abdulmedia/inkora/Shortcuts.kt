package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** Every keyboard shortcut, by group, as the sheet shows them. */
object Shortcuts {
    class Row(val keys: String, val does: String)
    class Group(val heading: String, val rows: List<Row>)

    val GROUPS = listOf(
        Group("Edit", listOf(
            Row("Ctrl+Z", "Undo"),
            Row("Ctrl+Shift+Z  Ctrl+Y", "Redo"),
            Row("Ctrl+C", "Copy the selection or the layer"),
            Row("Ctrl+X", "Cut the selection or the layer"),
            Row("Ctrl+V", "Paste as a new layer"),
            Row("J", "Duplicate the layer"),
            Row("Delete  Backspace", "Clear the layer"),
            Row("Ctrl+A", "Select all"),
            Row("D  Ctrl+D", "Deselect")
        )),
        Group("Tools", listOf(
            Row("B", "Brush"),
            Row("N", "Smudge"),
            Row("E", "Eraser"),
            Row("M", "Eyedropper: the next touch picks a color"),
            Row("S", "Selection"),
            Row("V", "Transform"),
            Row("F", "Fill"),
            Row("T", "Text"),
            Row("U", "Hue, Saturation, Brightness"),
            Row("X", "Swap with the previous color"),
            Row("← → ↑ ↓", "Transforming: move it a pixel (Shift: 10)")
        )),
        Group("Panels", listOf(
            Row("C", "Colors"),
            Row("K", "Actions"),
            Row("L", "Layers"),
            Row("0", "Hide or show the interface")
        )),
        Group("Brush size", listOf(
            Row("[  ]", "Smaller or bigger by 1%"),
            Row("Shift+[  Shift+]", "By 5%"),
            Row("Alt+[  Alt+]", "By 10%")
        )),
        Group("View", listOf(
            Row("Ctrl+G", "Grey view, to check values"),
            Row("Ctrl++  Ctrl+−", "Zoom in or out"),
            Row("Ctrl+0", "Fit the canvas"),
            Row("R  Shift+R", "Turn the canvas 15° (with Shift, back)"),
            Row("Esc", "Close what is open, or set the canvas upright")
        )),
        Group("Files", listOf(
            Row("Ctrl+N", "New canvas"),
            Row("Ctrl+O", "Open a project file"),
            Row("Ctrl+S", "Save"),
            Row("Ctrl+Shift+S", "Save project as…"),
            Row("Ctrl+E", "Save PNG to Gallery"),
            Row("Ctrl+/", "Show this list (or hold Ctrl)")
        ))
    )
}

/** The list of keyboard shortcuts, in columns, over a dimmed screen. */
@SuppressLint("ViewConstructor")
class ShortcutsView(context: Context, closable: Boolean, private val onClose: () -> Unit) : FrameLayout(context) {
    init {
        setBackgroundColor(0x99000000.toInt())
        isClickable = true
        if (closable) setOnClickListener { onClose() }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.PANEL, 28f, Ui.OUTLINE, 1)
            setPadding(24.dp, 22.dp, 24.dp, 20.dp)
            isClickable = true
        }
        card.addView(Ui.text(context, "Keyboard shortcuts", 24f, bold = true))
        val cols = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val columns = List(3) { LinearLayout(context).apply { orientation = LinearLayout.VERTICAL } }
        // three columns, filled in turn so they come out about as tall
        val split = listOf(listOf(0, 3), listOf(1, 2), listOf(4, 5))
        split.forEachIndexed { c, idxs ->
            for (i in idxs) {
                val g = Shortcuts.GROUPS[i]
                columns[c].addView(Ui.text(context, g.heading, 13f, Ui.TEXT_DIM, true).apply { setPadding(0, 14.dp, 0, 6.dp) })
                for (r in g.rows) {
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 3.dp, 0, 3.dp) }
                    row.addView(Ui.text(context, r.keys, 13.5f, Ui.TEXT, true).apply {
                        textDirection = TEXT_DIRECTION_LTR
                    }, LinearLayout.LayoutParams(130.dp, -2))
                    row.addView(Ui.text(context, r.does, 13.5f, Ui.TEXT_DIM), LinearLayout.LayoutParams(0, -2, 1f))
                    columns[c].addView(row)
                }
            }
            cols.addView(columns[c], LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = if (c < 2) 22.dp else 0 })
        }
        val scroll = ScrollView(context)
        scroll.addView(cols)
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (closable) card.addView(Ui.pill(context, "Done", primary = true) { onClose() },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = 12.dp })
        addView(card, LayoutParams(-1, -1).apply { setMargins(40.dp, 40.dp, 40.dp, 40.dp) })
    }
}
