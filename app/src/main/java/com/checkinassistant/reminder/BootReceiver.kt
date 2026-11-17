package com.checkinassistant.reminder

import android.content.*
import com.checkinassistant.data.AppDatabase
import com.checkinassistant.data.DiagnosticRecordLogger
import com.checkinassistant.domain.nextMonitoringChange
import com.checkinassistant.geofence.GeofenceMonitoringService
import com.checkinassistant.geofence.MonitorKeeper
import com.checkinassistant.geofence.cancelMonitoringReevaluation
import com.checkinassistant.geofence.scheduleMonitoringReevaluation
import kotlinx.coroutines.*

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val started = android.os.SystemClock.elapsedRealtime()
        DiagnosticRecordLogger.trace("BC_ENTRY：BootReceiver action=${intent.action ?: "<null>"}")
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
// restore 自己另开协程后立刻返回，和下面的步骤是同时进行的（现状）。这个接收器也没有自己拿唤醒锁（待处理问题 19）。
                ReminderEngine.restore(app)
                // v2.2.24（问题 1）：用户手动停止后，重启手机 / App 升级 / 改系统时间都不启动监测，也不排时段闹钟。
                // （上面的 restore 照常：停止前没响完的提醒，重启后仍会续上。）
                if (MonitorKeeper.userStopped(app)) {
                    cancelMonitoringReevaluation(app)
                    DiagnosticRecordLogger.geofence(app, "MONITOR_SYSTEM_EVENT：${intent.action ?: "<null>"}，用户已手动停止监测，不安排时段评估、不启动监测")
                    runCatching { MonitorKeeper.schedulePeriodicCheck(app) }
                    return@launch
                }
                val points = AppDatabase.get(app).checkInPointDao().enabledPoints()
                val now = System.currentTimeMillis()
                val nextChange = nextMonitoringChange(points, now)
                if (nextChange == null) cancelMonitoringReevaluation(app)
                else scheduleMonitoringReevaluation(app, nextChange)
                DiagnosticRecordLogger.geofence(
                    app,
                    "MONITOR_SYSTEM_EVENT：${intent.action ?: "<null>"}，已重新安排下一次时段评估=${nextChange ?: "无"}"
                )
                runCatching { MonitorKeeper.schedulePeriodicCheck(app) }
                // Starting the service also removes any fence left by a clock/time-zone change.
                GeofenceMonitoringService.tryStart(app)?.let { failure ->
                    DiagnosticRecordLogger.geofence(app, "MONITOR_SERVICE_START_FAILED：${intent.action ?: "<null>"} 后启动前台服务失败：${failure.javaClass.simpleName}: ${failure.message.orEmpty()}")
                }
            } catch (error: Exception) {
                DiagnosticRecordLogger.geofence(app, "MONITOR_SYSTEM_EVENT_FAILURE：${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            } finally {
                DiagnosticRecordLogger.trace("BC_EXIT：BootReceiver 占用广播链=${android.os.SystemClock.elapsedRealtime() - started}ms")
                pending.finish()
            }
        }
    }
}
