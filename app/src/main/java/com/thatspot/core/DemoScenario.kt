package com.thatspot.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 시연 재생 단위. */
sealed class DemoStep(open val timeMs: Long) {
    data class State(override val timeMs: Long, val state: DriveState) : DemoStep(timeMs)
    data class At(val fix: Fix) : DemoStep(fix.timeMs)
    /** 화면 위쪽에 띄우는 장면 설명 (운전자에게 일어나는 일) */
    data class Note(override val timeMs: Long, val text: String) : DemoStep(timeMs)
    /** 내비가 경로를 다시 찾음 (길을 놓침) */
    data class Reroute(override val timeMs: Long) : DemoStep(timeMs)
    /** 주차 장면에서 멈춤 */
    data class Pause(override val timeMs: Long, val text: String) : DemoStep(timeMs)
}

/** 가상의 출근길 (서울 강남 부근 좌표로 만든 꺾인 선, 4.7km). 실제 도로가 아니다. */
object DemoRoute {
    val start = LatLon(37.4979, 127.0276)
    private val legs = listOf(70.0 to 900.0, 10.0 to 1_400.0, 95.0 to 1_300.0, 20.0 to 1_100.0)
    val points: List<LatLon>
    private val cum: List<Double>
    val lengthM: Double

    init {
        val pts = mutableListOf(start)
        legs.forEach { (b, m) -> pts += Geo.offset(pts.last(), b, m) }
        points = pts
        cum = pts.indices.map { i -> legs.take(i).sumOf { it.second } }
        lengthM = cum.last()
    }

    /** 출발점에서 s 미터 간 곳의 위치와 진행 방향. */
    fun at(s: Double): Pair<LatLon, Double> {
        val x = s.coerceIn(0.0, lengthM)
        val i = (cum.indexOfLast { it <= x }).coerceIn(0, legs.size - 1)
        val (bearing, _) = legs[i]
        return Geo.offset(points[i], bearing, x - cum[i]) to bearing
    }
}

/**
 * 거리별 속도 제한으로 한 번의 주행을 만든다.
 * 제한마다 '그 지점에서 목표 속도' 가 있고, 앞뒤로 감속·가속 한계가 있다. 실제 속도는 모든 제한의 최솟값.
 */
class DemoDrive(private val startMs: Long, private val stepMs: Long) {

    sealed class Zone(val atM: Double) {
        /** 신호 대기 */
        class Stop(atM: Double, val idleMs: Long) : Zone(atM)
        /** 급정거 (갑자기 무언가 나타남): 시속 10km 까지 0.47g */
        class Hard(atM: Double) : Zone(atM)
        /** 알림을 듣고 미리 줄임: 시속 28km 까지 0.09g */
        class Gentle(atM: Double) : Zone(atM)
        /** 갈림길을 놓침: 속도는 그대로, [REROUTE_AFTER_M] 를 더 간 뒤 내비가 경로를 다시 찾는다 */
        class Missed(atM: Double) : Zone(atM)
    }

    companion object {
        /** 엔진의 lostBackM 과 같다: 재탐색 지점에서 이만큼 되짚은 곳이 갈림길 */
        const val REROUTE_AFTER_M = 200.0
    }

    private val cruise = 45 / 3.6
    private val accel = 1.6
    private val steps = mutableListOf<DemoStep>()
    private var t = startMs
    private var state: DriveState? = null
    private val timeAt = sortedMapOf<Int, Long>()

    fun run(zones: List<Zone>): List<DemoStep> {
        val stops = zones.filterIsInstance<Zone.Stop>().sortedBy { it.atM }.toMutableList()
        val missed = zones.filterIsInstance<Zone.Missed>().map { it.atM + REROUTE_AFTER_M }.sorted().toMutableList()
        setState(DriveState.IDLING)
        var s = 0.5
        var v = 0.0
        while (s < DemoRoute.lengthM - 0.6) {
            val nextStop = stops.firstOrNull()
            if (nextStop != null && nextStop.atM - s < 0.6) {
                // 신호 대기: 멈춰서 기다렸다가 다시 출발
                s = nextStop.atM
                v = 0.0
                var waited = 0L
                while (waited < nextStop.idleMs) { push(s, 0.0, brake = true); waited += stepMs }
                stops.removeAt(0)
                s += 0.6
                continue
            }
            val nv = limit(s, zones)
            val brake = (v - nv) / (stepMs / 1000.0) > 0.4
            v = nv
            s += max(v, 0.3) * stepMs / 1000.0
            push(s, v, brake)
            if (missed.isNotEmpty() && s >= missed.first()) { missed.removeAt(0); steps += DemoStep.Reroute(t) }
        }
        push(DemoRoute.lengthM, 0.0, brake = true)
        repeat((2_000 / stepMs).toInt()) { push(DemoRoute.lengthM, 0.0, brake = true) }
        setState(DriveState.PARKED)
        return steps
    }

    /** 출발 후 s 미터에 닿은 시각 (장면 설명을 맞추는 데 쓴다). */
    fun timeAtM(m: Double): Long = timeAt.tailMap(m.toInt()).values.firstOrNull() ?: t

    private fun limit(s: Double, zones: List<Zone>): Double {
        // 출발 가속, 도착 전 감속 (목적지에서 천천히 선다)
        var v = min(cruise, sqrt(2 * accel * s))
        v = min(v, sqrt(2 * 1.5 * max(0.0, DemoRoute.lengthM - s)))
        for (z in zones) {
            if (z is Zone.Missed) continue
            val (vt, dec, hold) = when (z) {
                is Zone.Stop -> Triple(0.0, 1.5, 0.0)
                is Zone.Hard -> Triple(10 / 3.6, 4.6, 6.0)
                is Zone.Gentle -> Triple(28 / 3.6, 0.9, 40.0)
                is Zone.Missed -> continue
            }
            val lim = when {
                s < z.atM -> sqrt(vt * vt + 2 * dec * (z.atM - s))
                s <= z.atM + hold -> vt
                else -> sqrt(vt * vt + 2 * accel * (s - z.atM - hold))
            }
            v = min(v, lim)
        }
        return v
    }

    private fun push(s: Double, vMs: Double, brake: Boolean) {
        t += stepMs
        setState(if (vMs < 0.14) DriveState.IDLING else DriveState.MOVING)
        val (p, heading) = DemoRoute.at(s)
        timeAt.putIfAbsent(s.toInt(), t)
        steps += DemoStep.At(
            Fix(
                timeMs = t, speedKmh = vMs * 3.6, lat = p.lat, lon = p.lon, headingDeg = heading,
                brakePressed = brake, steeringDeg = 0.0, turnSignalOn = false,
            ),
        )
    }

    private fun setState(s: DriveState) {
        if (s != state) { state = s; steps += DemoStep.State(t, s) }
    }
}

/**
 * 기획서의 시연 시나리오.
 *
 * 지난 10일 아침 출근길 기록 (같은 길):
 *  - 2번 지점 (2.0km, 짧은 신호): 첫 이틀 급정거 → 알림 → 세 번 연속 부드럽게 → 익숙해짐
 *  - 1번 지점 (1.2km, 골목 출구): 세 번 급정거. 알림을 들은 뒤 한 번 또 급정거, 그 뒤 한 번 부드럽게
 *  - 3번 지점 (3.5km): 4일 전 한 번 급정거 (아직 지켜보는 중)
 *  - 갈림길 (2.95km): 5일 전, 1일 전에 놓쳐서 내비가 경로를 다시 찾음 → 알리는 곳 ("헷갈리는 갈림길")
 *
 * 시연:
 *  오늘   — 1.2km 앞에서 앱이 먼저 말한다 → 운전자가 미리 줄인다.
 *           2.95km 갈림길 300m 앞에서 "지난번에 길을 놓치신 곳" → 이번엔 놓치지 않는다.
 *           3.5km 에서 옆 차가 끼어들어 또 급정거 → 다른 날 두 번째라 위험 지점이 된다.
 *           주차 → "무엇 때문이었나요?" → 정리 카드
 *  다음 날 — 1.2km 알림 → 부드럽게 (세 번째) → 3.5km 에서 방금 고른 이유로 미리 알림 → 부드럽게
 *           주차 → "1번 지점은 이제 조용히 할게요"
 */
object DemoScenario {
    const val DAY = 24 * 60 * 60_000L
    const val SPOT_ALLEY = 1_200.0
    const val SPOT_SIGNAL = 2_000.0
    const val SPOT_CUTIN = 3_500.0
    /** 길을 놓치는 갈림길 */
    const val SPOT_FORK = 2_950.0
    private val lights = listOf(DemoDrive.Zone.Stop(700.0, 15_000), DemoDrive.Zone.Stop(2_700.0, 20_000))

    /** 지난 기록을 엔진에 조용히 채운다. */
    fun preload(engine: SpotEngine, nowMs: Long) {
        engine.silent = true
        for (i in 0 until 10) {
            val start = nowMs - (10 - i) * DAY
            val signal = when (i) { 0, 1 -> DemoDrive.Zone.Hard(SPOT_SIGNAL); in 2..4 -> DemoDrive.Zone.Gentle(SPOT_SIGNAL); else -> null }
            val alley = when (i) { 2, 5, 8 -> DemoDrive.Zone.Hard(SPOT_ALLEY); 6, 7, 9 -> DemoDrive.Zone.Gentle(SPOT_ALLEY); else -> null }
            val cutIn = if (i == 6) DemoDrive.Zone.Hard(SPOT_CUTIN) else null
            // 갈림길: 5일 전, 1일 전에 놓침 → 다른 날 두 번이라 알리는 곳
            val fork = if (i == 5 || i == 9) DemoDrive.Zone.Missed(SPOT_FORK) else null
            val zones = lights + listOfNotNull(signal, alley, cutIn, fork)
            for (step in DemoDrive(start, stepMs = 500).run(zones)) feed(engine, step)
            // 주차하고 고른 이유
            if (i == 1) causeAt(engine, SPOT_SIGNAL, Cause.SIGNAL)
            if (i == 2) causeAt(engine, SPOT_ALLEY, Cause.ALLEY)
            if (i == 5) causeAt(engine, SPOT_FORK, Cause.FORK, lost = true)
        }
        engine.silent = false
    }

    /** 시연 장면. 시간은 실제 시각 기준이고 재생기가 배속으로 줄인다. */
    fun liveScript(nowMs: Long): List<DemoStep> {
        val out = mutableListOf<DemoStep>()

        // 오늘
        val d1 = DemoDrive(nowMs + 1_000, stepMs = 200)
        val trip1 = d1.run(lights + DemoDrive.Zone.Gentle(SPOT_ALLEY) + DemoDrive.Zone.Hard(SPOT_CUTIN))
        out += DemoStep.Note(nowMs, "오늘 아침 출근길 (가상 경로)")
        out += trip1
        out += DemoStep.Note(d1.timeAtM(SPOT_ALLEY - 200), "앱이 먼저 말합니다. 운전자는 화면을 보지 않아요")
        out += DemoStep.Note(d1.timeAtM(SPOT_ALLEY + 60), "미리 속도를 줄여 부드럽게 지나갑니다")
        out += DemoStep.Note(d1.timeAtM(SPOT_FORK - 320), "어제 놓친 갈림길. 차선을 바꿀 수 있게 더 일찍 알려 줍니다")
        out += DemoStep.Note(d1.timeAtM(SPOT_FORK + 40), "이번에는 갈림길을 놓치지 않았어요")
        out += DemoStep.Note(d1.timeAtM(SPOT_CUTIN - 120), "옆 차가 갑자기 끼어듭니다")
        out += DemoStep.Note(d1.timeAtM(SPOT_CUTIN + 60), "4일 전에도 놀란 곳. 다른 날 두 번째예요")
        val end1 = trip1.last().timeMs
        out += DemoStep.Pause(end1 + 500, "주차했습니다. 화면의 질문에 답해 보세요.")

        // 다음 날 (실제로 하루 뒤 시각을 쓴다. 재생기는 긴 공백을 건너뛴다)
        val start2 = nowMs + DAY
        val d2 = DemoDrive(start2 + 1_000, stepMs = 200)
        val trip2 = d2.run(lights + DemoDrive.Zone.Gentle(SPOT_ALLEY) + DemoDrive.Zone.Gentle(SPOT_CUTIN))
        out += DemoStep.Note(start2, "다음 날 아침, 같은 길")
        out += trip2
        out += DemoStep.Note(d2.timeAtM(SPOT_CUTIN - 200), "어제 고른 이유로 미리 알려 줍니다")
        out += DemoStep.Note(d2.timeAtM(SPOT_CUTIN + 60), "부드럽게 지나갑니다")

        return out.sortedBy { it.timeMs }
    }

    private fun feed(engine: SpotEngine, step: DemoStep) {
        when (step) {
            is DemoStep.State -> engine.onDriveState(step.state, step.timeMs)
            is DemoStep.At -> engine.onFix(step.fix)
            is DemoStep.Reroute -> engine.onReroute(step.timeMs)
            else -> Unit
        }
    }

    private fun causeAt(engine: SpotEngine, alongM: Double, cause: Cause, lost: Boolean = false) {
        val p = DemoRoute.at(alongM).first
        engine.state.spots.values.filter { it.lost == lost }.minByOrNull { Geo.distanceM(p, it.pos) }?.let { engine.setSpotCause(it.id, cause) }
    }
}
