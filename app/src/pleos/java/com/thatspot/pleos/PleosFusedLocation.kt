package com.thatspot.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.thatspot.app.location.GeoTarget
import com.thatspot.app.location.Geofencer
import com.thatspot.app.location.LocationFix
import com.thatspot.app.location.LocationSource

/**
 * Pleos FusedLocation SDK 1.1.3: 위치 구독 + 근접 알림.
 * 호출 모양은 공식 샘플 그대로 (github.com/Pleos-Playground/pleos-sample-fusedlocation-app,
 * location/FusedLocationClient.kt, MainViewModel.kt).
 *
 * 근접 알림은 '알리는 지점' 마다 하나씩 건다. 반경은 알림 거리보다 넓게 (400m + 100m).
 * ENTER 를 받으면 엔진이 그 지점을 알림 후보로 두고, 실제 알림 시점(거리·방향)은 엔진이 정한다.
 * 근접 알림은 방향을 모르기 때문이다 (반대 차로로 지날 때 알리면 안 된다).
 */
class PleosFusedLocation(context: Context) : LocationSource, Geofencer {

    override val name = "Pleos FusedLocation"
    private val app = context.applicationContext
    private val fused = FusedLocation(app)
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false
    private var onFix: ((LocationFix) -> Unit)? = null

    override var onTransition: (spotId: Long, entered: Boolean) -> Unit = { _, _ -> }

    /** spotId → 등록된 alertId. 등록 중이면 null */
    private val alerts = mutableMapOf<Long, String?>()
    private val targets = mutableMapOf<Long, GeoTarget>()
    @Volatile private var errors = 0

    override val healthy: Boolean
        get() = initialized && errors == 0 && alerts.isNotEmpty() && alerts.values.all { it != null }

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            val l = location ?: return
            if (!l.latitude.isFinite() || !l.longitude.isFinite()) return
            val fix = LocationFix(
                timeMs = System.currentTimeMillis(), lat = l.latitude, lon = l.longitude,
                headingDeg = l.heading.toDouble().takeIf { it.isFinite() && it >= 0 }, speedKmh = null,
            )
            main.post { onFix?.invoke(fix) }
        }

        override fun onError(exception: Exception) { Log.w(TAG, "위치 오류", exception) }
    }

    private fun ensureInit(): Boolean {
        if (initialized) return true
        if (app.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "$PERMISSION 권한이 아직 없어요"); return false
        }
        val supported = runCatching { fused.isSupported() }.getOrDefault(false)
        if (!supported) { Log.i(TAG, "이 기기에는 Pleos FusedLocation 서비스가 없어요"); return false }
        return runCatching { fused.initialize(); initialized = true; true }
            .onFailure { Log.w(TAG, "FusedLocation 초기화 실패", it) }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 위치

    override fun start(onFix: (LocationFix) -> Unit): Boolean {
        if (!ensureInit()) return false
        this.onFix = onFix
        return runCatching { fused.registerFusedLocationCallback(listener); true }
            .onFailure { Log.w(TAG, "위치 구독 실패", it) }.getOrDefault(false)
            .also { if (it) runCatching { fused.getLastKnownLocation(listener) } }
    }

    override fun stop() {
        onFix = null
        if (initialized) runCatching { fused.unregisterFusedLocationCallback(listener) }
    }

    // ------------------------------------------------------------------ 근접 알림

    override fun sync(targets: List<GeoTarget>) {
        if (!ensureInit()) return
        val want = targets.associateBy { it.spotId }
        // 없어졌거나 자리가 바뀐 지점은 지운다
        alerts.keys.toList().forEach { id ->
            val old = this.targets[id]; val now = want[id]
            if (now == null || old != now) remove(id)
        }
        want.values.filter { it.spotId !in alerts }.forEach { add(it) }
    }

    private fun add(t: GeoTarget) {
        alerts[t.spotId] = null
        targets[t.spotId] = t
        val l = object : ProximityAlertListener {
            override fun onRegistered(alertId: String) {
                main.post { if (t.spotId in alerts) alerts[t.spotId] = alertId else fused.removeProximityAlert(alertId) }
            }
            override fun onTransition(event: ProximityAlertEvent) {
                val entered = event.transition == ProximityTransition.ENTER
                main.post { onTransition(t.spotId, entered) }
            }
            override fun onError(exception: Exception) {
                Log.w(TAG, "근접 알림 오류 (지점 ${t.spotId})", exception)
                errors += 1
            }
        }
        runCatching {
            fused.addProximityAlert(
                ProximityAlertRequest(
                    latitude = t.lat, longitude = t.lon, radiusMeters = t.radiusM,
                    transitionTypes = setOf(ProximityTransition.ENTER, ProximityTransition.EXIT),
                ),
                l,
            )
        }.onFailure { Log.w(TAG, "근접 알림 등록 실패", it); errors += 1 }
    }

    private fun remove(spotId: Long) {
        alerts.remove(spotId)?.let { id -> runCatching { fused.removeProximityAlert(id) } }
        targets.remove(spotId)
    }

    override fun clear() { alerts.keys.toList().forEach(::remove); errors = 0 }

    fun release() {
        clear()
        stop()
        if (initialized) runCatching { fused.release() }
        initialized = false
    }

    companion object {
        private const val TAG = "PleosFusedLocation"
        private const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}
