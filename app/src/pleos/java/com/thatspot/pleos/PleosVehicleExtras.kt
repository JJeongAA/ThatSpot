package com.thatspot.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.thatspot.app.vehicle.VehicleExtras

/**
 * Pleos Vehicle SDK 2.3.2.
 *  - import 경로와 호출 모양: Pleos 공식 Vehicle 샘플 (github.com/Pleos-Playground/pleos-sample-vehicle-app)
 *  - Brake / SteeringWheel / TurnSignal: Pleos Connect Emulator 에서 값이 0.2초마다 들어오는 것을 Logcat 으로 확인함
 *    (BRAKE_PEDAL_STATUS, PERF_STEERING_ANGLE, TURN_SIGNAL_STATE)
 *
 * 속도는 여기서 읽지 않는다. Vehicle SDK 의 속도 리스너는 2.3.2 에서 빠졌고, Pleos FAQ 는
 * 표준 CarPropertyManager PERF_VEHICLE_SPEED 를 안내한다 (AaosVehicleInput).
 */
class PleosVehicleExtras(context: Context) : VehicleExtras {

    private val vehicle = Vehicle(context.applicationContext)

    @Volatile override var brakePressed: Boolean? = null; private set
    @Volatile override var steeringDeg: Double? = null; private set
    @Volatile override var turnSignalOn: Boolean? = null; private set

    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    @Volatile private var running = false
    private var initialized = false

    override fun start() {
        if (running) return
        try {
            if (!initialized) { vehicle.initialize(); initialized = true }
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle SDK 초기화 실패. Pleos Connect Emulator 인지, 권한을 허용했는지 확인하세요.", e)
            return
        }
        thread = HandlerThread("pleos-vehicle").also { it.start() }
        bg = Handler(thread!!.looper)
        running = true
        connectionTest()
        loop()
    }

    override fun stop() {
        if (!running) return
        running = false
        bg?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        bg = null; thread = null
    }

    /** 앱이 끝날 때만 부른다 (stop → start 가 권한 허용 때마다 반복되므로 release 는 따로). */
    fun release() {
        stop()
        if (initialized) runCatching { vehicle.release() }
        initialized = false
    }

    /** Pleos 문서의 첫 API 호출 예제 그대로. Logcat 에서 'PleosVehicleExtras' 로 결과를 본다. */
    private fun connectionTest() = guarded("door") {
        vehicle.getDoor().getCurrentDoorPosition(
            area = DoorArea(DoorValue.ROW_1_LEFT),
            onComplete = { pos: Int? -> Log.i(TAG, "Vehicle SDK 연결됨 - 운전석 문 위치 = $pos") },
            onFailed = { e: Exception -> Log.w(TAG, "Vehicle SDK 연결 확인 실패", e) },
        )
    }

    private fun loop() {
        val h = bg ?: return
        h.post(object : Runnable {
            override fun run() {
                if (!running) return
                pollBrake(); pollSteering(); pollTurnSignal()
                h.postDelayed(this, POLL_MS)
            }
        })
    }

    private fun pollBrake() = guarded("brake") {
        vehicle.getBrake().getCurrentBrakePedalStatus(
            onComplete = { v -> brakePressed = asOn(v) },
            onFailed = { e -> fail("brake", e); brakePressed = null },
        )
    }

    private fun pollSteering() = guarded("steering") {
        vehicle.getSteeringWheel().getCurrentSteeringWheelAngle(
            onComplete = { v -> steeringDeg = (v as? Number)?.toDouble() },
            onFailed = { e -> fail("steering", e); steeringDeg = null },
        )
    }

    private fun pollTurnSignal() = guarded("turn signal") {
        vehicle.getTurnSignal().getCurrentTurnSignalState(
            onComplete = { v -> turnSignalOn = asOn(v) },
            onFailed = { e -> fail("turn signal", e); turnSignalOn = null },
        )
    }

    private val failedOnce = mutableSetOf<String>()
    private fun fail(name: String, e: Exception) {
        if (failedOnce.add(name)) Log.w(TAG, "$name 읽기 실패 (이 차종에서 지원하지 않을 수 있음)", e)
    }

    private inline fun guarded(name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { fail(name, e) }
    }

    companion object {
        private const val TAG = "PleosVehicleExtras"
        private const val POLL_MS = 200L

        /** 켜짐/눌림 해석. Boolean, 숫자(0 = 꺼짐), enum(OFF/RELEASED/NONE = 꺼짐) 모두 받는다. */
        fun asOn(v: Any?): Boolean? = when (v) {
            null -> null
            is Boolean -> v
            is Number -> v.toInt() != 0
            is Enum<*> -> v.name.uppercase().let { n ->
                !(n.contains("OFF") || n.contains("RELEASE") || n == "NONE" || n.contains("UNKNOWN"))
            }
            else -> null
        }
    }
}
