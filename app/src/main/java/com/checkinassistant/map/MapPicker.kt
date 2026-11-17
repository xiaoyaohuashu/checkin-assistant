package com.checkinassistant.map

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.checkinassistant.domain.MapBackend
import com.checkinassistant.map.amap.AmapMapPickerView
import com.checkinassistant.map.amap.AmapPlaceSearch
import com.checkinassistant.map.google.GoogleMapPickerView
import com.checkinassistant.map.google.GooglePlaceSearch
import com.checkinassistant.map.google.googleGeoPoint

// 地图选点页用到的地图能力：显示地图、选点、画触发范围、地址搜索。
// 选点页只认这里的接口，不直接碰 Google 地图；以后要换别家地图，只需要再写一套实现。
// Google 一套实现（每个接口方法都和选点页原来的一次 Google 调用一一对应）；v2.2.25 起另有高德一套（map/amap）。

/** 一个经纬度。只是两个数的载体，不做任何换算。 */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** 地图控件本身，以及它需要跟着页面走的生命周期。 */
interface MapPickerView {
    /** 就是地图控件本身，不能再包一层：「定位中…」提示是加在它的上一级容器里的。 */
    val view: View
    fun onCreate(savedInstanceState: Bundle?)
    /** 地图准备好之后回调一次。 */
    fun getMapAsync(onReady: (PickerMap) -> Unit)
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()
    fun onLowMemory()
}

/** 准备好之后的地图：移动镜头、打标记、画圆、点选。 */
interface PickerMap {
    fun moveCamera(center: GeoPoint, zoom: Float)
    fun animateCamera(center: GeoPoint, zoom: Float)
    fun addMarker(position: GeoPoint, title: String)
    fun addCircle(center: GeoPoint, radiusMeters: Double, strokeColor: Int, strokeWidth: Float, fillColor: Int): MapCircle?
    /** 清掉地图上所有标记和圆。 */
    fun clear()
    fun setOnMapClickListener(listener: (GeoPoint) -> Unit)
    /** 显示「我的位置」蓝点。调用前必须已有定位权限。 */
    fun setMyLocationEnabled(enabled: Boolean)
    fun setMyLocationButtonEnabled(enabled: Boolean)
}

interface MapCircle {
    fun remove()
}

/** 地址搜索框。 */
interface PlaceSearch {
    /** 搜索服务是否已经初始化（整个 App 进程只需一次）。 */
    val isInitialized: Boolean
    fun initialize(applicationContext: Context, apiKey: String)
    /** 把搜索框放进页面里的 [containerId] 容器。必须在页面 setContentView 之后调用。 */
    fun attach(activity: FragmentActivity, containerId: Int, countries: List<String>, listener: PlaceSearchListener)
}

interface PlaceSearchListener {
    /** @param location 选中地点的坐标；搜索结果没带坐标时为 null。 */
    fun onPlaceSelected(location: GeoPoint?, name: String?, address: String?)
    fun onError(message: String?)
}

object MapProviders {
    /** v2.2.25：按地图页选定的后端返回实现。高德实现构造前必须先 AmapSdk.ensureReady 成功。 */
    fun pickerView(context: Context, backend: MapBackend): MapPickerView =
        if (backend == MapBackend.AMAP) AmapMapPickerView(context) else GoogleMapPickerView(context)
    fun placeSearch(backend: MapBackend): PlaceSearch =
        if (backend == MapBackend.AMAP) AmapPlaceSearch() else GooglePlaceSearch

    /**
     * 用两个原始数字造一个坐标。数值要先过一遍地图自己的坐标类型：Google 会把越界的纬度夹回 ±90、
     * 经度折回 ±180，原来页面里存的就是折算后的值，这里保持一致。
     */
    fun geoPoint(latitude: Double, longitude: Double): GeoPoint = googleGeoPoint(latitude, longitude)
}
