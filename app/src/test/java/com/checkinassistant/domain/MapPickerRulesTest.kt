package com.checkinassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 地图选点页的判断：起始位置（问题 3）、定位结果怎么用（v2.2.24 地图页定位）。 */
class MapPickerRulesTest {
    private val defLat = PointDefaults.LATITUDE
    private val defLng = PointDefaults.LONGITUDE

    @Test
    fun initialSelection_withOriginal_startsThereAndDoesNotAutoLocate() {
        val s = MapPickerRules.initialSelection(3.2, 101.5, defLat, defLng)
        assertEquals(3.2, s.latitude, 0.0)
        assertEquals(101.5, s.longitude, 0.0)
        assertFalse(s.autoLocate)
    }

    @Test
    fun initialSelection_newPoint_usesDefaultAndAutoLocates() {
        val s = MapPickerRules.initialSelection(null, null, defLat, defLng)
        assertEquals(defLat, s.latitude, 0.0)
        assertEquals(defLng, s.longitude, 0.0)
        assertTrue(s.autoLocate)
    }

    @Test
    fun onLocated_within30m_adoptsWithoutMessage() {
        for (accuracy in listOf(5f, 12f, 30f)) {
            val d = MapPickerRules.onLocated(accuracy)
            assertTrue(d.adoptAsSelected)
            assertEquals(null, d.statusText)
        }
    }

    @Test
    fun onLocated_worseThan30m_movesOnlyAndExplains() {
        val d = MapPickerRules.onLocated(45f)
        assertFalse(d.adoptAsSelected)
        assertEquals("定位精度一般（45 米），可到室外点“重新定位”，或直接在地图上点选", d.statusText)
        val edge = MapPickerRules.onLocated(30.01f)
        assertFalse(edge.adoptAsSelected)
        assertTrue(edge.statusText!!.contains("（31 米）"))
    }

    @Test
    fun initialSelection_halfOrInvalid_fallsBackToDefault() {
        val cases = listOf<Pair<Double?, Double?>>(
            3.2 to null, null to 101.5, 91.0 to 101.5, 3.2 to 181.0, Double.NaN to 101.5
        )
        for ((lat, lng) in cases) {
            val s = MapPickerRules.initialSelection(lat, lng, defLat, defLng)
            assertEquals(defLat, s.latitude, 0.0)
            assertEquals(defLng, s.longitude, 0.0)
            assertTrue(s.autoLocate)
        }
    }

    /** v2.2.26（中 1）：纯谷歌（谷歌地图 + 谷歌定位）不加超时；其余三种情况 15 秒。 */
    @Test
    fun locateTimeout_onlyWhenAmapMayBeInvolved() {
        assertEquals(null, MapPickerRules.locateTimeoutMs(backendIsAmap = false, dynamicLocation = false))
        assertEquals(15_000L, MapPickerRules.locateTimeoutMs(backendIsAmap = true, dynamicLocation = false))
        assertEquals(15_000L, MapPickerRules.locateTimeoutMs(backendIsAmap = false, dynamicLocation = true))
        assertEquals(15_000L, MapPickerRules.locateTimeoutMs(backendIsAmap = true, dynamicLocation = true))
    }
}
