package com.checkinassistant.domain

import com.checkinassistant.data.PointState

/**
 * 提醒轮次和每日上限里的几个「比大小」。只做比较本身：查库、读时钟、发通知、排闹钟都留在
 * ReminderEngine 原位，顺序不变。
 */
internal object ReminderRules {
    /** 一次提醒最多响几轮（通知上显示「提醒 n/3」）。 */
    const val MAX_REMINDERS = 3

    /** 每一轮响多久：30 秒（v2.2.24 起包括最后一轮，问题 6）。 */
    const val ROUND_DURATION_MS = 30_000L

    /** 提醒闹钟到点时该做什么。 */
    enum class Step {
        /** 这次提醒已经结束（点过按钮、离开范围等）：什么都不发。 */
        STOP,
        /** 还没响满轮数：发下一轮。 */
        SEND_ROUND,
        /** 三轮都响满了：收尾（停铃声振动、记「未理会」）。 */
        WRAP_UP
    }

    /** [reminderCount] = 已经发出去的轮数。 */
    fun nextStep(active: Boolean, reminderCount: Int): Step = when {
        !active -> Step.STOP
        reminderCount < MAX_REMINDERS -> Step.SEND_ROUND
        else -> Step.WRAP_UP
    }

    /**
     * 刚发完第 [count] 轮，隔多久再叫醒一次。每一轮都是 30 秒，包括最后一轮：
     * 到点后的那次闹钟要么发下一轮，要么收尾。所以第 3 轮也响满 30 秒，三轮共约 90 秒。
     */
    @Suppress("UNUSED_PARAMETER")
    fun delayAfterRoundMs(count: Int): Long = ROUND_DURATION_MS

    /**
     * 三轮都没理之后，提醒点的状态（v2.2.24，问题 15）。
     * 每天 / 按星期的点变成「已忽略」而不是「等待进入」：「进入范围」那条 SQL 只认「等待进入」，
     * 所以人不离开范围就不会再触发；离开范围时（rearmAfterExit）才改回「等待进入」，再进入照常走 25 秒确认 + 三轮。
     * 一次性的点和原来一样，标成「已完成」。
     */
    fun stateAfterRoundsExhausted(repeatMode: String): PointState =
        if (repeatMode == "ONCE") PointState.COMPLETED else PointState.IGNORED

    /** 今天已提醒的次数（不含模拟提醒，由查库的 SQL 保证）是否已到每日上限。 */
    fun isDailyLimitReached(countToday: Int, limit: Int): Boolean = countToday >= limit
}
