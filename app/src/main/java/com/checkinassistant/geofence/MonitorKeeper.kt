package com.checkinassistant.geofence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.checkinassistant.data.AppDatabase
import com.checkinassistant.data.DiagnosticRecordLogger
import java.util.concurrent.TimeUnit

/**
 * 常驻兜底（v2.2.14）。
 *
 * 10-26 的事故：所有提醒点完成后服务自停，只剩一个时段唤醒闹钟；进程随后被 LOW_MEMORY 杀掉，
 * 闹钟没有触发，第二天早上的提醒全没响。现在服务在有启用地点时常驻，这里再加一层：
 * WorkManager 每 15 分钟、进程每次启动、开机/升级/改时间时都来检查一次——
 * 前台服务在不在、下一次时段唤醒闹钟排没排，缺了就补上并写一条日志。
 */
internal object MonitorKeeper {
    private const val PREFERENCES_NAME = "monitor_keeper"
    private const val KEY_USER_STOPPED = "user_stopped"
    private const val KEY_WAKEUP_AT = "wakeup_at"
    private const val WORK_NAME = "monitor_keeper"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** 用户在首页手动停止监测时为 true；兜底不会把它拉起来。任何一次启动服务都会清掉它。 */
    fun setUserStopped(context: Context, stopped: Boolean) {
        prefs(context).edit().putBoolean(KEY_USER_STOPPED, stopped).commit()
    }

    fun userStopped(context: Context): Boolean = prefs(context).getBoolean(KEY_USER_STOPPED, false)

    /** 记录当前排着的时段唤醒闹钟时间；null = 已取消。只用于诊断「闹钟是不是丢了」。 */
    fun markWakeupScheduled(context: Context, at: Long?) {
        prefs(context).edit().apply {
            if (at == null) remove(KEY_WAKEUP_AT) else putLong(KEY_WAKEUP_AT, at)
        }.commit()
    }

    private fun recordedWakeupAt(context: Context): Long? =
        prefs(context).takeIf { it.contains(KEY_WAKEUP_AT) }?.getLong(KEY_WAKEUP_AT, 0L)

    fun schedulePeriodicCheck(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MonitorKeeperWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    /** 检查并恢复前台服务和时段唤醒闹钟。[source] 写进日志，说明是谁触发的检查。 */
    suspend fun ensure(context: Context, source: String) {
        val app = context.applicationContext
        if (userStopped(app)) {
            DiagnosticRecordLogger.trace("KEEPER_SKIP：来源=$source；用户已手动停止监测")
            return
        }
        val points = AppDatabase.get(app).checkInPointDao().enabledPoints()
        if (points.isEmpty()) return
        val repairs = mutableListOf<String>()
        val next = nextMonitoringChange(points, System.currentTimeMillis())
        if (next != null) {
            // 同一个 PendingIntent 重排只是覆盖，每次都排一遍是幂等的；记录对不上才算修复。
            val recorded = recordedWakeupAt(app)
            scheduleMonitoringReevaluation(app, next)
            if (recorded != next) repairs += "时段唤醒闹钟记录=${recorded ?: "无"}，应为=$next，已重新安排"
        }
        val wasRunning = GeofenceMonitoringService.isRunning
        if (!wasRunning) {
            val failure = GeofenceMonitoringService.tryStart(app)
            repairs += if (failure == null) "前台服务不在运行，已重新拉起"
            else "前台服务不在运行，重新拉起失败：${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
        }
        if (repairs.isNotEmpty()) {
            DiagnosticRecordLogger.geofence(app, "KEEPER_REPAIR：来源=$source；${repairs.joinToString("；")}")
        } else {
            DiagnosticRecordLogger.trace("KEEPER_OK：来源=$source；前台服务在运行，下一次时段唤醒=${next ?: "无"}")
        }
        if (wasRunning) PollWatchdog.check(app, source)
    }
}

/** WorkManager 周期任务（15 分钟）：跑一次 [MonitorKeeper.ensure]。 */
class MonitorKeeperWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            MonitorKeeper.ensure(applicationContext, "WORKMANAGER")
        } catch (error: Exception) {
            runCatching {
                DiagnosticRecordLogger.geofence(applicationContext, "KEEPER_ERROR：来源=WORKMANAGER；${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            }
        }
        return Result.success()
    }
}
