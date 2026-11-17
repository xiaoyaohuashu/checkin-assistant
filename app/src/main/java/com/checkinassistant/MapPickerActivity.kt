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
import com.checkinassistant.domain.MapBackend
import kotlinx.coroutines.launch
import com.checkinassistant.domain.MapDecision
import com.checkinassistant.domain.MapPickerRules
import com.checkinassistant.domain.PointDefaults
import com.checkinassistant.location.LocationProvider
import com.checkinassistant.location.LocationProviders
import com.checkinassistant.map.GeoPoint
import com.checkinassistant.map.AmapPrivacyPrompt
import com.checkinassistant.map.MapCircle
import com.checkinassistant.map.MapPickerView
import com.checkinassistant.map.MapProviders
import com.checkinassistant.map.MapServiceChooser
import com.checkinassistant.map.PickerMap
import com.checkinassistant.map.PlaceSearch
import com.checkinassistant.map.PlaceSearchListener

class MapPickerActivity : AppCompatActivity() {
    private lateinit var mapView: MapPickerView
    private var map: PickerMap? = null
    /** v2.2.25：谷歌或高德，在 onCreate 里按「地图服务」规则选定。 */
    private lateinit var placeSearch: PlaceSearch
    private var selected = MapProviders.geoPoint(PointDefaults.LATITUDE, PointDefaults.LONGITUDE)
    /** 触发范围（米）：编辑页传入的现值，或默认 200；选点页内可调，随确认回传 */
    private var radiusMeters = PointDefaults.RADIUS_M
    /** 打开地图时要不要自动定位到当前位置：新建时要；编辑已有点时不要（从原位置开始，见 MapPickerRules.initialSelection）。 */
    private var autoLocateOnOpen = true
    private var radiusCircle: MapCircle? = null
    private var radiusLabelView: TextView? = null
    private lateinit var locationProvider: LocationProvider
    /** v2.2.26：「定位到我」的超时；null = 不加超时（纯谷歌路径，和 v2.2.24 一样）。 */
    private var locateTimeoutMs: Long? = null
    /** 每次「定位到我」加 1；超时任务和迟到的回调只认最新一次。只在有超时的情况下使用。 */
    private var locateSeq = 0
    private var locateTimeoutTask: Runnable? = null
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
        // 编辑页传入的现有触发范围（100–5000 米，和编辑页一致，不截断），无传入则默认 200
        val incoming = PointDefaults.clampRadius(intent.getIntExtra(EXTRA_RADIUS, PointDefaults.RADIUS_M))
        radiusMeters = incoming
        // 问题 3：编辑已有提醒点时编辑页会带来原坐标，地图从那里开始；新建时不带，保持从默认位置开始并自动定位
        val initial = MapPickerRules.initialSelection(
            if (intent.hasExtra(EXTRA_LATITUDE)) intent.getDoubleExtra(EXTRA_LATITUDE, PointDefaults.LATITUDE) else null,
            if (intent.hasExtra(EXTRA_LONGITUDE)) intent.getDoubleExtra(EXTRA_LONGITUDE, PointDefaults.LONGITUDE) else null,
            PointDefaults.LATITUDE,
            PointDefaults.LONGITUDE
        )
        selected = MapProviders.geoPoint(initial.latitude, initial.longitude)
        autoLocateOnOpen = initial.autoLocate
        // v2.2.25：用谷歌还是高德（有 Google Play 服务、人不在中国大陆、设置为「自动」时一定是谷歌，不弹窗、不碰高德）
        var backend = when (val decision = MapServiceChooser.choose(this, selected)) {
            is MapDecision.AskPrivacy -> {
                // 先问高德隐私说明；同意或不同意都重新打开本页按新状态选，按返回键就关掉本页
                AmapPrivacyPrompt.ask(this, onAgree = { recreate() }, onDecline = { recreate() }, onCancel = { finish() })
                return
            }
            is MapDecision.Use -> {
                decision.notice?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                decision.backend
            }
        }
        if (backend == MapBackend.AMAP && !com.checkinassistant.map.amap.AmapSdk.ensureReady(applicationContext)) {
            Toast.makeText(this, "高德地图不可用，已改用谷歌地图", Toast.LENGTH_LONG).show()
            backend = MapBackend.GOOGLE
        }
        reportChinaRegionFailure()
        placeSearch = MapProviders.placeSearch(backend)
        // v2.2.26（中 1）：地图是高德时定位也用高德；否则和原来一样
        locationProvider = LocationProviders.forMap(this, backend)
        initializePlaces()
        mapView = try {
            MapProviders.pickerView(this, backend)
        } catch (t: Throwable) {
            if (backend != MapBackend.AMAP) throw t
            // 高德地图控件建不起来（比如原生库加载失败）：退回谷歌，不闪退
            Toast.makeText(this, "高德地图不可用，已改用谷歌地图", Toast.LENGTH_LONG).show()
            backend = MapBackend.GOOGLE
            placeSearch = MapProviders.placeSearch(MapBackend.GOOGLE)
            locationProvider = LocationProviders.get(this)
            initializePlaces()
            MapProviders.pickerView(this, MapBackend.GOOGLE)
        }
        // v2.2.26（中 1）：定位可能走高德时加 15 秒超时；纯谷歌（谷歌地图 + 谷歌定位）为 null，不加超时
        locateTimeoutMs = MapPickerRules.locateTimeoutMs(
            backendIsAmap = backend == MapBackend.AMAP,
            dynamicLocation = com.checkinassistant.domain.LocationSourceRules.needsDynamic(
                com.checkinassistant.map.MapSettings.setting(this), com.checkinassistant.location.PlayServices.isInstalled(this)
            )
        )
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { onMapReady(it) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(FrameLayout(this).apply { id = autocompleteContainerId }, LinearLayout.LayoutParams(-1, -2))
        // 地图容器：地图 + 右缘竖向滑杆叠加（滑杆放底部常被系统误判为任务切换手势）
        val mapContainer = FrameLayout(this)
        mapContainer.addView(mapView.view, FrameLayout.LayoutParams(-1, -1))
        // 触发范围：右缘竖向滑杆（SeekBar 旋转 270°，向上拖 = 半径增大）+ 半径胶囊文字
        val radiusLabel = TextView(this).apply {
            textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 36f; setColor(0xB3101418.toInt())
            }
            setPadding(20, 10, 20, 10)
        }
        val radiusSlider = android.widget.SeekBar(this).apply {
            max = PointDefaults.RADIUS_MAX_M - PointDefaults.RADIUS_MIN_M
            progress = radiusMeters - PointDefaults.RADIUS_MIN_M
            rotation = 270f
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    setRadiusMeters(PointDefaults.RADIUS_MIN_M + progress)
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

    /** v2.2.26：边界数据读不出来时（正常不会发生）在调试日志里留一条，每次打开 App 最多一条。 */
    private fun reportChinaRegionFailure() {
        val failure = com.checkinassistant.domain.ChinaRegion.loadFailure ?: return
        if (chinaRegionFailureReported) return
        chinaRegionFailureReported = true
        val app = applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.checkinassistant.data.DiagnosticRecordLogger.geofence(app, "CHINA_REGION_LOAD_FAILED：中国边界数据读取失败，一律按境外处理；$failure")
        }
    }

    private fun initializePlaces() {
        if (placeSearch.isInitialized) return
        val apiKey = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("com.google.android.geo.API_KEY")
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, "地址搜索不可用：未配置 Google API Key", Toast.LENGTH_LONG).show()
            return
        }
        placeSearch.initialize(applicationContext, apiKey)
    }

    private fun setupAutocomplete() {
        if (!placeSearch.isInitialized) return
        placeSearch.attach(this, autocompleteContainerId, listOf(SEARCH_COUNTRY), object : PlaceSearchListener {
            override fun onPlaceSelected(location: GeoPoint?, name: String?, address: String?) {
                if (location == null) {
                    Toast.makeText(this@MapPickerActivity, "未能获取该地点的坐标，请选择其他结果", Toast.LENGTH_SHORT).show()
                    return
                }
                selectLocation(location, name ?: address ?: "已选位置")
            }

            override fun onError(message: String?) {
                Toast.makeText(this@MapPickerActivity, "地址搜索失败：${message ?: "请检查网络后重试"}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun onMapReady(googleMap: PickerMap) {
        map = googleMap
        googleMap.moveCamera(selected, if (autoLocateOnOpen) ZOOM_INITIAL else ZOOM_SELECTED)
        googleMap.addMarker(selected, "已选位置")
        drawRadiusCircle()
        googleMap.setOnMapClickListener { latLng ->
            selectLocation(latLng, "已选位置")
        }
        requestLocationOrCenterMap()
    }

    /** 以当前选点为圆心绘制半透明触发范围圆（半径与触发范围完全一致） */
    private fun drawRadiusCircle() {
        radiusCircle?.remove()
        radiusCircle = map?.addCircle(selected, radiusMeters.toDouble(), CIRCLE_STROKE_COLOR, CIRCLE_STROKE_WIDTH, CIRCLE_FILL_COLOR)
    }

    /** 滑杆回调：更新半径并实时同步圆形覆盖与文字 */
    private fun setRadiusMeters(meters: Int) {
        radiusMeters = PointDefaults.clampRadius(meters)
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
        googleMap.setMyLocationEnabled(true)
        googleMap.setMyLocationButtonEnabled(true)
        // 编辑已有点时不自动定位，避免把原标记挪走；「重新定位」按钮照常可用
        if (autoLocateOnOpen) locateHighAccuracy()
    }

    /** "定位中" pill shown while waiting for a high-accuracy fix. */
    private lateinit var statusView: TextView

    /**
     * 精度 30 米以内：把定位结果设为选中位置（v5 任务三，行为不变）。
     * 精度不到 30 米（v2.2.24）：只把地图移过去并提示，不改选中位置。判断在 MapPickerRules.onLocated。
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
            (mapView.view.parent as? android.view.ViewGroup)?.let { parent ->
                (parent as? FrameLayout ?: parent).addView(
                    statusView,
                    FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER or Gravity.TOP; setMargins(0, 48, 0, 0) }
                )
            }
        }
        statusView.visibility = View.VISIBLE
        statusView.text = "定位中…"
        var done = false
        // v2.2.26（中 1）：有超时的情况下，到点还没结果就提示可以手动点选；之后迟到的结果不再采用。
        val timeoutMs = locateTimeoutMs
        val seq = ++locateSeq
        if (timeoutMs != null) {
            locateTimeoutTask?.let(statusView::removeCallbacks)
            val task = Runnable {
                if (done || seq != locateSeq) return@Runnable
                done = true
                statusView.visibility = View.VISIBLE
                statusView.text = MapPickerRules.LOCATE_TIMEOUT_TEXT
            }
            locateTimeoutTask = task
            statusView.postDelayed(task, timeoutMs)
        }
        locationProvider.requestCurrentLocationHighAccuracy(
            onSuccess = onSuccess@{ location ->
                if (timeoutMs != null && seq != locateSeq) return@onSuccess
                if (done) return@onSuccess
                done = true
                statusView.visibility = View.GONE
                location ?: return@onSuccess
                val decision = MapPickerRules.onLocated(location.accuracy)
                if (!decision.adoptAsSelected) {
                    // v2.2.24：精度一般时只把地图移过去方便用户看和点选；选中位置、标记、圆圈都不动
                    statusView.visibility = View.VISIBLE
                    statusView.text = decision.statusText
                    map?.animateCamera(MapProviders.geoPoint(location.latitude, location.longitude), ZOOM_MY_LOCATION)
                    return@onSuccess
                }
                selectLocation(MapProviders.geoPoint(location.latitude, location.longitude), "当前位置", ZOOM_MY_LOCATION)
            },
            onFailure = onFailure@{
                if (timeoutMs != null && (done || seq != locateSeq)) return@onFailure
                done = true
                statusView.visibility = View.VISIBLE
                statusView.text = "定位失败，请点击“重新定位”"
            }
        )
    }

    private fun selectLocation(location: GeoPoint, title: String, zoom: Float = ZOOM_SELECTED) {
        selected = location
        map?.clear()
        map?.addMarker(location, title)
        // 圆心跟随新选点（radiusCircle 引用已随 clear() 失效，重画）
        radiusCircle = null
        drawRadiusCircle()
        map?.animateCamera(location, zoom)
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

    override fun onStart() { super.onStart(); if (::mapView.isInitialized) mapView.onStart() }
    override fun onResume() { super.onResume(); if (::mapView.isInitialized) mapView.onResume() }
    override fun onPause() { if (::mapView.isInitialized) mapView.onPause(); super.onPause() }
    override fun onStop() { if (::mapView.isInitialized) mapView.onStop(); super.onStop() }
    override fun onDestroy() {
        locateTimeoutTask?.let { if (::statusView.isInitialized) statusView.removeCallbacks(it) }
        if (::mapView.isInitialized) mapView.onDestroy(); super.onDestroy()
    }
    override fun onLowMemory() { super.onLowMemory(); if (::mapView.isInitialized) mapView.onLowMemory() }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** 回传选中位置用；v2.2.24 起编辑已有点时也用同样两个名字把原坐标带进来。 */
        const val EXTRA_LATITUDE = "latitude"; const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_RADIUS = "radius"
        /** 地图缩放级别：刚打开 / 选点或搜索后 / 定位到当前位置。 */
        private const val ZOOM_INITIAL = 14f
        private const val ZOOM_SELECTED = 16f
        private const val ZOOM_MY_LOCATION = 17f
        /** 触发范围圆：半透明蓝色描边和填充。 */
        private const val CIRCLE_STROKE_COLOR = 0x662F6BF3
        private const val CIRCLE_FILL_COLOR = 0x262F6BF3
        private const val CIRCLE_STROKE_WIDTH = 3f
        @Volatile private var chinaRegionFailureReported = false
        /** 地址搜索只搜马来西亚境内（谷歌）。高德搜索忽略这个参数、搜全中国。 */
        private const val SEARCH_COUNTRY = "MY"
    }
}
