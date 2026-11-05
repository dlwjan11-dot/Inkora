import UIKit

/// One layer: its own pixels (premultiplied RGBA) and how it shows.
final class Layer {
    let id: UUID
    var name: String
    var visible = true
    var opacity: Float = 1
    var blend: BlendMode = .normal
    var alphaLock = false
    let width: Int
    let height: Int
    let pixels: UnsafeMutablePointer<UInt8>
    var stride: Int { width * 4 }
    var byteCount: Int { width * height * 4 }

    init(width: Int, height: Int, name: String, id: UUID = UUID()) {
        self.width = width
        self.height = height
        self.name = name
        self.id = id
        pixels = UnsafeMutablePointer<UInt8>.allocate(capacity: width * height * 4)
        pixels.initialize(repeating: 0, count: width * height * 4)
    }

    deinit { pixels.deallocate() }

    func clear() { pixels.update(repeating: 0, count: byteCount) }

    func copy(named: String) -> Layer {
        let l = Layer(width: width, height: height, name: named)
        l.pixels.update(from: pixels, count: byteCount)
        l.visible = visible; l.opacity = opacity; l.blend = blend; l.alphaLock = alphaLock
        return l
    }

    /// the pixels of a rectangle, row after row
    func region(_ r: IntRect) -> Data {
        var d = Data(count: r.w * r.h * 4)
        d.withUnsafeMutableBytes { raw in
            guard let dst = raw.bindMemory(to: UInt8.self).baseAddress else { return }
            for j in 0..<r.h {
                (dst + j * r.w * 4).update(from: pixels + (r.y + j) * stride + r.x * 4, count: r.w * 4)
            }
        }
        return d
    }

    func setRegion(_ r: IntRect, _ d: Data) {
        guard d.count >= r.w * r.h * 4 else { return }
        d.withUnsafeBytes { raw in
            guard let src = raw.bindMemory(to: UInt8.self).baseAddress else { return }
            for j in 0..<r.h {
                (pixels + (r.y + j) * stride + r.x * 4).update(from: src + j * r.w * 4, count: r.w * 4)
            }
        }
    }

    var context: CGContext? { bitmapContext(pixels, width: width, height: height, stride: stride) }

    func makeImage() -> CGImage? { context?.makeImage() }

    /// draws a picture onto the layer, fitted into rect (canvas pixels)
    func draw(_ image: CGImage, in rect: CGRect) {
        guard let ctx = context else { return }
        // CGContext's origin is at the bottom: flip so rect is in top-down canvas pixels
        ctx.translateBy(x: 0, y: CGFloat(height))
        ctx.scaleBy(x: 1, y: -1)
        ctx.interpolationQuality = .high
        ctx.saveGState()
        ctx.translateBy(x: rect.minX, y: rect.maxY)
        ctx.scaleBy(x: 1, y: -1)
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: rect.width, height: rect.height))
        ctx.restoreGState()
    }

    /// true when no pixel has any paint
    var isEmpty: Bool {
        let n = byteCount
        var i = 3
        while i < n { if pixels[i] != 0 { return false }; i += 4 }
        return true
    }

    /// a small picture of the layer for the layers list
    func thumbnail(maxSide: CGFloat) -> UIImage? {
        guard let img = makeImage() else { return nil }
        let k = maxSide / CGFloat(max(width, height))
        let size = CGSize(width: max(1, CGFloat(width) * k), height: max(1, CGFloat(height) * k))
        let r = UIGraphicsImageRenderer(size: size)
        return r.image { c in
            UIColor(white: 1, alpha: 0.9).setFill()
            c.fill(CGRect(origin: .zero, size: size))
            UIImage(cgImage: img).draw(in: CGRect(origin: .zero, size: size))
        }
    }
}

/// An artwork: its size, layers (bottom first) and background.
final class Document {
    let id: UUID
    var title: String
    let width: Int
    let height: Int
    var layers: [Layer] = []
    var activeIndex = 0
    /// 0xRRGGBBAA
    var background: UInt32 = 0xFFFFFFFF
    var backgroundVisible = true
    var layerNameCounter = 1
    var dirty = false

    init(width: Int, height: Int, title: String = "", id: UUID = UUID()) {
        self.width = width
        self.height = height
        self.title = title
        self.id = id
    }

    var bounds: IntRect { IntRect(x: 0, y: 0, w: width, h: height) }

    var active: Layer {
        activeIndex = max(0, min(layers.count - 1, activeIndex))
        return layers[activeIndex]
    }

    func nextLayerName() -> String {
        let n = String(format: L("Layer %d"), layerNameCounter)
        layerNameCounter += 1
        return n
    }

    @discardableResult
    func addLayer(above index: Int? = nil) -> Layer {
        let l = Layer(width: width, height: height, name: nextLayerName())
        let at = min(layers.count, (index ?? activeIndex) + 1)
        layers.insert(l, at: layers.isEmpty ? 0 : at)
        activeIndex = layers.firstIndex { $0 === l } ?? 0
        dirty = true
        return l
    }

    /// how many layers fit in memory for this canvas size
    var maxLayers: Int {
        let bytes = width * height * 4
        let budget = Int(ProcessInfo.processInfo.physicalMemory / 4)
        return max(4, min(100, budget / max(1, bytes)))
    }

    private func refs() -> [InkLayerRef] {
        layers.filter { $0.visible && $0.opacity > 0 }.map {
            InkLayerRef(pixels: UnsafePointer($0.pixels), stride: Int32($0.stride), opacity: $0.opacity, blend: $0.blend.rawValue)
        }
    }

    var backgroundForComposite: UInt32 { backgroundVisible ? background : 0 }

    /// the layers drawn together for rect, into dst (rect's size, stride bytes per row)
    func composite(into dst: UnsafeMutablePointer<UInt8>, stride: Int, rect r: IntRect) {
        let rf = refs()
        rf.withUnsafeBufferPointer { b in
            ink_composite(dst, Int32(stride), Int32(r.x), Int32(r.y), Int32(r.w), Int32(r.h),
                          b.baseAddress, Int32(b.count), backgroundForComposite)
        }
    }

    /// the color of the picture at a canvas pixel (straight RGB), or nil where there is nothing
    func pick(x: Int, y: Int) -> HSB? {
        guard x >= 0, y >= 0, x < width, y < height else { return nil }
        let rf = refs()
        let v = rf.withUnsafeBufferPointer { b in
            ink_pick(b.baseAddress, Int32(b.count), backgroundForComposite, Int32(x), Int32(y))
        }
        if v & 255 == 0 { return nil }
        return HSB.fromRGBA(v)
    }

    /// the whole picture as one image
    func flattened(withBackground: Bool = true) -> CGImage? {
        let stride = width * 4
        let buf = UnsafeMutablePointer<UInt8>.allocate(capacity: stride * height)
        defer { buf.deallocate() }
        let keep = backgroundVisible
        if !withBackground { backgroundVisible = false }
        composite(into: buf, stride: stride, rect: bounds)
        backgroundVisible = keep
        return bitmapContext(buf, width: width, height: height, stride: stride)?.makeImage()
    }

    func thumbnail(maxSide: CGFloat) -> UIImage? {
        guard let img = flattened() else { return nil }
        let k = min(1, maxSide / CGFloat(max(width, height)))
        let size = CGSize(width: max(1, (CGFloat(width) * k).rounded()), height: max(1, (CGFloat(height) * k).rounded()))
        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        return UIGraphicsImageRenderer(size: size, format: fmt).image { _ in
            UIImage(cgImage: img).draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
