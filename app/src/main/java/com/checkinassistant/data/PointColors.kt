package com.checkinassistant.data

import android.graphics.Color

/** Fixed palette so each reminder point keeps a distinguishable colour in the calendar. */
object PointColors {
    val PALETTE: List<Int> = listOf(
        Color.rgb(37, 99, 235),  // 蓝
        Color.rgb(22, 163, 74),  // 绿
        Color.rgb(245, 158, 11), // 橙
        Color.rgb(220, 38, 38),  // 红
        Color.rgb(124, 58, 237), // 紫
        Color.rgb(8, 145, 178),  // 青
        Color.rgb(219, 39, 119), // 粉
        Color.rgb(101, 163, 13)  // 黄绿
    )

    /** Points created before colours existed get a stable colour derived from their id. */
    fun forPoint(point: CheckInPoint): Int =
        point.color ?: PALETTE[(point.id % PALETTE.size.toLong()).toInt()]

    /** Prefers a colour no other point already displays. */
    fun nextColor(existing: List<CheckInPoint>): Int {
        val used = existing.map { forPoint(it) }.toSet()
        return PALETTE.firstOrNull { it !in used } ?: PALETTE[existing.size % PALETTE.size]
    }
}
