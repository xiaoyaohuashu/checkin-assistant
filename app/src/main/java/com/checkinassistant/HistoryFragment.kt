package com.checkinassistant

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.checkinassistant.data.AppDatabase
import com.checkinassistant.data.CheckInPoint
import com.checkinassistant.data.CheckInRecord
import com.checkinassistant.data.PointColors
import com.checkinassistant.data.PointEnabledEvent
import com.checkinassistant.data.PointSkipEvent
import com.checkinassistant.data.RecordStatus
import com.checkinassistant.reminder.ReminderEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v8: 历史页 —— 与 Web 原型 components/app/history-page.tsx 1:1 对齐。
 *
 * 结构：PageHeader（历史记录 / 本月）→ 月历卡片 → 打卡统计卡片 → 两张操作卡。
 * （v9 移除了「当日打卡记录」模块，统计卡之后直接进入操作卡。）
 * 顶部插画在历史/设置页与内容同宽（原型容器带 px-4），仅首页整幅铺满。
 */
class HistoryFragment : Fragment() {

    private val dao by lazy { AppDatabase.get(requireContext()).recordDao() }
    private val pointDao by lazy { AppDatabase.get(requireContext()).checkInPointDao() }
    private val eventDao by lazy { AppDatabase.get(requireContext()).pointEnabledEventDao() }
    private val skipEventDao by lazy { AppDatabase.get(requireContext()).pointSkipEventDao() }
    private var job: Job? = null
    private val calendar = Calendar.getInstance()
    private lateinit var headerRow: LinearLayout
    private lateinit var monthTitle: TextView
    private lateinit var calendarRows: LinearLayout
    private lateinit var statsContainer: LinearLayout
    private lateinit var notesContainer: LinearLayout
    private var recordsByDay: Map<Int, List<CheckInRecord>> = emptyMap()

    /** v7: 当前选中的日期（对应原型 selected），驱动日历上的日期高亮。 */
    private var selectedDay: Int = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)

    /** v7: 最新一次统计快照，供点击日期时重算当日记录。 */
    private var lastStats: List<PointStat> = emptyList()

    /** 日历单元格边长（7 列 + 6 个 6dp 间隙，与原型 grid-cols-7 gap-1.5 等宽等高）。 */
    private var cellWidth = 0

    /** v7: 单个提醒点的月度统计（原型 DayStat 等价物）。 */
    private data class PointStat(
        val name: String,
        val color: Int,
        val arrivedDays: Int,
        val missedDays: Int,
        val disabled: Boolean
    )

    private var colorByPointId: Map<Long, Int> = emptyMap()

    /** Keeps a day cell readable when many points trigger on the same day. */
    private val maxDotsPerDay = 3

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        createContent()

    override fun onDestroyView() { job?.cancel(); super.onDestroyView() }

    /**
     * v9: 进入页面即渲染完整月历（月份标题 + 全部日期格子）。
     *
     * 之前只在点击左右箭头 / 月份胶囊时才调用 observeMonth()，所以首次进入时月历卡
     * 是空的，看起来像「折叠」状态，必须点一下才展开。放在 onResume 而不是
     * onCreateView，是因为 onCreateView 阶段 viewLifecycleOwner 尚未就绪
     * （observeMonth 里的 lifecycleScope 取不到）。
     */
    override fun onResume() {
        super.onResume()
        observeMonth()
    }

    private fun createContent(): View {
        val context = requireContext()
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.PAGE_BG)
        }

        // ── 顶部插画 Header（与首页同款；历史/设置页与内容同宽）──
        // v12: 右上角「本月」胶囊已移除，且不用其他文字替代——留出插画右侧让车头露出。
        //       月份切换仍由日历卡片上的左右箭头承担（见 buildCalendarCard）。
        val header = Ui.pageHeader(context, Ui.HEADER_ICON_HISTORY, "历史记录", "查看你的打卡记录与相关信息")
        headerRow = header.row
        // v10: 顶部插画左右铺满屏幕（与首页一致），作为背景层，标题等前景内容叠加其上
        root.addView(header.root, LinearLayout.LayoutParams(-1, -2))

        // ── 内容列（原型 px-4 gap-4 pb-6，header 与卡片之间 16dp）──
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        // 日历单元格边长：屏宽 − page px-4 ×2 − 卡片 p-4 ×2 − 6 个 6dp 间隙
        // （原型 grid-cols-7 gap-1.5：gap 只存在于列之间，最左/最右没有外边距，
        //   所以单元格只在「内侧」留 3dp margin，见 cellParams）
        val gridWidth = resources.displayMetrics.widthPixels - dp(16) * 4
        cellWidth = (gridWidth - dp(6) * 6) / 7

        content.addView(buildCalendarCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        content.addView(buildStatsCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        // v9: 统计卡之后直接是操作卡（原「当日打卡记录」模块已删除）
        content.addView(buildActionCards(), LinearLayout.LayoutParams(-1, -2))

        root.addView(content)
        scroll.addView(root)

        // 顶部插画避让状态栏（原型 pt-[calc(env(safe-area-inset-top)+1.25rem)]）
        headerRow.setPadding(dp(20), maxOf(Ui.statusBarHeight(context), dp(24)) + dp(12), dp(20), dp(12))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            headerRow.setPadding(dp(20), statusBar + dp(12), dp(20), dp(12))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        Ui.trackStatusBarIcons(requireActivity(), scroll, header.root)
        return scroll
    }

    // ── 月历卡片（原型 rounded-3xl p-4）────────────────────────

    private fun buildCalendarCard(): View {
        val context = requireContext()
        val card = Ui.card(context, radiusDp = 22, paddingDp = 16)

        val navRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        navRow.addView(
            Ui.icon(context, R.drawable.ic_chevron_left, 20, Ui.CAL_NAV).apply {
                isClickable = true
                setOnClickListener { calendar.add(Calendar.MONTH, -1); observeMonth() }
            },
            LinearLayout.LayoutParams(dp(32), dp(32))
        )
        monthTitle = Ui.text(context, 18f, Ui.TEXT_MAIN, Ui.BOLD).apply {
            gravity = Gravity.CENTER
            // v12: 「跳到指定月份」原本挂在 header 的「本月」胶囊上；胶囊按要求删除后，
            //       入口移到这里的月份标题（不新增文字，月份切换/日历逻辑未改动）。
            isClickable = true
            setOnClickListener { pickMonth() }
        }
        navRow.addView(monthTitle, LinearLayout.LayoutParams(0, dp(32), 1f))
        navRow.addView(
            Ui.icon(context, R.drawable.ic_chevron_right, 20, Ui.CAL_NAV).apply {
                isClickable = true
                setOnClickListener { calendar.add(Calendar.MONTH, 1); observeMonth() }
            },
            LinearLayout.LayoutParams(dp(32), dp(32))
        )
        card.addView(navRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 星期行（grid-cols-7 gap-1.5 py-1 text-xs font-medium text-muted-foreground）
        val weekdays = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        arrayOf("日", "一", "二", "三", "四", "五", "六").forEachIndexed { index, day ->
            weekdays.addView(
                Ui.text(context, 12f, Ui.TEXT_SUB, Ui.SEMIBOLD, day).apply { gravity = Gravity.CENTER },
                cellParams(index, dp(24))
            )
        }
        card.addView(weekdays, LinearLayout.LayoutParams(-1, -2))

        calendarRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        card.addView(calendarRows, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    // ── 打卡统计卡片（原型 rounded-3xl p-4）─────────────────────

    /**
     * 统计行比原型紧凑（原型 size-11 图标 + py-2.5 = 64dp 行高）：
     * v9 压到 50dp（图标 36 / 上下留白 7 / 行距 8），
     * v10 再压到 44dp（图标 32 / 上下留白 6 / 行距 6，标题下间距 10→8）。
     * 卡片内边距保持 16dp，以便与上方月历卡的标题左边缘对齐；文字字号一律不变。
     */
    private fun buildStatsCard(): View {
        val context = requireContext()
        val card = Ui.card(context, radiusDp = 22, paddingDp = 16)
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        titleRow.addView(
            Ui.text(context, 16f, Ui.TEXT_MAIN, Ui.BOLD, "打卡统计").tight(),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        titleRow.addView(Ui.text(context, 12f, Ui.TEXT_SUB, Typeface.DEFAULT, "本月至今"))
        card.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        statsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        card.addView(statsContainer, LinearLayout.LayoutParams(-1, -2))

        // 统计口径说明（禁用/跳过日不计入分母）——原型无此项，仅在确实有说明时以小字补在卡片底部。
        notesContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        card.addView(notesContainer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return card
    }

    // ── 操作卡片（原型 grid-cols-2 gap-3）──────────────────────

    private fun buildActionCards(): View {
        val context = requireContext()
        val grid = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        grid.addView(
            actionCard(R.drawable.ic_play, Ui.Tint.BLUE, "手动模拟触发", "模拟当前位置触发提醒") { chooseSimulationPoint() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
        )
        grid.addView(
            actionCard(R.drawable.ic_file_text, Ui.Tint.PURPLE, "查看全部记录", "浏览历史打卡记录") { showAllRecords() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) }
        )
        return grid
    }

    private fun actionCard(
        iconRes: Int,
        tint: Ui.Tint,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): LinearLayout {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Color.WHITE, 22, context)
            // v10: 上下留白 16 → 12，图标圆 44 → 36，卡片整体变矮（约 124 → 104dp）
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = Ui.dpf(context, 1f)
            Ui.roundOutline(this, Ui.dpf(context, 22f))
            isClickable = true
            setOnClickListener { onClick() }
            val iconCircle = FrameLayout(context).apply { background = Ui.circle(tint.bg) }
            iconCircle.addView(
                Ui.icon(context, iconRes, 18, tint.fg),
                FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            )
            addView(iconCircle, LinearLayout.LayoutParams(dp(36), dp(36)).apply { bottomMargin = dp(6) })
            addView(Ui.text(context, 14f, Ui.TEXT_MAIN, Ui.BOLD, title))
            addView(
                Ui.text(context, 12f, Ui.TEXT_SUB, Typeface.DEFAULT, subtitle).leading(1.375f), // leading-snug
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) }
            )
        }
    }

    /** v6: 弹出提醒点选择，模拟触发（入口从首页迁移至此）。 */
    private fun chooseSimulationPoint() {
        viewLifecycleOwner.lifecycleScope.launch {
            val points = pointDao.enabledPoints()
            if (points.isEmpty()) {
                android.widget.Toast.makeText(requireActivity(), "暂无启用中的提醒点", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(requireActivity()).setTitle("选择要模拟的提醒点")
                .setItems(points.map { it.name }.toTypedArray()) { _, index ->
                    viewLifecycleOwner.lifecycleScope.launch { ReminderEngine.simulate(requireActivity(), points[index].id) }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** v6: 查看全部记录。 */
    private fun showAllRecords() {
        AlertDialog.Builder(requireContext())
            .setTitle("全部记录")
            .setAdapter(
                android.widget.ArrayAdapter(
                    requireContext(), android.R.layout.simple_list_item_1, mutableListOf<String>()
                ).also { adapter ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val records = dao.getAllOnce()
                        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        adapter.clear()
                        adapter.addAll(records.take(100).map { "${fmt.format(Date(it.triggeredAt))}  ${it.pointName}" })
                        adapter.notifyDataSetChanged()
                    }
                }
            ) { _, _ -> }
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun observeMonth() {
        if (view == null) return // onResume 之外的调用路径上视图可能已销毁
        monthTitle.text = "%tY年%<tm月".format(calendar)
        val start = (calendar.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        job?.cancel()
        job = viewLifecycleOwner.lifecycleScope.launch {
            combine(
                dao.observeMonthByStatus(start.timeInMillis, end.timeInMillis, RecordStatus.COMPLETED.name),
                pointDao.observeAll(),
                eventDao.observeSince(start.timeInMillis),
                skipEventDao.observeSince(start.timeInMillis)
            ) { records, points, events, skips -> StatisticsData(records, points, events, skips) }
                .collectLatest { data ->
                    recordsByDay = data.records.groupBy { it.day }
                    // Colours come from every point, not just enabled ones: history can outlive a point.
                    colorByPointId = data.points.associate { it.id to PointColors.forPoint(it) }
                    renderCalendar(start)
                    // v7: 打卡统计卡（每点 已到达/未到达 胶囊行，同原型「打卡统计」）
                    renderStats(data.points, data.records, data.events, data.skips, start)
                }
        }
    }

    /** Bundle for the four combine flows (combine supports up to five directly). */
    private data class StatisticsData(
        val records: List<CheckInRecord>,
        val points: List<CheckInPoint>,
        val events: List<PointEnabledEvent>,
        val skips: List<PointSkipEvent>
    )

    /** Days this point was explicitly skipped via "今天跳过" (skip events carry the local date). */
    private suspend fun skippedDaysInMonth(
        point: CheckInPoint,
        skips: List<PointSkipEvent>,
        monthStart: Calendar,
        countedDays: Int
    ): Int {
        if (countedDays <= 0) return 0
        val month = "%04d-%02d".format(monthStart.get(Calendar.YEAR), monthStart.get(Calendar.MONTH) + 1)
        return skips.asSequence()
            .filter { it.reminderPointId == point.id && it.skipDate.startsWith(month) }
            .map { it.skipDate }
            .distinct()
            .count()
    }

    /** Days whose start found the point disabled; toggles inside a day apply from that moment on. */
    private suspend fun disabledDaysInMonth(
        point: CheckInPoint,
        events: List<PointEnabledEvent>,
        monthStart: Calendar,
        countedDays: Int
    ): Int {
        if (countedDays <= 0) return 0
        val monthStartMillis = monthStart.timeInMillis
        // No recorded change means the point was never toggled, i.e. always enabled.
        var enabled = eventDao.latestBefore(point.id, monthStartMillis)?.enabled ?: true
        val changes = events.filter { it.pointId == point.id }.sortedBy { it.atMillis }
        var nextChange = 0
        var disabledDays = 0
        for (day in 1..countedDays) {
            val dayStart = (monthStart.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, day) }.timeInMillis
            while (nextChange < changes.size && changes[nextChange].atMillis < dayStart) {
                enabled = changes[nextChange].enabled
                nextChange++
            }
            if (!enabled) disabledDays++
        }
        return disabledDays
    }

    /** Current months count through today; completed past months count every calendar day. */
    private fun countedDaysInMonth(monthStart: Calendar): Int {
        val now = Calendar.getInstance()
        val monthComparison = compareValues(
            monthStart.get(Calendar.YEAR) * 12 + monthStart.get(Calendar.MONTH),
            now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH)
        )
        return when {
            monthComparison < 0 -> monthStart.getActualMaximum(Calendar.DAY_OF_MONTH)
            monthComparison == 0 -> now.get(Calendar.DAY_OF_MONTH)
            else -> 0
        }
    }

    // ── 渲染 ─────────────────────────────────────────────────

    private fun renderCalendar(monthStart: Calendar) {
        calendarRows.removeAllViews()
        // v11 修复「点击日期后星期错位」：
        // firstOffset 必须是**所选年月 1 号**的星期，不能用传进来的 Calendar 当前的
        // DAY_OF_MONTH 去取星期——点击某天时传的是 calendar 字段（其日期是今天，比如
        // 24 号星期四），拿 24 号的星期当偏移会让整月右移：2026-09-01（星期二）就被
        // 画到了「四」那一列。用 java.time 按真实年月日计算。
        //
        // DayOfWeek：MONDAY=1 … SUNDAY=7；日历首列是周日，故 %7 映射为 周日=0…周六=6。
        val yearMonth = java.time.YearMonth.of(
            monthStart.get(Calendar.YEAR),
            monthStart.get(Calendar.MONTH) + 1
        )
        val firstOffset = yearMonth.atDay(1).dayOfWeek.value % 7
        val daysInMonth = yearMonth.lengthOfMonth()
        // 只画需要的行数（原型 grid 按内容自适应，不补齐 6 行）
        val rows = (firstOffset + daysInMonth + 6) / 7
        for (row in 0 until rows) {
            val rowView = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until 7) {
                val index = row * 7 + col
                val day = index - firstOffset + 1
                val records = if (day in 1..daysInMonth) recordsByDay[day].orEmpty() else emptyList()
                rowView.addView(
                    dayCell(if (day in 1..daysInMonth) day else null, records),
                    cellParams(col, cellWidth)
                )
            }
            calendarRows.addView(
                rowView,
                // grid gap-1.5：星期行与第一行之间、以及各日行之间都是 6dp
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            )
        }
    }

    /**
     * 单元格布局参数：gap-1.5 只在列与列之间生效——最左列不留左边距、最右列不留右边距，
     * 这样 7 列 + 6×6dp 间隙正好等于卡片内宽（否则最后一列会被挤出卡片）。
     */
    private fun cellParams(col: Int, height: Int) = LinearLayout.LayoutParams(cellWidth, height).apply {
        leftMargin = if (col == 0) 0 else dp(3)
        rightMargin = if (col == 6) 0 else dp(3)
    }

    private fun dayCell(day: Int?, records: List<CheckInRecord>): View {
        val context = requireContext()
        // 原型里本月之外的格子是空白（不是灰块），只占位保持 7 列对齐。
        if (day == null) return View(context)
        val isSelected = day == selectedDay
        val cell = FrameLayout(context).apply {
            background = Ui.rounded(if (isSelected) Ui.CAL_SELECTED_BG else Ui.CAL_DAY_BG, 18, context)
        }
        cell.addView(
            Ui.text(
                context, 14f,
                if (isSelected) Ui.PRIMARY else Ui.CAL_DAY_FG,
                Ui.SEMIBOLD, day.toString()
            ).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -1)
        )
        cell.addView(
            dotRow(records),
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(6) }
        )
        // v7: 点击任意日期 → 选中并高亮（原型 setSelected）；仅重绘日历，不改变页面高度
        cell.setOnClickListener {
            selectedDay = day
            renderCalendar(calendar.clone() as Calendar)
        }
        return cell
    }

    /** One dot per point that triggered that day, so overlapping ranges stay visible. */
    private fun dotRow(records: List<CheckInRecord>): View {
        val context = requireContext()
        val pointIds = records.map { it.pointId }.distinct()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = if (pointIds.isEmpty()) View.INVISIBLE else View.VISIBLE
            pointIds.take(maxDotsPerDay).forEach { pointId ->
                addView(
                    View(context).apply { background = Ui.circle(colorByPointId[pointId] ?: Ui.PRIMARY) },
                    LinearLayout.LayoutParams(dp(4), dp(4)).apply { leftMargin = dp(1); rightMargin = dp(1) }
                )
            }
        }
    }

    /**
     * v7: 打卡统计卡内容——每个启用的提醒点一行
     * 「TintedIcon + 名称 + 已到达 N 天 + 未到达 N 天」，末尾追加原型中的
     * 「已禁用的提醒点」汇总行（圆形数字 + 已禁用胶囊）。
     */
    private suspend fun renderStats(
        points: List<CheckInPoint>,
        records: List<CheckInRecord>,
        events: List<PointEnabledEvent>,
        skips: List<PointSkipEvent>,
        monthStart: Calendar
    ) {
        val context = requireContext()
        val counted = countedDaysInMonth(monthStart)
        val stats = mutableListOf<PointStat>()
        var disabledCount = 0
        points.sortedBy { it.id }.forEach { point ->
            if (!point.enabled) {
                disabledCount++
                return@forEach
            }
            val arrived = records.asSequence()
                .filter { it.pointId == point.id && it.day <= counted }
                .map { it.day }
                .distinct()
                .count()
            val expected = (counted - disabledDaysInMonth(point, events, monthStart, counted) -
                skippedDaysInMonth(point, skips, monthStart, counted)).coerceAtLeast(0)
            stats.add(
                PointStat(
                    point.name, PointColors.forPoint(point),
                    arrived, (expected - arrived).coerceAtLeast(0), disabled = false
                )
            )
        }
        lastStats = stats

        statsContainer.removeAllViews()
        if (stats.isEmpty()) {
            statsContainer.addView(
                Ui.text(context, 13f, Ui.TEXT_SUB, Typeface.DEFAULT, "暂无提醒点"),
                LinearLayout.LayoutParams(-2, -2)
            )
        }
        stats.forEach { s ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(Ui.ROW_BG, 18, context)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            row.addView(
                Ui.tintedIconOf(context, R.drawable.ic_map_pin, s.color, boxDp = 32, glyphDp = 16, rightMarginDp = 10)
            )
            row.addView(
                Ui.text(context, 14f, Ui.TEXT_MAIN, Ui.SEMIBOLD, s.name).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(
                Ui.pill(context, "已到达 ${s.arrivedDays} 天", Ui.GREEN_OK_BG, Ui.GREEN_OK),
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) }
            )
            row.addView(
                Ui.pill(context, "未到达 ${s.missedDays} 天", Ui.CHIP_BG, Ui.CHIP_FG),
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) }
            )
            statsContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }

        // 「已禁用的提醒点」汇总行（同原型：圆形数字 + 灰字名称 + 已禁用胶囊）
        val disabledRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.ROW_BG, 18, context)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val countCircle = FrameLayout(context).apply { background = Ui.circle(Ui.CHIP_BG) }
        countCircle.addView(
            Ui.text(context, 13f, Ui.CHIP_FG, Ui.BOLD, disabledCount.toString()).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -1)
        )
        disabledRow.addView(
            countCircle,
            LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(10) }
        )
        disabledRow.addView(
            Ui.text(context, 14f, Ui.TEXT_SUB, Ui.SEMIBOLD, "已禁用的提醒点"),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        disabledRow.addView(Ui.pill(context, "已禁用", Ui.CHIP_BG, Ui.CHIP_FG))
        statsContainer.addView(disabledRow, LinearLayout.LayoutParams(-1, -2))

        renderNotes(points, events, skips, monthStart, counted)
    }

    /**
     * 统计口径说明：把「已禁用 N 天、已跳过 N 天（不计入）」这类解释性文字
     * 以小字附在统计卡底部；没有可说明的内容时整块隐藏。
     */
    private suspend fun renderNotes(
        points: List<CheckInPoint>,
        events: List<PointEnabledEvent>,
        skips: List<PointSkipEvent>,
        monthStart: Calendar,
        counted: Int
    ) {
        val context = requireContext()
        notesContainer.removeAllViews()
        if (points.isEmpty() || counted <= 0) {
            notesContainer.visibility = View.GONE
            return
        }
        val lines = mutableListOf<String>()
        points.sortedBy { it.id }.forEach { point ->
            if (!point.enabled) return@forEach
            val disabledDays = disabledDaysInMonth(point, events, monthStart, counted)
            val skippedDays = skippedDaysInMonth(point, skips, monthStart, counted)
            val notes = buildList {
                if (disabledDays > 0) add("已禁用 $disabledDays 天")
                if (skippedDays > 0) add("已跳过 $skippedDays 天")
            }
            if (notes.isNotEmpty()) lines.add("${point.name}：${notes.joinToString("、")}（不计入）")
        }
        if (lines.isEmpty()) {
            notesContainer.visibility = View.GONE
            return
        }
        notesContainer.visibility = View.VISIBLE
        lines.forEach { line ->
            notesContainer.addView(
                Ui.text(context, 12f, Ui.TEXT_SUB, Typeface.DEFAULT, line),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) }
            )
        }
    }

    private fun pickMonth() = DatePickerDialog(
        requireContext(),
        { _, year, month, _ -> calendar.set(year, month, 1); observeMonth() },
        calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), 1
    ).show()

    private fun dp(value: Int) = (value * requireContext().resources.displayMetrics.density).toInt()
}
