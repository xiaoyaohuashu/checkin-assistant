package com.checkinassistant.domain

/**
 * 到达判定里的几个「比大小」。只做比较本身：取定位、算距离、读时钟、写库、打日志都留在调用方原位。
 */
internal object ArrivalRules {
    /** 盯位置时可接受的最差定位精度（米）。比这更差的定位直接丢弃，避免误判进出（v5 任务四）。 */
    const val POLL_MAX_ACCURACY_M = 50f

    /** 轮询：这次定位精度太差，不能用。 */
    fun isTooInaccurate(accuracyMeters: Float): Boolean = accuracyMeters > POLL_MAX_ACCURACY_M

    /** 轮询：距离 ≤ 半径算在范围内（正好等于半径也算）。 */
    fun isInsideForPoll(distanceMeters: Float, radiusMeters: Int): Boolean = distanceMeters <= radiusMeters

    /**
     * 25 秒复核：距离 > 半径才算已离开。
     * 不能写成 !isInsideForPoll(...)：距离算不出来（NaN）时两个函数都返回 false，取反就变了。
     */
    fun isOutsideForConfirm(distanceMeters: Float, radiusMeters: Int): Boolean = distanceMeters > radiusMeters
}
