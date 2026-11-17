package com.checkinassistant.location.google

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.checkinassistant.location.LocationProvider
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

/**
 * [LocationProvider] 的 Google 实现（FusedLocationProviderClient）。
 * 里面的两句调用是从原来三个调用点原样搬来的，参数没动。
 */
internal class GoogleLocationProvider(context: Context) : LocationProvider {
    // 构造时立刻取客户端：和原来各处「先取客户端、再调 getCurrentLocation」的先后一致。
    private val client = LocationServices.getFusedLocationProviderClient(context)

    // 权限由调用方在调用前检查（和原来一样），所以这里压掉 lint 的权限提示。
    @SuppressLint("MissingPermission")
    override suspend fun currentLocationHighAccuracy(): Location? =
        // 取消令牌每次新建、从不取消：调用方 15 秒超时后，Google 那边的请求并不会被取消（现状，保持）。
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()

    @SuppressLint("MissingPermission")
    override fun requestCurrentLocationHighAccuracy(onSuccess: (Location?) -> Unit, onFailure: (Exception) -> Unit) {
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location -> onSuccess(location) }
            .addOnFailureListener { error -> onFailure(error) }
    }
}
