package com.checkinassistant

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v8: 底部导航栏 —— 与 Web 原型 components/app/bottom-nav.tsx 1:1 对齐。
 *
 * nav: border-t border-black/5 + bg-white/95；ul: px-2，三个等宽 tab；
 * tab: flex-col items-center gap-1 py-2.5，图标 23px（选中 strokeWidth 2.4 / 未选中 2），
 * 文字 11px font-medium，选中 text-primary / 未选中 #9aa4b4。
 *
 * 单 Activity + Fragment 架构：点击 tab 通过 [Bar.onSelect] 回调由宿主切换内容。
 */
object BottomNav {

    private const val TAB_HEIGHT_DP = 61 // py-2.5(10) + icon 23 + gap 4 + label ~14 + py-2.5(10)

    /**
     * 底部导航栏视图。持有三个 tab 的图标/文字，通过 [select] 刷新选中态。
     *
     * v11 修复「从历史/设置点底部『提醒点』回不去」：
     * 旧实现在 build() 时把 `active = index == current` 算成一个**一次性捕获的布尔值**，
     * 点击回调写成 `if (!active) onSelect(index)`。导航栏只在 Activity 创建时构建一次
     * （current 恒为 0），所以 tab 0 的 active 永远为 true，它的点击被永久吞掉——
     * 只有系统返回键（onBackPressed → switchTab(0)）才回得去。
     * 现在点击一律回调，选中态交给 [select] 在每次切换时刷新。
     */
    class Bar(activity: Activity) : LinearLayout(activity) {

        private class Tab(
            val icon: ImageView,
            val label: TextView,
            val activeIcon: Int,
            val inactiveIcon: Int
        )

        private val tabs = mutableListOf<Tab>()
        private var current = -1

        /** 宿主切换内容：参数为 0 提醒点 / 1 历史 / 2 设置。 */
        var onSelect: ((Int) -> Unit)? = null

        /** 在 [host] 行内追加一个 tab（build 内部使用）。 */
        internal fun addTab(host: LinearLayout, activeIcon: Int, inactiveIcon: Int, label: String) {
            val index = tabs.size
            val icon = Ui.icon(context, inactiveIcon, 23, Ui.NAV_INACTIVE)
            val text = Ui.text(context, 11f, Ui.NAV_INACTIVE, Ui.SEMIBOLD, label)
                .apply { gravity = Gravity.CENTER }
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = Ui.dp(context, TAB_HEIGHT_DP)
                isClickable = true
                setOnClickListener { onSelect?.invoke(index) }
            }
            tab.addView(icon, LinearLayout.LayoutParams(Ui.dp(context, 23), Ui.dp(context, 23)))
            tab.addView(
                text,
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(context, 4) }
            )
            host.addView(tab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            tabs.add(Tab(icon, text, activeIcon, inactiveIcon))
        }

        /** 刷新选中态：只改图标与文字配色，不重建视图，因此不影响页面状态。 */
        fun select(index: Int) {
            if (index == current || index !in tabs.indices) return
            current = index
            tabs.forEachIndexed { i, tab ->
                val on = i == index
                tab.icon.setImageResource(if (on) tab.activeIcon else tab.inactiveIcon)
                tab.icon.setColorFilter(if (on) Ui.PRIMARY else Ui.NAV_INACTIVE)
                tab.label.setTextColor(if (on) Ui.PRIMARY else Ui.NAV_INACTIVE)
            }
        }
    }

    /** current = 0 home, 1 history, 2 settings. */
    fun build(activity: Activity, current: Int, onSelect: (Int) -> Unit): Bar {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(Ui.dp(activity, 8), 0, Ui.dp(activity, 8), 0)
        }

        // bg-white/95 铺满整条（含导航栏安全区），顶部分隔线 border-black/5。
        val bar = Bar(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(242, 255, 255, 255))
            addView(
                View(activity).apply { setBackgroundColor(Ui.HAIRLINE) },
                LinearLayout.LayoutParams(-1, 1)
            )
            addView(row, LinearLayout.LayoutParams(-1, -2))
            this.onSelect = onSelect
        }

        bar.addTab(row, R.drawable.ic_nav_map_pin_active, R.drawable.ic_map_pin, "提醒点")
        bar.addTab(row, R.drawable.ic_nav_history_active, R.drawable.ic_history, "历史")
        bar.addTab(row, R.drawable.ic_nav_settings_active, R.drawable.ic_settings, "设置")
        bar.select(current)
        return bar
    }
}
