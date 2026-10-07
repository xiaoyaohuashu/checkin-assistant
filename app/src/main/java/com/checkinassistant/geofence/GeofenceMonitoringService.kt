package com.checkinassistant.geofence

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.checkinassistant.AlarmWakeLock
import com.checkinassistant.data.*
import com.checkinassistant.reminder.ReminderEngine
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/** Performs app-controlled checks only while at least one point is within its active window. */
class GeofenceMonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db by lazy { AppDatabase.get(this) }
    private val geofenceClient by lazy { LocationServices.getGeofencingClient(this) }
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var pollingJob: Job? = null
    /** 上一轮轮询开始的墙钟时间，仅用于诊断轮询实际周期。 */
    private var lastPollStartedAtMs = 0L
    /**
     * 轮询状态（v2.2.12 起跨轮保存在服务实例里）：上一轮时处于生效时间段的地点、参与轮询的地点。
     * null = 当前没有在轮询。轮询闹钟到了而这里是 null，说明服务/进程重启过，按启动请求重新评估。
     */
    private var pollActiveIds: List<Long>? = null
    private var pollPollableIds: List<Long> = emptyList()
    /** 上一次排下一轮时用的是不是闹钟类别；只在变化时记日志。 */
    private var lastTickExact: Boolean? = null

    /**
     * v2.2.12：不再是「一个 10 分钟唤醒锁 + 协程 delay 循环」。每一轮是一次独立的工作：
     * 开始时拿一把 60 秒上限的部分唤醒锁，做完立刻释放，并用 setAlarmClock 排下一轮
     * （[schedulePollTick]）；两轮之间不持锁、不靠协程计时，CPU 可以睡。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(FOREGROUND_ID, foregroundNotification())
        val tick = intent?.action == ACTION_POLL_TICK
        if (tick && pollingJob?.isActive == true) {
            // 上一轮（或一次重新评估）还没结束：它结束时会自己排下一轮，这个闹钟不用再做事。
            releaseTickHandoff()
            DiagnosticRecordLogger.trace("POLL_TICK_SKIPPED：上一轮还没结束")
            return START_NOT_STICKY
        }
        if (!tick) pollingJob?.cancel()
        pollingJob = scope.launch {
            // 每一轮（或每次重新评估）自己的锁：60 秒上限，finally 里立即释放。
            val lock = AlarmWakeLock.acquire(this@GeofenceMonitoringService, "GeofencePoll")
            releaseTickHandoff()
            try {
                if (tick && pollActiveIds != null) {
                    pollCycle(startId)
                } else {
                    DiagnosticRecordLogger.geofence(
                        this@GeofenceMonitoringService,
                        if (tick) "POLL_TICK_NO_STATE：收到轮询闹钟，但服务里没有轮询状态（服务或进程重启过，startId=$startId），重新评估生效时间段"
                        else "MONITOR_SERVICE_START：收到启动请求（startId=$startId），重新评估生效时间段"
                    )
                    configureMonitoring(startId)
                }
            } finally {
                AlarmWakeLock.release(lock)
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun configureMonitoring(startId: Int) {
        val points = db.checkInPointDao().enabledPoints()
        if (!hasLocationPermission()) {
            DiagnosticRecordLogger.geofence(this, "MONITOR_CONFIG_ABORTED：未同时授予精确位置和后台位置权限（${locationPermissionSummary()}）")
            // A revoked permission must not leave a previously registered passive fence alive.
            removeGeofences("定位权限不足")
            sleepUntilNextWindow(points, "定位权限不足", startId)
            return
        }
        val now = System.currentTimeMillis()
        val activePoints = points.filter { it.isActiveAtMillis(now) }
        // A scheduled wakeup can replace the previous polling coroutine exactly at a
        // boundary.  Clean inactive points here as well, so a cancelled old coroutine
        // cannot leave a confirmation alarm running outside its allowed window.
        points.asSequence()
            .filterNot { it.isActiveAtMillis(now) }
            .forEach { GeofenceTriggerCoordinator.exit(this, it.id, "MONITOR_WINDOW_INACTIVE") }
        if (activePoints.isEmpty()) {
            removeGeofences("当前不在任何地点的生效时间段")
            sleepUntilNextWindow(points, "当前无生效地点", startId)
            return
        }
        registerGeofences(activePoints)
        nextMonitoringChange(points, now)?.let(::scheduleReevaluation)
            ?: cancelMonitoringReevaluation(this)
        // 省电优化：当天已完成（窗口去重/已确认）或已跳过的点不再参与主动定位轮询。
        // 过滤口径全部来自 PointRules（持久化状态），重启后依然有效。
        val pollablePoints = activePoints.filter { com.checkinassistant.reminder.PointRules.isActiveNow(it, now) }
        if (pollablePoints.isEmpty()) {
            DiagnosticRecordLogger.geofence(this, "POLL_SKIP_ALL_DONE：生效时间段内的 ${activePoints.size} 个地点今日均已完成或已跳过，不启动定位轮询：${activePoints.joinToString { "#${it.id} ${it.name}" }}")
            sleepUntilNextWindow(points, "今日生效地点均已完成或已跳过", startId)
            return
        }
        DiagnosticRecordLogger.geofence(this, "POLL_START：${pollablePoints.size}/${activePoints.size} 个地点需要定位轮询（其余今日已完成/已跳过），每 ${POLL_INTERVAL_MS / 1000}s 一次：${pollablePoints.joinToString { "#${it.id} ${it.name}" }}")
        pollActiveIds = activePoints.map { it.id }
        pollPollableIds = pollablePoints.map { it.id }
        PollWatchdog.markExpected(this, true)
        pollCycle(startId)
    }

    /** 跑一轮轮询；还要继续轮询的话，结束时排下一轮闹钟。本轮出异常也照排，不让轮询因为一次异常断掉。 */
    private suspend fun pollCycle(startId: Int) {
        val keepPolling = try {
            pollCycleBody(startId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DiagnosticRecordLogger.geofence(this, "POLL_CYCLE_ERROR：${error.javaClass.simpleName}: ${error.message.orEmpty()}（本轮中断，照常排下一轮）")
            true
        }
        if (!keepPolling) return
        val at = System.currentTimeMillis() + POLL_INTERVAL_MS
        val exact = schedulePollTick(this, at)
        if (exact != lastTickExact) {
            lastTickExact = exact
            DiagnosticRecordLogger.geofence(
                this,
                if (exact) "POLL_TICK_SCHEDULED：每轮结束后用 setAlarmClock 排下一轮（${POLL_INTERVAL_MS / 1000}s 后），每轮持锁上限 60s、结束即释放；此后各轮不再重复记这一条"
                else "POLL_TICK_INEXACT：没有「闹钟和提醒」权限或 setAlarmClock 被拒绝，下一轮退化为 setAndAllowWhileIdle，可能被系统推后"
            )
        }
    }

    /** @return true = 继续轮询（调用方排下一轮）；false = 已停止轮询并安排好下一次时段唤醒。 */
    private suspend fun pollCycleBody(startId: Int): Boolean {
        var activeIds = pollActiveIds.orEmpty()
        var pollableIds = pollPollableIds
        val points = db.checkInPointDao().enabledPoints()
        val now = System.currentTimeMillis()
        val currentPoints = points.filter { it.isActiveAtMillis(now) }
        val currentIds = currentPoints.map { it.id }
        // Treat a time-window boundary like an EXIT.  This cancels a pending 25-second
        // confirmation and any alert before the point is outside its permitted window.
        (activeIds.toSet() - currentIds.toSet()).forEach { id ->
            GeofenceTriggerCoordinator.exit(this, id, "POLL_WINDOW_EXIT")
        }
        if (currentPoints.isEmpty()) {
            DiagnosticRecordLogger.geofence(this, "POLL_STOP：生效时间段结束，停止定位轮询并进入休眠")
            removeGeofences("生效时间段结束")
            sleepUntilNextWindow(points, "生效时间段结束", startId)
            return false
        }
        // 省电优化：每轮重新评估可轮询集合。当天完成（窗口去重/已确认）或已跳过
        // 的点立即退出轮询；其余点继续。判定全部来自 PointRules 的持久化状态。
        val currentPollable = currentPoints.filter { com.checkinassistant.reminder.PointRules.isActiveNow(it, now) }
        val currentPollableIds = currentPollable.map { it.id }
        val dropped = pollableIds.toSet() - currentPollableIds.toSet()
        if (dropped.isNotEmpty()) {
            val droppedNames = currentPoints.filter { it.id in dropped }.joinToString { "#${it.id} ${it.name}" }
            val exitedIds = pollableIds.toSet() - currentIds.toSet()
            if (dropped != exitedIds) {
                DiagnosticRecordLogger.geofence(this, "POLL_POINTS_DONE：$droppedNames 今日已完成/已跳过，移出定位轮询")
            }
        }
        pollableIds = currentPollableIds
        if (currentIds != activeIds) {
            activeIds = currentIds
            registerGeofences(currentPoints)
            DiagnosticRecordLogger.geofence(this, "POLL_ACTIVE_POINTS_CHANGED：当前轮询地点=${currentPollable.ifEmpty { currentPoints }.joinToString { "#${it.id} ${it.name}" }}")
        }
        if (currentPollable.isEmpty()) {
            DiagnosticRecordLogger.geofence(this, "POLL_STOP_ALL_DONE：当前生效时间段的地点今日均已完成或已跳过，停止定位轮询")
            // 围栏保留给下一个生效时间段重建；这里仅停轮询并安排下一次窗口唤醒。
            removeGeofences("今日生效地点均已完成或已跳过")
            sleepUntilNextWindow(points, "今日生效地点均已完成或已跳过", startId)
            return false
        }
        pollActiveIds = activeIds
        pollPollableIds = pollableIds
        pollOnce(currentPollable)
        return true
    }

    @SuppressLint("MissingPermission")
    private suspend fun pollOnce(points: List<CheckInPoint>) {
        val requestedAt = System.currentTimeMillis()
        val cycleMs = if (lastPollStartedAtMs > 0L) requestedAt - lastPollStartedAtMs else -1L
        lastPollStartedAtMs = requestedAt
        PollWatchdog.markCycle(this, requestedAt)
        // 诊断：记录轮询实际周期。周期 = POLL_INTERVAL_MS + 上一轮 pollOnce 耗时，
        // 兜底复核的延迟上界就是这个周期 —— 用于核对「固定 ~18.76s」是否来自轮询节拍。
        DiagnosticRecordLogger.geofence(
            this,
            "POLL_CYCLE：本轮开始 wall=$requestedAt（elapsed=${SystemClock.elapsedRealtime()}），" +
                "距上一轮开始=${cycleMs}ms（POLL_INTERVAL_MS=$POLL_INTERVAL_MS + 上一轮耗时）"
        )
        val location = try {
            withTimeout(LOCATION_QUERY_TIMEOUT_MS) {
                locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
            }
        } catch (_: TimeoutCancellationException) {
            DiagnosticRecordLogger.geofence(this, "POLL_LOCATION_TIMEOUT：${LOCATION_QUERY_TIMEOUT_MS / 1000}s 内未取得当前位置，本轮不触发提醒")
            return
        } catch (error: Exception) {
            DiagnosticRecordLogger.geofence(this, "POLL_LOCATION_ERROR：${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            return
        }
        if (location == null) {
            DiagnosticRecordLogger.geofence(this, "POLL_LOCATION_NULL：当前位置不可用，本轮不触发提醒")
            return
        }
        // v5 任务四: accuracy gate — the log showed fixes drifting 450-660 m with
        // accuracy 50-98 m, which armed confirmations that the 25 s re-check then
        // aborted ("设好了没响"). A fix this coarse cannot decide INSIDE/OUTSIDE
        // against a 200 m radius, so discard it and wait for the next poll.
        if (location.accuracy > POLL_MAX_ACCURACY_M) {
            DiagnosticRecordLogger.geofence(this, "POLL_LOCATION_INACCURATE：accuracy=${"%.1f".format(location.accuracy)}m > ${POLL_MAX_ACCURACY_M.toInt()}m，丢弃本轮定位，避免误进/误出判定")
            return
        }
        val distances = points.associateWith { point -> distanceMeters(point, location) }
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        DiagnosticRecordLogger.geofence(this, "POLL_LOCATION_RECEIVED：耗时=${System.currentTimeMillis() - requestedAt}ms，位置年龄=${ageMs}ms，accuracy=${"%.1f".format(location.accuracy)}m；" + distances.entries.joinToString("；") { (point, distance) -> "#${point.id} ${point.name} distance=${"%.1f".format(distance)}m/radius=${point.radiusMeters}m" })
        distances.forEach { (point, distance) ->
            if (distance <= point.radiusMeters) {
                val confirmAt = point.enteredAt?.let { it + ReminderEngine.LOCATION_CONFIRM_DELAY_MS }
                if (point.triggerState == PointState.ENTERED.name && confirmAt != null && System.currentTimeMillis() >= confirmAt) {
                    // 兜底复核：25 秒确认闹钟在 Doze / 未豁免电池优化时会被系统延后
                    // （历史上晚 18.8s 与约 3m34s）。而 enter() 对已处于 ENTERED 的点会
                    // 直接返回「忽略重复进入」，所以一旦闹钟迟到就没有任何第二条路。
                    // 这里由前台轮询在「同一计划复核时刻已过」时直接执行复核，延迟上界
                    // 收敛到一个轮询周期，而不是等闹钟或维护窗口。
                    DiagnosticRecordLogger.geofence(this, "POLL_CONFIRM_FALLBACK_DIAG：#${point.id} ${point.name} 计划复核=$confirmAt，当前=${System.currentTimeMillis()}，晚=${System.currentTimeMillis() - confirmAt}ms（轮询兜底路径，非闹钟）")
                    DiagnosticRecordLogger.geofence(this, "POLL_CONFIRM_FALLBACK：#${point.id} ${point.name} 仍为 ENTERED 且已过 ${ReminderEngine.LOCATION_CONFIRM_DELAY_MS / 1000}s（计划复核时间=$confirmAt），由轮询直接执行定位复核（复用本轮定位，省去一次查询等待）")
                    ReminderEngine.confirmLocation(this, point.id, confirmAt, location, "POLL_FALLBACK")
                } else {
                    DiagnosticRecordLogger.geofence(this, "POLL_INSIDE：#${point.id} ${point.name} distance=${"%.1f".format(distance)}m ≤ radius=${point.radiusMeters}m，进入确认流程")
                    GeofenceTriggerCoordinator.enter(this, point.id, "POLL_ENTER")
                }
            } else {
                GeofenceTriggerCoordinator.exit(this, point.id, "POLL_EXIT")
            }
        }
    }

    private fun distanceMeters(point: CheckInPoint, location: Location): Float = FloatArray(1).also { Location.distanceBetween(point.latitude, point.longitude, location.latitude, location.longitude, it) }[0]

    private suspend fun sleepUntilNextWindow(points: List<CheckInPoint>, reason: String, startId: Int) {
        pollingJob = null
        // 轮询到此为止：清掉轮询状态、取消还没到的轮询闹钟，并告诉看门狗「现在不该在跑」。
        pollActiveIds = null
        pollPollableIds = emptyList()
        cancelPollTick(this)
        PollWatchdog.markExpected(this, false)
        val nextChange = nextMonitoringChange(points, System.currentTimeMillis())
        if (nextChange == null) {
            cancelMonitoringReevaluation(this)
            DiagnosticRecordLogger.geofence(this, "MONITOR_SLEEP：$reason；没有已启用的分时段地点，停止前台服务")
        } else {
            val exact = scheduleMonitoringReevaluation(this, nextChange)
            DiagnosticRecordLogger.geofence(this, "MONITOR_SLEEP：$reason；已安排${if (exact) "精确" else "非精确"}唤醒，时间=$nextChange（分时段开始前 ${WINDOW_WARMUP_MS / 60_000}s 或状态边界）")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        // A wakeup or a point edit may have started this service again while this older
        // coroutine was cleaning up.  Never stop that newer start request.
        stopSelfResult(startId)
    }

    private fun scheduleReevaluation(at: Long) {
        val exact = scheduleMonitoringReevaluation(this, at)
        scope.launch { DiagnosticRecordLogger.geofence(this@GeofenceMonitoringService, "POLL_REEVALUATION_SCHEDULED：${if (exact) "精确" else "非精确"}调度时间=$at") }
    }

    @SuppressLint("MissingPermission")
    private suspend fun registerGeofences(points: List<CheckInPoint>) {
        val fences = points.map { point -> Geofence.Builder().setRequestId(point.id.toString()).setCircularRegion(point.latitude, point.longitude, point.radiusMeters.toFloat()).setExpirationDuration(Geofence.NEVER_EXPIRE).setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT).build() }
        try { geofenceClient.removeGeofences(geofencePendingIntent(this)).await(); DiagnosticRecordLogger.geofence(this, "REGISTER_OLD_REMOVED：已移除旧围栏") }
        catch (error: Exception) { DiagnosticRecordLogger.geofence(this, "REGISTER_OLD_REMOVE_FAILURE：${failureDetails(error)}") }
        val request = GeofencingRequest.Builder().setInitialTrigger(Geofence.GEOFENCE_TRANSITION_ENTER).addGeofences(fences).build()
        try { geofenceClient.addGeofences(request, geofencePendingIntent(this)).await(); DiagnosticRecordLogger.geofence(this, "REGISTER_SUCCESS：已注册 ${fences.size} 个当前生效的被动围栏，作为主动轮询冗余") }
        catch (error: Exception) { DiagnosticRecordLogger.geofence(this, "REGISTER_FAILURE：${failureDetails(error)}") }
    }

    private suspend fun removeGeofences(reason: String) {
        try { geofenceClient.removeGeofences(geofencePendingIntent(this)).await(); DiagnosticRecordLogger.geofence(this, "REGISTER_REMOVED：$reason") }
        catch (error: Exception) { DiagnosticRecordLogger.geofence(this, "REGISTER_REMOVE_FAILURE：$reason；${failureDetails(error)}") }
    }

    private fun failureDetails(error: Exception): String = when (error) {
        is com.google.android.gms.common.api.ApiException -> "ApiException code=${error.statusCode} (${GeofenceStatusCodes.getStatusCodeString(error.statusCode)}), ${error.message.orEmpty()}"
        else -> "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
    }
    private fun hasLocationPermission() = ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
    private fun locationPermissionSummary(): String {
        val fine = ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return "permission(fine=$fine,coarse=$coarse,background=$background,api=${Build.VERSION.SDK_INT})"
    }
    private fun foregroundNotification() = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("别错过站正在主动定位监测").setContentText("仅在提醒点生效时间段内定位").setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "后台监测", NotificationManager.IMPORTANCE_LOW)) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        logLifecycle("MONITOR_SERVICE_DESTROY：前台服务 onDestroy")
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        logLifecycle("MONITOR_SERVICE_TASK_REMOVED：前台服务 onTaskRemoved（应用被从最近任务里划掉）")
        super.onTaskRemoved(rootIntent)
    }

    /** onDestroy / onTaskRemoved 的诊断日志：服务自己的协程作用域马上要取消，另起一个把日志写完。 */
    private fun logLifecycle(what: String) {
        val app = applicationContext
        val detail = "$what；轮询任务在跑=${pollingJob?.isActive == true}，有轮询状态=${pollActiveIds != null}，" +
            "看门狗认为轮询应在运行=${PollWatchdog.expected(app)}，最后一次 POLL_CYCLE=${PollWatchdog.lastCycleAt(app)}，现在=${System.currentTimeMillis()}"
        CoroutineScope(Dispatchers.IO).launch { runCatching { DiagnosticRecordLogger.geofence(app, detail) } }
    }

    companion object {
        const val CHANNEL_ID = "geofence_monitoring"
        private const val FOREGROUND_ID = 301
        private const val POLL_INTERVAL_MS = 25_000L
        /** 轮询闹钟的广播交到服务手里之前持有的锁；服务这一轮拿到自己的锁后释放它。 */
        private val tickHandoff = java.util.concurrent.atomic.AtomicReference<PowerManager.WakeLock?>(null)

        private fun releaseTickHandoff() = AlarmWakeLock.release(tickHandoff.getAndSet(null))

        /**
         * 轮询闹钟到了（[MonitoringScheduleReceiver]，在 onReceive 里同步调用）：先持锁，再把这一轮交给服务。
         * @return null = 已交给服务；否则是启动前台服务时的异常（锁已释放）。
         */
        fun startPollTick(context: Context): Exception? {
            AlarmWakeLock.release(tickHandoff.getAndSet(AlarmWakeLock.acquire(context, "GeofencePollTick")))
            return try {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, GeofenceMonitoringService::class.java).setAction(ACTION_POLL_TICK)
                )
                null
            } catch (error: Exception) {
                releaseTickHandoff()
                error
            }
        }
        /** v5 任务四: polling fixes coarser than this are discarded (drift guard). */
        private const val POLL_MAX_ACCURACY_M = 50f
        private const val LOCATION_QUERY_TIMEOUT_MS = 15_000L
        fun start(context: Context) = androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, GeofenceMonitoringService::class.java))
        /** v7: 停止后台监测（首页「停止后台监测」按钮）。 */
        fun stop(context: Context) {
            // 用户手动停止：取消还没到的轮询闹钟，并告诉看门狗不要再把它拉起来。
            cancelPollTick(context)
            PollWatchdog.markExpected(context, false)
            context.stopService(Intent(context, GeofenceMonitoringService::class.java))
        }
        fun geofencePendingIntent(context: Context): PendingIntent {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(context, 1001, Intent(context, GeofenceBroadcastReceiver::class.java).setAction("com.checkinassistant.GEOFENCE"), flags)
        }
    }
}
