package com.checkinassistant.geofence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.checkinassistant.AlarmClockScheduler
import com.checkinassistant.data.CheckInPoint
import com.checkinassistant.data.DiagnosticRecordLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

internal const val WINDOW_WARMUP_MS = 2 * 60_000L

internal fun CheckInPoint.isActiveAtMillis(timeMillis: Long): Boolean {
    val start = activeStartMinute ?: return true
    val end = activeEndMinute ?: return true
    val calendar = Calendar.getInstance().apply { timeInMillis = timeMillis }
    val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    return if (start < end) minute in start until end else minute >= start || minute < end
}

/** The next time this point changes from inactive to active, assuming it is currently inactive. */
private fun CheckInPoint.nextStartAfter(now: Long): Long? {
    // Spec §5: delegate day-level eligibility (weekday mask / skip date / ONCE date)
    // to the single PointRules.isActiveDay implementation via findNextActiveTime.
    return com.checkinassistant.reminder.PointRules.findNextActiveTime(this, now)
}

/** Earliest future point at which the active/inactive point set needs recalculating. */
internal fun nextMonitoringChange(points: List<CheckInPoint>, now: Long): Long? {
    return points.mapNotNull { point ->
        point.activeStartMinute ?: return@mapNotNull null
        val end = point.activeEndMinute ?: return@mapNotNull null
        if (!point.isActiveAtMillis(now)) {
            // When the pre-warm alarm itself fires, `start - WINDOW_WARMUP_MS` is already
            // in the past.  Re-scheduling that past instant used to skip the whole window
            // and select tomorrow.  In that small gap, wake at the actual start instead.
            point.nextStartAfter(now)?.let { startAt ->
                (startAt - WINDOW_WARMUP_MS).takeIf { it > now } ?: startAt
            }
        } else {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, end / 60)
                set(Calendar.MINUTE, end % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (calendar.timeInMillis <= now) calendar.add(Calendar.DAY_OF_YEAR, 1)
            calendar.timeInMillis
        }
    }.filter { it > now }.minOrNull()
}

class MonitoringScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action == ACTION_POLL_TICK) {
            // 轮询闹钟（v2.2.12）：同步持锁并把这一轮交给前台服务，不在这里做任何异步工作。
            DiagnosticRecordLogger.trace("BC_ENTRY：MonitoringScheduleReceiver POLL_TICK")
            val failure = GeofenceMonitoringService.startPollTick(app) ?: return
            val pendingFailure = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    DiagnosticRecordLogger.geofence(app, "POLL_TICK_START_FAILURE：轮询闹钟到了但没能启动前台服务：${failure.javaClass.simpleName}: ${failure.message.orEmpty()}")
                } finally {
                    pendingFailure.finish()
                }
            }
            return
        }
        val pending = goAsync()
        // 广播链占用排查（同 GeofenceBroadcastReceiver）
        val started = android.os.SystemClock.elapsedRealtime()
        DiagnosticRecordLogger.trace("BC_ENTRY：MonitoringScheduleReceiver")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DiagnosticRecordLogger.geofence(app, "POLL_WINDOW_WAKEUP：收到时间段调度唤醒，重新评估主动定位轮询")
                GeofenceMonitoringService.start(app)
            } catch (error: Exception) {
                DiagnosticRecordLogger.geofence(app, "POLL_WINDOW_WAKEUP_FAILURE：${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            } finally {
                DiagnosticRecordLogger.trace("BC_EXIT：MonitoringScheduleReceiver 占用广播链=${android.os.SystemClock.elapsedRealtime() - started}ms")
                pending.finish()
            }
        }
    }
}

internal fun monitoringScheduleIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    1002,
    Intent(context, MonitoringScheduleReceiver::class.java).setAction("com.checkinassistant.POLL_WINDOW_WAKEUP"),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

internal fun scheduleMonitoringReevaluation(context: Context, at: Long): Boolean {
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
    1003,
    Intent(context, MonitoringScheduleReceiver::class.java).setAction(ACTION_POLL_TICK),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

/**
 * 排下一轮轮询（v2.2.12）。优先 setAlarmClock（本项目已验证锁屏下准时）；
 * 没有「闹钟和提醒」权限或被系统拒绝时退化到 setAndAllowWhileIdle（可能被系统推后）。
 * @return true = 按闹钟类别排入。
 */
internal fun schedulePollTick(context: Context, at: Long): Boolean {
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
    context.getSystemService(AlarmManager::class.java).cancel(monitoringScheduleIntent(context))
}
