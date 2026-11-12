package com.thatspot.app.vehicle

import com.thatspot.core.DriveState
import com.thatspot.core.Fix

/** 차량 데이터가 앱으로 들어오는 단일 통로. 실차(AAOS + Pleos)와 시연이 같은 모양이다. */
interface VehicleInput {
    interface Listener {
        fun onFix(fix: Fix)
        fun onDriveState(state: DriveState, timeMs: Long)
        /** 내비가 경로를 다시 찾았다 (시연 재생용. 실차는 Pleos NaviHelper 에서 바로 받는다) */
        fun onReroute(timeMs: Long) {}
    }

    /** 이 입력이 실제로 주는 데이터. 홈 화면에 '읽는 중/없음' 으로 보인다. */
    data class Capabilities(
        val speed: String?,
        val gear: Boolean,
        val brake: Boolean,
        val steering: Boolean,
        val turnSignal: Boolean,
        /** 위치를 주는 곳 이름. 없으면 null */
        val location: String?,
    )

    val capabilities: Capabilities
    fun start(listener: Listener)
    fun stop()
}

/**
 * 표준 AAOS 로 읽을 수 없는 값. 기본은 [NoExtras], Pleos 빌드에서는 Vehicle SDK 로 채운다.
 * 미지원이면 null 이다 (급정거 감지는 속도만으로도 된다. 브레이크 값이 있으면 오탐이 줄어든다).
 */
interface VehicleExtras {
    val brakePressed: Boolean?
    val steeringDeg: Double?
    val turnSignalOn: Boolean?
    fun start()
    fun stop()
}

object NoExtras : VehicleExtras {
    override val brakePressed: Boolean? = null
    override val steeringDeg: Double? = null
    override val turnSignalOn: Boolean? = null
    override fun start() = Unit
    override fun stop() = Unit
}
