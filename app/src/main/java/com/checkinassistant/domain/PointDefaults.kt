package com.checkinassistant.domain

/** 提醒点的默认值和取值范围。数值和重构前写在各处的字面量完全一样，只是起了名字。 */
internal object PointDefaults {
    /** 新建提醒点、地图页的默认位置：吉隆坡市中心。 */
    const val LATITUDE = 3.1390
    const val LONGITUDE = 101.6869

    /** 默认触发半径（米）。 */
    const val RADIUS_M = 200
    /** 触发半径下限（米），编辑页和地图页共用。 */
    const val RADIUS_MIN_M = 100
    /** 触发半径上限（米），编辑页和地图页共用（v2.2.24 起地图页滑杆也是 5000，原来是 500）。 */
    const val RADIUS_MAX_M = 5000

    /** 把半径收进允许范围 100–5000 米。地图页收到编辑页带来的半径、拖滑杆时都用它，所以 2000 米不会再被截成 500。 */
    fun clampRadius(meters: Int): Int = meters.coerceIn(RADIUS_MIN_M, RADIUS_MAX_M)

    /** 半径是否在允许范围内。编辑页接收地图页回传的半径时用它把关。 */
    fun isValidRadius(meters: Int): Boolean = meters in RADIUS_MIN_M..RADIUS_MAX_M

    /** 第一次勾选「仅在生效时间段内提醒」时的默认时间段 07:00–09:00（从 0 点起算的分钟数）。 */
    const val TIME_WINDOW_START_MINUTE = 7 * 60
    const val TIME_WINDOW_END_MINUTE = 9 * 60
    /** 时间滚轮没有初值时停在 08:00。 */
    const val TIME_WHEEL_MINUTE = 8 * 60
}
