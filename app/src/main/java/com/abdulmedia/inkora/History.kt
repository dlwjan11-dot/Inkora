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

class AddLayerCmd(private val layer: Layer, private val index: Int, private val prevActive: Int) : Command {
    override val bytes: Long = layer.bitmap.byteCount.toLong()
    override fun undo(doc: Document) {
        doc.layers.remove(layer)
        doc.activeIndex = prevActive
        doc.clampActive()
    }
    override fun redo(doc: Document) {
        doc.layers.add(index.coerceIn(0, doc.layers.size), layer)
        doc.activeIndex = doc.layers.indexOf(layer)
    }
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
        else -> "drawing"
    }

    val canUndo get() = undoStack.isNotEmpty()

    /** the command the next undo would take back */
    fun top(): Command? = undoStack.lastOrNull()
    val canRedo get() = redoStack.isNotEmpty()

    fun push(cmd: Command, doc: Document) {
        undoStack.addLast(cmd)
        redoStack.clear()
        doc.revision++
        trim()
    }

    fun undo(doc: Document): Boolean {
        val c = undoStack.removeLastOrNull() ?: return false
        c.undo(doc)
        redoStack.addLast(c)
        doc.revision++
        return true
    }

    fun redo(doc: Document): Boolean {
        val c = redoStack.removeLastOrNull() ?: return false
        c.redo(doc)
        undoStack.addLast(c)
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
