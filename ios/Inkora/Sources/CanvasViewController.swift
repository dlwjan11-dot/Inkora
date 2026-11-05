import UIKit
import PhotosUI

enum PaintTool { case brush, smudge, eraser }

/// The drawing screen: the canvas, the top bar (gallery, actions; brush, smudge, eraser,
/// layers, color) and the side bar (size, opacity, undo, redo, eyedropper).
final class CanvasViewController: UIViewController, CanvasViewDelegate, UIColorPickerViewControllerDelegate,
                                  UIPopoverPresentationControllerDelegate, PHPickerViewControllerDelegate {
    let doc: Document
    var onClose: (() -> Void)?

    let canvas = CanvasView()
    private let topBar = UIView()
    private let sideBar = UIView()
    private let sizeSlider = UISlider()
    private let opacitySlider = UISlider()
    private let colorButton = ColorDotButton()
    private var toolButtons: [PaintTool: UIButton] = [:]
    private let layersButton = UIButton(type: .system)
    private let undoButton = UIButton(type: .system)
    private let redoButton = UIButton(type: .system)
    private let eyedropButton = UIButton(type: .system)
    private let fillBadge = UILabel()
    private let toast = UILabel()

    private let brushTool = ToolBrush(key: "brush", defaultBrush: "studio_pen")
    private let smudgeTool = ToolBrush(key: "smudge", defaultBrush: "blender")
    private let eraserTool = ToolBrush(key: "eraser", defaultBrush: "soft_round")
    private var paintTool: PaintTool = .brush
    private var toolBeforePencilTap: PaintTool = .brush

    private var color: HSB {
        didSet { colorButton.color = color.uiColor; saveColor() }
    }

    init(document: Document) {
        doc = document
        let saved = UserDefaults.standard.object(forKey: "color") as? Int
        color = saved.map { HSB.fromRGBA(UInt32(truncatingIfNeeded: $0)) } ?? HSB(h: 0.62, s: 0.75, b: 0.35)
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge { .all }

    private var currentTool: ToolBrush {
        switch paintTool {
        case .brush: return brushTool
        case .smudge: return smudgeTool
        case .eraser: return eraserTool
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.canvasBack
        canvas.delegate = self
        canvas.fingerDrawing = UserDefaults.standard.bool(forKey: "fingerDrawing")
        view.addSubview(canvas)
        buildTopBar()
        buildSideBar()
        toast.textColor = Theme.text
        toast.backgroundColor = Theme.panel.withAlphaComponent(0.95)
        toast.font = .systemFont(ofSize: 15, weight: .medium)
        toast.textAlignment = .center
        toast.layer.cornerRadius = 16
        toast.layer.masksToBounds = true
        toast.alpha = 0
        view.addSubview(toast)
        colorButton.color = color.uiColor
        canvas.setDocument(doc)
        updateTools()
        updateHistoryButtons()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let b = view.bounds
        let barH: CGFloat = 50
        canvas.frame = b
        topBar.frame = CGRect(x: 0, y: 0, width: b.width, height: barH)
        layoutTopBar()
        let sideH = min(b.height - barH - 40, 440)
        sideBar.frame = CGRect(x: 8, y: barH + (b.height - barH - sideH) / 2, width: 46, height: sideH)
        layoutSideBar()
        let tw = min(b.width - 40, 420)
        toast.frame = CGRect(x: (b.width - tw) / 2, y: barH + 14, width: tw, height: 34)
    }

    // MARK: - top bar

    private var leftButtons: [UIView] = []
    private var rightButtons: [UIView] = []

    private func barButton(_ symbol: String, _ label: String, action: Selector?) -> UIButton {
        let b = UIButton(type: .system)
        b.setImage(UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 19, weight: .regular)), for: .normal)
        b.tintColor = Theme.text
        b.accessibilityLabel = label
        if let a = action { b.addTarget(self, action: a, for: .touchUpInside) }
        return b
    }

    private func buildTopBar() {
        topBar.backgroundColor = Theme.bar
        view.addSubview(topBar)

        let gallery = UIButton(type: .system)
        gallery.setTitle(L("Gallery"), for: .normal)
        gallery.titleLabel?.font = .systemFont(ofSize: 16, weight: .medium)
        gallery.tintColor = Theme.text
        gallery.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)

        let actions = barButton("wrench.adjustable", L("Actions"), action: nil)
        actions.menu = actionsMenu()
        actions.showsMenuAsPrimaryAction = true

        let brush = barButton("paintbrush.pointed", L("Brush"), action: #selector(brushTapped))
        let smudge = barButton("hand.point.up.left", L("Smudge"), action: #selector(smudgeTapped))
        let eraser = barButton("eraser", L("Eraser"), action: #selector(eraserTapped))
        toolButtons = [.brush: brush, .smudge: smudge, .eraser: eraser]

        layersButton.setImage(UIImage(systemName: "square.3.layers.3d", withConfiguration: UIImage.SymbolConfiguration(pointSize: 19)), for: .normal)
        layersButton.tintColor = Theme.text
        layersButton.accessibilityLabel = L("Layers")
        layersButton.addTarget(self, action: #selector(layersTapped), for: .touchUpInside)

        colorButton.addTarget(self, action: #selector(colorTapped), for: .touchUpInside)
        colorButton.accessibilityLabel = L("Color")

        fillBadge.text = L("Fill")
        fillBadge.font = .systemFont(ofSize: 12, weight: .semibold)
        fillBadge.textColor = Theme.canvasBack
        fillBadge.backgroundColor = Theme.accent
        fillBadge.textAlignment = .center
        fillBadge.layer.cornerRadius = 9
        fillBadge.layer.masksToBounds = true
        fillBadge.isHidden = true

        leftButtons = [gallery, actions, fillBadge]
        rightButtons = [brush, smudge, eraser, layersButton, colorButton]
        (leftButtons + rightButtons).forEach { topBar.addSubview($0) }
    }

    private func layoutTopBar() {
        let h = topBar.bounds.height
        var x: CGFloat = 12
        for v in leftButtons {
            let w: CGFloat
            if v is UILabel { w = 44 } else if let b = v as? UIButton, b.title(for: .normal) != nil { w = b.intrinsicContentSize.width + 8 } else { w = 44 }
            let vh: CGFloat = v is UILabel ? 20 : 44
            v.frame = CGRect(x: x, y: (h - vh) / 2, width: w, height: vh)
            x += w + 6
        }
        var rx = topBar.bounds.width - 12
        for v in rightButtons.reversed() {
            let w: CGFloat = v === colorButton ? 36 : 44
            rx -= w
            v.frame = CGRect(x: rx, y: (h - (v === colorButton ? 36 : 44)) / 2, width: w, height: v === colorButton ? 36 : 44)
            rx -= 6
        }
    }

    // MARK: - side bar

    private func buildSideBar() {
        sideBar.backgroundColor = Theme.bar
        sideBar.layer.cornerRadius = 14
        view.addSubview(sideBar)
        for s in [sizeSlider, opacitySlider] {
            s.transform = CGAffineTransform(rotationAngle: -.pi / 2)
            s.minimumTrackTintColor = Theme.text
            s.maximumTrackTintColor = Theme.dim.withAlphaComponent(0.4)
            s.setThumbImage(thumbImage(), for: .normal)
            sideBar.addSubview(s)
        }
        sizeSlider.minimumValue = 0
        sizeSlider.maximumValue = 1
        sizeSlider.addTarget(self, action: #selector(sizeChanged), for: .valueChanged)
        sizeSlider.addTarget(self, action: #selector(slidersDone), for: [.touchUpInside, .touchUpOutside])
        opacitySlider.minimumValue = 0.02
        opacitySlider.maximumValue = 1
        opacitySlider.addTarget(self, action: #selector(opacityChanged), for: .valueChanged)
        opacitySlider.addTarget(self, action: #selector(slidersDone), for: [.touchUpInside, .touchUpOutside])

        let cfg = UIImage.SymbolConfiguration(pointSize: 16)
        undoButton.setImage(UIImage(systemName: "arrow.uturn.backward", withConfiguration: cfg), for: .normal)
        redoButton.setImage(UIImage(systemName: "arrow.uturn.forward", withConfiguration: cfg), for: .normal)
        eyedropButton.setImage(UIImage(systemName: "eyedropper", withConfiguration: cfg), for: .normal)
        undoButton.accessibilityLabel = L("Undo")
        redoButton.accessibilityLabel = L("Redo")
        eyedropButton.accessibilityLabel = L("Eyedropper")
        undoButton.addTarget(self, action: #selector(undoTapped), for: .touchUpInside)
        redoButton.addTarget(self, action: #selector(redoTapped), for: .touchUpInside)
        eyedropButton.addTarget(self, action: #selector(eyedropTapped), for: .touchUpInside)
        for b in [undoButton, redoButton, eyedropButton] { b.tintColor = Theme.text; sideBar.addSubview(b) }
    }

    private func thumbImage() -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 22, height: 22)).image { _ in
            Theme.text.setFill()
            UIBezierPath(ovalIn: CGRect(x: 2, y: 2, width: 18, height: 18)).fill()
        }
    }

    private func layoutSideBar() {
        let w = sideBar.bounds.width, h = sideBar.bounds.height
        let buttons: CGFloat = 3 * 40 + 10
        let sliderSpace = (h - buttons - 20) / 2
        let len = max(60, sliderSpace - 16)
        // rotated sliders: set the length through bounds, place by center
        sizeSlider.bounds = CGRect(x: 0, y: 0, width: len, height: 30)
        sizeSlider.center = CGPoint(x: w / 2, y: 10 + sliderSpace / 2)
        opacitySlider.bounds = CGRect(x: 0, y: 0, width: len, height: 30)
        opacitySlider.center = CGPoint(x: w / 2, y: 10 + sliderSpace + sliderSpace / 2)
        var y = 10 + sliderSpace * 2 + 6
        for b in [eyedropButton, undoButton, redoButton] {
            b.frame = CGRect(x: 0, y: y, width: w, height: 40)
            y += 40
        }
    }

    // MARK: - tools

    /// the slider runs over sizes on a curve: fine control for small sizes
    private func sliderValue(forSize s: Float) -> Float { powf(max(0, (s - 1) / 499), 1 / 2.4) }
    private func size(forSlider v: Float) -> Float { 1 + powf(v, 2.4) * 499 }

    private func updateTools() {
        for (t, b) in toolButtons { b.tintColor = t == paintTool && canvas.tool == .paint ? Theme.accent : Theme.text }
        sizeSlider.value = sliderValue(forSize: currentTool.size)
        opacitySlider.value = currentTool.opacity
        eyedropButton.tintColor = canvas.tool == .eyedropper ? Theme.accent : Theme.text
        fillBadge.isHidden = canvas.tool != .fill
    }

    @objc private func sizeChanged() {
        currentTool.size = size(forSlider: sizeSlider.value)
        showToast(String(format: L("Size %d px"), Int(currentTool.size.rounded())))
    }

    @objc private func opacityChanged() {
        currentTool.opacity = opacitySlider.value
        showToast(String(format: L("Opacity %d%%"), Int((currentTool.opacity * 100).rounded())))
    }

    @objc private func slidersDone() { currentTool.save() }

    private func selectTool(_ t: PaintTool, sender: UIView) {
        if paintTool == t && canvas.tool == .paint {
            showBrushLibrary(from: sender)
        } else {
            paintTool = t
            canvas.tool = .paint
            updateTools()
        }
    }

    @objc private func brushTapped(_ s: UIButton) { selectTool(.brush, sender: s) }
    @objc private func smudgeTapped(_ s: UIButton) { selectTool(.smudge, sender: s) }
    @objc private func eraserTapped(_ s: UIButton) { selectTool(.eraser, sender: s) }

    @objc private func eyedropTapped() {
        canvas.tool = canvas.tool == .eyedropper ? .paint : .eyedropper
        if canvas.tool == .eyedropper { showToast(L("Touch the canvas to pick a color")) }
        updateTools()
    }

    private func showBrushLibrary(from sender: UIView) {
        let tool = currentTool
        let vc = BrushLibraryViewController(selected: tool.preset) { [weak self] p in
            tool.choose(p)
            self?.updateTools()
        }
        popover(vc, from: sender, size: CGSize(width: 380, height: 560))
    }

    // MARK: - CanvasViewDelegate

    func canvasStrokeStyle(_ c: CanvasView, erasingWithPencil: Bool) -> StrokeStyle? {
        let t = currentTool
        var st = StrokeStyle(preset: t.preset, size: t.size, opacity: t.opacity, color: color)
        st.erasing = paintTool == .eraser || erasingWithPencil
        st.smudging = paintTool == .smudge
        return st
    }

    func canvasDidPickColor(_ c: CanvasView, color picked: HSB) {
        color = picked
        if canvas.tool == .eyedropper { canvas.tool = .paint; updateTools() }
    }

    func canvasHistoryChanged(_ c: CanvasView) { updateHistoryButtons() }

    func canvasMessage(_ c: CanvasView, _ text: String) { showToast(text) }

    func canvasPencilTapped(_ c: CanvasView) {
        // Pencil double tap: eraser and back
        if paintTool == .eraser {
            paintTool = toolBeforePencilTap == .eraser ? .brush : toolBeforePencilTap
        } else {
            toolBeforePencilTap = paintTool
            paintTool = .eraser
        }
        canvas.tool = .paint
        updateTools()
    }

    private func updateHistoryButtons() {
        undoButton.isEnabled = canvas.history.canUndo
        redoButton.isEnabled = canvas.history.canRedo
        undoButton.alpha = undoButton.isEnabled ? 1 : 0.35
        redoButton.alpha = redoButton.isEnabled ? 1 : 0.35
    }

    @objc private func undoTapped() { canvas.undo() }
    @objc private func redoTapped() { canvas.redo() }

    // MARK: - color

    @objc private func colorTapped() {
        let picker = UIColorPickerViewController()
        picker.supportsAlpha = false
        picker.selectedColor = color.uiColor
        picker.delegate = self
        popover(picker, from: colorButton, size: CGSize(width: 360, height: 560))
    }

    func colorPickerViewController(_ viewController: UIColorPickerViewController, didSelect c: UIColor, continuously: Bool) {
        if viewController.view.tag == 77 {
            // the background color
            doc.background = HSB(c).rgba
            doc.backgroundVisible = true
            doc.dirty = true
            canvas.updateCheckerboard()
            canvas.refresh(nil)
            return
        }
        color = HSB(c)
    }

    private func saveColor() { UserDefaults.standard.set(Int(color.rgba), forKey: "color") }

    // MARK: - layers

    @objc private func layersTapped() {
        let vc = LayersViewController(canvas: canvas)
        popover(vc, from: layersButton, size: CGSize(width: 340, height: 520))
    }

    // MARK: - actions

    private func actionsMenu() -> UIMenu {
        UIMenu(children: [UIDeferredMenuElement.uncached { [weak self] done in
            done(self?.actionItems() ?? [])
        }])
    }

    private func actionItems() -> [UIMenuElement] {
        let add = UIMenu(title: L("Add"), options: .displayInline, children: [
            UIAction(title: L("Insert a photo"), image: UIImage(systemName: "photo")) { [weak self] _ in self?.insertPhoto() }
        ])
        let share = UIMenu(title: L("Share"), image: UIImage(systemName: "square.and.arrow.up"), children: [
            UIAction(title: "PNG") { [weak self] _ in self?.share(jpeg: false) },
            UIAction(title: "JPEG") { [weak self] _ in self?.share(jpeg: true) },
            UIAction(title: L("Save to Photos"), image: UIImage(systemName: "photo.on.rectangle")) { [weak self] _ in self?.saveToPhotos() }
        ])
        let sym = UIMenu(title: L("Symmetry"), image: UIImage(systemName: "circle.lefthalf.filled"), children: Symmetry.allCases.map { s in
            UIAction(title: s.title, state: canvas.symmetry == s ? .on : .off) { [weak self] _ in
                self?.canvas.symmetry = s
                if s != .off { self?.showToast(L("Symmetry") + ": " + s.title) }
            }
        })
        let fill = UIAction(title: L("Fill tool"), image: UIImage(systemName: "drop.fill"), state: canvas.tool == .fill ? .on : .off) { [weak self] _ in
            guard let self = self else { return }
            self.canvas.tool = self.canvas.tool == .fill ? .paint : .fill
            if self.canvas.tool == .fill { self.showToast(L("Tap an area to fill it with the color")) }
            self.updateTools()
        }
        let canvasMenu = UIMenu(title: L("Canvas"), image: UIImage(systemName: "rectangle.dashed"), children: [
            UIAction(title: L("Flip horizontally"), image: UIImage(systemName: "arrow.left.and.right.righttriangle.left.righttriangle.right")) { [weak self] _ in self?.flipCanvas(horizontal: true) },
            UIAction(title: L("Flip vertically"), image: UIImage(systemName: "arrow.up.and.down.righttriangle.up.righttriangle.down")) { [weak self] _ in self?.flipCanvas(horizontal: false) },
            UIAction(title: L("Fit to screen")) { [weak self] _ in self?.canvas.fit(animated: true) },
            UIAction(title: L("Actual size")) { [weak self] _ in self?.canvas.actualSize() },
            UIAction(title: L("Background color")) { [weak self] _ in self?.pickBackground() },
            UIAction(title: L("Background"), state: doc.backgroundVisible ? .on : .off) { [weak self] _ in self?.toggleBackground() },
            UIAction(title: L("Canvas information")) { [weak self] _ in self?.showInfo() }
        ])
        let prefs = UIMenu(title: L("Preferences"), image: UIImage(systemName: "gearshape"), children: [
            UIAction(title: L("Draw with your finger too"), state: canvas.fingerDrawing ? .on : .off) { [weak self] _ in
                guard let self = self else { return }
                self.canvas.fingerDrawing.toggle()
                UserDefaults.standard.set(self.canvas.fingerDrawing, forKey: "fingerDrawing")
            },
            UIAction(title: L("Open-source licenses")) { [weak self] _ in self?.showLicenses() }
        ])
        return [add, share, sym, fill, canvasMenu, prefs]
    }

    private func share(jpeg: Bool) {
        guard let url = ArtworkStore.shared.exportFile(doc, jpeg: jpeg) else { return }
        let vc = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        if let pop = vc.popoverPresentationController {
            pop.sourceView = topBar
            pop.sourceRect = CGRect(x: 60, y: 0, width: 44, height: 50)
        }
        present(vc, animated: true)
    }

    private func saveToPhotos() {
        guard let img = doc.flattened(withBackground: true) else { return }
        UIImageWriteToSavedPhotosAlbum(UIImage(cgImage: img), self, #selector(savedToPhotos(_:didFinishSavingWithError:contextInfo:)), nil)
    }

    @objc private func savedToPhotos(_ image: UIImage, didFinishSavingWithError error: Error?, contextInfo: UnsafeRawPointer) {
        showToast(error == nil ? L("Saved to Photos") : L("Could not save to Photos"))
    }

    private func pickBackground() {
        let picker = UIColorPickerViewController()
        picker.supportsAlpha = false
        picker.selectedColor = HSB.fromRGBA(doc.background).uiColor
        picker.delegate = self
        picker.title = L("Background color")
        picker.view.tag = 77
        popover(picker, from: topBar, size: CGSize(width: 360, height: 560))
    }

    private func toggleBackground() {
        doc.backgroundVisible.toggle()
        doc.dirty = true
        canvas.updateCheckerboard()
        canvas.refresh(nil)
    }

    /// every layer turned over; undo turns them back
    private func flipCanvas(horizontal: Bool) {
        let d = doc
        func flipAll() {
            for l in d.layers {
                let w = l.width, h = l.height, st = l.stride
                if horizontal {
                    for y in 0..<h {
                        let row = l.pixels + y * st
                        var a = 0, b = w - 1
                        while a < b {
                            for c in 0..<4 { let t = row[a * 4 + c]; row[a * 4 + c] = row[b * 4 + c]; row[b * 4 + c] = t }
                            a += 1; b -= 1
                        }
                    }
                } else {
                    let tmp = UnsafeMutablePointer<UInt8>.allocate(capacity: st)
                    defer { tmp.deallocate() }
                    var a = 0, b = h - 1
                    while a < b {
                        let ra = l.pixels + a * st, rb = l.pixels + b * st
                        tmp.update(from: ra, count: st); ra.update(from: rb, count: st); rb.update(from: tmp, count: st)
                        a += 1; b -= 1
                    }
                }
            }
        }
        flipAll()
        canvas.did(HistoryStep(undo: { flipAll() }, redo: { flipAll() }, bytes: 64, rect: nil))
    }

    private func showInfo() {
        let mb = Double(doc.width * doc.height * 4) / 1_000_000
        let text = [
            String(format: L("Canvas size: %d × %d px"), doc.width, doc.height),
            String(format: L("Layers: %d of %d"), doc.layers.count, doc.maxLayers),
            String(format: L("Memory per layer: %.0f MB"), mb),
            String(format: L("Print size at 300 dpi: %.1f × %.1f cm"), Double(doc.width) / 300 * 2.54, Double(doc.height) / 300 * 2.54)
        ].joined(separator: "\n")
        let a = UIAlertController(title: doc.title.isEmpty ? L("Untitled Artwork") : doc.title, message: text, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: L("OK"), style: .default))
        present(a, animated: true)
    }

    private func showLicenses() {
        let url = Bundle.main.url(forResource: "licenses", withExtension: "txt")
        let text = url.flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? ""
        let vc = UIViewController()
        let tv = UITextView()
        tv.text = text
        tv.isEditable = false
        tv.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        tv.backgroundColor = Theme.panel
        tv.textColor = Theme.text
        vc.view = tv
        vc.title = L("Open-source licenses")
        let nav = UINavigationController(rootViewController: vc)
        vc.navigationItem.rightBarButtonItem = UIBarButtonItem(systemItem: .done, primaryAction: UIAction { [weak nav] _ in nav?.dismiss(animated: true) })
        nav.overrideUserInterfaceStyle = .dark
        present(nav, animated: true)
    }

    // MARK: - insert a photo

    private func insertPhoto() {
        if doc.layers.count >= doc.maxLayers { showToast(L("No more layers fit in memory")); return }
        var cfg = PHPickerConfiguration()
        cfg.filter = .images
        cfg.selectionLimit = 1
        let picker = PHPickerViewController(configuration: cfg)
        picker.delegate = self
        present(picker, animated: true)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let item = results.first?.itemProvider, item.canLoadObject(ofClass: UIImage.self) else { return }
        item.loadObject(ofClass: UIImage.self) { [weak self] obj, _ in
            guard let img = obj as? UIImage else { return }
            DispatchQueue.main.async { self?.placePhoto(img) }
        }
    }

    private func placePhoto(_ image: UIImage) {
        // draw it upright (photos carry an orientation)
        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        let upright = UIGraphicsImageRenderer(size: image.size, format: fmt).image { _ in image.draw(at: .zero) }
        guard let cg = upright.cgImage else { return }
        let before = doc.layers, activeBefore = doc.activeIndex
        let l = doc.addLayer()
        l.name = L("Photo")
        let k = min(1, min(CGFloat(doc.width) / CGFloat(cg.width), CGFloat(doc.height) / CGFloat(cg.height)))
        let w = CGFloat(cg.width) * k, h = CGFloat(cg.height) * k
        l.draw(cg, in: CGRect(x: (CGFloat(doc.width) - w) / 2, y: (CGFloat(doc.height) - h) / 2, width: w, height: h))
        canvas.did(History.layers(doc, before: before, activeBefore: activeBefore, after: doc.layers, activeAfter: doc.activeIndex,
                                  extraBytes: l.byteCount))
    }

    // MARK: - closing

    @objc private func closeTapped() {
        save()
        dismiss(animated: true) { [weak self] in self?.onClose?() }
    }

    func save() {
        guard doc.dirty || !FileManager.default.fileExists(atPath: ArtworkStore.shared.folder(doc.id).path) else { return }
        do { try ArtworkStore.shared.save(doc) } catch { showToast(L("Could not save")) }
    }

    // MARK: - helpers

    private func popover(_ vc: UIViewController, from source: UIView, size: CGSize) {
        vc.modalPresentationStyle = .popover
        vc.preferredContentSize = size
        vc.overrideUserInterfaceStyle = .dark
        if let pop = vc.popoverPresentationController {
            pop.sourceView = source
            pop.sourceRect = source.bounds
            pop.permittedArrowDirections = [.up, .down, .left, .right]
            pop.delegate = self
        }
        present(vc, animated: true)
    }

    func adaptivePresentationStyle(for controller: UIPresentationController, traitCollection: UITraitCollection) -> UIModalPresentationStyle {
        // popovers stay popovers on iPad; on iPhone they become sheets
        traitCollection.horizontalSizeClass == .compact ? .formSheet : .none
    }

    private var toastHide: DispatchWorkItem?
    func showToast(_ text: String) {
        toast.text = text
        toastHide?.cancel()
        UIView.animate(withDuration: 0.15) { self.toast.alpha = 1 }
        let w = DispatchWorkItem { [weak self] in UIView.animate(withDuration: 0.3) { self?.toast.alpha = 0 } }
        toastHide = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4, execute: w)
    }
}

/// The current color as a round button in the top bar.
final class ColorDotButton: UIControl {
    var color: UIColor = .black { didSet { setNeedsDisplay() } }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        backgroundColor = .clear
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func draw(_ rect: CGRect) {
        let r = bounds.insetBy(dx: 3, dy: 3)
        color.setFill()
        UIBezierPath(ovalIn: r).fill()
        Theme.text.setStroke()
        let ring = UIBezierPath(ovalIn: r.insetBy(dx: -1, dy: -1))
        ring.lineWidth = 2
        ring.stroke()
    }
}
