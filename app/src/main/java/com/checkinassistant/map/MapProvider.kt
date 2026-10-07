package com.checkinassistant.map

import android.content.Context

/** Abstraction over the map engine, kept from v1 for a possible AMap fallback. */
interface MapProvider {
    val id: String
    /** Whether this provider is usable on this device right now. */
    fun isAvailable(context: Context): Boolean
}

object GoogleMapsProvider : MapProvider {
    override val id = "google"
    override fun isAvailable(context: Context): Boolean = true
}
