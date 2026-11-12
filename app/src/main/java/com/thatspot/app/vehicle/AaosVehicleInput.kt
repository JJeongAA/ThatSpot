package com.thatspot.app.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.thatspot.app.location.LocationFix
import com.thatspot.app.location.LocationSource
import com.thatspot.core.DriveState
import com.thatspot.core.Fix
import com.thatspot.core.Geo

/**
 * 실차 입력. 0.2초마다 최신 값으로 [Fix] 를 하나 만든다. 모든 콜백은 메인 스레드.
 *  - 속도·기어: 표준 AAOS CarPropertyManager (Pleos FAQ 가 안내하는 방법. Vehicle SDK 의 속도 리스너는 2.3.2 에서 빠졌다)
 *  - 브레이크·핸들·깜빡이: [VehicleExtras] (Pleos Vehicle SDK)
 *  - 위치: [LocationSource] (Pleos FusedLocation, 없으면 안드로이드 GPS)
 *
 * [gpsSpeed] 가 true 면 차량 속도 대신 위치 속도를 쓰고 기어를 무시한다.
 * 에뮬레이터에서 GPX 경로를 재생해 실제 흐름을 시험할 때 쓴다 (Virtual Console 에는 속도 카드가 없다).
 */
class AaosVehicleInput(
    private val context: Context,
    private val extras: VehicleExtras,
    private val locations: List<LocationSource>,
    private val gpsSpeed: Boolean,
) : VehicleInput {

    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var listener: VehicleInput.Listener? = null
    private var activeLocation: LocationSource? = null

    @Volatile private var carSpeedKmh: Double? = null
    @Volatile private var gear: Int? = null
    @Volatile private var gearSetMs = 0L
    private var lastMovingMs = 0L
    private var loc: LocationFix? = null
    private var prevLoc: LocationFix? = null
    private var state: DriveState? = null
    private var stillSinceMs: Long? = null
    /** 차량 속도를 구독하지 못했으면 (Pleos Connect Emulator 는 PERF_VEHICLE_SPEED 를 주지 않는다) 위치 속도를 쓴다 */
    @Volatile private var noCarSpeed = false
    private val useLocationSpeed get() = gpsSpeed || noCarSpeed

    override val capabilities: VehicleInput.Capabilities
        get() = VehicleInput.Capabilities(
            speed = when {
                gpsSpeed && loc != null -> "위치 속도 (시험 모드)"
                noCarSpeed && loc != null -> "위치 속도 (차량 속도 없음)"
                carSpeedKmh != null -> "차량 속도"
                else -> null
            },
            gear = gear != null,
            brake = extras.brakePressed != null,
            steering = extras.steeringDeg != null,
            turnSignal = extras.turnSignalOn != null,
            location = activeLocation?.name?.takeIf { loc != null } ?: activeLocation?.name?.let { "$it (신호 기다리는 중)" },
        )

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> carSpeedKmh = (value.value as Float) * 3.6
                VehiclePropertyIds.GEAR_SELECTION -> { gear = value.value as Int; gearSetMs = System.currentTimeMillis() }
            }
        }
        override fun onErrorEvent(propId: Int, zone: Int) { Log.w(TAG, "property error $propId") }
    }

    private val tick = object : Runnable {
        override fun run() { emit(); main.postDelayed(this, TICK_MS) }
    }

    override fun start(listener: VehicleInput.Listener) {
        this.listener = listener
        runCatching {
            car = Car.createCar(context)
            props = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        }.onFailure { Log.w(TAG, "Car 서비스 없음", it) }
        if (props == null) noCarSpeed = true
        props?.let { pm ->
            val ok = runCatching { pm.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FASTEST) }
                .onFailure { Log.w(TAG, "속도 구독 실패", it) }.getOrDefault(false)
            noCarSpeed = !ok
            if (!ok) Log.i(TAG, "차량 속도(PERF_VEHICLE_SPEED)를 주지 않는 기기라 위치 속도를 씁니다")
            runCatching { pm.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
                .onFailure { Log.w(TAG, "기어 구독 실패", it) }
        }
        extras.start()
        // 위치: 앞에 있는 것부터 시도 (Pleos FusedLocation → 안드로이드 GPS)
        startLocation(0)
        main.post(tick)
    }

    /** 위치: 앞에 있는 것부터 시도 (Pleos FusedLocation → 안드로이드 GPS). 10초 동안 신호가 없으면 다음 것으로. */
    private fun startLocation(from: Int) {
        activeLocation?.stop()
        activeLocation = null
        for (i in from until locations.size) {
            val src = locations[i]
            val ok = runCatching { src.start { onLocation(it) } }.onFailure { Log.w(TAG, "${src.name} 시작 실패", it) }.getOrDefault(false)
            if (!ok) continue
            activeLocation = src
            Log.i(TAG, "위치: ${src.name}")
            if (i + 1 < locations.size) main.postDelayed(locationWatch(i), 10_000)
            return
        }
        Log.i(TAG, "위치: 없음")
    }

    private fun locationWatch(index: Int) = Runnable {
        if (listener != null && loc == null && activeLocation === locations[index]) {
            Log.i(TAG, "${locations[index].name} 에서 10초 동안 위치가 오지 않아 다음 위치로 넘어갑니다")
            startLocation(index + 1)
        }
    }

    override fun stop() {
        main.removeCallbacksAndMessages(null)
        main.removeCallbacks(tick)
        runCatching { props?.unregisterCallback(callback) }
        extras.stop()
        activeLocation?.stop()
        activeLocation = null
        car?.disconnect()
        car = null
        listener = null
    }

    private fun onLocation(f: LocationFix) {
        prevLoc = loc
        loc = f
    }

    /** 위치 장치가 속도를 주지 않으면 앞 위치와의 거리로 구한다. */
    private fun locationSpeed(): Double? {
        val l = loc ?: return null
        l.speedKmh?.let { return it }
        val p = prevLoc ?: return null
        val dt = (l.timeMs - p.timeMs) / 1000.0
        if (dt <= 0.2) return null
        return Geo.distanceM(p.lat, p.lon, l.lat, l.lon) / dt * 3.6
    }

    private fun emit() {
        val l = listener ?: return
        val now = System.currentTimeMillis()
        val v = (if (useLocationSpeed) locationSpeed() else carSpeedKmh) ?: return
        val fresh = loc?.takeIf { now - it.timeMs < 3_000 }
        updateState(now, v, l)
        l.onFix(
            Fix(
                timeMs = now, speedKmh = v,
                lat = fresh?.lat, lon = fresh?.lon,
                // 멈춰 있을 때의 방향 값은 믿을 수 없다
                headingDeg = fresh?.headingDeg?.takeIf { v > 5 },
                // 경로 시험: 재생되는 감속에 맞춰 Virtual Console 브레이크를 누르기 어려우므로, 안 밟음은 '모름' 으로 본다
                brakePressed = if (gpsSpeed) extras.brakePressed?.takeIf { it } else extras.brakePressed,
                steeringDeg = extras.steeringDeg,
                turnSignalOn = extras.turnSignalOn,
            ),
        )
    }

    /** P단 = 주차, P 아님 + 속도 0 = 정차, 그 외 = 주행. 기어를 못 읽으면 60초 정지를 주차로 본다. */
    private fun updateState(now: Long, v: Double, l: VehicleInput.Listener) {
        val still = v < 1.0
        stillSinceMs = if (still) stillSinceMs ?: now else null
        if (!still) lastMovingMs = now
        // 경로 시험: Virtual Console 기어를 P 로 둔 채 경로를 재생할 수 있다. 그때는 '멈춘 뒤 P 로 바꿨을 때' 만 주차로 본다
        val parkedByGear = gear == VehicleGear.GEAR_PARK && (!gpsSpeed || (still && gearSetMs > lastMovingMs))
        val g = if (gpsSpeed && gear == VehicleGear.GEAR_PARK && !parkedByGear) null else gear
        val next = when {
            parkedByGear -> DriveState.PARKED
            g == null && still && now - (stillSinceMs ?: now) > 60_000 -> DriveState.PARKED
            still -> if (state == null) DriveState.PARKED else if (state == DriveState.PARKED) DriveState.PARKED else DriveState.IDLING
            else -> DriveState.MOVING
        }
        if (next != state) {
            Log.i(TAG, "상태 $state → $next (속도 ${"%.0f".format(v)}km/h, 기어 $gear, 위치 ${if (loc != null) "있음" else "없음"})")
            state = next; l.onDriveState(next, now)
        }
    }

    companion object {
        private const val TAG = "AaosVehicleInput"
        private const val TICK_MS = 200L
    }
}
