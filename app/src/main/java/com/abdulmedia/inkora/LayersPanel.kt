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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
    /** opens the 3D editor for a 3D layer */
    private val onEdit3D: ((Layer) -> Unit)? = null,
    private val onChanged: () -> Unit
) : LinearLayout(context) {

    private val list = LinearLayout(context)
    private var expanded = false
    /** the group whose options are open */
    private var expandedGroup: Int? = null
    private val picked get() = cv.pickedLayers
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
        doc.normalizeGroups()
        val targets = cv.targetLayers()
        if (targets.size > 1) list.addView(pickBar(targets), LayoutParams(-1, -2).apply { bottomMargin = 6.dp })

        var i = doc.layers.size - 1
        while (i >= 0) {
            val layer = doc.layers[i]
            val g = doc.groupOf(layer)
            if (g != null && (i == doc.layers.size - 1 || doc.layers[i + 1].groupId != g.id)) {
                list.addView(groupRow(g), LayoutParams(-1, -2).apply { bottomMargin = 4.dp })
                if (expandedGroup == g.id) list.addView(groupOptions(g), LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
                if (g.collapsed) {
                    // skip the group's layers
                    while (i >= 0 && doc.layers[i].groupId == g.id) i--
                    continue
                }
            }
            addLayerRow(layer, i, g != null)
            i--
        }

        if (doc.layers.size > 1 && targets.size == 1)
            list.addView(Ui.text(context, "Swipe a layer to the right to pick several", 11f, Ui.TEXT_DIM).apply {
                setPadding(6.dp, 2.dp, 6.dp, 4.dp)
            })

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

    private fun addLayerRow(layer: Layer, i: Int, inGroup: Boolean) {
        val isActive = i == doc.activeIndex
        val isPicked = !isActive && layer in picked
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val indent = (if (inGroup) 18.dp else 0) + (if (layer.isClippingMask || layer.isLayerMask) 22.dp else 8.dp)
            setPadding(indent, 6.dp, 4.dp, 6.dp)
            background = Ui.rounded(when {
                isActive -> Ui.PANEL_ROW_ACTIVE
                isPicked -> PICKED
                else -> Ui.PANEL_ROW
            }, 10f)
        }
        val img = ImageView(context)
        img.setImageBitmap(thumbnail(layer))
        row.addView(img, LayoutParams(64.dp, 44.dp))

        val texts = LinearLayout(context).apply { orientation = VERTICAL; setPadding(10.dp, 0, 0, 0) }
        val displayName = when {
            layer.isLayerMask -> "◐ ${L10n.tr(layer.name)}"
            layer.isClippingMask -> "↳ ${layer.name}"
            else -> layer.name
        }
        texts.addView(Ui.text(context, displayName, 14f))
        val sub = buildString {
            if (layer.isClippingMask) append("Clipping Mask · ")
            if (layer.isLayerMask) append("Layer mask · ")
            if (layer.reference) append("Reference · ")
            if (layer.shadow) append("Shadow · ")
            if (layer.scene != null) append("3D · ${layer.scene!!.objects.size} objects · ")
            if (layer.vector != null) append("VECTOR · ${layer.vector!!.shapes.size} shapes · ")
            append(layer.blend.label)
            if (layer.opacity < 0.999f) append(" · ${(layer.opacity * 100).roundToInt()}%")
            if (layer.alphaLock) append(" · Alpha Lock")
        }
        texts.addView(Ui.text(context, sub, 11f, Ui.TEXT_DIM))
        row.addView(texts, LayoutParams(0, -2, 1f))

        // blend mode as a short letter (tap it for the modes), then the visibility checkbox
        row.addView(Ui.text(context, blendCode(layer.blend), 13f, Ui.TEXT_DIM, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { v ->
                val pm = PopupMenu(context, v)
                LayerBlend.values().forEachIndexed { k, b -> pm.menu.add(0, k, k, b.label) }
                pm.setOnMenuItemClickListener { item -> layer.blend = LayerBlend.values()[item.itemId]; changed(); true }
                L10n.menu(pm.menu); pm.show()
            }
        }, LayoutParams(30.dp, 40.dp))
        val eye = CheckBoxView(context, layer.visible)
        eye.contentDescription = "Visible"
        eye.setOnClickListener {
            layer.visible = !layer.visible
            changed()
        }
        row.addView(eye, LayoutParams(40.dp, 40.dp))

        swipeable(row, onSwipe = {
            // pick or un-pick this layer, keeping the active one
            if (!isActive) {
                if (!picked.remove(layer)) picked.add(layer)
                expanded = false; expandedGroup = null
                rebuild(); onChanged()
            }
        }, onTap = {
            if (isActive && picked.isEmpty()) expanded = !expanded else {
                doc.activeIndex = i
                expanded = false
            }
            picked.clear()
            expandedGroup = null
            rebuild()
            onChanged()
        })
        list.addView(row, LayoutParams(-1, -2).apply { bottomMargin = 4.dp })
        if (isActive && expanded && picked.isEmpty()) list.addView(options(layer, i), LayoutParams(-1, -2).apply { bottomMargin = 8.dp })
    }

    /** a tap, or a swipe to the right (Procreate's way to pick several layers) */
    @SuppressLint("ClickableViewAccessibility")
    private fun swipeable(row: View, onSwipe: () -> Unit, onTap: () -> Unit) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var x0 = 0f; var y0 = 0f; var done = false
        row.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; done = false; v.isPressed = true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (!done && dx > 34.dp && kotlin.math.abs(dy) < dx / 2) {
                        done = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        v.isPressed = false
                        post { onSwipe() }
                    } else if (!done && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        // a sideways drag: keep the list from scrolling
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (!done && kotlin.math.abs(e.rawX - x0) < slop * 2 && kotlin.math.abs(e.rawY - y0) < slop * 2) post { onTap() }
                }
                MotionEvent.ACTION_CANCEL -> v.isPressed = false
            }
            true
        }
    }

    // ---------- groups ----------

    private fun groupRow(g: LayerGroup): LinearLayout {
        val members = doc.members(g.id)
        val isSelected = members.isNotEmpty() && doc.active in members && picked.containsAll(members - doc.active)
        val hasActive = doc.active in members
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            background = Ui.rounded(when {
                isSelected -> Ui.PANEL_ROW_ACTIVE
                members.any { it in picked } || hasActive -> GROUP_HAS_ACTIVE
                else -> GROUP_ROW
            }, 10f)
        }
        val arrow = Ui.text(context, if (g.collapsed) "▸" else "▾", 20f, Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                g.collapsed = !g.collapsed
                doc.revision++
                rebuild()
            }
        }
        row.addView(arrow, LayoutParams(40.dp, 44.dp))
        row.addView(IconView(context, Icon.FOLDER), LayoutParams(34.dp, 34.dp))
        val texts = LinearLayout(context).apply { orientation = VERTICAL; setPadding(8.dp, 0, 0, 0) }
        texts.addView(Ui.text(context, g.name, 14f, bold = true))
        val sub = buildString {
            append("Group · ${members.size} · ")
            append(if (g.isolated) g.blend.label else "Pass Through")
            if (g.opacity < 0.999f) append(" · ${(g.opacity * 100).roundToInt()}%")
        }
        texts.addView(Ui.text(context, sub, 11f, Ui.TEXT_DIM))
        row.addView(texts, LayoutParams(0, -2, 1f))
        val eye = IconView(context, if (g.visible) Icon.EYE else Icon.EYE_OFF)
        eye.setOnClickListener {
            g.visible = !g.visible
            changed()
        }
        row.addView(eye, LayoutParams(40.dp, 40.dp))

        swipeable(row, onSwipe = {
            // pick or un-pick all of its layers
            if (members.all { it in picked || it === doc.active }) picked.removeAll(members.toSet())
            else picked.addAll(members.filter { it !== doc.active })
            expanded = false; expandedGroup = null
            rebuild(); onChanged()
        }, onTap = {
            if (isSelected) expandedGroup = if (expandedGroup == g.id) null else g.id
            else {
                // select the whole group: it moves, merges and deletes as one
                selectGroup(g)
                expandedGroup = null
            }
            expanded = false
            rebuild()
            onChanged()
        })
        return row
    }

    private fun selectGroup(g: LayerGroup) {
        val members = doc.members(g.id)
        if (members.isEmpty()) return
        doc.activeIndex = doc.layers.indexOf(members.last())
        picked.clear()
        picked.addAll(members.dropLast(1))
    }

    private fun groupOptions(g: LayerGroup): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(10.dp, 4.dp, 10.dp, 10.dp)
            background = Ui.rounded(0xFF25262D.toInt(), 10f)
        }
        box.addView(Ui.sliderRow(context, "Opacity", g.opacity, { "${(it * 100).roundToInt()}%" }) {
            g.opacity = it
            doc.revision++
            cv.invalidate()
        })
        val blendBtn = Ui.button(context, "Blend mode: " + (if (g.blend == LayerBlend.NORMAL) "Pass Through" else g.blend.label)) {}
        blendBtn.setOnClickListener {
            val pm = PopupMenu(context, blendBtn)
            LayerBlend.values().forEachIndexed { i, b -> pm.menu.add(0, i, i, if (b == LayerBlend.NORMAL) "Pass Through" else b.label) }
            pm.setOnMenuItemClickListener { item ->
                g.blend = LayerBlend.values()[item.itemId]
                changed()
                true
            }
            L10n.menu(pm.menu); pm.show()
        }
        box.addView(blendBtn, LayoutParams(-1, -2).apply { topMargin = 6.dp })
        val actions = listOf(
            "Rename" to { renameGroup(g) },
            "Duplicate" to { duplicateGroup(g) },
            "Merge" to { flattenGroup(g) },
            "Ungroup" to { ungroup(g) },
            "Move Up" to { moveGroup(g, 1) },
            "Move Down" to { moveGroup(g, -1) },
            (if (g.collapsed) "Expand" else "Collapse") to { g.collapsed = !g.collapsed; rebuild() },
            "Delete" to { deleteGroup(g) }
        )
        buttonGrid(box, actions)
        return box
    }

    /** the bar shown while several layers are picked */
    private fun pickBar(targets: List<Layer>): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(10.dp, 8.dp, 10.dp, 10.dp)
            background = Ui.rounded(0xFF1E2C44.toInt(), 12f)
        }
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(context, "Selected layers", 13f, bold = true))
        head.addView(Ui.text(context, "  ${targets.size}", 13f, Ui.ACCENT, bold = true), LayoutParams(0, -2, 1f))
        box.addView(head)
        box.addView(Ui.text(context, "Transform moves them all together", 11f, Ui.TEXT_DIM))
        buttonGrid(box, listOf(
            "Group" to { groupTargets() },
            "Merge" to { mergeTargets() },
            "Delete" to { deleteTargets() },
            "Deselect" to { picked.clear(); rebuild(); onChanged() }
        ), perRow = 4)
        return box
    }

    private fun buttonGrid(box: LinearLayout, actions: List<Pair<String, () -> Unit>>, perRow: Int = 3) {
        var row: LinearLayout? = null
        actions.forEachIndexed { i, (label, act) ->
            if (i % perRow == 0) {
                row = LinearLayout(context).apply { orientation = HORIZONTAL }
                box.addView(row, LayoutParams(-1, -2).apply { topMargin = 6.dp })
            }
            val b = Ui.button(context, label) { act() }
            b.textSize = 12.5f
            if (label == "Delete") b.setTextColor(0xFFF07178.toInt())
            row!!.addView(b, LayoutParams(0, -2, 1f).apply { if (i % perRow != 0) leftMargin = 6.dp })
        }
    }

    /** records a change of the layer stack as one undo step */
    private inline fun structural(label: String, block: () -> Boolean) {
        val before = LayerState(doc)
        if (!block()) return
        doc.normalizeGroups()
        doc.clampActive()
        cv.pushCommand(LayerStructureCmd(before, LayerState(doc), label))
        changed()
    }

    private fun groupTargets() {
        val t = cv.targetLayers()
        val keepActive = doc.active
        var made: LayerGroup? = null
        structural("group") {
            val set = t.toHashSet()
            val g = doc.newGroup()
            made = g
            var at = doc.layers.subList(0, doc.layers.indexOf(t.last()) + 1).count { it !in set }
            doc.layers.removeAll(set)
            // never inside another group
            while (at > 0 && at < doc.layers.size && doc.layers[at - 1].groupId != null &&
                doc.layers[at - 1].groupId == doc.layers[at].groupId) at++
            for (l in t) l.groupId = g.id
            doc.layers.addAll(at, t)
            doc.activeIndex = doc.layers.indexOf(keepActive)
            true
        }
        // the new group stays selected
        made?.let { selectGroup(it) }
        rebuild(); onChanged()
    }

    private fun mergeInto(t: List<Layer>, name: String, label: String, setup: (Layer) -> Unit = {}) {
        if (t.size < 2 && label != "merge group") { cv.listener?.onMessage("Pick at least two layers"); return }
        val merged = try { doc.compose(t) } catch (e: OutOfMemoryError) { null }
            ?: run { cv.listener?.onMessage("Not enough memory for this right now"); return }
        structural(label) {
            val set = t.toHashSet()
            val nl = Layer(merged, name)
            nl.groupId = t.map { it.groupId }.distinct().singleOrNull()
            setup(nl)
            val at = doc.layers.subList(0, doc.layers.indexOf(t.last()) + 1).count { it !in set }
            doc.layers.removeAll(set)
            doc.layers.add(at.coerceIn(0, doc.layers.size), nl)
            doc.activeIndex = doc.layers.indexOf(nl)
            true
        }
        picked.clear()
        cv.vectorEditor.reset()
        rebuild(); onChanged()
    }

    private fun mergeTargets() {
        val t = cv.targetLayers()
        mergeInto(t, t.last().name, "merge")
    }

    private fun deleteTargets() {
        val t = cv.targetLayers()
        if (t.size >= doc.layers.size) { cv.listener?.onMessage("A canvas needs at least one layer"); return }
        structural("deleted layer") {
            val lowest = doc.layers.indexOf(t.first())
            doc.layers.removeAll(t.toHashSet())
            doc.activeIndex = (lowest - 1).coerceAtLeast(0)
            true
        }
        picked.clear()
        rebuild(); onChanged()
    }

    private fun flattenGroup(g: LayerGroup) {
        val members = doc.members(g.id)
        if (members.isEmpty()) return
        mergeInto(members, g.name, "merge group") { nl ->
            nl.groupId = null
            nl.visible = g.visible
            if (g.isolated) { nl.opacity = g.opacity; nl.blend = g.blend }
            doc.groups.remove(g.id)
        }
        expandedGroup = null
    }

    private fun ungroup(g: LayerGroup) {
        structural("ungroup") {
            for (l in doc.members(g.id)) l.groupId = null
            doc.groups.remove(g.id)
            true
        }
        picked.clear()
        expandedGroup = null
        rebuild()
    }

    private fun deleteGroup(g: LayerGroup) {
        val members = doc.members(g.id)
        if (members.size >= doc.layers.size) { cv.listener?.onMessage("A canvas needs at least one layer"); return }
        structural("deleted layer") {
            val lowest = doc.layers.indexOf(members.first())
            doc.layers.removeAll(members.toHashSet())
            doc.groups.remove(g.id)
            doc.activeIndex = (lowest - 1).coerceAtLeast(0)
            true
        }
        picked.clear()
        expandedGroup = null
        rebuild(); onChanged()
    }

    private fun duplicateGroup(g: LayerGroup) {
        val members = doc.members(g.id)
        if (doc.layers.size + members.size > doc.maxLayers()) {
            cv.listener?.onMessage("Layer limit reached for this canvas size")
            return
        }
        val copies = ArrayList<Layer>()
        try {
            for (src in members) copies.add(copyOf(src))
        } catch (e: OutOfMemoryError) {
            copies.forEach { it.bitmap.recycle() }
            cv.listener?.onMessage("Not enough memory for another layer right now")
            return
        }
        var made: LayerGroup? = null
        structural("new layer") {
            val ng = LayerGroup(g.name + " copy")
            made = ng
            ng.visible = g.visible; ng.opacity = g.opacity; ng.blend = g.blend
            doc.groups[ng.id] = ng
            for (c in copies) c.groupId = ng.id
            val top = doc.layers.indexOf(members.last())
            doc.layers.addAll(top + 1, copies)
            doc.activeIndex = doc.layers.indexOf(copies.last())
            true
        }
        made?.let { selectGroup(it) }
        expandedGroup = null
        rebuild(); onChanged()
    }

    /** moves a whole group up (+1) or down (-1), past the next layer or group */
    private fun moveGroup(g: LayerGroup, dir: Int) {
        val r = doc.rangeOf(g.id) ?: return
        val n = if (dir > 0) r.last + 1 else r.first - 1
        if (n < 0 || n >= doc.layers.size) return
        val keepActive = doc.active
        structural("layer order") {
            val nb = doc.layers[n].groupId?.let { doc.rangeOf(it) } ?: n..n
            val block = doc.layers.subList(r.first, r.last + 1).toList()
            doc.layers.removeAll(block.toHashSet())
            val at = if (dir > 0) nb.last - block.size + 1 else nb.first
            doc.layers.addAll(at, block)
            doc.activeIndex = doc.layers.indexOf(keepActive)
            true
        }
    }

    private fun renameGroup(g: LayerGroup) {
        val input = EditText(context)
        input.setText(g.name)
        input.setSelectAllOnFocus(true)
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L10n.tr("Rename group"))
            .setView(input)
            .setPositiveButton(L10n.tr("OK")) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    g.name = t
                    changed()
                }
            }
            .setNegativeButton(L10n.tr("Cancel"), null)
            .show()
    }

    private fun options(layer: Layer, index: Int): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(10.dp, 4.dp, 10.dp, 10.dp)
            background = Ui.rounded(0xFF25262D.toInt(), 10f)
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
            (if (layer.shadow) "Shadow layer: ON" else "Shadow layer: off") to {
                layer.shadow = !layer.shadow; changed()
                cv.listener?.onMessage(if (layer.shadow) "Shadow layer: you see it, but it stays out of the time-lapse and exports"
                    else "This layer shows in the time-lapse and exports again")
                Unit
            },
            (if (layer.reference) "Reference: ON" else "Reference: off") to {
                layer.reference = !layer.reference; changed()
                cv.listener?.onMessage(if (layer.reference) "Reference: Fill and ColorDrop now follow the lines on this layer, on any layer you color"
                    else "Fill looks at the whole artwork again")
                Unit
            },
            "Fill with color" to { fillLayer(layer) },
            "Add layer mask" to { addMask(layer, index) },
            "Duplicate" to { duplicate(layer, index) },
            "Merge Down" to { mergeDown(index) },
            "Clear" to { clear(layer) },
            "Move Up" to { move(index, index + 1) },
            "Move Down" to { move(index, index - 1) },
            (if (layer.groupId != null) "Out of group" else "Group") to {
                if (layer.groupId != null) structural("ungroup") { leaveGroup(layer); true } else groupTargets()
            },
            "Delete" to { delete(layer, index) }
        )
        if (layer.vector != null) {
            actions.removeAll { it.first.startsWith("Alpha Lock") || it.first == "Fill with color" }
            actions.add(actions.size - 1, "Rasterize" to { rasterize(layer) })
        }
        if (doc.hasMask(index) || layer.isLayerMask) actions.removeAll { it.first == "Add layer mask" }
        if (layer.isLayerMask) {
            actions.removeAll { it.first.startsWith("Clipping Mask") || it.first.startsWith("Alpha Lock") || it.first.startsWith("Shadow layer") ||
                it.first.startsWith("Reference") || it.first == "Fill with color" || it.first == "Merge Down" || it.first == "Duplicate" }
            actions.add(1, "Apply mask" to { applyMask(index) })
            actions.add(2, "Invert mask" to { invertMask(layer) })
            actions.add(3, "Show all" to { fillWhite(layer) })
        }
        if (layer.scene != null) {
            actions.removeAll { it.first.startsWith("Alpha Lock") || it.first == "Fill with color" }
            actions.add(1, "Edit 3D scene" to { onEdit3D?.invoke(layer); Unit })
            actions.add(actions.size - 1, "Rasterize" to { layer.scene = null; changed() })
        }
        buttonGrid(box, actions)
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
        val layer = try { doc.createLayer() } catch (e: OutOfMemoryError) {
            cv.listener?.onMessage("Not enough memory for another layer right now")
            return
        }
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
        val copy = try { src.bitmap.copy(Bitmap.Config.ARGB_8888, true) } catch (e: OutOfMemoryError) { null }
        if (copy == null) { cv.listener?.onMessage("Not enough memory for another layer right now"); return }
        val l = Layer(copy, src.name + " copy")
        l.copySettingsFrom(src)
        l.vector = src.vector?.copy()
        l.groupId = src.groupId
        val cmd = AddLayerCmd(l, index + 1, index)
        cmd.redo(doc)
        cv.pushCommand(cmd)
        changed()
    }

    // ---------- layer masks ----------

    private fun addMask(layer: Layer, index: Int) {
        if (doc.layers.size >= doc.maxLayers()) { cv.listener?.onMessage("Layer limit reached for this canvas size"); return }
        val bmp = try { doc.newLayerBitmap() } catch (e: OutOfMemoryError) { null }
        if (bmp == null) { cv.listener?.onMessage("Not enough memory for another layer right now"); return }
        bmp.eraseColor(0xFFFFFFFF.toInt())
        val m = Layer(bmp, "Mask")
        m.isLayerMask = true
        m.groupId = layer.groupId
        val cmd = AddLayerCmd(m, index + 1, index)
        cmd.redo(doc)
        cv.pushCommand(cmd)
        expanded = false
        cv.listener?.onMessage("Layer mask: erase to hide parts of the layer, paint to bring them back")
        changed()
    }

    /** the mask at [index] is cut into the layer below it and removed */
    private fun applyMask(index: Int) {
        if (index <= 0) return
        val mask = doc.layers[index]
        val base = doc.layers[index - 1]
        if (base.vector != null) { cv.listener?.onMessage("Can't merge into a vector layer (rasterize it first)"); return }
        val before = base.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (mask.visible) Canvas(base.bitmap).drawBitmap(mask.bitmap, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_IN })
        val after = base.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        doc.layers.remove(mask)
        doc.activeIndex = index - 1
        cv.pushCommand(MergeDownCmd(mask, index, base, before, after))
        expanded = false
        changed()
    }

    /** what was hidden shows, what showed is hidden */
    private fun invertMask(mask: Layer) {
        val before = mask.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val inv = doc.newLayerBitmap()
        inv.eraseColor(0xFFFFFFFF.toInt())
        Canvas(inv).drawBitmap(mask.bitmap, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_OUT })
        mask.bitmap.eraseColor(0)
        Canvas(mask.bitmap).drawBitmap(inv, 0f, 0f, null)
        inv.recycle()
        cv.pushPixelEdit(mask, Rect(0, 0, doc.width, doc.height), before)
        changed()
    }

    private fun fillWhite(mask: Layer) {
        val before = mask.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        mask.bitmap.eraseColor(0xFFFFFFFF.toInt())
        cv.pushPixelEdit(mask, Rect(0, 0, doc.width, doc.height), before)
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

    /** a copy of [src] (pixels, settings, vector shapes), not in any group yet */
    private fun copyOf(src: Layer): Layer {
        val l = Layer(src.bitmap.copy(Bitmap.Config.ARGB_8888, true), src.name)
        l.copySettingsFrom(src)
        l.vector = src.vector?.copy()
        return l
    }

    /** takes [layer] out of its group: it goes just above (or below) the group's other layers */
    private fun leaveGroup(layer: Layer) {
        val id = layer.groupId ?: return
        val r = doc.rangeOf(id) ?: return
        doc.layers.remove(layer)
        layer.groupId = null
        doc.layers.add(r.last.coerceIn(0, doc.layers.size), layer) // r.last is now just above the group
        doc.activeIndex = doc.layers.indexOf(layer)
        if (doc.members(id).isEmpty()) doc.groups.remove(id)
    }

    /**
     * Moves a layer one step. At the edge of a group it steps out of it first; next to a group it
     * steps into it (like Procreate), so a group's layers always stay together.
     */
    private fun move(from: Int, to: Int) {
        if (from !in doc.layers.indices) return
        val l = doc.layers[from]
        if (to < 0 || to >= doc.layers.size) {
            if (l.groupId == null) return
            structural("layer order") { l.groupId = null; true }
            return
        }
        val n = doc.layers[to]
        structural("layer order") {
            when {
                l.groupId != null && n.groupId != l.groupId -> l.groupId = null
                l.groupId == null && n.groupId != null -> l.groupId = n.groupId
                else -> { doc.layers.removeAt(from); doc.layers.add(to, l) }
            }
            doc.activeIndex = doc.layers.indexOf(l)
            true
        }
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

    companion object {
        private const val PICKED = 0xFF35507A.toInt()
        private const val GROUP_ROW = 0xFF2F2F37.toInt()
        private const val GROUP_HAS_ACTIVE = 0xFF2B3446.toInt()
    }
}

/** short letters for the blend modes, shown on each layer row */
fun blendCode(b: LayerBlend): String = when (b) {
    LayerBlend.NORMAL -> "N"; LayerBlend.MULTIPLY -> "M"; LayerBlend.DARKEN -> "D"; LayerBlend.COLOR_BURN -> "CB"
    LayerBlend.LIGHTEN -> "L"; LayerBlend.SCREEN -> "S"; LayerBlend.COLOR_DODGE -> "CD"; LayerBlend.ADD -> "A"
    LayerBlend.OVERLAY -> "O"; LayerBlend.SOFT_LIGHT -> "SL"; LayerBlend.HARD_LIGHT -> "HL"; LayerBlend.DIFFERENCE -> "Di"
    LayerBlend.EXCLUSION -> "E"; LayerBlend.HUE -> "H"; LayerBlend.SATURATION -> "Sa"; LayerBlend.COLOR -> "C"
    LayerBlend.LUMINOSITY -> "Lu"
}

/** a rounded checkbox drawn in code: checked = the layer shows */
@SuppressLint("ViewConstructor")
class CheckBoxView(context: Context, private val checked: Boolean) : View(context) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    init { isClickable = true }
    override fun onDraw(c: Canvas) {
        val s = 20f.dp
        val l = (width - s) / 2; val t = (height - s) / 2
        val r = android.graphics.RectF(l, t, l + s, t + s)
        if (checked) {
            p.style = android.graphics.Paint.Style.FILL; p.color = Ui.ACCENT
            c.drawRoundRect(r, 5f.dp, 5f.dp, p)
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 2.4f.dp; p.color = 0xFFFFFFFF.toInt()
            p.strokeCap = android.graphics.Paint.Cap.ROUND; p.strokeJoin = android.graphics.Paint.Join.ROUND
            path.reset()
            path.moveTo(l + s * 0.25f, t + s * 0.52f); path.lineTo(l + s * 0.43f, t + s * 0.7f); path.lineTo(l + s * 0.76f, t + s * 0.32f)
            c.drawPath(path, p)
        } else {
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 1.6f.dp; p.color = 0x99FFFFFF.toInt()
            c.drawRoundRect(r, 5f.dp, 5f.dp, p)
        }
    }
}
