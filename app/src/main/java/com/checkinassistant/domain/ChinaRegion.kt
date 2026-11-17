package com.checkinassistant.domain

import java.io.DataInputStream

/**
 * 判断一个 WGS-84 坐标在不在中国大陆（地图页「自动」选高德用）。
 *
 * v2.2.26：改用行政边界多边形（原来是几个粗矩形，把蛇口、拱北、黑河、东兴等判成了境外）。
 * 数据：OpenStreetMap 行政边界（© OpenStreetMap contributors，ODbL），简化到约 50 米，
 * 由 scripts/build_china_region.py 生成，放在同包的资源文件 china_region.bin 里：
 *   区域 0 = 中国（含港澳、含领海，不含台澎金马），区域 1 = 香港，区域 2 = 澳门。
 * 大陆 = 在区域 0 内，且不在区域 1、2 内。
 *
 * 外包框以外的点（比如整个马来西亚）直接返回 false，不读数据文件。
 * 已知局限：边界精度约 50 米，紧贴国境线、口岸的地方可能判反。
 */
object ChinaRegion {
    // 外包框，比数据的真实范围略大（单测保证数据都在框内）。单位：度。
    internal const val BOX_MIN_LAT = 8.6
    internal const val BOX_MAX_LAT = 53.6
    internal const val BOX_MIN_LNG = 73.4
    internal const val BOX_MAX_LNG = 134.8

    private const val RESOURCE = "china_region.bin"
    private const val MAGIC = 0x434E5247 // "CNRG"
    private const val REGION_CHINA = 0
    private const val REGION_HONG_KONG = 1
    private const val REGION_MACAU = 2

    /** 一个环：外包框 + 顶点（1e-6 度的整数）。 */
    internal class Ring(val minLat: Int, val maxLat: Int, val minLng: Int, val maxLng: Int, val lat: IntArray, val lng: IntArray)

    /** 数据读不出来时的原因（正常为 null）。读这个字段不会触发加载；由地图页写进调试日志。 */
    @Volatile var loadFailure: String? = null
        private set

    internal val regions: List<List<Ring>> by lazy { load() }

    /** 数据是否读到了（会触发加载，单测用）。 */
    internal val loaded: Boolean get() = regions.size == 3 && regions[REGION_CHINA].isNotEmpty()

    fun isMainland(latitude: Double, longitude: Double): Boolean {
        if (!inBox(latitude, longitude)) return false
        return inRegion(REGION_CHINA, latitude, longitude) &&
            !inRegion(REGION_HONG_KONG, latitude, longitude) &&
            !inRegion(REGION_MACAU, latitude, longitude)
    }

    /**
     * v2.2.26：这个 WGS-84 坐标是否在高德的坐标区里（要不要换算成 GCJ-02）。
     * 高德官方文档把「大陆以及港、澳地区」作为一个整体的高德数据区（高德坐标系），台湾和海外不在内；
     * 所以这里 = 中国多边形（本身含港澳）或 香港、澳门多边形内。
     */
    fun usesGcj02(latitude: Double, longitude: Double): Boolean {
        if (!inBox(latitude, longitude)) return false
        return inRegion(REGION_CHINA, latitude, longitude) ||
            inRegion(REGION_HONG_KONG, latitude, longitude) ||
            inRegion(REGION_MACAU, latitude, longitude)
    }

    private fun inBox(latitude: Double, longitude: Double): Boolean =
        latitude >= BOX_MIN_LAT && latitude <= BOX_MAX_LAT && longitude >= BOX_MIN_LNG && longitude <= BOX_MAX_LNG

    /** 射线法，奇偶规则，跨这个区域的所有环累加（洞、多块都能处理）。 */
    private fun inRegion(index: Int, latitude: Double, longitude: Double): Boolean {
        val rings = regions.getOrNull(index) ?: return false
        val y = latitude * 1_000_000.0
        val x = longitude * 1_000_000.0
        var inside = false
        for (ring in rings) {
            if (y < ring.minLat || y > ring.maxLat || x < ring.minLng || x > ring.maxLng) continue
            val lat = ring.lat
            val lng = ring.lng
            var j = lat.size - 1
            for (i in lat.indices) {
                val yi = lat[i].toDouble()
                val yj = lat[j].toDouble()
                if ((yi > y) != (yj > y)) {
                    val xi = lng[i].toDouble()
                    val xj = lng[j].toDouble()
                    if (x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                }
                j = i
            }
        }
        return inside
    }

    private fun load(): List<List<Ring>> = try {
        val stream = ChinaRegion::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("找不到资源文件 $RESOURCE")
        DataInputStream(stream.buffered()).use { input ->
            if (input.readInt() != MAGIC) throw IllegalStateException("文件头不对")
            val version = input.readInt()
            if (version != 1) throw IllegalStateException("不认识的版本 $version")
            List(3) {
                List(input.readInt()) {
                    val n = input.readInt()
                    val minLat = input.readInt()
                    val maxLat = input.readInt()
                    val minLng = input.readInt()
                    val maxLng = input.readInt()
                    val lat = IntArray(n)
                    val lng = IntArray(n)
                    for (k in 0 until n) {
                        lat[k] = input.readInt()
                        lng[k] = input.readInt()
                    }
                    Ring(minLat, maxLat, minLng, maxLng, lat, lng)
                }
            }
        }
    } catch (t: Throwable) {
        // 读不出来就一律当「不在大陆」：谷歌这条路不受影响；原因留给地图页写进调试日志。
        loadFailure = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
        emptyList()
    }
}
