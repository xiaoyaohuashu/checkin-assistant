package com.checkinassistant.update

import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 更新的对话框流程（v2.2.16）：发现新版 → 显示更新内容 → 下载（带进度）→ 系统安装界面。
 * [manual] = 用户在设置页手动点「检查更新」：已是最新、网络失败都要提示；自动检查时这些情况保持安静。
 */
object UpdatePrompt {

    private var running = false

    fun check(activity: AppCompatActivity, manual: Boolean, force: Boolean = false) {
        if (running) return
        if (!AppUpdater.isConfigured(activity)) {
            if (manual) toast(activity, "这个安装包没有配置更新地址")
            return
        }
        if (!manual && !force && !AppUpdater.autoCheckDue(activity)) return
        running = true
        if (manual) toast(activity, "正在检查更新…")
        activity.lifecycleScope.launch {
            try {
                val info = AppUpdater.fetchNewer(activity)
                when {
                    info != null -> showAvailable(activity, info)
                    manual -> toast(activity, "已经是最新版本")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) toast(activity, "检查更新失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                running = false
            }
        }
    }

    private fun showAvailable(activity: AppCompatActivity, info: AppUpdater.UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        val sizeText = if (info.size > 0) "（%.1f MB）".format(info.size / 1024.0 / 1024.0) else ""
        val notes = info.notes.ifBlank { "（没有填写更新内容）" }
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 v${info.versionName}")
            .setMessage("$notes\n\n当前版本 v${currentName(activity)} $sizeText")
            .setPositiveButton("立即更新") { _, _ -> startDownload(activity, info) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun startDownload(activity: AppCompatActivity, info: AppUpdater.UpdateInfo) {
        if (!AppUpdater.canInstall(activity)) {
            AlertDialog.Builder(activity)
                .setTitle("需要允许安装")
                .setMessage("第一次更新需要允许「别错过站」安装应用。\n\n在接下来的页面打开开关，返回后再点一次「立即更新」。")
                .setPositiveButton("去打开") { _, _ ->
                    runCatching { activity.startActivity(AppUpdater.unknownSourcesSettingsIntent(activity)) }
                        .onFailure { toast(activity, "打不开设置页，请到系统设置里手动允许") }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val density = activity.resources.displayMetrics.density
        val label = TextView(activity).apply { text = "正在下载… 0%" }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(label)
            addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
        }
        var job: Job? = null
        val dialog = AlertDialog.Builder(activity)
            .setTitle("下载 v${info.versionName}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> job?.cancel() }
            .show()

        job = activity.lifecycleScope.launch {
            try {
                val apk = AppUpdater.download(activity, info) { percent ->
                    if (percent < 0) {
                        bar.isIndeterminate = true
                        label.text = "正在下载…"
                    } else {
                        bar.isIndeterminate = false
                        bar.progress = percent
                        label.text = "正在下载… $percent%"
                    }
                }
                dialog.dismiss()
                activity.startActivity(AppUpdater.installIntent(activity, apk))
            } catch (e: CancellationException) {
                dialog.dismiss()
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                if (!activity.isFinishing) {
                    AlertDialog.Builder(activity)
                        .setTitle("更新失败")
                        .setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton("重试") { _, _ -> startDownload(activity, info) }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
        }
    }

    private fun currentName(activity: AppCompatActivity): String = runCatching {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private fun toast(activity: AppCompatActivity, text: String) =
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
