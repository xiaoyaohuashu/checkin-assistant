package com.checkinassistant.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.checkinassistant.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内更新（v2.2.16）。
 *
 * 服务器上放一个 version.json 和对应的 APK（地址写在 local.properties 的 UPDATE_URL，
 * 不进公开仓库）。这里负责：拉 version.json → 比 versionCode → 下载 APK 到缓存 →
 * 校验 sha256、包名和版本号 → 交给系统安装器。所有包都是同一把 debug 签名，系统会直接覆盖安装。
 *
 * version.json 格式：
 * {"versionCode":38,"versionName":"2.2.16","apk":"checkin-assistant-v2.2.16.apk",
 *  "sha256":"…","size":14559740,"notes":"更新内容"}
 * apk 可以是相对 version.json 的文件名，也可以是完整 https 地址。
 */
object AppUpdater {

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val size: Long,
        val notes: String
    )

    private const val PREFS = "app_update"
    private const val KEY_LAST_CHECK = "last_check_at"
    private const val KEY_NOTIFIED_CODE = "notified_version_code"
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 15_000
    private const val UPDATE_DIR = "updates"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 编译时没配 UPDATE_URL 时为空，整套更新功能静默关闭。 */
    fun manifestUrl(context: Context): String =
        context.getString(R.string.update_manifest_url).trim()

    fun isConfigured(context: Context) = manifestUrl(context).startsWith("https://")

    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** 打开 App 时的自动检查：距上次检查不到 6 小时就跳过。 */
    fun autoCheckDue(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_CHECK, 0L) >= AUTO_CHECK_INTERVAL_MS

    /** 后台通知只对同一个版本发一次。 */
    fun alreadyNotified(context: Context, code: Long) = prefs(context).getLong(KEY_NOTIFIED_CODE, 0L) >= code
    fun markNotified(context: Context, code: Long) { prefs(context).edit().putLong(KEY_NOTIFIED_CODE, code).apply() }

    /** 返回比当前安装版本更新的版本；已是最新返回 null；网络或格式错误抛异常。 */
    suspend fun fetchNewer(context: Context, recordCheck: Boolean = true): UpdateInfo? = withContext(Dispatchers.IO) {
        val base = manifestUrl(context)
        require(base.startsWith("https://")) { "没有配置更新地址" }
        val conn = (URL(base).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
        }
        val body = try {
            if (conn.responseCode != 200) throw IOException("服务器返回 ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val json = JSONObject(body)
        val info = UpdateInfo(
            versionCode = json.getLong("versionCode"),
            versionName = json.getString("versionName"),
            apkUrl = URL(URL(base), json.getString("apk")).toString(),
            sha256 = json.getString("sha256").lowercase(),
            size = json.optLong("size", -1L),
            notes = json.optString("notes", "")
        )
        // 只有前台检查才记时间：后台任务查到了也要让下次打开 App 时弹出对话框
        if (recordCheck) prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        info.takeIf { it.versionCode > currentVersionCode(context) }
    }

    private fun apkFile(context: Context, info: UpdateInfo): File =
        File(File(context.cacheDir, UPDATE_DIR), "checkin-assistant-v${info.versionName}.apk")

    /**
     * 下载并校验。缓存里已有校验通过的同版本文件就直接用（比如第一次去开「安装未知应用」权限后回来再点）。
     * [onProgress] 参数为 0..100，大小未知时为 -1。
     */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val target = apkFile(context, info)
        if (target.exists() && sha256(target) == info.sha256) return@withContext target
        target.parentFile?.listFiles()?.forEach { it.delete() } // 只留当前这一个，旧包占空间
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS * 2
        }
        try {
            if (conn.responseCode != 200) throw IOException("下载失败，服务器返回 ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -2
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            withContext(Dispatchers.Main) { onProgress(percent) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val actual = sha256(part)
        if (actual != info.sha256) {
            part.delete()
            throw IOException("文件校验失败（下载不完整或被改动），请重试")
        }
        verifyPackage(context, part, info)
        if (!part.renameTo(target)) throw IOException("保存安装包失败")
        target
    }

    private fun verifyPackage(context: Context, file: File, info: UpdateInfo) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(file.absolutePath, 0)
            ?: run { file.delete(); throw IOException("安装包无法解析") }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        if (archive.packageName != context.packageName || code != info.versionCode) {
            file.delete()
            throw IOException("安装包与版本说明不符（${archive.packageName} / $code）")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 是否已允许本应用「安装未知应用」。 */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
