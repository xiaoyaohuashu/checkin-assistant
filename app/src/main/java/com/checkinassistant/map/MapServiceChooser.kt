package com.checkinassistant.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.checkinassistant.domain.ChinaRegion
import com.checkinassistant.domain.MapDecision
import com.checkinassistant.domain.MapServiceRules
import com.checkinassistant.domain.MapServiceSetting
import com.checkinassistant.location.PlayServices

/** v2.2.25：地图页打开时决定用谷歌还是高德。这里不碰任何高德类。 */
internal object MapServiceChooser {
    /** @param fallbackCenter 拿不到当前位置时用它判断（编辑页带来的坐标，或默认位置）。 */
    fun choose(context: Context, fallbackCenter: GeoPoint): MapDecision {
        val setting = MapSettings.setting(context)
        val hasGms = PlayServices.isInstalled(context)
        // 只有「自动 + 有 Google Play 服务」才需要知道人在不在大陆
        val inMainland = if (setting == MapServiceSetting.AUTO && hasGms) {
            val here = lastKnown(context)
            if (here != null) ChinaRegion.isMainland(here.latitude, here.longitude)
            else ChinaRegion.isMainland(fallbackCenter.latitude, fallbackCenter.longitude)
        } else false
        return MapServiceRules.decide(setting, hasGms, inMainland, MapSettings.amapKeyConfigured(context), MapSettings.privacy(context))
    }

    /** 系统记着的最近一次位置（不发起新定位）。没权限、没有或太旧都返回 null。全工程只有这里用 getLastKnownLocation。 */
    private fun lastKnown(context: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val now = SystemClock.elapsedRealtimeNanos()
        return try {
            manager.getProviders(true).mapNotNull { provider ->
                try { manager.getLastKnownLocation(provider) } catch (e: SecurityException) { null } catch (e: IllegalArgumentException) { null }
            }.filter { MapServiceRules.usableLastKnown((now - it.elapsedRealtimeNanos) / 1_000_000L) }
                .maxByOrNull { it.elapsedRealtimeNanos }
        } catch (e: Exception) {
            null
        }
    }
}
