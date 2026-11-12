package com.thatspot.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log

/** 위치 한 번. 속도는 위치 장치가 줄 때만 있다 (Pleos FusedLocation 은 속도를 주지 않는다). */
data class LocationFix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val headingDeg: Double?,
    val speedKmh: Double?,
)

interface LocationSource {
    val name: String
    /** @return 시작했으면 true (권한이 없거나 지원하지 않으면 false) */
    fun start(onFix: (LocationFix) -> Unit): Boolean
    fun stop()
}

/** 근접 알림을 걸 지점 하나. */
data class GeoTarget(val spotId: Long, val lat: Double, val lon: Double, val radiusM: Float)

/**
 * 위치 서비스의 근접 알림 (지오펜스). Pleos FusedLocation ProximityAlert 로 구현한다.
 * [healthy] 가 true 일 때만 엔진이 지오펜스 안의 지점에서만 알린다. 등록에 실패하면 엔진이 직접 거리를 잰다.
 */
interface Geofencer {
    val healthy: Boolean
    var onTransition: (spotId: Long, entered: Boolean) -> Unit
    fun sync(targets: List<GeoTarget>)
    fun clear()
}

/** 안드로이드 기본 위치 (GPS). Pleos 가 없을 때와, 에뮬레이터에서 GPX 경로를 재생할 때 쓴다. */
class AndroidLocationSource(private val context: Context) : LocationSource {
    override val name = "안드로이드 GPS"
    private val lm = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission")
    override fun start(onFix: (LocationFix) -> Unit): Boolean {
        stop()
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        val l = LocationListener { loc: Location ->
            onFix(
                LocationFix(
                    timeMs = System.currentTimeMillis(),
                    lat = loc.latitude, lon = loc.longitude,
                    headingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                    speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else null,
                ),
            )
        }
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
        }
        if (providers.isEmpty()) return false
        var ok = false
        for (p in providers) {
            runCatching { lm.requestLocationUpdates(p, 500L, 0f, l, Looper.getMainLooper()); ok = true }
                .onFailure { Log.w(TAG, "$p 위치 구독 실패", it) }
        }
        if (ok) listener = l
        return ok
    }

    override fun stop() {
        listener?.let { runCatching { lm.removeUpdates(it) } }
        listener = null
    }

    companion object { private const val TAG = "AndroidLocation" }
}
