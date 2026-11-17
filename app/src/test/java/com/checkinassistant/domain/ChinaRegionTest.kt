package com.checkinassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChinaRegionTest {
    /** 必须判为大陆。前 7 个是 v2.2.25 的粗矩形判错、审查点名的地方。 */
    private val mainland = listOf(
        Triple("深圳蛇口", 22.48, 113.92), Triple("珠海拱北", 22.224, 113.552), Triple("珠海横琴", 22.13, 113.53),
        Triple("黑河", 50.24, 127.53), Triple("逊克", 49.58, 128.47), Triple("东兴", 21.5471, 107.9719),
        Triple("连江黄岐", 26.33, 119.88),
        Triple("深圳湾口岸", 22.504, 113.944), Triple("横琴口岸", 22.142, 113.547), Triple("拱北口岸北", 22.2195, 113.5495),
        Triple("深圳福田", 22.541, 114.059), Triple("罗湖口岸", 22.532, 114.113), Triple("厦门", 24.48, 118.09),
        Triple("北京", 39.9042, 116.4074), Triple("上海", 31.2304, 121.4737), Triple("广州", 23.1291, 113.2644),
        Triple("三亚", 18.2528, 109.5119), Triple("海口", 20.04, 110.2), Triple("漠河", 52.97, 122.5),
        Triple("喀什", 39.47, 75.99), Triple("瑞丽", 24.01, 97.85), Triple("磨憨", 21.19, 101.69),
        Triple("丹东", 40.129, 124.354), Triple("珲春", 42.86, 130.37), Triple("霍尔果斯", 44.2, 80.4),
        Triple("舟山", 30.0, 122.2), Triple("崇明", 31.62, 121.4), Triple("平潭", 25.5, 119.78),
        Triple("涠洲岛", 21.03, 109.1), Triple("樟木", 27.99, 85.98), Triple("亚东", 27.48, 88.9),
        Triple("满洲里", 49.6, 117.4), Triple("拉萨", 29.65, 91.1), Triple("乌鲁木齐", 43.8256, 87.6168),
        Triple("哈尔滨", 45.8038, 126.535), Triple("昆明", 25.0389, 102.7183), Triple("成都", 30.5728, 104.0668),
        Triple("福州", 26.0745, 119.2965)
    )

    /** 必须判为境外。前 5 个是审查点名的；港澳台、金门、马祖、邻国边境城市都在里面。 */
    private val notMainland = listOf(
        Triple("拉合尔", 31.5, 74.3), Triple("加德满都", 27.7, 85.3), Triple("比什凯克", 42.87, 74.59),
        Triple("密支那", 25.4, 97.4), Triple("澎湖", 23.57, 119.58),
        Triple("昌迪加尔", 30.7, 76.8), Triple("香港尖沙咀", 22.297, 114.172), Triple("香港中环", 22.2819, 114.1582),
        Triple("香港上水", 22.51, 114.13), Triple("香港落马洲", 22.51, 114.07), Triple("澳门", 22.19, 113.54),
        Triple("澳门关闸", 22.213, 113.549), Triple("澳门路氹", 22.145, 113.56), Triple("金门", 24.43, 118.32),
        Triple("马祖南竿", 26.16, 119.94), Triple("台北", 25.033, 121.5654), Triple("高雄", 22.6273, 120.3014),
        Triple("河内", 21.0285, 105.8542), Triple("芒街", 21.5245, 107.9665), Triple("老街", 22.48, 103.97),
        Triple("木姐", 23.99, 97.9), Triple("新义州", 40.1, 124.4), Triple("咸兴", 39.9, 127.5),
        Triple("平壤", 39.0392, 125.7625), Triple("布拉戈维申斯克", 50.27, 127.54), Triple("海参崴", 43.1155, 131.8855),
        Triple("乔巴山", 48.07, 114.5), Triple("乌兰巴托", 47.8864, 106.9057), Triple("阿拉木图", 43.24, 76.9),
        Triple("廷布", 27.47, 89.64), Triple("新德里", 28.6139, 77.209), Triple("曼谷", 13.7563, 100.5018),
        Triple("吉隆坡", 3.139, 101.6869), Triple("新加坡", 1.3521, 103.8198), Triple("首尔", 37.5665, 126.978),
        Triple("东京", 35.6762, 139.6503), Triple("伦敦", 51.5074, -0.1278)
    )

    @Test fun mainlandPlaces() = mainland.forEach { (name, lat, lng) -> assertTrue(name, ChinaRegion.isMainland(lat, lng)) }

    @Test fun notMainlandPlaces() = notMainland.forEach { (name, lat, lng) -> assertFalse(name, ChinaRegion.isMainland(lat, lng)) }

    @Test fun dataIsLoadedAndInsideTheQuickRejectBox() {
        assertTrue(ChinaRegion.loaded)
        assertNull(ChinaRegion.loadFailure)
        assertEquals(3, ChinaRegion.regions.size)
        ChinaRegion.regions.flatten().forEach { ring ->
            assertTrue(ring.lat.size >= 4)
            assertTrue(ring.minLat / 1e6 >= ChinaRegion.BOX_MIN_LAT && ring.maxLat / 1e6 <= ChinaRegion.BOX_MAX_LAT)
            assertTrue(ring.minLng / 1e6 >= ChinaRegion.BOX_MIN_LNG && ring.maxLng / 1e6 <= ChinaRegion.BOX_MAX_LNG)
        }
    }

    /** v2.2.25 的粗矩形判断，原样搬来只做对照。 */
    private object LegacyChinaRegion {
        private val include = arrayOf(
            doubleArrayOf(79.446200, 49.220400, 96.330000, 42.889900), doubleArrayOf(109.687200, 54.141500, 135.000200, 39.374200),
            doubleArrayOf(73.124600, 42.889900, 124.143255, 29.529700), doubleArrayOf(82.968400, 29.529700, 97.035200, 26.718600),
            doubleArrayOf(97.025300, 29.529700, 124.367395, 20.414096), doubleArrayOf(107.975793, 20.414096, 111.744104, 17.871542)
        )
        private val exclude = arrayOf(
            doubleArrayOf(119.921265, 25.398623, 122.497559, 21.785006), doubleArrayOf(101.865200, 22.284000, 106.665000, 20.098800),
            doubleArrayOf(106.452500, 21.542200, 108.051000, 20.487800), doubleArrayOf(109.032300, 55.817500, 119.127000, 50.325700),
            doubleArrayOf(127.456800, 55.817500, 137.022700, 49.557400), doubleArrayOf(131.266200, 44.892200, 137.022700, 42.569200),
            doubleArrayOf(113.820000, 22.500000, 114.450000, 22.130000), doubleArrayOf(113.520000, 22.220000, 113.610000, 22.100000),
            doubleArrayOf(118.200000, 24.530000, 118.500000, 24.370000), doubleArrayOf(119.850000, 26.400000, 120.550000, 25.900000)
        )
        private fun inBox(b: DoubleArray, lat: Double, lng: Double) = lng >= b[0] && lng <= b[2] && lat >= b[3] && lat <= b[1]
        fun isMainland(lat: Double, lng: Double) = include.any { inBox(it, lat, lng) } && exclude.none { inBox(it, lat, lng) }
    }

    /** 谷歌这条路的保证：整个马来西亚（含东马）新旧判断都是「不在大陆」，结果不变。 */
    @Test fun malaysiaIsNeverMainland_sameAsV2225() {
        var lat = 0.8
        while (lat <= 7.5) {
            var lng = 99.0
            while (lng <= 120.0) {
                assertFalse("($lat,$lng)", ChinaRegion.isMainland(lat, lng))
                assertFalse("($lat,$lng)", LegacyChinaRegion.isMainland(lat, lng))
                lng += 0.25
            }
            lat += 0.25
        }
    }

    /** 新旧对照：审查点名的 7 个大陆地点旧判断是错的，新判断是对的。 */
    @Test fun fixesThePlacesTheOldBoxesGotWrong() {
        mainland.take(7).filter { it.first != "珠海拱北" && it.first != "东兴" }.forEach { (name, lat, lng) ->
            assertFalse("$name 旧判断", LegacyChinaRegion.isMainland(lat, lng))
            assertTrue("$name 新判断", ChinaRegion.isMainland(lat, lng))
        }
        notMainland.take(5).forEach { (name, lat, lng) ->
            assertTrue("$name 旧判断", LegacyChinaRegion.isMainland(lat, lng))
            assertFalse("$name 新判断", ChinaRegion.isMainland(lat, lng))
        }
    }
}
