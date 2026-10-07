package com.checkinassistant

import android.app.AlertDialog
import android.app.ActivityManager
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.checkinassistant.data.AppDatabase
import com.checkinassistant.data.CheckInPoint
import com.checkinassistant.data.DiagnosticRecordLogger
import com.checkinassistant.data.PointColors
import com.checkinassistant.data.PointEnabledEvent
import com.checkinassistant.data.PointSkipEvent
import com.checkinassistant.data.RecordStatus
import com.checkinassistant.geofence.GeofenceMonitoringService
import com.checkinassistant.reminder.TimeWheelDialog
import com.checkinassistant.reminder.ReminderEngine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val dao by lazy { AppDatabase.get(this).checkInPointDao() }
    private lateinit var adapter: PointAdapter
    private lateinit var pointsList: RecyclerView
    private lateinit var monitoringText: TextView
    private lateinit var monitoringSubtitle: TextView
    private lateinit var monitoringButton: TextView
    private lateinit var shieldIcon: ImageView
    private lateinit var shieldBox: FrameLayout
    private lateinit var monitoringDot: View
    private lateinit var listCount: TextView
    private lateinit var headerRow: LinearLayout
    private lateinit var navBar: BottomNav.Bar
    private var selectedLatitude = 3.1390 // Kuala Lumpur; used until a map selection is made.
    private var selectedLongitude = 101.6869
    private var pendingPoint: CheckInPoint? = null
    private var pendingName = ""
    private var pendingRadius = ""
    private var pendingActiveStartMinute: Int? = null
    private var pendingActiveEndMinute: Int? = null
    private var pendingDailyTriggerLimit: Int? = null
    private var pendingColor: Int? = null
    private var pendingRepeatMode = "DAILY"
    private var pendingActiveDays = 127
    private var pendingOnceDate: String? = null
    private var currentPoints: List<CheckInPoint> = emptyList()
    /** pointId → status of its latest check-in record (badge: 已完成 vs 已到达，未确认). */
    private var latestRecordStatus: Map<Long, String> = emptyMap()

    private val ringtonePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (result.resultCode == RESULT_OK && uri != null) {
            getSharedPreferences("reminder_settings", MODE_PRIVATE).edit().putString("ringtone_uri", uri.toString()).apply()
            Toast.makeText(this, "提醒铃声已保存", Toast.LENGTH_SHORT).show()
        }
    }

    private val mapPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            selectedLatitude = result.data?.getDoubleExtra(MapPickerActivity.EXTRA_LATITUDE, selectedLatitude) ?: selectedLatitude
            selectedLongitude = result.data?.getDoubleExtra(MapPickerActivity.EXTRA_LONGITUDE, selectedLongitude) ?: selectedLongitude
            // 选点页内调整过的触发范围写回待保存值（仍走现有 radiusMeters 字段保存）
            result.data?.getIntExtra(MapPickerActivity.EXTRA_RADIUS, -1)?.takeIf { it in 100..500 }?.let {
                pendingRadius = it.toString()
            }
            showEditor(pendingPoint, keepSelectedCoordinates = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "别错过站"
        // v8：沉浸式 —— 顶部插画延伸到状态栏下方（原型 header 的 safe-area 内边距写法），
        // 状态栏图标用浅色压在蓝色渐变遮罩上，导航栏保持白底深色图标。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.WHITE
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
        setContentView(createContent())
        adapter = PointAdapter(::showEditor, ::confirmDelete, ::togglePoint, ::skipPointToday, ::restorePoint, ::acknowledgePoint)
        pointsList.adapter = adapter
        // 三页常驻（offscreenPageLimit = 2），这里先把两个 Fragment 装好：滑动过程中
        // 目标页已经是有内容的，不会先看到一帧空白再补上。
        ensurePage(1)
        ensurePage(2)
        lifecycleScope.launch {
            // v5+: latest record status per point drives the "已到达，未确认" badge.
            AppDatabase.get(this@MainActivity).recordDao().observeAll().collectLatest { allRecords ->
                latestRecordStatus = allRecords.groupBy { it.pointId }
                    .mapValues { (_, list) -> list.first().status }
                // v12 修复「已禁用/已完成的提醒点没沉底」：这里原先提交的是**未排序**的
                // currentPoints，而记录流与提醒点流是两个独立收集器，记录流一发射就会用
                // 数据库原始顺序覆盖掉另一个收集器排好的列表（首帧、以及每次打卡记录变化
                // 时都会发生）。排序必须在这两条提交路径上都生效。
                adapter.submitList(sortPoints(currentPoints), latestRecordStatus)
            }
        }
        lifecycleScope.launch {
            dao.observeAll().collectLatest {
                currentPoints = it
                adapter.submitList(sortPoints(it), latestRecordStatus)
                listCount.text = "${it.size} 个"
            }
        }
        if (!getSharedPreferences("onboarding", MODE_PRIVATE).getBoolean("shown", false)) {
            getSharedPreferences("onboarding", MODE_PRIVATE).edit().putBoolean("shown", true).apply()
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    /**
     * v6: 启用中的提醒点排前；已完成/已停用（ONCE COMPLETED 或 enabled=false）沉底。
     *
     * v12: tiebreaker 由 id 升序改为**降序**，与 [CheckInPointDao.observeAll] 的
     * `ORDER BY id DESC` 保持一致。原因是第 105 行的排序 bug 让列表多数时候实际按数据库
     * 原始顺序（新→旧）渲染，若这里继续用升序，修好分组后会连带把「同一组内部的先后」
     * 整个翻过来——用户没要求改这个。现在只在原顺序之上叠加「启用优先」这一条。
     */
    private fun sortPoints(points: List<CheckInPoint>): List<CheckInPoint> =
        points.sortedWith(
            compareByDescending<CheckInPoint> { it.enabled && !(it.repeatMode == "ONCE" && it.onceStatus == "COMPLETED") }
                .thenByDescending { it.id }
        )

    override fun onResume() {
        super.onResume()
        if (::monitoringText.isInitialized) refreshMonitoringStatus()
        // 配置变更/进程恢复后 ViewPager2 会把自己恢复到原来的页码，而底部导航是按 0 新建的，
        // 这里补一次对齐，保证「当前页 ↔ 导航选中态」始终一致。
        if (::pager.isInitialized && pager.currentItem != currentTab) onPageChanged(pager.currentItem)
    }

    private fun createContent(): View {
        // v8 视觉移植：以 /root/资料/ui Web 原型 reminders-page.tsx 为唯一视觉基准。
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.PAGE_BG) }
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── 顶部插画 Header（三页共用的 PageHeader：hero + 渐变 + 图标框 + 标题）──
        // v12: 右上角的日期胶囊已移除，且不再用其他文字替代——留出插画右侧，
        //       让轻轨车头完整露出。
        val header = Ui.pageHeader(
            this, Ui.HEADER_ICON_MAP_PIN,
            "别错过站", "到达指定地点时提醒你确认"
        )
        headerRow = header.row
        val heroHeader = header.root
        root.addView(heroHeader, LinearLayout.LayoutParams(-1, -2))

        // ── 内容列（原型 px-4 pt-4 gap-4 pb-6）──
        // v10: 顶部插画变矮后，内容起始留白 16 → 12，让「添加提醒点」等整体上移。
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        // ── 添加提醒点：primary 大按钮（rounded-2xl py-4 shadow-lg，内含白色 25% 圆 + Plus 16/3）──
        val addButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Ui.rounded(Ui.PRIMARY, 18, this@MainActivity)
            isClickable = true
            setOnClickListener { showEditor(null) }
            elevation = Ui.dpf(this@MainActivity, 4f)
            Ui.roundOutline(this, Ui.dpf(this@MainActivity, 18f))
            val plusCircle = FrameLayout(this@MainActivity).apply {
                background = Ui.circle(Color.argb(64, 255, 255, 255))
            }
            plusCircle.addView(
                Ui.icon(this@MainActivity, R.drawable.ic_plus_bold, 16, Color.WHITE),
                FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
            )
            addView(plusCircle, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(8) })
            addView(Ui.text(this@MainActivity, 16f, Color.WHITE, Ui.SEMIBOLD, "添加提醒点"))
        }
        content.addView(addButton, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(16) })

        // ── 后台监测状态卡片（rounded-3xl p-5：状态点 + 文案 + 盾牌圆形，同原型）──
        val monitorCard = Ui.card(this)
        val monitorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val monitorLeft = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        monitoringDot = View(this).apply { background = Ui.circle(Ui.PRIMARY) }
        monitorLeft.addView(
            monitoringDot,
            LinearLayout.LayoutParams(dp(10), dp(10)).apply { topMargin = dp(6); rightMargin = dp(10) }
        )
        val monitorTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        monitoringText = Ui.text(this, 18f, Ui.TEXT_MAIN, Ui.BOLD, "后台监测未启动").tight().leading(1.25f)
        monitoringSubtitle = Ui.text(this, 13f, Ui.TEXT_SUB, Typeface.DEFAULT, "开启后，到达提醒点范围会自动提醒你").leading(1.625f)
        monitorTexts.addView(monitoringText)
        monitorTexts.addView(monitoringSubtitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        monitorLeft.addView(monitorTexts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        monitorRow.addView(
            monitorLeft,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) }
        )
        // 盾牌状态图标：size-11 圆底 + 24px 图标（已启动 ✓ 绿 / 未启动 ✕ 灰）
        shieldIcon = Ui.icon(this, R.drawable.ic_shield_x, 24, Ui.SHIELD_OFF_FG)
        shieldBox = FrameLayout(this).apply { background = Ui.circle(Ui.SHIELD_OFF_BG) }
        shieldBox.addView(shieldIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        monitorRow.addView(shieldBox, LinearLayout.LayoutParams(dp(44), dp(44)))
        monitorCard.addView(monitorRow)

        monitoringButton = Ui.text(this, 15f, Color.WHITE, Ui.SEMIBOLD, "启动后台监测").apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(50) // py-3.5
            isClickable = true
            background = Ui.rounded(Ui.PRIMARY, 18, this@MainActivity)
            setOnClickListener {
                if (monitoringRunning()) GeofenceMonitoringService.stop(this@MainActivity)
                else GeofenceMonitoringService.start(this@MainActivity)
                refreshMonitoringStatus()
            }
        }
        monitorCard.addView(
            monitoringButton,
            LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        )
        content.addView(monitorCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        // ── 我的提醒点（text-xl 标题 + 「N 个 ›」计数，同原型 section）──
        val listTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        listTitleRow.addView(
            Ui.text(this, 20f, Ui.TEXT_MAIN, Ui.BOLD, "我的提醒点").tight(),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        // v12: 这一行只是分组标题，点击不会进入任何页面/展开任何内容，因此去掉右侧小箭头。
        listCount = Ui.text(this, 14f, Ui.TEXT_SUB, Ui.SEMIBOLD)
        listTitleRow.addView(listCount)
        content.addView(listTitleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        pointsList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        content.addView(pointsList, LinearLayout.LayoutParams(-1, -2))
        root.addView(content)

        scroll.addView(root)

        // ── 底部宣传图：紧贴底部导航栏上方，左右铺满屏幕（原型仅首页显示）──
        // v10: 换成 banner_bottom.png，以底层背景/宣传背景的形式整幅显示。
        //   · 宽度 = 屏幕宽（外层 LayoutParams 为 -1），无左右边距
        //   · 高度由 AspectFrameLayout 按图片原始比例（4608×864）算出，
        //     所以比例完整保留、不横向拉伸变形
        //   · 文案「不再错过，每一个重要的地点」已经印在图上，不再叠加 TextView，
        //     也不再叠白色渐变遮罩，让整幅宣传图原样呈现
        val footer = AspectFrameLayout(this, Ui.BANNER_ASPECT).apply {
            background = Ui.imageLayers(this@MainActivity, Ui.BANNER_IMAGE, Ui.noScrim())
        }

        // ── 三个一级页面改由 ViewPager2 承载：左右滑动与底部导航共用同一套页面状态 ──
        // 第 0 页 = 首页，仍是 Activity 自建视图（scroll + footer）原样放进一个纵向容器里，
        //   没有改造成 Fragment —— showEditor / 定位选择 / 列表适配器 / 各 ActivityResult
        //   回调都还挂在 Activity 上，一行未动。
        // 第 1 / 2 页 = 两个空容器，由 FragmentManager 把 HistoryFragment / SettingsFragment 放进去。
        //
        // 为什么不用 FragmentStateAdapter：首页不是 Fragment，为它单独建一个 Fragment 要把
        // showEditor（连同十几个 pending* 字段）和两个 ActivityResult 启动器一起搬过去，
        // 属于要求里明确不要的重构。用普通 RecyclerView.Adapter 承载三个**视图**即可，
        // 三个页面的内部实现完全不动，滑动与切页都由 ViewPager2 原生处理。
        val homePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        homePage.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        homePage.addView(footer, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))

        historyPage = pageSlot(R.id.page_history)
        settingsPage = pageSlot(R.id.page_settings)

        // ── 关键：两个 Fragment 容器必须先进入 Activity 的视图树，再让 FragmentManager 建视图 ──
        // 崩溃根因（v21 真机启动必崩）：
        //   FragmentManager 创建 Fragment 视图时会用宿主容器（= android.R.id.content）
        //   去 onFindViewById(mContainerId)。而 ViewPager2 的页面要等**第一次 layout**
        //   才被创建并挂到树上，那次 layout 已经晚于 onStart——FragmentActivity.onStart()
        //   里会同步 execPendingActions()（源码里就这一行），把 ensurePage() 排队的
        //   HistoryFragment 推进到 STARTED 并 createView()。于是
        //     IllegalArgumentException: No view found for id ... for fragment HistoryFragment
        //   启动、旋转、后台/进程恢复都会走这条路径（恢复时是 FragmentManager 自己重建，
        //   时机一样早）。顺带说明：上方的 ViewPager2 适配器当时还没 bind 过任何一页。
        //
        // 解法：把两个容器先挂在一个 0×0、GONE 的挂载点上。
        //   · GONE + 0×0 → 不参与任何布局，不影响首页
        //   · View.findViewById 不检查 visibility（ViewGroup.findViewTraversal 逐个子 View
        //     递归，没有任何 visibility 判断）→ onFindViewById 立刻能找到，Fragment 视图正常创建
        //   · 容器此时还没被 ViewPager2 接管，等它首次 layout 时再由适配器搬到对应页里
        // 这样 id 查找永远成功，不需要 catch 异常、也没有掩盖任何错误。
        val pageStaging = FrameLayout(this).apply { visibility = View.GONE }
        page.addView(pageStaging, LinearLayout.LayoutParams(0, 0))
        pageStaging.addView(historyPage)
        pageStaging.addView(settingsPage)

        pager = ViewPager2(this).apply {
            id = R.id.page_pager
            adapter = PagesAdapter(listOf(homePage, historyPage, settingsPage))
            // 三页常驻：切页不重建，首页滚动位置、日历选中日、列表状态都保留下来
            offscreenPageLimit = 2
            // 首/末页到边界后不再回弹/发光（ViewPager2 本身也不会滑出第 1、3 页之外）
            (getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                // 滑动与点底部导航最终都汇到这里，状态只有这一处出口
                override fun onPageSelected(position: Int) = onPageChanged(position)
            })
        }
        page.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))

        navBar = BottomNav.build(this, 0) { tab -> switchTab(tab) }
        page.addView(navBar, LinearLayout.LayoutParams(-1, -2))

        // 顶部插画避让状态栏（原型 pt-[calc(env(safe-area-inset-top)+1.25rem)]）；
        // 非沉浸式窗口下 insets.top 为 0，此时等价于原来的固定留白。
        // 首帧兜底：监听器生效前先按系统状态栏高度留白，避免插画被状态栏压住。
        headerRow.setPadding(dp(20), maxOf(Ui.statusBarHeight(this), dp(24)) + dp(12), dp(20), dp(12))
        ViewCompat.setOnApplyWindowInsetsListener(page) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerRow.setPadding(dp(20), bars.top + dp(12), dp(20), dp(12))
            navBar.setPadding(0, 0, 0, bars.bottom)
            page.setPadding(bars.left, 0, bars.right, 0) // 横屏挖孔/手势条侧边避让
            insets
        }
        ViewCompat.requestApplyInsets(page)
        // 滚过顶部插画后把状态栏图标翻成深色（沉浸式的必要配套）
        Ui.trackStatusBarIcons(this, scroll, heroHeader)
        return page
    }

    /** 后台监测服务是否在运行（胶囊/盾牌状态与按钮文案都基于它）。 */
    private fun monitoringRunning(): Boolean =
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == GeofenceMonitoringService::class.java.name }

    /**
     * v13: 一级页面改成 ViewPager2 承载。
     *
     * 「左右滑动」和「点底部导航」不再是两条路径：点导航 = `setCurrentItem`，
     * 滑动 = 用户直接拖，两者最终都触发 [onPageChanged]，底部导航选中态、当前页 Fragment
     * 的装载都只在那里更新一次，不会出现两边状态打架。
     */
    private lateinit var pager: ViewPager2
    private lateinit var historyPage: FrameLayout
    private lateinit var settingsPage: FrameLayout
    private var currentTab = 0

    /** 一级页面容器：背景与页面一致，id 从 ids.xml 取，保证配置变更后仍能恢复。 */
    private fun pageSlot(id: Int): FrameLayout = FrameLayout(this).apply {
        this.id = id
        setBackgroundColor(Ui.PAGE_BG)
    }

    /** 底部导航点击 → 平滑滑到对应页；其余状态更新交给 [onPageChanged]。 */
    private fun switchTab(tab: Int) {
        if (!::pager.isInitialized) return
        if (pager.currentItem == tab) return
        pager.setCurrentItem(tab, true)
    }

    /** 页码变化（左右滑动或点导航）后：同步底部导航选中态 + 确保该页 Fragment 已装载。 */
    private fun onPageChanged(tab: Int) {
        currentTab = tab
        if (::navBar.isInitialized) navBar.select(tab)
        ensurePage(tab)
    }

    /**
     * 把 Fragment 放进对应的页面容器。已经在该容器里就不再重建——这样历史/设置页的
     * 滚动位置和内部状态在反复切页后仍然保留（旧实现每次切页都 replace，等于重建）。
     */
    private fun ensurePage(tab: Int) {
        val container = when (tab) {
            1 -> if (::historyPage.isInitialized) historyPage else null
            2 -> if (::settingsPage.isInitialized) settingsPage else null
            else -> null // 第 0 页是 Activity 自建视图，没有 Fragment
        } ?: return
        if (supportFragmentManager.findFragmentById(container.id) != null) return
        val fragment: androidx.fragment.app.Fragment =
            if (tab == 1) HistoryFragment() else SettingsFragment()
        supportFragmentManager.beginTransaction()
            .replace(container.id, fragment)
            .commitAllowingStateLoss()
    }

    /**
     * ViewPager2 的适配器：三个一级页面视图各占一个 slot。
     *
     * 每个位置返回**独立的 viewType**（= position），所以 RecyclerView 不会把一个页面的
     * ViewHolder 复用到另一个页面上，三个页面视图各自只被一个 ViewHolder 持有。
     * bind 时先把内容从旧父节点摘下再挂到新 slot 上，避免 "already has a parent" 崩溃。
     */
    private class PagesAdapter(private val pages: List<View>) : RecyclerView.Adapter<PagesAdapter.Slot>() {

        class Slot(val slot: FrameLayout) : RecyclerView.ViewHolder(slot)

        override fun getItemCount(): Int = pages.size

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Slot {
            val frame = FrameLayout(parent.context)
            frame.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            return Slot(frame)
        }

        override fun onBindViewHolder(holder: Slot, position: Int) {
            val content = pages[position]
            (content.parent as? ViewGroup)?.removeView(content)
            holder.slot.addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        // 刻意不重写 onViewRecycled：第 1/2 页装的是 Fragment 容器，回收时把它从视图树上
        // 摘下来，就等于让 FragmentManager 在「容器不在树上」的时刻有概率去 createView()，
        // 正是要修的那类崩溃。offscreenPageLimit = 2 已经保证三页常驻、根本不会回收；
        // 万一真发生回收，onBindViewHolder 里的「先摘再挂」也足以正确处理跨 holder 搬运。
    }

    /** 返回键：历史/设置 Tab 下先回首页；首页则走默认（退出）。 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab != 0) {
            switchTab(0)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /**
     * 后台监测卡片的两态（同原型 reminders-page.tsx）：
     * 已启动 → 绿点、ShieldCheck 绿底绿标、次要按钮（浅蓝底蓝字）；
     * 未启动 → 蓝点、ShieldX 灰底灰标、主按钮（主色底白字）。
     */
    private fun refreshMonitoringStatus() {
        val running = monitoringRunning()
        monitoringText.text = if (running) "后台监测已启动" else "后台监测未启动"
        monitoringSubtitle.text =
            if (running) "正在持续监测已启用的提醒点" else "开启后，到达提醒点范围会自动提醒你"

        monitoringDot.background = Ui.circle(if (running) Ui.TOGGLE_ON else Ui.PRIMARY)
        shieldIcon.setImageResource(if (running) R.drawable.ic_shield_check else R.drawable.ic_shield_x)
        shieldIcon.setColorFilter(if (running) Ui.GREEN_OK else Ui.SHIELD_OFF_FG)
        shieldBox.background = Ui.circle(if (running) Ui.GREEN_OK_BG else Ui.SHIELD_OFF_BG)

        monitoringButton.text = if (running) "停止后台监测" else "启动后台监测"
        monitoringButton.setTextColor(if (running) Ui.PRIMARY else Color.WHITE)
        monitoringButton.background = Ui.rounded(if (running) Ui.SHIELD_OFF_BG else Ui.PRIMARY, 18, this)
        monitoringButton.elevation = if (running) 0f else Ui.dpf(this, 2f)
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { cornerRadius = radius.toFloat(); setColor(color) }
    private fun swatchDrawable(color: Int, selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
        if (selected) setStroke(dp(3), Color.rgb(31, 41, 55))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showEditor(point: CheckInPoint?, keepSelectedCoordinates: Boolean = false) {
        pendingPoint = point
        if (!keepSelectedCoordinates) {
            selectedLatitude = point?.latitude ?: selectedLatitude
            selectedLongitude = point?.longitude ?: selectedLongitude
            pendingName = point?.name.orEmpty()
            pendingRadius = (point?.radiusMeters ?: 200).toString()
            pendingActiveStartMinute = point?.activeStartMinute
            pendingActiveEndMinute = point?.activeEndMinute
            pendingDailyTriggerLimit = point?.dailyTriggerLimit
            pendingColor = point?.color
            pendingRepeatMode = point?.repeatMode ?: "DAILY"
            pendingActiveDays = point?.activeDays ?: 127
            pendingOnceDate = point?.onceDate
        }
        lateinit var editorDialog: AlertDialog
        var selectedColor: Int = pendingColor
            ?: if (point == null) PointColors.nextColor(currentPoints) else PointColors.forPoint(point)
        // v5+: parent padding 8dp (was 48 raw px ≈ 16dp, which inflated the gaps).
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), 0) }
        // v5+: defined early — buttons/checkboxes below use it before the card helpers.
        fun <T : View> compact(view: T): T = view.apply {
            minimumHeight = 0
            (view as? android.widget.Button)?.apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        }
        val name = EditText(this).apply { hint = "地点名称，例如：公司大门"; setText(pendingName) }
        // v5 任务二: coordinates become a small caption under the name — no separate block.
        val coordinate = TextView(this).apply { text = coordinatesText(); textSize = 12f; setTextColor(Ui.TEXT_SUB); setPadding(dp(4), dp(4), 0, 0) }
        val radius = EditText(this).apply {
            hint = "触发半径（100–5000 米）"; inputType = InputType.TYPE_CLASS_NUMBER
            setText(pendingRadius)
        }
        val timeWindowEnabled = CheckBox(this).apply { text = "仅在生效时间段内提醒（可选）"; isChecked = pendingActiveStartMinute != null && pendingActiveEndMinute != null; setPadding(0, dp(4), 0, 0); minimumHeight = 0 }
        val startTime = Button(this)
        val endTime = Button(this)
        fun timeLabel(minute: Int?) = minute?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "请选择"
        // v5 任务二: start/end share one row, each half width — "开始 [08:00]  结束 [09:00]".
        fun updateTimeButtons() {
            startTime.text = "开始 ${timeLabel(pendingActiveStartMinute)}"
            endTime.text = "结束 ${timeLabel(pendingActiveEndMinute)}"
            startTime.isEnabled = timeWindowEnabled.isChecked; endTime.isEnabled = timeWindowEnabled.isChecked
        }
        fun pickTime(isStart: Boolean) {
            val value = if (isStart) pendingActiveStartMinute else pendingActiveEndMinute
            // v5 任务一: wheel-style picker replaces the system TimePickerDialog.
            TimeWheelDialog(this, value) { hour, minute ->
                if (isStart) pendingActiveStartMinute = hour * 60 + minute else pendingActiveEndMinute = hour * 60 + minute
                updateTimeButtons()
            }.show()
        }
        startTime.setOnClickListener { pickTime(true) }; endTime.setOnClickListener { pickTime(false) }
        timeWindowEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked && (pendingActiveStartMinute == null || pendingActiveEndMinute == null)) { pendingActiveStartMinute = 7 * 60; pendingActiveEndMinute = 9 * 60 }
            updateTimeButtons()
        }
        val dailyLimitEnabled = CheckBox(this).apply { text = "限制每日最多提醒次数（可选）"; isChecked = pendingDailyTriggerLimit != null; setPadding(0, dp(4), 0, 0); minimumHeight = 0 }
        val dailyLimit = EditText(this).apply { hint = "每天最多触发次数"; inputType = InputType.TYPE_CLASS_NUMBER; setText(pendingDailyTriggerLimit?.toString().orEmpty()); isEnabled = dailyLimitEnabled.isChecked }
        dailyLimitEnabled.setOnCheckedChangeListener { _, checked -> dailyLimit.isEnabled = checked }

        // ── 重复方式三选一（§6.1）──
        val repeatLabel = TextView(this).apply {
            text = "重复方式"; textSize = 13f; setTextColor(Ui.TEXT_SUB); setPadding(0, dp(12), 0, dp(6))
        }
        val weeklyRadio = RadioButton(this).apply { text = "按星期重复" }
        val onceRadio = RadioButton(this).apply { text = "只提醒一次" }
        val dailyRadio = RadioButton(this).apply { text = "每天都提醒" }
        // v5+: radio options packed 4dp apart; group-to-date-button gap 8dp.
        val repeatGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        repeatGroup.addView(weeklyRadio, RadioGroup.LayoutParams(-2, -2).apply { setMargins(0, 0, 0, dp(4)) })
        repeatGroup.addView(onceRadio, RadioGroup.LayoutParams(-2, -2).apply { setMargins(0, 0, 0, dp(4)) })
        repeatGroup.addView(dailyRadio, RadioGroup.LayoutParams(-2, -2))
        val weekdayRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        // v5+: 8dp above the date button (group → date); compact 40dp height.
        val dateButton = compact(Button(this)).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(40)).apply { setMargins(0, dp(8), 0, 0) } }
        val weekdayCells = mutableListOf<TextView>()
        val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
        fun weekdayCellDrawable(selected: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (selected) Ui.PRIMARY else Color.WHITE)
            setStroke(dp(1), if (selected) Ui.PRIMARY else Ui.CHEVRON)
        }
        fun refreshWeekdayRow() {
            weekdayCells.forEachIndexed { index, cell ->
                val selected = (pendingActiveDays shr index) and 1 == 1
                cell.background = weekdayCellDrawable(selected)
                cell.setTextColor(if (selected) Color.WHITE else Ui.TEXT_SUB)
            }
        }
        DAY_LABELS.forEachIndexed { index, label ->
            val cell = TextView(this).apply {
                text = label; gravity = Gravity.CENTER; textSize = 13f
                setOnClickListener {
                    pendingActiveDays = pendingActiveDays xor (1 shl index)
                    refreshWeekdayRow()
                }
            }
            weekdayCells.add(cell)
            weekdayRow.addView(cell, LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        fun refreshRepeatViews() {
            when (pendingRepeatMode) {
                "WEEKLY" -> weeklyRadio.isChecked = true
                "ONCE" -> onceRadio.isChecked = true
                else -> dailyRadio.isChecked = true
            }
            weekdayRow.visibility = if (pendingRepeatMode == "WEEKLY") View.VISIBLE else View.GONE
            dateButton.visibility = if (pendingRepeatMode == "ONCE") View.VISIBLE else View.GONE
            refreshWeekdayRow()
            dateButton.text = "提醒日期：${pendingOnceDate ?: "请选择"}"
        }
        weeklyRadio.setOnClickListener { pendingRepeatMode = "WEEKLY"; refreshRepeatViews() }
        onceRadio.setOnClickListener { pendingRepeatMode = "ONCE"; refreshRepeatViews() }
        dailyRadio.setOnClickListener { pendingRepeatMode = "DAILY"; refreshRepeatViews() }
        dateButton.setOnClickListener {
            val initial = java.time.LocalDate.parse(pendingOnceDate ?: java.time.LocalDate.now().toString())
            android.app.DatePickerDialog(this, { _, y, m, d ->
                pendingOnceDate = "%04d-%02d-%02d".format(y, m + 1, d)
                refreshRepeatViews()
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
        }
        when (pendingRepeatMode) { "WEEKLY" -> weeklyRadio.isChecked = true; "ONCE" -> onceRadio.isChecked = true; else -> dailyRadio.isChecked = true }
        refreshRepeatViews()

        val colorLabel = TextView(this).apply {
            text = "颜色（日历上用颜色区分不同提醒点）"; textSize = 13f
            setTextColor(Ui.TEXT_SUB); setPadding(0, dp(12), 0, dp(6))
        }
        val swatchRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val swatches = mutableListOf<View>()
        fun refreshSwatches() {
            swatches.forEachIndexed { index, view -> view.background = swatchDrawable(PointColors.PALETTE[index], PointColors.PALETTE[index] == selectedColor) }
        }
        PointColors.PALETTE.forEach { paletteColor ->
            val swatch = View(this).apply { setOnClickListener { selectedColor = paletteColor; refreshSwatches() } }
            swatches.add(swatch)
            swatchRow.addView(swatch, LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(0, 0, dp(8), 0) })
        }
        refreshSwatches()
        val chooseMap = Button(this).apply {
            text = "在地图上选点"
            setOnClickListener {
                // Re-open after the result so there is never more than one editor dialog.
                pendingName = name.text.toString()
                pendingRadius = radius.text.toString()
                pendingColor = selectedColor
                editorDialog.dismiss()
                mapPicker.launch(
                    Intent(this@MainActivity, MapPickerActivity::class.java)
                        .putExtra(MapPickerActivity.EXTRA_RADIUS, radius.text.toString().toIntOrNull() ?: 200)
                )
            }
        }
        // v4 §4.3: grouped card form — white rounded cards with grey section labels.
        // v5 任务二: tighter padding (12dp cards, 8dp field gaps) so one screen fits most fields.
        // TODO 沉浸式标题栏：对话框内以自定义头部行模拟，后续可换独立 Activity
        fun sectionLabel(label: String) = TextView(this).apply {
            text = label; textSize = 13f; setTextColor(Ui.TEXT_SUB); setPadding(0, dp(6), 0, dp(3))
        }
        // v5+: 8dp inner padding, no minimum heights — Buttons/CheckBox carry ~88px
        // built-in minima that inflated every card.
        fun card(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            minimumWidth = 0
            background = rounded(Color.WHITE, dp(16))
            views.forEach { addView(it) }
        }
        // v5 任务二: start/end side by side, each half width (compact 40dp buttons).
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        timeRow.addView(compact(startTime).apply { layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(0, 0, dp(6), 0) } })
        timeRow.addView(compact(endTime).apply { layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(6), 0, 0, 0) } })
        // v5+: no "地点" section label — the input's placeholder carries the meaning;
        // compact 40dp button, 8dp under the coordinate caption.
        val locationCard = card(
            name,
            coordinate,
            compact(chooseMap).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(40)).apply { setMargins(0, dp(8), 0, 0) } }
        )
        // v5 任务二: radius slider and value share one row (SeekBar 100–5000 m, two-way sync).
        val radiusValue = TextView(this).apply {
            textSize = 13f; setTextColor(Ui.TEXT_MAIN); gravity = Gravity.END
            setPadding(0, 0, dp(4), 0)
        }
        val radiusSlider = android.widget.SeekBar(this).apply {
            max = 5000 - 100
            // keep the existing numeric input as the source of truth
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val meters = 100 + progress
                    radius.setText(meters.toString())
                    radiusValue.text = "${meters} 米"
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
            })
        }
        fun syncRadiusSlider() {
            val meters = radius.text.toString().toIntOrNull() ?: 200
            radiusSlider.progress = (meters.coerceIn(100, 5000)) - 100
            radiusValue.text = "${meters.coerceIn(100, 5000)} 米"
        }
        radius.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) syncRadiusSlider() }
        syncRadiusSlider()
        val radiusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        radiusRow.addView(radiusSlider, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        radiusRow.addView(radiusValue)
        radiusRow.addView(radius, LinearLayout.LayoutParams(dp(88), LinearLayout.LayoutParams.WRAP_CONTENT))
        val rangeCard = card(sectionLabel("触发范围"), radiusRow)
        val repeatCard = card(sectionLabel("重复方式"), repeatGroup, weekdayRow, dateButton)
        val timeCard = card(sectionLabel("生效时间"), timeWindowEnabled, timeRow, dailyLimitEnabled, dailyLimit)
        val styleCard = card(sectionLabel("颜色（日历区分）"), swatchRow)
        // v5+: colour shown inline again — the window-padding fix frees enough space.
        fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) }
        content.addView(locationCard, cardParams()); content.addView(rangeCard, cardParams())
        content.addView(repeatCard, cardParams()); content.addView(timeCard, cardParams())
        content.addView(styleCard, cardParams())
        // Input boxes: borderless, tinted background to separate from the card.
        fun tinted(editText: EditText) = editText.apply {
            background = rounded(Ui.CHIP_BG, dp(10))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        tinted(name); tinted(radius); tinted(dailyLimit)
        updateTimeButtons()
        // v4 §4.3: full-height dialog — header row (back/title), scrollable form, bottom save bar.
        val backButton = TextView(this).apply {
            text = "←"; textSize = 22f; setPadding(dp(8), 0, dp(24), 0)
            setOnClickListener { editorDialog.dismiss() }
        }
        val dialogTitle = TextView(this).apply {
            text = if (point == null) "新建提醒点" else "编辑提醒点"
            textSize = 18f; setTextColor(Ui.TEXT_MAIN)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
            addView(backButton)
            addView(dialogTitle)
        }
        val saveButton = TextView(this).apply {
            text = "保存"; gravity = Gravity.CENTER; textSize = 17f
            setTextColor(Color.WHITE); setPadding(0, dp(14), 0, dp(14))
            background = rounded(Ui.PRIMARY, dp(28))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        val editorRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(saveButton, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), dp(8), dp(16), dp(16)) })
        }
        editorDialog = AlertDialog.Builder(this, R.style.Theme_CheckInAssistant_FullScreenDialog)
            .setView(editorRoot)
            .create()

        // v11 修复「进入编辑页时先留白、再向左跳一下」：
        // Dialog.show() 会把 decor 视图按「当时的 window attributes」加进 WindowManager，
        // 所以第一帧用的是主题默认值——WRAP_CONTENT 的窄窗口 + 系统 dialog 9-patch
        // 背景的左右内缩（约 16–24dp）。原先这些配置写在 setOnShowListener 里，
        // 而该回调在 decor 已经 addView + layout 之后才触发，于是先渲染出一帧
        // 又窄又内缩的画面，随后 setLayout(-1,-1) 才把它撑成全屏——看起来就是
        // 「左边一点空白 → 内容从右往左移一下」。
        // 把配置全部提前到 show() 之前：PhoneWindow.getAttributes() 返回的是同一个
        // 可变对象，show() 添加 decor 时读到的就已经是全屏参数，第一帧即铺满。
        editorDialog.window?.apply {
            setLayout(-1, -1) // full-screen editor (spec §4.3)
            // v5+: replace the stock dialog background — its 9-patch insets (~16-24dp)
            // were the invisible layer inflating every gap between cards. A plain
            // drawable leaves spacing entirely to editorRoot/content.
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        editorRoot.setPadding(0, 0, 0, 0)
        editorRoot.setBackgroundColor(Ui.PAGE_BG)

        editorDialog.setOnShowListener {
                    saveButton.setOnClickListener {
                        val pointName = name.text.toString().trim()
                        val radiusMeters = radius.text.toString().toIntOrNull()
                        val startMinute = pendingActiveStartMinute
                        val endMinute = pendingActiveEndMinute
                        val dailyLimitValue = if (dailyLimitEnabled.isChecked) dailyLimit.text.toString().toIntOrNull() else null
                        when {
                            pointName.isBlank() -> name.error = "请输入地点名称"
                            radiusMeters == null || radiusMeters !in 100..5000 -> radius.error = "半径应为 100–5000 米"
                            timeWindowEnabled.isChecked && (startMinute == null || endMinute == null || startMinute == endMinute) -> Toast.makeText(this@MainActivity, "请选择有效的生效时间段", Toast.LENGTH_SHORT).show()
                            dailyLimitEnabled.isChecked && (dailyLimitValue == null || dailyLimitValue < 1) -> dailyLimit.error = "次数应至少为 1"
                            pendingRepeatMode == "WEEKLY" && pendingActiveDays == 0 -> Toast.makeText(this@MainActivity, "按星期重复至少选择一天", Toast.LENGTH_SHORT).show()
                            pendingRepeatMode == "ONCE" && pendingOnceDate == null -> Toast.makeText(this@MainActivity, "请选择一次性提醒的日期", Toast.LENGTH_SHORT).show()
                            else -> {
                                // Spec §4.9: reset dedup/ONCE state when the relevant inputs change.
                                val updated = point
                                    ?.let { com.checkinassistant.reminder.PointRules.resetWindowDedup(it) }
                                    ?.let {
                                        if (pendingRepeatMode == "ONCE" && pendingOnceDate != it.onceDate) {
                                            com.checkinassistant.reminder.PointRules.rearmOnce(it)
                                        } else it
                                    }
                                val onceStatus = when {
                                    pendingRepeatMode != "ONCE" -> null
                                    point?.onceStatus != null && pendingOnceDate == point.onceDate -> point.onceStatus
                                    else -> "PENDING"
                                }
                                val saved = CheckInPoint(
                                    id = point?.id ?: 0, name = pointName, latitude = selectedLatitude, longitude = selectedLongitude,
                                    radiusMeters = radiusMeters, dwellSeconds = point?.dwellSeconds ?: 25, enabled = point?.enabled ?: true,
                                    triggerState = point?.triggerState ?: com.checkinassistant.data.PointState.ARMED.name, enteredAt = point?.enteredAt,
                                    activeStartMinute = if (timeWindowEnabled.isChecked) startMinute else null,
                                    activeEndMinute = if (timeWindowEnabled.isChecked) endMinute else null,
                                    dailyTriggerLimit = dailyLimitValue,
                                    color = selectedColor,
                                    repeatMode = pendingRepeatMode,
                                    activeDays = if (pendingRepeatMode == "WEEKLY") pendingActiveDays else 127,
                                    onceDate = if (pendingRepeatMode == "ONCE") pendingOnceDate else null,
                                    onceStatus = onceStatus,
                                    completedAt = if (onceStatus == point?.onceStatus) point?.completedAt else null,
                                    skipUntilDate = point?.skipUntilDate,
                                    lastTriggeredWindowStart = updated?.lastTriggeredWindowStart
                                )
                                lifecycleScope.launch {
                                    val pointId = if (point == null) dao.insert(saved) else {
                                        dao.update(saved)
                                        saved.id
                                    }
                                    DiagnosticRecordLogger.geofence(this@MainActivity, "POINT_SAVE：地点 #$pointId“$pointName”已写入数据库，立即请求后台服务重新注册")
                                    // The service re-registers the complete current set atomically after each CRUD change.
                                    GeofenceMonitoringService.start(this@MainActivity)
                                }
                                pendingPoint = null
                                pendingName = ""
                                pendingRadius = ""
                                pendingActiveStartMinute = null
                                pendingActiveEndMinute = null
                                pendingDailyTriggerLimit = null
                                pendingColor = null
                                pendingRepeatMode = "DAILY"
                                pendingActiveDays = 127
                                pendingOnceDate = null
                                editorDialog.dismiss()
                            }
                        }
                    }
                }
        editorDialog.show()
    }

    /** A disabled point must stop immediately: drop its reminders, then re-register geofences. */
    /** §4.7 / §6.2: skip today for WEEKLY / DAILY / ONCE; writes a PointSkipEvent for stats. */
    private fun skipPointToday(point: CheckInPoint) = lifecycleScope.launch {
        val today = java.time.LocalDate.now()
        val updated = com.checkinassistant.reminder.PointRules.onSkipToday(point, today)
        dao.update(updated)
        AppDatabase.get(this@MainActivity).pointSkipEventDao().insert(
            PointSkipEvent(reminderPointId = point.id, skipDate = today.toString(), createdAt = System.currentTimeMillis())
        )
        ReminderEngine.cancel(this@MainActivity, point.id)
        ReminderEngine.dismissNotifications(this@MainActivity, point.id)
        AppDatabase.get(this@MainActivity).reminderDao().deactivate(point.id)
        GeofenceMonitoringService.start(this@MainActivity)
        DiagnosticRecordLogger.geofence(this@MainActivity, "POINT_SKIP：#${point.id} ${point.name} 跳过 ${today}，已写入跳过事件")
        Toast.makeText(this@MainActivity, "已跳过今天：${point.name}", Toast.LENGTH_SHORT).show()
    }

    /** §4.9c: restore a finished/skipped/expired ONCE point — date unchanged, status back to PENDING. */
    /** v5+: user acknowledges an unacknowledged ONCE arrival — latest IGNORED record → COMPLETED. */
    private fun acknowledgePoint(point: CheckInPoint) = lifecycleScope.launch {
        val recordDao = AppDatabase.get(this@MainActivity).recordDao()
        recordDao.latestForPoint(point.id)?.let { record ->
            if (record.status == RecordStatus.IGNORED.name) {
                recordDao.update(record.copy(status = RecordStatus.COMPLETED.name, actionAt = System.currentTimeMillis()))
            }
        }
        // Badge flips to "已完成" via the records flow; the point itself stays as-is.
        DiagnosticRecordLogger.geofence(this@MainActivity, "POINT_ACKNOWLEDGE：#${point.id} ${point.name} 已到达未确认 → 已完成")
        Toast.makeText(this@MainActivity, "已确认：${point.name}", Toast.LENGTH_SHORT).show()
    }

    private fun restorePoint(point: CheckInPoint) = lifecycleScope.launch {
        val updated = com.checkinassistant.reminder.PointRules.rearmOnce(point)
        dao.update(updated)
        GeofenceMonitoringService.start(this@MainActivity)
        DiagnosticRecordLogger.geofence(this@MainActivity, "POINT_RESTORE：#${point.id} ${point.name} onceStatus=${point.onceStatus}→PENDING，日期保持 ${point.onceDate}")
        Toast.makeText(this@MainActivity, "已恢复：${point.name}", Toast.LENGTH_SHORT).show()
    }

    private fun togglePoint(point: CheckInPoint, enabled: Boolean) = lifecycleScope.launch {
        // v4 §1.3 改动点4: re-enabling a COMPLETED once-point re-arms it (date unchanged).
        if (enabled && point.repeatMode == "ONCE" && point.onceStatus == "COMPLETED") {
            dao.update(com.checkinassistant.reminder.PointRules.rearmOnce(point).copy(enabled = true))
        } else {
            dao.setEnabledAndRearm(point.id, enabled)
        }
        AppDatabase.get(this@MainActivity).pointEnabledEventDao()
            .insert(PointEnabledEvent(pointId = point.id, atMillis = System.currentTimeMillis(), enabled = enabled))
        ReminderEngine.cancel(this@MainActivity, point.id)
        ReminderEngine.dismissNotifications(this@MainActivity, point.id)
        AppDatabase.get(this@MainActivity).reminderDao().deactivate(point.id)
        GeofenceMonitoringService.start(this@MainActivity)
        DiagnosticRecordLogger.geofence(this@MainActivity, "POINT_TOGGLE：#${point.id} ${point.name} enabled=$enabled，已取消提醒并重新注册围栏")
        Toast.makeText(this@MainActivity, if (enabled) "已启用：${point.name}" else "已禁用：${point.name}", Toast.LENGTH_SHORT).show()
    }

    private fun coordinatesText() = "坐标：${"%.6f".format(selectedLatitude)}, ${"%.6f".format(selectedLongitude)}"

    private fun confirmDelete(point: CheckInPoint) {
        AlertDialog.Builder(this).setTitle("删除提醒点？").setMessage("将删除“${point.name}”。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> lifecycleScope.launch { dao.delete(point); GeofenceMonitoringService.start(this@MainActivity) } }.show()
    }

    private fun chooseSimulationPoint() {
        lifecycleScope.launch {
            val points = dao.enabledPoints()
            if (points.isEmpty()) { Toast.makeText(this@MainActivity, "请先添加并保存一个提醒点", Toast.LENGTH_SHORT).show(); return@launch }
            AlertDialog.Builder(this@MainActivity).setTitle("选择要模拟的提醒点")
                .setItems(points.map { it.name }.toTypedArray()) { _, index ->
                    lifecycleScope.launch { ReminderEngine.simulate(this@MainActivity, points[index].id) }
                }.show()
        }
    }

    private fun chooseRingtone() {
        val preferences = getSharedPreferences("reminder_settings", MODE_PRIVATE)
        val current = preferences.getString("ringtone_uri", null)?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        })
    }
}
