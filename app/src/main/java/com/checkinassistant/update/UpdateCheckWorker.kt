package com.checkinassistant.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.checkinassistant.MainActivity
import java.util.concurrent.TimeUnit

/**
 * 后台每 12 小时查一次新版本（有网络时），发现新版发一条通知；同一版本只通知一次。
 * 点通知打开首页并弹出更新对话框。
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!AppUpdater.isConfigured(app)) return Result.success()
        val info = runCatching { AppUpdater.fetchNewer(app, recordCheck = false) }.getOrElse { return Result.success() }
            ?: return Result.success()
        if (AppUpdater.alreadyNotified(app, info.versionCode)) return Result.success()
        if (notify(app, info)) AppUpdater.markNotified(app, info.versionCode)
        return Result.success()
    }

    private fun notify(context: Context, info: AppUpdater.UpdateInfo): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "版本更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "有新版本时提醒一次"
            }
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SHOW_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = info.notes.lineSequence().firstOrNull { it.isNotBlank() } ?: "点这里下载安装"
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("别错过站有新版本 v${info.versionName}")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(info.notes.ifBlank { "点这里下载安装" }))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
        return true
    }

    companion object {
        const val EXTRA_SHOW_UPDATE = "com.checkinassistant.SHOW_UPDATE"
        private const val CHANNEL_ID = "app_update_v1"
        private const val NOTIFICATION_ID = 7301
        private const val WORK_NAME = "update_check"

        fun schedule(context: Context) {
            if (!AppUpdater.isConfigured(context)) return
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }

        fun cancelNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }
}
