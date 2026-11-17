package com.checkinassistant.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.checkinassistant.data.DiagnosticRecordLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MonitoringScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action == ACTION_POLL_TICK) {
            // 轮询闹钟（v2.2.12）：同步持锁并把这一轮交给前台服务，不在这里做任何异步工作。
            DiagnosticRecordLogger.trace("BC_ENTRY：MonitoringScheduleReceiver POLL_TICK")
// 轮询闹钟这条路是同步的：成功时直接返回，不调 goAsync、不开协程。锁在 startPollTick 里拿，交给服务后由服务释放。
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
                // v2.2.24（问题 1）：用户手动停止后，时段闹钟到点不启动监测。
                if (MonitorKeeper.userStopped(app)) {
                    DiagnosticRecordLogger.geofence(app, "POLL_WINDOW_WAKEUP_REFUSED：用户已手动停止监测，时段闹钟到点不启动监测")
                    return@launch
                }
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
