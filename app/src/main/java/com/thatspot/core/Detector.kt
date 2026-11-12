package com.thatspot.core

import kotlin.math.abs
import kotlin.math.max

/**
 * 모든 기준값. 근거:
 *  - 급정거 0.3g: 텔레매틱스·보험 업계가 흔히 쓰는 0.3~0.45g 구간의 아래쪽.
 *    NJIT 연구(Hossain 외, Vehicles 2026)는 0.2g 로 잡은 급정거 빈도가 구간 사고 건수와 유의하게 비례함을 보였다.
 *    여기서는 일상 제동과 헷갈리지 않도록 조금 더 높게 잡았다.
 *  - 지점 반경 50m, 방향 차이 50도: 교차로 하나 안에서 같은 진입 방향만 묶는다 (반대 차로는 다른 지점).
 *  - 알림 거리: 10초 앞, 최소 150m 최대 400m. 문장을 다 듣고도 속도를 줄일 거리.
 * 실차 기록으로 다시 맞춰야 한다.
 */
data class Config(
    val hardBrakeG: Double = 0.30,
    val minFromKmh: Double = 20.0,
    /** 이만큼은 줄어야 급정거로 센다. 1초 간격 위치로 속도를 구하면 꺾이는 길에서 속도가 잠깐 떨어져 보이기 때문 */
    val minDropKmh: Double = 15.0,
    val lookbackMs: Long = 1_000,
    val eventCooldownMs: Long = 6_000,
    val swerveDeg: Double = 45.0,
    val swerveWindowMs: Long = 600,
    val swerveMinKmh: Double = 40.0,

    val spotRadiusM: Double = 50.0,
    val headingTolDeg: Double = 50.0,
    val activeMinTrips: Int = 2,

    val trackRadiusM: Double = 500.0,
    val passRadiusM: Double = 40.0,
    val warnLeadSec: Double = 10.0,
    val warnMinM: Double = 150.0,
    val warnMaxM: Double = 400.0,
    val warnCooldownMs: Long = 10 * 60_000,
    val maxWarnsPerTrip: Int = 6,
    val quietAfterSmooth: Int = 3,

    /** 길을 놓친 곳: 재탐색 신호는 갈림길을 지난 뒤에 오므로 이만큼 되돌아간 곳을 지점으로 삼는다 */
    val lostBackM: Double = 200.0,
    /** 길을 놓친 곳 묶음 반경. 재탐색이 오는 때가 그때그때 달라 (갈림길 하나 크기) 넓게 잡는다 */
    val lostSpotRadiusM: Double = 100.0,
    /** 길을 놓친 곳은 차선을 바꿀 여유가 필요해 더 일찍 알린다: 20초 앞, 300~700m */
    val lostWarnLeadSec: Double = 20.0,
    val lostWarnMinM: Double = 300.0,
    val lostWarnMaxM: Double = 700.0,
    val lostTrackRadiusM: Double = 900.0,
    /** 내비가 재탐색을 여러 번 연달아 알릴 수 있어 한 번으로 묶는다 */
    val rerouteCooldownMs: Long = 30_000,

    /** 주차 화면 경로 스케치에 남기는 간격 */
    val trackStepM: Double = 25.0,
)

/** 감지기가 내보내는 날것의 사건. 엔진이 지점에 묶는다. */
data class Detected(
    val startFix: Fix,
    val endFix: Fix,
    val fromKmh: Double,
    val toKmh: Double,
    val peakG: Double,
    val kind: EventKind,
)

/**
 * 속도 변화로 급정거를, 핸들 각도 변화로 급회피를 찾는다.
 * 급정거는 시작 → 끝을 한 사건으로 묶어 한 번만 낸다 (감속이 0.08g 아래로 떨어지거나 거의 멈추면 끝).
 * 브레이크 신호가 있으면 "밟지 않은 감속" (언덕, 기어 변속) 은 제외한다. 없으면 속도만 본다.
 */
class HardEventDetector(private val config: Config = Config()) {

    private val buf = ArrayDeque<Fix>()
    private var inEvent: Detected? = null
    private var lastEventMs = Long.MIN_VALUE / 2

    fun reset() { buf.clear(); inEvent = null }

    /** @return 사건이 끝났으면 그 사건 */
    fun add(f: Fix): Detected? {
        buf.addLast(f)
        while (buf.size > 2 && f.timeMs - buf.first().timeMs > 4_000) buf.removeFirst()

        val g = decelG(f, config.lookbackMs) ?: return null
        val open = inEvent
        if (open == null) {
            if (f.timeMs - lastEventMs < config.eventCooldownMs) return null
            val start = fixAgo(f, config.lookbackMs) ?: return null
            val braking = f.brakePressed != false // 모르면 속도만 본다
            if (g >= config.hardBrakeG && braking && start.speedKmh >= config.minFromKmh) {
                // 감속이 시작된 지점까지 거슬러 올라간다 (그곳이 위험이 보인 곳)
                val origin = buf.lastOrNull { it.timeMs <= start.timeMs && decelG(it, 400)?.let { d -> d < 0.08 } != false } ?: start
                val from = buf.filter { it.timeMs >= origin.timeMs - 500 && it.timeMs <= f.timeMs }.maxOf { it.speedKmh }
                inEvent = Detected(origin, f, from, f.speedKmh, g, EventKind.HARD_BRAKE)
            } else {
                swerve(f)?.let { lastEventMs = f.timeMs; return it }
            }
            return null
        }
        // 진행 중인 급정거: 가장 센 감속과 가장 낮은 속도를 갱신하고, 끝나면 낸다
        val updated = open.copy(endFix = f, toKmh = minOf(open.toKmh, f.speedKmh), peakG = max(open.peakG, g))
        val shortG = decelG(f, 400) ?: g
        return if (shortG < 0.08 || f.speedKmh < 2.0) {
            inEvent = null
            if (updated.fromKmh - updated.toKmh < config.minDropKmh) return null
            lastEventMs = f.timeMs
            updated
        } else {
            inEvent = updated
            null
        }
    }

    /** 주행이 끝날 때 진행 중이던 급정거를 마저 낸다. */
    fun flush(): Detected? = inEvent?.also { inEvent = null }
        ?.takeIf { it.fromKmh - it.toKmh >= config.minDropKmh }?.also { lastEventMs = it.endFix.timeMs }

    private fun swerve(f: Fix): Detected? {
        val now = f.steeringDeg ?: return null
        if (f.turnSignalOn == true || f.speedKmh < config.swerveMinKmh) return null
        val past = buf.firstOrNull { it.timeMs >= f.timeMs - config.swerveWindowMs && it.steeringDeg != null } ?: return null
        if (past === f) return null
        val change = abs(now - past.steeringDeg!!)
        if (change < config.swerveDeg) return null
        return Detected(past, f, past.speedKmh, f.speedKmh, 0.0, EventKind.SWERVE)
    }

    private fun fixAgo(f: Fix, ms: Long): Fix? =
        buf.lastOrNull { it.timeMs <= f.timeMs - ms } ?: buf.firstOrNull()?.takeIf { it !== f && f.timeMs - it.timeMs >= ms / 2 }

    /** 최근 ms 동안의 평균 감속 (g). 표본이 모자라면 null. */
    private fun decelG(f: Fix, ms: Long): Double? {
        val p = fixAgo(f, ms) ?: return null
        val dt = (f.timeMs - p.timeMs) / 1000.0
        if (dt <= 0) return null
        return ((p.speedKmh - f.speedKmh) / 3.6) / dt / 9.81
    }
}
