package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.hypot

@SuppressLint("ViewConstructor")
class ReferenceWindow(
    context: Context,
    private val onImport: () -> Unit,
    private val onColorPicked: (Int) -> Unit
) : LinearLayout(context) {

    private var bitmap: Bitmap? = null
    private val imgView = ReferenceImageView(context)

    init {
        orientation = VERTICAL
        setPadding(8.dp, 4.dp, 8.dp, 8.dp)

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val importBtn = Ui.button(context, "Import") { onImport() }
        val flipBtn = Ui.button(context, "Flip") { imgView.flipHorizontal() }
        val fitBtn = Ui.button(context, "Fit") { imgView.resetTransform() }

        bar.addView(importBtn, LayoutParams(0, -2, 1f))
        bar.addView(flipBtn, LayoutParams(0, -2, 1f).apply { leftMargin = 4.dp })
        bar.addView(fitBtn, LayoutParams(0, -2, 1f).apply { leftMargin = 4.dp })
        addView(bar, LayoutParams(-1, -2))

        addView(imgView, LayoutParams(220.dp, 220.dp).apply { topMargin = 6.dp })
    }

    fun setImage(bmp: Bitmap) {
        bitmap = bmp
        imgView.setBitmap(bmp)
    }

    private inner class ReferenceImageView(context: Context) : View(context) {
        private var bmp: Bitmap? = null
        private val imgMatrix = Matrix()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88FFFFFF.toInt()
            textAlign = Paint.Align.CENTER
        }
        private var startDist = 0f
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var lastX = 0f
        private var lastY = 0f

        init {
            background = Ui.rounded(0xFF14151A.toInt(), 12f)
            textPaint.textSize = 12f * resources.displayMetrics.density
        }

        fun setBitmap(b: Bitmap) {
            bmp = b
            resetTransform()
        }

        fun resetTransform() {
            val b = bmp ?: return
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            imgMatrix.reset()
            val scale = (w / b.width).coerceAtMost(h / b.height)
            val dx = (w - b.width * scale) / 2f
            val dy = (h - b.height * scale) / 2f
            imgMatrix.postScale(scale, scale)
            imgMatrix.postTranslate(dx, dy)
            invalidate()
        }

        fun flipHorizontal() {
            val w = width.toFloat().coerceAtLeast(1f)
            imgMatrix.postScale(-1f, 1f, w / 2f, 0f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val b = bmp
            if (b == null) {
                canvas.drawText("Tap Import for a reference photo", width / 2f, height / 2f + 4.dp, textPaint)
            } else {
                canvas.drawBitmap(b, imgMatrix, paint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (bmp == null) return super.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastX = e.x; lastY = e.y
                    downX = e.x; downY = e.y
                    moved = false
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // keep following the finger that stays down (no jump)
                    val stay = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(stay); lastY = e.getY(stay)
                    moved = true
                }
                MotionEvent.ACTION_UP -> {
                    // a tap (no drag) picks the color under the finger
                    if (!moved) sampleAt(e.x, e.y)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    moved = true
                    if (e.pointerCount == 2) {
                        startDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.x - downX, e.y - downY) > 8f * resources.displayMetrics.density) moved = true
                    if (e.pointerCount == 1) {
                        val dx = e.x - lastX
                        val dy = e.y - lastY
                        imgMatrix.postTranslate(dx, dy)
                        lastX = e.x; lastY = e.y
                        invalidate()
                    } else if (e.pointerCount == 2) {
                        val newDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        if (startDist > 10f && newDist > 10f) {
                            val factor = newDist / startDist
                            val cx = (e.getX(0) + e.getX(1)) / 2f
                            val cy = (e.getY(0) + e.getY(1)) / 2f
                            imgMatrix.postScale(factor, factor, cx, cy)
                            startDist = newDist
                            invalidate()
                        }
                    }
                }
            }
            return true
        }

        private fun sampleAt(x: Float, y: Float) {
            val b = bmp ?: return
            val inv = Matrix()
            if (imgMatrix.invert(inv)) {
                val pts = floatArrayOf(x, y)
                inv.mapPoints(pts)
                val bx = pts[0].toInt()
                val by = pts[1].toInt()
                if (bx in 0 until b.width && by in 0 until b.height) {
                    val color = b.getPixel(bx, by)
                    onColorPicked(color)
                }
            }
        }
    }
}
