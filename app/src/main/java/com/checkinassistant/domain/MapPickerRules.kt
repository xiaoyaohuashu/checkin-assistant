package com.checkinassistant.domain

/**
 * 地图选点页的两个判断，单独放在这里是为了能用单元测试直接测（不依赖地图和定位）。
 */
internal object MapPickerRules {
    /** 地图页打开时的起始位置，以及要不要自动定位到「我现在的位置」。 */
    data class InitialSelection(val latitude: Double, val longitude: Double, val autoLocate: Boolean)

    /**
     * 问题 3（v2.2.24）：编辑已有提醒点时，编辑页会把这个点原来的坐标带进来——
     * 地图从原位置开始、标记和圆圈画在原位置，并且不自动定位（否则 30 米以内的定位结果会把原标记挪走）。
     * 新建时不带坐标（或带来的坐标不合法）——保持原来的做法：从默认位置开始，自动定位。
     */
    fun initialSelection(
        incomingLatitude: Double?,
        incomingLongitude: Double?,
        defaultLatitude: Double,
        defaultLongitude: Double
    ): InitialSelection {
        val valid = incomingLatitude != null && incomingLongitude != null &&
            incomingLatitude.isFinite() && incomingLongitude.isFinite() &&
            incomingLatitude in -90.0..90.0 && incomingLongitude in -180.0..180.0
        return if (valid) {
            InitialSelection(incomingLatitude!!, incomingLongitude!!, autoLocate = false)
        } else {
            InitialSelection(defaultLatitude, defaultLongitude, autoLocate = true)
        }
    }

    const val LOCATE_TIMEOUT_MS = 15_000L
    const val LOCATE_TIMEOUT_TEXT = "定位超时，可直接在地图上点选，或点“重新定位”"

    /**
     * v2.2.26（中 1）：地图页「定位到我」的超时。地图是高德、或定位来源可能是高德（设置为始终高德 / 没有 Google Play 服务）
     * 时 15 秒；纯谷歌（谷歌地图 + 谷歌定位）返回 null = 不加超时，和 v2.2.24 完全一样。
     */
    fun locateTimeoutMs(backendIsAmap: Boolean, dynamicLocation: Boolean): Long? =
        if (backendIsAmap || dynamicLocation) LOCATE_TIMEOUT_MS else null

    /** 「定位到我」时，精度在这个数（米）以内才自动把定位结果设为选中位置。正好 30 米算合格。 */
    const val LOCATE_MAX_ACCURACY_M = 30f

    /** 拿到定位结果后怎么办：[adoptAsSelected] 为真＝设为选中位置；为假＝只把地图移过去并显示 [statusText]。 */
    data class LocateDecision(val adoptAsSelected: Boolean, val statusText: String?)

    /**
     * v2.2.24：精度不到 30 米时也先把地图移到定位结果的位置，但不自动设为选中位置（标记和圆圈不动），
     * 并提示用户可以重新定位或直接点选。30 米以内的行为不变。
     * 米数向上取整，保证提示里的数字一定大于 30。
     */
    fun onLocated(accuracyMeters: Float): LocateDecision =
        if (accuracyMeters > LOCATE_MAX_ACCURACY_M) {
            LocateDecision(
                adoptAsSelected = false,
                statusText = "定位精度一般（${kotlin.math.ceil(accuracyMeters).toInt()} 米），可到室外点“重新定位”，或直接在地图上点选"
            )
        } else {
            LocateDecision(adoptAsSelected = true, statusText = null)
        }
}
