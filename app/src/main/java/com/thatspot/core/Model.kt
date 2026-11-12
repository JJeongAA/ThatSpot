package com.thatspot.core

/** AAOS 정의를 따른다: P단 = 주차, P가 아니고 멈춤 = 정차, 그 외 = 주행. */
enum class DriveState { MOVING, IDLING, PARKED }

/**
 * 한 순간의 차 상태. 위치가 없으면 lat/lon 이 null (급정거는 세지만 지점에는 묶지 못한다).
 * 브레이크·핸들·깜빡이는 Pleos Vehicle SDK 가 있을 때만 값이 있다.
 */
data class Fix(
    val timeMs: Long,
    val speedKmh: Double,
    val lat: Double? = null,
    val lon: Double? = null,
    /** 진행 방향 (0 = 북). 없으면 앞 위치와 비교해 구한다. */
    val headingDeg: Double? = null,
    val brakePressed: Boolean? = null,
    val steeringDeg: Double? = null,
    val turnSignalOn: Boolean? = null,
) {
    val hasPosition get() = lat != null && lon != null
}

enum class EventKind(val label: String) {
    HARD_BRAKE("급정거"),
    SWERVE("급히 핸들을 꺾음"),
    /** 내비가 경로를 다시 찾음 (갈림길, 출구를 놓침). NaviHelper onReRouteEnded */
    MISSED_TURN("길을 놓침"),
}

/**
 * 운전자가 주차 후 고르는 "왜 놀랐는지". 다음에 그 자리에 갈 때 이 문장을 미리 들려준다.
 * [warn] 은 경고 문장 끝에 붙는 말, [short] 는 목록에 쓰는 이름.
 * [isAccidental] 이 true 면 "우연형" — 도로 구조가 아니라 그때 그 차량 행동 때문.
 *   같은 자리에서 반복될 가능성이 낮으므로 경고 후 "계속 알릴까요?" 를 한 번 묻는다.
 */
enum class Cause(val label: String, val warn: String?, val short: String, val isAccidental: Boolean = false) {
    ALLEY("골목에서 차가 나왔어요", "골목에서 차가 나올 수 있어요", "골목 출구"),
    PEDESTRIAN("사람이나 자전거가 나왔어요", "사람이나 자전거가 나올 수 있어요", "보행자"),
    LEAD_STOP("앞차가 갑자기 섰어요", "앞차가 갑자기 설 수 있어요", "앞차 급정거", isAccidental = true),
    CUT_IN("옆 차가 끼어들었어요", "옆 차가 끼어들 수 있어요", "끼어들기", isAccidental = true),
    SIGNAL("신호가 갑자기 바뀌었어요", "신호가 짧아요", "짧은 신호"),
    BLIND("앞이 잘 안 보였어요", "앞이 잘 안 보여요", "시야 가림"),
    OBSTACLE("길에 뭔가 있었어요", "길에 장애물이 있을 수 있어요", "장애물"),
    // 길을 놓친 곳
    FORK("갈림길이 헷갈렸어요", "갈림길이 헷갈리는 곳이에요. 미리 차선을 확인하세요", "헷갈리는 갈림길"),
    LATE_LANE("차선을 제때 못 바꿨어요", "미리 차선을 바꿔 두세요", "늦은 차선 변경"),
    SIGN_HIDDEN("표지판이 잘 안 보였어요", "표지판이 잘 안 보이는 곳이에요", "안 보이는 표지판"),
    LATE_GUIDE("안내를 늦게 알아챘어요", "곧 갈림길이 나와요", "놓치기 쉬운 안내"),
    OTHER("잘 모르겠어요", null, "지점");

    companion object {
        /** 주차 화면에 보여 줄 선택지. 급정거와 급조향은 이유가 다르다 (OTHER 는 따로 버튼). */
        fun choicesFor(kind: EventKind): List<Cause> = when (kind) {
            EventKind.HARD_BRAKE -> listOf(ALLEY, PEDESTRIAN, LEAD_STOP, CUT_IN, SIGNAL, BLIND)
            EventKind.SWERVE -> listOf(CUT_IN, OBSTACLE, PEDESTRIAN, ALLEY, BLIND, LEAD_STOP)
            EventKind.MISSED_TURN -> listOf(FORK, LATE_LANE, SIGN_HIDDEN, LATE_GUIDE)
        }

        /** 지점 목록에서 이유를 바꿀 때의 선택지. */
        fun choicesFor(spot: Spot): List<Cause> =
            if (spot.lost) choicesFor(EventKind.MISSED_TURN) + OTHER
            else (choicesFor(EventKind.HARD_BRAKE) + choicesFor(EventKind.SWERVE)).distinct() + OTHER
    }
}

/** 감지한 급정거 한 번. 위치는 브레이크를 밟기 시작한 곳 (위험이 나타난 곳에 가깝다). */
data class HardEvent(
    val id: Long,
    val timeMs: Long,
    val tripId: Int,
    val lat: Double?,
    val lon: Double?,
    val headingDeg: Double?,
    val fromKmh: Double,
    val toKmh: Double,
    val peakG: Double,
    val kind: EventKind,
    /** 출발하고 몇 m 를 달린 곳인지 (주차 화면 설명용) */
    val alongM: Double,
    var spotId: Long? = null,
)

/**
 * 지점의 상태.
 * WATCH       : 급정거 1번. 아직 우연일 수 있어 알리지 않는다.
 * ACTIVE      : 서로 다른 날(주행) 2번 이상. 다가가면 미리 알린다.
 * ACCIDENTAL  : ACTIVE 이지만 원인이 우연형(끼어들기·앞차 급정거).
 *               경고는 하되 주차 후 "계속 알릴까요?" 를 한 번 물어본다.
 *               운전자가 "끄기"를 고르면 QUIET, "계속"을 고르면 ACTIVE 로 바꾼다.
 * QUIET       : 알림을 들은 뒤 연속으로 부드럽게 지나갔다. 익숙해졌다고 보고 알리지 않는다.
 *               여기서 다시 급정거하면 ACTIVE 로 돌아간다.
 */
enum class SpotStatus { WATCH, ACTIVE, ACCIDENTAL, QUIET }

/** 같은 자리, 같은 방향의 급정거 묶음. */
class Spot(
    val id: Long,
    /** 사람에게 보여 주는 번호 (1번 지점, 2번 지점 …) */
    val number: Int,
    var lat: Double,
    var lon: Double,
    var headingDeg: Double?,
    val createdMs: Long,
) {
    val eventIds = mutableListOf<Long>()
    val tripIds = mutableSetOf<Int>()
    var lastEventMs = createdMs
    var cause: Cause? = null
    /** 운전자가 말로 남긴 메모. 다음 경고 때 그대로 들려준다. */
    var memo: String? = null
    var status = SpotStatus.WATCH
    /** 길을 놓친 곳이면 true. 급정거 지점과는 따로 묶고, 더 일찍 알린다. */
    var lost = false
    /** 지점이 생긴 뒤 지나간 횟수 / 그중 급정거한 횟수 */
    var passes = 0
    var hardPasses = 0
    /** ACTIVE 가 된 뒤 연속으로 부드럽게 지나간 횟수 */
    var smoothStreak = 0
    var warnings = 0
    var lastWarnMs = Long.MIN_VALUE / 2

    val pos get() = LatLon(lat, lon)

    /** 이유를 알면 이유가 이름 (골목 출구), 모르면 번호 (3번 지점). */
    val name: String get() = cause?.takeIf { it != Cause.OTHER }?.short ?: if (lost) "${number}번 갈림길" else "${number}번 지점"
    val hasCauseName: Boolean get() = cause != null && cause != Cause.OTHER
}

enum class PassOutcome { SMOOTH, HARD }

/** 지점을 한 번 지나간 기록. */
data class Pass(val spotId: Long, val timeMs: Long, val tripId: Int, val warned: Boolean, val outcome: PassOutcome)

/** 엔진이 앱에 내보내는 것. 주행 중에는 Speak/Alert 만, 주차하면 카드가 나온다. */
sealed class Output {
    /** 소리로 말하기. */
    data class Speak(val text: String) : Output()

    /** 주행 중 화면 위쪽에 잠깐 띄우는 큰 글자 (거리와 지점 이름만). */
    data class Alert(val spotId: Long, val distanceM: Int, val title: String, val timeMs: Long, val detail: String = "") : Output()

    /** 주차 후: 방금 급정거한 이유 묻기. */
    data class AskCause(
        val eventId: Long,
        val spotId: Long?,
        val timeMs: Long,
        val fromKmh: Int,
        val toKmh: Int,
        val alongM: Int,
        /** 이 지점의 급정거 횟수 (이번 것 포함) */
        val spotEvents: Int,
        val track: List<LatLon>,
        val eventPos: LatLon?,
        val kind: EventKind = EventKind.HARD_BRAKE,
    ) : Output()

    /** 주차 후: 이번 주행 정리. */
    data class TripSummary(val lines: List<SummaryLine>, val track: List<LatLon>, val spotIds: List<Long>) : Output()

    /**
     * 주차 후: 이번 주행 간단 리포트.
     * [hardCount] 이번 주행 급정거 횟수, [warnedSmooth] 경고 받은 지점에서 무사통과 횟수,
     * [newSpots] 새로 등록된 지점 수.
     */
    data class TripReport(
        val hardCount: Int,
        val warnedSmooth: Int,
        val newSpots: Int,
        val tripDistanceM: Double,
        /** 이번 주행에서 길을 놓친 횟수 ([hardCount] 에는 들어가지 않는다) */
        val missedCount: Int = 0,
    ) : Output()

    /**
     * 주차 후: 우연형 원인(끼어들기·앞차 급정거)이 등록된 지점에 대해
     * "이 지점, 계속 알릴까요?" 를 한 번 묻는다.
     * 운전자가 "계속 알리기"를 고르면 ACTIVE, "알림 끄기"를 고르면 QUIET.
     */
    data class AskKeepAlert(
        val spotId: Long,
        val spotName: String,
        val cause: Cause,
        val timeMs: Long,
    ) : Output()
}

data class SummaryLine(val spotId: Long?, val kind: Kind, val text: String) {
    enum class Kind { GOOD, NEW, QUIET, AGAIN, INFO }
}
