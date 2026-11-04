package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.hypot

@SuppressLint("ViewConstructor")
class LightingOrbView(context: Context) : LinearLayout(context) {

    private val orb = OrbView(context)

    init {
        orientation = VERTICAL
        setPadding(8.dp, 4.dp, 8.dp, 8.dp)

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val topLBtn = Ui.button(context, "Top-L") { orb.setLight(-0.5f, -0.6f) }
        val rimBtn = Ui.button(context, "Rim") { orb.setLight(0.8f, -0.2f) }
        val frontBtn = Ui.button(context, "Front") { orb.setLight(0f, 0f) }

        bar.addView(topLBtn, LayoutParams(0, -2, 1f))
        bar.addView(rimBtn, LayoutParams(0, -2, 1f).apply { leftMargin = 4.dp })
        bar.addView(frontBtn, LayoutParams(0, -2, 1f).apply { leftMargin = 4.dp })
        addView(bar, LayoutParams(-1, -2))

        addView(orb, LayoutParams(180.dp, 180.dp).apply { topMargin = 6.dp })
    }

    private inner class OrbView(context: Context) : View(context) {
        private var lightX = -0.5f
        private var lightY = -0.5f
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.WHITE
        }

        init {
            background = Ui.rounded(0xFF141418.toInt(), 12f)
        }

        fun setLight(lx: Float, ly: Float) {
            lightX = lx; lightY = ly
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = (minOf(width, height) / 2f - 12.dp)

            val highlightX = cx + lightX * r * 0.7f
            val highlightY = cy + lightY * r * 0.7f

            val shader = RadialGradient(
                highlightX, highlightY, r * 1.3f,
                intArrayOf(0xFFFFFFFF.toInt(), 0xFF888890.toInt(), 0xFF222228.toInt(), 0xFF0D0D10.toInt()),
                floatArrayOf(0f, 0.45f, 0.82f, 1f),
                Shader.TileMode.CLAMP
            )
            basePaint.shader = shader
            canvas.drawCircle(cx, cy, r, basePaint)
            basePaint.shader = null

            // Outline
            borderPaint.color = 0x44FFFFFF
            canvas.drawCircle(cx, cy, r, borderPaint)

            // Light Handle
            val hx = cx + lightX * r
            val hy = cy + lightY * r
            handlePaint.style = Paint.Style.FILL
            handlePaint.color = 0xFFFFD700.toInt()
            canvas.drawCircle(hx, hy, 8f.dp, handlePaint)

            borderPaint.color = Color.WHITE
            canvas.drawCircle(hx, hy, 8f.dp, borderPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val cx = width / 2f
            val cy = height / 2f
            val r = (minOf(width, height) / 2f - 12.dp).coerceAtLeast(1f)

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val dx = (e.x - cx) / r
                    val dy = (e.y - cy) / r
                    val dist = hypot(dx, dy)
                    if (dist > 1f) {
                        lightX = dx / dist
                        lightY = dy / dist
                    } else {
                        lightX = dx
                        lightY = dy
                    }
                    invalidate()
                }
            }
            return true
        }
    }
}
