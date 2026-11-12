package com.thatspot.app.vehicle

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.thatspot.core.DemoScenario
import com.thatspot.core.DemoStep

/**
 * 시연 모드: 기획서의 시나리오를 [speedUp] 배속으로 재생한다. 값 주입 없이 영상 촬영이 된다.
 * 주차 장면에서 멈추고 [resume] 으로 이어 간다. 장면 사이의 긴 공백(다음 날)은 1.5초로 줄인다.
 */
class DemoVehicleInput(private val speedUp: Int = 8) : VehicleInput {

    private val main = Handler(Looper.getMainLooper())
    private var listener: VehicleInput.Listener? = null
    private var steps: List<DemoStep> = emptyList()
    private var index = 0
    private var paused = false
    private var holdUntil = 0L

    var onNote: (String) -> Unit = {}
    var onPaused: (String) -> Unit = {}
    var onFinished: () -> Unit = {}

    override val capabilities = VehicleInput.Capabilities(
        speed = "시연 데이터", gear = true, brake = true, steering = true, turnSignal = true, location = "시연 경로",
    )

    private val runner = object : Runnable {
        override fun run() {
            val l = listener ?: return
            val wait = holdUntil - SystemClock.uptimeMillis()
            if (wait > 0) { main.postDelayed(this, wait); return }
            if (index >= steps.size) { onFinished(); return }
            val step = steps[index++]
            when (step) {
                is DemoStep.State -> l.onDriveState(step.state, step.timeMs)
                is DemoStep.At -> l.onFix(step.fix)
                is DemoStep.Reroute -> l.onReroute(step.timeMs)
                is DemoStep.Note -> onNote(step.text)
                is DemoStep.Pause -> { paused = true; onPaused(step.text); return }
            }
            val next = steps.getOrNull(index) ?: run { onFinished(); return }
            main.postDelayed(this, ((next.timeMs - step.timeMs) / speedUp).coerceIn(0, 1_500))
        }
    }

    override fun start(listener: VehicleInput.Listener) {
        this.listener = listener
        steps = DemoScenario.liveScript(System.currentTimeMillis())
        index = 0
        paused = false
        main.post(runner)
    }

    /** 앱이 말하는 동안 장면을 붙잡는다 (8배속이라 말이 끝나기 전에 지점을 지나가지 않도록). */
    fun hold(ms: Long) { holdUntil = SystemClock.uptimeMillis() + ms }

    fun resume() {
        if (!paused) return
        paused = false
        main.post(runner)
    }

    override fun stop() {
        main.removeCallbacks(runner)
        listener = null
    }
}
