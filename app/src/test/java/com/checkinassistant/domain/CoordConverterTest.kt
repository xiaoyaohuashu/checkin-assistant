package com.checkinassistant.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class CoordConverterTest {
    /** 两点距离（米，球面近似），独立于被测代码。 */
    private fun meters(a: DoubleArray, b: DoubleArray): Double {
        val r = 6_371_000.0
        val la1 = a[0] * PI / 180; val la2 = b[0] * PI / 180
        val dLa = la2 - la1; val dLo = (b[1] - a[1]) * PI / 180
        val h = sin(dLa / 2) * sin(dLa / 2) + cos(la1) * cos(la2) * sin(dLo / 2) * sin(dLo / 2)
        return 2 * r * asin(sqrt(h))
    }

    private val cities = listOf(
        39.9042 to 116.4074, 31.2304 to 121.4737, 23.1291 to 113.2644, 22.5410 to 114.0590, 30.5728 to 104.0668,
        43.8256 to 87.6168, 45.8038 to 126.5350, 18.2528 to 109.5119, 29.6500 to 91.1000, 24.4800 to 118.0900
    )

    @Test fun roundTripInMainlandIsBelowOneCentimetre() {
        val points = cities + (0 until 10).flatMap { i -> (0 until 10).map { j -> (22.0 + i * 2.5) to (100.0 + j * 2.5) } }
            .filter { ChinaRegion.isMainland(it.first, it.second) }
        points.forEach { (lat, lng) ->
            val gcj = CoordConverter.wgsToGcj(lat, lng)
            val back = CoordConverter.gcjToWgs(gcj[0], gcj[1])
            assertTrue("($lat,$lng) 往返误差 ${meters(doubleArrayOf(lat, lng), back)} 米", meters(doubleArrayOf(lat, lng), back) < 0.01)
            val viaRegion = CoordConverter.gcjToWgsInAmapArea(gcj[0], gcj[1])
            assertTrue(meters(doubleArrayOf(lat, lng), viaRegion) < 0.01)
        }
    }

    @Test fun offsetIsTypicalGcjMagnitude() {
        cities.forEach { (lat, lng) ->
            val d = meters(doubleArrayOf(lat, lng), CoordConverter.wgsToGcj(lat, lng))
            assertTrue("($lat,$lng) 偏移 $d 米", d in 50.0..1000.0)
        }
    }

    /** 台湾、金门和海外不换算（高德坐标区 = 大陆 + 港澳）。 */
    @Test fun outsideAmapAreaIsUntouched() {
        for ((lat, lng) in listOf(3.1390 to 101.6869, 25.0330 to 121.5654, 24.43 to 118.32, 1.3521 to 103.8198, 21.0285 to 105.8542)) {
            assertArrayEquals(doubleArrayOf(lat, lng), CoordConverter.wgsToGcjInAmapArea(lat, lng), 0.0)
            assertArrayEquals(doubleArrayOf(lat, lng), CoordConverter.gcjToWgsInAmapArea(lat, lng), 0.0)
        }
    }

    /** v2.2.26（中 3）：港澳和大陆一样按 GCJ-02 换算，往返无损。 */
    @Test fun hongKongAndMacauAreConverted() {
        for ((lat, lng) in listOf(22.2970 to 114.1720, 22.2819 to 114.1582, 22.19 to 113.54, 22.145 to 113.56)) {
            assertTrue(ChinaRegion.usesGcj02(lat, lng))
            assertFalse(ChinaRegion.isMainland(lat, lng))
            val gcj = CoordConverter.wgsToGcjInAmapArea(lat, lng)
            val d = meters(doubleArrayOf(lat, lng), gcj)
            assertTrue("($lat,$lng) 偏移 $d 米", d in 50.0..1000.0)
            assertTrue(meters(doubleArrayOf(lat, lng), CoordConverter.gcjToWgsInAmapArea(gcj[0], gcj[1])) < 0.01)
        }
    }

    @Test fun usesGcj02CoversMainlandAndExcludesTaiwanAndAbroad() {
        cities.forEach { (lat, lng) -> assertTrue("($lat,$lng)", ChinaRegion.usesGcj02(lat, lng)) }
        for ((lat, lng) in listOf(25.0330 to 121.5654, 23.57 to 119.58, 24.43 to 118.32, 3.1390 to 101.6869, 37.5665 to 126.978)) {
            assertFalse("($lat,$lng)", ChinaRegion.usesGcj02(lat, lng))
        }
    }

    @Test fun toWgsFollowsCoordType() {
        assertArrayEquals(doubleArrayOf(39.9, 116.4), CoordConverter.toWgs(39.9, 116.4, isGcj = false), 0.0)
        val gcj = CoordConverter.wgsToGcj(39.9, 116.4)
        val back = CoordConverter.toWgs(gcj[0], gcj[1], isGcj = true)
        assertEquals(39.9, back[0], 1e-8); assertEquals(116.4, back[1], 1e-8)
    }
}
