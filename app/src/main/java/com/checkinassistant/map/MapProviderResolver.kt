package com.checkinassistant.map

import android.content.Context

/** Registry resolving the usable map provider for this device. */
object MapProviderResolver {
    fun resolve(context: Context): MapProvider = GoogleMapsProvider
}
