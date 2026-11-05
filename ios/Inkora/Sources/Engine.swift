import UIKit

/// One brush of the MyPaint engine (libmypaint, ISC license), loaded from a .myb settings file.
final class EngineBrush {
    let handle: OpaquePointer

    init?() {
        guard let h = ink_brush_new() else { return nil }
        handle = h
    }

    deinit { ink_brush_free(handle) }

    @discardableResult
    func load(_ json: String) -> Bool { ink_brush_load(handle, json) == 1 }
    func set(_ name: String, _ value: Float) { ink_brush_set(handle, name, value) }
    func get(_ name: String) -> Float { ink_brush_get(handle, name) }
    func setColor(_ c: HSB) { ink_brush_color(handle, c.h, c.s, c.b) }
}

/// What a stroke paints with: the brush file plus the user's size, opacity and color.
struct StrokeStyle {
    var preset: BrushPreset
    var size: Float          // diameter in canvas pixels
    var opacity: Float       // 0...1
    var color: HSB
    var erasing = false
    var smudging = false
    var alphaLock = false
}

/**
 Paints strokes onto a layer with the MyPaint engine. The pixels of the layer are read in as
 the stroke reaches them, painted, and written straight back; the layer as it was is kept by
 the engine for the parts the stroke touched, which is what undo stores.
 */
final class Painter {
    private let canvas: OpaquePointer
    let width: Int
    let height: Int
    private let brush: EngineBrush
    private var events: [Float] = []
    private var lastTime: TimeInterval = 0
    private(set) var dirty = IntRect(x: 0, y: 0, w: 0, h: 0)
    private(set) var active = false
    var viewZoom: Float = 1
    var viewRotation: Float = 0

    init?(width: Int, height: Int) {
        guard let c = ink_canvas_new(Int32(width), Int32(height)), let b = EngineBrush() else { return nil }
        canvas = c
        brush = b
        self.width = width
        self.height = height
    }

    deinit { ink_canvas_free(canvas) }

    func begin(_ style: StrokeStyle, symmetry: Symmetry, time: TimeInterval) -> Bool {
        guard let json = style.preset.json else { return false }
        if !brush.load(json) { return false }
        Painter.configure(brush, style)
        let s = symmetry.engine
        ink_canvas_symmetry(canvas, s.type, Float(width) / 2, Float(height) / 2, 0, s.lines)
        ink_stroke_begin(canvas, brush.handle)
        events.removeAll(keepingCapacity: true)
        lastTime = time
        dirty = IntRect(x: 0, y: 0, w: 0, h: 0)
        active = true
        return true
    }

    /// one pen position in canvas pixels; tilt -1...1
    func add(x: Float, y: Float, pressure: Float, xtilt: Float, ytilt: Float, time: TimeInterval) {
        guard active else { return }
        let dt = max(0.0005, time - lastTime)
        lastTime = time
        events.append(contentsOf: [x, y, max(0, min(1, pressure)), xtilt, ytilt, Float(dt)])
    }

    /// paints what was added; returns the changed part of the layer
    func flush(into layer: Layer) -> IntRect? {
        guard active, !events.isEmpty else { return nil }
        var out: [Int32] = [0, 0, 0, 0]
        let n = Int32(events.count / 6)
        let changed = events.withUnsafeBufferPointer { ev in
            out.withUnsafeMutableBufferPointer { o in
                ink_stroke(canvas, brush.handle, layer.pixels, Int32(layer.stride), ev.baseAddress, n,
                           viewZoom, viewRotation, o.baseAddress)
            }
        }
        events.removeAll(keepingCapacity: true)
        guard changed == 1 else { return nil }
        let r = IntRect(x: Int(out[0]), y: Int(out[1]), w: Int(out[2]), h: Int(out[3]))
            .intersection(IntRect(x: 0, y: 0, w: width, h: height))
        if r.isEmpty { return nil }
        dirty = dirty.union(r)
        return r
    }

    /// the layer's pixels in rect as they were before this stroke
    func original(of layer: Layer, rect r: IntRect) -> Data {
        var data = Data(count: r.w * r.h * 4)
        data.withUnsafeMutableBytes { raw in
            guard let p = raw.bindMemory(to: UInt8.self).baseAddress else { return }
            ink_stroke_original(canvas, layer.pixels, Int32(layer.stride), p, Int32(r.w * 4),
                                Int32(r.x), Int32(r.y), Int32(r.w), Int32(r.h))
        }
        return data
    }

    func end() {
        ink_stroke_end(canvas)
        active = false
        events.removeAll(keepingCapacity: true)
    }

    /// The user's settings on top of the brush file: size, opacity, color, eraser, smudge.
    static func configure(_ brush: EngineBrush, _ style: StrokeStyle) {
        // the size: the file's own pressure curve stays, its base moves to the chosen size
        let base = brush.get("radius_logarithmic")
        let fileSize = max(0.5, style.preset.defaultSize)
        brush.set("radius_logarithmic", base + logf(max(0.5, style.size) / fileSize))
        if style.smudging {
            // smudge: the same brush shape, moving paint without adding any
            brush.set("smudge", 1)
            brush.set("opaque", 1)
            if brush.get("smudge_length") < 0.4 { brush.set("smudge_length", 0.5) }
        }
        brush.set("opaque", brush.get("opaque") * max(0, min(1, style.opacity)))
        if style.erasing { brush.set("eraser", 1) }
        if style.alphaLock { brush.set("lock_alpha", 1) }
        brush.setColor(style.color)
    }

    /// A sample stroke (light → heavy → light) for the brush library.
    static func preview(_ preset: BrushPreset, width: Int, height: Int, color: HSB) -> UIImage? {
        guard let json = preset.json, let painter = Painter(width: width, height: height) else { return nil }
        let layer = Layer(width: width, height: height, name: "")
        if preset.fileName == "blender" {
            // a blender only moves paint: give it some to move
            if let ctx = bitmapContext(layer.pixels, width: width, height: height, stride: layer.stride) {
                ctx.setFillColor(UIColor(red: 0.36, green: 0.55, blue: 0.94, alpha: 1).cgColor)
                ctx.fill(CGRect(x: Double(width) * 0.2, y: Double(height) * 0.15, width: Double(width) * 0.25, height: Double(height) * 0.7))
                ctx.setFillColor(UIColor(red: 0.91, green: 0.69, blue: 0.29, alpha: 1).cgColor)
                ctx.fill(CGRect(x: Double(width) * 0.55, y: Double(height) * 0.15, width: Double(width) * 0.25, height: Double(height) * 0.7))
            }
        }
        _ = json
        let style = StrokeStyle(preset: preset, size: min(Float(height) * 0.42, max(4, preset.defaultSize)),
                                opacity: 1, color: color)
        guard painter.begin(style, symmetry: .off, time: 0) else { return nil }
        let n = 120
        for i in 0...n {
            let t = Float(i) / Float(n)
            let x = 16 + t * Float(width - 32)
            let y = Float(height) / 2 + sinf(t * 2 * .pi) * Float(height) * 0.22
            let p = 0.15 + 0.85 * sinf(t * .pi)
            painter.add(x: x, y: y, pressure: p, xtilt: 0, ytilt: 0, time: Double(i) / 120)
        }
        _ = painter.flush(into: layer)
        painter.end()
        guard let img = layer.makeImage() else { return nil }
        return UIImage(cgImage: img)
    }
}
