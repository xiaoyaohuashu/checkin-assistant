package com.checkinassistant.reminder

import android.content.*
import com.checkinassistant.system.AlarmWakeLock
import com.checkinassistant.data.DiagnosticRecordLogger
import com.checkinassistant.geofence.PollWatchdog
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
// 【顺序不能动】取时间戳 → 写 logcat → goAsync → 开协程之前先拿锁 → 干完活先放锁、再 finish。
// 锁必须在开协程之前拿：onReceive 一返回，系统为这条广播持的锁就可能放掉，CPU 随时会睡（09-28 的记录）。
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
