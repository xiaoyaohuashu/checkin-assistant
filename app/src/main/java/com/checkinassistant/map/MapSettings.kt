package com.checkinassistant.map

import android.content.Context
import android.content.pm.PackageManager
import com.checkinassistant.domain.AmapPrivacy
import com.checkinassistant.domain.MapServiceRules
import com.checkinassistant.domain.MapServiceSetting

/** v2.2.25：「地图服务」设置和高德隐私同意状态（只存本机）。这里不碰任何高德类。 */
internal object MapSettings {
    private const val PREFERENCES_NAME = "map_settings"
    private const val KEY_MAP_SERVICE = "map_service"
    private const val KEY_AMAP_PRIVACY = "amap_privacy"
    private const val KEY_AMAP_NOTICE_AT = "amap_notice_at"
    private const val AMAP_KEY_META = "com.amap.api.v2.apikey"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun setting(context: Context): MapServiceSetting = MapServiceRules.parseSetting(prefs(context).getString(KEY_MAP_SERVICE, null))
    fun setSetting(context: Context, value: MapServiceSetting): Boolean = prefs(context).edit().putString(KEY_MAP_SERVICE, value.name).commit()

    fun privacy(context: Context): AmapPrivacy = MapServiceRules.parsePrivacy(prefs(context).getString(KEY_AMAP_PRIVACY, null))
    fun setPrivacy(context: Context, value: AmapPrivacy): Boolean = prefs(context).edit().putString(KEY_AMAP_PRIVACY, value.name).commit()

    /** v2.2.26：上次发「请同意高德隐私说明」通知的时间（毫秒）；没发过是 0。 */
    fun amapNoticeAt(context: Context): Long = prefs(context).getLong(KEY_AMAP_NOTICE_AT, 0L)
    fun setAmapNoticeAt(context: Context, atMs: Long): Boolean = prefs(context).edit().putLong(KEY_AMAP_NOTICE_AT, atMs).commit()

    /** v2.2.26：后台定位想用高德却用不了的原因（见 [LocationBlock]）。 */
    fun locationBlock(context: Context): com.checkinassistant.domain.LocationBlock =
        com.checkinassistant.domain.LocationSourceRules.block(
            setting(context), com.checkinassistant.location.PlayServices.isInstalled(context), amapKeyConfigured(context), privacy(context)
        )

    /** local.properties 里填了 AMAP_API_KEY 才算配置了（没填时 Manifest 里是空字符串）。 */
    fun amapKeyConfigured(context: Context): Boolean = try {
        val app = context.applicationContext
        !app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString(AMAP_KEY_META).isNullOrBlank()
    } catch (e: Exception) {
        false
    }

    /** 有 Key 且同意了隐私说明。 */
    fun amapReady(context: Context): Boolean = amapKeyConfigured(context) && privacy(context) == AmapPrivacy.AGREED
}
