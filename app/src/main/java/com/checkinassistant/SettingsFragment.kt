package com.checkinassistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * v8 设置页 —— 与 Web 原型 components/app/settings-page.tsx 1:1 对齐。
 *
 * 结构：PageHeader（设置 / 无月份按钮）→ 4 个分组（分组标题 + rounded-3xl 白卡 +
 * TintedIcon 行 + 68dp 缩进细分隔线）→ 页脚版本号。header 与内容同宽（容器带 px-4）。
 *
 * 箭头规则（v12 起）：只有点击后会离开当前页面的行才显示 Chevron
 * （提醒铃声 / 权限自检 / 调试日志）；弹确认框、弹说明框、拉起分享面板、无操作的行都不显示。
 * 仅影响箭头的有无，点击事件本身保持原样。
 */
class SettingsFragment : Fragment() {

    private lateinit var headerRow: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val scroll = android.widget.ScrollView(context).apply { isFillViewport = true }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.PAGE_BG)
        }

        // ── 顶部插画 Header（与首页/历史页同款，无月份按钮）──
        val header = Ui.pageHeader(context, Ui.HEADER_ICON_SETTINGS, "设置", "管理你的应用和相关设置")
        headerRow = header.row
        // v10: 顶部插画左右铺满屏幕（背景层），标题等前景内容叠加其上
        root.addView(header.root, LinearLayout.LayoutParams(-1, -2))

        // ── 内容列（原型 flex flex-col gap-5 px-4 pb-6）──
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        // ── 分组：提醒 ──
        content.addView(section("提醒", listOf(
            SettingRow(R.drawable.ic_bell, Ui.Tint.BLUE, "提醒铃声", "选择提醒提示音", chevron = true) { chooseRingtone() }
        )))
        // ── 分组：诊断 ──
        content.addView(section("诊断", listOf(
            SettingRow(R.drawable.ic_shield_check, Ui.Tint.GREEN, "权限自检", "定位 / 通知 / 闹钟 / 全屏提醒权限", chevron = true) {
                requireActivity().startActivity(Intent(requireContext(), PermissionsActivity::class.java))
            },
            SettingRow(R.drawable.ic_file_text, Ui.Tint.PURPLE, "调试日志", "技术诊断日志", chevron = true) {
                requireActivity().startActivity(Intent(requireContext(), DebugLogActivity::class.java))
            }
        )))
        // ── 分组：数据管理 ──
        content.addView(section("数据管理", listOf(
            SettingRow(R.drawable.ic_trash2, Ui.Tint.AMBER, "清空调试日志", "删除全部诊断记录") { confirmClearLogs() },
            SettingRow(R.drawable.ic_download, Ui.Tint.BLUE, "导出数据备份", "导出提醒点 / 历史 / 日志") { exportBackup() }
        )))
        // ── 分组：关于 ──
        content.addView(section("关于", listOf(
            SettingRow(R.drawable.ic_info, Ui.Tint.SLATE, "版本信息", "查看当前版本", trailing = appVersionName()),
            SettingRow(R.drawable.ic_book_open, Ui.Tint.GREEN, "使用说明", "功能简介与使用方法") { showHelp() }
        )))

        content.addView(
            Ui.text(context, 12f, Ui.TEXT_SUB, Typeface.DEFAULT, "别错过站 · v${appVersionName()}").apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        )

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

    // ── UI 构件（对齐原型 settings-page.tsx）────────────────────

    private data class SettingRow(
        val iconRes: Int,
        val tint: Ui.Tint,
        val title: String,
        val subtitle: String,
        val trailing: String? = null,
        /**
         * v12: 是否显示右侧小箭头。规则＝只有点击后会**离开当前页面**（打开另一个页面，
         * 含系统页面）的行才显示。默认 false：弹确认框 / 弹说明框 / 拉起分享面板 / 无操作
         * 的行都不再画箭头。点击事件本身不受影响，仍然照常触发 [onClick]。
         */
        val chevron: Boolean = false,
        val onClick: (() -> Unit)? = null
    )

    /** 分组：section gap-2 —— px-2 标题 + rounded-3xl 白卡。 */
    private fun section(title: String, rows: List<SettingRow>): LinearLayout {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                Ui.groupLabel(context, title).apply { setPadding(dp(8), 0, 0, 0) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            )

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(Color.WHITE, 22, context) // rounded-3xl
                elevation = Ui.dpf(context, 1f) // shadow-sm shadow-black/5
                Ui.roundOutline(this, Ui.dpf(context, 22f))
            }
            rows.forEachIndexed { index, row ->
                card.addView(settingRow(row))
                if (index < rows.size - 1) {
                    // 行间分隔线：ml-[4.25rem] h-px bg-black/[0.05]
                    card.addView(
                        View(context).apply { setBackgroundColor(Ui.DIVIDER) },
                        LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(68) }
                    )
                }
            }
            addView(card, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /**
     * 设置行：px-4 py-3.5 gap-3 —— TintedIcon 44 + 标题 15sp/半粗 + 副标题 12.5sp
     * + 可选尾部灰字 13sp + Chevron（原型每行都有）。
     */
    private fun settingRow(item: SettingRow): LinearLayout {
        val context = requireContext()
        val gap = dp(12)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { item.onClick?.invoke() }

            addView(
                Ui.tintedIcon(context, item.iconRes, item.tint),
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = gap }
            )

            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.text(context, 15f, Ui.TEXT_MAIN, Ui.SEMIBOLD, item.title))
            texts.addView(
                Ui.text(context, 12.5f, Ui.TEXT_SUB, Typeface.DEFAULT, item.subtitle).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) }
            )
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            item.trailing?.let {
                addView(
                    Ui.text(context, 13f, Ui.TEXT_SUB, Ui.SEMIBOLD, it),
                    LinearLayout.LayoutParams(-2, -2).apply { rightMargin = gap }
                )
            }
            // v12: 只有会跳转到另一个页面的行才画箭头（见 SettingRow.chevron 注释）
            if (item.chevron) {
                addView(Ui.chevron(context), LinearLayout.LayoutParams(dp(20), dp(20)))
            }
        }
    }

    // ── 动作（逻辑未改动）───────────────────────────────────────

    private fun chooseRingtone() {
        try {
            val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALARM)
                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提醒铃声")
                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            }
            ringtonePicker.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法打开铃声选择器", Toast.LENGTH_SHORT).show()
        }
    }

    // v6.1: Fragment 不能用 onActivityResult override，改用 ActivityResult API
    private val ringtonePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            // 与 MainActivity 铃声选择共用同一存储（reminder_settings / ringtone_uri）
            requireContext().getSharedPreferences("reminder_settings", Context.MODE_PRIVATE).edit()
                .putString("ringtone_uri", uri?.toString().orEmpty()).apply()
            val name = uri?.let { android.media.RingtoneManager.getRingtone(requireContext(), it)?.getTitle(requireContext()) } ?: "静音"
            Toast.makeText(requireContext(), "已设置提醒铃声：$name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearLogs() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("清空调试日志")
            .setMessage("将删除全部诊断记录，且不可恢复。确定继续？")
            .setPositiveButton("清空") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    // Debug 状态的记录即调试日志（pointId=0 的诊断条目）
                    val dao = com.checkinassistant.data.AppDatabase.get(requireContext().applicationContext).recordDao()
                    dao.deleteByStatusBefore(com.checkinassistant.data.RecordStatus.DEBUG.name, Long.MAX_VALUE)
                    Toast.makeText(requireActivity(), "调试日志已清空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportBackup() {
        viewLifecycleOwner.lifecycleScope.launch {
            val json = com.checkinassistant.data.BackupExporter.exportAll(requireContext().applicationContext)
            val dir = File(requireContext().getExternalFilesDir(null), "backup").apply { mkdirs() }
            val file = File(dir, "checkin-backup-${System.currentTimeMillis()}.json")
            file.writeText(json)
            val uri = FileProvider.getUriForFile(
                requireActivity(), "${requireContext().packageName}.fileprovider", file
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "导出数据备份"))
        }
    }

    private fun appVersionName(): String = try {
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun showHelp() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("使用说明")
            .setMessage(
                "「别错过站」在您到达指定地点附近时弹出全屏提醒，帮您确认到站/打卡，不再坐过站。\n\n" +
                    "① 首页点「添加提醒点」，在地图上选位置并设置半径；\n" +
                    "② 可选设置生效时间段与重复方式（每天/按星期/仅一次）；\n" +
                    "③ 保持后台监测开启，到达附近即会全屏提醒，点「我已到达」完成；\n" +
                    "④ 「历史」页查看打卡记录，「设置」页管理权限与数据。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun dp(value: Int) = (value * requireContext().resources.displayMetrics.density).toInt()
}
