package com.checkinassistant.map.amap

import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import com.checkinassistant.map.MapSettings

/**
 * v2.2.25：高德隐私合规。调用任何高德接口之前先调这里。
 * 只有配了 Key、用户同意了隐私说明才会调 updatePrivacyShow / updatePrivacyAgree（地图、定位、搜索三套各一次），
 * 每个进程都要调（是进程内的状态），可以重复调。CheckInApp 启动时不调，用谷歌的人永远走不到这里。
 */
internal object AmapSdk {
    fun ensureReady(context: Context): Boolean {
        val app = context.applicationContext
        if (!MapSettings.amapReady(app)) return false
        return try {
            MapsInitializer.updatePrivacyShow(app, true, true)
            MapsInitializer.updatePrivacyAgree(app, true)
            AMapLocationClient.updatePrivacyShow(app, true, true)
            AMapLocationClient.updatePrivacyAgree(app, true)
            ServiceSettings.updatePrivacyShow(app, true, true)
            ServiceSettings.updatePrivacyAgree(app, true)
            true
        } catch (t: Throwable) {
            // 比如原生库加载失败（UnsatisfiedLinkError 不是 Exception），退回谷歌
            false
        }
    }

    /**
     * v2.2.26（中 6）：撤回隐私同意。调用前调用方已把隐私状态改成「不同意」，所以之后 [ensureReady] 一律返回 false，
     * 地图、搜索、定位的所有入口都不会再创建高德对象。这里再告诉高德 SDK「不同意」，并销毁复用的定位客户端。
     */
    fun revoke(context: Context) {
        val app = context.applicationContext
        try { MapsInitializer.updatePrivacyAgree(app, false) } catch (t: Throwable) { }
        try { AMapLocationClient.updatePrivacyAgree(app, false) } catch (t: Throwable) { }
        try { ServiceSettings.updatePrivacyAgree(app, false) } catch (t: Throwable) { }
        try { com.checkinassistant.location.amap.AmapLocator.destroy() } catch (t: Throwable) { }
    }
}
