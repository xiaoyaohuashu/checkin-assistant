package com.checkinassistant.map.google

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import com.checkinassistant.map.GeoPoint
import com.checkinassistant.map.MapCircle
import com.checkinassistant.map.MapPickerView
import com.checkinassistant.map.PickerMap
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions

// 地图接口的 Google 实现。每个方法里只有原来选点页里的那一句 Google 调用，参数没动。

/** 先造 Google 的 LatLng（它会折算越界数值），再取出折算后的两个数。 */
internal fun googleGeoPoint(latitude: Double, longitude: Double): GeoPoint = LatLng(latitude, longitude).toGeoPoint()

internal fun LatLng.toGeoPoint() = GeoPoint(latitude, longitude)
private fun GeoPoint.toLatLng() = LatLng(latitude, longitude)

internal class GoogleMapPickerView(context: Context) : MapPickerView {
    private val mapView = MapView(context)
    override val view: View get() = mapView
    override fun onCreate(savedInstanceState: Bundle?) = mapView.onCreate(savedInstanceState)
    override fun getMapAsync(onReady: (PickerMap) -> Unit) = mapView.getMapAsync { googleMap -> onReady(GooglePickerMap(googleMap)) }
    override fun onStart() = mapView.onStart()
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onStop() = mapView.onStop()
    override fun onDestroy() = mapView.onDestroy()
    override fun onLowMemory() = mapView.onLowMemory()
}

private class GooglePickerMap(private val map: GoogleMap) : PickerMap {
    override fun moveCamera(center: GeoPoint, zoom: Float) = map.moveCamera(CameraUpdateFactory.newLatLngZoom(center.toLatLng(), zoom))
    override fun animateCamera(center: GeoPoint, zoom: Float) = map.animateCamera(CameraUpdateFactory.newLatLngZoom(center.toLatLng(), zoom))
    override fun addMarker(position: GeoPoint, title: String) { map.addMarker(MarkerOptions().position(position.toLatLng()).title(title)) }
    override fun addCircle(center: GeoPoint, radiusMeters: Double, strokeColor: Int, strokeWidth: Float, fillColor: Int): MapCircle? =
        map.addCircle(
            CircleOptions()
                .center(center.toLatLng())
                .radius(radiusMeters)
                .strokeColor(strokeColor)
                .strokeWidth(strokeWidth)
                .fillColor(fillColor)
                .clickable(false)
        )?.let(::GoogleMapCircle)
    override fun clear() = map.clear()
    override fun setOnMapClickListener(listener: (GeoPoint) -> Unit) = map.setOnMapClickListener { latLng -> listener(latLng.toGeoPoint()) }
    // 权限由选点页在调用前检查（和原来一样）。
    @SuppressLint("MissingPermission")
    override fun setMyLocationEnabled(enabled: Boolean) { map.isMyLocationEnabled = enabled }
    override fun setMyLocationButtonEnabled(enabled: Boolean) { map.uiSettings.isMyLocationButtonEnabled = enabled }
}

private class GoogleMapCircle(private val circle: Circle) : MapCircle {
    override fun remove() = circle.remove()
}
