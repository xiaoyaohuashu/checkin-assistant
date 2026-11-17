package com.checkinassistant

import com.checkinassistant.ui.Ui
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
        // v2.2.26（低 5）：把旧版留下的撤销文件挪到不参与系统备份的目录（设置页随 App 启动创建，等于每次打开 App 检查一次）
        context.applicationContext.let { app ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.checkinassistant.data.AndroidBackupStore.migrateLegacyUndoFiles(app)
            }
        }
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
        // ── 分组：地图（v2.2.25）──
        content.addView(section("地图", listOfNotNull(
            SettingRow(
                R.drawable.ic_map_pin, Ui.Tint.GREEN, "地图服务", "地图和定位用谷歌还是高德",
                trailing = mapServiceLabel(com.checkinassistant.map.MapSettings.setting(context)),
                trailingSink = { mapServiceTrailing = it }
            ) { chooseMapService() },
            // v2.2.26（中 6）：没配高德 Key 的安装包里不可能同意过，就不显示这一行
            if (com.checkinassistant.map.MapSettings.amapKeyConfigured(context)) SettingRow(
                R.drawable.ic_shield_x, Ui.Tint.AMBER, "撤回高德隐私同意", "撤回后不再使用高德地图和高德定位",
                trailing = amapPrivacyLabel(),
                trailingSink = { amapPrivacyTrailing = it }
            ) { revokeAmapPrivacy() } else null
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
            SettingRow(R.drawable.ic_download, Ui.Tint.BLUE, "导出数据备份", "导出提醒点 / 历史 / 设置") { exportBackup() },
            SettingRow(R.drawable.ic_file_text, Ui.Tint.GREEN, "导入数据备份", "用备份文件替换全部数据") { importBackup() }
        )))
        // ── 分组：关于 ──
        content.addView(section("关于", listOf(
            SettingRow(R.drawable.ic_download, Ui.Tint.BLUE, "检查更新", "有新版本时在应用内下载安装") {
                com.checkinassistant.update.UpdatePrompt.check(requireActivity() as androidx.appcompat.app.AppCompatActivity, manual = true)
            },
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
        /** v2.2.25：拿到右侧灰字控件，选完后刷新显示用。 */
        val trailingSink: ((TextView) -> Unit)? = null,
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
                val trailingView = Ui.text(context, 13f, Ui.TEXT_SUB, Ui.SEMIBOLD, it)
                addView(
                    trailingView,
                    LinearLayout.LayoutParams(-2, -2).apply { rightMargin = gap }
                )
                item.trailingSink?.invoke(trailingView)
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
            (activity as? com.checkinassistant.MainActivity)?.askRingtonePermissionIfNeeded(force = true)
        }
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

    // ── v2.2.25：地图服务 ─────────────────────────────────────────

    private var mapServiceTrailing: TextView? = null
    private var amapPrivacyTrailing: TextView? = null

    private fun amapPrivacyLabel(): String = when (com.checkinassistant.map.MapSettings.privacy(requireContext())) {
        com.checkinassistant.domain.AmapPrivacy.AGREED -> "已同意"
        com.checkinassistant.domain.AmapPrivacy.DECLINED -> "未同意"
        com.checkinassistant.domain.AmapPrivacy.UNKNOWN -> "未询问"
    }

    private fun refreshAmapPrivacyLabel() {
        if (isAdded) amapPrivacyTrailing?.text = amapPrivacyLabel()
    }

    override fun onResume() {
        super.onResume()
        refreshAmapPrivacyLabel()
    }

    private fun revokeAmapPrivacy() {
        val context = requireContext()
        if (com.checkinassistant.map.MapSettings.privacy(context) != com.checkinassistant.domain.AmapPrivacy.AGREED) {
            refreshAmapPrivacyLabel()
            Toast.makeText(context, "当前没有同意高德隐私说明，无需撤回", Toast.LENGTH_SHORT).show()
            return
        }
        com.checkinassistant.map.AmapPrivacyPrompt.confirmRevoke(requireActivity()) { refreshAmapPrivacyLabel() }
    }

    private fun mapServiceLabel(value: com.checkinassistant.domain.MapServiceSetting) = when (value) {
        com.checkinassistant.domain.MapServiceSetting.AUTO -> "自动"
        com.checkinassistant.domain.MapServiceSetting.GOOGLE -> "始终谷歌"
        com.checkinassistant.domain.MapServiceSetting.AMAP -> "始终高德"
    }

    private fun chooseMapService() {
        val context = requireContext()
        val values = com.checkinassistant.domain.MapServiceSetting.values()
        val current = com.checkinassistant.map.MapSettings.setting(context)
        val labels = arrayOf(
            "自动（推荐）：地图页在中国大陆或手机没有 Google Play 服务时用高德，否则用谷歌；后台定位规则不变",
            "始终谷歌",
            "始终高德（地图和后台定位都用高德）"
        )
        android.app.AlertDialog.Builder(context)
            .setTitle("地图服务")
            .setSingleChoiceItems(labels, values.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                applyMapService(values[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyMapService(value: com.checkinassistant.domain.MapServiceSetting) {
        val context = requireContext()
        val old = com.checkinassistant.map.MapSettings.setting(context)
        com.checkinassistant.map.MapSettings.setSetting(context, value)
        mapServiceTrailing?.text = mapServiceLabel(value)
        // v2.2.26（中 1）：切到「始终高德」时后台定位也要换成高德，需要重建一次后台服务（手动停止过则不动）
        if (com.checkinassistant.domain.LocationSourceRules.needsServiceRecreate(
                old, value, com.checkinassistant.location.PlayServices.isInstalled(context)
            )
        ) {
            if (com.checkinassistant.geofence.MonitorRestart.recreateService(context) != null) {
                Toast.makeText(context, "后台监测重启失败，请到首页重新启动", Toast.LENGTH_LONG).show()
            }
        }
        when (value) {
            com.checkinassistant.domain.MapServiceSetting.AUTO -> Unit
            com.checkinassistant.domain.MapServiceSetting.GOOGLE ->
                if (!com.checkinassistant.location.PlayServices.isInstalled(context)) {
                    Toast.makeText(context, "这台手机没有 Google Play 服务，地图页会改用高德", Toast.LENGTH_LONG).show()
                }
            com.checkinassistant.domain.MapServiceSetting.AMAP -> when {
                !com.checkinassistant.map.MapSettings.amapKeyConfigured(context) ->
                    Toast.makeText(context, "未配置高德 Key，地图和后台定位暂时仍用谷歌", Toast.LENGTH_LONG).show()
                com.checkinassistant.map.MapSettings.privacy(context) != com.checkinassistant.domain.AmapPrivacy.AGREED ->
                    com.checkinassistant.map.AmapPrivacyPrompt.ask(
                        requireActivity(),
                        onAgree = { refreshAmapPrivacyLabel(); Toast.makeText(context, "已同意，地图和后台定位会使用高德", Toast.LENGTH_SHORT).show() },
                        onDecline = { refreshAmapPrivacyLabel(); Toast.makeText(context, "未同意高德隐私说明，地图和后台定位仍用谷歌", Toast.LENGTH_LONG).show() },
                        onCancel = {}
                    )
                else -> Unit
            }
        }
    }

    // ── v2.2.25：导入数据备份（覆盖全部）──────────────────────────

    private val importPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? -> uri?.let(::readAndConfirm) }

    private fun importBackup() {
        val undo = com.checkinassistant.data.AndroidBackupStore.latestUndoFile(requireContext())
        if (undo == null) {
            launchImportPicker()
            return
        }
        val time = com.checkinassistant.data.BackupImportRules.formatTime(undo.lastModified())
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("导入数据备份")
            .setItems(arrayOf("从文件选择备份…", "撤销上次导入（恢复到 $time 导入前的数据）")) { _, which ->
                if (which == 0) launchImportPicker() else confirmUndo(undo, time)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchImportPicker() {
        // 不按文件类型过滤：微信、网盘转来的 .json 常被标成别的类型，统一靠内容校验。
        try {
            importPicker.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readAndConfirm(uri: android.net.Uri) {
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            buffer.write(chunk, 0, n)
                            if (buffer.size() > com.checkinassistant.data.BackupImportRules.MAX_IMPORT_BYTES) {
                                throw com.checkinassistant.data.BackupFormatException(com.checkinassistant.data.BackupImportRules.TOO_LARGE_TEXT)
                            }
                        }
                        buffer.toByteArray()
                    } ?: throw java.io.IOException("打不开这个文件")
                    Result.success(com.checkinassistant.data.BackupCodec.decode(String(bytes, Charsets.UTF_8)))
                } catch (e: com.checkinassistant.data.BackupFormatException) {
                    Result.failure(e)
                } catch (e: Exception) {
                    Result.failure(Exception("无法读取文件（${e.message ?: e.javaClass.simpleName}）"))
                }
            }
            if (!isAdded) return@launch
            decoded.fold(
                onSuccess = { confirmImport(it, com.checkinassistant.data.UndoKind.BEFORE_IMPORT, null) },
                onFailure = { showImportFailure(it.message ?: "未知原因") }
            )
        }
    }

    private fun confirmUndo(file: File, time: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // 撤销文件是 App 自己写的：用宽松模式读，不受 v2.2.26 新加的取值校验影响
                runCatching { com.checkinassistant.data.BackupCodec.decode(file.readText(), lenient = true) }
            }
            if (!isAdded) return@launch
            decoded.fold(
                onSuccess = { confirmImport(it, com.checkinassistant.data.UndoKind.BEFORE_UNDO, time) },
                onFailure = { showImportFailure("撤销文件读不出来（${it.message}）") }
            )
        }
    }

    private fun confirmImport(backup: com.checkinassistant.data.DecodedBackup, kind: com.checkinassistant.data.UndoKind, undoTime: String?) {
        val isUndo = kind == com.checkinassistant.data.UndoKind.BEFORE_UNDO
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(if (isUndo) "撤销上次导入" else "导入数据备份")
            .setMessage(com.checkinassistant.data.BackupImportRules.confirmText(backup, isUndo, undoTime))
            .setPositiveButton(if (isUndo) "恢复" else "导入") { _, _ -> runImport(backup, kind) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runImport(backup: com.checkinassistant.data.DecodedBackup, kind: com.checkinassistant.data.UndoKind) {
        val app = requireContext().applicationContext
        val isUndo = kind == com.checkinassistant.data.UndoKind.BEFORE_UNDO
        Toast.makeText(app, if (isUndo) "正在恢复…" else "正在导入…", Toast.LENGTH_SHORT).show()
        // 进程级作用域：转屏、离开设置页都不会把导入的后半段（停提醒、重新安排监测）取消掉。
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val result = com.checkinassistant.data.BackupImporter.importAll(
                com.checkinassistant.data.AndroidBackupStore(app), backup, kind
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val (title, message) = when (result) {
                    is com.checkinassistant.data.ImportResult.Success ->
                        (if (isUndo) "已撤销上次导入" else "导入完成") to com.checkinassistant.data.BackupImportRules.resultText(result, isUndo)
                    is com.checkinassistant.data.ImportResult.Failure -> (if (isUndo) "撤销失败" else "导入失败") to result.reason
                    com.checkinassistant.data.ImportResult.Busy -> "请稍候" to "已有一次导入正在进行"
                }
                if (isAdded) {
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle(title).setMessage(message).setPositiveButton("知道了", null).show()
                } else {
                    Toast.makeText(app, "$title：${message.lineSequence().first()}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showImportFailure(reason: String) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("无法导入")
            .setMessage("$reason\n\n现有数据没有改动。")
            .setPositiveButton("知道了", null)
            .show()
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
                    "④ 「历史」页查看打卡记录，「设置」页管理权限与数据。\n\n" +
                    "中国边界数据 © OpenStreetMap contributors（ODbL）"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun dp(value: Int) = (value * requireContext().resources.displayMetrics.density).toInt()
}
