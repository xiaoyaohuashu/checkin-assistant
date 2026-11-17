package com.checkinassistant.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v2.2.25：WGS-84（GPS、谷歌、数据库里存的）和 GCJ-02（高德地图、高德定位、高德搜索）互转。
 * 数据库一律存 WGS-84；只在高德的接口实现里换算。v2.2.26：大陆和港澳换算，台湾和海外不换算（[ChinaRegion.usesGcj02]）。
 * 算法是公开的标准近似公式（克拉索夫斯基椭球），反算用迭代，误差远小于 1 米。
 */
object CoordConverter {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** 不判断地区，直接 WGS-84 → GCJ-02。返回 [纬度, 经度]。 */
    fun wgsToGcj(latitude: Double, longitude: Double): DoubleArray {
        val dLat0 = transformLat(longitude - 105.0, latitude - 35.0)
        val dLng0 = transformLng(longitude - 105.0, latitude - 35.0)
        val radLat = latitude / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val dLat = dLat0 * 180.0 / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val dLng = dLng0 * 180.0 / (A / sqrtMagic * cos(radLat) * PI)
        return doubleArrayOf(latitude + dLat, longitude + dLng)
    }

    /** 不判断地区，GCJ-02 → WGS-84（迭代反算）。返回 [纬度, 经度]。 */
    fun gcjToWgs(latitude: Double, longitude: Double): DoubleArray {
        var lat = latitude
        var lng = longitude
        repeat(30) {
            val g = wgsToGcj(lat, lng)
            val dLat = g[0] - latitude
            val dLng = g[1] - longitude
            lat -= dLat
            lng -= dLng
            if (abs(dLat) < 1e-12 && abs(dLng) < 1e-12) return doubleArrayOf(lat, lng)
        }
        return doubleArrayOf(lat, lng)
    }

    /** 显示到高德地图之前：在高德坐标区（大陆 + 港澳）内换成 GCJ-02，区外原样。 */
    fun wgsToGcjInAmapArea(latitude: Double, longitude: Double): DoubleArray =
        if (ChinaRegion.usesGcj02(latitude, longitude)) wgsToGcj(latitude, longitude) else doubleArrayOf(latitude, longitude)

    /**
     * 高德地图给的坐标（点选、搜索结果）存进数据库之前：在高德坐标区内换回 WGS-84，区外原样。
     * 先反算再判区：边界数据是 WGS-84 的，直接拿 GCJ-02 坐标去判会在边界附近几百米内判错。
     */
    fun gcjToWgsInAmapArea(latitude: Double, longitude: Double): DoubleArray {
        val wgs = gcjToWgs(latitude, longitude)
        return if (ChinaRegion.usesGcj02(wgs[0], wgs[1])) wgs else doubleArrayOf(latitude, longitude)
    }

    /** 高德定位结果自己标了坐标系：是 GCJ-02 才换算。 */
    fun toWgs(latitude: Double, longitude: Double, isGcj: Boolean): DoubleArray =
        if (isGcj) gcjToWgs(latitude, longitude) else doubleArrayOf(latitude, longitude)

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
