package com.checkinassistant

import com.checkinassistant.data.CheckInPoint
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * 单测公用工具。所有测试都是「固定现状」测试：断言写的是重构前代码的实际行为。
 * 测试提交之后如果变红，说明重构改了行为，应撤回代码，而不是改测试。
 */
object TestSupport {
    /** 用户所在时区；没有夏令时。夏令时切换日的行为未覆盖（已知）。 */
    const val ZONE = "Asia/Kuala_Lumpur"

    fun useKualaLumpur(): TimeZone {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE))
        return previous
    }

    /** at("2026-11-09", "09:00") → 吉隆坡当地该时刻的毫秒时间戳（期望值独立于被测代码计算）。 */
    fun at(date: String, hm: String, second: Int = 0): Long =
        LocalDateTime.parse("${date}T$hm:00").plusSeconds(second.toLong())
            .atZone(ZoneId.of(ZONE)).toInstant().toEpochMilli()

    fun hm(text: String): Int = text.substring(0, 2).toInt() * 60 + text.substring(3, 5).toInt()

    /** 只用实体现有的构造函数造数据，不改实体。 */
    fun point(
        id: Long = 1,
        enabled: Boolean = true,
        start: String? = null,
        end: String? = null,
        repeatMode: String = "DAILY",
        activeDays: Int = 127,
        onceDate: String? = null,
        onceStatus: String? = null,
        skipUntilDate: String? = null,
        lastTriggeredWindowStart: Long? = null,
        radiusMeters: Int = 200,
        name: String = "测试点"
    ) = CheckInPoint(
        id = id, name = name, latitude = 3.1390, longitude = 101.6869, radiusMeters = radiusMeters,
        enabled = enabled,
        activeStartMinute = start?.let(::hm), activeEndMinute = end?.let(::hm),
        repeatMode = repeatMode, activeDays = activeDays, onceDate = onceDate, onceStatus = onceStatus,
        skipUntilDate = skipUntilDate, lastTriggeredWindowStart = lastTriggeredWindowStart
    )
}
