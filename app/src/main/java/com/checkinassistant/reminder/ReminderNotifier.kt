package com.checkinassistant.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.checkinassistant.data.CheckInPoint
import com.checkinassistant.data.DiagnosticRecordLogger
import com.checkinassistant.domain.ReminderRules

/**
 * 到达提醒的通知：渠道、通知按钮、全屏页入口、发通知、清通知。
 * 从 ReminderEngine 原样搬出来，内容没动。响铃和振动不在这里，仍在 ReminderEngine。
 */
internal object ReminderNotifier {
    // Notification-channel importance is immutable once Android has created it.  Use a
    // new ID so devices that retained an older channel configuration start from HIGH.
    // v4 (2.2.18): 声音统一由 App 播放（耳机/外放切换），渠道静音。
    const val CHANNEL_ID = "checkin_reminders_v4"
    private val LEGACY_CHANNEL_IDS = listOf("checkin_reminders_v3")
    private const val ALERT_NOTIFICATION_TAG = "checkin_reminder_alert"
    /**
     * 通知按钮和全屏页的 PendingIntent 编号 = 点位 id 取模后加偏移，三者互不相撞。
     * 这些编号系统会记住，数值不能改。
     */
    private const val REQUEST_CODE_MODULUS = 1_000_000
    private const val REQUEST_CODE_COMPLETE_OFFSET = 1_000_000
    private const val REQUEST_CODE_SNOOZE_OFFSET = 2_000_000
    private const val REQUEST_CODE_FULLSCREEN_OFFSET = 3_000_000

    /** 发（或更新）这个点位的提醒通知。顺序：建渠道 → 两个按钮 → 全屏页 → 发通知 → 写日志。 */
    suspend fun post(context: Context, point: CheckInPoint, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java); createChannel(manager)
        val complete = actionPending(context, point.id, NotificationActionReceiver.ACTION_COMPLETE); val snooze = actionPending(context, point.id, NotificationActionReceiver.ACTION_SNOOZE)
        // v4 §2: full-screen intent — alarm-style popup on the lock screen. When the
        // USE_FULL_SCREEN_INTENT permission is missing (Android 14+ gate), the system
        // automatically falls back to this same notification as a heads-up banner.
        val fullscreen = PendingIntent.getActivity(
            context,
            ((point.id % REQUEST_CODE_MODULUS) + REQUEST_CODE_FULLSCREEN_OFFSET).toInt(),
            FullscreenReminderActivity.intent(context, point.id, point.name, count),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("到达${point.name},请确认").setContentText("提醒 $count/${ReminderRules.MAX_REMINDERS}").setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM).setFullScreenIntent(fullscreen, true).setAutoCancel(false).addAction(0, "已到达", complete).addAction(0, "稍后提醒", snooze).build()
        // A tag makes this ID independent of the foreground-service notification ID.
        // More importantly, both POLL_ENTER and GEOFENCE_ENTER reach this exact post.
        manager.notify(ALERT_NOTIFICATION_TAG, point.id.toInt(), notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = manager.getNotificationChannel(CHANNEL_ID)?.importance
            DiagnosticRecordLogger.geofence(context, "REMINDER_NOTIFICATION_POSTED：#${point.id} round=$count，channel=$CHANNEL_ID，effectiveImportance=$importance，priority=HIGH")
        }
    }

    /** 把这个点位的通知从通知栏清掉（旧版不带 tag 的和现在带 tag 的都清）。只在用户明确操作时调用。 */
    fun dismiss(context: Context, id: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(id.toInt())
        manager.cancel(ALERT_NOTIFICATION_TAG, id.toInt())
    }

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        // 渠道本身不响不振：声音由 AlertSoundPlayer 播放，振动由 startAlert 负责。
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "到达提醒", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(false); setSound(null, null) })
        LEGACY_CHANNEL_IDS.forEach { runCatching { manager.deleteNotificationChannel(it) } }
    }
    private fun actionPending(context: Context, id: Long, action: String) = PendingIntent.getBroadcast(context, ((id % REQUEST_CODE_MODULUS) + if (action == NotificationActionReceiver.ACTION_COMPLETE) REQUEST_CODE_COMPLETE_OFFSET else REQUEST_CODE_SNOOZE_OFFSET).toInt(), Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(ReminderEngine.EXTRA_POINT_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
