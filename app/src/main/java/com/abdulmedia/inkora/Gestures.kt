package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Gesture controls (like Procreate's): what each way of touching does.
 *
 * Every page is something a gesture can do; on it, a switch for each way of touching that can do
 * it. A way of touching does one thing at most, so turning it on for one page turns it off on the
 * others. Settings live in the app's SharedPreferences as "g.PAGE.KEY" and "g.PAGE.delay".
 */
enum class GesturePurpose(val title: String, val icon: Icon) {
    SMUDGE("Smudge", Icon.SMUDGE),
    ERASE("Erase", Icon.ERASER),
    ASSISTED("Assisted Drawing", Icon.RULER),
    EYEDROPPER("Eyedropper", Icon.EYEDROPPER),
    QUICKSHAPE("QuickShape", Icon.SHAPE),
    QUICKMENU("QuickMenu", Icon.WHEEL),
    FULL_SCREEN("Full Screen", Icon.FOCUS),
    CLEAR_LAYER("Clear Layer", Icon.CLEAR),
    COPY_PASTE("Copy & Paste", Icon.COPY),
    LAYER_SELECT("Layer Select", Icon.LAYERS),
    HOVER("Hover", Icon.CURSOR),
    GENERAL("General", Icon.SETTINGS)
}

/** A way of touching. Each does one thing at most. */
enum class GestureTrigger(val label: String) {
    TAP_MODIFY("Tap □"),
    TOUCH("Touch"),
    PEN("Pen"),
    MODIFY_TOUCH("□ + Touch"),
    MODIFY_PEN("□ + Pen"),
    THREE_SWIPE("Three-finger swipe"),
    FOUR_TAP("Four-finger tap"),
    PEN_CLICK("S Pen button"),
    PEN_DOUBLE("S Pen double-click"),
    PEN_HOLD("S Pen button hold"),
    PEN_STILL("Pen held still"),
    HOLD("Touch and hold"),
    SCRUB("Scrub")
}

/** One switch on a page: a way of touching, or a setting of the page's own. */
class GestureOption(
    val key: String,
    val label: String,
    val detail: String,
    val default: Boolean,
    val trigger: GestureTrigger?,
    /** a delay slider goes under it */
    val delayed: Boolean
)

class GestureSettings(private val prefs: SharedPreferences) {

    companion object {
        const val MIN_DELAY = 0.1f
        const val MAX_DELAY = 1.5f

        private fun opt(t: GestureTrigger, detail: String, default: Boolean = false) =
            GestureOption(t.name, t.label, detail, default, t, t == GestureTrigger.HOLD || t == GestureTrigger.PEN_STILL)

        private fun own(key: String, label: String, detail: String, default: Boolean, delayed: Boolean = false) =
            GestureOption(key, label, detail, default, null, delayed)

        /** Every page's switches, in order. */
        val OPTIONS: Map<GesturePurpose, List<GestureOption>> = linkedMapOf(
            GesturePurpose.SMUDGE to listOf(
                opt(GestureTrigger.TOUCH, "Finger strokes smudge"),
                opt(GestureTrigger.PEN, "Pen strokes smudge"),
                opt(GestureTrigger.MODIFY_TOUCH, "Finger strokes smudge while □ is held down"),
                opt(GestureTrigger.MODIFY_PEN, "Pen strokes smudge while □ is held down")
            ),
            GesturePurpose.ERASE to listOf(
                opt(GestureTrigger.TOUCH, "Finger strokes erase"),
                opt(GestureTrigger.PEN, "Pen strokes erase"),
                opt(GestureTrigger.MODIFY_TOUCH, "Finger strokes erase while □ is held down"),
                opt(GestureTrigger.MODIFY_PEN, "Pen strokes erase while □ is held down"),
                opt(GestureTrigger.PEN_CLICK, "Switches between the brush and the eraser", true)
            ),
            GesturePurpose.ASSISTED to listOf(
                opt(GestureTrigger.TAP_MODIFY, "Turns assisted drawing on or off"),
                opt(GestureTrigger.TOUCH, "Finger strokes follow the drawing guide"),
                opt(GestureTrigger.PEN, "Pen strokes follow the drawing guide"),
                opt(GestureTrigger.MODIFY_TOUCH, "Finger strokes follow the guide while □ is held down"),
                opt(GestureTrigger.MODIFY_PEN, "Pen strokes follow the guide while □ is held down"),
                opt(GestureTrigger.PEN_CLICK, "Turns assisted drawing on or off"),
                opt(GestureTrigger.PEN_DOUBLE, "Turns assisted drawing on or off")
            ),
            GesturePurpose.EYEDROPPER to listOf(
                opt(GestureTrigger.TAP_MODIFY, "The next touch picks a color", true),
                opt(GestureTrigger.TOUCH, "A finger on the canvas picks a color"),
                opt(GestureTrigger.MODIFY_TOUCH, "A finger picks a color while □ is held down", true),
                opt(GestureTrigger.MODIFY_PEN, "The pen picks a color while □ is held down", true),
                opt(GestureTrigger.PEN_CLICK, "The next touch picks a color"),
                opt(GestureTrigger.PEN_DOUBLE, "The next touch picks a color"),
                opt(GestureTrigger.HOLD, "A finger kept still on the canvas picks a color", true)
            ),
            GesturePurpose.QUICKSHAPE to listOf(
                opt(GestureTrigger.TAP_MODIFY, "Turns the last stroke into a shape"),
                opt(GestureTrigger.THREE_SWIPE, "Three fingers swiped down turn the last stroke into a shape"),
                opt(GestureTrigger.FOUR_TAP, "Four fingers tapped turn the last stroke into a shape"),
                opt(GestureTrigger.PEN_CLICK, "Turns the last stroke into a shape"),
                opt(GestureTrigger.PEN_DOUBLE, "Turns the last stroke into a shape"),
                own("DRAW_HOLD", "Draw and hold", "Pausing at the end of a stroke turns it into a shape", true, delayed = true)
            ),
            GesturePurpose.QUICKMENU to listOf(
                opt(GestureTrigger.TAP_MODIFY, "Opens the quick menu"),
                opt(GestureTrigger.TOUCH, "A finger touch opens the quick menu"),
                opt(GestureTrigger.MODIFY_TOUCH, "A finger opens the quick menu while □ is held down"),
                opt(GestureTrigger.MODIFY_PEN, "The pen opens the quick menu while □ is held down"),
                opt(GestureTrigger.THREE_SWIPE, "Three fingers swiped down open the quick menu"),
                opt(GestureTrigger.FOUR_TAP, "Four fingers tapped open the quick menu"),
                opt(GestureTrigger.PEN_CLICK, "Opens the quick menu"),
                opt(GestureTrigger.PEN_DOUBLE, "Opens the quick menu", true),
                opt(GestureTrigger.PEN_HOLD, "Opens the quick menu; let go over an item to use it", true),
                opt(GestureTrigger.PEN_STILL, "The pen tip kept still on the canvas opens the quick menu", true),
                opt(GestureTrigger.HOLD, "A finger kept still on the canvas opens the quick menu")
            ),
            GesturePurpose.FULL_SCREEN to listOf(
                opt(GestureTrigger.TOUCH, "A finger touch shows or hides the interface"),
                opt(GestureTrigger.THREE_SWIPE, "Three fingers swiped down show or hide the interface"),
                opt(GestureTrigger.FOUR_TAP, "Four fingers tapped show or hide the interface", true),
                opt(GestureTrigger.PEN_CLICK, "Shows or hides the interface"),
                opt(GestureTrigger.PEN_DOUBLE, "Shows or hides the interface")
            ),
            GesturePurpose.CLEAR_LAYER to listOf(
                opt(GestureTrigger.SCRUB, "Rubbing three fingers back and forth clears the layer", true),
                opt(GestureTrigger.THREE_SWIPE, "Three fingers swiped down clear the layer"),
                opt(GestureTrigger.FOUR_TAP, "Four fingers tapped clear the layer"),
                opt(GestureTrigger.PEN_CLICK, "Clears the layer"),
                opt(GestureTrigger.HOLD, "A finger kept still on the canvas clears the layer")
            ),
            GesturePurpose.COPY_PASTE to listOf(
                opt(GestureTrigger.TAP_MODIFY, "Opens the copy and paste menu"),
                opt(GestureTrigger.TOUCH, "A finger touch opens the copy and paste menu"),
                opt(GestureTrigger.MODIFY_TOUCH, "A finger opens the copy and paste menu while □ is held down"),
                opt(GestureTrigger.MODIFY_PEN, "The pen opens the copy and paste menu while □ is held down"),
                opt(GestureTrigger.THREE_SWIPE, "Three fingers swiped down open the copy and paste menu", true),
                opt(GestureTrigger.FOUR_TAP, "Four fingers tapped open the copy and paste menu"),
                opt(GestureTrigger.PEN_CLICK, "Opens the copy and paste menu"),
                opt(GestureTrigger.HOLD, "A finger kept still on the canvas opens the copy and paste menu")
            ),
            GesturePurpose.LAYER_SELECT to listOf(
                opt(GestureTrigger.TOUCH, "A finger touch picks the layer drawn there"),
                opt(GestureTrigger.MODIFY_TOUCH, "A finger picks the layer drawn there while □ is held down"),
                opt(GestureTrigger.MODIFY_PEN, "The pen picks the layer drawn there while □ is held down"),
                opt(GestureTrigger.PEN_CLICK, "Picks the layer drawn under the pen"),
                opt(GestureTrigger.HOLD, "A finger kept still picks the layer drawn there")
            ),
            GesturePurpose.HOVER to listOf(
                own("HOVER_TOUCH", "Touch", "While the pen hovers, drag a finger for opacity and pinch for size", false)
            ),
            GesturePurpose.GENERAL to listOf(
                own("FINGER_PAINT", "Painting with a finger", "One finger paints as the pen does", true),
                own("ONE_FINGER_PAN", "Move the canvas with one finger", "One finger drags the canvas instead of painting; the pen still paints", false),
                own("NO_UNDO", "Disable undo and redo gestures", "Two- and three-finger taps leave the history alone", false),
                own("ROTATE", "Rotate with pinch zoom", "Two fingers turn the canvas as they zoom it", true),
                own("RAPID_UNDO", "Rapid undo", "Two fingers held still keep undoing, three keep redoing", true, delayed = true)
            )
        )

        private val DEFAULT_DELAY = mapOf(
            GesturePurpose.EYEDROPPER to 0.45f,
            GesturePurpose.QUICKSHAPE to 0.5f,
            GesturePurpose.QUICKMENU to 0.42f,
            GesturePurpose.GENERAL to 0.65f
        )
    }

    /** whether fingers paint right now, for when the user hasn't chosen (they stop once a pen is seen) */
    var fingerPaintNow: () -> Boolean = { true }

    /** bumped on every change, so a canvas can cache what it reads */
    var revision = 0
        private set

    private fun name(p: GesturePurpose, key: String) = "g.${p.name}.$key"

    fun option(p: GesturePurpose, key: String) = OPTIONS[p]?.firstOrNull { it.key == key }

    fun isOn(p: GesturePurpose, key: String): Boolean {
        // finger painting kept its own setting from before gesture controls existed
        if (p == GesturePurpose.GENERAL && key == "FINGER_PAINT")
            return if (prefs.contains("fingerDrawing")) prefs.getBoolean("fingerDrawing", true) else fingerPaintNow()
        if (p == GesturePurpose.QUICKSHAPE && key == "DRAW_HOLD") return prefs.getBoolean("quickShape", true)
        val o = option(p, key) ?: return false
        return prefs.getBoolean(name(p, key), o.default)
    }

    fun isOn(p: GesturePurpose, t: GestureTrigger) = isOn(p, t.name)

    /** Turns a switch on or off; on, its way of touching goes off on every other page. */
    fun set(p: GesturePurpose, key: String, value: Boolean) {
        val e = prefs.edit()
        when {
            p == GesturePurpose.GENERAL && key == "FINGER_PAINT" -> e.putBoolean("fingerDrawing", value)
            p == GesturePurpose.QUICKSHAPE && key == "DRAW_HOLD" -> e.putBoolean("quickShape", value)
            else -> e.putBoolean(name(p, key), value)
        }
        val trig = option(p, key)?.trigger
        if (value && trig != null) {
            for ((other, opts) in OPTIONS) {
                if (other == p) continue
                if (opts.any { it.trigger == trig }) e.putBoolean(name(other, trig.name), false)
            }
        }
        e.apply()
        revision++
    }

    /** What [t] does, if anything. */
    fun purposeOf(t: GestureTrigger): GesturePurpose? =
        OPTIONS.entries.firstOrNull { (p, opts) -> opts.any { it.trigger == t } && isOn(p, t) }?.key

    /** A page's delay, seconds. */
    fun delay(p: GesturePurpose): Float =
        prefs.getFloat(name(p, "delay"), DEFAULT_DELAY[p] ?: 0.5f).coerceIn(MIN_DELAY, MAX_DELAY)

    fun delayMs(p: GesturePurpose) = (delay(p) * 1000).roundToInt().toLong()

    fun setDelay(p: GesturePurpose, seconds: Float) {
        prefs.edit().putFloat(name(p, "delay"), seconds.coerceIn(MIN_DELAY, MAX_DELAY)).apply()
        revision++
    }

    /** Everything as the app ships it. */
    fun resetAll() {
        val e = prefs.edit()
        for ((p, opts) in OPTIONS) {
            for (o in opts) e.remove(name(p, o.key))
            e.remove(name(p, "delay"))
        }
        e.remove("fingerDrawing").putBoolean("quickShape", true)
        e.apply()
        revision++
    }
}

/**
 * Gesture controls, full screen: a list of what gestures can do on the left and, for the one
 * chosen, a card of switches for the ways of touching that do it (a delay under the held ones).
 */
@SuppressLint("ViewConstructor")
class GestureControlsView(
    context: Context,
    private val settings: GestureSettings,
    private val onChanged: () -> Unit,
    private val onDone: () -> Unit
) : FrameLayout(context) {

    private var page = GesturePurpose.EYEDROPPER
    private val rail = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val heading = Ui.text(context, "", 20f, bold = true)

    init {
        setBackgroundColor(Ui.SURFACE)
        isClickable = true // nothing reaches the canvas underneath
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        val railCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.PANEL_ROW, 20f, Ui.OUTLINE, 1)
            setPadding(8.dp, 18.dp, 8.dp, 8.dp)
        }
        railCard.addView(Ui.text(context, "Gesture controls", 22f, bold = true).apply { setPadding(14.dp, 0, 0, 14.dp) })
        val railScroll = ScrollView(context)
        railScroll.addView(rail)
        railCard.addView(railScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        row.addView(railCard, LinearLayout.LayoutParams(300.dp, -1).apply { setMargins(10.dp, 10.dp, 0, 10.dp) })

        val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(28.dp, 22.dp, 24.dp, 0) }
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val done = IconView(context, Icon.CHECK).apply {
            background = Ui.rounded(Ui.ACCENT, 22f)
            tint = 0xFFFFFFFF.toInt()
            setOnClickListener { onDone() }
            contentDescription = L10n.tr("Done")
        }
        top.addView(done, LinearLayout.LayoutParams(44.dp, 44.dp))
        right.addView(top)
        val scroll = ScrollView(context)
        scroll.addView(body)
        right.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 18.dp })
        row.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        addView(row, LayoutParams(-1, -1))
        rebuild()
    }

    private fun rebuild() {
        rail.removeAllViews()
        for (p in GesturePurpose.entries) {
            val chosen = p == page
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8.dp, 0, 12.dp, 0)
                background = Ui.rounded(if (chosen) Ui.ACCENT else 0, 26f)
                setOnClickListener { page = p; rebuild() }
            }
            item.addView(IconView(context, p.icon).apply {
                isClickable = false
                tint = if (chosen) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
            }, LinearLayout.LayoutParams(40.dp, 40.dp))
            item.addView(Ui.text(context, p.title, 16f, if (chosen) 0xFFFFFFFF.toInt() else 0xFFB5B5BC.toInt()),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8.dp })
            rail.addView(item, LinearLayout.LayoutParams(-1, 50.dp).apply { bottomMargin = 3.dp })
        }

        heading.text = L10n.tr(page.title)
        body.removeAllViews()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, 22f)
        }
        val opts = GestureSettings.OPTIONS[page].orEmpty()
        opts.forEachIndexed { i, o ->
            if (i > 0) card.addView(View(context).apply { setBackgroundColor(Ui.SURFACE) }, LinearLayout.LayoutParams(-1, 1.dp))
            val on = settings.isOn(page, o.key)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24.dp, 12.dp, 16.dp, 12.dp)
            }
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.text(context, o.label, 16.5f, Ui.TEXT, true))
            texts.addView(Ui.text(context, o.detail, 12.5f, Ui.TEXT_DIM))
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = SwitchView(context).apply {
                checked = on
                onToggle = { v ->
                    settings.set(page, o.key, v)
                    onChanged()
                    rebuild()
                }
            }
            row.addView(sw)
            row.setOnClickListener { sw.performClick() }
            card.addView(row)
            if (o.delayed) card.addView(delayRow(on))
        }
        body.addView(card, LinearLayout.LayoutParams(-1, -2))
        if (page == GesturePurpose.GENERAL) {
            body.addView(Ui.pill(context, "Reset to defaults") {
                android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(L10n.tr("Reset gesture controls?"))
                    .setMessage(L10n.tr("Every gesture goes back to how the app starts out."))
                    .setPositiveButton(L10n.tr("Reset")) { _, _ -> settings.resetAll(); onChanged(); rebuild() }
                    .setNegativeButton(L10n.tr("Cancel"), null)
                    .show()
            }, LinearLayout.LayoutParams(-1, 50.dp).apply { topMargin = 14.dp })
        }
        body.addView(Ui.text(context, alwaysOn(page), 12.5f, Ui.TEXT_DIM).apply { setPadding(8.dp, 16.dp, 8.dp, 40.dp) })
    }

    /** gestures that are always on, said on the page they belong to */
    private fun alwaysOn(p: GesturePurpose): String = when (p) {
        GesturePurpose.GENERAL -> "Always on: two fingers pinch to zoom and move the canvas, two-finger tap undoes, three-finger tap redoes, and a quick pinch in fits the canvas to the screen."
        GesturePurpose.ERASE -> "Always on: drawing with the S Pen button held down erases."
        GesturePurpose.QUICKSHAPE -> "After a shape snaps, keep the pen down and move it to resize or turn the shape. A finger touching the screen meanwhile makes it perfect."
        else -> ""
    }

    private fun delayRow(enabled: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dp, 0, 20.dp, 10.dp)
            alpha = if (enabled) 1f else 0.4f
        }
        row.addView(Ui.text(context, "Delay", 14f), LinearLayout.LayoutParams(70.dp, -2))
        val value = Ui.text(context, fmt(settings.delay(page)), 14f)
        val bar = DelayBar(context, (settings.delay(page) - GestureSettings.MIN_DELAY) / (GestureSettings.MAX_DELAY - GestureSettings.MIN_DELAY)) { f ->
            val s = GestureSettings.MIN_DELAY + f * (GestureSettings.MAX_DELAY - GestureSettings.MIN_DELAY)
            settings.setDelay(page, s)
            value.text = fmt(s)
            onChanged()
        }
        bar.isEnabled = enabled
        row.addView(bar, LinearLayout.LayoutParams(0, 40.dp, 1f))
        row.addView(value, LinearLayout.LayoutParams(64.dp, -2).apply { marginStart = 12.dp })
        return row
    }

    private fun fmt(s: Float) = String.format(Locale.US, "%.2fs", s)

    /** a thin track with a pill knob, dragged or tapped along */
    private class DelayBar(context: Context, private var fraction: Float, private val onChange: (Float) -> Unit) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!isEnabled) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val pad = 10f.dp
                    var f = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
                    if (layoutDirection == LAYOUT_DIRECTION_RTL) f = 1f - f
                    fraction = f
                    onChange(f)
                    invalidate()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val pad = 10f.dp
            val y = height / 2f
            val w = width - 2 * pad
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val x = if (rtl) pad + (1f - fraction) * w else pad + fraction * w
            p.color = 0xFF414148.toInt()
            canvas.drawRect(pad, y - 1.5f.dp, pad + w, y + 1.5f.dp, p)
            p.color = Ui.ACCENT
            if (rtl) canvas.drawRect(x, y - 1.5f.dp, pad + w, y + 1.5f.dp, p)
            else canvas.drawRect(pad, y - 1.5f.dp, x, y + 1.5f.dp, p)
            p.color = 0xFFFFFFFF.toInt()
            canvas.drawRoundRect(RectF(x - 9f.dp, y - 6f.dp, x + 9f.dp, y + 6f.dp), 6f.dp, 6f.dp, p)
        }
    }
}
