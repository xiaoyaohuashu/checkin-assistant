package com.checkinassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 距离、半径、定位精度的边界（期望值手写）。 */
class ArrivalRulesTest {
    @Test fun accuracyGate_is50Meters_exclusive() {
        assertEquals(50f, ArrivalRules.POLL_MAX_ACCURACY_M, 0f)
        assertEquals(false, ArrivalRules.isTooInaccurate(10f))
        assertEquals(false, ArrivalRules.isTooInaccurate(49.9f))
        assertEquals(false, ArrivalRules.isTooInaccurate(50f))
        assertEquals(true, ArrivalRules.isTooInaccurate(50.01f))
        assertEquals(true, ArrivalRules.isTooInaccurate(98f))
        assertEquals(false, ArrivalRules.isTooInaccurate(Float.NaN))
    }

    @Test fun distanceEqualToRadius_countsAsInside_andNotOutside() {
        assertEquals(true, ArrivalRules.isInsideForPoll(200f, 200))
        assertEquals(false, ArrivalRules.isOutsideForConfirm(200f, 200))
    }

    @Test fun slightlyInsideAndOutside() {
        assertEquals(true, ArrivalRules.isInsideForPoll(199.9f, 200))
        assertEquals(false, ArrivalRules.isOutsideForConfirm(199.9f, 200))
        assertEquals(false, ArrivalRules.isInsideForPoll(200.1f, 200))
        assertEquals(true, ArrivalRules.isOutsideForConfirm(200.1f, 200))
        assertEquals(true, ArrivalRules.isInsideForPoll(0f, 100))
        assertEquals(false, ArrivalRules.isInsideForPoll(5000.5f, 5000))
        assertEquals(true, ArrivalRules.isOutsideForConfirm(5000.5f, 5000))
    }

    @Test fun floatPrecisionAtBoundary() {
        // Float 在 100 附近的最小间隔约 0.0000076：100.000001f 存下来就是 100f，仍算在范围内
        assertEquals(true, ArrivalRules.isInsideForPoll(100.000001f, 100))
        assertEquals(false, ArrivalRules.isOutsideForConfirm(100.000001f, 100))
        // 100.00001f 已经比 100 大一格
        assertEquals(false, ArrivalRules.isInsideForPoll(100.00001f, 100))
        assertEquals(true, ArrivalRules.isOutsideForConfirm(100.00001f, 100))
    }

    @Test fun nanDistance_isNeitherInsideNorOutside() {
        assertEquals(false, ArrivalRules.isInsideForPoll(Float.NaN, 200))
        assertEquals(false, ArrivalRules.isOutsideForConfirm(Float.NaN, 200))
    }
}
