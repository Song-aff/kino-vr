package dev.astriavr.player

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 首页通用的「可聚焦条目」渲染器（2D 实现）。
 *
 * 它只认识 [HomeItem]，不认识设备、串流、语言或任何业务；未来 VR 化时
 * 由 VrHomeRenderer 用同一份 items 画空间卡片，这个 View 可以被整体替换。
 */
class HomeItemListView(
    context: Context,
    private val onClick: (HomeItem) -> Unit,
    private val onLongClick: (HomeItem) -> Unit = {}
) : LinearLayout(context) {
    private var items: List<HomeItem> = emptyList()
    private val rows = ArrayList<View>()
    /** 每行内所有文字视图 + 它们的常态色，用于橙底选中态的精确还原。 */
    private val rowTexts = ArrayList<List<Pair<TextView, Int>>>()
    private var focus = -1
    private val dp = { value: Int -> UiStyle.dp(context, value) }

    init { orientation = VERTICAL }

    fun setItems(next: List<HomeItem>) {
        items = next
        rows.clear()
        rowTexts.clear()
        removeAllViews()
        for (item in next) {
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                isFocusable = true
            }
            val titleRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(TextView(context).apply {
                text = item.title
                setTextColor(UiStyle.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }, LayoutParams(0, -2, 1f))
            if (item.badge.isNotEmpty()) {
                titleRow.addView(UiStyle.label(context, item.badge, 11f, UiStyle.ACCENT, bold = true))
            }
            card.addView(titleRow, LayoutParams(-1, -2))
            if (item.subtitle.isNotEmpty()) {
                card.addView(UiStyle.label(context, item.subtitle, 11f, UiStyle.MUTED).apply {
                    setPadding(0, dp(3), 0, 0)
                }, LayoutParams(-1, -2))
            }
            card.setOnClickListener { onClick(item) }
            card.setOnLongClickListener { onLongClick(item); true }
            rowTexts.add(collectTexts(card))
            rows.add(card)
            addView(card, LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        setFocus(focus)
    }

    private fun collectTexts(root: View): List<Pair<TextView, Int>> {
        val out = ArrayList<Pair<TextView, Int>>()
        fun walk(view: View) {
            if (view is TextView) out.add(view to view.currentTextColor)
            else if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return out
    }

    fun setFocus(index: Int) {
        focus = index
        for (i in rows.indices) {
            val active = i == index
            rows[i].background = if (active) UiStyle.selected(context, 10)
            else UiStyle.ripple(context, UiStyle.SURFACE, 10)
            // 橙实心底上白字/灰字对比度不足，整行文字统一切成近黑；取消焦点时逐字还原。
            for ((text, normalColor) in rowTexts.getOrElse(i) { emptyList() })
                text.setTextColor(if (active) UiStyle.BRAND_INK else normalColor)
        }
    }

    fun itemAt(index: Int): HomeItem? = items.getOrNull(index)
}
