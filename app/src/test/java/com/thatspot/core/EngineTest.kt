package com.thatspot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = DemoScenario.DAY
private val base = 1_800_000_000_000L

private fun drive(engine: SpotEngine, start: Long, zones: List<DemoDrive.Zone>) {
    for (s in DemoDrive(start, 500).run(zones)) when (s) {
        is DemoStep.State -> engine.onDriveState(s.state, s.timeMs)
        is DemoStep.At -> engine.onFix(s.fix)
        else -> Unit
    }
}

private fun collect(): Pair<MutableList<Output>, SpotEngine> {
    val out = mutableListOf<Output>()
    val e = SpotEngine { out += it }
    return out to e
}

private val fixedZone = java.time.ZoneId.of("Asia/Seoul").also { Texts.zone = it }

class DetectorTest {
    init { Texts.zone = fixedZone }
    private fun run(speeds: List<Double>, stepMs: Long = 200, brake: Boolean? = true): List<Detected> {
        val d = HardEventDetector()
        val found = mutableListOf<Detected>()
        speeds.forEachIndexed { i, v -> d.add(Fix(base + i * stepMs, v, brakePressed = brake))?.let { found += it } }
        d.flush()?.let { found += it }
        return found
    }

    @Test fun hardBrakeIsOneEvent() {
        // 45km/h 로 가다가 2초 동안 10km/h 까지 (0.5g)
        val speeds = List(20) { 45.0 } + List(10) { 45.0 - 3.5 * (it + 1) } + List(20) { 10.0 }
        val e = run(speeds)
        assertEquals(1, e.size)
        assertTrue(e[0].peakG > 0.4)
        assertEquals(45.0, e[0].fromKmh, 0.1)
        assertTrue(e[0].toKmh <= 11.0)
    }

    @Test fun normalStopIsNotHard() {
        // 신호 앞에서 0.15g 로 서기
        val speeds = List(20) { 45.0 } + List(42) { (45.0 - 1.07 * (it + 1)).coerceAtLeast(0.0) } + List(10) { 0.0 }
        assertTrue(run(speeds).isEmpty())
    }

    @Test fun decelWithoutBrakeIsIgnored() {
        val speeds = List(20) { 45.0 } + List(10) { 45.0 - 3.5 * (it + 1) } + List(20) { 10.0 }
        assertTrue(run(speeds, brake = false).isEmpty())
        // 브레이크 값을 모르면 속도만 본다
        assertEquals(1, run(speeds, brake = null).size)
    }

    @Test fun slowCrawlIsIgnored() {
        val speeds = List(10) { 15.0 } + List(5) { 15.0 - 3.0 * (it + 1) } + List(10) { 0.0 }
        assertTrue(run(speeds).isEmpty())
    }

    @Test fun worksWithOneHzGps() {
        val speeds = listOf(45.0, 45.0, 45.0, 45.0, 30.0, 14.0, 9.0, 9.0, 9.0)
        assertEquals(1, run(speeds, stepMs = 1_000, brake = null).size)
    }
}

class SpotRulesTest {
    init { Texts.zone = fixedZone }
    private val spotAt = 1_200.0

    @Test fun oneHardBrakeOnlyWatches() {
        val (out, e) = collect()
        drive(e, base, listOf(DemoDrive.Zone.Hard(spotAt)))
        assertEquals(1, e.state.spots.size)
        assertEquals(SpotStatus.WATCH, e.state.spots.values.first().status)
        // 다음 날 지나가도 알리지 않는다
        out.clear()
        drive(e, base + DAY, emptyList())
        assertTrue(out.none { it is Output.Speak })
    }

    @Test fun twoHardBrakesSameTripDoNotActivate() {
        // 같은 주행에서 두 번 (예: 정체) 은 '다른 날 두 번' 이 아니다
        val (_, e) = collect()
        drive(e, base, listOf(DemoDrive.Zone.Hard(spotAt), DemoDrive.Zone.Hard(spotAt + 20)))
        assertTrue(e.state.spots.values.all { it.status == SpotStatus.WATCH })
    }

    @Test fun secondDayActivatesAndWarnsOnceWithDistance() {
        val (out, e) = collect()
        drive(e, base, listOf(DemoDrive.Zone.Hard(spotAt)))
        drive(e, base + DAY, listOf(DemoDrive.Zone.Hard(spotAt)))
        val s = e.state.spots.values.single()
        assertEquals(SpotStatus.ACTIVE, s.status)
        assertTrue(out.any { it is Output.TripSummary && it.lines.any { l -> l.kind == SummaryLine.Kind.NEW } })

        out.clear()
        drive(e, base + 2 * DAY, emptyList())
        val speaks = out.filterIsInstance<Output.Speak>()
        assertEquals(1, speaks.size)
        assertTrue(speaks[0].text, speaks[0].text.startsWith("150미터 앞, 어제도 급정거하신 곳이에요. 모두 두 번."))
        assertEquals(1, out.filterIsInstance<Output.Alert>().size)
    }

    @Test fun oppositeDirectionIsDifferentSpotAndNoWarning() {
        val (out, e) = collect()
        drive(e, base, listOf(DemoDrive.Zone.Hard(spotAt)))
        drive(e, base + DAY, listOf(DemoDrive.Zone.Hard(spotAt)))
        // 같은 자리를 반대 방향으로 지나가는 주행을 직접 만든다
        out.clear()
        val s = e.state.spots.values.single()
        val h = s.headingDeg!!
        val back = (0..300).map { i -> Geo.offset(s.pos, h, 600.0 - i * 4.0) }
        e.onDriveState(DriveState.MOVING, base + 3 * DAY)
        back.forEachIndexed { i, p -> e.onFix(Fix(base + 3 * DAY + i * 300L, 48.0, p.lat, p.lon, headingDeg = (h + 180) % 360, brakePressed = false)) }
        e.onDriveState(DriveState.PARKED, base + 3 * DAY + 200_000)
        assertTrue(out.none { it is Output.Speak })
    }

    @Test fun quietAfterThreeSmoothWarnedPasses() {
        val (out, e) = collect()
        drive(e, base, listOf(DemoDrive.Zone.Hard(spotAt)))
        drive(e, base + DAY, listOf(DemoDrive.Zone.Hard(spotAt)))
        repeat(3) { drive(e, base + (2 + it) * DAY, listOf(DemoDrive.Zone.Gentle(spotAt))) }
        val s = e.state.spots.values.single()
        assertEquals(SpotStatus.QUIET, s.status)
        assertTrue(out.any { it is Output.TripSummary && it.lines.any { l -> l.kind == SummaryLine.Kind.QUIET } })
        // 그 뒤로는 말하지 않는다
        out.clear()
        drive(e, base + 6 * DAY, emptyList())
        assertTrue(out.none { it is Output.Speak })
        // 다시 급정거하면 돌아온다
        drive(e, base + 7 * DAY, listOf(DemoDrive.Zone.Hard(spotAt)))
        assertEquals(SpotStatus.ACTIVE, s.status)
    }

    @Test fun hardAfterWarningResetsStreak() {
        val (_, e) = collect()
        drive(e, base, listOf(DemoDrive.Zone.Hard(spotAt)))
        drive(e, base + DAY, listOf(DemoDrive.Zone.Hard(spotAt)))
        drive(e, base + 2 * DAY, listOf(DemoDrive.Zone.Gentle(spotAt)))
        drive(e, base + 3 * DAY, listOf(DemoDrive.Zone.Hard(spotAt)))
        val s = e.state.spots.values.single()
        assertEquals(0, s.smoothStreak)
        assertEquals(3, s.eventIds.size)
        // 둘째 날(지점이 생긴 뒤 첫 통과)과 넷째 날 모두 지나다 급정거
        assertEquals(2, s.hardPasses)
    }

    @Test fun cardsOnlyWhenParkedAndCauseAskedOncePerSpot() {
        val out = mutableListOf<Output>()
        var parked = true
        val e2 = SpotEngine { o -> if (o !is Output.Speak && o !is Output.Alert) assertTrue("카드는 주차 중에만", parked); out += o }
        for (s in DemoDrive(base, 500).run(listOf(DemoDrive.Zone.Hard(spotAt)))) when (s) {
            is DemoStep.State -> { parked = s.state == DriveState.PARKED; e2.onDriveState(s.state, s.timeMs) }
            is DemoStep.At -> e2.onFix(s.fix)
            else -> Unit
        }
        val ask = out.filterIsInstance<Output.AskCause>().single()
        assertTrue(ask.fromKmh >= 44)
        assertTrue(ask.alongM in 1_100..1_200)
        e2.setCause(ask.eventId, Cause.ALLEY, "오른쪽 골목에서 배달 오토바이")
        out.clear()
        parked = true
        for (s in DemoDrive(base + DAY, 500).run(listOf(DemoDrive.Zone.Hard(spotAt)))) when (s) {
            is DemoStep.State -> { parked = s.state == DriveState.PARKED; e2.onDriveState(s.state, s.timeMs) }
            is DemoStep.At -> e2.onFix(s.fix)
            else -> Unit
        }
        assertTrue("이유를 아는 지점은 다시 묻지 않는다", out.none { it is Output.AskCause })
    }

    @Test fun memoIsSpokenBack() {
        val (out, e) = collect()
        drive(e, base, listOf(DemoDrive.Zone.Hard(spotAt)))
        val ask = out.filterIsInstance<Output.AskCause>().single()
        e.setCause(ask.eventId, Cause.ALLEY, "오른쪽 골목에서 배달 오토바이")
        drive(e, base + DAY, listOf(DemoDrive.Zone.Hard(spotAt)))
        out.clear()
        drive(e, base + 2 * DAY, emptyList())
        val text = out.filterIsInstance<Output.Speak>().single().text
        assertTrue(text, text.contains("남기신 말: 오른쪽 골목에서 배달 오토바이."))
    }

    @Test fun externalGeofenceGatesWarnings() {
        val (out, e) = collect()
        drive(e, base, listOf(DemoDrive.Zone.Hard(spotAt)))
        drive(e, base + DAY, listOf(DemoDrive.Zone.Hard(spotAt)))
        e.useExternalGeofence = true
        out.clear()
        drive(e, base + 2 * DAY, emptyList())
        assertTrue("지오펜스 밖이면 알리지 않는다", out.none { it is Output.Speak })
        e.onGeofence(e.watchList().single().id, entered = true)
        drive(e, base + 3 * DAY, emptyList())
        assertEquals(1, out.filterIsInstance<Output.Speak>().size)
    }

    @Test fun dropEventRemovesSpot() {
        val (out, e) = collect()
        drive(e, base, listOf(DemoDrive.Zone.Hard(spotAt)))
        val ask = out.filterIsInstance<Output.AskCause>().single()
        e.dropEvent(ask.eventId)
        assertTrue(e.state.spots.isEmpty())
    }
}

class DemoScenarioTest {
    init { Texts.zone = fixedZone }
    @Test fun fullDemo() {
        val now = base
        val out = mutableListOf<Pair<DriveState, Output>>()
        lateinit var e: SpotEngine
        e = SpotEngine { o -> out += e.drivingState to o }
        DemoScenario.preload(e, now)

        val spots = e.state.spots.values.sortedBy { it.number }
        fun spotNear(m: Double, lost: Boolean = false) = spots.filter { it.lost == lost }.minBy { Geo.distanceM(DemoRoute.at(m).first, it.pos) }
        val alley = spotNear(DemoScenario.SPOT_ALLEY)
        val signal = spotNear(DemoScenario.SPOT_SIGNAL)
        val cutIn = spotNear(DemoScenario.SPOT_CUTIN)
        val fork = spotNear(DemoScenario.SPOT_FORK, lost = true)
        assertEquals(4, spots.size)
        assertEquals(SpotStatus.ACTIVE, fork.status)
        assertEquals(Cause.FORK, fork.cause)
        assertTrue(Geo.distanceM(DemoRoute.at(DemoScenario.SPOT_FORK).first, fork.pos) < 30)
        assertEquals(SpotStatus.ACTIVE, alley.status)
        assertEquals(1, alley.smoothStreak)
        assertEquals(SpotStatus.QUIET, signal.status)
        assertEquals(SpotStatus.WATCH, cutIn.status)
        assertTrue(out.isEmpty())

        val script = DemoScenario.liveScript(now)
        val pause = script.indexOfFirst { it is DemoStep.Pause }
        fun play(steps: List<DemoStep>) = steps.forEach { s ->
            when (s) {
                is DemoStep.State -> e.onDriveState(s.state, s.timeMs)
                is DemoStep.At -> e.onFix(s.fix)
                is DemoStep.Reroute -> e.onReroute(s.timeMs)
                else -> Unit
            }
        }

        // 오늘
        play(script.subList(0, pause))
        val day1Speaks = out.filter { it.second is Output.Speak }
        assertEquals(2, day1Speaks.size)
        assertTrue(day1Speaks.all { it.first != DriveState.PARKED })
        val t1 = (day1Speaks[0].second as Output.Speak).text
        assertTrue(t1, t1.startsWith("150미터 앞, 2일 전에도 급정거하신 곳이에요. 모두 세 번. 골목에서 차가 나올 수 있어요"))
        val t2 = (day1Speaks[1].second as Output.Speak).text
        assertTrue(t2, t2.startsWith("300미터 앞, 어제도 길을 놓치신 곳이에요. 모두 두 번. 갈림길이 헷갈리는 곳이에요"))
        assertEquals(1, fork.smoothStreak)
        assertTrue(out.filter { it.second !is Output.Speak && it.second !is Output.Alert }.all { it.first == DriveState.PARKED })
        val ask = out.map { it.second }.filterIsInstance<Output.AskCause>().single()
        assertEquals(cutIn.id, ask.spotId)
        assertEquals(2, ask.spotEvents)
        assertEquals(SpotStatus.ACTIVE, cutIn.status)
        val sum1 = out.map { it.second }.filterIsInstance<Output.TripSummary>().single()
        assertTrue(sum1.lines.any { it.kind == SummaryLine.Kind.GOOD && it.spotId == alley.id })
        assertTrue(sum1.lines.any { it.kind == SummaryLine.Kind.NEW && it.spotId == cutIn.id })
        e.setCause(ask.eventId, Cause.CUT_IN)

        // 다음 날
        out.clear()
        play(script.subList(pause + 1, script.size))
        val day2 = out.map { it.second }.filterIsInstance<Output.Speak>().map { it.text }
        assertEquals(3, day2.size)
        assertTrue(day2[0], day2[0].contains("모두 세 번"))
        assertTrue(day2[1], day2[1].contains("길을 놓치신 곳"))
        assertTrue(day2[2], day2[2].startsWith("150미터 앞, 어제도 급정거하신 곳이에요. 모두 두 번. 옆 차가 끼어들 수 있어요"))
        assertEquals(SpotStatus.QUIET, alley.status)
        val sum2 = out.map { it.second }.filterIsInstance<Output.TripSummary>().single()
        assertTrue(sum2.lines.any { it.kind == SummaryLine.Kind.QUIET && it.spotId == alley.id })
        assertTrue(sum2.lines.any { it.kind == SummaryLine.Kind.GOOD && it.spotId == cutIn.id })
        assertTrue(out.none { it.second is Output.AskCause })
    }

    @Test fun routeIsContinuous() {
        var prev = DemoRoute.at(0.0).first
        var m = 10.0
        while (m <= DemoRoute.lengthM) {
            val p = DemoRoute.at(m).first
            assertEquals(10.0, Geo.distanceM(prev, p), 0.5)
            prev = p; m += 10.0
        }
    }
}

class CauseMatcherTest {
    @Test fun matchesSpokenReasons() {
        assertEquals(Cause.ALLEY, CauseMatcher.match("오른쪽 골목에서 차가 튀어나왔어"))
        assertEquals(Cause.ALLEY, CauseMatcher.match("골목에서 자전거가 나왔어"))
        assertEquals(Cause.PEDESTRIAN, CauseMatcher.match("배달 오토바이가 갑자기"))
        assertEquals(Cause.CUT_IN, CauseMatcher.match("버스가 깜빡이도 없이 끼어들었어"))
        assertEquals(Cause.LEAD_STOP, CauseMatcher.match("앞차가 갑자기 섰어"))
        assertEquals(Cause.SIGNAL, CauseMatcher.match("노란불이 너무 짧아"))
        assertEquals(Cause.BLIND, CauseMatcher.match("커브라서 앞이 안 보여"))
        assertEquals(Cause.OTHER, CauseMatcher.match("몰라"))
        assertEquals(Cause.OTHER, CauseMatcher.match(null))
    }
}

class SmallDipTest {
    @Test fun smallSharpDipIsNotHardBrake() {
        // 1초 간격 위치 속도가 꺾이는 길에서 44 → 34 로 잠깐 떨어져 보이는 경우
        val d = HardEventDetector()
        val speeds = listOf(44.0, 44.0, 44.0, 44.0, 34.0, 40.0, 44.0, 44.0)
        val found = speeds.mapIndexedNotNull { i, v -> d.add(Fix(base + i * 1_000L, v)) } + listOfNotNull(d.flush())
        assertTrue(found.isEmpty())
    }
}

class SwerveTest {
    init { Texts.zone = fixedZone }

    /** 북쪽으로 50km/h 직진하다 [atM] 에서 0.4초 동안 핸들을 60도 꺾고, 1.5km 를 달린 뒤 주차. */
    private fun swerveTrip(e: SpotEngine, start: Long, atM: Double?) {
        e.onDriveState(DriveState.MOVING, start)
        val step = 200L; val v = 50.0; val mps = v / 3.6
        var t = start; var m = 0.0
        while (m < 1500) {
            val p = Geo.offset(LatLon(37.5, 127.0), 0.0, m)
            val steer = if (atM != null && m >= atM && m < atM + mps * 0.4) 60.0 else 0.0
            e.onFix(Fix(t, v, p.lat, p.lon, 0.0, brakePressed = false, steeringDeg = steer, turnSignalOn = false))
            t += step; m += mps * step / 1000.0
        }
        e.onDriveState(DriveState.PARKED, t)
    }

    @Test fun swerveAsksWithSteeringWordsAndChoices() {
        val (out, e) = collect()
        swerveTrip(e, base, 600.0)
        val ask = out.filterIsInstance<Output.AskCause>().single()
        assertEquals(EventKind.SWERVE, ask.kind)
        assertTrue(Texts.causeQuestion(ask), Texts.causeQuestion(ask).contains("핸들"))
        assertTrue(Texts.eventLine(ask).contains("핸들"))
        assertTrue(Cause.OBSTACLE in Cause.choicesFor(ask.kind))
        assertTrue(Cause.SIGNAL !in Cause.choicesFor(ask.kind))
    }

    @Test fun swerveSpotWarnsWithSteeringWords() {
        val (out, e) = collect()
        swerveTrip(e, base, 600.0)
        swerveTrip(e, base + DAY, 600.0)
        out.clear()
        swerveTrip(e, base + 2 * DAY, null)
        val text = out.filterIsInstance<Output.Speak>().single().text
        assertTrue(text, text.contains("급히 핸들을 꺾으신 곳"))
    }

    @Test fun obstacleWordsMatch() {
        assertEquals(Cause.OBSTACLE, CauseMatcher.match("도로에 박스가 떨어져 있었어"))
    }
}

class MissedTurnTest {
    init { Texts.zone = fixedZone }

    /** 북쪽으로 50km/h. [rerouteAtM] 에서 내비가 경로를 다시 찾는다 (null 이면 맞게 감). */
    private fun trip(e: SpotEngine, start: Long, rerouteAtM: Double?) {
        e.onDriveState(DriveState.MOVING, start)
        val step = 500L; val v = 50.0; val mps = v / 3.6
        var t = start; var m = 0.0; var fired = false
        while (m < 2500) {
            val p = Geo.offset(LatLon(37.5, 127.0), 0.0, m)
            e.onFix(Fix(t, v, p.lat, p.lon, 0.0))
            if (rerouteAtM != null && !fired && m >= rerouteAtM) { e.onReroute(t); e.onReroute(t + 1_000); fired = true }
            t += step; m += mps * step / 1000.0
        }
        e.onDriveState(DriveState.PARKED, t)
    }

    @Test fun rerouteMakesLostSpotBehindIt() {
        val (out, e) = collect()
        trip(e, base, 1500.0)
        val s = e.state.spots.values.single()
        assertTrue(s.lost)
        // 재탐색 지점에서 200m 되짚은 곳 (중복 알림은 하나로)
        assertEquals(1300.0, Geo.distanceM(LatLon(37.5, 127.0), s.pos), 25.0)
        val ask = out.filterIsInstance<Output.AskCause>().single()
        assertEquals(EventKind.MISSED_TURN, ask.kind)
        assertTrue(Texts.causeQuestion(ask).contains("길을 놓치셨어요"))
        val report = out.filterIsInstance<Output.TripReport>().single()
        assertEquals(1, report.missedCount); assertEquals(0, report.hardCount)
    }

    @Test fun lostSpotWarnsEarlierAndQuietsAfterThreeGoodPasses() {
        val (out, e) = collect()
        trip(e, base, 1500.0)
        trip(e, base + DAY, 1500.0)
        assertEquals(SpotStatus.ACTIVE, e.state.spots.values.single().status)
        out.clear()
        trip(e, base + 2 * DAY, null)
        val alert = out.filterIsInstance<Output.Alert>().single()
        assertTrue("${alert.distanceM}", alert.distanceM >= 300)
        val text = out.filterIsInstance<Output.Speak>().single().text
        assertTrue(text, text.contains("길을 놓치신 곳") && text.contains("미리 차선을 확인하세요"))
        trip(e, base + 3 * DAY, null)
        trip(e, base + 4 * DAY, null)
        assertEquals(SpotStatus.QUIET, e.state.spots.values.single().status)
    }

    @Test fun lostAndBrakeSpotsDoNotMerge() {
        val (_, e) = collect()
        trip(e, base, 1500.0)
        // 같은 자리 (1300m) 에서 급정거
        e.onDriveState(DriveState.MOVING, base + DAY)
        var t = base + DAY; var m = 0.0; var v = 50.0
        while (m < 1600) {
            val p = Geo.offset(LatLon(37.5, 127.0), 0.0, m)
            v = if (m in 1300.0..1400.0) maxOf(10.0, v - 3.5) else minOf(50.0, v + 1.0)
            e.onFix(Fix(t, v, p.lat, p.lon, 0.0)); t += 200; m += v / 3.6 * 0.2
        }
        e.onDriveState(DriveState.PARKED, t)
        assertEquals(2, e.state.spots.size)
        assertEquals(1, e.state.spots.values.count { it.lost })
    }

    @Test fun laneWordsMatchLostCausesOnlyForMissedTurn() {
        assertEquals(Cause.LATE_LANE, CauseMatcher.match("차선을 못 바꿔서 지나쳤어", EventKind.MISSED_TURN))
        assertEquals(Cause.FORK, CauseMatcher.match("출구가 헷갈렸어", EventKind.MISSED_TURN))
        assertEquals(Cause.CUT_IN, CauseMatcher.match("차선 바꾸던 차가 끼어들었어"))
    }
}
