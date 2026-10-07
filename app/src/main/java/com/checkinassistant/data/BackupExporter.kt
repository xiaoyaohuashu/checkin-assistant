package com.checkinassistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v6: full data export (points / records / skip events / enable events) as one
 * JSON document. Pure read — never mutates the database.
 */
object BackupExporter {

    suspend fun exportAll(context: Context): String {
        val db = AppDatabase.get(context.applicationContext)
        val out = JSONObject()
        out.put("app", "checkin-assistant")
        out.put("schema", db.hashCode()) // informational only
        out.put("exportedAt", System.currentTimeMillis())

        out.put("points", JSONArray().apply {
            db.checkInPointDao().getAllOnce().forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("name", p.name)
                    put("latitude", p.latitude); put("longitude", p.longitude)
                    put("radiusMeters", p.radiusMeters)
                    put("repeatMode", p.repeatMode); put("onceStatus", p.onceStatus)
                    put("activeDays", p.activeDays)
                    put("activeStartMinute", p.activeStartMinute)
                    put("activeEndMinute", p.activeEndMinute)
                    put("enabled", p.enabled)
                })
            }
        })
        out.put("records", JSONArray().apply {
            db.recordDao().getAllOnce().forEach { r ->
                put(JSONObject().apply {
                    put("pointId", r.pointId); put("pointName", r.pointName)
                    put("triggeredAt", r.triggeredAt); put("status", r.status)
                })
            }
        })
        return out.toString(2)
    }
}
