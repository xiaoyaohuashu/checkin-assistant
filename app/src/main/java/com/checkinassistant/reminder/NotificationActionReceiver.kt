package com.checkinassistant.reminder

import android.content.*
import com.checkinassistant.data.DiagnosticRecordLogger
import com.checkinassistant.geofence.PollWatchdog
import kotlinx.coroutines.*

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val started = android.os.SystemClock.elapsedRealtime()
        DiagnosticRecordLogger.trace("BC_ENTRY：NotificationActionReceiver")
// 这里没有像 ReminderAlarmReceiver 那样自己拿唤醒锁（现状，见待处理问题 19）。本次重构不补。
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
