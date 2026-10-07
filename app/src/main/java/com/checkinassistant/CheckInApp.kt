package com.checkinassistant

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.checkinassistant.data.DiagnosticRecordLogger
import com.checkinassistant.geofence.PollWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 进程级诊断（v2.2.12）：每次进程启动记 PROCESS_START，并把系统记录的「上一次进程退出原因」
 * 记成 PROCESS_LAST_EXIT —— 用来回答「轮询静默停止时进程是不是被杀了、被谁杀的」。
 * 进程启动本身也是一次唤醒，顺带跑一次轮询看门狗。
 */
class CheckInApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val startedWall = System.currentTimeMillis()
        val startedElapsed = SystemClock.elapsedRealtime()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                DiagnosticRecordLogger.geofence(
                    this@CheckInApp,
                    "PROCESS_START：进程启动 pid=${Process.myPid()}，wall=$startedWall，elapsed=$startedElapsed，" +
                        "轮询应在运行=${PollWatchdog.expected(this@CheckInApp)}，最后一次 POLL_CYCLE=${PollWatchdog.lastCycleAt(this@CheckInApp)}"
                )
                DiagnosticRecordLogger.geofence(this@CheckInApp, "PROCESS_LAST_EXIT：${lastExit()}")
            }
            runCatching { PollWatchdog.check(this@CheckInApp, "PROCESS_START") }
        }
    }

    private fun lastExit(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "系统版本低于 Android 11（api=${Build.VERSION.SDK_INT}），没有 getHistoricalProcessExitReasons"
        val info = runCatching {
            getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()
        }.getOrElse { return "查询失败 ${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
            ?: return "系统没有这个应用的进程退出记录"
        return "上一次退出时间=${info.timestamp}，原因=${reasonName(info.reason)}(${info.reason})，子原因status=${info.status}，" +
            "退出时进程重要性=${info.importance}，pid=${info.pid}，描述=${info.description ?: "<无>"}"
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        else -> "UNKNOWN"
    }
}
