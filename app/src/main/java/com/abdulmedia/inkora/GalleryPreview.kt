package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.min

/**
 * An artwork shown big, from the gallery (pinch a card open, or Preview in its menu). Swipe left or
 * right for the next one, tap to close. It shows the thumbnail at once and the sharp picture a moment later.
 */
@SuppressLint("ViewConstructor")
class GalleryPreview(
    context: Context,
    private val list: List<Gallery.Entry>,
    start: Int,
    private val onOpen: (String) -> Unit,
    private val onClose: () -> Unit
) : FrameLayout(context) {
    private var index = start.coerceIn(0, list.size - 1)
    private var picture: Bitmap? = null
    private var sharp: Bitmap? = null
    private val title = Ui.text(context, "", 16f, bold = true)
    private val counter = Ui.text(context, "", 12.5f, Ui.TEXT_DIM)
    private var loadToken = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var slide = 0f

    private val stage = object : View(context) {
        override fun onDraw(c: Canvas) {
            val b = sharp ?: picture ?: return
            val pad = 24f.dp
            val s = min((width - pad * 2) / b.width, (height - pad * 2) / b.height)
            val w = b.width * s; val h = b.height * s
            dst.set((width - w) / 2 + slide, (height - h) / 2, (width + w) / 2 + slide, (height + h) / 2)
            c.drawBitmap(b, null, dst, paint)
        }
    }

    init {
        setBackgroundColor(0xF50B0C10.toInt())
        isClickable = true
        addView(stage, LayoutParams(-1, -1).apply { bottomMargin = 64.dp })
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dp, 8.dp, 14.dp, 12.dp)
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title); texts.addView(counter)
        bar.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Ui.pill(context, "Close") { onClose() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8.dp })
        bar.addView(Ui.pill(context, "Open", primary = true) { onOpen(list[index].id) })
        addView(bar, LayoutParams(-1, -2, Gravity.BOTTOM))
        show()
    }

    private fun show() {
        val e = list[index]
        title.text = e.title
        counter.text = "${index + 1} / ${list.size}  ·  ${e.width} × ${e.height}"
        sharp?.recycle(); sharp = null
        picture = runCatching { BitmapFactory.decodeFile(e.thumb.absolutePath) }.getOrNull()
        slide = 0f
        stage.invalidate()
        // the sharp picture: the artwork itself, drawn at the size of the screen
        val token = ++loadToken
        val ctx = context.applicationContext
        val maxSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        Thread {
            val bmp = try {
                Gallery.load(ctx, e.id)?.let { doc ->
                    val s = min(1f, maxSide / maxOf(doc.width, doc.height).toFloat())
                    val out = Bitmap.createBitmap((doc.width * s).toInt().coerceAtLeast(1), (doc.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    val c = Canvas(out)
                    c.drawColor(0xFFFFFFFF.toInt())
                    c.scale(s, s)
                    doc.draw(c, true, screen = false)
                    doc.layers.forEach { it.bitmap.recycle() }
                    out
                }
            } catch (t: Throwable) { null }
            post {
                if (token == loadToken && isAttachedToWindow && bmp != null) { sharp = bmp; stage.invalidate() } else bmp?.recycle()
            }
        }.apply { isDaemon = true }.start()
    }

    private var x0 = 0f; private var y0 = 0f; private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; moved = false }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - x0) > 10f.dp) moved = true
                if (moved) { slide = e.x - x0; stage.invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - x0
                when {
                    !moved && abs(e.y - y0) < 10f.dp -> onClose()
                    dx < -width * 0.15f && index < list.size - 1 -> { index++; show() }
                    dx > width * 0.15f && index > 0 -> { index--; show() }
                    else -> { slide = 0f; stage.invalidate() }
                }
            }
            MotionEvent.ACTION_CANCEL -> { slide = 0f; stage.invalidate() }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadToken++
        sharp?.recycle(); sharp = null
    }
}
