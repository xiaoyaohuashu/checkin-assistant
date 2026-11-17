package com.checkinassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 问题 4：地图页的半径范围和编辑页一致（100–5000 米），带进地图页的半径不再被截成 500。 */
class PointDefaultsTest {
    @Test
    fun clampRadius_keepsEditorRange() {
        // 修之前地图页会把 2000、5000 都截成 500
        assertEquals(2000, PointDefaults.clampRadius(2000))
        assertEquals(5000, PointDefaults.clampRadius(5000))
        assertEquals(100, PointDefaults.clampRadius(100))
        assertEquals(501, PointDefaults.clampRadius(501))
    }

    @Test
    fun clampRadius_clampsOutOfRange() {
        assertEquals(100, PointDefaults.clampRadius(50))
        assertEquals(5000, PointDefaults.clampRadius(6000))
    }

    @Test
    fun isValidRadius_matchesEditorRange() {
        assertTrue(PointDefaults.isValidRadius(100))
        assertTrue(PointDefaults.isValidRadius(2000))
        assertTrue(PointDefaults.isValidRadius(5000))
        assertFalse(PointDefaults.isValidRadius(99))
        assertFalse(PointDefaults.isValidRadius(5001))
    }
}
