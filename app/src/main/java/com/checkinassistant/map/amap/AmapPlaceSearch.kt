package com.checkinassistant.map.amap

import android.app.AlertDialog
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.checkinassistant.domain.CoordConverter
import com.checkinassistant.map.GeoPoint
import com.checkinassistant.map.PlaceSearch
import com.checkinassistant.map.PlaceSearchListener

/**
 * v2.2.25：地址搜索的高德实现：输入框 + 「搜索」按钮，在全中国范围搜（忽略传进来的国家参数），
 * 结果列表点选后把 GCJ-02 换回 WGS-84。普通 class，没有任何静态字段。
 * 不需要另外初始化（隐私合规在 AmapSdk.ensureReady 里做），所以 [isInitialized] 恒为 true，
 * 选点页因此不会去读谷歌的 Key。
 */
internal class AmapPlaceSearch : PlaceSearch {
    override val isInitialized: Boolean get() = true

    override fun initialize(applicationContext: Context, apiKey: String) {}

    override fun attach(activity: FragmentActivity, containerId: Int, countries: List<String>, listener: PlaceSearchListener) {
        val container = activity.findViewById<FrameLayout>(containerId) ?: return
        val input = EditText(activity).apply {
            hint = "搜索地点（中国）"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val button = Button(activity).apply { text = "搜索" }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button, LinearLayout.LayoutParams(-2, -2))
        }
        container.addView(row, FrameLayout.LayoutParams(-1, -2))
        val go = {
            val keyword = input.text.toString().trim()
            if (keyword.isNotEmpty()) {
                activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
                search(activity, keyword, listener)
            }
        }
        button.setOnClickListener { go() }
        input.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_SEARCH) { go(); true } else false }
    }

    private fun search(activity: FragmentActivity, keyword: String, listener: PlaceSearchListener) {
        try {
            if (!AmapSdk.ensureReady(activity)) {
                listener.onError("高德搜索不可用（未配置 Key 或未同意隐私说明）")
                return
            }
            val query = PoiSearch.Query(keyword, "", "")
            query.setPageSize(20)
            query.setCityLimit(false)
            val poiSearch = PoiSearch(activity, query)
            poiSearch.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResult?, code: Int) {
                    if (activity.isFinishing || activity.isDestroyed) return
                    if (code != 1000) {
                        listener.onError("高德搜索失败（错误码 $code）")
                        return
                    }
                    val items = result?.pois.orEmpty().filter { it.latLonPoint != null }
                    if (items.isEmpty()) {
                        Toast.makeText(activity, "没有找到相关地点", Toast.LENGTH_SHORT).show()
                        return
                    }
                    showResults(activity, items, listener)
                }

                override fun onPoiItemSearched(item: PoiItem?, code: Int) {}
            })
            poiSearch.searchPOIAsyn()
        } catch (t: Throwable) {
            listener.onError("高德搜索出错：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun showResults(activity: FragmentActivity, items: List<PoiItem>, listener: PlaceSearchListener) {
        val labels = items.map { item ->
            listOfNotNull(item.title, listOfNotNull(item.cityName, item.adName, item.snippet).joinToString(" ").ifBlank { null })
                .joinToString(" · ")
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("搜索结果")
            .setItems(labels) { _, which ->
                val item = items[which]
                val p = item.latLonPoint
                val wgs = CoordConverter.gcjToWgsInAmapArea(p.latitude, p.longitude)
                listener.onPlaceSelected(GeoPoint(wgs[0], wgs[1]), item.title, item.snippet)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
