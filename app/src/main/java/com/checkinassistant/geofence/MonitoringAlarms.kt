package com.checkinassistant.geofence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.checkinassistant.system.AlarmClockScheduler

// 时段唤醒闹钟和轮询闹钟的排程 / 取消。原先和接收器、时间计算一起放在 MonitoringSchedule.kt 里，
// 这里只是拆出来，函数内容和先后顺序都没动。

/** 时段唤醒闹钟的 PendingIntent 编号和 action。系统按这两个值认闹钟，不能改。 */
private const val REQUEST_CODE_WINDOW_WAKEUP = 1002
private const val ACTION_POLL_WINDOW_WAKEUP = "com.checkinassistant.POLL_WINDOW_WAKEUP"
/** 轮询闹钟的 PendingIntent 编号：正常下一轮和 90 秒保底闹钟共用这一个，靠后排的覆盖先排的。 */
private const val REQUEST_CODE_POLL_TICK = 1003

internal fun monitoringScheduleIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    REQUEST_CODE_WINDOW_WAKEUP,
    Intent(context, MonitoringScheduleReceiver::class.java).setAction(ACTION_POLL_WINDOW_WAKEUP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

internal fun scheduleMonitoringReevaluation(context: Context, at: Long): Boolean {
    MonitorKeeper.markWakeupScheduled(context, at)
    val alarm = context.getSystemService(AlarmManager::class.java)
    val pendingIntent = monitoringScheduleIntent(context)
    // v2.2.11：与 25 秒定位复核闹钟一致，改用 setAlarmClock（系统里最高优先级的闹钟类别，
    // 不受批量对齐 / 后台投递限制）。operation 不变，故 cancelMonitoringReevaluation() 继续有效。
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
        if (AlarmClockScheduler.set(context, at, pendingIntent)) return true
    }
    return try {
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
        false
    } catch (_: SecurityException) {
        false
    }
}

internal const val ACTION_POLL_TICK = "com.checkinassistant.POLL_TICK"

/** 轮询闹钟的 PendingIntent：和时段唤醒闹钟（1002）分开，互不覆盖、各自取消。 */
internal fun pollTickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    REQUEST_CODE_POLL_TICK,
    Intent(context, MonitoringScheduleReceiver::class.java).setAction(ACTION_POLL_TICK),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

/**
 * 排下一轮轮询（v2.2.12）。优先 setAlarmClock（本项目已验证锁屏下准时）；
 * 没有「闹钟和提醒」权限或被系统拒绝时退化到 setAndAllowWhileIdle（可能被系统推后）。
 * [backup] = true 表示这是每轮开始时排的保底闹钟（v2.2.13）；它和正常的下一轮共用同一个 PendingIntent，
 * 所以本轮正常结束后再排一次就把它覆盖了。
 * @return true = 按闹钟类别排入。
 */
internal fun schedulePollTick(context: Context, at: Long, backup: Boolean = false): Boolean {
    // 先落盘「排着的是不是保底闹钟」再排：进程随后被杀，闹钟到的时候才认得出来。
// 【先落盘、再排闹钟】而且 PollWatchdog 里写配置一律用 commit()（同步写完才返回），不能换成 apply()：
// 进程可能在排完闹钟后立刻被杀，apply() 的异步写入来不及落盘，闹钟到的时候就认不出它是不是保底闹钟（v2.2.13）。
    PollWatchdog.markTickBackup(context, backup)
    val alarm = context.getSystemService(AlarmManager::class.java)
    val pendingIntent = pollTickIntent(context)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
        if (AlarmClockScheduler.set(context, at, pendingIntent)) return true
    }
    return try {
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
        false
    } catch (_: SecurityException) {
        false
    }
}

internal fun cancelPollTick(context: Context) {
    context.getSystemService(AlarmManager::class.java).cancel(pollTickIntent(context))
}

internal fun cancelMonitoringReevaluation(context: Context) {
    MonitorKeeper.markWakeupScheduled(context, null)
    context.getSystemService(AlarmManager::class.java).cancel(monitoringScheduleIntent(context))
}
