package com.abdulmedia.inkora

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

interface Command {
    val bytes: Long
    fun undo(doc: Document)
    fun redo(doc: Document)
}

private val SRC_PAINT = Paint().apply { blendMode = BlendMode.SRC }

private fun paste(target: Bitmap, src: Bitmap, x: Int, y: Int) {
    Canvas(target).drawBitmap(src, x.toFloat(), y.toFloat(), SRC_PAINT)
}

/** A change of pixels inside one rectangle of one layer. */
class PixelEdit(
    private val layer: Layer,
    private val rect: Rect,
    private val before: Bitmap,
    private val after: Bitmap
) : Command {
    override val bytes: Long = before.byteCount.toLong() + after.byteCount
    override fun undo(doc: Document) = paste(layer.bitmap, before, rect.left, rect.top)
    override fun redo(doc: Document) = paste(layer.bitmap, after, rect.left, rect.top)
}

/** A 3D layer drawn again: its pixels, its scene and whether it is a shadow layer. */
class Scene3DCmd(
    private val layer: Layer,
    private val beforeBmp: Bitmap, private val beforeScene: Scene3D?, private val beforeShadow: Boolean,
    private val afterBmp: Bitmap, private val afterScene: Scene3D?, private val afterShadow: Boolean
) : Command {
    override val bytes: Long = beforeBmp.byteCount.toLong() + afterBmp.byteCount
    override fun undo(doc: Document) { paste(layer.bitmap, beforeBmp, 0, 0); layer.scene = beforeScene; layer.shadow = beforeShadow }
    override fun redo(doc: Document) { paste(layer.bitmap, afterBmp, 0, 0); layer.scene = afterScene; layer.shadow = afterShadow }
}

class AddLayerCmd(private val layer: Layer, index: Int, private val prevActive: Int) : Command {
    private var index = index
    override val bytes: Long = layer.bitmap.byteCount.toLong()
    override fun undo(doc: Document) {
        doc.layers.remove(layer)
        doc.activeIndex = prevActive
        doc.clampActive()
    }
    override fun redo(doc: Document) {
        var at = index.coerceIn(0, doc.layers.size)
        if (firstAdd) {
            // a layer and its mask stay together: the new layer goes above the mask, not between them
            if (!layer.isLayerMask && at > 0 && doc.hasMask(at - 1)) at++
            index = at
            // a new layer added above a layer of a group joins that group
            if (layer.groupId == null && at > 0) layer.groupId = doc.layers[at - 1].groupId
            firstAdd = false
        }
        doc.layers.add(at, layer)
        doc.normalizeGroups()
        doc.activeIndex = doc.layers.indexOf(layer)
    }
    private var firstAdd = true
}

/** Everything about the layer stack except pixels: order, groups and the active layer. */
class LayerState(doc: Document) {
    val layers: List<Layer> = ArrayList(doc.layers)
    val groupIds: List<Int?> = doc.layers.map { it.groupId }
    val groups: List<LayerGroup> = doc.groups.values.map { it.copy() }
    val active = doc.activeIndex

    fun restore(doc: Document) {
        doc.layers.clear()
        doc.layers.addAll(layers)
        layers.forEachIndexed { i, l -> l.groupId = groupIds[i] }
        doc.groups.clear()
        for (g in groups) doc.groups[g.id] = g.copy()
        doc.activeIndex = active
        doc.clampActive()
    }
}

/** A change of the layer stack (grouping, several layers deleted or merged at once). */
class LayerStructureCmd(private val before: LayerState, private val after: LayerState, val label: String) : Command {
    override val bytes: Long = run {
        val a = before.layers.toHashSet(); val b = after.layers.toHashSet()
        (before.layers.filter { it !in b } + after.layers.filter { it !in a }).sumOf { it.bitmap.byteCount.toLong() } + 256
    }
    override fun undo(doc: Document) = before.restore(doc)
    override fun redo(doc: Document) = after.restore(doc)
}

/** Several commands as one step (a transform of several layers). */
class MultiCmd(private val cmds: List<Command>, val label: String) : Command {
    override val bytes: Long = cmds.sumOf { it.bytes }
    override fun undo(doc: Document) { for (c in cmds.asReversed()) c.undo(doc) }
    override fun redo(doc: Document) { for (c in cmds) c.redo(doc) }
}

class DeleteLayerCmd(private val layer: Layer, private val index: Int) : Command {
    override val bytes: Long = layer.bitmap.byteCount.toLong()
    override fun undo(doc: Document) {
        doc.layers.add(index.coerceIn(0, doc.layers.size), layer)
        doc.activeIndex = doc.layers.indexOf(layer)
    }
    override fun redo(doc: Document) {
        doc.layers.remove(layer)
        doc.activeIndex = index - 1
        doc.clampActive()
    }
}

class MoveLayerCmd(private val from: Int, private val to: Int) : Command {
    override val bytes: Long = 64
    private fun move(doc: Document, a: Int, b: Int) {
        val l = doc.layers.removeAt(a)
        doc.layers.add(b, l)
        doc.activeIndex = b
    }
    override fun undo(doc: Document) = move(doc, to, from)
    override fun redo(doc: Document) = move(doc, from, to)
}

class MergeDownCmd(
    private val upper: Layer,
    private val upperIndex: Int,
    private val lower: Layer,
    private val lowerBefore: Bitmap,
    private val lowerAfter: Bitmap
) : Command {
    override val bytes: Long = lowerBefore.byteCount.toLong() * 2
    override fun undo(doc: Document) {
        paste(lower.bitmap, lowerBefore, 0, 0)
        doc.layers.add(upperIndex.coerceIn(0, doc.layers.size), upper)
        doc.activeIndex = doc.layers.indexOf(upper)
    }
    override fun redo(doc: Document) {
        paste(lower.bitmap, lowerAfter, 0, 0)
        doc.layers.remove(upper)
        doc.activeIndex = doc.layers.indexOf(lower)
        doc.clampActive()
    }
}

class History {
    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()
    private val maxBytes = 700L * 1024 * 1024
    private val maxSteps = 120

    /** what the next undo / redo will change, for the on-screen message */
    val undoLabel get() = undoStack.lastOrNull()?.let { labelOf(it) }
    val redoLabel get() = redoStack.lastOrNull()?.let { labelOf(it) }

    private fun labelOf(c: Command) = when (c) {
        is AddLayerCmd -> "new layer"
        is DeleteLayerCmd -> "deleted layer"
        is MoveLayerCmd -> "layer order"
        is MergeDownCmd -> "merge"
        is VectorEdit -> "shape edit"
        is RasterizeCmd -> "rasterize"
        is LayerStructureCmd -> c.label
        is MultiCmd -> c.label
        else -> "drawing"
    }

    val canUndo get() = undoStack.isNotEmpty()

    /** goes up with every change and down with every undo: a tool remembers it to take back all it did */
    var mark = 0L
        private set

    /** the command the next undo would take back */
    fun top(): Command? = undoStack.lastOrNull()
    val canRedo get() = redoStack.isNotEmpty()

    fun push(cmd: Command, doc: Document) {
        undoStack.addLast(cmd)
        redoStack.clear()
        mark++
        doc.revision++
        trim()
    }

    fun undo(doc: Document): Boolean {
        val c = undoStack.removeLastOrNull() ?: return false
        c.undo(doc)
        redoStack.addLast(c)
        mark--
        doc.revision++
        return true
    }

    fun redo(doc: Document): Boolean {
        val c = redoStack.removeLastOrNull() ?: return false
        c.redo(doc)
        undoStack.addLast(c)
        mark++
        doc.revision++
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun trim() {
        var total = undoStack.sumOf { it.bytes } + redoStack.sumOf { it.bytes }
        while (undoStack.size > 1 && (undoStack.size > maxSteps || total > maxBytes)) {
            total -= undoStack.removeFirst().bytes
        }
    }
}
