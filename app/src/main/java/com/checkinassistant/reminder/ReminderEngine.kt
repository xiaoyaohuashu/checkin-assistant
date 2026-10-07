package com.checkinassistant.reminder

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.checkinassistant.AlarmClockScheduler
import com.checkinassistant.data.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.Calendar

object ReminderEngine {
    private const val ACTION_ALARM = "com.checkinassistant.REMINDER_ALARM"
    private const val ACTION_STOP_ALERT = "com.checkinassistant.STOP_REMINDER_ALERT"
    private const val ACTION_LOCATION_CONFIRM = "com.checkinassistant.LOCATION_CONFIRM"
    private const val PREFERENCES_NAME = "reminder_settings"
    private const val KEY_RINGTONE_URI = "ringtone_uri"
    private const val REMINDER_INTERVAL_MS = 30_000L
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
    const val MAX_REMINDERS = 3
    private fun db(context: Context) = AppDatabase.get(context)

    // One process-wide alert session prevents overlapping vibrator/ringtone sources.
    private var alertPointId: Long? = null
    private var vibrator: Vibrator? = null
    private var ringtone: Ringtone? = null
    private var audioFocusRequest: AudioFocusRequest? = null

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
                    LocationServices.getFusedLocationProviderClient(context)
                        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                        .await()
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
        if (distance > point.radiusMeters) {
            db(context).checkInPointDao().update(point.copy(triggerState = PointState.ARMED.name, enteredAt = null))
            DiagnosticRecordLogger.geofence(
                context,
                "LOCATION_CONFIRM_OUTSIDE：#${point.id} ${point.name} distance=${"%.1f".format(distance)}m > radius=${point.radiusMeters}m，accuracy=${"%.1f".format(location.accuracy)}m；未提醒并重新设防"
            )
            return
        }
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
            if (count >= limit) {
                db(context).checkInPointDao().update(point.copy(triggerState = PointState.IGNORED.name, enteredAt = null))
                DiagnosticRecordLogger.geofence(
                    context,
                    "LOCATION_CONFIRM_IGNORED_DAILY_LIMIT：#${point.id} ${point.name} 今日已触发 ${count}/${limit} 次，已忽略"
                )
                return
            }
        }
        DiagnosticRecordLogger.geofence(
            context,
            "LOCATION_CONFIRM_INSIDE：#${point.id} ${point.name} distance=${"%.1f".format(distance)}m ≤ radius=${point.radiusMeters}m，accuracy=${"%.1f".format(location.accuracy)}m；立即触发提醒"
        )
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
        val recordId = db(context).recordDao().insert(newRecord(point, now))
        db(context).reminderDao().upsert(ReminderState(pointId, recordId, now, 0, simulated = true))
        sendNext(context, pointId)
    }

    suspend fun sendNext(context: Context, pointId: Long) {
        val state = db(context).reminderDao().get(pointId) ?: return
        if (!state.active || state.reminderCount >= MAX_REMINDERS) { db(context).reminderDao().deactivate(pointId); return }
        val point = db(context).checkInPointDao().get(pointId) ?: return
        if (!point.enabled) {
            DiagnosticRecordLogger.geofence(context, "REMINDER_DISABLED：#${point.id} ${point.name} 已禁用，停止后续提醒")
            cancel(context, pointId); db(context).reminderDao().deactivate(pointId)
            return
        }
        val count = state.reminderCount + 1
        showNotification(context, point, count)
        db(context).reminderDao().upsert(state.copy(reminderCount = count))
        db(context).recordDao().get(state.recordId)?.let { db(context).recordDao().update(it.copy(reminderCount = count)) }
        if (count < MAX_REMINDERS) schedule(context, pointId, System.currentTimeMillis() + REMINDER_INTERVAL_MS)
        else {
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
                            .copy(triggerState = PointState.COMPLETED.name)
                        db(context).checkInPointDao().update(done)
                    }
                    else -> {
                        // DAILY/WEEKLY stay enabled — just re-arm for the next window.
                        db(context).checkInPointDao().update(point.copy(triggerState = PointState.ARMED.name, enteredAt = null))
                    }
                }
            }
            db(context).recordDao().get(state.recordId)?.let {
                if (it.status == RecordStatus.UNCONFIRMED.name) {
                    db(context).recordDao().update(it.copy(status = RecordStatus.IGNORED.name, actionAt = System.currentTimeMillis()))
                }
            }
            DiagnosticRecordLogger.geofence(context, "REMINDER_MAX_ROUNDS_REACHED：#${point.id} ${point.name} 第${MAX_REMINDERS}轮通知完毕，自动结束本次提醒会话（cancel+deactivate，mode=${point.repeatMode}）")
        }
    }

    suspend fun complete(context: Context, pointId: Long) = finish(context, pointId, RecordStatus.COMPLETED)
    suspend fun ignore(context: Context, pointId: Long) = finish(context, pointId, RecordStatus.IGNORED)
    private suspend fun finish(context: Context, pointId: Long, status: RecordStatus) {
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
        db(context).reminderDao().upsert(state.copy(reminderCount = 0, snoozeUntil = System.currentTimeMillis() + 600_000L, active = true))
        if (!state.simulated) db(context).checkInPointDao().get(pointId)?.let { db(context).checkInPointDao().update(it.copy(triggerState = PointState.SNOOZED.name)) }; schedule(context, pointId, System.currentTimeMillis() + 600_000L)
    }
    suspend fun onAlarm(context: Context, pointId: Long, action: String?, expectedAt: Long = -1L) {
        if (action == ACTION_STOP_ALERT) { stopAlert(context, pointId); return }
        if (action == ACTION_LOCATION_CONFIRM) {
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
        val intent = locationConfirmationIntent(context, id, expectedAt, setBeforeElapsed)
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
    fun schedule(context: Context, id: Long, at: Long) { val alarm = context.getSystemService(AlarmManager::class.java); alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context, id)) }
    private fun scheduleAlertStop(context: Context, id: Long, at: Long) { context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, stopAlertIntent(context, id)) }
    fun cancel(context: Context, id: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(alarmIntent(context, id)); alarm.cancel(stopAlertIntent(context, id)); alarm.cancel(locationConfirmationIntent(context, id, 0))
        stopAlert(context, id)
        // v5+: notifications are NOT dismissed here on purpose — after a max-rounds
        // wrap-up the user should still see the reminder history in the shade.
        // Callers that represent explicit user action use dismissNotifications().
    }

    /** Removes the point's notifications from the shade — only for user-driven paths. */
    fun dismissNotifications(context: Context, id: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(id.toInt())
        manager.cancel(ALERT_NOTIFICATION_TAG, id.toInt())
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
            if (it.snoozeUntil != null && it.snoozeUntil > System.currentTimeMillis()) schedule(context, it.pointId, it.snoozeUntil) else schedule(context, it.pointId, System.currentTimeMillis() + 2_000L)
        }
    } }
    private fun alarmIntent(context: Context, id: Long) = PendingIntent.getBroadcast(context, (id % Int.MAX_VALUE).toInt(), Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_ALARM).putExtra(EXTRA_POINT_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun stopAlertIntent(context: Context, id: Long) = PendingIntent.getBroadcast(context, (id % Int.MAX_VALUE).toInt(), Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_STOP_ALERT).putExtra(EXTRA_POINT_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun locationConfirmationIntent(
        context: Context, id: Long, expectedAt: Long, setElapsed: Long = -1L
    ) = PendingIntent.getBroadcast(
        context,
        (id % Int.MAX_VALUE).toInt(),
        Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ACTION_LOCATION_CONFIRM)
            .putExtra(EXTRA_POINT_ID, id)
            .putExtra(EXTRA_EXPECTED_AT, expectedAt)
            .putExtra(EXTRA_SET_ELAPSED, setElapsed),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    private suspend fun showNotification(context: Context, point: CheckInPoint, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java); createChannel(manager)
        val complete = actionPending(context, point.id, NotificationActionReceiver.ACTION_COMPLETE); val snooze = actionPending(context, point.id, NotificationActionReceiver.ACTION_SNOOZE)
        // v4 §2: full-screen intent — alarm-style popup on the lock screen. When the
        // USE_FULL_SCREEN_INTENT permission is missing (Android 14+ gate), the system
        // automatically falls back to this same notification as a heads-up banner.
        val fullscreen = PendingIntent.getActivity(
            context,
            ((point.id % 1_000_000) + 3_000_000).toInt(),
            FullscreenReminderActivity.intent(context, point.id, point.name, count),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("到达${point.name},请确认").setContentText("提醒 $count/$MAX_REMINDERS").setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM).setFullScreenIntent(fullscreen, true).setAutoCancel(false).addAction(0, "已到达", complete).addAction(0, "稍后提醒", snooze).build()
        // A tag makes this ID independent of the foreground-service notification ID.
        // More importantly, both POLL_ENTER and GEOFENCE_ENTER reach this exact post.
        manager.notify(ALERT_NOTIFICATION_TAG, point.id.toInt(), notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = manager.getNotificationChannel(CHANNEL_ID)?.importance
            DiagnosticRecordLogger.geofence(context, "REMINDER_NOTIFICATION_POSTED：#${point.id} round=$count，channel=$CHANNEL_ID，effectiveImportance=$importance，priority=HIGH")
        }
        startAlert(context, point.id)
    }
    @Synchronized private fun startAlert(context: Context, pointId: Long) {
        if (alertPointId == pointId && ringtone?.isPlaying == true) return
        stopAlert(context, null)
        alertPointId = pointId
        vibrator = context.getSystemService(Vibrator::class.java)?.also {
            // Repeat from index 0: 450ms vibration, 180ms pause, then indefinitely.
            it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 450, 180), 0))
        }
        val audioManager = context.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener { }.build()
        if (audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            ringtone = try { RingtoneManager.getRingtone(context, selectedRingtoneUri(context))?.apply { audioAttributes = attributes; isLooping = true; play() } } catch (_: Exception) { null }
        }
    }
    @Synchronized internal fun stopAlert(context: Context, pointId: Long?) {
        if (pointId != null && alertPointId != pointId) return
        vibrator?.cancel(); vibrator = null
        ringtone?.stop(); ringtone = null
        audioFocusRequest?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        audioFocusRequest = null; alertPointId = null
    }
    private fun selectedRingtoneUri(context: Context): Uri {
        val saved = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getString(KEY_RINGTONE_URI, null)
        return saved?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }
    private fun createChannel(manager: NotificationManager) { if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "到达提醒", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(false); setSound(null, null) }) }
    private fun actionPending(context: Context, id: Long, action: String) = PendingIntent.getBroadcast(context, ((id % 1_000_000) + if (action == NotificationActionReceiver.ACTION_COMPLETE) 1_000_000 else 2_000_000).toInt(), Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(EXTRA_POINT_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun newRecord(point: CheckInPoint, time: Long): CheckInRecord { val c = Calendar.getInstance().apply { timeInMillis = time }; return CheckInRecord(pointId = point.id, pointName = point.name, triggeredAt = time, year = c.get(Calendar.YEAR), month = c.get(Calendar.MONTH) + 1, day = c.get(Calendar.DAY_OF_MONTH), weekday = c.get(Calendar.DAY_OF_WEEK), hour = c.get(Calendar.HOUR_OF_DAY), minute = c.get(Calendar.MINUTE), second = c.get(Calendar.SECOND)) }
    private fun formatMinute(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
    // Notification-channel importance is immutable once Android has created it.  Use a
    // new ID so devices that retained an older channel configuration start from HIGH.
    const val CHANNEL_ID = "checkin_reminders_v3"
    private const val ALERT_NOTIFICATION_TAG = "checkin_reminder_alert"
}

private fun CheckInPoint.isActiveAt(minuteOfDay: Int): Boolean {
    val start = activeStartMinute ?: return true
    val end = activeEndMinute ?: return true
    return if (start < end) minuteOfDay in start until end else minuteOfDay >= start || minuteOfDay < end
}

private fun CheckInPoint.timeWindowText(): String = "%02d:%02d-%02d:%02d".format(
    activeStartMinute!! / 60, activeStartMinute % 60, activeEndMinute!! / 60, activeEndMinute % 60
)
