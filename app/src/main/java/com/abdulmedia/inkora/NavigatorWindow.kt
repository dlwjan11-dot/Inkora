package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

@SuppressLint("ViewConstructor")
class NavigatorWindow(
    context: Context,
    private val cv: CanvasView
) : LinearLayout(context) {

    private val navView = NavigatorThumbnailView(context)

    init {
        orientation = VERTICAL
        setPadding(6.dp, 4.dp, 6.dp, 6.dp)
        addView(navView, LayoutParams(200.dp, 200.dp))
    }

    fun update() {
        navView.invalidate()
    }

    // what the picture was last drawn from: it is drawn again only when one of these changed
    private var seenDoc: Document? = null
    private var seenRevision = -1
    private val seenView = FloatArray(9)
    private val nowView = FloatArray(9)

    private val tick = object : Runnable {
        override fun run() {
            if (cv.isDocReady()) {
                cv.viewMatrix.getValues(nowView)
                val d = cv.doc
                if (d !== seenDoc || d.revision != seenRevision || !nowView.contentEquals(seenView)) {
                    if (d !== seenDoc || d.revision != seenRevision) navView.dropPicture()
                    seenDoc = d; seenRevision = d.revision
                    nowView.copyInto(seenView)
                    navView.invalidate()
                }
            }
            postDelayed(this, 300)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private inner class NavigatorThumbnailView(context: Context) : View(context) {
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0xFFFF4444.toInt()
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x22FF4444.toInt()
        }
        private val docBounds = RectF()
        private val viewportRect = RectF()
        private val screenRect = RectF()
        private val navMatrix = Matrix()
        private val invMatrix = Matrix()
        private var picture: android.graphics.Bitmap? = null
        private val picPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        fun dropPicture() { picture?.recycle(); picture = null }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            dropPicture()
        }

        init {
            background = Ui.rounded(0xFF14151A.toInt(), 10f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!cv.isDocReady()) return
            val doc = cv.doc
            docBounds.set(0f, 0f, doc.width.toFloat(), doc.height.toFloat())

            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            val scale = (w / doc.width).coerceAtMost(h / doc.height)
            val dx = (w - doc.width * scale) / 2f
            val dy = (h - doc.height * scale) / 2f

            navMatrix.reset()
            navMatrix.postScale(scale, scale)
            navMatrix.postTranslate(dx, dy)

            // the artwork as a small picture, kept until the artwork changes (drawing every layer
            // at full size several times a second would slow the brush down)
            var pic = picture
            if (pic == null || pic.isRecycled) {
                val pw = (doc.width * scale).toInt().coerceAtLeast(1)
                val ph = (doc.height * scale).toInt().coerceAtLeast(1)
                pic = try {
                    android.graphics.Bitmap.createBitmap(pw, ph, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                        val pc = Canvas(b)
                        pc.scale(pw / doc.width.toFloat(), ph / doc.height.toFloat())
                        doc.draw(pc, true, null, true)
                    }
                } catch (e: OutOfMemoryError) { null }
                picture = pic
            }
            if (pic != null) canvas.drawBitmap(pic, null, RectF(dx, dy, dx + doc.width * scale, dy + doc.height * scale), picPaint)

            // calculate visible viewport rectangle
            if (cv.viewMatrix.invert(invMatrix)) {
                screenRect.set(0f, 0f, cv.width.toFloat(), cv.height.toFloat())
                invMatrix.mapRect(screenRect)
                navMatrix.mapRect(viewportRect, screenRect)

                canvas.drawRect(viewportRect, fillPaint)
                canvas.drawRect(viewportRect, borderPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    panCanvasTo(e.x, e.y)
                }
            }
            return true
        }

        private fun panCanvasTo(nx: Float, ny: Float) {
            val doc = cv.doc
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            val scale = (w / doc.width).coerceAtMost(h / doc.height)
            val dx = (w - doc.width * scale) / 2f
            val dy = (h - doc.height * scale) / 2f

            val docX = (nx - dx) / scale
            val docY = (ny - dy) / scale

            // bring that point of the artwork to the middle of the screen (right for a turned
            // or mirrored canvas too)
            val p = floatArrayOf(docX, docY)
            cv.viewMatrix.mapPoints(p)
            cv.viewMatrix.postTranslate(cv.width / 2f - p[0], cv.height / 2f - p[1])
            cv.invalidate()
            invalidate()
        }
    }
}
