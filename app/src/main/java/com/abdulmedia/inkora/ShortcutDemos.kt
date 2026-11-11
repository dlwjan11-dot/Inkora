package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Every shortcut as a small moving picture: fingers, the pen and what the app does in answer.
 * The pictures are data ([ShortcutData]) in a 100 x 60 space, each value a number or keyframes.
 */
object Demos {
    /** a number, or keyframes (time 0..1 → value) with a soft ease between them */
    class Num(private val ts: FloatArray, private val vs: FloatArray) {
        fun at(t: Float): Float {
            if (ts.size == 1 || t <= ts[0]) return vs[0]
            for (i in 1 until ts.size) if (t <= ts[i]) {
                var u = (t - ts[i - 1]) / max(1e-6f, ts[i] - ts[i - 1])
                u = u * u * (3 - 2 * u)
                return vs[i - 1] + (vs[i] - vs[i - 1]) * u
            }
            return vs[vs.size - 1]
        }
    }

    class El(val kind: String, val color: Int, val text: String?, val pts: FloatArray?, private val nums: Map<String, Num>) {
        fun n(key: String, t: Float, default: Float = 0f) = nums[key]?.at(t) ?: default
    }

    class Demo(val title: String, val text: String, val seconds: Float, val els: List<El>)
    class Group(val title: String, val demos: List<Demo>)

    private val colors = mapOf(
        "ink" to 0xFFE8E9ED.toInt(), "accent" to 0xFF6E9FF5.toInt(), "accentsoft" to 0xFF2B3D63.toInt(), "dim" to 0xFF5A5F6C.toInt(),
        "paper" to 0xFFF2F1EC.toInt(), "gold" to 0xFFFFBE3D.toInt(), "red" to 0xFFF0706A.toInt(), "green" to 0xFF6FCF97.toInt(),
        "panel" to 0xFF1B1D24.toInt(), "panel2" to 0xFF2C2F3A.toInt())

    private fun num(v: Any?): Num? = when (v) {
        is Number -> Num(floatArrayOf(0f), floatArrayOf(v.toFloat()))
        is JSONArray -> Num(FloatArray(v.length()) { v.getJSONArray(it).getDouble(0).toFloat() }, FloatArray(v.length()) { v.getJSONArray(it).getDouble(1).toFloat() })
        else -> null
    }

    private fun el(o: JSONObject): El {
        val nums = HashMap<String, Num>()
        for (key in o.keys()) if (key != "k" && key != "c" && key != "t" && key != "pts") num(o.get(key))?.let { nums[key] = it }
        val pts = o.optJSONArray("pts")?.let { a -> FloatArray(a.length() * 2) { a.getJSONArray(it / 2).getDouble(it % 2).toFloat() } }
        return El(o.getString("k"), colors[o.optString("c", "ink")] ?: colors.getValue("ink"), if (o.has("t")) o.getString("t") else null, pts, nums)
    }

    val groups: List<Group> by lazy {
        runCatching {
            val a = JSONArray(ShortcutData.json)
            List(a.length()) { gi ->
                val g = a.getJSONObject(gi)
                val ds = g.getJSONArray("demos")
                Group(g.getString("title"), List(ds.length()) { di ->
                    val d = ds.getJSONObject(di)
                    val els = d.getJSONArray("els")
                    Demo(d.getString("title"), d.getString("text"), d.getDouble("dur").toFloat(), List(els.length()) { el(els.getJSONObject(it)) })
                })
            }
        }.getOrDefault(emptyList())
    }
}

/** plays one [Demos.Demo], over and over while it is on screen */
class DemoView(context: Context, private val demo: Demos.Demo) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val box = RectF()
    private val start = SystemClock.uptimeMillis()

    init { background = Ui.rounded(0xFF1B1D24.toInt(), 12f); clipToOutline = true }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(w, (w * 0.6f).toInt())
    }

    private fun paint(color: Int, alpha: Float, stroke: Float) {
        p.color = color
        p.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        p.style = if (stroke > 0f) Paint.Style.STROKE else Paint.Style.FILL
        p.strokeWidth = stroke
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(c: Canvas) {
        val t = ((SystemClock.uptimeMillis() - start) / (demo.seconds * 1000f)) % 1f
        val k = width / 100f
        c.save()
        c.scale(k, k)
        for (e in demo.els) {
            val a = e.n("a", t, 1f)
            if (a <= 0.004f) continue
            when (e.kind) {
                "rect" -> {
                    val w = e.n("w", t); val h = e.n("h", t); val s = e.n("s", t)
                    c.save()
                    c.translate(e.n("x", t), e.n("y", t)); c.rotate(e.n("rot", t))
                    val r = min(e.n("r", t), min(w, h) / 2f)
                    box.set(-w / 2, -h / 2, w / 2, h / 2)
                    paint(e.color, a, s); c.drawRoundRect(box, r, r, p)
                    c.restore()
                }
                "circ" -> { paint(e.color, a, e.n("s", t)); c.drawCircle(e.n("x", t), e.n("y", t), max(0.01f, e.n("r", t)), p) }
                "line" -> { paint(e.color, a, e.n("s", t, 1.4f)); c.drawLine(e.n("x1", t), e.n("y1", t), e.n("x2", t), e.n("y2", t), p) }
                "poly" -> {
                    val pts = e.pts ?: continue
                    val n = pts.size / 2 - 1
                    val upto = e.n("p", t, 1f) * n
                    if (upto <= 0f) continue
                    path.rewind(); path.moveTo(pts[0], pts[1])
                    for (i in 1..n) {
                        if (i <= upto) path.lineTo(pts[i * 2], pts[i * 2 + 1])
                        else {
                            val f = upto - (i - 1)
                            if (f > 0f) path.lineTo(pts[i * 2 - 2] + (pts[i * 2] - pts[i * 2 - 2]) * f, pts[i * 2 - 1] + (pts[i * 2 + 1] - pts[i * 2 - 1]) * f)
                            break
                        }
                    }
                    paint(e.color, a, e.n("s", t, 1.6f)); c.drawPath(path, p)
                }
                "finger" -> {
                    val x = e.n("x", t); val y = e.n("y", t); val down = e.n("d", t, 1f) > 0.5f
                    paint(0xFFFFFFFF.toInt(), a * (if (down) 0.92f else 0.45f), 0f); c.drawCircle(x, y, 3.6f, p)
                    paint(0xFFFFFFFF.toInt(), a * (if (down) 0.4f else 0.15f), 1.1f); c.drawCircle(x, y, 5.6f, p)
                    paint(0xFF000000.toInt(), a * 0.5f, 0.4f); c.drawCircle(x, y, 3.6f, p)
                }
                "pen" -> {
                    val x = e.n("x", t); val y = e.n("y", t); val pressed = e.n("b", t) > 0.5f
                    paint(0xFF11131A.toInt(), a, 4.6f); c.drawLine(x + 2.2f, y - 3.1f, x + 15f, y - 21f, p)
                    paint(0xFFD5D8E0.toInt(), a, 3.4f); c.drawLine(x + 2.2f, y - 3.1f, x + 15f, y - 21f, p)
                    paint(if (pressed) Ui.ACCENT else 0xFF7A7F8C.toInt(), a, 3.4f); p.strokeCap = Paint.Cap.BUTT
                    c.drawLine(x + 5.6f, y - 7.8f, x + 8.2f, y - 11.5f, p)
                    path.rewind(); path.moveTo(x, y); path.lineTo(x + 3.6f, y - 2.2f); path.lineTo(x + 0.9f, y - 4.1f); path.close()
                    paint(0xFFD5D8E0.toInt(), a, 0f); c.drawPath(path, p)
                }
                "txt" -> {
                    paint(e.color, a, 0f)
                    p.textSize = e.n("z", t, 7f); p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
                    c.drawText(e.text ?: "", e.n("x", t), e.n("y", t), p)
                }
            }
        }
        c.restore()
        // keeps moving only while it can be seen
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate()
    }
}

/** The page of all of them, by group. */
@SuppressLint("ViewConstructor")
class DemosPage(context: Context, onKeyboard: () -> Unit, onClose: () -> Unit) : FrameLayout(context) {
    init {
        setBackgroundColor(Ui.SCRIM)
        isClickable = true
        val narrow = context.narrow
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (narrow) setPadding(16.dp, 14.dp, 12.dp, 8.dp) else setPadding(28.dp, 20.dp, 22.dp, 10.dp)
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Ui.text(context, "Shortcuts, shown", if (narrow) 20f else 26f, bold = true))
        titles.addView(Ui.text(context, "Every gesture as a small moving picture", 13.5f, Ui.TEXT_DIM))
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        if (!narrow) head.addView(Ui.pill(context, "Keyboard shortcuts") { onKeyboard() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8.dp })
        head.addView(Ui.pill(context, "Close", primary = true) { onClose() })
        col.addView(head)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (narrow) setPadding(10.dp, 0, 10.dp, 40.dp) else setPadding(22.dp, 0, 22.dp, 40.dp)
        }
        val wDp = context.resources.configuration.screenWidthDp
        val cols = (wDp / 250).coerceIn(if (wDp < 340) 1 else 2, 5)
        for (g in Demos.groups) {
            body.addView(Ui.text(context, g.title, 16f, bold = true).apply { setPadding(6.dp, 18.dp, 6.dp, 6.dp) })
            val grid = GridLayout(context).apply { columnCount = cols }
            for (d in g.demos) {
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(8.dp, 8.dp, 8.dp, 10.dp)
                    background = Ui.rounded(Ui.PANEL_ROW, 16f)
                }
                card.addView(DemoView(context, d), LinearLayout.LayoutParams(-1, -2))
                card.addView(Ui.text(context, d.title, 14.5f, bold = true).apply { setPadding(4.dp, 8.dp, 4.dp, 2.dp) })
                card.addView(Ui.text(context, d.text, 12.5f, Ui.TEXT_DIM).apply { setPadding(4.dp, 0, 4.dp, 0) })
                grid.addView(card, GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(5.dp, 5.dp, 5.dp, 5.dp)
                })
            }
            body.addView(grid, LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(Ui.text(context, "You can change what each gesture does in Actions › Help › Gesture controls", 12.5f, Ui.TEXT_DIM).apply { setPadding(6.dp, 18.dp, 6.dp, 0) })
        val scroll = ScrollView(context)
        scroll.addView(body)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(col, LayoutParams(-1, -1))
    }
}
