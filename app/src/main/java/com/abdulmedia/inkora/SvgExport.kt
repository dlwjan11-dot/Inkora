package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Exports the artwork as SVG: vector layers become real, editable paths
 * (open the file in Illustrator, Figma, Inkscape…); drawing layers are embedded as images.
 */
object SvgExport {

    private fun f(v: Float) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun hex(c: Int) = String.format(Locale.US, "#%06X", c and 0xFFFFFF)
    private fun alpha(c: Int) = ((c ushr 24) / 255f)

    private fun blendCss(b: LayerBlend) = when (b) {
        LayerBlend.NORMAL -> null
        LayerBlend.ADD -> "plus-lighter"
        else -> b.name.lowercase(Locale.US).replace('_', '-')
    }

    fun pathData(s: VShape): String {
        val sb = StringBuilder()
        for (c in s.contours) {
            val n = c.nodes
            if (n.isEmpty()) continue
            sb.append("M").append(f(n[0].x)).append(' ').append(f(n[0].y))
            fun seg(a: VNode, b: VNode) {
                if (a.hasOut || b.hasIn) sb.append("C").append(f(a.outX)).append(' ').append(f(a.outY)).append(' ')
                    .append(f(b.inX)).append(' ').append(f(b.inY)).append(' ').append(f(b.x)).append(' ').append(f(b.y))
                else sb.append("L").append(f(b.x)).append(' ').append(f(b.y))
            }
            for (i in 1 until n.size) seg(n[i - 1], n[i])
            if (c.closed && n.size > 1) { seg(n.last(), n[0]); sb.append("Z") }
        }
        return sb.toString()
    }

    fun export(doc: Document, rasterLayers: Boolean = true): String {
        val defs = StringBuilder()
        val body = StringBuilder()
        var gid = 0

        fun paintRef(p: VPaint): Pair<String, Float> {
            return when (p.kind) {
                FillKind.NONE -> "none" to 1f
                FillKind.SOLID -> hex(p.color) to alpha(p.color)
                FillKind.LINEAR -> {
                    val id = "g${gid++}"
                    defs.append("<linearGradient id=\"$id\" gradientUnits=\"userSpaceOnUse\" x1=\"${f(p.x1)}\" y1=\"${f(p.y1)}\" x2=\"${f(p.x2)}\" y2=\"${f(p.y2)}\">")
                        .append("<stop offset=\"0\" stop-color=\"${hex(p.color)}\" stop-opacity=\"${f(alpha(p.color))}\"/>")
                        .append("<stop offset=\"1\" stop-color=\"${hex(p.color2)}\" stop-opacity=\"${f(alpha(p.color2))}\"/>")
                        .append("</linearGradient>\n")
                    "url(#$id)" to 1f
                }
                FillKind.RADIAL -> {
                    val id = "g${gid++}"
                    val r = Math.hypot((p.x2 - p.x1).toDouble(), (p.y2 - p.y1).toDouble()).toFloat()
                    defs.append("<radialGradient id=\"$id\" gradientUnits=\"userSpaceOnUse\" cx=\"${f(p.x1)}\" cy=\"${f(p.y1)}\" r=\"${f(r)}\">")
                        .append("<stop offset=\"0\" stop-color=\"${hex(p.color)}\" stop-opacity=\"${f(alpha(p.color))}\"/>")
                        .append("<stop offset=\"1\" stop-color=\"${hex(p.color2)}\" stop-opacity=\"${f(alpha(p.color2))}\"/>")
                        .append("</radialGradient>\n")
                    "url(#$id)" to 1f
                }
            }
        }

        if (doc.backgroundVisible) {
            body.append("<rect width=\"${doc.width}\" height=\"${doc.height}\" fill=\"${hex(doc.backgroundColor)}\"/>\n")
        }
        for (layer in doc.layers) {
            if (!layer.visible) continue
            val style = StringBuilder()
            blendCss(layer.blend)?.let { style.append("mix-blend-mode:$it;") }
            val name = layer.name.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
            body.append("<g id=\"$name\"")
            if (layer.opacity < 0.999f) body.append(" opacity=\"${f(layer.opacity)}\"")
            if (style.isNotEmpty()) body.append(" style=\"$style\"")
            body.append(">\n")
            val v = layer.vector
            if (v != null) {
                for (s in v.shapes) {
                    val (fill, fo) = paintRef(s.fill)
                    val (stroke, so) = paintRef(s.stroke)
                    body.append("<path d=\"").append(pathData(s)).append("\" fill=\"$fill\"")
                    if (fo < 1f && s.fill.kind == FillKind.SOLID) body.append(" fill-opacity=\"${f(fo)}\"")
                    if (s.evenOdd) body.append(" fill-rule=\"evenodd\"")
                    if (s.stroke.kind != FillKind.NONE && s.strokeWidth > 0f) {
                        body.append(" stroke=\"$stroke\" stroke-width=\"${f(s.strokeWidth)}\"")
                        if (so < 1f && s.stroke.kind == FillKind.SOLID) body.append(" stroke-opacity=\"${f(so)}\"")
                        body.append(" stroke-linecap=\"${s.cap.name.lowercase(Locale.US).replace("butt", "butt")}\"")
                        body.append(" stroke-linejoin=\"${s.join.name.lowercase(Locale.US)}\"")
                        if (s.dashed) body.append(" stroke-dasharray=\"${f(s.strokeWidth * 3)} ${f(s.strokeWidth * 2)}\"")
                    }
                    if (s.opacity < 0.999f) body.append(" opacity=\"${f(s.opacity)}\"")
                    body.append("/>\n")
                }
            } else if (rasterLayers) {
                val r = Selections.contentBounds(layer.bitmap)
                if (r != null) {
                    val crop = Bitmap.createBitmap(layer.bitmap, r.left, r.top, r.width(), r.height())
                    val bos = ByteArrayOutputStream()
                    crop.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                    body.append("<image x=\"${r.left}\" y=\"${r.top}\" width=\"${r.width()}\" height=\"${r.height()}\" href=\"data:image/png;base64,")
                        .append(b64).append("\"/>\n")
                }
            }
            body.append("</g>\n")
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${doc.width}\" height=\"${doc.height}\" viewBox=\"0 0 ${doc.width} ${doc.height}\">\n" +
            (if (defs.isNotEmpty()) "<defs>\n$defs</defs>\n" else "") + body + "</svg>\n"
    }
}
