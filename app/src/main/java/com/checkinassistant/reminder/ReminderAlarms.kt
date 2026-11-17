package com.checkinassistant.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 提醒用到的三条闹钟（下一轮提醒 / 停止响铃 / 25 秒定位复核）的 PendingIntent、排程和取消。
 * 从 ReminderEngine 原样搬出来，内容没动。
 *
 * 注意：三条闹钟用同一个编号（点位 id），靠 action 区分；这些 action 和编号系统会记住，不能改。
 */
internal object ReminderAlarms {
    const val ACTION_ALARM = "com.checkinassistant.REMINDER_ALARM"
    const val ACTION_STOP_ALERT = "com.checkinassistant.STOP_REMINDER_ALERT"
    const val ACTION_LOCATION_CONFIRM = "com.checkinassistant.LOCATION_CONFIRM"

    fun schedule(context: Context, id: Long, at: Long) { val alarm = context.getSystemService(AlarmManager::class.java); alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context, id)) }
    // 未使用（保留不删）。v2.2.24 的「第 3 轮响满 30 秒」靠的是多排一次提醒闹钟，没有用它。
    @Suppress("unused")
    private fun scheduleAlertStop(context: Context, id: Long, at: Long) { context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, stopAlertIntent(context, id)) }

    /**
     * 取消这个点位的三条闹钟。
     * 第三条用 expectedAt = 0 重新构造 PendingIntent：带 FLAG_UPDATE_CURRENT，会顺带改写已排闹钟里的附加数据，
     * 这个副作用是现状，保持，不要换成 FLAG_NO_CREATE。
     */
    fun cancelAlarms(context: Context, id: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(alarmIntent(context, id)); alarm.cancel(stopAlertIntent(context, id)); alarm.cancel(locationConfirmationIntent(context, id, 0))
    }

    private fun alarmIntent(context: Context, id: Long) = PendingIntent.getBroadcast(context, (id % Int.MAX_VALUE).toInt(), Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_ALARM).putExtra(ReminderEngine.EXTRA_POINT_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun stopAlertIntent(context: Context, id: Long) = PendingIntent.getBroadcast(context, (id % Int.MAX_VALUE).toInt(), Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_STOP_ALERT).putExtra(ReminderEngine.EXTRA_POINT_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun locationConfirmationIntent(
        context: Context, id: Long, expectedAt: Long, setElapsed: Long = -1L
    ) = PendingIntent.getBroadcast(
        context,
        (id % Int.MAX_VALUE).toInt(),
        Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ACTION_LOCATION_CONFIRM)
            .putExtra(ReminderEngine.EXTRA_POINT_ID, id)
            .putExtra(ReminderEngine.EXTRA_EXPECTED_AT, expectedAt)
            .putExtra(ReminderEngine.EXTRA_SET_ELAPSED, setElapsed),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
