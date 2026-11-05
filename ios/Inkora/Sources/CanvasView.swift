import UIKit

protocol CanvasViewDelegate: AnyObject {
    /// the style for a new stroke (nil = don't draw)
    func canvasStrokeStyle(_ canvas: CanvasView, erasingWithPencil: Bool) -> StrokeStyle?
    func canvasDidPickColor(_ canvas: CanvasView, color: HSB)
    func canvasHistoryChanged(_ canvas: CanvasView)
    func canvasMessage(_ canvas: CanvasView, _ text: String)
    /// Apple Pencil double tap / squeeze
    func canvasPencilTapped(_ canvas: CanvasView)
}

enum CanvasTool { case paint, fill, eyedropper }

/**
 The drawing surface. The picture is shown as 256 × 256 tiles (each redrawn only when the
 paint under it changes) inside one layer that is moved, zoomed and turned with two fingers.
 Apple Pencil always draws; a finger draws too until a Pencil is used (Procreate-style).
 */
final class CanvasView: UIView, UIGestureRecognizerDelegate, UIPencilInteractionDelegate {
    weak var delegate: CanvasViewDelegate?

    private(set) var doc: Document!
    let history = History()
    private var painter: Painter?

    /// the canvas in canvas pixels; its transform places it on screen
    private let content = CALayer()
    private var tiles: [Int: CALayer] = [:]
    private let tileSize = 256
    private var cols = 0
    private var rows = 0
    private let tileBuffer: UnsafeMutablePointer<UInt8>

    /// canvas pixels → view points
    private(set) var viewTransform = CGAffineTransform.identity

    var symmetry: Symmetry = .off
    var tool: CanvasTool = .paint
    var fillTolerance: Float = 0.12
    /// fingers also draw when an Apple Pencil is used (off: fingers only move the canvas)
    var fingerDrawing = false
    private(set) var pencilSeen = false
    /// fingers draw until an Apple Pencil is seen, or always when the user turned that on
    var fingersDraw: Bool { fingerDrawing || !pencilSeen }

    init() {
        tileBuffer = UnsafeMutablePointer<UInt8>.allocate(capacity: 256 * 256 * 4)
        super.init(frame: .zero)
        backgroundColor = Theme.canvasBack
        isMultipleTouchEnabled = true
        clipsToBounds = true
        content.anchorPoint = .zero
        content.position = .zero
        content.magnificationFilter = .nearest
        content.minificationFilter = .trilinear
        content.shadowColor = UIColor.black.cgColor
        content.shadowOpacity = 0.45
        content.shadowRadius = 16
        content.shadowOffset = .zero
        layer.addSublayer(content)
        setupGestures()
        history.onChange = { [weak self] in
            guard let self = self else { return }
            self.delegate?.canvasHistoryChanged(self)
        }
        let pencil = UIPencilInteraction()
        pencil.delegate = self
        addInteraction(pencil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    deinit { tileBuffer.deallocate() }

    // MARK: - document and tiles

    func setDocument(_ d: Document) {
        doc = d
        painter = Painter(width: d.width, height: d.height)
        history.clear()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        tiles.values.forEach { $0.removeFromSuperlayer() }
        tiles.removeAll()
        cols = (d.width + tileSize - 1) / tileSize
        rows = (d.height + tileSize - 1) / tileSize
        content.bounds = CGRect(x: 0, y: 0, width: d.width, height: d.height)
        content.shadowPath = UIBezierPath(rect: content.bounds).cgPath
        for ty in 0..<rows {
            for tx in 0..<cols {
                let t = CALayer()
                t.anchorPoint = .zero
                let w = min(tileSize, d.width - tx * tileSize)
                let h = min(tileSize, d.height - ty * tileSize)
                t.frame = CGRect(x: tx * tileSize, y: ty * tileSize, width: w, height: h)
                t.magnificationFilter = .nearest
                t.minificationFilter = .trilinear
                t.isOpaque = d.backgroundVisible
                content.addSublayer(t)
                tiles[ty * cols + tx] = t
            }
        }
        updateCheckerboard()
        CATransaction.commit()
        refresh(nil)
        fit(animated: false)
    }

    func updateCheckerboard() {
        guard let d = doc else { return }
        if d.backgroundVisible { content.backgroundColor = UIColor.white.cgColor; return }
        let s: CGFloat = 16
        let img = UIGraphicsImageRenderer(size: CGSize(width: s * 2, height: s * 2)).image { c in
            UIColor(white: 0.93, alpha: 1).setFill(); c.fill(CGRect(x: 0, y: 0, width: s * 2, height: s * 2))
            UIColor(white: 0.8, alpha: 1).setFill()
            c.fill(CGRect(x: 0, y: 0, width: s, height: s)); c.fill(CGRect(x: s, y: s, width: s, height: s))
        }
        content.backgroundColor = UIColor(patternImage: img).cgColor
        tiles.values.forEach { $0.isOpaque = false }
    }

    /// redraws the tiles under rect (nil = all)
    func refresh(_ rect: IntRect?) {
        guard let d = doc else { return }
        let r = (rect ?? d.bounds).intersection(d.bounds)
        if r.isEmpty { return }
        let tx0 = r.x / tileSize, tx1 = (r.maxX - 1) / tileSize
        let ty0 = r.y / tileSize, ty1 = (r.maxY - 1) / tileSize
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for ty in ty0...ty1 {
            for tx in tx0...tx1 {
                guard let t = tiles[ty * cols + tx] else { continue }
                let x = tx * tileSize, y = ty * tileSize
                let w = min(tileSize, d.width - x), h = min(tileSize, d.height - y)
                d.composite(into: tileBuffer, stride: tileSize * 4, rect: IntRect(x: x, y: y, w: w, h: h))
                t.contents = bitmapContext(tileBuffer, width: w, height: h, stride: tileSize * 4)?.makeImage()
            }
        }
        CATransaction.commit()
    }

    // MARK: - view transform

    var zoom: CGFloat { sqrt(viewTransform.a * viewTransform.a + viewTransform.b * viewTransform.b) }
    var rotation: CGFloat { atan2(viewTransform.b, viewTransform.a) }

    private func apply(_ t: CGAffineTransform) {
        viewTransform = t
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        content.setAffineTransform(t)
        CATransaction.commit()
        // pixels show as pixels when zoomed in a lot
        let filter: CALayerContentsFilter = zoom > 2.5 ? .nearest : .linear
        if content.magnificationFilter != filter {
            content.magnificationFilter = filter
            tiles.values.forEach { $0.magnificationFilter = filter }
        }
    }

    /// the whole canvas on screen, centered
    func fit(animated: Bool) {
        guard let d = doc, bounds.width > 0 else { return }
        let s = min(bounds.width * 0.9 / CGFloat(d.width), bounds.height * 0.86 / CGFloat(d.height))
        let t = CGAffineTransform(a: s, b: 0, c: 0, d: s,
                                  tx: (bounds.width - CGFloat(d.width) * s) / 2,
                                  ty: (bounds.height - CGFloat(d.height) * s) / 2)
        if animated {
            CATransaction.begin()
            CATransaction.setAnimationDuration(0.25)
            viewTransform = t
            content.setAffineTransform(t)
            CATransaction.commit()
        } else { apply(t) }
    }

    /// 100%: one canvas pixel per screen pixel, around the middle of the view
    func actualSize() {
        let s = 1 / (window?.screen.scale ?? UIScreen.main.scale)
        let mid = CGPoint(x: bounds.midX, y: bounds.midY)
        let c = toCanvas(mid)
        let t = CGAffineTransform(translationX: -c.x, y: -c.y)
            .concatenating(CGAffineTransform(scaleX: s, y: s))
            .concatenating(CGAffineTransform(translationX: mid.x, y: mid.y))
        apply(t)
    }

    private var lastSize = CGSize.zero
    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != lastSize {
            let first = lastSize == .zero
            lastSize = bounds.size
            if first || doc != nil { fit(animated: false) }
        }
    }

    func toCanvas(_ p: CGPoint) -> CGPoint { p.applying(viewTransform.inverted()) }

    // MARK: - gestures (fingers)

    private let pinch = UIPinchGestureRecognizer()
    private let rotate = UIRotationGestureRecognizer()
    private let twoTap = UITapGestureRecognizer()
    private let threeTap = UITapGestureRecognizer()
    private let hold = UILongPressGestureRecognizer()

    private func setupGestures() {
        let fingers = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        pinch.addTarget(self, action: #selector(onTransformGesture(_:)))
        rotate.addTarget(self, action: #selector(onTransformGesture(_:)))
        for g in [pinch, rotate] as [UIGestureRecognizer] {
            g.delegate = self
            g.allowedTouchTypes = fingers
            addGestureRecognizer(g)
        }
        twoTap.numberOfTouchesRequired = 2
        twoTap.addTarget(self, action: #selector(onUndoTap))
        threeTap.numberOfTouchesRequired = 3
        threeTap.addTarget(self, action: #selector(onRedoTap))
        hold.minimumPressDuration = 0.45
        hold.allowableMovement = 12
        hold.addTarget(self, action: #selector(onHold(_:)))
        for g in [twoTap, threeTap, hold] as [UIGestureRecognizer] {
            g.allowedTouchTypes = fingers
            g.delegate = self
            addGestureRecognizer(g)
        }
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith o: UIGestureRecognizer) -> Bool {
        (g === pinch && o === rotate) || (g === rotate && o === pinch)
    }

    override func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        // touch and hold picks a color only when fingers don't draw
        if g === hold { return !fingersDraw || tool == .eyedropper }
        return super.gestureRecognizerShouldBegin(g)
    }

    private var gStart = CGAffineTransform.identity
    private var gCenter = CGPoint.zero
    private var gScaleBase: CGFloat = 1
    private var gRotationBase: CGFloat = 0
    private var gBeganAt: TimeInterval = 0
    private var gMinScale: CGFloat = 1

    private func gestureCenter() -> CGPoint {
        if pinch.numberOfTouches >= 2 { return pinch.location(in: self) }
        if rotate.numberOfTouches >= 2 { return rotate.location(in: self) }
        return gCenter
    }

    private func rebase() {
        gStart = viewTransform
        gCenter = gestureCenter()
        let pinchOn = pinch.state == .began || pinch.state == .changed
        let rotOn = rotate.state == .began || rotate.state == .changed
        gScaleBase = pinchOn ? max(0.001, pinch.scale) : 1
        gRotationBase = rotOn ? rotate.rotation : 0
    }

    @objc private func onTransformGesture(_ g: UIGestureRecognizer) {
        switch g.state {
        case .began:
            // a finger stroke that just started becomes the gesture; a Pencil stroke goes on
            if let t = strokeTouch, t.type != .pencil, tool == .paint { cancelStroke() }
            if g === pinch { gBeganAt = CACurrentMediaTime(); gMinScale = 1 }
            rebase()
        case .changed:
            let pinchOn = pinch.state == .began || pinch.state == .changed
            let rotOn = rotate.state == .began || rotate.state == .changed
            let s = pinchOn ? pinch.scale / gScaleBase : 1
            let r = rotOn ? rotate.rotation - gRotationBase : 0
            if pinchOn { gMinScale = min(gMinScale, pinch.scale) }
            let c = gestureCenter()
            var t = gStart
                .concatenating(CGAffineTransform(translationX: -gCenter.x, y: -gCenter.y))
                .concatenating(CGAffineTransform(rotationAngle: r))
                .concatenating(CGAffineTransform(scaleX: s, y: s))
                .concatenating(CGAffineTransform(translationX: c.x, y: c.y))
            let z = sqrt(t.a * t.a + t.b * t.b)
            if z < 0.02 || z > 64 { t = viewTransform }
            apply(t)
        case .ended, .cancelled, .failed:
            if g === pinch, g.state == .ended,
               CACurrentMediaTime() - gBeganAt < 0.35, pinch.scale < 0.7, gMinScale < 0.7 {
                // a quick small pinch: the whole canvas on screen
                fit(animated: true)
            } else {
                snapRotation()
            }
            rebase()
        default: break
        }
    }

    /// a rotation close to a right angle snaps to it
    private func snapRotation() {
        let r = rotation
        let q = (r / (.pi / 2)).rounded() * (.pi / 2)
        if abs(r - q) < 0.06 && abs(r - q) > 0.0001 {
            let mid = CGPoint(x: bounds.midX, y: bounds.midY)
            let t = viewTransform
                .concatenating(CGAffineTransform(translationX: -mid.x, y: -mid.y))
                .concatenating(CGAffineTransform(rotationAngle: q - r))
                .concatenating(CGAffineTransform(translationX: mid.x, y: mid.y))
            apply(t)
        }
    }

    @objc private func onUndoTap() { undo() }
    @objc private func onRedoTap() { redo() }

    @objc private func onHold(_ g: UILongPressGestureRecognizer) {
        guard g.state == .began || g.state == .changed else { return }
        pickColor(at: g.location(in: self))
    }

    func pickColor(at p: CGPoint) {
        guard let d = doc else { return }
        let c = toCanvas(p)
        if let color = d.pick(x: Int(c.x), y: Int(c.y)) { delegate?.canvasDidPickColor(self, color: color) }
    }

    // MARK: - Apple Pencil double tap

    func pencilInteractionDidTap(_ interaction: UIPencilInteraction) {
        delegate?.canvasPencilTapped(self)
    }

    // MARK: - drawing

    private var strokeTouch: UITouch?
    private var strokeLayer: Layer?
    private var strokeStartedAt: TimeInterval = 0

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard strokeTouch == nil, let t = touches.first, let d = doc else { return }
        if t.type == .pencil {
            pencilSeen = true
        } else {
            // more than one finger is a gesture, not a stroke
            if tool == .paint && !fingersDraw { return }
            if (event?.allTouches?.filter { $0.type != .pencil }.count ?? 1) > 1 { return }
        }
        let p = toCanvas(t.preciseLocation(in: self))
        switch tool {
        case .eyedropper:
            pickColor(at: t.location(in: self))
            strokeTouch = t
            return
        case .fill:
            fill(at: p)
            return
        case .paint:
            break
        }
        let layer = d.active
        if !layer.visible { delegate?.canvasMessage(self, L("This layer is hidden")); return }
        guard let style = delegate?.canvasStrokeStyle(self, erasingWithPencil: false), let painter = painter else { return }
        var st = style
        st.alphaLock = st.alphaLock || layer.alphaLock
        painter.viewZoom = Float(zoom)
        painter.viewRotation = Float(rotation)
        guard painter.begin(st, symmetry: symmetry, time: t.timestamp) else { return }
        strokeTouch = t
        strokeLayer = layer
        strokeStartedAt = t.timestamp
        addSamples(t, event: event)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = strokeTouch, touches.contains(t) else { return }
        if tool == .eyedropper { pickColor(at: t.location(in: self)); return }
        addSamples(t, event: event)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = strokeTouch, touches.contains(t) else { return }
        if tool == .eyedropper { strokeTouch = nil; return }
        addSamples(t, event: event)
        finishStroke()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = strokeTouch, touches.contains(t) else { return }
        if tool == .eyedropper { strokeTouch = nil; return }
        cancelStroke()
    }

    private func addSamples(_ t: UITouch, event: UIEvent?) {
        guard let painter = painter, let layer = strokeLayer else { return }
        let samples = event?.coalescedTouches(for: t) ?? [t]
        for s in samples {
            let p = toCanvas(s.preciseLocation(in: self))
            var pressure: CGFloat = 1
            var xt: CGFloat = 0, yt: CGFloat = 0
            if s.type == .pencil {
                pressure = s.maximumPossibleForce > 0 ? min(1, s.force / s.maximumPossibleForce * 1.25) : 0.5
                let tilt = 1 - s.altitudeAngle / (.pi / 2)
                let az = s.azimuthAngle(in: self) - rotation
                xt = cos(az) * tilt
                yt = sin(az) * tilt
            }
            painter.add(x: Float(p.x), y: Float(p.y), pressure: Float(pressure), xtilt: Float(xt), ytilt: Float(yt),
                        time: s.timestamp)
        }
        if let r = painter.flush(into: layer) { refresh(r) }
    }

    private func finishStroke() {
        defer { strokeTouch = nil; strokeLayer = nil }
        guard let painter = painter, let layer = strokeLayer else { return }
        _ = painter.flush(into: layer).map { refresh($0) }
        let r = painter.dirty
        if !r.isEmpty {
            let before = painter.original(of: layer, rect: r)
            let after = layer.region(r)
            history.push(History.pixels(layer, r, before: before, after: after))
            doc.dirty = true
        }
        painter.end()
    }

    /// takes the stroke back (a gesture started, or the system cancelled the touch)
    private func cancelStroke() {
        defer { strokeTouch = nil; strokeLayer = nil }
        guard let painter = painter, let layer = strokeLayer, painter.active else { return }
        let r = painter.dirty
        if !r.isEmpty {
            layer.setRegion(r, painter.original(of: layer, rect: r))
            refresh(r)
        }
        painter.end()
    }

    // MARK: - fill

    private func fill(at p: CGPoint) {
        guard let d = doc, let style = delegate?.canvasStrokeStyle(self, erasingWithPencil: false) else { return }
        let x = Int(p.x), y = Int(p.y)
        guard x >= 0, y >= 0, x < d.width, y < d.height else { return }
        let layer = d.active
        let before = layer.region(d.bounds)
        // fill the area as you see it on this layer
        let n = ink_flood_fill(layer.pixels, layer.pixels, Int32(d.width), Int32(d.height), Int32(layer.stride),
                               Int32(x), Int32(y), style.color.rgba, fillTolerance)
        if n <= 0 { return }
        let after = layer.region(d.bounds)
        history.push(History.pixels(layer, d.bounds, before: before, after: after))
        d.dirty = true
        refresh(nil)
    }

    // MARK: - undo

    func undo() {
        if strokeTouch != nil { return }
        if let s = history.undo() { doc.dirty = true; refresh(s.rect) }
    }

    func redo() {
        if strokeTouch != nil { return }
        if let s = history.redo() { doc.dirty = true; refresh(s.rect) }
    }

    /// pushes a step that was already done, and redraws
    func did(_ step: HistoryStep) {
        history.push(step)
        doc.dirty = true
        refresh(step.rect)
    }
}
