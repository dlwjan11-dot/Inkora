package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * What Inkora can do, page by page: the same pages are the first-run tour ([TipsOverlay]) and the
 * guide you can open any time ([GuideView], Actions › Help).
 */
object Tour {
    /** [art] 0..2 = a drawing made for the page; otherwise the page shows its [icons] */
    class Page(val art: Int, val title: String, val lines: List<String>, val icons: List<Icon> = emptyList())

    val pages = listOf(
        Page(0, "Your pen button does the work", listOf(
            "Click the S Pen button: brush ⇄ eraser",
            "Hold it or double-click: the quick menu with the color wheel",
            "Draw with the button held: erase",
            "Keep the pen still on the canvas: the quick menu too")),
        Page(2, "Fingers move the canvas", listOf(
            "Pinch with two fingers: zoom, move and turn the canvas",
            "A quick pinch in: fit the canvas to the screen",
            "Two-finger tap: undo · three-finger tap: redo (hold to keep going)",
            "Touch and hold: pick a color from the artwork",
            "Three fingers down: cut, copy and paste · four-finger tap: hide the interface")),
        Page(-1, "Brushes", listOf(
            "Tap Brush, Smudge or Eraser to use it; tap it again for the brush library",
            "Tap the brush in hand again: the Brush Studio, with every setting",
            "Slide a brush to the left: pin, duplicate, reset",
            "Pinned and Recent sit at the top of the library",
            "Hold Eraser or Smudge: it takes the brush you are painting with"), listOf(Icon.BRUSH, Icon.SMUDGE, Icon.ERASER)),
        Page(-1, "Size, opacity and color", listOf(
            "The two sliders on the side: brush size and opacity",
            "Tap the color circle for the colors and your palettes",
            "Drag the color circle onto the canvas: fill that area",
            "Pull the little bar on top of the color panel: it stays open as a floating window",
            "□ under the size slider: tap it, then touch the canvas to pick a color"), listOf(Icon.PALETTE, Icon.WHEEL, Icon.EYEDROPPER)),
        Page(-1, "Layers", listOf(
            "Tap the active layer again for its options: opacity, blend mode, masks, merge",
            "Slide a layer sideways, or hold it: pick several layers",
            "With several picked: group, merge or delete them, or move them all with Transform",
            "The checkbox hides a layer; the letter beside it is its blend mode",
            "Clipping mask, alpha lock and layer masks are in the layer's options"), listOf(Icon.LAYERS, Icon.FOLDER, Icon.EYE)),
        Page(-1, "Select, move and adjust", listOf(
            "The three icons on the top bar: adjustments, selection, transform",
            "Selection: freehand, rectangle, ellipse, magic wand, color range or a selection brush",
            "Transform: drag to move, corners to scale, the green dot to turn, two fingers to pinch",
            "Adjustments show live: hue, brightness, blur, sharpen and more"), listOf(Icon.ADJUST, Icon.SELECT, Icon.TRANSFORM)),
        Page(-1, "Perfect shapes and guides", listOf(
            "Draw a line, circle or box and keep the pen down: it becomes a clean shape",
            "Grids and perspective, with strokes that snap to them (Assisted drawing)",
            "Symmetry: mirror, quadrant, radial, mandala and kaleidoscope"), listOf(Icon.SHAPE, Icon.GRID, Icon.SYMMETRY)),
        Page(-1, "Watercolor and living paint", listOf(
            "Aquarelle: watercolor that catches on the paper and pools at the edges while you draw",
            "Living Paint: paint that stays wet and mixes on the canvas",
            "Light & Shade: airbrushes that brighten, deepen or tint what is under them",
            "Import Procreate brushes (.brush, .brushset) with + in the brush library"), listOf(Icon.DROP, Icon.PALETTE, Icon.LIGHTBULB)),
        Page(1, "More tools, and your own toolbar", listOf(
            "The ••• in the top bar opens Creative and Editing",
            "Creative: text, vector shapes and pen, 3D figures to pose, photos, fill",
            "Editing: liquify, canvas size and crop, filters, feather",
            "Tap ☆ on any tool to put it on your own toolbar; long-press the toolbar to arrange it")),
        Page(-1, "Your artworks", listOf(
            "Gallery (top left): all your artworks, in stacks if you like",
            "Inkora saves by itself while you work and when you leave",
            "Actions (the wrench) › Share: PNG, JPEG, Photoshop PSD, PDF, TIFF, project file",
            "Every artwork records a time-lapse video you can export"), listOf(Icon.FOLDER, Icon.EXPORT, Icon.VIDEO)),
        Page(-1, "Find all of this again", listOf(
            "Actions (the wrench) › Help › Guide: everything on these pages",
            "Gesture controls: change what each gesture and the pen button do",
            "With a keyboard: hold Ctrl to see every shortcut"), listOf(Icon.HELP, Icon.HAND, Icon.KEYBOARD))
    )

    /** a page's lines as one text, each on its own line with a dot in front */
    fun body(p: Page) = p.lines.joinToString("\n") { "•  " + L10n.tr(it) }
}

/**
 * The first-run tour: one card per page of [Tour], with Back, Next and Skip.
 */
@SuppressLint("ViewConstructor")
class TipsOverlay(context: Context, private val onDone: (View) -> Unit) : FrameLayout(context) {

    private val pages = Tour.pages
    private var index = 0

    private val card = LinearLayout(context)
    private val art = TipArt(context)
    private val title = Ui.text(context, "", 20f, bold = true)
    private val body = Ui.text(context, "", 14.5f, Ui.TEXT_DIM)
    private val dots = LinearLayout(context)
    private val count = Ui.text(context, "", 12f, Ui.TEXT_DIM)
    private val back = Ui.button(context, "Back") { if (index > 0) go(index - 1) }.apply { background = null; setTextColor(Ui.TEXT_DIM) }
    private val next = Ui.pill(context, "Next", primary = true) { go(index + 1) }

    init {
        setBackgroundColor(0xCC08080F.toInt())
        isClickable = true // nothing behind it reacts while the tips are open

        card.orientation = LinearLayout.VERTICAL
        card.setPadding(24.dp, 22.dp, 24.dp, 20.dp)
        card.background = Ui.rounded(Ui.PANEL, 24f, 0x22FFFFFF, 1)
        card.elevation = 24f.dp

        card.addView(art, LinearLayout.LayoutParams(-1, 190.dp))
        title.setPadding(0, 14.dp, 0, 6.dp)
        card.addView(title)
        body.setLineSpacing(3f.dp, 1f)
        card.addView(body)

        val bottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dots.orientation = LinearLayout.HORIZONTAL
        bottom.addView(count, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(Ui.button(context, "Skip all") { finish() }.apply { background = null; setTextColor(Ui.TEXT_DIM) })
        bottom.addView(back)
        bottom.addView(next, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 6.dp })
        card.addView(dots, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 16.dp })
        card.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })

        addView(card, LayoutParams(min(520.dp, context.resources.displayMetrics.widthPixels - 32.dp), -2, Gravity.CENTER))
        alpha = 0f
        animate().alpha(1f).setDuration(220).start()
        show()
    }

    private fun go(i: Int) {
        if (i >= pages.size) { finish(); return }
        val dir = (if (i < index) -1 else 1) * (if (L10n.isRtl) -1 else 1)
        index = i
        card.animate().alpha(0f).translationX(-16f.dp * dir).setDuration(110).withEndAction {
            show()
            card.translationX = 16f.dp * dir
            card.animate().alpha(1f).translationX(0f).setDuration(160).start()
        }.start()
    }

    private fun show() {
        val p = pages[index]
        art.page = p.art
        art.icons = p.icons
        title.text = L10n.tr(p.title)
        body.text = Tour.body(p)
        next.text = L10n.tr(if (index == pages.size - 1) "Start drawing" else "Next")
        back.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        count.text = "${index + 1} / ${pages.size}"
        dots.removeAllViews()
        for (k in pages.indices) {
            dots.addView(View(context).apply {
                background = Ui.rounded(if (k == index) Ui.ACCENT else 0x44FFFFFF, 4f)
            }, LinearLayout.LayoutParams(if (k == index) 20.dp else 7.dp, 7.dp).apply { marginEnd = 5.dp })
        }
    }

    private fun finish() {
        animate().alpha(0f).setDuration(160).withEndAction { onDone(this) }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = true

    /** simple drawings for each tip */
    private class TipArt(context: Context) : View(context) {
        var page = 0
            set(v) { field = v; invalidate() }
        /** shown big, side by side, on pages without a drawing of their own */
        var icons: List<Icon> = emptyList()
            set(v) { field = v; invalidate() }
        private val disc = ColorDisc().apply { set(0xFFE0457B.toInt()) }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            p.style = Paint.Style.FILL
            p.color = 0xFF16161B.toInt()
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 18f.dp, 18f.dp, p)
            if (page !in 0..2) {
                // the page's icons, each on its own tile
                val n = icons.size.coerceAtLeast(1)
                val tile = min(92f.dp, (width - 40f.dp) / n - 14f.dp)
                val total = n * tile + (n - 1) * 18f.dp
                icons.forEachIndexed { i, ic ->
                    val x = cx - total / 2 + tile / 2 + i * (tile + 18f.dp)
                    p.color = if (i == 0) Ui.PANEL_ROW_ACTIVE else 0xFF24252B.toInt()
                    canvas.drawRoundRect(RectF(x - tile / 2, cy - tile / 2, x + tile / 2, cy + tile / 2), 22f.dp, 22f.dp, p)
                    IconPainter.draw(canvas, ic, x, cy, tile * 0.52f, if (i == 0) Ui.ACCENT else Ui.TEXT)
                }
                return
            }
            when (page) {
                0 -> {
                    // the pen menu
                    p.color = 0xFF0F0F12.toInt()
                    canvas.drawCircle(cx, cy, 86f.dp, p)
                    disc.layout(cx, cy, 46f.dp, 10f.dp)
                    disc.draw(canvas)
                    val icons = listOf(Icon.BRUSH, Icon.SMUDGE, Icon.ERASER, Icon.EYEDROPPER, Icon.UNDO)
                    icons.forEachIndexed { i, ic ->
                        val a = Math.toRadians(270.0 + (i - 2) * 26.0)
                        val x = cx + cos(a).toFloat() * 70f.dp; val y = cy + sin(a).toFloat() * 70f.dp
                        p.color = if (i == 0) Ui.PANEL_ROW_ACTIVE else 0xFF2B2C33.toInt()
                        canvas.drawCircle(x, y, 13f.dp, p)
                        IconPainter.draw(canvas, ic, x, y, 14f.dp, if (i == 0) Ui.ACCENT else Ui.TEXT)
                    }
                    p.style = Paint.Style.STROKE; p.strokeWidth = 6f.dp; p.strokeCap = Paint.Cap.ROUND
                    p.color = 0xD9FFFFFF.toInt()
                    val r = RectF(cx - 70f.dp, cy - 70f.dp, cx + 70f.dp, cy + 70f.dp)
                    canvas.drawArc(r, 160f, 40f, false, p)
                    canvas.drawArc(r, -20f, 40f, false, p)
                    p.style = Paint.Style.FILL
                    // the pen
                    pen(canvas, cx + 92f.dp, cy + 54f.dp)
                }
                1 -> {
                    // the toolbar with a star being added
                    val w = 44f.dp; val left = cx + 60f.dp
                    p.color = 0xFF131316.toInt()
                    canvas.drawRoundRect(RectF(left, cy - 80f.dp, left + w, cy + 80f.dp), 14f.dp, 14f.dp, p)
                    val icons = listOf(Icon.SELECT, Icon.TRANSFORM, Icon.FILL, Icon.TEXT, Icon.SYMMETRY)
                    icons.forEachIndexed { i, ic ->
                        IconPainter.draw(canvas, ic, left + w / 2, cy - 58f.dp + i * 29f.dp, 20f.dp, if (i == 2) Ui.ACCENT else Ui.TEXT)
                    }
                    // a card with a star
                    p.color = 0xFF27282F.toInt()
                    val cardR = RectF(cx - 130f.dp, cy - 50f.dp, cx + 20f.dp, cy + 50f.dp)
                    canvas.drawRoundRect(cardR, 14f.dp, 14f.dp, p)
                    IconPainter.draw(canvas, Icon.FILL, cardR.centerX(), cardR.centerY(), 40f.dp, Ui.TEXT)
                    IconPainter.draw(canvas, Icon.STAR_FILLED, cardR.right - 16f.dp, cardR.top + 16f.dp, 18f.dp, Ui.ACCENT)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f.dp; p.color = 0x884C8DFF.toInt()
                    p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f.dp, 5f.dp), 0f)
                    canvas.drawLine(cardR.right + 6f.dp, cy, left - 6f.dp, cy, p)
                    p.pathEffect = null; p.style = Paint.Style.FILL
                }
                else -> {
                    // two fingers pinching
                    p.color = 0xFFFBF8F2.toInt()
                    canvas.drawRoundRect(RectF(cx - 110f.dp, cy - 66f.dp, cx + 110f.dp, cy + 66f.dp), 6f.dp, 6f.dp, p)
                    p.color = 0xFF2F6B8F.toInt()
                    canvas.drawCircle(cx + 50f.dp, cy - 20f.dp, 22f.dp, p)
                    p.color = 0x664C8DFF
                    canvas.drawCircle(cx - 40f.dp, cy + 10f.dp, 22f.dp, p)
                    canvas.drawCircle(cx + 10f.dp, cy + 40f.dp, 22f.dp, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f.dp; p.color = Ui.ACCENT
                    canvas.drawCircle(cx - 40f.dp, cy + 10f.dp, 22f.dp, p)
                    canvas.drawCircle(cx + 10f.dp, cy + 40f.dp, 22f.dp, p)
                    canvas.drawLine(cx - 66f.dp, cy - 16f.dp, cx - 82f.dp, cy - 32f.dp, p)
                    canvas.drawLine(cx + 36f.dp, cy + 66f.dp, cx + 52f.dp, cy + 82f.dp, p)
                    p.style = Paint.Style.FILL
                }
            }
        }

        private fun pen(canvas: Canvas, x: Float, y: Float) {
            canvas.save()
            canvas.rotate(-35f, x, y)
            p.color = 0xFF3A3A44.toInt()
            canvas.drawRoundRect(RectF(x - 6f.dp, y - 10f.dp, x + 6f.dp, y + 70f.dp), 6f.dp, 6f.dp, p)
            p.color = Ui.ACCENT
            canvas.drawRoundRect(RectF(x - 2.5f.dp, y + 12f.dp, x + 2.5f.dp, y + 24f.dp), 2f.dp, 2f.dp, p)
            canvas.restore()
        }
    }
}

/**
 * The guide: every page of [Tour] on one screen, to read at any time (Actions › Help › Guide).
 */
@SuppressLint("ViewConstructor")
class GuideView(
    context: Context,
    private val onTour: () -> Unit,
    private val onShortcuts: () -> Unit,
    private val onGestures: () -> Unit,
    private val onClose: () -> Unit
) : FrameLayout(context) {
    init {
        setBackgroundColor(0xF2101115.toInt())
        isClickable = true
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28.dp, 20.dp, 22.dp, 10.dp)
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Ui.text(context, "Guide", 26f, bold = true))
        titles.addView(Ui.text(context, "Everything Inkora can do, and the gestures that get you there", 13.5f, Ui.TEXT_DIM))
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Ui.pill(context, "Take the tour") { onTour() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8.dp })
        head.addView(Ui.pill(context, "Gesture controls") { onGestures() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8.dp })
        head.addView(Ui.pill(context, "Keyboard shortcuts") { onShortcuts() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8.dp })
        head.addView(Ui.pill(context, "Close", primary = true) { onClose() })
        col.addView(head)

        // two columns of cards, filled in turn
        val cols = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(18.dp, 4.dp, 18.dp, 40.dp) }
        val columns = List(2) { LinearLayout(context).apply { orientation = LinearLayout.VERTICAL } }
        Tour.pages.forEachIndexed { i, p ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18.dp, 16.dp, 18.dp, 16.dp)
                background = Ui.rounded(0xFF1E1F25.toInt(), 18f, 0x14FFFFFF, 1)
            }
            val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val icons = p.icons.ifEmpty { listOf(when (p.art) { 0 -> Icon.WHEEL; 1 -> Icon.CUSTOMIZE; else -> Icon.HAND }) }
            for (ic in icons.take(1)) top.addView(IconView(context, ic).apply { isClickable = false; tint = Ui.ACCENT; iconScale = 0.7f },
                LinearLayout.LayoutParams(34.dp, 34.dp))
            top.addView(Ui.text(context, p.title, 17f, bold = true).apply { setPadding(8.dp, 0, 0, 0) })
            card.addView(top)
            card.addView(Ui.text(context, "", 14f, 0xFFC9CCD6.toInt()).apply {
                text = Tour.body(p)
                setLineSpacing(4f.dp, 1f)
                setPadding(2.dp, 10.dp, 0, 0)
            })
            columns[i % 2].addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp })
        }
        cols.addView(columns[0], LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 6.dp })
        cols.addView(columns[1], LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 6.dp })
        val scroll = android.widget.ScrollView(context)
        scroll.addView(cols)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(col, LayoutParams(-1, -1))
    }
}
