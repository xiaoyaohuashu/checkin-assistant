package com.checkinassistant.map.amap

import android.content.Context
import android.os.Bundle
import android.view.View
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.checkinassistant.domain.CoordConverter
import com.checkinassistant.map.GeoPoint
import com.checkinassistant.map.MapCircle
import com.checkinassistant.map.MapPickerView
import com.checkinassistant.map.PickerMap

/**
 * v2.2.25：地图接口的高德实现。进出都换坐标：
 * 交给高德之前 WGS-84 → GCJ-02，从高德拿回来（点选）GCJ-02 → WGS-84；台湾和海外不换（v2.2.26：港澳和大陆一样换）。
 * 构造前调用方必须先 AmapSdk.ensureReady 成功。
 */
internal class AmapMapPickerView(context: Context) : MapPickerView {
    private val mapView = MapView(context)
    override val view: View get() = mapView
    override fun onCreate(savedInstanceState: Bundle?) = mapView.onCreate(savedInstanceState)
    // 异步回调：选点页要等地图控件加进容器以后才能放「定位中…」提示（谷歌那边本来就是异步的）。
    override fun getMapAsync(onReady: (PickerMap) -> Unit) { mapView.post { onReady(AmapPickerMap(mapView.map)) } }
    override fun onStart() {}
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onStop() {}
    override fun onDestroy() = mapView.onDestroy()
    override fun onLowMemory() = mapView.onLowMemory()
}

private fun GeoPoint.toAmap(): LatLng {
    val g = CoordConverter.wgsToGcjInAmapArea(latitude, longitude)
    return LatLng(g[0], g[1])
}

private fun LatLng.toWgsGeoPoint(): GeoPoint {
    val w = CoordConverter.gcjToWgsInAmapArea(latitude, longitude)
    return GeoPoint(w[0], w[1])
}

private class AmapPickerMap(private val map: AMap) : PickerMap {
    override fun moveCamera(center: GeoPoint, zoom: Float) = map.moveCamera(CameraUpdateFactory.newLatLngZoom(center.toAmap(), zoom))
    override fun animateCamera(center: GeoPoint, zoom: Float) = map.animateCamera(CameraUpdateFactory.newLatLngZoom(center.toAmap(), zoom))
    override fun addMarker(position: GeoPoint, title: String) { map.addMarker(MarkerOptions().position(position.toAmap()).title(title)) }
    override fun addCircle(center: GeoPoint, radiusMeters: Double, strokeColor: Int, strokeWidth: Float, fillColor: Int): MapCircle? =
        map.addCircle(
            CircleOptions().center(center.toAmap()).radius(radiusMeters)
                .strokeColor(strokeColor).strokeWidth(strokeWidth).fillColor(fillColor)
        )?.let(::AmapMapCircle)
    /** 保留「我的位置」蓝点，只清标记和圆。 */
    override fun clear() = map.clear(true)
    override fun setOnMapClickListener(listener: (GeoPoint) -> Unit) = map.setOnMapClickListener { latLng -> listener(latLng.toWgsGeoPoint()) }
    override fun setMyLocationEnabled(enabled: Boolean) {
        if (enabled) map.setMyLocationStyle(MyLocationStyle().myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW))
        map.isMyLocationEnabled = enabled
    }
    override fun setMyLocationButtonEnabled(enabled: Boolean) { map.uiSettings.isMyLocationButtonEnabled = enabled }
}

private class AmapMapCircle(private val circle: Circle) : MapCircle {
    override fun remove() = circle.remove()
}
