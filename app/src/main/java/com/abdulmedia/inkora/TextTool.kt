package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class TextFont(val label: String, val family: String, val style: Int) {
    SANS("Sans", "sans-serif", Typeface.NORMAL),
    SANS_BOLD("Sans Bold", "sans-serif", Typeface.BOLD),
    SANS_BLACK("Sans Black", "sans-serif-black", Typeface.NORMAL),
    CONDENSED("Condensed", "sans-serif-condensed", Typeface.NORMAL),
    SERIF("Serif", "serif", Typeface.NORMAL),
    SERIF_ITALIC("Serif Italic", "serif", Typeface.ITALIC),
    MONO("Monospace", "monospace", Typeface.NORMAL),
    HANDWRITING("Handwriting", "cursive", Typeface.NORMAL),
    CASUAL("Casual", "casual", Typeface.NORMAL);

    fun typeface(): Typeface = Typeface.create(family, style)
}

object TextTool {
    /**
     * Renders text (any language, Arabic included — Android shapes it and lays it right-to-left)
     * into a bitmap that fits inside maxW x maxH.
     */
    fun render(text: String, font: TextFont, sizePx: Float, color: Int, align: Layout.Alignment, maxW: Int, maxH: Int): Bitmap? {
        if (text.isBlank()) return null
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color
            textSize = sizePx
            typeface = font.typeface()
        }
        val natural = text.split('\n').maxOf { ceil(paint.measureText(it)).toInt() }
        val width = min(max(1, natural + 4), max(16, maxW))
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setIncludePad(true)
            .build()
        val h = min(layout.height + 4, max(16, maxH))
        val bmp = Bitmap.createBitmap(width, max(1, h), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(0f, 2f)
        layout.draw(c)
        return bmp
    }
}
