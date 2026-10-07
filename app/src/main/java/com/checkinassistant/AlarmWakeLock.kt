package com.checkinassistant

import android.content.Context
import android.os.PowerManager

/**
 * 广播接收器里做异步工作时持有的部分唤醒锁。
 *
 * `goAsync()` 只延长「这条广播尚未处理完」的生命周期，并**不**保证 CPU 保持唤醒。
 * 系统为广播持有的唤醒锁一旦释放，设备就可以再次挂起 —— 此时 `getCurrentLocation()`
 * 这类 await 会随 CPU 一起停住，直到下一次系统唤醒才继续，表现为复核/提醒晚到数分钟。
 *
 * 因此凡是「收到广播 → goAsync → 协程里查定位」的路径，都必须自己持锁，
 * 在 finally 中释放；超时上限由系统兜底，防止异常路径漏放。
 */
internal object AlarmWakeLock {

    /** 单次异步工作的最长持锁时间（含最长 15s 的定位查询 + 数据库读写）。 */
    private const val TIMEOUT_MS = 60_000L

    fun acquire(context: Context, tag: String): PowerManager.WakeLock? = runCatching {
        context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "checkinassistant:$tag")
            .apply {
                setReferenceCounted(false)
                acquire(TIMEOUT_MS)
            }
    }.getOrNull()

    fun release(lock: PowerManager.WakeLock?) {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }
}
