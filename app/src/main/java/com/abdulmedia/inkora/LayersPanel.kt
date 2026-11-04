package com.abdulmedia.inkora

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class LayersPanel(
    context: Context,
    private val cv: CanvasView,
    private val currentColor: () -> Int,
    private val onAddVector: () -> Unit = {},
    private val onPin: (() -> Unit)? = null,
    private val onChanged: () -> Unit
) : LinearLayout(context) {

    private val list = LinearLayout(context)
    private var expanded = false
    private val doc get() = cv.doc

    init {
        orientation = VERTICAL
        setPadding(14.dp, 12.dp, 14.dp, 14.dp)
        background = Ui.rounded(Ui.PANEL, 18f)

        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(context, "Layers", 17f, bold = true), LayoutParams(0, -2, 1f))
        if (onPin != null) {
            val pin = IconView(context, Icon.PIN)
            pin.setOnClickListener { onPin.invoke() }
            head.addView(pin, LayoutParams(40.dp, 40.dp))
        }
        val plus = IconView(context, Icon.PLUS)
        plus.setOnClickListener {
            val pm = PopupMenu(context, plus)
            pm.menu.add(0, 1, 1, "Drawing layer")
            pm.menu.add(0, 2, 2, "Vector layer (pen, shapes, logos)")
            pm.setOnMenuItemClickListener {
                if (it.itemId == 1) addLayer() else onAddVector()
                true
            }
            L10n.menu(pm.menu); pm.show()
        }
        head.addView(plus, LayoutParams(44.dp, 44.dp))
        addView(head)

        list.orientation = VERTICAL
        val scroll = ScrollView(context)
        scroll.addView(list)
        addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = 6.dp })
        rebuild()
    }

    private fun changed() {
        doc.revision++
        cv.invalidate()
        onChanged()
        rebuild()
    }

    fun rebuild() {
        list.removeAllViews()
        for (i in doc.layers.indices.reversed()) {
            val layer = doc.layers[i]
            val isActive = i == doc.activeIndex
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((if (layer.isClippingMask) 22.dp else 8.dp), 6.dp, 4.dp, 6.dp)
                background = Ui.rounded(if (isActive) Ui.PANEL_ROW_ACTIVE else Ui.PANEL_ROW, 10f)
            }
            val img = ImageView(context)
            img.setImageBitmap(thumbnail(layer))
            row.addView(img, LayoutParams(64.dp, 44.dp))

            val texts = LinearLayout(context).apply { orientation = VERTICAL; setPadding(10.dp, 0, 0, 0) }
            val displayName = if (layer.isClippingMask) "↳ ${layer.name}" else layer.name
            texts.addView(Ui.text(context, displayName, 14f))
            val sub = buildString {
                if (layer.isClippingMask) append("Clipping Mask · ")
                if (layer.vector != null) append("VECTOR · ${layer.vector!!.shapes.size} shapes · ")
                append(layer.blend.label)
                if (layer.opacity < 0.999f) append(" · ${(layer.opacity * 100).roundToInt()}%")
                if (layer.alphaLock) append(" · Alpha Lock")
            }
            texts.addView(Ui.text(context, sub, 11f, Ui.TEXT_DIM))
            row.addView(texts, LayoutParams(0, -2, 1f))

            val eye = IconView(context, if (layer.visible) Icon.EYE else Icon.EYE_OFF)
            eye.setOnClickListener {
                layer.visible = !layer.visible
                changed()
            }
            row.addView(eye, LayoutParams(40.dp, 40.dp))

            row.setOnClickListener {
                if (isActive) expanded = !expanded else {
                    doc.activeIndex = i
                    expanded = false
                }
                rebuild()
                onChanged()
            }
            list.addView(row, LayoutParams(-1, -2).apply { bottomMargin = 4.dp })
            if (isActive && expanded) list.addView(options(layer, i), LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
        }

        // background row
        val bg = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 6.dp, 4.dp, 6.dp)
            background = Ui.rounded(Ui.PANEL_ROW, 10f)
        }
        val sw = ColorSwatchView(context)
        sw.color = doc.backgroundColor
        bg.addView(sw, LayoutParams(44.dp, 44.dp))
        val bt = LinearLayout(context).apply { orientation = VERTICAL; setPadding(10.dp, 0, 0, 0) }
        bt.addView(Ui.text(context, "Background color", 14f))
        bt.addView(Ui.text(context, "Tap to use the current color", 11f, Ui.TEXT_DIM))
        bg.addView(bt, LayoutParams(0, -2, 1f))
        val bgEye = IconView(context, if (doc.backgroundVisible) Icon.EYE else Icon.EYE_OFF)
        bgEye.setOnClickListener {
            doc.backgroundVisible = !doc.backgroundVisible
            changed()
        }
        bg.addView(bgEye, LayoutParams(40.dp, 40.dp))
        bg.setOnClickListener {
            doc.backgroundColor = currentColor() or 0xFF000000.toInt()
            changed()
        }
        list.addView(bg, LayoutParams(-1, -2).apply { topMargin = 4.dp })
    }

    private fun options(layer: Layer, index: Int): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(10.dp, 4.dp, 10.dp, 10.dp)
            background = Ui.rounded(0xFF24242A.toInt(), 10f)
        }
        box.addView(Ui.sliderRow(context, "Opacity", layer.opacity, { "${(it * 100).roundToInt()}%" }) {
            layer.opacity = it
            doc.revision++
            cv.invalidate()
        })

        val blendBtn = Ui.button(context, "Blend mode: ${layer.blend.label}") {}
        blendBtn.setOnClickListener {
            val pm = PopupMenu(context, blendBtn)
            LayerBlend.values().forEachIndexed { i, b -> pm.menu.add(0, i, i, b.label) }
            pm.setOnMenuItemClickListener { item ->
                layer.blend = LayerBlend.values()[item.itemId]
                changed()
                true
            }
            L10n.menu(pm.menu); pm.show()
        }
        box.addView(blendBtn, LayoutParams(-1, -2).apply { topMargin = 6.dp })

        val actions = mutableListOf(
            "Rename" to { rename(layer) },
            (if (layer.isClippingMask) "Clipping Mask: ON" else "Clipping Mask: off") to {
                layer.isClippingMask = !layer.isClippingMask; changed()
            },
            (if (layer.alphaLock) "Alpha Lock: ON" else "Alpha Lock: off") to {
                layer.alphaLock = !layer.alphaLock; changed()
            },
            "Fill with color" to { fillLayer(layer) },
            "Duplicate" to { duplicate(layer, index) },
            "Merge Down" to { mergeDown(index) },
            "Clear" to { clear(layer) },
            "Move Up" to { move(index, index + 1) },
            "Move Down" to { move(index, index - 1) },
            "Delete" to { delete(layer, index) }
        )
        if (layer.vector != null) {
            actions.removeAll { it.first.startsWith("Alpha Lock") || it.first == "Fill with color" }
            actions.add(actions.size - 1, "Rasterize" to { rasterize(layer) })
        }
        var row: LinearLayout? = null
        actions.forEachIndexed { i, (label, act) ->
            if (i % 3 == 0) {
                row = LinearLayout(context).apply { orientation = HORIZONTAL }
                box.addView(row, LayoutParams(-1, -2).apply { topMargin = 6.dp })
            }
            val b = Ui.button(context, label) { act() }
            b.textSize = 12.5f
            if (label == "Delete") b.setTextColor(0xFFFF6B6B.toInt())
            row!!.addView(b, LayoutParams(0, -2, 1f).apply { if (i % 3 != 0) leftMargin = 6.dp })
        }
        return box
    }

    private fun thumbnail(layer: Layer): Bitmap {
        val tw = 128
        val th = 88
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFD8D8DC.toInt())
        val s = min(tw / doc.width.toFloat(), th / doc.height.toFloat())
        val w = doc.width * s
        val h = doc.height * s
        val dst = RectF((tw - w) / 2, (th - h) / 2, (tw + w) / 2, (th + h) / 2)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.color = if (doc.backgroundVisible) doc.backgroundColor else 0xFFFFFFFF.toInt()
        c.drawRect(dst, p)
        c.drawBitmap(layer.bitmap, null, dst, p)
        return out
    }

    // ---------- actions ----------

    private fun addLayer() {
        if (doc.layers.size >= doc.maxLayers()) {
            cv.listener?.onMessage("Layer limit reached for this canvas size")
            return
        }
        val layer = doc.createLayer()
        val cmd = AddLayerCmd(layer, doc.activeIndex + 1, doc.activeIndex)
        cmd.redo(doc)
        cv.pushCommand(cmd)
        expanded = false
        changed()
    }

    private fun duplicate(src: Layer, index: Int) {
        if (doc.layers.size >= doc.maxLayers()) {
            cv.listener?.onMessage("Layer limit reached for this canvas size")
            return
        }
        val l = Layer(src.bitmap.copy(Bitmap.Config.ARGB_8888, true), src.name + " copy")
        l.opacity = src.opacity; l.blend = src.blend; l.alphaLock = src.alphaLock; l.isClippingMask = src.isClippingMask; l.visible = src.visible
        l.vector = src.vector?.copy()
        val cmd = AddLayerCmd(l, index + 1, index)
        cmd.redo(doc)
        cv.pushCommand(cmd)
        changed()
    }

    private fun mergeDown(index: Int) {
        if (index <= 0) {
            cv.listener?.onMessage("Nothing below to merge into")
            return
        }
        val upper = doc.layers[index]
        val lower = doc.layers[index - 1]
        if (lower.vector != null) {
            cv.listener?.onMessage("Can't merge into a vector layer (rasterize it first)")
            return
        }
        val before = lower.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (upper.visible) {
            val p = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (upper.opacity * 255).roundToInt()
                blendMode = upper.blend.mode
            }
            Canvas(lower.bitmap).drawBitmap(upper.bitmap, 0f, 0f, p)
        }
        val after = lower.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        doc.layers.remove(upper)
        doc.activeIndex = index - 1
        cv.pushCommand(MergeDownCmd(upper, index, lower, before, after))
        expanded = false
        changed()
    }

    private fun clear(layer: Layer) {
        layer.vector?.let { v ->
            val before = v.copyShapes()
            v.shapes.clear()
            v.render(layer.bitmap)
            cv.vectorEditor.reset()
            cv.pushCommand(VectorEdit(layer, before, emptyList()))
            changed()
            return
        }
        val r = Rect(0, 0, doc.width, doc.height)
        val before = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        layer.bitmap.eraseColor(0)
        cv.pushPixelEdit(layer, r, before)
        changed()
    }

    private fun fillLayer(layer: Layer) {
        val r = Rect(0, 0, doc.width, doc.height)
        val before = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val p = Paint().apply {
            color = currentColor() or 0xFF000000.toInt()
            blendMode = if (layer.alphaLock) BlendMode.SRC_ATOP else BlendMode.SRC_OVER
        }
        Canvas(layer.bitmap).drawRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat(), p)
        cv.pushPixelEdit(layer, r, before)
        changed()
    }

    private fun move(from: Int, to: Int) {
        if (to < 0 || to >= doc.layers.size) return
        val cmd = MoveLayerCmd(from, to)
        cmd.redo(doc)
        cv.pushCommand(cmd)
        changed()
    }

    private fun delete(layer: Layer, index: Int) {
        if (doc.layers.size <= 1) {
            cv.listener?.onMessage("A canvas needs at least one layer")
            return
        }
        val cmd = DeleteLayerCmd(layer, index)
        cmd.redo(doc)
        cv.pushCommand(cmd)
        expanded = false
        changed()
    }

    private fun rasterize(layer: Layer) {
        val v = layer.vector ?: return
        layer.vector = null
        cv.vectorEditor.reset()
        cv.pushCommand(RasterizeCmd(layer, v))
        cv.listener?.onMessage("Now a drawing layer")
        changed()
    }

    private fun rename(layer: Layer) {
        val input = EditText(context)
        input.setText(layer.name)
        input.setSelectAllOnFocus(true)
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Rename layer"))
            .setView(input)
            .setPositiveButton(L10n.tr("OK")) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    layer.name = t
                    changed()
                }
            }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }
}
