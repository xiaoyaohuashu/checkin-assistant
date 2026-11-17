package com.checkinassistant.map

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.checkinassistant.MainActivity
import com.checkinassistant.domain.LocationBlock
import com.checkinassistant.domain.LocationSourceRules

/**
 * v2.2.26（中 5）：后台需要用高德定位、但还没同意高德隐私说明时，发一条通知提醒打开 App 同意，不悄悄失败。
 * 单独的通知渠道（不碰提醒的渠道），第一次真正要发通知时才创建——用谷歌定位的手机上不会出现这个渠道。
 * 最多每 24 小时一条。这里不碰任何高德类。
 */
internal object AmapNotice {
    const val EXTRA_ASK_PRIVACY = "com.checkinassistant.extra.ASK_AMAP_PRIVACY"
    private const val CHANNEL_ID = "amap_notice_v1"
    private const val NOTIFICATION_ID = 7401

    fun maybeNotify(context: Context, block: LocationBlock) {
        if (block != LocationBlock.AMAP_PRIVACY_NEEDED) return
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (!LocationSourceRules.shouldNotify(MapSettings.amapNoticeAt(app), now)) return
        // Android 13+ 没给通知权限：发不出去，也不记时间；这时只有首页卡片能提示
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val manager = app.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "高德定位提示", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "需要同意高德隐私说明才能定位时提醒"
                }
            )
            val open = PendingIntent.getActivity(
                app, NOTIFICATION_ID,
                Intent(app, MainActivity::class.java)
                    .putExtra(EXTRA_ASK_PRIVACY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("别错过站：后台定位需要你同意高德隐私说明")
                    .setContentText("还没同意，高德定位用不了。点这里打开 App 同意。")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("还没同意高德隐私说明，高德定位用不了，到站可能不会提醒。点这里打开 App 同意。"))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
            MapSettings.setAmapNoticeAt(app, now)
        } catch (e: Exception) {
            // 通知发不出去不能影响取定位
        }
    }

    fun cancel(context: Context) {
        runCatching { context.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }
}
