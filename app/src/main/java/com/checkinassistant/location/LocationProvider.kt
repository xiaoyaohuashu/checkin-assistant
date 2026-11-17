package com.checkinassistant.location

import android.content.Context
import android.location.Location
import com.checkinassistant.domain.LocationSourceRules
import com.checkinassistant.domain.MapBackend
import com.checkinassistant.location.amap.AmapLocationProvider
import com.checkinassistant.location.google.GoogleLocationProvider
import com.checkinassistant.map.MapSettings

/**
 * 「取一次当前位置」的统一入口。App 里其它地方只认这个接口，不直接碰 Google 定位；
 * 有 Google Play 服务、设置不是「始终高德」时用 Google 实现（和 v2.2.24 完全一样）；否则用 [DynamicLocationProvider]（可能是高德）。
 *
 * 接口故意做得很薄，下面这些都留在调用方，和接口化之前一样：
 * 15 秒超时（withTimeout）、权限判断、出错和超时的日志、精度门槛。
 */
interface LocationProvider {
    /**
     * 高精度取一次当前位置（协程版，盯位置和 25 秒复核用）。
     * 取不到返回 null；底层出错原样抛出、不包装；本身不带超时。
     */
    suspend fun currentLocationHighAccuracy(): Location?

    /**
     * 高精度取一次当前位置（回调版，只有地图选点页用）。回调在主线程。
     * 先挂成功回调、再挂失败回调，顺序和接口化之前一致。
     */
    fun requestCurrentLocationHighAccuracy(onSuccess: (Location?) -> Unit, onFailure: (Exception) -> Unit)
}

object LocationProviders {
    /**
     * 每次调用都新建一个，不做缓存，也不把 context 换成 applicationContext：
     * 保持原来「后台服务一个实例用到底、25 秒复核每次新建、地图页打开时新建」的差别。
     */
    fun get(context: Context): LocationProvider =
        if (LocationSourceRules.needsDynamic(MapSettings.setting(context), PlayServices.isInstalled(context))) DynamicLocationProvider(context)
        else GoogleLocationProvider(context)

    /**
     * v2.2.26（中 1）：地图选点页用。地图是高德时定位也用高德；否则同 [get]。
     * 高德地图只有配了 Key、同意了隐私说明才会出现，所以这里不用再判断。
     */
    fun forMap(context: Context, backend: MapBackend): LocationProvider =
        if (backend == MapBackend.AMAP) AmapLocationProvider(context) else get(context)
}
