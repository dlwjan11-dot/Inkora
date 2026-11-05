import UIKit
import ImageIO
import UniformTypeIdentifiers

/// What the gallery shows for one artwork.
struct ArtworkInfo {
    let id: UUID
    let title: String
    let width: Int
    let height: Int
    let modified: Date
    let folder: URL
    var thumbnailURL: URL { folder.appendingPathComponent("thumbnail.jpg") }
}

/**
 Artworks live in the app's Documents folder (visible in the Files app), one folder each:
 artwork.json (size, layers and their settings), one PNG per layer, and a thumbnail.
 */
final class ArtworkStore {
    static let shared = ArtworkStore()

    let root: URL

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        root = docs.appendingPathComponent("Artworks", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    func folder(_ id: UUID) -> URL { root.appendingPathComponent(id.uuidString, isDirectory: true) }

    func list() -> [ArtworkInfo] {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) else { return [] }
        var out: [ArtworkInfo] = []
        for f in items {
            let meta = f.appendingPathComponent("artwork.json")
            guard let data = try? Data(contentsOf: meta),
                  let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let ids = j["id"] as? String, let id = UUID(uuidString: ids) else { continue }
            let date = (try? fm.attributesOfItem(atPath: meta.path)[.modificationDate] as? Date) ?? Date.distantPast
            out.append(ArtworkInfo(id: id, title: j["title"] as? String ?? "", width: j["width"] as? Int ?? 0,
                                   height: j["height"] as? Int ?? 0, modified: date, folder: f))
        }
        return out.sorted { $0.modified > $1.modified }
    }

    func save(_ doc: Document) throws {
        let f = folder(doc.id)
        let fm = FileManager.default
        try fm.createDirectory(at: f, withIntermediateDirectories: true)
        var layers: [[String: Any]] = []
        var keep: Set<String> = ["artwork.json", "thumbnail.jpg"]
        for l in doc.layers {
            let file = "layer-\(l.id.uuidString).png"
            keep.insert(file)
            if let img = l.makeImage() { try writePNG(img, to: f.appendingPathComponent(file)) }
            layers.append(["id": l.id.uuidString, "name": l.name, "visible": l.visible, "opacity": l.opacity,
                           "blend": Int(l.blend.rawValue), "alphaLock": l.alphaLock, "file": file])
        }
        let j: [String: Any] = [
            "format": 1, "id": doc.id.uuidString, "title": doc.title, "width": doc.width, "height": doc.height,
            "background": Int(doc.background), "backgroundVisible": doc.backgroundVisible,
            "active": doc.activeIndex, "layerNameCounter": doc.layerNameCounter, "layers": layers
        ]
        let data = try JSONSerialization.data(withJSONObject: j, options: [.prettyPrinted])
        try data.write(to: f.appendingPathComponent("artwork.json"), options: .atomic)
        if let t = doc.thumbnail(maxSide: 600), let jpg = t.jpegData(compressionQuality: 0.85) {
            try jpg.write(to: f.appendingPathComponent("thumbnail.jpg"), options: .atomic)
        }
        // layers that were deleted
        if let items = try? fm.contentsOfDirectory(atPath: f.path) {
            for name in items where !keep.contains(name) { try? fm.removeItem(at: f.appendingPathComponent(name)) }
        }
        doc.dirty = false
    }

    func load(_ id: UUID) -> Document? {
        let f = folder(id)
        guard let data = try? Data(contentsOf: f.appendingPathComponent("artwork.json")),
              let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let w = j["width"] as? Int, let h = j["height"] as? Int, w > 0, h > 0 else { return nil }
        let doc = Document(width: w, height: h, title: j["title"] as? String ?? "", id: id)
        if let bg = j["background"] as? Int { doc.background = UInt32(truncatingIfNeeded: bg) }
        doc.backgroundVisible = j["backgroundVisible"] as? Bool ?? true
        doc.layerNameCounter = j["layerNameCounter"] as? Int ?? 1
        for lj in (j["layers"] as? [[String: Any]]) ?? [] {
            let lid = (lj["id"] as? String).flatMap { UUID(uuidString: $0) } ?? UUID()
            let l = Layer(width: w, height: h, name: lj["name"] as? String ?? "", id: lid)
            l.visible = lj["visible"] as? Bool ?? true
            l.opacity = Float(lj["opacity"] as? Double ?? 1)
            l.blend = BlendMode(rawValue: Int32(lj["blend"] as? Int ?? 0)) ?? .normal
            l.alphaLock = lj["alphaLock"] as? Bool ?? false
            if let file = lj["file"] as? String,
               let src = CGImageSourceCreateWithURL(f.appendingPathComponent(file) as CFURL, nil),
               let img = CGImageSourceCreateImageAtIndex(src, 0, nil) {
                l.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
            }
            doc.layers.append(l)
        }
        if doc.layers.isEmpty { doc.addLayer() }
        doc.activeIndex = max(0, min(doc.layers.count - 1, j["active"] as? Int ?? 0))
        return doc
    }

    func delete(_ id: UUID) { try? FileManager.default.removeItem(at: folder(id)) }

    func duplicate(_ id: UUID) {
        guard let doc = load(id) else { return }
        let copy = Document(width: doc.width, height: doc.height, title: doc.title.isEmpty ? "" : doc.title + " " + L("copy"))
        copy.layers = doc.layers.map { l in
            let c = l.copy(named: l.name)
            return c
        }
        copy.activeIndex = doc.activeIndex
        copy.background = doc.background
        copy.backgroundVisible = doc.backgroundVisible
        copy.layerNameCounter = doc.layerNameCounter
        try? save(copy)
    }

    func rename(_ id: UUID, to title: String) {
        let meta = folder(id).appendingPathComponent("artwork.json")
        guard let data = try? Data(contentsOf: meta),
              var j = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        j["title"] = title
        if let out = try? JSONSerialization.data(withJSONObject: j, options: [.prettyPrinted]) {
            try? out.write(to: meta, options: .atomic)
        }
    }

    private func writePNG(_ img: CGImage, to url: URL) throws {
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        CGImageDestinationAddImage(dest, img, nil)
        if !CGImageDestinationFinalize(dest) { throw CocoaError(.fileWriteUnknown) }
    }

    /// a PNG or JPEG of the whole picture, in a temporary file to share
    func exportFile(_ doc: Document, jpeg: Bool) -> URL? {
        guard let img = doc.flattened(withBackground: jpeg || doc.backgroundVisible) else { return nil }
        let name = (doc.title.isEmpty ? "Inkora" : doc.title) + (jpeg ? ".jpg" : ".png")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: url)
        let type = (jpeg ? UTType.jpeg : UTType.png).identifier as CFString
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, type, 1, nil) else { return nil }
        let opts: [CFString: Any] = jpeg ? [kCGImageDestinationLossyCompressionQuality: 0.92] : [:]
        CGImageDestinationAddImage(dest, img, opts as CFDictionary)
        return CGImageDestinationFinalize(dest) ? url : nil
    }
}
