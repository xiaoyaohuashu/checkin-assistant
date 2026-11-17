package com.checkinassistant

import android.content.Context
import com.checkinassistant.data.AppDatabase
import com.checkinassistant.data.CheckInPoint
import com.checkinassistant.data.DiagnosticRecordLogger
import com.checkinassistant.data.PointEnabledEvent
import com.checkinassistant.data.PointSkipEvent
import com.checkinassistant.data.RecordStatus
import com.checkinassistant.domain.PointCleanup
import com.checkinassistant.domain.PointRules
import com.checkinassistant.geofence.GeofenceMonitoringService
import com.checkinassistant.reminder.ReminderEngine

/**
 * 首页对一个提醒点做的五种操作的「后台部分」：今天跳过、确认已处理、恢复、启用/禁用、删除（删除是 v2.2.24 加进来的）。
 * 原先直接写在 MainActivity 里，这里只是搬出来：每个函数里的语句和先后顺序都没动。
 *
 * 约定：
 * - 这里不自己开协程，仍由 MainActivity 用 lifecycleScope 调用（页面销毁时一起取消，和原来一样）；
 * - 弹提示（Toast）留在 MainActivity，在这些函数返回之后弹，先后和原来一致；
 * - 用的是界面上稍早读到的那份提醒点数据整条写回（待处理问题 23，本次不修）。
 */
internal object PointActions {
    /** 今天跳过（按星期 / 每天 / 只提醒一次都适用）；同时记一条跳过事件给历史页统计用。 */
    suspend fun skipToday(context: Context, point: CheckInPoint) {
        val today = java.time.LocalDate.now()
        val updated = PointRules.onSkipToday(point, today)
        AppDatabase.get(context).checkInPointDao().update(updated)
        AppDatabase.get(context).pointSkipEventDao().insert(
            PointSkipEvent(reminderPointId = point.id, skipDate = today.toString(), createdAt = System.currentTimeMillis())
        )
        // 被跳过的点必须马上停：先取消它的提醒和通知，再让后台监测重新安排。
        ReminderEngine.cancel(context, point.id)
        ReminderEngine.dismissNotifications(context, point.id)
        AppDatabase.get(context).reminderDao().deactivate(point.id)
        GeofenceMonitoringService.start(context)
        DiagnosticRecordLogger.geofence(context, "POINT_SKIP：#${point.id} ${point.name} 跳过 ${today}，已写入跳过事件")
    }

    /** 确认已处理：把最近一条「未理会」的记录改成「已完成」。提醒点本身不变。 */
    suspend fun acknowledge(context: Context, point: CheckInPoint) {
        val recordDao = AppDatabase.get(context).recordDao()
        recordDao.latestForPoint(point.id)?.let { record ->
            if (record.status == RecordStatus.IGNORED.name) {
                recordDao.update(record.copy(status = RecordStatus.COMPLETED.name, actionAt = System.currentTimeMillis()))
            }
        }
        // Badge flips to "已完成" via the records flow; the point itself stays as-is.
        DiagnosticRecordLogger.geofence(context, "POINT_ACKNOWLEDGE：#${point.id} ${point.name} 已到达未确认 → 已完成")
    }

    /**
     * 恢复一次性提醒点（长按菜单「恢复」，v2.2.24 接上，问题 2）：日期不变，回到待提醒并重新启用。
     * 和启用/禁用一样，先把它残留的提醒清干净（比如三轮没理后点过「稍后提醒」留下的那次），再让后台监测重新安排。
     */
    suspend fun restore(context: Context, point: CheckInPoint) {
        val updated = PointRules.restoreOnce(point)
        AppDatabase.get(context).checkInPointDao().update(updated)
        ReminderEngine.cancel(context, point.id)
        ReminderEngine.dismissNotifications(context, point.id)
        AppDatabase.get(context).reminderDao().deactivate(point.id)
        GeofenceMonitoringService.start(context)
        DiagnosticRecordLogger.geofence(context, "POINT_RESTORE：#${point.id} ${point.name} onceStatus=${point.onceStatus}→PENDING，已重新启用，日期保持 ${point.onceDate}")
    }

    /** 启用 / 禁用。禁用的点必须马上停：取消提醒、清通知，再让后台监测重新安排。 */
    suspend fun toggle(context: Context, point: CheckInPoint, enabled: Boolean) {
        // v4 §1.3 改动点4: re-enabling a COMPLETED once-point re-arms it (date unchanged).
        if (enabled && point.repeatMode == "ONCE" && point.onceStatus == "COMPLETED") {
            AppDatabase.get(context).checkInPointDao().update(PointRules.rearmOnce(point).copy(enabled = true))
        } else {
            AppDatabase.get(context).checkInPointDao().setEnabledAndRearm(point.id, enabled)
        }
        AppDatabase.get(context).pointEnabledEventDao()
            .insert(PointEnabledEvent(pointId = point.id, atMillis = System.currentTimeMillis(), enabled = enabled))
        ReminderEngine.cancel(context, point.id)
        ReminderEngine.dismissNotifications(context, point.id)
        AppDatabase.get(context).reminderDao().deactivate(point.id)
        GeofenceMonitoringService.start(context)
        DiagnosticRecordLogger.geofence(context, "POINT_TOGGLE：#${point.id} ${point.name} enabled=$enabled，已取消提醒并重新注册围栏")
    }

    /**
     * 删除提醒点（v2.2.24，问题 22）：除了删掉这一行，还要停掉它正在响的提醒、清掉通知、取消它排着的所有闹钟。
     * 做哪几步、先后顺序见 PointCleanup。历史记录不跟着删（和原来一样）。
     */
    suspend fun delete(context: Context, point: CheckInPoint) {
        PointCleanup.deletePoint(
            deleteRow = { AppDatabase.get(context).checkInPointDao().delete(point) },
            cancelAlarmsAndAlert = { ReminderEngine.cancel(context, point.id) },
            dismissNotifications = { ReminderEngine.dismissNotifications(context, point.id) },
            deactivateReminder = { AppDatabase.get(context).reminderDao().deactivate(point.id) },
            reevaluateMonitoring = { GeofenceMonitoringService.start(context) },
        )
        DiagnosticRecordLogger.geofence(context, "POINT_DELETE：#${point.id} ${point.name} 已删除，并取消它的提醒、通知和闹钟")
    }
}
