package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A new palette from a picture: Inkora finds its main colors, you choose how many, take out the
 * ones you don't want (tap them) and add any spot of the picture (tap it there).
 */
object PaletteMaker {
    fun show(ctx: Context, picture: Bitmap, name: String, onSaved: () -> Unit) {
        val small = scaled(picture, 260)
        var count = 12
        val picked = ArrayList<Int>()          // spots you tapped on the picture
        val removed = HashSet<Int>()           // found colors you took out
        var found = PaletteStore.extract(small, count)

        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp, 8.dp, 20.dp, 4.dp) }
        val grid = GridLayout(ctx).apply { columnCount = 8 }
        fun colors() = (found.filter { it !in removed } + picked).distinct()
        fun refresh() {
            grid.removeAllViews()
            for (c in colors()) {
                val sw = ColorSwatchView(ctx)
                sw.color = c
                sw.setOnClickListener { if (c in picked) picked.remove(c) else removed.add(c); refresh() }
                grid.addView(sw, GridLayout.LayoutParams().apply { width = 32.dp; height = 32.dp; setMargins(3.dp, 3.dp, 3.dp, 3.dp) })
            }
        }
        val pic = PictureView(ctx, picture) { c ->
            if (picked.size + found.size < PaletteStore.MAX_COLORS) { picked.add(c); refresh() }
        }
        box.addView(pic, LinearLayout.LayoutParams(-1, 200.dp))
        box.addView(Ui.text(ctx, "Tap the picture to add a color. Tap a color below to take it out.", 12.5f, Ui.TEXT_DIM).apply { setPadding(2.dp, 8.dp, 2.dp, 4.dp) })
        box.addView(Ui.sliderRow(ctx, "Colors", (count - 4) / 20f, { "${(4 + it * 20).roundToInt()}" }) { v ->
            val c = (4 + v * 20).roundToInt()
            if (c != count) { count = c; removed.clear(); found = PaletteStore.extract(small, count); refresh() }
        })
        box.addView(grid, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4.dp })
        val nameIn = EditText(ctx).apply { setText(L10n.tr(name)); setSingleLine(); hint = L10n.tr("Palette name") }
        box.addView(nameIn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.dp })
        refresh()

        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("New palette"))
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(L10n.tr("Save palette")) { _, _ ->
                val cs = colors()
                if (cs.isNotEmpty()) {
                    PaletteStore.create(nameIn.text.toString().trim(), cs.take(PaletteStore.MAX_COLORS))
                    onSaved()
                    Ui.toast(ctx, "Palette saved")
                }
            }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .setOnDismissListener { if (small !== picture) small.recycle() }
            .show()
    }

    private fun scaled(b: Bitmap, maxSide: Int): Bitmap {
        val s = min(1f, maxSide / maxOf(b.width, b.height).toFloat())
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    /** the picture, fitted; a tap (or a drag, for a loupe-free look) picks the color under the finger */
    @SuppressLint("ViewConstructor")
    private class PictureView(context: Context, private val bmp: Bitmap, private val onPick: (Int) -> Unit) : View(context) {
        private val dst = RectF()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var hx = -1f; private var hy = -1f; private var hc = 0

        init { background = Ui.rounded(0xFF14151A.toInt(), 12f); clipToOutline = true }

        override fun onDraw(c: Canvas) {
            val s = min(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            val w = bmp.width * s; val h = bmp.height * s
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            c.drawBitmap(bmp, null, dst, paint)
            if (hx >= 0) {
                ring.color = Color.BLACK; ring.strokeWidth = 5f.dp; c.drawCircle(hx, hy, 16f.dp, ring)
                ring.color = hc; ring.strokeWidth = 3f.dp; c.drawCircle(hx, hy, 16f.dp, ring)
                ring.color = Color.WHITE; ring.strokeWidth = 1.2f.dp; c.drawCircle(hx, hy, 18.5f.dp, ring)
            }
        }

        private fun colorAt(x: Float, y: Float): Int? {
            if (!dst.contains(x, y)) return null
            val bx = ((x - dst.left) / dst.width() * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val by = ((y - dst.top) / dst.height() * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            return bmp.getPixel(bx, by) or 0xFF000000.toInt()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    colorAt(e.x, e.y)?.let { hc = it; hx = e.x; hy = e.y; invalidate() }
                }
                MotionEvent.ACTION_UP -> { colorAt(e.x, e.y)?.let { onPick(it) }; hx = -1f; invalidate() }
                MotionEvent.ACTION_CANCEL -> { hx = -1f; invalidate() }
            }
            return true
        }
    }
}
