package com.abdulmedia.inkora

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView

/**
 * Inkora's own pop-up menu, in place of Android's grey one: dark (or light) like the rest of the
 * interface, with icons, groups split by a thin line, and red rows for things that delete.
 * It reads like android.widget.PopupMenu: menu.add(group, id, order, title), a click listener, show().
 */
class InkMenu(private val context: Context, private val anchor: View) {

    class Item(val groupId: Int, val itemId: Int, val order: Int, var title: CharSequence) {
        var icon: Icon? = null
        var isEnabled = true
        var isChecked = false
        fun icon(i: Icon) = apply { icon = i }
    }

    class Items {
        internal val list = ArrayList<Item>()
        fun add(group: Int, id: Int, order: Int, title: CharSequence): Item = Item(group, id, order, title).also { list.add(it) }
        fun add(title: CharSequence): Item = add(0, list.size, list.size, title)
        fun size() = list.size
    }

    val menu = Items()
    private var listener: ((Item) -> Boolean)? = null
    private var window: PopupWindow? = null

    fun setOnMenuItemClickListener(l: (Item) -> Boolean) { listener = l }

    fun dismiss() { runCatching { window?.dismiss() } }

    fun show() {
        val items = menu.list.sortedWith(compareBy({ it.groupId }, { it.order }))
        if (items.isEmpty()) return
        val withIcons = items.any { (it.icon ?: autoIcon(it.title.toString())) != null }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            layoutDirection = if (L10n.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        var lastGroup = items.first().groupId
        for (it in items) {
            if (it.groupId != lastGroup) {
                box.addView(Ui.divider(context), LinearLayout.LayoutParams(-1, 1.dp).apply { topMargin = 5.dp; bottomMargin = 5.dp; marginStart = 10.dp; marginEnd = 10.dp })
                lastGroup = it.groupId
            }
            box.addView(row(it, withIcons), LinearLayout.LayoutParams(-1, 46.dp))
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(box)
        }
        val card = android.widget.FrameLayout(context).apply {
            background = Ui.rounded(Ui.PANEL, 16f, Ui.OUTLINE, 1)
            clipToOutline = true
            elevation = 14f.dp
            addView(scroll)
        }
        // size: as wide as the longest row (within limits), as tall as fits on the screen
        val dm = context.resources.displayMetrics
        val maxW = minOf(340.dp, dm.widthPixels - 24.dp)
        card.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val w = card.measuredWidth.coerceIn(minOf(210.dp, maxW), maxW)
        val maxH = dm.heightPixels - 96.dp
        val h = if (card.measuredHeight > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT
        val pw = PopupWindow(card, w, h, true).apply {
            setBackgroundDrawable(ColorDrawable(0))
            isOutsideTouchable = true
            elevation = 14f.dp
            animationStyle = android.R.style.Animation_Dialog
        }
        window = pw
        runCatching { pw.showAsDropDown(anchor, 0, 4.dp, Gravity.START) }
    }

    private fun row(item: Item, withIcons: Boolean): View {
        val raw = item.title.toString()
        val checked = item.isChecked || raw.startsWith("✓ ")
        val title = raw.removePrefix("✓ ")
        val danger = isDanger(item)
        val color = when {
            danger -> Ui.DANGER
            checked -> Ui.ACCENT
            else -> Ui.TEXT
        }
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (withIcons) 6.dp else 14.dp, 0, 10.dp, 0)
            background = if (checked) Ui.rounded(Ui.ACCENT_SOFT, 11f) else null
            foreground = Ui.ripple(11f)
            alpha = if (item.isEnabled) 1f else 0.4f
            isClickable = item.isEnabled
            if (item.isEnabled) setOnClickListener {
                dismiss()
                listener?.invoke(item)
            }
        }
        if (withIcons) {
            val ic = item.icon ?: autoIcon(raw)
            r.addView(if (ic != null) IconView(context, ic).apply { isClickable = false; tint = color; iconScale = 0.5f } else View(context),
                LinearLayout.LayoutParams(36.dp, 36.dp))
        }
        // the title is already translated by L10n.menu (or is a name, like a palette's)
        r.addView(android.widget.TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(color)
            maxLines = 2
            setPadding(if (withIcons) 6.dp else 0, 0, 12.dp, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (checked) r.addView(IconView(context, Icon.CHECK).apply { isClickable = false; tint = Ui.ACCENT; iconScale = 0.5f }, LinearLayout.LayoutParams(28.dp, 28.dp))
        return r
    }

    private fun isDanger(item: Item): Boolean {
        val en = item.title.toString().removePrefix("✓ ")
        return dangerWords.any { w -> en.startsWith(w) || en.startsWith(L10n.tr(w)) }
    }

    companion object {
        private val dangerWords = listOf("Delete", "Remove", "Clear")

        /** icons for the menu rows Inkora uses in many places (by the English title, or its translation) */
        private val autoIcons: List<Pair<String, Icon>> = listOf(
            "Preview" to Icon.EYE, "Rename" to Icon.EDIT, "Duplicate" to Icon.COPY, "Move to stack" to Icon.FOLDER,
            "Take out of the stack" to Icon.EXPORT, "Unstack" to Icon.LAYERS, "Delete" to Icon.CLEAR, "Remove" to Icon.CLEAR,
            "Import file" to Icon.IMPORT, "Import from link" to Icon.GLOBE, "New brush" to Icon.PLUS, "Export set" to Icon.EXPORT,
            "Replace with current color" to Icon.RECOLOR, "New palette from artwork" to Icon.PALETTE,
            "New palette from photo" to Icon.PHOTO, "New palette" to Icon.PLUS,
            "Drawing layer" to Icon.BRUSH, "Vector layer" to Icon.VECTOR,
            "Creative tools" to Icon.SPARKLE, "Editing tools" to Icon.WAND,
            "Photo as a new artwork" to Icon.PHOTO, "Inkora project or Photoshop file" to Icon.FOLDER,
            "Restore a backup" to Icon.SNAPSHOT, "Back up all artworks" to Icon.BACKUP, "Feather" to Icon.FEATHER
        )

        fun autoIcon(title: String): Icon? {
            val t = title.removePrefix("✓ ")
            return autoIcons.firstOrNull { (k, _) -> t.startsWith(k) || t.startsWith(L10n.tr(k)) }?.second
        }
    }
}
