package com.checkinassistant.reminder

import android.app.*
import android.content.*
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.checkinassistant.system.AlarmClockScheduler
import com.checkinassistant.data.*
import com.checkinassistant.domain.ArrivalRules
import com.checkinassistant.domain.PointRules
import com.checkinassistant.domain.ReminderRules
import com.checkinassistant.domain.isMinuteInWindow
import com.checkinassistant.location.LocationProviders
import kotlinx.coroutines.*
import java.util.Calendar

object ReminderEngine {
    private const val PREFERENCES_NAME = "reminder_settings"
    private const val KEY_RINGTONE_URI = "ringtone_uri"
    /** 点「稍后提醒」后隔多久重新提醒：10 分钟。 */
    private const val SNOOZE_DELAY_MS = 600_000L
    /** 手机重启 / App 升级后，没响完的提醒隔多久接着响：2 秒。 */
    private const val RESTORE_RESUME_DELAY_MS = 2_000L
    /** 振动节奏：振 0.45 秒、停 0.18 秒，从头循环。 */
    private const val VIBRATE_ON_MS = 450L
    private const val VIBRATE_PAUSE_MS = 180L
    private const val VIBRATE_REPEAT_FROM_INDEX = 0
    /** 进入围栏后多久做一次定位复核。前台轮询复用同一常量作为兜底复核的判据。 */
    internal const val LOCATION_CONFIRM_DELAY_MS = 25_000L
    private const val LOCATION_QUERY_TIMEOUT_MS = 15_000L
    const val EXTRA_POINT_ID = "point_id"
    const val EXTRA_EXPECTED_AT = "expected_at"
    /**
     * 排程那一刻的 `SystemClock.elapsedRealtime()`。随 Intent 一起带到接收端，
     * 用于计算「排程 → 送达」的**真实经过时间**：
     * 它不受墙钟跳变（NTP 校时）影响，可以区分「闹钟真的被推迟」与
     * 「墙钟被校正导致看起来晚了」。
     */
    const val EXTRA_SET_ELAPSED = "set_elapsed"
    const val MAX_REMINDERS = ReminderRules.MAX_REMINDERS
    private fun db(context: Context) = AppDatabase.get(context)

    // One process-wide alert session prevents overlapping vibrator/ringtone sources.
    private var alertPointId: Long? = null
    private var vibrator: Vibrator? = null
    private var soundPlayer: AlertSoundPlayer? = null

    /**
     * @param knownLocation 调用方（前台轮询兜底复核）本轮刚取得的定位。传入时直接复用，
     * 省掉一次最长 15s 的 `getCurrentLocation()` 等待 —— 该定位本来就晚于计划复核时刻，
     * 与「25 秒后重新确认位置」的语义一致。
     * @param source 触发来源（ALARM = 闹钟；POLL_FALLBACK = 前台轮询兜底）。
     * 之前两条路径共用同一条 LOCATION_CONFIRM_ALARM_FIRED 日志，无法区分「闹钟迟到」
     * 与「轮询兜底按周期执行」，这里显式区分。
     */
    suspend fun confirmLocation(
        context: Context,
        pointId: Long,
        expectedAt: Long,
        knownLocation: android.location.Location? = null,
        source: String = "ALARM"
    ) {
        val firedAt = System.currentTimeMillis()
        val delayText = if (expectedAt > 0) "，实际晚于计划 ${firedAt - expectedAt}ms" else ""
        DiagnosticRecordLogger.geofence(context, "LOCATION_CONFIRM_FIRED[$source]：点位 #$pointId$delayText")
        DiagnosticRecordLogger.geofence(
            context,
            "LOCATION_CONFIRM_DIAG[$source]：pointId=$pointId，firedAt=$firedAt，expectedAt=$expectedAt，" +
                "delayMs=${firedAt - expectedAt}，elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
        )
        val point = db(context).checkInPointDao().get(pointId)
        if (point == null) {
            DiagnosticRecordLogger.geofence(context, "LOCATION_CONFIRM_ABORTED：点位 #$pointId 已不存在")
            return
        }
        if (!point.enabled) {
            DiagnosticRecordLogger.geofence(context, "LOCATION_CONFIRM_DISABLED：#${point.id} ${point.name} 已禁用，取消提醒")
            cancel(context, pointId); db(context).reminderDao().deactivate(pointId)
            return
        }
        // v2.2.24（问题 1）：停止前刚进入范围、25 秒确认还没到就点了「停止后台监测」——到点后不提醒，状态放回「等待进入」。
        if (com.checkinassistant.geofence.MonitorKeeper.userStopped(context)) {
            db(context).checkInPointDao().rearmIfEntered(pointId)
            DiagnosticRecordLogger.geofence(context, "LOCATION_CONFIRM_STOPPED：#${point.id} ${point.name} 用户已手动停止监测，不触发提醒并重新设防")
            return
        }
        if (point.triggerState != PointState.ENTERED.name) {
            DiagnosticRecordLogger.geofence(context, "LOCATION_CONFIRM_CANCELLED：#${point.id} ${point.name} 当前状态=${point.triggerState}")
            return
        }
        val location = if (knownLocation != null) {
            DiagnosticRecordLogger.geofence(context, "LOCATION_CONFIRM_REUSE：#${point.id} ${point.name} 复用前台轮询本轮刚取得的定位（accuracy=${"%.1f".format(knownLocation.accuracy)}m），省去一次最长 ${LOCATION_QUERY_TIMEOUT_MS / 1000}s 的查询")
            knownLocation
        } else {
            DiagnosticRecordLogger.geofence(context, "LOCATION_CONFIRM_QUERY：#${point.id} ${point.name} 请求 FusedLocationProviderClient.getCurrentLocation()，最长等待 ${LOCATION_QUERY_TIMEOUT_MS / 1000}s")
            val queried = try {
                withTimeout(LOCATION_QUERY_TIMEOUT_MS) {
                    LocationProviders.get(context).currentLocationHighAccuracy()
                }
            } catch (_: TimeoutCancellationException) {
                DiagnosticRecordLogger.geofence(context, "LOCATION_CONFIRM_LOCATION_TIMEOUT：${LOCATION_QUERY_TIMEOUT_MS / 1000}s 内未取得定位，未触发提醒")
                return
            } catch (error: Exception) {
                DiagnosticRecordLogger.geofence(context, "LOCATION_CONFIRM_LOCATION_ERROR：${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                return
            }
            if (queried == null) {
                DiagnosticRecordLogger.geofence(context, "LOCATION_CONFIRM_LOCATION_NULL：当前定位不可用，未触发提醒")
                return
            }
            queried
        }
        val results = FloatArray(1)
        android.location.Location.distanceBetween(point.latitude, point.longitude, location.latitude, location.longitude, results)
        val distance = results[0]
        if (ArrivalRules.isOutsideForConfirm(distance, point.radiusMeters)) {
            db(context).checkInPointDao().update(point.copy(triggerState = PointState.ARMED.name, enteredAt = null))
            DiagnosticRecordLogger.geofence(
                context,
                "LOCATION_CONFIRM_OUTSIDE：#${point.id} ${point.name} distance=${"%.1f".format(distance)}m > radius=${point.radiusMeters}m，accuracy=${"%.1f".format(location.accuracy)}m；未提醒并重新设防"
            )
            return
        }
// 下面三道关（时间段、每日上限）用的都是「进入范围那一刻」的时间，不是现在（25 秒之后）的时间。
        // The time window applies to when Android delivered ENTER, rather than 25 seconds
        // later when the confirmation alarm happens to run.
        val now = Calendar.getInstance().apply { timeInMillis = point.enteredAt ?: System.currentTimeMillis() }
        val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        if (!point.isActiveAt(minuteOfDay)) {
            db(context).checkInPointDao().update(point.copy(triggerState = PointState.IGNORED.name, enteredAt = null))
            DiagnosticRecordLogger.geofence(
                context,
                "LOCATION_CONFIRM_IGNORED_TIME_WINDOW：#${point.id} ${point.name} ENTER时间=${formatMinute(minuteOfDay)}，未在生效时段 ${point.timeWindowText()} 内，已忽略"
            )
            return
        }
        val limit = point.dailyTriggerLimit
        if (limit != null) {
            val count = db(context).recordDao().triggeredCountForDay(
                point.id, now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
            )
            if (ReminderRules.isDailyLimitReached(count, limit)) {
                db(context).checkInPointDao().update(point.copy(triggerState = PointState.IGNORED.name, enteredAt = null))
                DiagnosticRecordLogger.geofence(
                    context,
                    "LOCATION_CONFIRM_IGNORED_DAILY_LIMIT：#${point.id} ${point.name} 今日已触发 ${count}/${limit} 次（不含模拟提醒），已忽略"
                )
                return
            }
        }
        DiagnosticRecordLogger.geofence(
            context,
            "LOCATION_CONFIRM_INSIDE：#${point.id} ${point.name} distance=${"%.1f".format(distance)}m ≤ radius=${point.radiusMeters}m，accuracy=${"%.1f".format(location.accuracy)}m；立即触发提醒"
        )
// 【顺序和所用的数据都不能动】写「本时间段已提醒」标记 → 插历史记录 → 写提醒状态 → 把点位标成「提醒中」→ 发第 1 轮。
// 注意第 4 步用的是函数开头读出来的旧 point，整条写回会把第 1 步刚写的标记盖掉（现状）。
// 这看着像疏漏，但不是待修的问题，不能「顺手修」：「提醒中 / 三轮没理的点仍在轮询、离开范围能被发现、
// 再进入能通过 isActiveNow」都靠它成立。问题 15（三轮没理后重复提醒）v2.2.24 已改由收尾时把状态记成「已忽略」解决。
// （下一行英文注释里「ONCE points flip to COMPLETED here」是旧说法：现在一次性点要等点了「已到达」或三轮响完才标完成。）
        val recordAt = System.currentTimeMillis()
        // Spec §4.6: persist window dedup marker; ONCE points flip to COMPLETED here.
        PointRules.onTriggered(point, recordAt).let { db(context).checkInPointDao().update(it) }
        val recordId = db(context).recordDao().insert(newRecord(point, recordAt))
        db(context).reminderDao().upsert(ReminderState(pointId, recordId, System.currentTimeMillis(), 0))
        db(context).checkInPointDao().update(point.copy(triggerState = PointState.REMINDING.name))
        sendNext(context, pointId)
    }
    /** Development-only path: it creates the same reminder/record lifecycle but leaves geofence state untouched. */
    suspend fun simulate(context: Context, pointId: Long) {
        val point = db(context).checkInPointDao().get(pointId) ?: return
        if (!point.enabled) {
            DiagnosticRecordLogger.geofence(context, "SIMULATE_DISABLED：#${point.id} ${point.name} 已禁用，不模拟")
            return
        }
        val now = System.currentTimeMillis()
        if (db(context).reminderDao().get(pointId)?.let { it.active && !it.simulated } == true) return
        val recordId = db(context).recordDao().insert(newRecord(point, now).copy(simulated = true))
        db(context).reminderDao().upsert(ReminderState(pointId, recordId, now, 0, simulated = true))
        sendNext(context, pointId)
    }

    suspend fun sendNext(context: Context, pointId: Long) {
        val state = db(context).reminderDao().get(pointId) ?: return
        val step = ReminderRules.nextStep(state.active, state.reminderCount)
        if (step == ReminderRules.Step.STOP) { db(context).reminderDao().deactivate(pointId); return }
        val point = db(context).checkInPointDao().get(pointId)
        if (point == null) {
            // v2.2.24（问题 22）兜底：提醒点已经被删了，但它的闹钟抢在取消之前响了。把残留的收干净，不再响。
            cancel(context, pointId); db(context).reminderDao().deactivate(pointId); dismissNotifications(context, pointId)
            DiagnosticRecordLogger.geofence(context, "REMINDER_POINT_MISSING：#$pointId 提醒点已不存在，停止提醒并清掉通知")
            return
        }
        if (!point.enabled) {
            DiagnosticRecordLogger.geofence(context, "REMINDER_DISABLED：#${point.id} ${point.name} 已禁用，停止后续提醒")
            cancel(context, pointId); db(context).reminderDao().deactivate(pointId)
            return
        }
        if (step == ReminderRules.Step.WRAP_UP) {
// 三轮都响满了：收尾。通知故意留在通知栏不清。
            // Max rounds reached: wrap up the session for ALL modes. Without this the
            // point used to stay REMINDING forever — the polling loop kept running with
            // nobody left to answer the notification ("仅一次提醒挂起" bug).
            cancel(context, pointId) // cancels queued alarms + ringer/vibration + notifications
            db(context).reminderDao().deactivate(pointId)
            if (!state.simulated) db(context).checkInPointDao().get(pointId)?.let { point ->
                when (point.repeatMode) {
                    "ONCE" -> {
                        // All rounds exhausted without confirmation: geolocation fact
                        // stands but the user never responded — acknowledged=false,
                        // list renders "已到达，未确认" (record goes to IGNORED below).
                        val done = PointRules.onArrivedConfirmed(point, System.currentTimeMillis(), acknowledged = false)
                            .copy(triggerState = ReminderRules.stateAfterRoundsExhausted(point.repeatMode).name)
                        db(context).checkInPointDao().update(done)
                    }
                    else -> {
                        // v2.2.24（问题 15）每天 / 按星期的点保持启用，但三轮没理后本时间段内不再提醒：
                        // 状态记成「已忽略」，离开范围后才重新设防（见 ReminderRules.stateAfterRoundsExhausted）。
                        db(context).checkInPointDao().update(point.copy(triggerState = ReminderRules.stateAfterRoundsExhausted(point.repeatMode).name, enteredAt = null))
                    }
                }
            }
            db(context).recordDao().get(state.recordId)?.let {
                if (it.status == RecordStatus.UNCONFIRMED.name) {
                    db(context).recordDao().update(it.copy(status = RecordStatus.IGNORED.name, actionAt = System.currentTimeMillis()))
                }
            }
            DiagnosticRecordLogger.geofence(context, "REMINDER_MAX_ROUNDS_REACHED：#${point.id} ${point.name} 第${MAX_REMINDERS}轮通知完毕，自动结束本次提醒会话（cancel+deactivate，mode=${point.repeatMode}）")
            return
        }
// 先发通知（里面会开始响铃振动），再把轮数写库。
        val count = state.reminderCount + 1
        showNotification(context, point, count)
        db(context).reminderDao().upsert(state.copy(reminderCount = count))
        db(context).recordDao().get(state.recordId)?.let { db(context).recordDao().update(it.copy(reminderCount = count)) }
// v2.2.24（问题 6）第 3 轮也响满 30 秒：每一轮发完都排 30 秒后的闹钟（包括第 3 轮），
// 那次闹钟到了，上面的 nextStep 才走到「收尾」，停铃声和振动。三轮合计约 90 秒。
        schedule(context, pointId, System.currentTimeMillis() + ReminderRules.delayAfterRoundMs(count))
    }

    suspend fun complete(context: Context, pointId: Long) = finish(context, pointId, RecordStatus.COMPLETED)
    suspend fun ignore(context: Context, pointId: Long) = finish(context, pointId, RecordStatus.IGNORED)
    private suspend fun finish(context: Context, pointId: Long, status: RecordStatus) {
// 这次提醒已经不存在（状态查不到）就直接返回，连 cancel 都不做——两句写在同一行是有意的，保持。
        val state = db(context).reminderDao().get(pointId) ?: return; cancel(context, pointId)
        // User acted on the notification — its job is done, clear it from the shade.
        dismissNotifications(context, pointId)
        db(context).reminderDao().deactivate(pointId); db(context).recordDao().get(state.recordId)?.let { db(context).recordDao().update(it.copy(status = status.name, actionAt = System.currentTimeMillis())) }
        if (!state.simulated) db(context).checkInPointDao().get(pointId)?.let {
            // v4 §1.3 改动点2: "我已到达" runs onArrivedConfirmed — ONCE flips to
            // COMPLETED + auto-disables here; other modes only stop this window.
            db(context).checkInPointDao().update(
                PointRules.onArrivedConfirmed(it, System.currentTimeMillis())
                    .copy(triggerState = if (status == RecordStatus.COMPLETED) PointState.COMPLETED.name else PointState.IGNORED.name)
            )
        }
    }
    suspend fun snooze(context: Context, pointId: Long) {
        val state = db(context).reminderDao().get(pointId) ?: return; cancel(context, pointId)
// 稍后提醒：轮数清零（10 分钟后重新从第 1 轮开始）。下面两处「现在 + 10 分钟」各读一次时钟，是现状，不要合并成一个变量。
        db(context).reminderDao().upsert(state.copy(reminderCount = 0, snoozeUntil = System.currentTimeMillis() + SNOOZE_DELAY_MS, active = true))
        if (!state.simulated) db(context).checkInPointDao().get(pointId)?.let { db(context).checkInPointDao().update(it.copy(triggerState = PointState.SNOOZED.name)) }; schedule(context, pointId, System.currentTimeMillis() + SNOOZE_DELAY_MS)
    }
    suspend fun onAlarm(context: Context, pointId: Long, action: String?, expectedAt: Long = -1L) {
        if (action == ReminderAlarms.ACTION_STOP_ALERT) { stopAlert(context, pointId); return }
        if (action == ReminderAlarms.ACTION_LOCATION_CONFIRM) {
            confirmLocation(context, pointId, expectedAt, null, "ALARM")
        } else {
            sendNext(context, pointId)
        }
    }
    // v5+: exit does NOT dismiss notifications — after a max-rounds wrap-up the next
    // poll still runs an EXIT for the finished session, which used to wipe the
    // notification we intentionally kept in the shade. Only explicit user paths
    // (tap actions, toggle/skip in MainActivity) clear it.
    suspend fun cancelForExit(context: Context, pointId: Long) { cancel(context, pointId); db(context).reminderDao().deactivate(pointId) }
    /**
     * 安排进入围栏后 25 秒的定位复核。
     *
     * 返回 true = 已排到闹钟（优先 `setAlarmClock`；系统拒绝或缺少「闹钟和提醒」精确闹钟
     * 权限时退化为非精确 allow-while-idle，仍会被送达，只是可能晚一点）。返回 false = 完全排不上闹钟。
     *
     * 旧实现在拿不到精确闹钟权限时直接返回 false，调用方随即 `rearmIfEntered()`
     * 重新设防 —— 待确认状态被抹掉，前台轮询的兜底复核因此永远不触发，只剩
     * 「等下一次系统维护窗口」这一条路，这正是复核晚约 3m34s 的成因之一。
     */
    suspend fun scheduleLocationConfirmation(context: Context, id: Long): Boolean {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        val batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName)
        val idle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && power.isDeviceIdleMode
        // 复核延迟的根因排查：allow-while-idle 闹钟在 Doze / 未豁免电池优化时会被系统
        // 延后（历史上出现过晚 18.8s 与晚 3m34s），这里把三个关键前置条件落进诊断日志。
        DiagnosticRecordLogger.geofence(
            context,
            "LOCATION_CONFIRM_ENV：精确闹钟可用=$exact，电池优化已豁免=$batteryExempt，当前 Doze=$idle"
        )
        val setBeforeWall = System.currentTimeMillis()
        val setBeforeElapsed = android.os.SystemClock.elapsedRealtime()
        val expectedAt = setBeforeWall + LOCATION_CONFIRM_DELAY_MS
        val intent = ReminderAlarms.locationConfirmationIntent(context, id, expectedAt, setBeforeElapsed)
        DiagnosticRecordLogger.geofence(
            context,
            "ALARM_SET_BEFORE：pointId=$id，now(wall)=$setBeforeWall，now(elapsed)=$setBeforeElapsed，" +
                "triggerAt=$expectedAt，计划延迟=${LOCATION_CONFIRM_DELAY_MS}ms（triggerAt-now=${expectedAt - setBeforeWall}ms），" +
                "PendingIntent(hash=${intent.hashCode()}，flags=FLAG_UPDATE_CURRENT|FLAG_IMMUTABLE，requestCode=${(id % Int.MAX_VALUE).toInt()}）"
        )
        if (exact) {
            // v2.2.11：改用 setAlarmClock —— 系统里最高优先级的闹钟类别（状态栏会显示
            // 「下一个闹钟」），不受批量对齐 / 后台投递限制。触发用的 operation 仍是原来
            // 那个 PendingIntent，因此 ReminderEngine.cancel() 的 cancel 语义不变。
            if (AlarmClockScheduler.set(context, expectedAt, intent)) {
                logAlarmSet(context, id, "setAlarmClock", expectedAt, setBeforeWall, setBeforeElapsed, true)
                return true
            }
            DiagnosticRecordLogger.geofence(context, "LOCATION_CONFIRM_ALARMCLOCK_DENIED：系统拒绝 setAlarmClock（缺「闹钟和提醒」权限或厂商限制），改用非精确闹钟")
        } else {
            DiagnosticRecordLogger.geofence(context, "LOCATION_CONFIRM_NO_EXACT_PERMISSION：缺少“闹钟和提醒”精确闹钟权限，改用非精确闹钟并保留待确认状态（前台轮询仍会兜底复核）")
        }
        return try {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, expectedAt, intent)
            logAlarmSet(context, id, "setAndAllowWhileIdle", expectedAt, setBeforeWall, setBeforeElapsed, false)
            true
        } catch (error: SecurityException) {
            DiagnosticRecordLogger.geofence(context, "LOCATION_CONFIRM_NOT_SCHEDULED：SecurityException: ${error.message.orEmpty()}")
            false
        }
    }

    /** 排程后的诊断：核对剩余时间、调用耗时，确认实际写入的 triggerAt。 */
    private suspend fun logAlarmSet(
        context: Context, id: Long, api: String, triggerAt: Long,
        beforeWall: Long, beforeElapsed: Long, isExact: Boolean
    ) {
        val afterWall = System.currentTimeMillis()
        val afterElapsed = android.os.SystemClock.elapsedRealtime()
        DiagnosticRecordLogger.geofence(context, "LOCATION_CONFIRM_SCHEDULED${if (isExact) "" else "_INEXACT"}：点位 #$id，计划延迟=${LOCATION_CONFIRM_DELAY_MS / 1000}s，计划时间=$triggerAt（与 triggerAt 同一变量）")
        DiagnosticRecordLogger.geofence(
            context,
            "ALARM_SET_AFTER：pointId=$id，api=$api，triggerAt=$triggerAt，now(wall)=$afterWall，" +
                "now(elapsed)=$afterElapsed，set调用耗时=${afterElapsed - beforeElapsed}ms，" +
                "排程后剩余=${triggerAt - afterWall}ms（应≈${LOCATION_CONFIRM_DELAY_MS}ms）"
        )
    }
// 提醒轮次和「稍后提醒」用的是 setAndAllowWhileIdle；25 秒定位复核才优先用 setAlarmClock（v2.2.11 真机验证）。两者不要「统一」。
    fun schedule(context: Context, id: Long, at: Long) { ReminderAlarms.schedule(context, id, at) }
// 先取消三条闹钟，再停响铃振动。
    fun cancel(context: Context, id: Long) {
        ReminderAlarms.cancelAlarms(context, id)
        stopAlert(context, id)
        // v5+: notifications are NOT dismissed here on purpose — after a max-rounds
        // wrap-up the user should still see the reminder history in the shade.
        // Callers that represent explicit user action use dismissNotifications().
    }

    /** Removes the point's notifications from the shade — only for user-driven paths. */
    fun dismissNotifications(context: Context, id: Long) {
        ReminderNotifier.dismiss(context, id)
    }
    /** A point disabled before a reboot must not start ringing again on boot. */
    fun restore(context: Context) { CoroutineScope(Dispatchers.IO).launch {
        val enabledIds = db(context).checkInPointDao().enabledPoints().map { it.id }.toSet()
        db(context).reminderDao().active().forEach {
            if (it.pointId !in enabledIds) {
                cancel(context, it.pointId)
                db(context).reminderDao().deactivate(it.pointId)
                return@forEach
            }
            if (it.snoozeUntil != null && it.snoozeUntil > System.currentTimeMillis()) schedule(context, it.pointId, it.snoozeUntil) else schedule(context, it.pointId, System.currentTimeMillis() + RESTORE_RESUME_DELAY_MS)
        }
    } }
    private suspend fun showNotification(context: Context, point: CheckInPoint, count: Int) {
        // 先发通知（ReminderNotifier 里：建渠道 → 发通知 → 写日志），再开始响铃振动。先后不能换。
        ReminderNotifier.post(context, point, count)
        startAlert(context, point.id)
    }
// startAlert / stopAlert 用同一把锁（@Synchronized 锁的是 ReminderEngine 这个对象），保证同一时刻只有一个点位在响。
// 所以这两个函数和上面三个状态字段必须留在同一个对象里，不能拆到别处。
    @Synchronized private fun startAlert(context: Context, pointId: Long) {
        if (alertPointId == pointId && soundPlayer?.isPlaying == true) return
        stopAlert(context, null)
        alertPointId = pointId
        vibrator = context.getSystemService(Vibrator::class.java)?.also {
            // Repeat from index 0: 450ms vibration, 180ms pause, then indefinitely.
            it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, VIBRATE_ON_MS, VIBRATE_PAUSE_MS), VIBRATE_REPEAT_FROM_INDEX))
        }
        // 声音由 App 自己播放：连蓝牙耳机只走耳机，否则外放（见 AlertSoundPlayer）。
        soundPlayer = AlertSoundPlayer(context.applicationContext, pointId, selectedRingtoneUri(context)).also {
            try { it.start() } catch (_: Exception) { }
        }
    }
    @Synchronized internal fun stopAlert(context: Context, pointId: Long?) {
        if (pointId != null && alertPointId != pointId) return
        vibrator?.cancel(); vibrator = null
        soundPlayer?.stop(); soundPlayer = null
        alertPointId = null
    }
    /** null = 用户选了「静音」。 */
    private fun selectedRingtoneUri(context: Context): Uri? {
        val saved = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getString(KEY_RINGTONE_URI, null)
            ?: return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        return saved.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }
    private fun newRecord(point: CheckInPoint, time: Long): CheckInRecord { val c = Calendar.getInstance().apply { timeInMillis = time }; return CheckInRecord(pointId = point.id, pointName = point.name, triggeredAt = time, year = c.get(Calendar.YEAR), month = c.get(Calendar.MONTH) + 1, day = c.get(Calendar.DAY_OF_MONTH), weekday = c.get(Calendar.DAY_OF_WEEK), hour = c.get(Calendar.HOUR_OF_DAY), minute = c.get(Calendar.MINUTE), second = c.get(Calendar.SECOND)) }
    /** 渠道 ID 的定义在 ReminderNotifier；这里保留同名常量给外面用。 */
    const val CHANNEL_ID = ReminderNotifier.CHANNEL_ID
    private fun formatMinute(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
}

private fun CheckInPoint.isActiveAt(minuteOfDay: Int): Boolean {
    val start = activeStartMinute ?: return true
    val end = activeEndMinute ?: return true
    return isMinuteInWindow(start, end, minuteOfDay)
}

private fun CheckInPoint.timeWindowText(): String = "%02d:%02d-%02d:%02d".format(
    activeStartMinute!! / 60, activeStartMinute % 60, activeEndMinute!! / 60, activeEndMinute % 60
)
