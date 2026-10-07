package com.checkinassistant.geofence

import android.content.Context
import com.checkinassistant.data.AppDatabase
import com.checkinassistant.data.DiagnosticRecordLogger
import com.checkinassistant.data.PointState
import com.checkinassistant.reminder.ReminderEngine

/** Makes passive geofence and active-polling transitions share one atomic entry path. */
object GeofenceTriggerCoordinator {
    suspend fun enter(context: Context, pointId: Long, source: String) {
        val dao = AppDatabase.get(context).checkInPointDao()
        val point = dao.get(pointId)
        if (point == null) {
            DiagnosticRecordLogger.geofence(context, "$source：点位 #$pointId 已不存在")
            return
        }
        if (!point.enabled) {
            DiagnosticRecordLogger.geofence(context, "$source：#${point.id} ${point.name} 已禁用，忽略进入")
            return
        }
        if (!point.isActiveAtMillis(System.currentTimeMillis())) {
            DiagnosticRecordLogger.geofence(context, "$source：#${point.id} ${point.name} 不在生效时间段，忽略进入")
            return
        }
        // Spec §4.5: day-level eligibility (weekday/skip/repeat-mode) + window dedup
        // live in PointRules.isActiveNow — the single filtering implementation.
        val now = System.currentTimeMillis()
        if (!com.checkinassistant.reminder.PointRules.isActiveNow(point, now)) {
            DiagnosticRecordLogger.geofence(context, "$source：#${point.id} ${point.name} 今日不生效或窗口已提醒过，忽略进入")
            return
        }
        val enteredAt = System.currentTimeMillis()
        if (dao.markEnteredIfArmed(pointId, enteredAt) != 1) {
            DiagnosticRecordLogger.geofence(context, "$source：#${point.id} ${point.name} 当前状态=${dao.get(pointId)?.triggerState ?: "已删除"}，忽略重复进入")
            return
        }
        if (ReminderEngine.scheduleLocationConfirmation(context, pointId)) {
            DiagnosticRecordLogger.geofence(context, "$source：#${point.id} ${point.name}，已进入待确认状态并安排 25 秒后定位复核")
        } else {
            // 完全排不上闹钟：不能再像旧实现那样立刻 rearmIfEntered() —— 那会把
            // ENTERED/enteredAt 抹掉，前台轮询的兜底复核（POLL_CONFIRM_FALLBACK）
            // 就再也不会触发，只剩等系统维护窗口一条路。这里保留待确认状态，
            // 并确保前台监测服务在跑，由轮询在下一个周期完成复核。
            GeofenceMonitoringService.start(context)
            DiagnosticRecordLogger.geofence(context, "$source：#${point.id} ${point.name}，未能安排复核闹钟，保留待确认状态并启动前台轮询兜底复核")
        }
    }

    suspend fun exit(context: Context, pointId: Long, source: String) {
        val dao = AppDatabase.get(context).checkInPointDao()
        val point = dao.get(pointId) ?: return
        if (point.triggerState == PointState.ARMED.name) return
        ReminderEngine.cancelForExit(context, pointId)
        // Do not write back a stale entity here: a location poll and a passive geofence
        // callback can arrive together.  This conditional SQL update only changes state.
        if (dao.rearmAfterExit(pointId) == 1) {
            DiagnosticRecordLogger.geofence(context, "$source：#${point.id} ${point.name}，已取消提醒并重新设防")
        }
    }
}
