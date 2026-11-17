package com.checkinassistant.location

import android.content.Context
import com.checkinassistant.domain.PlayServicesRules
import com.google.android.gms.common.GoogleApiAvailability

/** 手机上有没有可用的 Google Play 服务（v2.2.26：只有完全可用才算有）。全工程只有这里查 GoogleApiAvailability。 */
internal object PlayServices {
    /** 查询本身出错时按「有」处理：出问题也只是退回 v2.2.24 的行为。 */
    fun isInstalled(context: Context): Boolean = try {
        PlayServicesRules.isInstalled(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context))
    } catch (e: Exception) {
        true
    }
}
