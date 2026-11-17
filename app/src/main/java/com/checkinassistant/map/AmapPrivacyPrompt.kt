package com.checkinassistant.map

import android.app.Activity
import android.app.AlertDialog
import android.text.util.Linkify
import android.widget.TextView
import android.widget.Toast
import com.checkinassistant.domain.AmapPrivacy
import com.checkinassistant.domain.LocationSourceRules
import com.checkinassistant.location.PlayServices
import com.checkinassistant.map.amap.AmapSdk

/**
 * v2.2.25：高德隐私说明。只在第一次真正要用高德时弹：地图页决定用高德、设置里选「始终高德」、
 * 没有 Google Play 服务的手机在首页点「启动后台监测」。有 Google Play 服务、人在国外用谷歌时永远不会弹。
 */
internal object AmapPrivacyPrompt {
    const val POLICY_URL = "https://lbs.amap.com/pages/privacy/"

    fun ask(activity: Activity, onAgree: () -> Unit, onDecline: () -> Unit, onCancel: () -> Unit) {
        val message = "要用高德地图，需要先同意下面的说明：\n\n" +
            "地图显示、选点、地址搜索和定位会用到高德开放平台的 SDK（高德软件有限公司）。" +
            "使用时高德 SDK 会收集位置信息（GPS、Wi-Fi、基站）和设备信息（如设备标识、网络状态），用于提供地图、定位和搜索服务。\n\n" +
            "高德隐私政策：$POLICY_URL\n\n" +
            "不同意也可以继续用谷歌地图（需要手机有 Google Play 服务）。"
        val dialog = AlertDialog.Builder(activity)
            .setTitle("高德地图隐私说明")
            .setMessage(message)
            .setPositiveButton("同意") { d, _ ->
                MapSettings.setPrivacy(activity, AmapPrivacy.AGREED)
                AmapSdk.ensureReady(activity)
                AmapNotice.cancel(activity)
                d.dismiss()
                onAgree()
            }
            .setNegativeButton("不同意") { d, _ ->
                MapSettings.setPrivacy(activity, AmapPrivacy.DECLINED)
                d.dismiss()
                onDecline()
            }
            .setOnCancelListener { onCancel() }
            .show()
        (dialog.findViewById<TextView>(android.R.id.message))?.let { Linkify.addLinks(it, Linkify.WEB_URLS) }
    }

    /**
     * 首页点「启动后台监测」之后调用。后台定位不可能用高德时（有 Google Play 服务、设置不是「始终高德」）立刻返回，什么也不做。
     * [onChanged]：隐私状态可能变了（同意 / 不同意 / 关掉弹窗）之后回调，首页用来刷新监测卡片上的原因。
     */
    fun offerOnMonitorStart(activity: Activity, onChanged: () -> Unit = {}) {
        val hasGms = PlayServices.isInstalled(activity)
        if (!LocationSourceRules.needsDynamic(MapSettings.setting(activity), hasGms)) return
        if (!MapSettings.amapKeyConfigured(activity)) {
            if (!hasGms) Toast.makeText(activity, "这台手机没有 Google Play 服务，需要高德定位，但未配置高德 Key：后台定位不可用", Toast.LENGTH_LONG).show()
            return
        }
        when (MapSettings.privacy(activity)) {
            AmapPrivacy.AGREED -> AmapSdk.ensureReady(activity)
            AmapPrivacy.UNKNOWN -> ask(
                activity,
                onAgree = onChanged,
                onDecline = { if (!hasGms) declinedToast(activity); onChanged() },
                onCancel = onChanged
            )
            AmapPrivacy.DECLINED -> if (!hasGms) declinedToast(activity)
        }
    }

    /**
     * v2.2.26（中 6）：设置页「撤回高德隐私同意」。确认后：状态改为不同意 → 通知高德 SDK 并销毁定位客户端 →
     * 之后地图、搜索、定位都不再用高德（「始终高德」会退回谷歌）。撤回后 24 小时内不发「请同意」的通知。
     */
    fun confirmRevoke(activity: Activity, onDone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("撤回高德隐私同意")
            .setMessage(
                "撤回后，地图、地址搜索和定位都不再使用高德：\n" +
                    "· 地图页改用谷歌地图\n" +
                    "· 后台定位改用谷歌定位（这台手机没有可用的 Google Play 服务时，后台定位将不可用）\n\n" +
                    "以后想再用高德，在「地图服务」里选「始终高德」重新同意即可。"
            )
            .setPositiveButton("撤回") { _, _ ->
                MapSettings.setPrivacy(activity, AmapPrivacy.DECLINED)
                AmapSdk.revoke(activity)
                MapSettings.setAmapNoticeAt(activity, System.currentTimeMillis())
                AmapNotice.cancel(activity)
                Toast.makeText(activity, "已撤回，之后不再使用高德", Toast.LENGTH_LONG).show()
                onDone()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun declinedToast(activity: Activity) {
        Toast.makeText(
            activity,
            "未同意高德隐私说明，这台手机没有 Google Play 服务，后台定位不可用；可在设置「地图服务」里选「始终高德」重新同意",
            Toast.LENGTH_LONG
        ).show()
    }
}
