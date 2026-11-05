import UIKit

/// The brush library: sets on top, each brush with a sample stroke.
final class BrushLibraryViewController: UITableViewController {
    private let selected: BrushPreset
    private let onPick: (BrushPreset) -> Void
    private let categories = BrushLibrary.categories

    init(selected: BrushPreset, onPick: @escaping (BrushPreset) -> Void) {
        self.selected = selected
        self.onPick = onPick
        super.init(style: .insetGrouped)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = L("Brush Library")
        tableView.register(BrushCell.self, forCellReuseIdentifier: "b")
        tableView.rowHeight = 74
        tableView.backgroundColor = Theme.panel
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        for (s, cat) in categories.enumerated() {
            if let r = brushes(cat).firstIndex(where: { $0 === selected }) {
                tableView.scrollToRow(at: IndexPath(row: r, section: s), at: .middle, animated: false)
            }
        }
    }

    private func brushes(_ cat: String) -> [BrushPreset] { BrushLibrary.all.filter { $0.category == cat } }

    override func numberOfSections(in tableView: UITableView) -> Int { categories.count }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        L(categories[section])
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        brushes(categories[section]).count
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "b", for: indexPath) as! BrushCell
        let p = brushes(categories[indexPath.section])[indexPath.row]
        cell.nameLabel.text = L(p.name)
        cell.stroke.image = BrushLibrary.preview(p)
        cell.backgroundColor = p === selected ? Theme.accent.withAlphaComponent(0.25) : Theme.bar
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        onPick(brushes(categories[indexPath.section])[indexPath.row])
        dismiss(animated: true)
    }
}

final class BrushCell: UITableViewCell {
    let nameLabel = UILabel()
    let stroke = UIImageView()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        nameLabel.font = .systemFont(ofSize: 14, weight: .medium)
        nameLabel.textColor = Theme.text
        stroke.contentMode = .scaleAspectFit
        contentView.addSubview(nameLabel)
        contentView.addSubview(stroke)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let b = contentView.bounds
        nameLabel.frame = CGRect(x: 16, y: 6, width: b.width - 32, height: 20)
        stroke.frame = CGRect(x: 10, y: 26, width: b.width - 20, height: b.height - 30)
    }
}
