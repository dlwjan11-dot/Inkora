import UIKit

/// The gallery: every artwork as a picture with its name. + makes a new canvas.
final class GalleryViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegate,
                                   UICollectionViewDelegateFlowLayout {
    private var items: [ArtworkInfo] = []
    private var collection: UICollectionView!
    private let empty = UILabel()
    private var thumbs: [UUID: UIImage] = [:]

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Inkora"
        view.backgroundColor = Theme.canvasBack
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 22
        layout.minimumLineSpacing = 28
        layout.sectionInset = UIEdgeInsets(top: 20, left: 24, bottom: 40, right: 24)
        collection = UICollectionView(frame: view.bounds, collectionViewLayout: layout)
        collection.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collection.backgroundColor = .clear
        collection.dataSource = self
        collection.delegate = self
        collection.register(ArtworkCell.self, forCellWithReuseIdentifier: "a")
        view.addSubview(collection)

        empty.text = L("Tap + to make your first artwork")
        empty.textColor = Theme.dim
        empty.font = .systemFont(ofSize: 17, weight: .medium)
        empty.textAlignment = .center
        empty.numberOfLines = 0
        view.addSubview(empty)

        let plus = UIBarButtonItem(image: UIImage(systemName: "plus"), menu: newCanvasMenu())
        plus.accessibilityLabel = L("New canvas")
        navigationItem.rightBarButtonItem = plus
        navigationItem.largeTitleDisplayMode = .always
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reload()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        empty.frame = view.bounds.insetBy(dx: 40, dy: 0)
        collection.collectionViewLayout.invalidateLayout()
    }

    func reload() {
        items = ArtworkStore.shared.list()
        thumbs.removeAll()
        collection.reloadData()
        empty.isHidden = !items.isEmpty
    }

    // MARK: - new canvas

    private func newCanvasMenu() -> UIMenu {
        let screen = UIScreen.main.nativeBounds.size
        let sw = Int(max(screen.width, screen.height)), sh = Int(min(screen.width, screen.height))
        let sizes: [(String, Int, Int)] = [
            (L("Screen size"), sw, sh),
            (L("Square"), 2048, 2048),
            ("4K", 3840, 2160),
            ("A4 300 dpi", 2480, 3508),
            (L("Instagram post"), 1080, 1350),
            (L("Comic page"), 1988, 3056)
        ]
        var actions: [UIMenuElement] = sizes.map { s in
            UIAction(title: s.0, subtitle: "\(s.1) × \(s.2) px") { [weak self] _ in self?.create(width: s.1, height: s.2) }
        }
        actions.append(UIAction(title: L("Custom size…"), image: UIImage(systemName: "slider.horizontal.3")) { [weak self] _ in
            self?.askCustomSize()
        })
        return UIMenu(title: L("New canvas"), children: actions)
    }

    private func askCustomSize() {
        let a = UIAlertController(title: L("Custom size"), message: L("Width and height in pixels"), preferredStyle: .alert)
        a.addTextField { $0.placeholder = L("Width"); $0.keyboardType = .numberPad; $0.text = "3000" }
        a.addTextField { $0.placeholder = L("Height"); $0.keyboardType = .numberPad; $0.text = "2000" }
        a.addAction(UIAlertAction(title: L("Cancel"), style: .cancel))
        a.addAction(UIAlertAction(title: L("Create"), style: .default) { [weak self] _ in
            let w = Int(a.textFields?[0].text ?? "") ?? 0
            let h = Int(a.textFields?[1].text ?? "") ?? 0
            self?.create(width: w, height: h)
        })
        present(a, animated: true)
    }

    private func create(width: Int, height: Int) {
        let w = max(16, min(8192, width)), h = max(16, min(8192, height))
        if w * h > 36_000_000 {
            let a = UIAlertController(title: nil, message: L("That canvas is too big. The largest is about 36 megapixels."), preferredStyle: .alert)
            a.addAction(UIAlertAction(title: L("OK"), style: .default))
            present(a, animated: true)
            return
        }
        let doc = Document(width: w, height: h, title: L("Untitled Artwork"))
        doc.addLayer()
        doc.dirty = true
        open(doc)
    }

    private func open(_ doc: Document) {
        let vc = CanvasViewController(document: doc)
        vc.onClose = { [weak self] in self?.reload() }
        present(vc, animated: true)
    }

    // MARK: - collection

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int { items.count }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "a", for: indexPath) as! ArtworkCell
        let it = items[indexPath.item]
        cell.title.text = it.title.isEmpty ? L("Untitled Artwork") : it.title
        cell.sizeLabel.text = "\(it.width) × \(it.height)"
        if let t = thumbs[it.id] { cell.image.image = t } else {
            let img = UIImage(contentsOfFile: it.thumbnailURL.path)
            thumbs[it.id] = img
            cell.image.image = img
        }
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, layout l: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        let w = collectionView.bounds.width - 48
        let cols = max(2, Int(w / 230))
        let cw = floor((w - CGFloat(cols - 1) * 22) / CGFloat(cols))
        return CGSize(width: cw, height: cw * 0.78 + 46)
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        let it = items[indexPath.item]
        guard let doc = ArtworkStore.shared.load(it.id) else { return }
        open(doc)
    }

    func collectionView(_ collectionView: UICollectionView, contextMenuConfigurationForItemsAt indexPaths: [IndexPath], point: CGPoint) -> UIContextMenuConfiguration? {
        guard let ip = indexPaths.first else { return nil }
        let it = items[ip.item]
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            UIMenu(children: [
                UIAction(title: L("Rename"), image: UIImage(systemName: "pencil")) { _ in self?.renameArtwork(it) },
                UIAction(title: L("Share"), image: UIImage(systemName: "square.and.arrow.up")) { _ in self?.shareArtwork(it, at: ip) },
                UIAction(title: L("Duplicate"), image: UIImage(systemName: "plus.square.on.square")) { _ in
                    ArtworkStore.shared.duplicate(it.id); self?.reload()
                },
                UIAction(title: L("Delete"), image: UIImage(systemName: "trash"), attributes: .destructive) { _ in self?.confirmDelete(it) }
            ])
        }
    }

    private func renameArtwork(_ it: ArtworkInfo) {
        let a = UIAlertController(title: L("Rename"), message: nil, preferredStyle: .alert)
        a.addTextField { $0.text = it.title }
        a.addAction(UIAlertAction(title: L("Cancel"), style: .cancel))
        a.addAction(UIAlertAction(title: L("OK"), style: .default) { [weak self] _ in
            ArtworkStore.shared.rename(it.id, to: a.textFields?.first?.text ?? "")
            self?.reload()
        })
        present(a, animated: true)
    }

    private func shareArtwork(_ it: ArtworkInfo, at ip: IndexPath) {
        guard let doc = ArtworkStore.shared.load(it.id), let url = ArtworkStore.shared.exportFile(doc, jpeg: false) else { return }
        let vc = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        if let pop = vc.popoverPresentationController, let cell = collection.cellForItem(at: ip) {
            pop.sourceView = cell
            pop.sourceRect = cell.bounds
        }
        present(vc, animated: true)
    }

    private func confirmDelete(_ it: ArtworkInfo) {
        let a = UIAlertController(title: L("Delete this artwork?"), message: L("This can't be undone."), preferredStyle: .alert)
        a.addAction(UIAlertAction(title: L("Cancel"), style: .cancel))
        a.addAction(UIAlertAction(title: L("Delete"), style: .destructive) { [weak self] _ in
            ArtworkStore.shared.delete(it.id)
            self?.reload()
        })
        present(a, animated: true)
    }
}

final class ArtworkCell: UICollectionViewCell {
    let image = UIImageView()
    let title = UILabel()
    let sizeLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        image.contentMode = .scaleAspectFit
        image.layer.shadowColor = UIColor.black.cgColor
        image.layer.shadowOpacity = 0.4
        image.layer.shadowRadius = 8
        image.layer.shadowOffset = CGSize(width: 0, height: 3)
        title.textColor = Theme.text
        title.font = .systemFont(ofSize: 15, weight: .semibold)
        title.textAlignment = .center
        sizeLabel.textColor = Theme.dim
        sizeLabel.font = .systemFont(ofSize: 12)
        sizeLabel.textAlignment = .center
        [image, title, sizeLabel].forEach { contentView.addSubview($0) }
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let b = contentView.bounds
        image.frame = CGRect(x: 0, y: 0, width: b.width, height: b.height - 46)
        title.frame = CGRect(x: 0, y: b.height - 42, width: b.width, height: 22)
        sizeLabel.frame = CGRect(x: 0, y: b.height - 20, width: b.width, height: 18)
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        image.image = nil
    }
}
