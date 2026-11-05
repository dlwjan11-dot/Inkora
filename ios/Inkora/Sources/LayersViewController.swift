import UIKit

/// The layers panel: top layer first. Tap to pick a layer, the switch to hide it, the
/// letters to change its blend mode, press and hold for more; Edit to reorder.
final class LayersViewController: UITableViewController {
    private let canvas: CanvasView
    private var doc: Document { canvas.doc }

    init(canvas: CanvasView) {
        self.canvas = canvas
        super.init(style: .plain)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.register(LayerCell.self, forCellReuseIdentifier: "l")
        tableView.rowHeight = 64
        tableView.backgroundColor = Theme.panel
        tableView.separatorColor = UIColor(white: 1, alpha: 0.08)
        let header = UIView(frame: CGRect(x: 0, y: 0, width: 320, height: 54))
        let title = UILabel(frame: CGRect(x: 16, y: 12, width: 160, height: 30))
        title.text = L("Layers")
        title.font = .systemFont(ofSize: 20, weight: .semibold)
        title.textColor = Theme.text
        title.autoresizingMask = [.flexibleRightMargin]
        header.addSubview(title)
        let add = UIButton(type: .system)
        add.setImage(UIImage(systemName: "plus", withConfiguration: UIImage.SymbolConfiguration(pointSize: 20, weight: .medium)), for: .normal)
        add.tintColor = Theme.text
        add.frame = CGRect(x: 320 - 52, y: 5, width: 44, height: 44)
        add.autoresizingMask = [.flexibleLeftMargin]
        add.accessibilityLabel = L("Add layer")
        add.addTarget(self, action: #selector(addLayer), for: .touchUpInside)
        header.addSubview(add)
        let edit = UIButton(type: .system)
        edit.setTitle(L("Edit"), for: .normal)
        edit.tintColor = Theme.text
        edit.frame = CGRect(x: 320 - 52 - 70, y: 5, width: 64, height: 44)
        edit.autoresizingMask = [.flexibleLeftMargin]
        edit.addTarget(self, action: #selector(toggleEdit(_:)), for: .touchUpInside)
        header.addSubview(edit)
        tableView.tableHeaderView = header
    }

    // rows: the top layer first
    private func layerIndex(_ row: Int) -> Int { doc.layers.count - 1 - row }
    private func row(_ layerIndex: Int) -> Int { doc.layers.count - 1 - layerIndex }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { doc.layers.count }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "l", for: indexPath) as! LayerCell
        let i = layerIndex(indexPath.row)
        let l = doc.layers[i]
        cell.nameLabel.text = l.name
        cell.thumb.image = l.thumbnail(maxSide: 46)
        cell.visibleSwitch.isOn = l.visible
        cell.blendButton.setTitle(l.blend.code + (l.alphaLock ? " 🔒" : ""), for: .normal)
        cell.backgroundColor = i == doc.activeIndex ? Theme.accent.withAlphaComponent(0.35) : Theme.bar
        cell.onVisible = { [weak self] on in self?.setVisible(l, on) }
        cell.blendButton.menu = blendMenu(l)
        cell.blendButton.showsMenuAsPrimaryAction = true
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        doc.activeIndex = layerIndex(indexPath.row)
        tableView.reloadData()
    }

    // MARK: - changes

    private func step(_ change: () -> Void) {
        let before = doc.layers, ab = doc.activeIndex
        change()
        canvas.did(History.layers(doc, before: before, activeBefore: ab, after: doc.layers, activeAfter: doc.activeIndex))
        tableView.reloadData()
    }

    /// a setting of one layer, with undo
    private func setting(_ l: Layer, _ apply: @escaping (Layer) -> Void) {
        let old = (l.visible, l.opacity, l.blend, l.alphaLock, l.name)
        apply(l)
        let new = (l.visible, l.opacity, l.blend, l.alphaLock, l.name)
        canvas.did(HistoryStep(undo: {
            l.visible = old.0; l.opacity = old.1; l.blend = old.2; l.alphaLock = old.3; l.name = old.4
        }, redo: {
            l.visible = new.0; l.opacity = new.1; l.blend = new.2; l.alphaLock = new.3; l.name = new.4
        }, bytes: 64, rect: nil))
        tableView.reloadData()
    }

    private func setVisible(_ l: Layer, _ on: Bool) { setting(l) { $0.visible = on } }

    @objc private func addLayer() {
        if doc.layers.count >= doc.maxLayers {
            alert(L("No more layers fit in memory"))
            return
        }
        step { doc.addLayer() }
    }

    @objc private func toggleEdit(_ b: UIButton) {
        tableView.setEditing(!tableView.isEditing, animated: true)
        b.setTitle(tableView.isEditing ? L("Done") : L("Edit"), for: .normal)
    }

    override func tableView(_ tableView: UITableView, editingStyleForRowAt indexPath: IndexPath) -> UITableViewCell.EditingStyle { .none }
    override func tableView(_ tableView: UITableView, shouldIndentWhileEditingRowAt indexPath: IndexPath) -> Bool { false }
    override func tableView(_ tableView: UITableView, canMoveRowAt indexPath: IndexPath) -> Bool { true }

    override func tableView(_ tableView: UITableView, moveRowAt from: IndexPath, to: IndexPath) {
        let before = doc.layers, ab = doc.activeIndex
        let active = doc.active
        let l = doc.layers.remove(at: layerIndex(from.row))
        doc.layers.insert(l, at: doc.layers.count - to.row)
        doc.activeIndex = doc.layers.firstIndex { $0 === active } ?? 0
        canvas.did(History.layers(doc, before: before, activeBefore: ab, after: doc.layers, activeAfter: doc.activeIndex))
        DispatchQueue.main.async { tableView.reloadData() }
    }

    override func tableView(_ tableView: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        let i = layerIndex(indexPath.row)
        let del = UIContextualAction(style: .destructive, title: L("Delete")) { [weak self] _, _, done in
            self?.deleteLayer(i); done(true)
        }
        let dup = UIContextualAction(style: .normal, title: L("Duplicate")) { [weak self] _, _, done in
            self?.duplicateLayer(i); done(true)
        }
        dup.backgroundColor = Theme.accent
        return UISwipeActionsConfiguration(actions: [del, dup])
    }

    override func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
        let i = layerIndex(indexPath.row)
        let l = doc.layers[i]
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            guard let self = self else { return nil }
            var items: [UIMenuElement] = [
                UIAction(title: L("Rename"), image: UIImage(systemName: "pencil")) { _ in self.renameLayer(l) },
                UIAction(title: L("Opacity"), image: UIImage(systemName: "circle.lefthalf.filled")) { _ in self.opacity(l, row: indexPath) },
                self.blendMenu(l),
                UIAction(title: L("Alpha lock"), image: UIImage(systemName: "lock"), state: l.alphaLock ? .on : .off) { _ in
                    self.setting(l) { $0.alphaLock.toggle() }
                },
                UIAction(title: L("Duplicate"), image: UIImage(systemName: "plus.square.on.square")) { _ in self.duplicateLayer(i) },
                UIAction(title: L("Clear"), image: UIImage(systemName: "eraser")) { _ in self.clearLayer(l) }
            ]
            if i > 0 {
                items.append(UIAction(title: L("Merge down"), image: UIImage(systemName: "arrow.down.square")) { _ in self.mergeDown(i) })
            }
            items.append(UIAction(title: L("Delete"), image: UIImage(systemName: "trash"), attributes: .destructive) { _ in self.deleteLayer(i) })
            return UIMenu(title: l.name, children: items)
        }
    }

    private func blendMenu(_ l: Layer) -> UIMenu {
        UIMenu(title: L("Blend mode"), image: UIImage(systemName: "square.on.square"), children: BlendMode.allCases.map { m in
            UIAction(title: m.title, state: l.blend == m ? .on : .off) { [weak self] _ in self?.setting(l) { $0.blend = m } }
        })
    }

    private func deleteLayer(_ i: Int) {
        if doc.layers.count <= 1 {
            // the last layer is cleared instead
            clearLayer(doc.layers[0])
            return
        }
        let l = doc.layers[i]
        step {
            doc.layers.remove(at: i)
            doc.activeIndex = max(0, min(doc.layers.count - 1, i - 1))
        }
        _ = l
    }

    private func duplicateLayer(_ i: Int) {
        if doc.layers.count >= doc.maxLayers { alert(L("No more layers fit in memory")); return }
        let src = doc.layers[i]
        step {
            let c = src.copy(named: src.name + " " + L("copy"))
            doc.layers.insert(c, at: i + 1)
            doc.activeIndex = i + 1
        }
    }

    private func clearLayer(_ l: Layer) {
        let before = l.region(doc.bounds)
        l.clear()
        let after = l.region(doc.bounds)
        canvas.did(History.pixels(l, doc.bounds, before: before, after: after))
        tableView.reloadData()
    }

    /// the layer is drawn into the one under it (with its opacity and blend mode)
    private func mergeDown(_ i: Int) {
        guard i > 0 else { return }
        let top = doc.layers[i], below = doc.layers[i - 1]
        let beforePixels = below.region(doc.bounds)
        let d = doc
        // draw the two layers together with no background into the lower one
        let refs = [
            InkLayerRef(pixels: UnsafePointer(below.pixels), stride: Int32(below.stride), opacity: below.visible ? 1 : 0, blend: BlendMode.normal.rawValue),
            InkLayerRef(pixels: UnsafePointer(top.pixels), stride: Int32(top.stride), opacity: top.visible ? top.opacity : 0, blend: top.blend.rawValue)
        ]
        let tmp = UnsafeMutablePointer<UInt8>.allocate(capacity: below.byteCount)
        defer { tmp.deallocate() }
        refs.withUnsafeBufferPointer { b in
            ink_composite(tmp, Int32(below.stride), 0, 0, Int32(d.width), Int32(d.height), b.baseAddress, 2, 0)
        }
        below.pixels.update(from: tmp, count: below.byteCount)
        let afterPixels = below.region(d.bounds)
        let layersBefore = d.layers, ab = d.activeIndex
        d.layers.remove(at: i)
        d.activeIndex = i - 1
        canvas.did(History.layers(d, before: layersBefore, activeBefore: ab, after: d.layers, activeAfter: d.activeIndex,
                                  extraBytes: beforePixels.count * 2,
                                  undoExtra: { below.setRegion(d.bounds, beforePixels) },
                                  redoExtra: { below.setRegion(d.bounds, afterPixels) }))
        tableView.reloadData()
    }

    private func renameLayer(_ l: Layer) {
        let a = UIAlertController(title: L("Rename"), message: nil, preferredStyle: .alert)
        a.addTextField { $0.text = l.name }
        a.addAction(UIAlertAction(title: L("Cancel"), style: .cancel))
        a.addAction(UIAlertAction(title: L("OK"), style: .default) { [weak self] _ in
            let name = a.textFields?.first?.text?.trimmingCharacters(in: .whitespaces) ?? ""
            if !name.isEmpty { self?.setting(l) { $0.name = name } }
        })
        present(a, animated: true)
    }

    private func opacity(_ l: Layer, row: IndexPath) {
        let vc = OpacityViewController(value: l.opacity) { [weak self] v, final in
            l.opacity = v
            self?.canvas.refresh(nil)
            if final { self?.tableView.reloadData() }
        }
        let start = l.opacity
        vc.onDone = { [weak self] in
            let end = l.opacity
            if end != start {
                self?.canvas.did(HistoryStep(undo: { l.opacity = start }, redo: { l.opacity = end }, bytes: 64, rect: nil))
            }
        }
        vc.modalPresentationStyle = .popover
        vc.preferredContentSize = CGSize(width: 300, height: 90)
        if let pop = vc.popoverPresentationController, let cell = tableView.cellForRow(at: row) {
            pop.sourceView = cell
            pop.sourceRect = cell.bounds
        }
        present(vc, animated: true)
    }

    private func alert(_ text: String) {
        let a = UIAlertController(title: nil, message: text, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: L("OK"), style: .default))
        present(a, animated: true)
    }
}

final class LayerCell: UITableViewCell {
    let thumb = UIImageView()
    let nameLabel = UILabel()
    let blendButton = UIButton(type: .system)
    let visibleSwitch = UISwitch()
    var onVisible: ((Bool) -> Void)?

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        thumb.contentMode = .scaleAspectFit
        thumb.layer.cornerRadius = 4
        thumb.layer.masksToBounds = true
        nameLabel.textColor = Theme.text
        nameLabel.font = .systemFont(ofSize: 15, weight: .medium)
        blendButton.tintColor = Theme.text
        blendButton.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        visibleSwitch.onTintColor = Theme.accent
        visibleSwitch.addTarget(self, action: #selector(switched), for: .valueChanged)
        [thumb, nameLabel, blendButton, visibleSwitch].forEach { contentView.addSubview($0) }
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    @objc private func switched() { onVisible?(visibleSwitch.isOn) }

    override func layoutSubviews() {
        super.layoutSubviews()
        let b = contentView.bounds
        thumb.frame = CGRect(x: 12, y: (b.height - 46) / 2, width: 46, height: 46)
        let sw = visibleSwitch.intrinsicContentSize
        visibleSwitch.frame = CGRect(x: b.width - sw.width - 12, y: (b.height - sw.height) / 2, width: sw.width, height: sw.height)
        blendButton.frame = CGRect(x: visibleSwitch.frame.minX - 52, y: (b.height - 36) / 2, width: 46, height: 36)
        nameLabel.frame = CGRect(x: thumb.frame.maxX + 12, y: 0, width: max(10, blendButton.frame.minX - thumb.frame.maxX - 16), height: b.height)
    }
}

/// A slider for a layer's opacity.
final class OpacityViewController: UIViewController {
    private let slider = UISlider()
    private let label = UILabel()
    private let onChange: (Float, Bool) -> Void
    var onDone: (() -> Void)?

    init(value: Float, onChange: @escaping (Float, Bool) -> Void) {
        self.onChange = onChange
        super.init(nibName: nil, bundle: nil)
        slider.value = value
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.panel
        overrideUserInterfaceStyle = .dark
        slider.minimumValue = 0
        slider.maximumValue = 1
        slider.minimumTrackTintColor = Theme.accent
        slider.addTarget(self, action: #selector(changed), for: .valueChanged)
        slider.addTarget(self, action: #selector(released), for: [.touchUpInside, .touchUpOutside])
        label.textColor = Theme.text
        label.font = .monospacedDigitSystemFont(ofSize: 15, weight: .medium)
        label.textAlignment = .right
        view.addSubview(slider)
        view.addSubview(label)
        updateLabel()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let b = view.bounds
        slider.frame = CGRect(x: 16, y: (b.height - 34) / 2, width: b.width - 90, height: 34)
        label.frame = CGRect(x: b.width - 70, y: (b.height - 24) / 2, width: 56, height: 24)
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        onDone?()
        onDone = nil
    }

    private func updateLabel() { label.text = "\(Int((slider.value * 100).rounded()))%" }

    @objc private func changed() { updateLabel(); onChange(slider.value, false) }
    @objc private func released() { onChange(slider.value, true) }
}
