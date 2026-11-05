import UIKit

/// A brush in the library: its MyPaint settings file (in the app's Brushes folder) and size.
final class BrushPreset {
    let name: String
    let fileName: String
    let category: String
    /// the diameter the file is made for (canvas pixels)
    let defaultSize: Float

    init(_ name: String, _ fileName: String, _ category: String, _ defaultSize: Float) {
        self.name = name
        self.fileName = fileName
        self.category = category
        self.defaultSize = defaultSize
    }

    private var cached: String?
    var json: String? {
        if let c = cached { return c }
        guard let url = Bundle.main.url(forResource: fileName, withExtension: "myb"),
              let s = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        cached = s
        return s
    }

    var id: String { fileName }
}

enum BrushLibrary {
    static let sketching = "Sketching"
    static let inking = "Inking"
    static let painting = "Living Paint"
    static let artistic = "Artistic"
    static let airbrushing = "Airbrushing"

    /// every brush is an original Inkora design for the MyPaint engine
    static let all: [BrushPreset] = [
        BrushPreset("Sketch Pencil", "sketch_pencil", sketching, 3.6),
        BrushPreset("Soft Graphite", "graphite", sketching, 3.2),
        BrushPreset("Raw Charcoal", "charcoal", sketching, 8),
        BrushPreset("Studio Pen", "studio_pen", inking, 7),
        BrushPreset("Fine Liner", "fine_liner", inking, 4),
        BrushPreset("Living Ink", "living_ink", inking, 8.4),
        BrushPreset("Calligraphy", "calligraphy", inking, 14),
        BrushPreset("Felt Marker", "felt_marker", inking, 14),
        BrushPreset("Rain Wash", "rain_wash", painting, 40),
        BrushPreset("Creamy Gouache", "gouache", painting, 28),
        BrushPreset("Loaded Oil", "oil_drag", painting, 32),
        BrushPreset("Wet Blender", "blender", painting, 36),
        BrushPreset("Hard Round", "hard_round", painting, 24),
        BrushPreset("Soft Round", "soft_round", painting, 48),
        BrushPreset("Chalk Pastel", "soft_pastel", artistic, 18),
        BrushPreset("Dry Bristle", "dry_bristle", artistic, 6),
        BrushPreset("Grain Shader", "grain_shader", artistic, 32),
        BrushPreset("Flick Spatter", "spatter", artistic, 5),
        BrushPreset("Mist Spray", "airbrush", airbrushing, 60),
        BrushPreset("Hard Airbrush", "hard_airbrush", airbrushing, 40),
    ]

    static var categories: [String] {
        var seen: [String] = []
        for p in all where !seen.contains(p.category) { seen.append(p.category) }
        return seen
    }

    static func preset(_ id: String) -> BrushPreset? { all.first { $0.id == id } }

    private static var previews: [String: UIImage] = [:]

    static func preview(_ p: BrushPreset) -> UIImage? {
        if let img = previews[p.id] { return img }
        let img = Painter.preview(p, width: 300, height: 64, color: HSB(h: 0, s: 0, b: 0.92))
        previews[p.id] = img
        return img
    }
}

/// One tool's choice: its brush, size and opacity (brush, smudge and eraser each have one).
final class ToolBrush {
    let key: String
    var preset: BrushPreset
    var size: Float
    var opacity: Float

    init(key: String, defaultBrush: String) {
        self.key = key
        let d = UserDefaults.standard
        preset = BrushLibrary.preset(d.string(forKey: key + ".brush") ?? defaultBrush)
            ?? BrushLibrary.preset(defaultBrush) ?? BrushLibrary.all[0]
        let s = d.float(forKey: key + ".size")
        size = s > 0 ? s : preset.defaultSize
        let o = d.object(forKey: key + ".opacity") as? Float
        opacity = o ?? 1
    }

    func choose(_ p: BrushPreset) {
        preset = p
        size = p.defaultSize
        save()
    }

    func save() {
        let d = UserDefaults.standard
        d.set(preset.id, forKey: key + ".brush")
        d.set(size, forKey: key + ".size")
        d.set(opacity, forKey: key + ".opacity")
    }
}
