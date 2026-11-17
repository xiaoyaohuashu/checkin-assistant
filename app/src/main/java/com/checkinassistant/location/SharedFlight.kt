package com.checkinassistant.location

/**
 * v2.2.26（中 4）：把同时来的多个「取一次结果」的请求合成一趟。
 * 第一个人加入时开始（[start]），后来的人并入同一趟；有结果（[deliver]）或失败（[fail]）时所有人一起收到，
 * 然后停下（[stop]）；最后一个等的人取消时也停下。
 * 不是线程安全的：只在一个线程里用（高德定位用它，都在主线程）。
 *
 * 已知限制：后并入的人会和先到的人一起收到失败（哪怕他才等了几秒）。
 */
internal class SharedFlight<T>(private val start: () -> Unit, private val stop: () -> Unit) {
    private class Waiter<T>(val onResult: (T) -> Unit, val onError: (Exception) -> Unit)

    private val waiters = ArrayList<Waiter<T>>()

    val active: Boolean get() = waiters.isNotEmpty()

    /** 加入。返回「我不等了」的取消函数（重复调用无害）。 */
    fun join(onResult: (T) -> Unit, onError: (Exception) -> Unit): () -> Unit {
        val waiter = Waiter(onResult, onError)
        val first = waiters.isEmpty()
        waiters.add(waiter)
        if (first) {
            try {
                start()
            } catch (e: Exception) {
                fail(e)
            }
        }
        return {
            if (waiters.remove(waiter) && waiters.isEmpty()) stop()
        }
    }

    fun deliver(value: T) = finish { it.onResult(value) }

    fun fail(error: Exception) = finish { it.onError(error) }

    /** 先清空并停下，再逐个通知：回调里再加入算新的一趟；一个回调出错不影响其他人。 */
    private fun finish(notify: (Waiter<T>) -> Unit) {
        if (waiters.isEmpty()) return
        val snapshot = ArrayList(waiters)
        waiters.clear()
        try { stop() } catch (_: Exception) { }
        snapshot.forEach { waiter -> try { notify(waiter) } catch (_: Exception) { } }
    }
}
