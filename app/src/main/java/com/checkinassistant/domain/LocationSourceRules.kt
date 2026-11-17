package com.checkinassistant.domain

/** v2.2.26：取位置用谷歌还是高德。 */
enum class LocationSource { GOOGLE, AMAP }

/**
 * v2.2.26（中 5）：后台定位想用高德却用不了的原因（给首页卡片和通知用）。
 * - [AMAP_PRIVACY_NEEDED]：差「同意高德隐私说明」这一步（发通知 + 卡片可点）。
 * - [AMAP_KEY_MISSING_NO_GMS]：没有 Google Play 服务，安装包又没配高德 Key，后台定位用不了。
 * - [AMAP_OFF_USING_GOOGLE]：选了「始终高德」但高德用不了（没配 Key / 明确不同意），目前仍用谷歌定位，只在卡片说明。
 */
enum class LocationBlock { NONE, AMAP_PRIVACY_NEEDED, AMAP_KEY_MISSING_NO_GMS, AMAP_OFF_USING_GOOGLE }

/** v2.2.26（中 1）：定位来源的规则（纯函数）。 */
object LocationSourceRules {
    /**
     * - 设置是「始终高德」且高德可用（配了 Key、同意了隐私说明）→ 高德；
     * - 否则有可用的 Google Play 服务 → 谷歌（「自动」「始终谷歌」的后台定位规则不变）；
     * - 否则高德可用 → 高德；
     * - 都不行 → 谷歌（取不到位置，和 v2.2.24 在这种手机上一样）。
     */
    fun pick(setting: MapServiceSetting, hasGms: Boolean, amapUsable: Boolean): LocationSource = when {
        setting == MapServiceSetting.AMAP && amapUsable -> LocationSource.AMAP
        hasGms -> LocationSource.GOOGLE
        amapUsable -> LocationSource.AMAP
        else -> LocationSource.GOOGLE
    }

    /**
     * 定位来源有没有可能是高德。为假（有 Google Play 服务，且设置不是「始终高德」）时，
     * 直接用谷歌定位对象，和 v2.2.24 完全一样。
     */
    fun needsDynamic(setting: MapServiceSetting, hasGms: Boolean): Boolean =
        setting == MapServiceSetting.AMAP || !hasGms

    /**
     * 改设置后要不要重建后台服务：只有「有 Google Play 服务的手机上，从别的选项切到始终高德」才需要——
     * 这时服务手里拿的是谷歌定位对象，不重建就换不了。反方向不需要（服务手里的对象每轮重新判断，自己会退回谷歌）。
     */
    fun block(setting: MapServiceSetting, hasGms: Boolean, keyConfigured: Boolean, privacy: AmapPrivacy): LocationBlock = when {
        !needsDynamic(setting, hasGms) -> LocationBlock.NONE
        !keyConfigured -> if (hasGms) LocationBlock.AMAP_OFF_USING_GOOGLE else LocationBlock.AMAP_KEY_MISSING_NO_GMS
        privacy == AmapPrivacy.AGREED -> LocationBlock.NONE
        privacy == AmapPrivacy.UNKNOWN -> LocationBlock.AMAP_PRIVACY_NEEDED
        // 明确不同意（或撤回了同意）：没有 Google Play 服务时定位全废，仍要提醒；有的话谷歌能用，只在卡片说明
        else -> if (hasGms) LocationBlock.AMAP_OFF_USING_GOOGLE else LocationBlock.AMAP_PRIVACY_NEEDED
    }

    /** 首页监测卡片上的原因文字；null = 不显示。 */
    fun blockText(block: LocationBlock, hasGms: Boolean, keyConfigured: Boolean): String? = when (block) {
        LocationBlock.NONE -> null
        LocationBlock.AMAP_PRIVACY_NEEDED ->
            if (hasGms) "已选「始终高德」但还没同意高德隐私说明，后台定位暂时仍用谷歌。点这里同意"
            else "还没同意高德隐私说明，后台定位不可用。点这里同意"
        LocationBlock.AMAP_KEY_MISSING_NO_GMS -> "这台手机没有可用的 Google Play 服务，安装包也没配置高德 Key，后台定位不可用"
        LocationBlock.AMAP_OFF_USING_GOOGLE ->
            if (keyConfigured) "已选「始终高德」，但没有同意高德隐私说明，后台定位仍用谷歌"
            else "已选「始终高德」，但安装包没配置高德 Key，后台定位仍用谷歌"
    }

    const val NOTIFY_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /** 通知节流：距上次不少于 24 小时才再发；手机时间被往回调过也发。 */
    fun shouldNotify(lastAtMs: Long, nowMs: Long): Boolean = nowMs < lastAtMs || nowMs - lastAtMs >= NOTIFY_INTERVAL_MS

    fun needsServiceRecreate(old: MapServiceSetting, new: MapServiceSetting, hasGms: Boolean): Boolean =
        hasGms && old != MapServiceSetting.AMAP && new == MapServiceSetting.AMAP
}
