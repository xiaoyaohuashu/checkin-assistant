package com.checkinassistant

import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.checkinassistant.map.GoogleMapsProvider
import com.checkinassistant.map.MapProviderResolver
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener

class MapPickerActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var mapView: MapView
    private var map: GoogleMap? = null
    private var selected = LatLng(3.1390, 101.6869)
    /** 触发范围（米）：编辑页传入的现值，或默认 200；选点页内可调，随确认回传 */
    private var radiusMeters = 200
    private var radiusCircle: Circle? = null
    private var radiusLabelView: TextView? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val autocompleteContainerId = View.generateViewId()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            enableMyLocationAndCenter()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "地图选点"
        // 编辑页传入的现有触发范围（100–500 米），无传入则默认 200
        val incoming = intent.getIntExtra(EXTRA_RADIUS, 200).coerceIn(100, 500)
        radiusMeters = incoming
        if (MapProviderResolver.resolve(this) !== GoogleMapsProvider) {
            showFallbackScreen()
            return
        }
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        initializePlaces()
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(FrameLayout(this).apply { id = autocompleteContainerId }, LinearLayout.LayoutParams(-1, -2))
        // 地图容器：地图 + 右缘竖向滑杆叠加（滑杆放底部常被系统误判为任务切换手势）
        val mapContainer = FrameLayout(this)
        mapContainer.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        // 触发范围：右缘竖向滑杆（SeekBar 旋转 270°，向上拖 = 半径增大）+ 半径胶囊文字
        val radiusLabel = TextView(this).apply {
            textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 36f; setColor(0xB3101418.toInt())
            }
            setPadding(20, 10, 20, 10)
        }
        val radiusSlider = android.widget.SeekBar(this).apply {
            max = 500 - 100
            progress = radiusMeters - 100
            rotation = 270f
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    setRadiusMeters(100 + progress)
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
            })
        }
        val sliderBox = FrameLayout(this)
        sliderBox.addView(
            radiusSlider,
            FrameLayout.LayoutParams(dp(240), -2, Gravity.CENTER) // 屏幕右侧垂直居中；水平居中必须保留——纯 CENTER_VERTICAL 会使旋转后的滑杆整体移出屏幕（v2.2.5 滑杆消失根因）
        )
        // 文字放右上角「定位到我的位置」按钮下方一点（按钮约占地图顶部 16~64dp，留 76dp 起步）
        sliderBox.addView(
            radiusLabel,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { setMargins(0, dp(76), 0, 0) }
        )
        // 把滑杆区域从系统边缘手势中排除：拖滑杆不被判定为侧边返回/切任务（minSdk 29 支持该 API）
        sliderBox.doOnLayout { sliderBox.systemGestureExclusionRects = listOf(Rect(0, 0, it.width, it.height)) }
        mapContainer.addView(
            sliderBox,
            FrameLayout.LayoutParams(dp(48), -1, Gravity.END or Gravity.CENTER_VERTICAL)
        )
        root.addView(mapContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        // v5 任务三: "重新定位" is user-driven — GPS never overrides a manual pick.
        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val relocate = Button(this).apply {
            text = "重新定位"; setOnClickListener { locateHighAccuracy() }
        }
        val confirm = Button(this).apply { text = "确认此位置"; setOnClickListener { returnSelection() } }
        bottomRow.addView(relocate, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(8, 0, 4, 0) })
        bottomRow.addView(confirm, LinearLayout.LayoutParams(0, -2, 2f).apply { setMargins(4, 0, 8, 0) })
        root.addView(bottomRow, LinearLayout.LayoutParams(-1, -2))
        // 底部按钮行排除系统手势：按钮不被误识别为手势操作
        bottomRow.doOnLayout { bottomRow.systemGestureExclusionRects = listOf(Rect(0, 0, it.width, it.height)) }
        radiusLabelView = radiusLabel
        updateRadiusLabel(radiusLabel)
        setContentView(root)
        setupAutocomplete()
    }

    private fun initializePlaces() {
        if (Places.isInitialized()) return
        val apiKey = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("com.google.android.geo.API_KEY")
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, "地址搜索不可用：未配置 Google API Key", Toast.LENGTH_LONG).show()
            return
        }
        Places.initializeWithNewPlacesApiEnabled(applicationContext, apiKey)
    }

    private fun setupAutocomplete() {
        if (!Places.isInitialized()) return
        val autocomplete = AutocompleteSupportFragment.newInstance().apply {
            setPlaceFields(listOf(Place.Field.ID, Place.Field.NAME, Place.Field.ADDRESS, Place.Field.LAT_LNG))
            setCountries(listOf("MY"))
            setOnPlaceSelectedListener(object : PlaceSelectionListener {
                override fun onPlaceSelected(place: Place) {
                    val placeLocation = place.latLng
                    if (placeLocation == null) {
                        Toast.makeText(this@MapPickerActivity, "未能获取该地点的坐标，请选择其他结果", Toast.LENGTH_SHORT).show()
                        return
                    }
                    selectLocation(placeLocation, place.name ?: place.address ?: "已选位置")
                }

                override fun onError(status: com.google.android.gms.common.api.Status) {
                    Toast.makeText(this@MapPickerActivity, "地址搜索失败：${status.statusMessage ?: "请检查网络后重试"}", Toast.LENGTH_LONG).show()
                }
            })
        }
        supportFragmentManager.beginTransaction()
            .replace(autocompleteContainerId, autocomplete)
            .commit()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(selected, 14f))
        googleMap.addMarker(MarkerOptions().position(selected).title("已选位置"))
        drawRadiusCircle()
        googleMap.setOnMapClickListener { latLng ->
            selectLocation(latLng, "已选位置")
        }
        requestLocationOrCenterMap()
    }

    /** 以当前选点为圆心绘制半透明触发范围圆（半径与触发范围完全一致） */
    private fun drawRadiusCircle() {
        radiusCircle?.remove()
        radiusCircle = map?.addCircle(
            com.google.android.gms.maps.model.CircleOptions()
                .center(selected)
                .radius(radiusMeters.toDouble())
                .strokeColor(0x662F6BF3)
                .strokeWidth(3f)
                .fillColor(0x262F6BF3)
                .clickable(false)
        )
    }

    /** 滑杆回调：更新半径并实时同步圆形覆盖与文字 */
    private fun setRadiusMeters(meters: Int) {
        radiusMeters = meters.coerceIn(100, 500)
        drawRadiusCircle()
        updateRadiusLabel()
    }

    private fun updateRadiusLabel(label: TextView? = null) {
        val text = "触发范围：${radiusMeters} 米"
        (label ?: radiusLabelView)?.text = text
    }

    private fun requestLocationOrCenterMap() {
        if (hasLocationPermission()) {
            enableMyLocationAndCenter()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun enableMyLocationAndCenter() {
        val googleMap = map ?: return
        if (!hasLocationPermission()) return
        googleMap.isMyLocationEnabled = true
        googleMap.uiSettings.isMyLocationButtonEnabled = true
        locateHighAccuracy()
    }

    /** "定位中" pill shown while waiting for a high-accuracy fix. */
    private lateinit var statusView: TextView

    /**
     * v5 任务三: wait for a fix with accuracy < 30 m before showing the position;
     * coarse/cache fixes are discarded instead of snapping the camera to a wrong spot.
     * Only called on map open or when the user taps "重新定位" — never after a manual pick.
     */
    private fun locateHighAccuracy() {
        if (!hasLocationPermission()) return
        if (!::statusView.isInitialized) {
            statusView = TextView(this).apply {
                text = "定位中…"; textSize = 13f; setTextColor(Color.WHITE)
                setPadding(24, 12, 24, 12); gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 36f; setColor(0xCC101418.toInt())
                }
            }
            (mapView.parent as? android.view.ViewGroup)?.let { parent ->
                (parent as? FrameLayout ?: parent).addView(
                    statusView,
                    FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER or Gravity.TOP; setMargins(0, 48, 0, 0) }
                )
            }
        }
        statusView.visibility = View.VISIBLE
        statusView.text = "定位中…"
        var done = false
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (done) return@addOnSuccessListener
                done = true
                statusView.visibility = View.GONE
                location ?: return@addOnSuccessListener
                if (location.accuracy > 30f) {
                    // Spec: accuracy must be < 30 m before we display a position.
                    statusView.visibility = View.VISIBLE
                    statusView.text = "定位精度不足（${location.accuracy.toInt()} 米），请点击“重新定位”"
                    return@addOnSuccessListener
                }
                selectLocation(LatLng(location.latitude, location.longitude), "当前位置", 17f)
            }
            .addOnFailureListener {
                done = true
                statusView.visibility = View.VISIBLE
                statusView.text = "定位失败，请点击“重新定位”"
            }
    }

    private fun selectLocation(location: LatLng, title: String, zoom: Float = 16f) {
        selected = location
        map?.clear()
        map?.addMarker(MarkerOptions().position(location).title(title))
        // 圆心跟随新选点（radiusCircle 引用已随 clear() 失效，重画）
        radiusCircle = null
        drawRadiusCircle()
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(location, zoom))
    }

    private fun returnSelection() {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_LATITUDE, selected.latitude)
                .putExtra(EXTRA_LONGITUDE, selected.longitude)
                .putExtra(EXTRA_RADIUS, radiusMeters)
        ); finish()
    }

    private fun showFallbackScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48) }
        root.addView(TextView(this).apply { text = "未检测到可用的 Google Play 服务。\n\n已保留高德地图（AMap）回退接口；第一版尚未集成高德 SDK。"; textSize = 17f })
        root.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        setContentView(root)
    }

    override fun onStart() { super.onStart(); if (::mapView.isInitialized) mapView.onStart() }
    override fun onResume() { super.onResume(); if (::mapView.isInitialized) mapView.onResume() }
    override fun onPause() { if (::mapView.isInitialized) mapView.onPause(); super.onPause() }
    override fun onStop() { if (::mapView.isInitialized) mapView.onStop(); super.onStop() }
    override fun onDestroy() { if (::mapView.isInitialized) mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); if (::mapView.isInitialized) mapView.onLowMemory() }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_LATITUDE = "latitude"; const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_RADIUS = "radius"
    }
}
