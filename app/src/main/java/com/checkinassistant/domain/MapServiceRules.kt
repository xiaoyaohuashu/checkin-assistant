package com.checkinassistant.domain

/** v2.2.25：设置页「地图服务」。 */
enum class MapServiceSetting { AUTO, GOOGLE, AMAP }
enum class MapBackend { GOOGLE, AMAP }
/** 高德隐私说明：还没问过 / 同意 / 不同意。只存在这台手机上，不进备份。 */
enum class AmapPrivacy { UNKNOWN, AGREED, DECLINED }

sealed class MapDecision {
    /** 用 [backend]；[notice] 不为空时提示用户一下。 */
    data class Use(val backend: MapBackend, val notice: String?) : MapDecision()
    /** 要用高德，但还没问过隐私说明：先弹窗。 */
    object AskPrivacy : MapDecision()
}

/** v2.2.25：地图页用谷歌还是高德（纯函数）。 */
object MapServiceRules {
    /** 「当前位置」用最近一次已知位置，太旧（超过 6 小时）就当拿不到，改用地图中心。 */
    const val LAST_KNOWN_MAX_AGE_MS = 6 * 60 * 60 * 1000L

    fun parseSetting(value: String?): MapServiceSetting =
        MapServiceSetting.values().firstOrNull { it.name == value } ?: MapServiceSetting.AUTO

    fun parsePrivacy(value: String?): AmapPrivacy =
        AmapPrivacy.values().firstOrNull { it.name == value } ?: AmapPrivacy.UNKNOWN

    fun usableLastKnown(ageMs: Long): Boolean = ageMs in 0..LAST_KNOWN_MAX_AGE_MS

    /**
     * 规则（需求原文）：
     * - 自动：没有 Google Play 服务 → 高德；有的话，人在中国大陆 → 高德，否则 → 谷歌。
     * - 始终谷歌：没有 Google Play 服务时提示并改用高德。
     * - 始终高德：高德。
     * 想用高德时：没配 Key → 退回谷歌并提示；没问过隐私 → 先问；不同意 → 退回谷歌。
     */
    fun decide(
        setting: MapServiceSetting,
        hasGms: Boolean,
        inMainland: Boolean,
        amapKeyConfigured: Boolean,
        privacy: AmapPrivacy
    ): MapDecision {
        val wantAmap: Boolean
        var notice: String? = null
        when (setting) {
            MapServiceSetting.AUTO -> wantAmap = !hasGms || inMainland
            MapServiceSetting.GOOGLE -> {
                wantAmap = !hasGms
                if (!hasGms) notice = "这台手机没有 Google Play 服务，已改用高德地图"
            }
            MapServiceSetting.AMAP -> wantAmap = true
        }
        if (!wantAmap) return MapDecision.Use(MapBackend.GOOGLE, null)
        if (!amapKeyConfigured) {
            return MapDecision.Use(
                MapBackend.GOOGLE,
                "未配置高德 Key，已改用谷歌地图" + if (!hasGms) "（这台手机没有 Google Play 服务，地图和定位可能不可用）" else ""
            )
        }
        return when (privacy) {
            AmapPrivacy.AGREED -> MapDecision.Use(MapBackend.AMAP, notice)
            AmapPrivacy.UNKNOWN -> MapDecision.AskPrivacy
            AmapPrivacy.DECLINED -> MapDecision.Use(
                MapBackend.GOOGLE,
                if (!hasGms) "未同意高德隐私说明，且这台手机没有 Google Play 服务，地图和定位不可用" else null
            )
        }
    }
}

/**
 * 「手机有没有可用的 Google Play 服务」。
 * v2.2.26（中 2）：只有查询结果是 SUCCESS(0) 才算有；没装、无效、被停用、需要更新、正在更新一律算没有。
 * （v2.2.25 只把没装和无效算没有。）
 */
object PlayServicesRules {
    const val SUCCESS = 0
    fun isInstalled(resultCode: Int): Boolean = resultCode == SUCCESS
}
