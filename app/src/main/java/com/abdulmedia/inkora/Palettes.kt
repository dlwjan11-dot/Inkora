package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class Palette(var name: String, val colors: MutableList<Int>)

/**
 * The user's palettes: make, name, fill, switch and delete them. The active one also shows in the
 * pen's quick menu. Saved in the app's settings.
 */
object PaletteStore {
    const val MAX_COLORS = 60
    val palettes = ArrayList<Palette>()
    var active = 0
        private set
    private var prefs: SharedPreferences? = null

    val current: Palette get() = palettes[active.coerceIn(0, palettes.size - 1)]

    fun load(p: SharedPreferences) {
        prefs = p
        palettes.clear()
        val json = p.getString("palettes", null)
        if (json != null) runCatching {
            val o = JSONObject(json)
            val arr = o.getJSONArray("list")
            for (i in 0 until arr.length()) {
                val po = arr.getJSONObject(i)
                val cs = po.getJSONArray("colors")
                palettes.add(Palette(po.optString("name", "Palette"), MutableList(cs.length()) { cs.getLong(it).toInt() }))
            }
            active = o.optInt("active", 0)
        }
        if (palettes.isEmpty()) {
            // first time: the colors saved from the quick menu become "My colors"
            val old = p.getString("palette", "")!!.split(',').mapNotNull { it.trim().toLongOrNull()?.toInt() }
            palettes.add(Palette("My colors", old.toMutableList()))
            palettes.add(Palette("Basic", ColorPanel.PALETTE.toMutableList()))
            palettes.add(Palette("Skin tones", hex("FFE0BD FFCD94 EAC086 FFAD60 D9A066 C68642 A0522D 8D5524 6B3E26 4A2C1D 3B2219 F3D3C1")))
            palettes.add(Palette("Pastels", hex("FFD1DC FFB7B2 FFDAC1 FFF5BA E2F0CB B5EAD7 C7CEEA A0C4FF CDB4DB BDE0FE FFC8DD D8E2DC")))
            palettes.add(Palette("Earth", hex("3B2F2F 5C4033 7B5B3A A47551 C19A6B D2B48C 6B8E23 556B2F 8F9779 A9A18C 708090 2F4F4F")))
            palettes.add(Palette("Grays", hex("000000 1C1C1E 2C2C2E 3A3A3C 48484A 636366 8E8E93 AEAEB2 C7C7CC D1D1D6 E5E5EA FFFFFF")))
            active = 0
            save()
        }
        active = active.coerceIn(0, palettes.size - 1)
    }

    private fun hex(s: String) = s.split(' ').map { (it.toLong(16) or 0xFF000000L).toInt() }.toMutableList()

    fun save() {
        val o = JSONObject()
        o.put("active", active)
        o.put("list", JSONArray().apply {
            for (p in palettes) put(JSONObject().apply {
                put("name", p.name)
                put("colors", JSONArray().apply { p.colors.forEach { put(it.toLong() and 0xFFFFFFFFL) } })
            })
        })
        prefs?.edit()?.putString("palettes", o.toString())?.apply()
    }

    fun select(i: Int) { active = i.coerceIn(0, palettes.size - 1); save() }

    /** adds [c] to the active palette (first place); false when it is full */
    fun addColor(c: Int): Boolean {
        val list = current.colors
        val cc = c or 0xFF000000.toInt()
        list.remove(cc)
        if (list.size >= MAX_COLORS) return false
        list.add(0, cc)
        save()
        return true
    }

    fun removeAt(i: Int) { current.colors.removeAt(i); save() }

    fun create(name: String, colors: List<Int> = emptyList()): Palette {
        val p = Palette(name.ifBlank { "Palette ${palettes.size + 1}" }, colors.toMutableList())
        palettes.add(p)
        active = palettes.size - 1
        save()
        return p
    }

    fun deleteCurrent() {
        if (palettes.size <= 1) { current.colors.clear(); save(); return }
        palettes.removeAt(active)
        active = active.coerceIn(0, palettes.size - 1)
        save()
    }

    /**
     * The main colors of a picture (for "Palette from artwork / photo"): colors are grouped into
     * boxes by hue, saturation and brightness, and the fullest boxes give one average color each.
     */
    /**
     * The main colors of a picture: k-means over a sample of its pixels, so a small but strong
     * color (a red flower in a green field) still gets its place. Sorted from dark to light by hue family.
     */
    fun extract(bmp: Bitmap, count: Int = 12): List<Int> {
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return emptyList()
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val step = maxOf(1, px.size / 12000)
        val sr = ArrayList<Float>(); val sg = ArrayList<Float>(); val sb = ArrayList<Float>()
        var i = 0
        while (i < px.size) {
            val c = px[i]; i += step
            if ((c ushr 24) < 200) continue
            sr.add(((c shr 16) and 255).toFloat()); sg.add(((c shr 8) and 255).toFloat()); sb.add((c and 255).toFloat())
        }
        val n = sr.size
        if (n == 0) return emptyList()
        val k = count.coerceIn(1, minOf(32, n))
        val cr = FloatArray(k); val cg = FloatArray(k); val cb = FloatArray(k)
        // k-means++ start: each new center far from the ones already picked
        val rnd = java.util.Random(7)
        val dist = FloatArray(n) { Float.MAX_VALUE }
        var pick = rnd.nextInt(n)
        for (c in 0 until k) {
            cr[c] = sr[pick]; cg[c] = sg[pick]; cb[c] = sb[pick]
            var sum = 0.0
            for (j in 0 until n) {
                val d = d2(sr[j], sg[j], sb[j], cr[c], cg[c], cb[c])
                if (d < dist[j]) dist[j] = d
                sum += dist[j]
            }
            var r = rnd.nextDouble() * sum
            pick = 0
            for (j in 0 until n) { r -= dist[j]; if (r <= 0) { pick = j; break } }
        }
        val owner = IntArray(n)
        val cnt = IntArray(k)
        repeat(12) {
            val ar = DoubleArray(k); val ag = DoubleArray(k); val ab = DoubleArray(k)
            java.util.Arrays.fill(cnt, 0)
            for (j in 0 until n) {
                var best = 0; var bd = Float.MAX_VALUE
                for (c in 0 until k) { val d = d2(sr[j], sg[j], sb[j], cr[c], cg[c], cb[c]); if (d < bd) { bd = d; best = c } }
                owner[j] = best; cnt[best]++
                ar[best] += sr[j].toDouble(); ag[best] += sg[j].toDouble(); ab[best] += sb[j].toDouble()
            }
            for (c in 0 until k) if (cnt[c] > 0) { cr[c] = (ar[c] / cnt[c]).toFloat(); cg[c] = (ag[c] / cnt[c]).toFloat(); cb[c] = (ab[c] / cnt[c]).toFloat() }
        }
        // the centers sit in the middle of their pixels; the most typical real pixel looks truer (less muddy)
        val out = ArrayList<Int>()
        for (c in 0 until k) {
            if (cnt[c] == 0) continue
            var best = -1; var bd = Float.MAX_VALUE
            for (j in 0 until n) if (owner[j] == c) { val d = d2(sr[j], sg[j], sb[j], cr[c], cg[c], cb[c]); if (d < bd) { bd = d; best = j } }
            val col = if (best >= 0) Color.rgb(sr[best].toInt(), sg[best].toInt(), sb[best].toInt()) else Color.rgb(cr[c].toInt(), cg[c].toInt(), cb[c].toInt())
            if (out.none { near(it, col) }) out.add(col)
        }
        return sortColors(out)
    }

    /** grays first (dark to light), then the colors by hue, each family dark to light */
    fun sortColors(cs: List<Int>): List<Int> {
        val hsv = FloatArray(3)
        return cs.sortedWith(compareBy({ Color.colorToHSV(it, hsv); if (hsv[1] < 0.12f) -1 else (hsv[0] / 30f).toInt() }, { Color.colorToHSV(it, hsv); hsv[2] }))
    }

    // perceptual-ish distance: the eye is most sensitive to green, least to blue
    private fun d2(r1: Float, g1: Float, b1: Float, r2: Float, g2: Float, b2: Float): Float {
        val dr = r1 - r2; val dg = g1 - g2; val db = b1 - b2
        val rm = (r1 + r2) / 2f
        return (2f + rm / 256f) * dr * dr + 4f * dg * dg + (2f + (255f - rm) / 256f) * db * db
    }

    private fun near(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b)) < 36
}

/**
 * A horizontal color slider (Hue, Saturation or Brightness) drawn as a gradient bar with a ring.
 * [hsv] is shared with the color panel; [channel] is 0 = hue, 1 = saturation, 2 = brightness.
 */
@SuppressLint("ViewConstructor")
class HsvSlider(context: Context, private val hsv: FloatArray, private val channel: Int) : View(context) {
    var onChange: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 30.dp)

    private val value get() = when (channel) { 0 -> hsv[0] / 360f; 1 -> hsv[1]; else -> hsv[2] }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat(); val pad = h / 2
        r.set(pad - 6f.dp, h * 0.22f, width - pad + 6f.dp, h * 0.78f)
        val colors = when (channel) {
            0 -> IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, maxOf(hsv[1], 0.25f), maxOf(hsv[2], 0.35f))) }
            1 -> intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])))
            else -> intArrayOf(0xFF000000.toInt(), Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
        }
        bar.shader = LinearGradient(r.left, 0f, r.right, 0f, colors, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, bar)
        val x = pad + value.coerceIn(0f, 1f) * (width - 2 * pad)
        fill.color = Color.HSVToColor(hsv)
        canvas.drawCircle(x, h / 2, h / 2 - 3f.dp, fill)
        ring.strokeWidth = 3f.dp; ring.color = Color.WHITE
        canvas.drawCircle(x, h / 2, h / 2 - 3f.dp, ring)
        ring.strokeWidth = 1f.dp; ring.color = 0x66000000
        canvas.drawCircle(x, h / 2, h / 2 - 1f.dp, ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val pad = height / 2f
        val v = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        when (channel) { 0 -> hsv[0] = (v * 360f).coerceAtMost(359.9f); 1 -> hsv[1] = v; else -> hsv[2] = v }
        onChange?.invoke()
        if (e.actionMasked == MotionEvent.ACTION_UP) onCommit?.invoke()
        return true
    }
}
