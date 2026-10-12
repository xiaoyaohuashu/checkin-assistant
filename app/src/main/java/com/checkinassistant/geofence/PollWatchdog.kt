package com.checkinassistant.geofence

import android.content.Context
import com.checkinassistant.data.AppDatabase
import com.checkinassistant.data.DiagnosticRecordLogger
import com.checkinassistant.reminder.PointRules

/**
 * 轮询看门狗（v2.2.12）。
 *
 * v2.2.11 真机上出现过「最后一轮 POLL_CYCLE 之后轮询静默停止，没有 POLL_STOP，也没有服务重启日志，
 * 53 分钟后才被被动围栏广播唤醒」。所以把「轮询此刻应该在跑」和「最后一次 POLL_CYCLE 的时间」落盘，
 * 进程每次因为别的原因醒来（围栏广播、提醒/复核闹钟、时段闹钟、开机、进程启动）都来查一次：
 * 应该在跑、而且当前确实有需要轮询的地点，但最后一轮已经是 [STALE_MS] 以前的事 → 重启前台服务和轮询。
 */
internal object PollWatchdog {
    private const val PREFERENCES_NAME = "poll_watchdog"
    private const val KEY_EXPECTED = "poll_expected"
    private const val KEY_LAST_CYCLE = "last_poll_cycle_at"
    private const val KEY_TICK_BACKUP = "poll_tick_is_backup"

    /** 最后一轮 POLL_CYCLE 距今超过它就算轮询停了（正常周期 = 25s 间隔 + 最长 15s 定位）。 */
    const val STALE_MS = 60_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** 轮询开始 / 停止时调用。开始时顺带把「最后一轮」记为现在，免得刚启动就被判成停了。 */
    fun markExpected(context: Context, expected: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXPECTED, expected).apply {
            if (expected) putLong(KEY_LAST_CYCLE, System.currentTimeMillis())
        }.commit()
    }

    /** 每轮 POLL_CYCLE 开始时调用。 */
    fun markCycle(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_LAST_CYCLE, at).commit()
    }

    /** 每次排轮询闹钟时调用：当前排着的是不是每轮开始时的保底闹钟（v2.2.13）。 */
    fun markTickBackup(context: Context, backup: Boolean) {
        prefs(context).edit().putBoolean(KEY_TICK_BACKUP, backup).commit()
    }

    fun tickIsBackup(context: Context): Boolean = prefs(context).getBoolean(KEY_TICK_BACKUP, false)

    fun expected(context: Context): Boolean = prefs(context).getBoolean(KEY_EXPECTED, false)

    fun lastCycleAt(context: Context): Long = prefs(context).getLong(KEY_LAST_CYCLE, 0L)

    /**
     * 检查并在需要时重启轮询。[source] 写进日志，说明是被什么唤醒的。
     * 用户手动停止监测、时间段结束、今日地点都已完成时 expected 为 false，不会重启。
     */
    suspend fun check(context: Context, source: String) {
        val app = context.applicationContext
        if (!expected(app)) return
        val now = System.currentTimeMillis()
        val last = lastCycleAt(app)
        val age = now - last
        if (age <= STALE_MS) return
        // 只在生效时间段内、且确实还有需要轮询的地点时才重启。
        val pollable = AppDatabase.get(app).checkInPointDao().enabledPoints()
            .filter { it.isActiveAtMillis(now) && PointRules.isActiveNow(it, now) }
        if (pollable.isEmpty()) return
        // 先把「最后一轮」记为现在：重启要几秒才出第一轮，期间再来的广播不要重复重启。
        markCycle(app, now)
        DiagnosticRecordLogger.geofence(
            app,
            "POLL_WATCHDOG_RESTART：来源=$source；最后一次 POLL_CYCLE=$last（距今 ${age / 1000}s > ${STALE_MS / 1000}s），" +
                "当前仍有 ${pollable.size} 个地点需要轮询（${pollable.joinToString { "#${it.id} ${it.name}" }}）→ 重新启动前台服务和轮询"
        )
        try {
            GeofenceMonitoringService.start(app)
        } catch (error: Exception) {
            DiagnosticRecordLogger.geofence(app, "POLL_WATCHDOG_RESTART_FAILURE：${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        }
    }
}
