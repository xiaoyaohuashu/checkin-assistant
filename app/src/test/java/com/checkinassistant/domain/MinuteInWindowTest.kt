package com.checkinassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 时间段比较的真值表（手写）：含开始、不含结束；结束 ≤ 开始按跨夜。 */
class MinuteInWindowTest {
    private fun m(h: Int, min: Int) = h * 60 + min

    @Test fun normalWindow() {
        assertEquals(false, isMinuteInWindow(m(9, 0), m(18, 0), m(8, 59)))
        assertEquals(true, isMinuteInWindow(m(9, 0), m(18, 0), m(9, 0)))
        assertEquals(true, isMinuteInWindow(m(9, 0), m(18, 0), m(17, 59)))
        assertEquals(false, isMinuteInWindow(m(9, 0), m(18, 0), m(18, 0)))
        assertEquals(false, isMinuteInWindow(m(9, 0), m(18, 0), 0))
        assertEquals(false, isMinuteInWindow(m(9, 0), m(18, 0), 1439))
    }

    @Test fun overnightWindow() {
        assertEquals(false, isMinuteInWindow(m(22, 0), m(6, 0), m(21, 59)))
        assertEquals(true, isMinuteInWindow(m(22, 0), m(6, 0), m(22, 0)))
        assertEquals(true, isMinuteInWindow(m(22, 0), m(6, 0), 1439))
        assertEquals(true, isMinuteInWindow(m(22, 0), m(6, 0), 0))
        assertEquals(true, isMinuteInWindow(m(22, 0), m(6, 0), m(5, 59)))
        assertEquals(false, isMinuteInWindow(m(22, 0), m(6, 0), m(6, 0)))
        assertEquals(false, isMinuteInWindow(m(22, 0), m(6, 0), m(12, 0)))
    }

    @Test fun startEqualsEnd_everyMinuteIsInside() {
        // 现状：开始 = 结束时走跨夜分支，全天都算在内（待处理问题 21）
        for (minute in 0..1439) assertEquals(true, isMinuteInWindow(600, 600, minute))
    }

    @Test fun oneMinuteWindow() {
        assertEquals(true, isMinuteInWindow(600, 601, 600))
        assertEquals(false, isMinuteInWindow(600, 601, 601))
        assertEquals(false, isMinuteInWindow(600, 601, 599))
    }
}
