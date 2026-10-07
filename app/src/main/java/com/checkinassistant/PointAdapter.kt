package com.checkinassistant

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.checkinassistant.data.CheckInPoint
import com.checkinassistant.data.PointColors

/**
 * v8: 提醒点列表卡片 —— 与 Web 原型 components/app/reminders-page.tsx 的 ReminderCard 1:1 对齐。
 *
 * 白底 24dp 圆角 + 阴影；启用时左侧 4dp 主色竖条；停用整卡 opacity-70。
 * 结构：[TintedIcon 44] [名称 15sp/半粗 (+状态胶囊)] / [计划 12.5sp] [Toggle 46x28] [Chevron 20]
 */
class PointAdapter(
    private val onEdit: (CheckInPoint) -> Unit,
    private val onDelete: (CheckInPoint) -> Unit,
    private val onToggle: (CheckInPoint, Boolean) -> Unit,
    private val onSkipToday: (CheckInPoint) -> Unit = {},
    private val onRestore: (CheckInPoint) -> Unit = {},
    private val onAcknowledge: (CheckInPoint) -> Unit = {}
) : RecyclerView.Adapter<PointAdapter.Holder>() {

    private var points: List<CheckInPoint> = emptyList()

    /** pointId → status of the point's latest record, drives the "已到达，未确认" badge. */
    private var latestStatusByPoint: Map<Long, String> = emptyMap()

    fun submitList(newPoints: List<CheckInPoint>, latestStatus: Map<Long, String>? = null) {
        points = newPoints
        if (latestStatus != null) latestStatusByPoint = latestStatus
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val gap = Ui.dp(context, 12) // gap-3

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 14)) // px-4 py-3.5
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = gap }
            elevation = Ui.dpf(context, 1f) // shadow-sm shadow-black/5
            Ui.roundOutline(this, Ui.dpf(context, 22f))
        }

        val tintIcon = Ui.tintedIconOf(context, R.drawable.ic_map_pin, Ui.PRIMARY)
        root.addView(
            tintIcon,
            LinearLayout.LayoutParams(Ui.dp(context, 44), Ui.dp(context, 44)).apply { rightMargin = gap }
        )

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val nameRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val name = Ui.text(context, 15f, Ui.TEXT_MAIN, Ui.SEMIBOLD).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nameRow.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val badge = Ui.pill(context, "", Ui.Tint.SLATE.bg, Ui.CHIP_FG).apply {
            visibility = View.GONE
            textSize = 11f
            setPadding(Ui.dp(context, 8), Ui.dp(context, 2), Ui.dp(context, 8), Ui.dp(context, 2))
        }
        nameRow.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(context, 8) })
        texts.addView(nameRow, LinearLayout.LayoutParams(-1, -2))

        val schedule = Ui.text(context, 12.5f, Ui.TEXT_SUB).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(
            schedule,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 4) }
        )
        root.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val toggle = ToggleView(context)
        root.addView(
            toggle,
            LinearLayout.LayoutParams(Ui.dp(context, 46), Ui.dp(context, 28)).apply { leftMargin = gap }
        )

        root.addView(
            Ui.chevron(context),
            LinearLayout.LayoutParams(Ui.dp(context, 20), Ui.dp(context, 20)).apply { leftMargin = gap }
        )

        return Holder(root, tintIcon, name, schedule, toggle, badge)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(points[position])

    override fun getItemCount() = points.size

    inner class Holder(
        view: LinearLayout,
        private val tintIcon: android.widget.FrameLayout,
        private val name: TextView,
        private val schedule: TextView,
        private val toggle: ToggleView,
        private val badge: TextView
    ) : RecyclerView.ViewHolder(view) {

        private var current: CheckInPoint? = null

        init {
            // 交互保持不变：点按进入编辑页，长按弹出跳过/确认/删除。
            view.setOnClickListener { current?.let(onEdit) }
            view.setOnLongClickListener {
                current?.let { point ->
                    val unacknowledged = point.repeatMode == "ONCE" &&
                        point.onceStatus == "COMPLETED" &&
                        latestStatusByPoint[point.id] == com.checkinassistant.data.RecordStatus.IGNORED.name
                    val items = buildList {
                        add("今天跳过")
                        if (unacknowledged) add("确认已处理")
                        add("删除")
                    }
                    android.app.AlertDialog.Builder(view.context)
                        .setTitle(point.name)
                        .setItems(items.toTypedArray()) { _, which ->
                            when (items[which]) {
                                "今天跳过" -> onSkipToday(point)
                                "确认已处理" -> onAcknowledge(point)
                                "删除" -> onDelete(point)
                            }
                        }
                        .show()
                }
                true
            }
        }

        fun bind(point: CheckInPoint) {
            current = point
            val context = itemView.context
            val completed = point.repeatMode == "ONCE" && point.onceStatus == "COMPLETED"
            val isOff = !point.enabled

            name.text = point.name

            // 计划行：与原型 schedule 同样由「重复方式 · 时间段」拼成。
            val repeat = when (point.repeatMode) {
                "WEEKLY" -> com.checkinassistant.reminder.PointRules.formatActiveDays(point.activeDays)
                "ONCE" -> "仅一次 · ${point.onceDate.orEmpty()}"
                else -> "每天"
            }
            val hasWindow = point.activeStartMinute != null && point.activeEndMinute != null
            val window = if (hasWindow) {
                "${formatTime(point.activeStartMinute!!)}-${formatTime(point.activeEndMinute!!)}"
            } else null
            schedule.text = listOfNotNull(repeat, window).joinToString(" · ")

            // TintedIcon：停用时用 slate 静音色，否则用提醒点自身的颜色做浅底+同色图标。
            val color = PointColors.forPoint(point)
            val glyphColor = if (isOff) Ui.Tint.SLATE.fg else color
            tintIcon.background = Ui.circle(if (isOff) Ui.Tint.SLATE.bg else Ui.paleOf(color))
            (tintIcon.getChildAt(0) as? android.widget.ImageView)?.setColorFilter(glyphColor)

            toggle.setChecked(point.enabled, animate = false)
            toggle.isEnabled = !completed
            toggle.alpha = if (completed) 0.6f else 1f
            toggle.onCheckedChange = { checked -> current?.let { onToggle(it, checked) } }

            // 「已到达，未确认」= 一次性的点已触发但用户没有确认；否则就是「已完成」。
            val unacknowledged = completed &&
                latestStatusByPoint[point.id] == com.checkinassistant.data.RecordStatus.IGNORED.name
            badge.text = if (unacknowledged) "已到达，未确认" else "已完成"
            badge.visibility = if (completed) View.VISIBLE else View.GONE

            // border-l-4 border-l-primary（启用时）；停用整卡 opacity-70。
            itemView.background = AccentCardDrawable(
                Color.WHITE,
                Ui.dpf(context, 22f),
                if (point.enabled) Ui.PRIMARY else null,
                Ui.dpf(context, 4f)
            )
            name.setTextColor(if (isOff) Ui.TEXT_SUB else Ui.TEXT_MAIN)
            name.typeface = Ui.SEMIBOLD // 原型停用时仅变灰，字重仍为 font-semibold
            itemView.alpha = if (isOff) 0.7f else 1f
        }

        private fun formatTime(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
    }
}
