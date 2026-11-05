import UIKit

/// Text in the user's language (English, Arabic or Kurdish), from Localizable.strings.
func L(_ s: String) -> String { NSLocalizedString(s, comment: "") }

/// A rectangle of whole pixels on the canvas.
struct IntRect: Equatable {
    var x: Int
    var y: Int
    var w: Int
    var h: Int

    init(x: Int, y: Int, w: Int, h: Int) { self.x = x; self.y = y; self.w = w; self.h = h }

    var maxX: Int { x + w }
    var maxY: Int { y + h }
    var isEmpty: Bool { w <= 0 || h <= 0 }
    var cgRect: CGRect { CGRect(x: x, y: y, width: w, height: h) }

    func union(_ o: IntRect) -> IntRect {
        if isEmpty { return o }
        if o.isEmpty { return self }
        let nx = min(x, o.x), ny = min(y, o.y)
        return IntRect(x: nx, y: ny, w: max(maxX, o.maxX) - nx, h: max(maxY, o.maxY) - ny)
    }

    func intersection(_ o: IntRect) -> IntRect {
        let nx = max(x, o.x), ny = max(y, o.y)
        let r = IntRect(x: nx, y: ny, w: min(maxX, o.maxX) - nx, h: min(maxY, o.maxY) - ny)
        return r.isEmpty ? IntRect(x: 0, y: 0, w: 0, h: 0) : r
    }

    func insetBy(_ d: Int) -> IntRect { IntRect(x: x + d, y: y + d, w: w - 2 * d, h: h - 2 * d) }
}

/// A paint color as hue, saturation, brightness (0...1), with RGB conversions.
struct HSB: Equatable {
    var h: Float
    var s: Float
    var b: Float

    init(h: Float, s: Float, b: Float) { self.h = h; self.s = s; self.b = b }

    init(_ color: UIColor) {
        var hh: CGFloat = 0, ss: CGFloat = 0, bb: CGFloat = 0, aa: CGFloat = 0
        if color.getHue(&hh, saturation: &ss, brightness: &bb, alpha: &aa) {
            h = Float(hh); s = Float(ss); b = Float(bb)
        } else {
            var w: CGFloat = 0
            color.getWhite(&w, alpha: &aa)
            h = 0; s = 0; b = Float(w)
        }
    }

    var uiColor: UIColor { UIColor(hue: CGFloat(h), saturation: CGFloat(s), brightness: CGFloat(b), alpha: 1) }

    /// 0xRRGGBBAA, fully opaque
    var rgba: UInt32 {
        var r: CGFloat = 0, g: CGFloat = 0, bl: CGFloat = 0, a: CGFloat = 0
        uiColor.getRed(&r, green: &g, blue: &bl, alpha: &a)
        func byte(_ v: CGFloat) -> UInt32 { UInt32(max(0, min(255, (v * 255).rounded()))) }
        return (byte(r) << 24) | (byte(g) << 16) | (byte(bl) << 8) | 255
    }

    static func fromRGBA(_ v: UInt32) -> HSB {
        let c = UIColor(red: CGFloat((v >> 24) & 255) / 255, green: CGFloat((v >> 16) & 255) / 255,
                        blue: CGFloat((v >> 8) & 255) / 255, alpha: 1)
        return HSB(c)
    }
}

/// How a layer mixes with the layers under it (the same order as the C compositor).
enum BlendMode: Int32, CaseIterable {
    case normal = 0, multiply, screen, overlay, darken, lighten, colorDodge, colorBurn,
         hardLight, softLight, difference, add

    var title: String {
        switch self {
        case .normal: return L("Normal")
        case .multiply: return L("Multiply")
        case .screen: return L("Screen")
        case .overlay: return L("Overlay")
        case .darken: return L("Darken")
        case .lighten: return L("Lighten")
        case .colorDodge: return L("Color Dodge")
        case .colorBurn: return L("Color Burn")
        case .hardLight: return L("Hard Light")
        case .softLight: return L("Soft Light")
        case .difference: return L("Difference")
        case .add: return L("Add")
        }
    }

    /// short letters shown on the layer row
    var code: String {
        switch self {
        case .normal: return "N"
        case .multiply: return "M"
        case .screen: return "S"
        case .overlay: return "O"
        case .darken: return "D"
        case .lighten: return "L"
        case .colorDodge: return "CD"
        case .colorBurn: return "CB"
        case .hardLight: return "HL"
        case .softLight: return "SL"
        case .difference: return "Di"
        case .add: return "A"
        }
    }
}

/// Mirror and turned copies of every stroke.
enum Symmetry: Int, CaseIterable {
    case off, vertical, horizontal, quadrant, radial

    var title: String {
        switch self {
        case .off: return L("Off")
        case .vertical: return L("Vertical")
        case .horizontal: return L("Horizontal")
        case .quadrant: return L("Quadrant")
        case .radial: return L("Radial")
        }
    }

    /// the engine's symmetry type (-1 = off) and how many lines
    var engine: (type: Int32, lines: Int32) {
        switch self {
        case .off: return (-1, 2)
        case .vertical: return (0, 2)
        case .horizontal: return (1, 2)
        case .quadrant: return (2, 2)
        case .radial: return (4, 8)
        }
    }
}

enum Theme {
    static let bar = UIColor(red: 0.118, green: 0.122, blue: 0.145, alpha: 0.96)
    static let panel = UIColor(red: 0.16, green: 0.165, blue: 0.19, alpha: 1)
    static let accent = UIColor(red: 0.43, green: 0.62, blue: 0.96, alpha: 1)
    static let text = UIColor(red: 0.91, green: 0.914, blue: 0.93, alpha: 1)
    static let dim = UIColor(red: 0.55, green: 0.57, blue: 0.63, alpha: 1)
    static let canvasBack = UIColor(red: 0.09, green: 0.094, blue: 0.11, alpha: 1)
    static let gold = UIColor(red: 1.0, green: 0.745, blue: 0.239, alpha: 1)
    static let navy = UIColor(red: 0.137, green: 0.102, blue: 0.388, alpha: 1)
}

let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!
/// R, G, B, A bytes, premultiplied: the layout the engine and the compositor use
let premultipliedRGBA = CGImageAlphaInfo.premultipliedLast.rawValue

/// A CGContext over existing premultiplied RGBA pixels.
func bitmapContext(_ data: UnsafeMutableRawPointer?, width: Int, height: Int, stride: Int) -> CGContext? {
    CGContext(data: data, width: width, height: height, bitsPerComponent: 8, bytesPerRow: stride,
              space: sRGB, bitmapInfo: premultipliedRGBA)
}
