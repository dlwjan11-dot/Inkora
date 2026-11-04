package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

/** ColorDrop: fills the area under the finger, using the visible artwork as reference. */
object FloodFill {

    class Result(val rect: Rect, val bitmap: Bitmap)

    fun compute(reference: Bitmap, sx: Int, sy: Int, color: Int, threshold: Float): Result? {
        val w = reference.width
        val h = reference.height
        if (sx !in 0 until w || sy !in 0 until h) return null
        val px = IntArray(w * h)
        reference.getPixels(px, 0, w, 0, 0, w, h)
        val target = px[sy * w + sx]
        val tol = (threshold.coerceIn(0f, 1f) * 255).toInt()
        val tA = target ushr 24
        val tR = (target shr 16) and 0xFF
        val tG = (target shr 8) and 0xFF
        val tB = target and 0xFF

        fun match(c: Int): Boolean =
            abs((c ushr 24) - tA) <= tol &&
                abs(((c shr 16) and 0xFF) - tR) <= tol &&
                abs(((c shr 8) and 0xFF) - tG) <= tol &&
                abs((c and 0xFF) - tB) <= tol

        val mask = ByteArray(w * h)
        var stack = IntArray(4096)
        var sp = 0
        fun push(v: Int) {
            if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
            stack[sp++] = v
        }

        var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
        push(sy * w + sx)
        while (sp > 0) {
            val i = stack[--sp]
            if (mask[i].toInt() != 0) continue
            val y = i / w
            val x = i - y * w
            val row = y * w
            var lx = x
            while (lx > 0 && mask[row + lx - 1].toInt() == 0 && match(px[row + lx - 1])) lx--
            var rx = x
            while (rx < w - 1 && mask[row + rx + 1].toInt() == 0 && match(px[row + rx + 1])) rx++
            for (xx in lx..rx) mask[row + xx] = 1
            if (lx < minX) minX = lx
            if (rx > maxX) maxX = rx
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            for (ny in intArrayOf(y - 1, y + 1)) {
                if (ny < 0 || ny >= h) continue
                val nrow = ny * w
                var xx = lx
                while (xx <= rx) {
                    if (mask[nrow + xx].toInt() == 0 && match(px[nrow + xx])) {
                        push(nrow + xx)
                        while (xx <= rx && mask[nrow + xx].toInt() == 0 && match(px[nrow + xx])) xx++
                    } else xx++
                }
            }
        }

        // grow by 1px so the fill tucks under anti-aliased line edges
        val r = Rect(
            (minX - 1).coerceAtLeast(0), (minY - 1).coerceAtLeast(0),
            (maxX + 2).coerceAtMost(w), (maxY + 2).coerceAtMost(h)
        )
        val rw = r.width()
        val rh = r.height()
        val out = IntArray(rw * rh)
        val opaque = color or 0xFF000000.toInt()
        for (yy in 0 until rh) {
            val y = r.top + yy
            for (xx in 0 until rw) {
                val x = r.left + xx
                val i = y * w + x
                val filled = mask[i].toInt() != 0 ||
                    (x > 0 && mask[i - 1].toInt() != 0) ||
                    (x < w - 1 && mask[i + 1].toInt() != 0) ||
                    (y > 0 && mask[i - w].toInt() != 0) ||
                    (y < h - 1 && mask[i + w].toInt() != 0)
                if (filled) out[yy * rw + xx] = opaque
            }
        }
        // mutable, so a selection can still mask it
        val bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, rw, 0, 0, rw, rh)
        return Result(r, bmp)
    }
}
