package com.checkinassistant.location

import android.content.Context
import android.location.Location
import com.checkinassistant.domain.LocationSource
import com.checkinassistant.domain.LocationSourceRules
import com.checkinassistant.location.amap.AmapLocationProvider
import com.checkinassistant.location.google.GoogleLocationProvider
import com.checkinassistant.map.AmapNotice
import com.checkinassistant.map.MapSettings
import com.checkinassistant.map.amap.AmapSdk

/**
 * 定位来源可能是高德的两种情况用它（v2.2.25 叫 NoGmsLocationProvider，只管没有 Google Play 服务的手机）：
 * - 没有可用的 Google Play 服务；
 * - v2.2.26（中 1）：设置里「地图服务」选了「始终高德」。
 * 每次取位置都重新按 [LocationSourceRules.pick] 判断（后台服务里的定位对象是一直用到底的，
 * 用户中途同意 / 撤回隐私说明、改设置后，下一轮就按新状态走）。
 * 高德不能用（没配 Key / 没同意隐私）时交给谷歌实现。
 */
internal class DynamicLocationProvider(private val context: Context) : LocationProvider {
    /** 退回谷歌时用同一个对象，不每轮新建。 */
    private val google by lazy { GoogleLocationProvider(context) }

    private fun pick(): LocationProvider {
        val source = LocationSourceRules.pick(
            setting = MapSettings.setting(context),
            hasGms = PlayServices.isInstalled(context),
            amapUsable = MapSettings.amapReady(context) && AmapSdk.ensureReady(context)
        )
        return if (source == LocationSource.AMAP) AmapLocationProvider(context) else google
    }

    override suspend fun currentLocationHighAccuracy(): Location? {
        // v2.2.26（中 5）：后台取位置时，如果只差「同意高德隐私说明」，发通知提醒（每 24 小时最多一条），不悄悄失败
        AmapNotice.maybeNotify(context, MapSettings.locationBlock(context))
        return pick().currentLocationHighAccuracy()
    }

    override fun requestCurrentLocationHighAccuracy(onSuccess: (Location?) -> Unit, onFailure: (Exception) -> Unit) =
        pick().requestCurrentLocationHighAccuracy(onSuccess, onFailure)
}
