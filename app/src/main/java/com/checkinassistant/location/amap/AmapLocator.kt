package com.checkinassistant.location.amap

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.checkinassistant.domain.CoordConverter
import com.checkinassistant.location.SharedFlight
import com.checkinassistant.map.amap.AmapSdk

/**
 * v2.2.26（中 4）：高德定位客户端只建一个、一直复用（v2.2.25 是每取一次位置新建再销毁一个）。
 * - 只在主线程调用；同时来的请求合成一趟（[SharedFlight]）。
 * - 每趟最多 20 秒，到点算失败并销毁客户端，免得卡死的客户端被一直复用。
 * - 撤回隐私同意、高德不可用时销毁（[destroy]）；之后再请求会重新创建。
 * 「只建一个」只对本项目自己的代码成立：高德地图上的蓝点由高德 SDK 内部另外管理。
 */
internal object AmapLocator {
    private const val WATCHDOG_MS = 20_000L

    private val handler = Handler(Looper.getMainLooper())
    private var client: AMapLocationClient? = null
    private val watchdog = Runnable {
        flight.fail(IllegalStateException("高德定位 ${WATCHDOG_MS / 1000} 秒没有结果"))
        destroy()
    }
    private val flight: SharedFlight<Location?> = SharedFlight(
        start = {
            val c = client ?: throw IllegalStateException("高德定位客户端未创建")
            c.setLocationOption(option())
            c.startLocation()
            handler.postDelayed(watchdog, WATCHDOG_MS)
        },
        stop = {
            handler.removeCallbacks(watchdog)
            runCatching { client?.stopLocation() }
        }
    )

    private fun option(): AMapLocationClientOption = AMapLocationClientOption()
        .setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
        .setOnceLocation(true)
        .setOnceLocationLatest(true)
        .setNeedAddress(false)
        .setLocationCacheEnable(false)
        .setHttpTimeOut(15_000L)

    /** 取一次位置（WGS-84）。回调在主线程。返回取消函数（也要在主线程调）。 */
    fun request(app: Context, onResult: (Location?) -> Unit, onError: (Exception) -> Unit): () -> Unit {
        if (!AmapSdk.ensureReady(app)) {
            destroy()
            onError(IllegalStateException("高德定位不可用（未配置 Key 或未同意隐私说明）"))
            return {}
        }
        if (client == null) {
            try {
                client = AMapLocationClient(app.applicationContext).also { created ->
                    created.setLocationListener { location -> onLocation(location) }
                }
            } catch (t: Throwable) {
                client = null
                onError(IllegalStateException("高德定位初始化失败：${t.message ?: t.javaClass.simpleName}", t))
                return {}
            }
        }
        return try {
            flight.join(onResult, onError)
        } catch (t: Throwable) {
            // start 里抛了不是 Exception 的错（比如原生库问题）
            destroy()
            onError(IllegalStateException("高德定位启动失败：${t.message ?: t.javaClass.simpleName}", t))
            return {}
        }
    }

    private fun onLocation(location: AMapLocation?) {
        if (!flight.active) return
        try {
            flight.deliver(convert(location))
        } catch (e: Exception) {
            flight.fail(e)
        }
    }

    /** 先让正在等的人收到失败，再销毁客户端。 */
    fun destroy() {
        flight.fail(IllegalStateException("高德定位已停止"))
        handler.removeCallbacks(watchdog)
        client?.let { c ->
            runCatching { c.stopLocation() }
            runCatching { c.onDestroy() }
        }
        client = null
    }

    private fun convert(location: AMapLocation?): Location? {
        location ?: return null
        if (location.errorCode != 0) {
            throw IllegalStateException("高德定位失败 errorCode=${location.errorCode}，${location.errorInfo}")
        }
        val wgs = CoordConverter.toWgs(location.latitude, location.longitude, location.coordType == AMapLocation.COORD_TYPE_GCJ02)
        return Location("amap").apply {
            latitude = wgs[0]
            longitude = wgs[1]
            if (location.hasAccuracy()) accuracy = location.accuracy
            time = if (location.time > 0) location.time else System.currentTimeMillis()
            elapsedRealtimeNanos = if (location.elapsedRealtimeNanos > 0) location.elapsedRealtimeNanos else SystemClock.elapsedRealtimeNanos()
        }
    }
}
