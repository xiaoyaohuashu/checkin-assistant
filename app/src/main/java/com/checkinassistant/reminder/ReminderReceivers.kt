package com.checkinassistant.reminder

import android.content.*
import com.checkinassistant.AlarmWakeLock
import com.checkinassistant.data.AppDatabase
import com.checkinassistant.data.DiagnosticRecordLogger
import com.checkinassistant.geofence.GeofenceMonitoringService
import com.checkinassistant.geofence.PollWatchdog
import com.checkinassistant.geofence.cancelMonitoringReevaluation
import com.checkinassistant.geofence.nextMonitoringChange
import com.checkinassistant.geofence.scheduleMonitoringReevaluation
import kotlinx.coroutines.*

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 复核延迟排查：在 onReceive 的**第一瞬间**（尚未派发到协程）取时间，用来区分
        // 「AlarmManager 本身迟到」与「goAsync + 协程派发迟到」。这两者此前无法区分。
        val receivedWall = System.currentTimeMillis()
        val receivedElapsed = android.os.SystemClock.elapsedRealtime()
        val id = intent.getLongExtra(ReminderEngine.EXTRA_POINT_ID, -1)
        val expectedAt = intent.getLongExtra(ReminderEngine.EXTRA_EXPECTED_AT, -1L)
        val setElapsed = intent.getLongExtra(ReminderEngine.EXTRA_SET_ELAPSED, -1L)
        // 送达时刻的电源状态（排程时采样的 Doze=false 不能代表这 25 秒里的真实状态）
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val idleAtDelivery = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && power.isDeviceIdleMode
        val interactive = power.isInteractive
        val powerSave = power.isPowerSaveMode
        val entryMsg = "ALARM_RECEIVED：pointId=$id，action=${intent.action ?: "<null>"}，" +
            "receivedAt=$receivedWall，receivedElapsed=$receivedElapsed，expectedAt=$expectedAt，" +
            "墙钟送达延迟=${if (expectedAt > 0) receivedWall - expectedAt else -1}ms，" +
            "排程elapsed=$setElapsed，真实经过(elapsed增量)=${if (setElapsed > 0) receivedElapsed - setElapsed else -1}ms，" +
            "送达时Doze=$idleAtDelivery，屏幕交互=$interactive，省电模式=$powerSave"
        DiagnosticRecordLogger.trace(entryMsg)
        val pending = goAsync()
        val app = context.applicationContext
        // 复核延迟修复：goAsync 不保证 CPU 唤醒，定位复核里的 getCurrentLocation()
        // 可能随 CPU 挂起一起停住（历史上晚约 3m34s）。这里显式持锁直到工作结束。
        val wakeLock = AlarmWakeLock.acquire(app, "ReminderAlarm")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DiagnosticRecordLogger.geofence(app, entryMsg)
                runCatching { PollWatchdog.check(app, "提醒/复核闹钟 ${intent.action ?: "<null>"}") }
                if (id >= 0) ReminderEngine.onAlarm(app, id, intent.action, expectedAt)
            } finally {
                AlarmWakeLock.release(wakeLock)
                pending.finish()
            }
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val started = android.os.SystemClock.elapsedRealtime()
        DiagnosticRecordLogger.trace("BC_ENTRY：NotificationActionReceiver")
        val pending = goAsync(); val id = intent.getLongExtra(ReminderEngine.EXTRA_POINT_ID, -1)
        CoroutineScope(Dispatchers.IO).launch {
            try { runCatching { PollWatchdog.check(context.applicationContext, "通知按钮") }; if (id >= 0) when (intent.action) { ACTION_COMPLETE -> ReminderEngine.complete(context.applicationContext, id); ACTION_SNOOZE -> ReminderEngine.snooze(context.applicationContext, id) } }
            finally {
                DiagnosticRecordLogger.trace("BC_EXIT：NotificationActionReceiver 占用广播链=${android.os.SystemClock.elapsedRealtime() - started}ms")
                pending.finish()
            }
        }
    }
    companion object { const val ACTION_COMPLETE = "com.checkinassistant.COMPLETE"; const val ACTION_SNOOZE = "com.checkinassistant.SNOOZE" }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val started = android.os.SystemClock.elapsedRealtime()
        DiagnosticRecordLogger.trace("BC_ENTRY：BootReceiver action=${intent.action ?: "<null>"}")
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderEngine.restore(app)
                val points = AppDatabase.get(app).checkInPointDao().enabledPoints()
                val now = System.currentTimeMillis()
                val nextChange = nextMonitoringChange(points, now)
                if (nextChange == null) cancelMonitoringReevaluation(app)
                else scheduleMonitoringReevaluation(app, nextChange)
                DiagnosticRecordLogger.geofence(
                    app,
                    "MONITOR_SYSTEM_EVENT：${intent.action ?: "<null>"}，已重新安排下一次时段评估=${nextChange ?: "无"}"
                )
                // Starting the service also removes any fence left by a clock/time-zone change.
                GeofenceMonitoringService.start(app)
            } catch (error: Exception) {
                DiagnosticRecordLogger.geofence(app, "MONITOR_SYSTEM_EVENT_FAILURE：${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            } finally {
                DiagnosticRecordLogger.trace("BC_EXIT：BootReceiver 占用广播链=${android.os.SystemClock.elapsedRealtime() - started}ms")
                pending.finish()
            }
        }
    }
}
