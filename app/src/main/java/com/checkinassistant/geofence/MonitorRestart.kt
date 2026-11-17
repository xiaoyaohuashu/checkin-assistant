package com.checkinassistant.geofence

import android.content.Context
import android.content.Intent
import com.checkinassistant.data.DiagnosticRecordLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * v2.2.26（中 1）：设置里把「地图服务」切到「始终高德」时，让正在跑的后台服务换一个定位对象。
 * 服务的定位对象只在服务创建时取一次，所以做法是：销毁服务实例，再走普通入口重新启动。
 * 只从设置页（App 在前台）调用。
 */
internal object MonitorRestart {
    /**
     * 用户手动停止过，或服务没在跑：什么都不做，返回 null。
     * 否则重建服务；返回 null = 已发出启动请求，否则是启动时的异常。
     * 不走 stop()：不碰「手动停止」标记，不取消轮询和时段闹钟（万一没起来，这些闹钟会把服务再拉起来）。
     */
    fun recreateService(context: Context): Exception? {
        val app = context.applicationContext
        if (MonitorKeeper.userStopped(app) || !GeofenceMonitoringService.isRunning) return null
        CoroutineScope(Dispatchers.IO).launch {
            DiagnosticRecordLogger.geofence(app, "MONITOR_RECREATE：地图服务改为始终高德，重建后台服务以切换定位来源（随后的 MONITOR_SERVICE_DESTROY 属正常）")
        }
        app.stopService(Intent(app, GeofenceMonitoringService::class.java))
        return GeofenceMonitoringService.tryStart(app)
    }
}
