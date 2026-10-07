package com.checkinassistant.geofence

import android.content.*
import com.checkinassistant.AlarmWakeLock
import com.checkinassistant.data.*
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofenceStatusCodes
import kotlinx.coroutines.*

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // This is deliberately the first persisted operation.  Its presence in history proves
        // that Google Play services / Android delivered the PendingIntent to this receiver.
        val pending = goAsync()
        val app = context.applicationContext
        // 广播链占用排查：后台广播队列对同一进程是串行处理的，goAsync 未 finish 前会一直占用。
        // 这里记录本条广播占用广播链的时长，用于核对闹钟广播是否排在它后面。
        val started = android.os.SystemClock.elapsedRealtime()
        DiagnosticRecordLogger.trace("BC_ENTRY：GeofenceBroadcastReceiver action=${intent.action ?: "<null>"}")
        // 与 ReminderAlarmReceiver 同理：进入围栏后的待确认写入与后续 25 秒复核都要
        // 保证 CPU 不在这段时间里挂起，否则整条链路会被推迟到下一次系统唤醒。
        val wakeLock = AlarmWakeLock.acquire(app, "GeofenceEvent")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DiagnosticRecordLogger.geofence(
                    app,
                    "GEOFENCE_EVENT_RECEIVED：收到广播（action=${intent.action ?: "<null>"}）"
                )
                runCatching { PollWatchdog.check(app, "围栏广播") }

                val event = GeofencingEvent.fromIntent(intent)
                if (event == null) {
                    DiagnosticRecordLogger.geofence(app, "GEOFENCE_EVENT_PARSE_NULL：GeofencingEvent.fromIntent() 返回 null")
                    return@launch
                }
                if (event.hasError()) {
                    DiagnosticRecordLogger.geofence(
                        app,
                        "GEOFENCE_EVENT_ERROR：errorCode=${event.errorCode} (${GeofenceStatusCodes.getStatusCodeString(event.errorCode)})"
                    )
                    return@launch
                }

                val transition = event.geofenceTransition
                val requestIds = event.triggeringGeofences?.map { it.requestId }.orEmpty()
                DiagnosticRecordLogger.geofence(
                    app,
                    "GEOFENCE_EVENT_PARSED：transition=${transitionName(transition)}($transition)，围栏ID=${requestIds.joinToString().ifBlank { "<none>" }}"
                )
                if (transition !in setOf(Geofence.GEOFENCE_TRANSITION_ENTER, Geofence.GEOFENCE_TRANSITION_EXIT)) {
                    DiagnosticRecordLogger.geofence(app, "GEOFENCE_EVENT_IGNORED：不支持的 transition=$transition")
                    return@launch
                }

                val ids = requestIds.mapNotNull { requestId ->
                    requestId.toLongOrNull() ?: run {
                        DiagnosticRecordLogger.geofence(app, "GEOFENCE_EVENT_BAD_ID：无法转换 requestId=$requestId")
                        null
                    }
                }
                if (ids.isEmpty()) {
                    DiagnosticRecordLogger.geofence(app, "GEOFENCE_EVENT_NO_VALID_FENCE：没有可处理的围栏 ID")
                    return@launch
                }
                ids.forEach { id -> handle(app, id, transition) }
            } catch (error: Exception) {
                DiagnosticRecordLogger.geofence(
                    app,
                    "GEOFENCE_EVENT_EXCEPTION：${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                )
            } finally {
                DiagnosticRecordLogger.trace("BC_EXIT：GeofenceBroadcastReceiver 占用广播链=${android.os.SystemClock.elapsedRealtime() - started}ms")
                AlarmWakeLock.release(wakeLock)
                pending.finish()
            }
        }
    }

    private fun transitionName(transition: Int) = when (transition) {
        Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
        Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
        Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
        else -> "UNKNOWN"
    }

    private suspend fun handle(context: Context, id: Long, transition: Int) {
        val dao = AppDatabase.get(context).checkInPointDao()
        val point = dao.get(id)
        if (point == null) {
            DiagnosticRecordLogger.geofence(context, "GEOFENCE_EVENT_POINT_MISSING：ID=$id 在数据库中不存在")
            return
        }
        when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceTriggerCoordinator.enter(context, id, "GEOFENCE_ENTER")
            Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceTriggerCoordinator.exit(context, id, "GEOFENCE_EXIT")
        }
    }
}
