package com.checkinassistant.data

import android.content.Context
import android.util.Log
import java.util.Calendar

/**
 * Persists short-lived investigation evidence in the existing history screen.
 * `pointId = 0` is reserved for diagnostics and cannot collide with Room-generated points.
 */
object DiagnosticRecordLogger {
    private const val TAG = "GeofenceDiagnostic"
    const val PREFERENCES_NAME = "diagnostic_log_settings"
    const val KEY_RETENTION_DAYS = "retention_days"
    const val DEFAULT_RETENTION_DAYS = 30

    fun retentionDays(context: Context): Int = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
        .coerceIn(1, 3650)

    suspend fun clearExpired(context: Context): Int {
        val days = retentionDays(context)
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        return AppDatabase.get(context.applicationContext).recordDao()
            .deleteByStatusBefore(RecordStatus.DEBUG.name, cutoff)
    }

    /**
     * 只写 logcat、不落库：供同步上下文（BroadcastReceiver.onReceive）记录**精确时刻**。
     *
     * [geofence] 是 suspend 且要写 Room，只能在协程里调用；而「闹钟到底几点送达」
     * 必须在 onReceive 的一瞬间取时间，交给协程会混入调度延迟，无法区分
     * 「AlarmManager 迟到」与「协程派发迟到」。
     */
    fun trace(message: String) {
        Log.i(TAG, message)
    }

    suspend fun geofence(context: Context, message: String) {
        Log.i(TAG, message)
        clearExpired(context)
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        AppDatabase.get(context.applicationContext).recordDao().insert(
            CheckInRecord(
                pointId = 0,
                pointName = "【围栏调试】$message",
                triggeredAt = now,
                year = calendar.get(Calendar.YEAR),
                month = calendar.get(Calendar.MONTH) + 1,
                day = calendar.get(Calendar.DAY_OF_MONTH),
                weekday = calendar.get(Calendar.DAY_OF_WEEK),
                hour = calendar.get(Calendar.HOUR_OF_DAY),
                minute = calendar.get(Calendar.MINUTE),
                second = calendar.get(Calendar.SECOND),
                status = RecordStatus.DEBUG.name
            )
        )
    }
}
