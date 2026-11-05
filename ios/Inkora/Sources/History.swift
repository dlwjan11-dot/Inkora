import Foundation

/// One step that can be undone and redone. [rect] is what to redraw (nil = everything).
struct HistoryStep {
    let undo: () -> Void
    let redo: () -> Void
    let bytes: Int
    let rect: IntRect?
}

/// Undo and redo, with a memory budget: the oldest steps go first.
final class History {
    private var steps: [HistoryStep] = []
    private var index = 0
    private var bytes = 0
    var budget = 500_000_000
    var onChange: (() -> Void)?

    var canUndo: Bool { index > 0 }
    var canRedo: Bool { index < steps.count }

    func push(_ s: HistoryStep) {
        while steps.count > index { bytes -= steps.removeLast().bytes }
        steps.append(s)
        index = steps.count
        bytes += s.bytes
        while bytes > budget && steps.count > 1 {
            bytes -= steps.removeFirst().bytes
            index -= 1
        }
        onChange?()
    }

    @discardableResult
    func undo() -> HistoryStep? {
        guard canUndo else { return nil }
        index -= 1
        let s = steps[index]
        s.undo()
        onChange?()
        return s
    }

    @discardableResult
    func redo() -> HistoryStep? {
        guard canRedo else { return nil }
        let s = steps[index]
        index += 1
        s.redo()
        onChange?()
        return s
    }

    func clear() {
        steps.removeAll()
        index = 0
        bytes = 0
        onChange?()
    }

    /// a change of pixels in one rectangle of a layer
    static func pixels(_ layer: Layer, _ r: IntRect, before: Data, after: Data) -> HistoryStep {
        HistoryStep(undo: { layer.setRegion(r, before) }, redo: { layer.setRegion(r, after) },
                    bytes: before.count + after.count, rect: r)
    }

    /// a change of the layer list (added, deleted, moved, merged...)
    static func layers(_ doc: Document, before: [Layer], activeBefore: Int, after: [Layer], activeAfter: Int,
                       extraBytes: Int = 0, undoExtra: (() -> Void)? = nil, redoExtra: (() -> Void)? = nil) -> HistoryStep {
        HistoryStep(undo: {
            doc.layers = before
            doc.activeIndex = activeBefore
            undoExtra?()
        }, redo: {
            doc.layers = after
            doc.activeIndex = activeAfter
            redoExtra?()
        }, bytes: extraBytes + 64, rect: nil)
    }
}
