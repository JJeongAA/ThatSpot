package com.thatspot.core

/** 저장되는 전부. 기기 안에만 둔다. */
class EngineState {
    val events = mutableListOf<HardEvent>()
    val spots = linkedMapOf<Long, Spot>()
    val passes = mutableListOf<Pass>()
    var tripCounter = 0
    var nextId = 1L
    var nextSpotNumber = 1
    /** 이유를 이미 물어본 사건 (같은 것을 두 번 묻지 않는다) */
    val askedEventIds = mutableSetOf<Long>()
    /** "계속 알릴까요?"를 이미 물어본 지점 (같은 것을 두 번 묻지 않는다) */
    val askedKeepAlertIds = mutableSetOf<Long>()
}

/**
 * That Spot 엔진. Android 에 의존하지 않는다 (단위 테스트로 전부 검증).
 *
 * 흐름
 *  1. 주행 중 [onFix] 로 들어오는 속도·위치에서 급정거를 찾는다 ([HardEventDetector]).
 *  2. 같은 자리·같은 방향의 급정거를 한 지점으로 묶는다. 서로 다른 주행에서 2번이면 ACTIVE.
 *  3. ACTIVE 지점에 다가가면 (방향까지 맞을 때만) 거리에 맞춰 한 번 말한다.
 *  4. 지나간 뒤 그 자리에서 다시 급정거했는지 본다. 알림 뒤 연속 3번 부드러우면 QUIET (더 말하지 않음).
 *  5. 주차하면 이번 주행의 새 급정거를 묻고, 정리 카드를 낸다.
 *
 * 채널 규칙: 주행·정차 중에는 Speak/Alert 만 낸다. 카드(AskCause, TripSummary)는 PARKED 에서만.
 */
class SpotEngine(
    val config: Config = Config(),
    val state: EngineState = EngineState(),
    private val sink: (Output) -> Unit,
) {
    /** true 면 아무것도 내보내지 않는다 (시연용 과거 기록을 채울 때) */
    var silent = false

    /**
     * 위치 서비스가 근접 알림(지오펜스)을 주면 true 로 두고 [onGeofence] 를 부른다.
     * 그러면 알림은 '지오펜스 안에 들어온 지점' 에서만 낸다. 거리 계산은 그대로 엔진이 한다.
     */
    var useExternalGeofence = false

    private val detector = HardEventDetector(config)
    private var drive = DriveState.PARKED
    private var trip: Trip? = null
    private val approaches = mutableMapOf<Long, Approach>()
    private val insideFence = mutableSetOf<Long>()
    private var lastPos: Fix? = null
    /** 최근 위치 (길을 놓친 곳을 되짚는 데 쓴다) */
    private val recent = ArrayDeque<Fix>()
    private var lastRerouteMs = Long.MIN_VALUE / 2

    private class Trip(val id: Int, val startMs: Long) {
        val newEvents = mutableListOf<Long>()
        val passes = mutableListOf<Pass>()
        val activated = linkedSetOf<Long>()
        val quieted = linkedSetOf<Long>()
        val reactivated = linkedSetOf<Long>()
        val track = mutableListOf<LatLon>()
        var alongM = 0.0
        var warnings = 0
    }

    private class Approach {
        var minD = Double.MAX_VALUE
        var warned = false
        var hard = false
        var reachedPass = false
    }

    /** 주차할 때까지 쌓아 두는 카드. */
    private val parkedQueue = mutableListOf<Output>()

    val drivingState get() = drive
    val inTrip get() = trip != null

    // ------------------------------------------------------------------ 입력

    fun onDriveState(s: DriveState, timeMs: Long) {
        val before = drive
        drive = s
        if (s != DriveState.PARKED && trip == null) startTrip(timeMs)
        if (s == DriveState.PARKED && before != DriveState.PARKED) endTrip(timeMs)
        if (s == DriveState.PARKED) flushParked()
    }

    fun onFix(f: Fix) {
        if (trip == null) {
            if (f.speedKmh < 0.5) return
            startTrip(f.timeMs)
        }
        val t = trip!!
        val fix = withHeading(f)
        if (fix.hasPosition) {
            lastPos?.takeIf { it.hasPosition }?.let { p ->
                t.alongM += Geo.distanceM(p.lat!!, p.lon!!, fix.lat!!, fix.lon!!)
            }
            val last = t.track.lastOrNull()
            if (last == null || Geo.distanceM(last.lat, last.lon, fix.lat!!, fix.lon!!) >= config.trackStepM) {
                t.track += LatLon(fix.lat!!, fix.lon!!)
            }
            lastPos = fix
            recent.addLast(fix)
            while (recent.size > 2 && fix.timeMs - recent.first().timeMs > 120_000) recent.removeFirst()
        }
        detector.add(fix)?.let { record(it, t) }
        if (fix.hasPosition) trackApproaches(fix, t)
    }

    /**
     * 내비가 경로를 다시 찾았다 (Pleos NaviHelper onReRouteEnded). 갈림길이나 출구를 놓쳤다는 뜻이다.
     * 재탐색은 갈림길을 지난 뒤에 오므로, 지나온 길을 [Config.lostBackM] 만큼 되짚은 곳을 지점으로 삼는다.
     * 그곳은 맞게 간 날에도 지나는 길목이라 '부드럽게 지나감' 을 셀 수 있다.
     */
    fun onReroute(timeMs: Long) {
        val t = trip ?: return
        if (timeMs - lastRerouteMs < config.rerouteCooldownMs) return
        val now = recent.lastOrNull()?.takeIf { timeMs - it.timeMs < 5_000 } ?: return
        lastRerouteMs = timeMs
        var back = 0.0
        var origin = now
        for (i in recent.size - 1 downTo 1) {
            val a = recent[i]; val b = recent[i - 1]
            back += Geo.distanceM(a.lat!!, a.lon!!, b.lat!!, b.lon!!)
            origin = b
            if (back >= config.lostBackM) break
        }
        val heading = origin.headingDeg ?: now.headingDeg
        record(Detected(origin.copy(headingDeg = heading), now, now.speedKmh, now.speedKmh, 0.0, EventKind.MISSED_TURN), t)
    }

    /** 위치 서비스의 근접 알림 (Pleos FusedLocation ProximityAlert). */
    fun onGeofence(spotId: Long, entered: Boolean) {
        if (entered) insideFence += spotId else insideFence -= spotId
    }

    // ------------------------------------------------------------------ 주차 후 운전자 동작

    /** 왜 놀랐는지. 그 사건이 묶인 지점에 남는다. [memo] 는 말로 남긴 원문. */
    fun setCause(eventId: Long, cause: Cause, memo: String? = null) {
        val e = state.events.firstOrNull { it.id == eventId } ?: return
        val s = e.spotId?.let { state.spots[it] } ?: return
        s.cause = cause
        if (!memo.isNullOrBlank()) s.memo = memo.trim()
        // 우연형 원인이 등록되면 ACCIDENTAL 로 전환 (ACTIVE 일 때만)
        if (cause.isAccidental && s.status == SpotStatus.ACTIVE) {
            s.status = SpotStatus.ACCIDENTAL
        }
    }

    fun setSpotCause(spotId: Long, cause: Cause, memo: String? = null) {
        val s = state.spots[spotId] ?: return
        s.cause = cause
        s.memo = memo?.trim()?.ifBlank { null }
        if (cause.isAccidental && s.status == SpotStatus.ACTIVE) {
            s.status = SpotStatus.ACCIDENTAL
        } else if (!cause.isAccidental && s.status == SpotStatus.ACCIDENTAL) {
            s.status = SpotStatus.ACTIVE
        }
    }

    /** 우연형 지점에 대해 운전자가 "계속 알리기" 를 고른 경우. */
    fun keepAlert(spotId: Long) {
        val s = state.spots[spotId] ?: return
        if (s.status == SpotStatus.ACCIDENTAL) s.status = SpotStatus.ACTIVE
    }

    /** 우연형 지점에 대해 운전자가 "알림 끄기" 를 고른 경우. */
    fun stopAlert(spotId: Long) {
        val s = state.spots[spotId] ?: return
        if (s.status == SpotStatus.ACCIDENTAL) { s.status = SpotStatus.QUIET; s.smoothStreak = 0 }
    }

    /** "급정거가 아니었어요" (예: 시험 삼아 세게 밟음). 사건을 지우고 지점을 다시 계산한다. */
    fun dropEvent(eventId: Long) {
        val e = state.events.firstOrNull { it.id == eventId } ?: return
        state.events.remove(e)
        val s = e.spotId?.let { state.spots[it] } ?: return
        s.eventIds.remove(e.id)
        s.tripIds.clear(); s.tripIds += state.events.filter { it.spotId == s.id }.map { it.tripId }
        if (s.eventIds.isEmpty()) { state.spots.remove(s.id); return }
        if (s.status == SpotStatus.ACTIVE && s.tripIds.size < config.activeMinTrips) s.status = SpotStatus.WATCH
    }

    fun deleteSpot(spotId: Long) {
        state.spots.remove(spotId)
        state.events.removeAll { it.spotId == spotId }
        state.passes.removeAll { it.spotId == spotId }
        approaches.remove(spotId)
    }

    /** 알림 대상 지점 (지오펜스 등록용). */
    fun watchList(): List<Spot> = state.spots.values.filter { it.status == SpotStatus.ACTIVE || it.status == SpotStatus.ACCIDENTAL }

    /** 홈 화면 순서: 알리는 중 → 우연형 → 지켜보는 중 → 익숙해짐, 각각 최근 것부터. */
    fun spots(): List<Spot> = state.spots.values.sortedWith(
        compareBy<Spot>({ when (it.status) { SpotStatus.ACTIVE -> 0; SpotStatus.ACCIDENTAL -> 1; SpotStatus.WATCH -> 2; SpotStatus.QUIET -> 3 } }, { -it.lastEventMs }),
    )

    fun event(id: Long) = state.events.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ 주행 시작/끝

    private fun startTrip(timeMs: Long) {
        state.tripCounter += 1
        trip = Trip(state.tripCounter, timeMs)
        approaches.clear()
        detector.reset()
        lastPos = null
        recent.clear()
    }

    private fun endTrip(timeMs: Long) {
        val t = trip ?: return
        detector.flush()?.let { record(it, t) }
        // 아직 지나는 중이던 지점은 지난 것으로 본다
        approaches.keys.toList().forEach { id -> approaches[id]?.takeIf { it.reachedPass }?.let { finishPass(id, it, timeMs, t) } }
        approaches.clear()
        trip = null

        // 새 급정거: 지점당 하나만, 이유를 아직 모르는 것만 묻는다
        val asked = mutableSetOf<Long?>()
        for (id in t.newEvents) {
            val e = state.events.firstOrNull { it.id == id } ?: continue
            val spot = e.spotId?.let { state.spots[it] }
            if (spot?.cause != null || e.spotId in asked || id in state.askedEventIds) continue
            asked += e.spotId
            state.askedEventIds += id
            parkedQueue += Output.AskCause(
                eventId = e.id, spotId = e.spotId, timeMs = e.timeMs,
                fromKmh = e.fromKmh.toInt(), toKmh = e.toKmh.toInt(), alongM = e.alongM.toInt(),
                spotEvents = spot?.eventIds?.size ?: 1,
                track = t.track.toList(), eventPos = if (e.lat != null && e.lon != null) LatLon(e.lat, e.lon) else null,
                kind = e.kind,
            )
        }
        summary(t)?.let { parkedQueue += it }
        // 우연형 원인이 이미 등록된 ACCIDENTAL 지점: "계속 알릴까요?" 카드
        for (s in state.spots.values) {
            if (s.status == SpotStatus.ACCIDENTAL && s.cause != null && s.id !in state.askedKeepAlertIds) {
                state.askedKeepAlertIds += s.id
                parkedQueue += Output.AskKeepAlert(s.id, s.name, s.cause!!, t.startMs)
            }
        }
        // 주행 리포트 (급정거가 있거나 경고 받은 지점이 있을 때만)
        val warnedSmooth = t.passes.count { it.warned && it.outcome == PassOutcome.SMOOTH }
        val newSpots = t.activated.size + t.newEvents.mapNotNull { id ->
            state.events.firstOrNull { it.id == id }?.spotId?.let { state.spots[it] }
        }.count { it.status == SpotStatus.WATCH }
        val newKinds = t.newEvents.mapNotNull { id -> state.events.firstOrNull { it.id == id }?.kind }
        if (t.newEvents.isNotEmpty() || warnedSmooth > 0) {
            parkedQueue += Output.TripReport(
                hardCount = newKinds.count { it != EventKind.MISSED_TURN },
                missedCount = newKinds.count { it == EventKind.MISSED_TURN },
                warnedSmooth = warnedSmooth,
                newSpots = newSpots,
                tripDistanceM = t.alongM,
            )
        }
    }

    private fun summary(t: Trip): Output.TripSummary? {
        val lines = mutableListOf<SummaryLine>()
        val warnedPasses = t.passes.filter { it.warned }
        for (p in warnedPasses) {
            val s = state.spots[p.spotId] ?: continue
            if (p.outcome == PassOutcome.SMOOTH) {
                if (s.id !in t.quieted) lines += SummaryLine(s.id, SummaryLine.Kind.GOOD,
                    "${s.name}: 미리 알려 드렸고 부드럽게 지나셨어요 (연속 ${s.smoothStreak}번)")
            } else {
                lines += SummaryLine(s.id, SummaryLine.Kind.AGAIN,
                    if (s.lost) "${s.name}: 알려 드렸는데도 또 길을 놓치셨어요. 다음엔 더 일찍 알려 드릴게요"
                    else "${s.name}: 알려 드렸는데도 또 급정거하셨어요. 더 일찍 알려 드릴게요")
            }
        }
        for (id in t.quieted) state.spots[id]?.let {
            lines += SummaryLine(id, SummaryLine.Kind.QUIET,
                "${it.name}: ${config.quietAfterSmooth}번 연속 부드럽게 지나셨어요. 이제 여기선 조용히 할게요")
        }
        for (id in t.activated) state.spots[id]?.let {
            lines += SummaryLine(id, SummaryLine.Kind.NEW,
                if (it.lost) "${it.name}: 다른 날에 또 길을 놓친 곳이에요. 다음부터 미리 알려 드려요"
                else "${it.name}: 다른 날에 또 급정거해서 위험 지점이 됐어요. 다음부터 미리 알려 드려요")
        }
        for (id in t.reactivated) state.spots[id]?.let {
            lines += SummaryLine(id, SummaryLine.Kind.AGAIN,
                if (it.lost) "${it.name}: 익숙해진 줄 알았는데 다시 길을 놓치셨어요. 다시 알려 드릴게요"
                else "${it.name}: 익숙해진 줄 알았는데 다시 급정거하셨어요. 다시 알려 드릴게요")
        }
        val fresh = t.newEvents.mapNotNull { id -> state.events.firstOrNull { it.id == id } }
            .filter { e -> e.spotId?.let { state.spots[it]?.status == SpotStatus.WATCH } ?: true }
        if (fresh.isNotEmpty()) {
            lines += SummaryLine(fresh.first().spotId, SummaryLine.Kind.INFO,
                "새로 기억한 곳 ${fresh.size}곳은 지켜볼게요. 다른 날 같은 곳에서 또 그러면 알려 드려요")
        }
        if (lines.isEmpty()) return null
        return Output.TripSummary(lines, t.track.toList(), lines.mapNotNull { it.spotId }.distinct())
    }

    // ------------------------------------------------------------------ 급정거 기록

    private fun record(d: Detected, t: Trip) {
        val pos = d.startFix
        // 감지는 제동이 끝난 뒤에 끝난다. 설명용 거리는 제동을 시작한 곳으로 되돌린다.
        val back = if (pos.hasPosition && d.endFix.hasPosition) Geo.distanceM(pos.lat!!, pos.lon!!, d.endFix.lat!!, d.endFix.lon!!) else 0.0
        val e = HardEvent(
            id = state.nextId++, timeMs = d.startFix.timeMs, tripId = t.id,
            lat = pos.lat, lon = pos.lon, headingDeg = pos.headingDeg ?: d.endFix.headingDeg,
            fromKmh = d.fromKmh, toKmh = d.toKmh, peakG = d.peakG, kind = d.kind,
            alongM = (t.alongM - back).coerceAtLeast(0.0),
        )
        state.events += e
        t.newEvents += e.id
        if (e.lat == null || e.lon == null) return

        val lost = e.kind == EventKind.MISSED_TURN
        val spot = nearestSpot(e.lat, e.lon, e.headingDeg, lost)
        if (spot == null) {
            val s = Spot(state.nextId++, state.nextSpotNumber++, e.lat, e.lon, e.headingDeg, e.timeMs).also { it.lost = lost }
            attach(s, e)
            state.spots[s.id] = s
            return
        }
        // 무게 중심을 옮긴다 (처음 몇 번은 많이, 쌓일수록 조금)
        val w = 1.0 / (spot.eventIds.size + 1)
        spot.lat += (e.lat - spot.lat) * w
        spot.lon += (e.lon - spot.lon) * w
        if (e.headingDeg != null) spot.headingDeg = spot.headingDeg?.let { Geo.blendHeading(it, e.headingDeg, w) } ?: e.headingDeg
        attach(spot, e)
        approaches[spot.id]?.hard = true

        when {
            spot.status == SpotStatus.WATCH && spot.tripIds.size >= config.activeMinTrips -> {
                // 우연형 원인이 이미 등록돼 있으면 바로 ACCIDENTAL
                spot.status = if (spot.cause?.isAccidental == true) SpotStatus.ACCIDENTAL else SpotStatus.ACTIVE
                t.activated += spot.id
            }
            spot.status == SpotStatus.QUIET -> {
                spot.status = SpotStatus.ACTIVE; spot.smoothStreak = 0; t.reactivated += spot.id
            }
        }
    }

    private fun attach(s: Spot, e: HardEvent) {
        e.spotId = s.id
        s.eventIds += e.id
        s.tripIds += e.tripId
        s.lastEventMs = maxOf(s.lastEventMs, e.timeMs)
    }

    /** 급정거 지점과 길 놓친 지점은 서로 섞지 않는다. */
    private fun nearestSpot(lat: Double, lon: Double, heading: Double?, lost: Boolean): Spot? =
        state.spots.values
            .filter { it.lost == lost }
            .map { it to Geo.distanceM(lat, lon, it.lat, it.lon) }
            .filter { (s, d) -> d <= (if (lost) config.lostSpotRadiusM else config.spotRadiusM) && headingOk(s.headingDeg, heading) }
            .minByOrNull { it.second }?.first

    private fun headingOk(a: Double?, b: Double?) = a == null || b == null || Geo.angleDiff(a, b) <= config.headingTolDeg

    // ------------------------------------------------------------------ 다가가기 · 알림 · 지나감

    private fun trackApproaches(f: Fix, t: Trip) {
        val lat = f.lat!!; val lon = f.lon!!
        for (s in state.spots.values) {
            val d = Geo.distanceM(lat, lon, s.lat, s.lon)
            val a = approaches[s.id]
            if (d > (if (s.lost) config.lostTrackRadiusM else config.trackRadiusM)) {
                if (a != null) { if (a.reachedPass) finishPass(s.id, a, f.timeMs, t); approaches.remove(s.id) }
                continue
            }
            if (!headingOk(s.headingDeg, f.headingDeg)) {
                // 같은 자리를 반대 방향으로 지나는 중: 이 지점과는 상관없다
                if (a != null && a.reachedPass) { finishPass(s.id, a, f.timeMs, t); approaches.remove(s.id) }
                continue
            }
            val ap = a ?: Approach().also { approaches[s.id] = it }
            val toward = f.headingDeg?.let { h -> Geo.angleDiff(h, Geo.bearingDeg(lat, lon, s.lat, s.lon)) <= 60 } ?: (d < ap.minD)
            if (d < ap.minD) ap.minD = d
            if (d <= config.passRadiusM) ap.reachedPass = true

            if (!ap.warned && !ap.reachedPass && toward && shouldWarn(s, d, f, t)) {
                ap.warned = true
                warn(s, d, f, t)
            }
            // 가장 가까웠던 곳을 지나 30m 이상 멀어지면 지나간 것
            if (ap.reachedPass && d > ap.minD + 30 && !toward) {
                finishPass(s.id, ap, f.timeMs, t)
                approaches.remove(s.id)
            }
        }
    }

    private fun shouldWarn(s: Spot, d: Double, f: Fix, t: Trip): Boolean {
        if (s.status != SpotStatus.ACTIVE && s.status != SpotStatus.ACCIDENTAL) return false
        if (useExternalGeofence && s.id !in insideFence) return false
        if (t.warnings >= config.maxWarnsPerTrip) return false
        if (f.timeMs - s.lastWarnMs < config.warnCooldownMs) return false
        val lead = if (s.lost) (f.speedKmh / 3.6 * config.lostWarnLeadSec).coerceIn(config.lostWarnMinM, config.lostWarnMaxM)
            else (f.speedKmh / 3.6 * config.warnLeadSec).coerceIn(config.warnMinM, config.warnMaxM)
        return d <= lead && d >= config.passRadiusM
    }

    private fun warn(s: Spot, d: Double, f: Fix, t: Trip) {
        t.warnings += 1
        s.warnings += 1
        s.lastWarnMs = f.timeMs
        val dist = Texts.roundDistance(d)
        val evs = s.eventIds.mapNotNull { id -> state.events.firstOrNull { it.id == id } }
        val onlySwerve = evs.isNotEmpty() && evs.all { it.kind == EventKind.SWERVE }
        emit(Output.Alert(s.id, dist, Texts.alertTitle(s), f.timeMs, Texts.alertDetail(s, f.timeMs, onlySwerve)))
        emit(Output.Speak(Texts.warning(s, dist, f.timeMs, evs.map { it.timeMs }, onlySwerve)))
    }

    private fun finishPass(spotId: Long, a: Approach, timeMs: Long, t: Trip) {
        val s = state.spots[spotId] ?: return
        // 이번 주행에 이 지점이 막 생겼다면 그 급정거 자체는 '지나감' 으로 세지 않는다
        if (s.eventIds.size == 1 && s.tripIds.singleOrNull() == t.id) return
        val outcome = if (a.hard) PassOutcome.HARD else PassOutcome.SMOOTH
        val p = Pass(spotId, timeMs, t.id, a.warned, outcome)
        state.passes += p
        t.passes += p
        s.passes += 1
        if (outcome == PassOutcome.HARD) {
            s.hardPasses += 1
            s.smoothStreak = 0
        } else if (s.status == SpotStatus.ACTIVE && a.warned) {
            s.smoothStreak += 1
            if (s.smoothStreak >= config.quietAfterSmooth) {
                s.status = SpotStatus.QUIET
                t.quieted += s.id
            }
        }
    }

    private fun withHeading(f: Fix): Fix {
        if (f.headingDeg != null || !f.hasPosition) return f
        val p = lastPos ?: return f
        if (!p.hasPosition) return f
        val moved = Geo.distanceM(p.lat!!, p.lon!!, f.lat!!, f.lon!!)
        if (moved < 3.0) return f.copy(headingDeg = p.headingDeg)
        return f.copy(headingDeg = Geo.bearingDeg(p.lat, p.lon, f.lat, f.lon))
    }

    // ------------------------------------------------------------------ 출력

    private fun emit(o: Output) {
        if (silent) return
        when (o) {
            is Output.Speak, is Output.Alert -> sink(o)
            else -> if (drive == DriveState.PARKED) sink(o) else parkedQueue += o
        }
    }

    private fun flushParked() {
        if (parkedQueue.isEmpty()) return
        val out = parkedQueue.toList()
        parkedQueue.clear()
        if (silent) return
        out.forEach(sink)
    }
}
