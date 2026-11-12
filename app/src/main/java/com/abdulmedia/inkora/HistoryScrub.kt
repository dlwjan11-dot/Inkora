package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import kotlin.math.roundToInt

/**
 * The history as a line you drag along: every step of the artwork is a mark, the handle sits on
 * where you are. Dragging takes steps back (left) or brings them back (right) and the canvas shows
 * each one at once. Nothing is lost by dragging: the steps to the right stay until you draw again.
 */
@SuppressLint("ViewConstructor")
class HistoryScrubView(context: Context, private val total: Int, start: Int, private val onMove: (Int) -> Unit) : View(context) {

    var pos = start
        private set
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pad get() = 18f.dp

    init {
        isClickable = true
        contentDescription = L10n.tr("History")
    }

    private fun xOf(i: Int): Float = if (total <= 0) pad else pad + (width - 2 * pad) * i / total.toFloat()

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        // the track: done steps in the accent, steps you can bring back in grey
        p.style = Paint.Style.FILL
        p.color = Ui.TRACK
        canvas.drawRoundRect(RectF(pad, cy - 3f.dp, width - pad, cy + 3f.dp), 3f.dp, 3f.dp, p)
        p.color = Ui.ACCENT
        canvas.drawRoundRect(RectF(pad, cy - 3f.dp, xOf(pos), cy + 3f.dp), 3f.dp, 3f.dp, p)
        // a mark for every step when there is room for them
        if (total in 1..160) {
            p.color = Ui.TEXT_DIM
            val r = if (total > 60) 1f.dp else 1.6f.dp
            for (i in 0..total) canvas.drawCircle(xOf(i), cy + 11f.dp, r, p)
        }
        // the handle
        p.color = Ui.TEXT
        canvas.drawCircle(xOf(pos), cy, 11f.dp, p)
        p.color = Ui.ACCENT
        canvas.drawCircle(xOf(pos), cy, 5f.dp, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val f = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
                val np = (f * total).roundToInt()
                if (np != pos) { pos = np; onMove(np); invalidate() }
            }
        }
        return true
    }

    /** one step with the arrow buttons */
    fun step(d: Int) {
        val np = (pos + d).coerceIn(0, total)
        if (np != pos) { pos = np; onMove(np); invalidate() }
    }
}

object HistoryScrub {
    /**
     * Opens the scrubber next to [anchor] (the undo or redo button). [steps] is how many steps can be
     * taken back now, [later] how many can be brought back.
     */
    fun show(anchor: View, cv: CanvasView, onChanged: () -> Unit): PopupWindow? {
        val ctx = anchor.context
        val done = cv.undoSteps
        val total = done + cv.redoSteps
        if (total == 0) { Ui.toast(ctx, "Nothing to go back to yet"); return null }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 10.dp, 14.dp, 12.dp)
            background = Ui.rounded(Ui.PANEL, 18f, Ui.OUTLINE, 1)
            elevation = 12f.dp
            layoutDirection = View.LAYOUT_DIRECTION_LTR // the past is always on the left
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = Ui.text(ctx, "History", 14f, bold = true)
        val info = Ui.text(ctx, "", 12.5f, Ui.TEXT_DIM).apply { gravity = Gravity.END; maxLines = 1 }
        head.addView(title)
        head.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 12.dp })
        box.addView(head)
        fun describe(pos: Int) {
            val what = cv.historyLabel(pos - 1)?.let { L10n.tr(it) } ?: L10n.tr("The start")
            info.text = "$pos / $total · $what"
        }
        lateinit var scrub: HistoryScrubView
        scrub = HistoryScrubView(ctx, total, done) { pos -> cv.stepTo(pos); describe(pos); onChanged() }
        describe(done)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(IconView(ctx, Icon.UNDO).apply { iconScale = 0.5f; contentDescription = L10n.tr("Undo"); setOnClickListener { scrub.step(-1) } },
            LinearLayout.LayoutParams(40.dp, 40.dp))
        row.addView(scrub, LinearLayout.LayoutParams(0, 48.dp, 1f))
        row.addView(IconView(ctx, Icon.REDO).apply { iconScale = 0.5f; contentDescription = L10n.tr("Redo"); setOnClickListener { scrub.step(1) } },
            LinearLayout.LayoutParams(40.dp, 40.dp))
        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp })
        box.addView(Ui.text(ctx, "Drag to go back and forward. The steps on the right stay until you draw again.", 11.5f, Ui.TEXT_DIM).apply { setPadding(4.dp, 2.dp, 4.dp, 0) })

        val root = anchor.rootView
        val w = minOf(460.dp, root.width - 24.dp)
        box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = box.measuredHeight
        val loc = IntArray(2); anchor.getLocationInWindow(loc)
        val right = loc[0] + anchor.width + 12.dp
        val x = if (right + w <= root.width - 8.dp) right else (loc[0] - w - 12.dp).coerceAtLeast(8.dp)
        val y = (loc[1] + anchor.height / 2 - h / 2).coerceIn(8.dp, (root.height - h - 8.dp).coerceAtLeast(8.dp))
        val pw = PopupWindow(box, w, h, true).apply {
            setBackgroundDrawable(ColorDrawable(0))
            isOutsideTouchable = true
            elevation = 16f.dp
            animationStyle = android.R.style.Animation_Dialog
        }
        runCatching { pw.showAtLocation(root, Gravity.TOP or Gravity.LEFT, x, y) }
        return pw
    }
}
