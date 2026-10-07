package com.checkinassistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * `setAlarmClock()` 排程封装（窗口唤醒闹钟与 25 秒定位复核闹钟共用）。
 *
 * `setAlarmClock` 是系统里优先级最高的闹钟类别，会显示在状态栏 / 锁屏的「下一个闹钟」，
 * 不受批量对齐与后台投递限制。本次用它替换 `setExactAndAllowWhileIdle()`，用来验证真机上
 * 「25 秒复核闹钟被推后 18.76s / 231.9s」是否只是投递策略问题。
 *
 * **取消逻辑无需改动**：触发用的 `operation` 仍是原来那个 PendingIntent，
 * `AlarmManager.cancel(operation)` 按 PendingIntent 的 filterEquals 匹配，与当初用哪个
 * set* 方法排入无关 —— 因此 `ReminderEngine.cancel()` 与 `cancelMonitoringReevaluation()`
 * 继续能取消掉这两条闹钟。
 */
internal object AlarmClockScheduler {

    /** 状态栏「下一个闹钟」被点击时打开的界面 = App 主界面。 */
    private const val SHOW_REQUEST_CODE = 2001

    private fun showIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        SHOW_REQUEST_CODE,
        // 系统 UI 不是 Activity，必须带 NEW_TASK；CLEAR_TOP 避免主界面被重复叠栈。
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * @return true = 已按闹钟类别排入；false = 系统拒绝（缺少「闹钟和提醒」权限或厂商限制），
     *         由调用方退化到 `setAndAllowWhileIdle`。
     */
    fun set(context: Context, triggerAt: Long, operation: PendingIntent): Boolean = try {
        context.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent(context)), operation)
        true
    } catch (_: SecurityException) {
        false
    }
}
