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

    private val tick = object : Runnable {
        override fun run() {
            navView.invalidate()
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

            val s = canvas.save()
            canvas.concat(navMatrix)
            // draw document preview
            doc.draw(canvas, true, null, true)
            canvas.restoreToCount(s)

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

            // Center canvas on docX, docY
            val z = cv.zoom()
            val targetScreenX = cv.width / 2f
            val targetScreenY = cv.height / 2f

            if (cv.viewMatrix.invert(invMatrix)) {
                val screenCenter = floatArrayOf(targetScreenX, targetScreenY)
                invMatrix.mapPoints(screenCenter)
                val deltaDocX = docX - screenCenter[0]
                val deltaDocY = docY - screenCenter[1]

                cv.viewMatrix.postTranslate(-deltaDocX * z, -deltaDocY * z)
                cv.invalidate()
                invalidate()
            }
        }
    }
}
