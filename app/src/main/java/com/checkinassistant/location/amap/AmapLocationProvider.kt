package com.checkinassistant.location.amap

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.checkinassistant.location.LocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LocationProvider] 的高德实现。同意了隐私说明后，在三种情况下用：没有 Google Play 服务；
 * 设置选了「始终高德」；地图选点页的地图是高德（后两种 v2.2.26 起）。
 * 单次高精度定位、不用缓存；高德给的 GCJ-02 换回 WGS-84 再交出去（在 [AmapLocator] 里）。
 * 超时（15 秒）、精度门槛仍在调用方，和谷歌实现一样。
 * v2.2.26（中 4）：不再每次新建高德客户端，统一走 [AmapLocator] 复用同一个。
 */
internal class AmapLocationProvider(context: Context) : LocationProvider {
    private val app = context.applicationContext

    override suspend fun currentLocationHighAccuracy(): Location? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val cancel = AmapLocator.request(
                app,
                onResult = { if (cont.isActive) cont.resume(it) },
                onError = { if (cont.isActive) cont.resumeWithException(it) }
            )
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { cancel() } }
        }
    }

    override fun requestCurrentLocationHighAccuracy(onSuccess: (Location?) -> Unit, onFailure: (Exception) -> Unit) {
        Handler(Looper.getMainLooper()).post { AmapLocator.request(app, onSuccess, onFailure) }
    }
}
