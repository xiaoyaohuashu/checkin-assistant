package com.checkinassistant.domain

import com.checkinassistant.reminder.ReminderEngine
import com.checkinassistant.data.PointState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 提醒轮次上限与每日次数上限（期望值手写）。 */
class ReminderRulesTest {
    @Test fun maxRoundsIsThree() {
        assertEquals(3, ReminderRules.MAX_REMINDERS)
        assertEquals(3, ReminderEngine.MAX_REMINDERS)
    }

    // ── 问题 6（v2.2.24）：第 3 轮也响满 30 秒 ──

    @Test fun nextStep_truthTable() {
        val stop = ReminderRules.Step.STOP; val send = ReminderRules.Step.SEND_ROUND; val wrap = ReminderRules.Step.WRAP_UP
        assertEquals(stop, ReminderRules.nextStep(active = false, reminderCount = 0))
        assertEquals(stop, ReminderRules.nextStep(active = false, reminderCount = 2))
        assertEquals(stop, ReminderRules.nextStep(active = false, reminderCount = 3))
        assertEquals(send, ReminderRules.nextStep(active = true, reminderCount = 0))
        assertEquals(send, ReminderRules.nextStep(active = true, reminderCount = 1))
        assertEquals(send, ReminderRules.nextStep(active = true, reminderCount = 2))
        assertEquals(wrap, ReminderRules.nextStep(active = true, reminderCount = 3))
        assertEquals(wrap, ReminderRules.nextStep(active = true, reminderCount = 4))
    }

    @Test fun everyRoundIncludingTheThirdLasts30s() {
        assertEquals(30_000L, ReminderRules.delayAfterRoundMs(1))
        assertEquals(30_000L, ReminderRules.delayAfterRoundMs(2))
        assertEquals(30_000L, ReminderRules.delayAfterRoundMs(3))
    }

    @Test fun fullSession_threeRounds_ringFor90sBeforeWrapUp() {
        // 模拟 sendNext：每次闹钟到点问一次 nextStep；发一轮就把轮数 +1，并等 delayAfterRoundMs 那么久
        var count = 0
        var total = 0L
        while (ReminderRules.nextStep(active = true, reminderCount = count) == ReminderRules.Step.SEND_ROUND) {
            count += 1
            total += ReminderRules.delayAfterRoundMs(count)
        }
        assertEquals(3, count)
        assertEquals(90_000L, total)
        assertEquals(ReminderRules.Step.WRAP_UP, ReminderRules.nextStep(active = true, reminderCount = count))
    }

    // ── 问题 15（v2.2.24）：三轮没理后不回到「等待进入」 ──

    @Test fun roundsExhausted_repeatingPointBecomesIgnored_notArmed() {
        assertEquals(PointState.IGNORED, ReminderRules.stateAfterRoundsExhausted("DAILY"))
        assertEquals(PointState.IGNORED, ReminderRules.stateAfterRoundsExhausted("WEEKLY"))
    }

    @Test fun roundsExhausted_oncePointStaysCompleted() {
        assertEquals(PointState.COMPLETED, ReminderRules.stateAfterRoundsExhausted("ONCE"))
    }

    @Test fun roundsExhausted_neverReturnsArmed() {
        // 「进入范围」只认 ARMED；只要不是 ARMED，人不离开就不会再触发
        listOf("DAILY", "WEEKLY", "ONCE").forEach {
            assertNotEquals(PointState.ARMED, ReminderRules.stateAfterRoundsExhausted(it))
        }
    }

    @Test fun dailyLimit() {
        assertEquals(false, ReminderRules.isDailyLimitReached(countToday = 0, limit = 1))
        assertEquals(true, ReminderRules.isDailyLimitReached(countToday = 1, limit = 1))
        assertEquals(true, ReminderRules.isDailyLimitReached(countToday = 2, limit = 1))
        assertEquals(false, ReminderRules.isDailyLimitReached(countToday = 2, limit = 3))
        assertEquals(true, ReminderRules.isDailyLimitReached(countToday = 3, limit = 3))
        // 现状：上限为 0 或负数（界面存不出来）时一律拦住
        assertEquals(true, ReminderRules.isDailyLimitReached(countToday = 0, limit = 0))
    }
}
