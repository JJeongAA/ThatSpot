package com.thatspot.app

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.thatspot.app.data.StateStore
import com.thatspot.app.location.AndroidLocationSource
import com.thatspot.app.location.GeoTarget
import com.thatspot.app.vehicle.AaosVehicleInput
import com.thatspot.app.vehicle.DemoVehicleInput
import com.thatspot.app.vehicle.NoExtras
import com.thatspot.app.vehicle.VehicleInput
import com.thatspot.app.voice.AndroidEars
import com.thatspot.app.voice.AndroidSpeaker
import com.thatspot.app.voice.Ears
import com.thatspot.app.voice.Speaker
import com.thatspot.core.Cause
import com.thatspot.core.CauseMatcher
import com.thatspot.core.DemoScenario
import com.thatspot.core.DriveState
import com.thatspot.core.EngineState
import com.thatspot.core.EventKind
import com.thatspot.core.Fix
import com.thatspot.core.Output
import com.thatspot.core.Spot
import com.thatspot.core.SpotEngine
import com.thatspot.core.SpotStatus
import com.thatspot.core.Texts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 앱 전체에서 하나. 입력 → 엔진 → 음성·화면을 잇는다. 모든 엔진 호출은 메인 스레드에서 일어난다.
 */
class AppController(private val context: Context) {

    enum class Mode { REAL, DEMO }

    private val store = StateStore(context)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var engine = newEngine(store.load())
    private val kit: PleosKit? = PleosKit.loadOrNull(context)
    private var input: VehicleInput? = null
    private lateinit var androidSpeaker: AndroidSpeaker
    private lateinit var androidEars: AndroidEars
    private var car: Car? = null

    // ------------------------------------------------------------------ 화면이 보는 상태

    private val _driveState = MutableStateFlow(DriveState.PARKED)
    val driveState: StateFlow<DriveState> = _driveState.asStateFlow()

    /** Car 서비스가 알려 주는 주행 제한 (Pleos 가이드: CarUxRestrictions 로 처리할 것) */
    private val _uxRestricted = MutableStateFlow(false)
    val uxRestricted: StateFlow<Boolean> = _uxRestricted.asStateFlow()

    /** 주차 화면 카드 대기열. 맨 앞이 지금 보이는 것 */
    private val _cards = MutableStateFlow<List<Output>>(emptyList())
    val cards: StateFlow<List<Output>> = _cards.asStateFlow()

    /** 주행 중 화면에 잠깐 띄우는 알림 (거리 + 이름) */
    private val _alert = MutableStateFlow<Output.Alert?>(null)
    val alert: StateFlow<Output.Alert?> = _alert.asStateFlow()
    private val clearAlert = Runnable { _alert.value = null }

    private val _spots = MutableStateFlow<List<Spot>>(emptyList())
    val spots: StateFlow<List<Spot>> = _spots.asStateFlow()
    /** 목록을 다시 그리게 하는 값 (Spot 은 내용이 바뀌는 객체라서) */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _mode = MutableStateFlow(Mode.REAL)
    val mode: StateFlow<Mode> = _mode.asStateFlow()
    private val _demoNote = MutableStateFlow<String?>(null)
    val demoNote: StateFlow<String?> = _demoNote.asStateFlow()
    private val _demoPaused = MutableStateFlow<String?>(null)
    val demoPaused: StateFlow<String?> = _demoPaused.asStateFlow()

    /** 주차 화면에서 이유를 말로 듣는 중이면 true, 들은 말은 [heard] */
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _heard = MutableStateFlow<String?>(null)
    val heard: StateFlow<String?> = _heard.asStateFlow()

    data class Caption(val text: String, val audible: Boolean)
    private val _caption = MutableStateFlow<Caption?>(null)
    val caption: StateFlow<Caption?> = _caption.asStateFlow()
    private val clearCaption = Runnable { _caption.value = null }

    private val _capabilities = MutableStateFlow<VehicleInput.Capabilities?>(null)
    val capabilities: StateFlow<VehicleInput.Capabilities?> = _capabilities.asStateFlow()

    private val _gpsSpeed = MutableStateFlow(prefs.getBoolean(KEY_GPS_SPEED, false))
    /** 에뮬레이터 시험 모드: 차량 속도 대신 위치 속도 (GPX 경로 재생용) */
    val gpsSpeed: StateFlow<Boolean> = _gpsSpeed.asStateFlow()

    /** 지금 속도 (대기 화면 도로 애니메이션용). */
    private val _speedKmh = MutableStateFlow(0f)
    val speedKmh: StateFlow<Float> = _speedKmh.asStateFlow()

    val config get() = engine.config

    private val listener = object : VehicleInput.Listener {
        override fun onFix(fix: Fix) {
            _speedKmh.value = fix.speedKmh.toFloat()
            engine.onFix(fix)
        }
        override fun onDriveState(state: DriveState, timeMs: Long) {
            Log.i(TAG, "주행 상태: $state")
            engine.onDriveState(state, timeMs)
            _driveState.value = state
            if (state == DriveState.PARKED) {
                persist(); refresh(); syncGeofences()
                Log.i(TAG, "기억한 지점: " + engine.spots().joinToString { "${it.name} ${it.status} 급정거${it.eventIds.size}번" }.ifEmpty { "없음" })
            } else dismissCaption()
            refreshCaps()
        }

        override fun onReroute(timeMs: Long) {
            Log.i(TAG, "길을 놓침 (시연)")
            engine.onReroute(timeMs)
        }
    }

    /** 내비 재탐색 → 길을 놓친 곳. 실제 기록 모드에서만. */
    private fun onRealReroute(source: String) {
        if (_mode.value != Mode.REAL) return
        Log.i(TAG, "길을 놓침 ($source)")
        engine.onReroute(System.currentTimeMillis())
    }

    /**
     * 에뮬레이터 시험용 신호. '에뮬레이터 경로 시험' 을 켠 동안에만 받는다.
     *   adb shell am broadcast -a com.thatspot.app.SIMULATE_REROUTE
     * GPX 경로를 재생하면서 갈림길을 지난 직후 보내면, 내비가 경로를 다시 찾은 것과 같다.
     */
    private val testReroute = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (_gpsSpeed.value) onRealReroute("시험 신호") else Log.i(TAG, "시험 신호는 '에뮬레이터 경로 시험' 을 켠 뒤에 보내 주세요")
        }
    }

    // ------------------------------------------------------------------ 시작/종료

    fun start() {
        androidSpeaker = AndroidSpeaker(context)
        androidEars = AndroidEars(context)
        kit?.geofencer?.onTransition = { id, entered -> main.post { engine.onGeofence(id, entered) } }
        kit?.navi?.onReroute = { onRealReroute("내비") }
        runCatching {
            val f = IntentFilter(ACTION_TEST_REROUTE)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(testReroute, f, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(testReroute, f)
        }.onFailure { Log.w(TAG, "시험 신호를 받을 수 없어요", it) }
        connectUxRestrictions()
        refresh()
        startReal()
    }

    /** 권한이 새로 허용되면 실제 입력을 다시 연결한다 (시연 중이면 그대로). */
    fun onPermissionsChanged() { if (_mode.value == Mode.REAL) startReal() }

    fun shutdown() {
        input?.stop()
        persist()
        androidEars.shutdown(); androidSpeaker.shutdown()
        runCatching { context.unregisterReceiver(testReroute) }
        kit?.release()
        car?.disconnect()
    }

    private fun startReal() {
        input?.stop()
        _mode.value = Mode.REAL
        val locations = listOfNotNull(kit?.location, AndroidLocationSource(context))
        input = AaosVehicleInput(context, kit?.extras ?: NoExtras, locations, _gpsSpeed.value).also { it.start(listener) }
        syncGeofences()
        refreshCaps()
        // 위치 신호가 들어오면 '읽는 중' 으로 바뀌도록 잠시 뒤 한 번 더
        main.postDelayed({ refreshCaps() }, 3_000)
    }

    fun setGpsSpeed(on: Boolean) {
        _gpsSpeed.value = on
        prefs.edit().putBoolean(KEY_GPS_SPEED, on).apply()
        if (_mode.value == Mode.REAL) startReal()
    }

    // ------------------------------------------------------------------ 시연

    /** 지난 10일 기록을 새로 만들고 오늘 아침부터 재생한다. 실제 기록은 건드리지 않는다. */
    fun startDemo() {
        input?.stop()
        androidEars.cancel(); kit?.ears?.cancel()
        _mode.value = Mode.DEMO
        _cards.value = emptyList()
        engine = newEngine(EngineState())
        DemoScenario.preload(engine, System.currentTimeMillis())
        refresh()
        _demoPaused.value = null
        input = DemoVehicleInput().apply {
            onNote = { _demoNote.value = it }
            onPaused = { _demoPaused.value = it }
            onFinished = { _demoNote.value = "시연이 끝났어요" }
            start(listener)
        }
        refreshCaps()
    }

    fun continueDemo() {
        _demoPaused.value = null
        (input as? DemoVehicleInput)?.resume()
    }

    fun stopDemo() {
        engine = newEngine(store.load())
        _cards.value = emptyList()
        _demoNote.value = null; _demoPaused.value = null; _alert.value = null
        refresh()
        startReal()
    }

    // ------------------------------------------------------------------ 주차 후 운전자 동작

    fun answerCause(eventId: Long, cause: Cause, memo: String? = null) {
        cancelListening()
        engine.setCause(eventId, cause, memo)
        dismissCard(); persist(); refresh(); syncGeofences()
    }

    fun notHardBrake(eventId: Long) {
        cancelListening()
        engine.dropEvent(eventId)
        dismissCard(); persist(); refresh(); syncGeofences()
    }

    /** 이유를 말로: 질문을 들려주고, 다 말하면 듣는다. 들은 말은 낱말로 분류하고 원문은 메모로 남긴다. */
    fun askCauseByVoice(card: Output.AskCause) {
        _heard.value = null
        val q = when (card.kind) {
            EventKind.SWERVE -> "아까 급히 핸들을 꺾으신 곳, 무엇 때문이었나요?"
            EventKind.MISSED_TURN -> "아까 길을 놓치신 곳, 무엇 때문이었나요?"
            EventKind.HARD_BRAKE -> "아까 급정거하신 곳, 무엇 때문이었나요?"
        }
        val ears: Ears = kit?.ears?.takeIf { it.ready } ?: androidEars
        _listening.value = true
        speak(q) {
            val started = ears.listen { text ->
                main.post {
                    _listening.value = false
                    _heard.value = text ?: "잘 못 들었어요. 아래에서 골라 주세요."
                    if (!text.isNullOrBlank()) answerCause(card.eventId, CauseMatcher.match(text, card.kind), text)
                }
            }
            if (!started) main.post { _listening.value = false; _heard.value = "이 기기에서는 음성 인식을 쓸 수 없어요. 아래에서 골라 주세요." }
        }
    }

    private fun cancelListening() {
        if (!_listening.value) return
        androidEars.cancel(); kit?.ears?.cancel()
        _listening.value = false
    }

    fun setSpotCause(spotId: Long, cause: Cause) {
        engine.setSpotCause(spotId, cause, engine.state.spots[spotId]?.memo)
        persist(); refresh(); syncGeofences()
    }

    /** 우연형 지점 — 운전자가 "계속 알리기"를 선택 */
    fun keepAlert(spotId: Long) {
        engine.keepAlert(spotId)
        dismissCard(); persist(); refresh(); syncGeofences()
    }

    /** 우연형 지점 — 운전자가 "알림 끄기"를 선택 */
    fun stopAlert(spotId: Long) {
        engine.stopAlert(spotId)
        dismissCard(); persist(); refresh(); syncGeofences()
    }

    fun deleteSpot(spotId: Long) {
        engine.deleteSpot(spotId)
        persist(); refresh(); syncGeofences()
    }

    fun dismissCard() {
        _cards.value = _cards.value.drop(1)
        _heard.value = null
    }

    fun spot(id: Long?): Spot? = id?.let { engine.state.spots[it] }
    fun allSpots(): Collection<Spot> = engine.state.spots.values

    // ------------------------------------------------------------------ 음성

    /** 홈 화면 '센서' 줄: 내비 재탐색 신호를 받을 수 있는지 */
    val naviStatus: String? get() = kit?.navi?.takeIf { it.ready }?.let { "읽는 중" }

    val speakerName: String get() = currentSpeaker()?.name ?: "음성 없음 (자막으로 보여 드려요)"

    private fun currentSpeaker(): Speaker? = kit?.speaker?.takeIf { it.ready } ?: androidSpeaker.takeIf { !it.failed }

    /** 말하고, 말한 내용은 주차 중이면 자막으로도 보여 준다. @return 소리로 나갔으면 true */
    fun speak(text: String, onDone: (() -> Unit)? = null): Boolean {
        val audible = currentSpeaker()?.speak(text) { main.post { onDone?.invoke() } } == true
        Log.i(TAG, "말하기 ${if (audible) "재생" else "실패(소리 없음)"} · $speakerName")
        if (!audible) main.postDelayed({ onDone?.invoke() }, 1_500)
        if (_driveState.value == DriveState.PARKED) {
            _caption.value = Caption(text, audible)
            main.removeCallbacks(clearCaption)
            main.postDelayed(clearCaption, 8_000)
        }
        return audible
    }

    fun dismissCaption() { main.removeCallbacks(clearCaption); _caption.value = null }

    // ------------------------------------------------------------------ 내부

    private fun newEngine(state: EngineState) = SpotEngine(state = state) { out ->
        when (out) {
            is Output.Speak -> {
                Log.i(TAG, "알림 음성: ${out.text}")
                speak(out.text)
                // 시연은 8배속이라 말하는 동안 장면을 붙잡는다
                (input as? DemoVehicleInput)?.hold(700L + out.text.length * 120L)
            }
            is Output.Alert -> {
                _alert.value = out
                main.removeCallbacks(clearAlert)
                main.postDelayed(clearAlert, 9_000)
            }
            else -> {
                Log.i(TAG, "주차 카드: ${out::class.simpleName}")
                _cards.value = _cards.value + out
            }
        }
    }

    private fun refresh() {
        _spots.value = engine.spots()
        _revision.value += 1
    }

    /** ACTIVE 지점에 Pleos 근접 알림을 건다. 등록이 다 되면 엔진은 근접 알림 안에서만 알린다. */
    private fun syncGeofences() {
        val g = kit?.geofencer
        if (g == null || _mode.value == Mode.DEMO) { engine.useExternalGeofence = false; return }
        val radius = engine.config.warnMaxM.toFloat() + 100f
        g.sync(engine.watchList().map { GeoTarget(it.id, it.lat, it.lon, radius) })
        main.postDelayed({ engine.useExternalGeofence = g.healthy && engine.watchList().isNotEmpty() }, 3_000)
    }

    private fun persist() {
        if (_mode.value == Mode.DEMO) return // 시연 기록은 저장하지 않는다
        runCatching { store.save(engine.state) }.onFailure { Log.w(TAG, "저장 실패", it) }
    }

    private fun refreshCaps() { _capabilities.value = input?.capabilities }

    private fun connectUxRestrictions() {
        runCatching {
            car = Car.createCar(context)
            val m = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
            _uxRestricted.value = m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
            m.registerListener { r -> _uxRestricted.value = r.isRequiresDistractionOptimization }
        }.onFailure { Log.w(TAG, "주행 제한 서비스 연결 실패", it) }
    }

    /** 홈 화면용 한 줄 요약. */
    fun spotStatus(s: Spot): String = Texts.statusLabel(s, engine.config)
    fun activeCount(): Int = engine.watchList().size

    companion object {
        private const val TAG = "AppController"
        private const val KEY_GPS_SPEED = "gpsSpeed"
        const val ACTION_TEST_REROUTE = "com.thatspot.app.SIMULATE_REROUTE"
    }
}
