package com.checkinassistant

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.checkinassistant.data.AppDatabase
import com.checkinassistant.data.CheckInRecord
import com.checkinassistant.data.DiagnosticRecordLogger
import com.checkinassistant.data.RecordStatus
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Technical records deliberately kept separate from the daily check-in calendar. */
class DebugLogActivity : AppCompatActivity() {
    private val dao by lazy { AppDatabase.get(this).recordDao() }
    private lateinit var retentionButton: Button
    private lateinit var logContainer: LinearLayout
    /** 最近一次渲染的记录，供「复制全部」使用（不改动任何日志读写逻辑）。 */
    private var lastRecords: List<CheckInRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "调试记录"
        setContentView(createContent())
        lifecycleScope.launch { DiagnosticRecordLogger.clearExpired(this@DebugLogActivity) }
        lifecycleScope.launch {
            dao.observeByStatus(RecordStatus.DEBUG.name).collectLatest(::renderLogs)
        }
    }

    private fun createContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Ui.ROW_BG)
        }
        root.addView(TextView(this).apply {
            text = "围栏与后台监测的技术记录，仅用于排查问题。"
            textSize = 14f; setTextColor(Ui.TEXT_SUB); setPadding(0, 0, 0, dp(10))
        })
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        retentionButton = Button(this).apply { setOnClickListener { editRetention() } }
        actions.addView(retentionButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(0, 0, dp(4), 0) })
        actions.addView(Button(this).apply { text = "导出日志"; setOnClickListener { exportLogs() } }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        actions.addView(Button(this).apply { text = "复制全部"; setOnClickListener { copyAllLogs() } }, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(actions)
        logContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
        root.addView(ScrollView(this).apply { addView(logContainer) }, LinearLayout.LayoutParams(-1, 0, 1f))
        updateRetentionLabel()
        return root
    }

    private fun renderLogs(records: List<CheckInRecord>) {
        logContainer.removeAllViews()
        lastRecords = records
        if (records.isEmpty()) {
            logContainer.addView(TextView(this).apply { text = "暂无保留的调试记录"; textSize = 15f; setTextColor(Color.GRAY); gravity = Gravity.CENTER; setPadding(0, dp(30), 0, 0) })
            return
        }
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        records.forEach { record ->
            val text = "${format.format(Date(record.triggeredAt))}\n${record.pointName.removePrefix("【围栏调试】")}"
            logContainer.addView(TextView(this).apply {
                this.text = text
                textSize = 13f; setTextColor(Ui.TEXT_MAIN); setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.WHITE)
                // 可自由复制：系统长按选择 + 长按整条复制，两者都不影响原有导出/保留功能
                setTextIsSelectable(true)
                setOnLongClickListener { copyToClipboard("调试日志", text); true }
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) })
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val manager = getSystemService(android.content.ClipboardManager::class.java)
        manager.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
    }

    /** 复制当前列表里的全部日志，格式与「导出日志」保持一致（时间正序）。 */
    private fun copyAllLogs() {
        val records = lastRecords
        if (records.isEmpty()) {
            Toast.makeText(this, "暂无可复制的调试日志", Toast.LENGTH_SHORT).show()
            return
        }
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val text = buildString {
            appendLine("别错过站调试日志")
            appendLine("复制时间：${format.format(Date())}")
            appendLine()
            records.asReversed().forEach { record ->
                appendLine("[${format.format(Date(record.triggeredAt))}] ${record.pointName.removePrefix("【围栏调试】")}")
            }
        }
        copyToClipboard("调试日志(全部)", text)
    }

    private fun editRetention() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(DiagnosticRecordLogger.retentionDays(this@DebugLogActivity).toString())
            selectAll()
        }
        AlertDialog.Builder(this).setTitle("调试日志保留天数").setMessage("超过此天数的调试日志会自动删除（1–3650 天）。")
            .setView(input).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                val days = input.text.toString().toIntOrNull()
                if (days == null || days !in 1..3650) {
                    Toast.makeText(this, "请输入 1 到 3650 的天数", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                getSharedPreferences(DiagnosticRecordLogger.PREFERENCES_NAME, MODE_PRIVATE).edit().putInt(DiagnosticRecordLogger.KEY_RETENTION_DAYS, days).apply()
                updateRetentionLabel()
                lifecycleScope.launch { DiagnosticRecordLogger.clearExpired(this@DebugLogActivity) }
            }.show()
    }

    private fun updateRetentionLabel() {
        retentionButton.text = "保留 ${DiagnosticRecordLogger.retentionDays(this)} 天"
    }

    private fun exportLogs() {
        lifecycleScope.launch {
            val records = dao.getByStatus(RecordStatus.DEBUG.name)
            if (records.isEmpty()) {
                Toast.makeText(this@DebugLogActivity, "暂无可导出的调试日志", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val directory = File(cacheDir, "shared_logs").apply { mkdirs() }
            val file = File(directory, "checkin-debug-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
            file.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("别错过站调试日志")
                writer.appendLine("导出时间：${format.format(Date())}")
                writer.appendLine("保留策略：最近 ${DiagnosticRecordLogger.retentionDays(this@DebugLogActivity)} 天")
                writer.appendLine()
                records.asReversed().forEach { record ->
                    writer.appendLine("[${format.format(Date(record.triggeredAt))}] ${record.pointName.removePrefix("【围栏调试】")}")
                }
            }
            val uri = FileProvider.getUriForFile(this@DebugLogActivity, "$packageName.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "导出调试日志"))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
