package com.checkinassistant.map.google

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.checkinassistant.map.PlaceSearch
import com.checkinassistant.map.PlaceSearchListener
import com.google.android.gms.common.api.Status
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener

/** 地址搜索的 Google 实现（Places 自动补全搜索框）。调用和参数是从选点页原样搬来的。 */
internal object GooglePlaceSearch : PlaceSearch {
    override val isInitialized: Boolean get() = Places.isInitialized()

    override fun initialize(applicationContext: Context, apiKey: String) {
        Places.initializeWithNewPlacesApiEnabled(applicationContext, apiKey)
    }

    override fun attach(activity: FragmentActivity, containerId: Int, countries: List<String>, listener: PlaceSearchListener) {
        val autocomplete = AutocompleteSupportFragment.newInstance().apply {
            setPlaceFields(listOf(Place.Field.ID, Place.Field.NAME, Place.Field.ADDRESS, Place.Field.LAT_LNG))
            setCountries(countries)
            setOnPlaceSelectedListener(object : PlaceSelectionListener {
                override fun onPlaceSelected(place: Place) {
                    listener.onPlaceSelected(place.latLng?.toGeoPoint(), place.name, place.address)
                }

                override fun onError(status: Status) {
                    listener.onError(status.statusMessage)
                }
            })
        }
        activity.supportFragmentManager.beginTransaction()
            .replace(containerId, autocomplete)
            .commit()
    }
}
