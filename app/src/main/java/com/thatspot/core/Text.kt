package com.thatspot.core

import kotlin.math.roundToInt

/** 사람에게 들려주고 보여 주는 문장. 운전 중에 듣는 문장은 짧게, 숫자는 어림해서. */
object Texts {
    /** 날짜를 세는 시간대. 앱은 기기 시간대, 테스트는 고정. */
    var zone: java.time.ZoneId = java.time.ZoneId.systemDefault()

    fun countWord(n: Int): String = when (n) {
        1 -> "한"; 2 -> "두"; 3 -> "세"; 4 -> "네"; 5 -> "다섯"; 6 -> "여섯"; 7 -> "일곱"; 8 -> "여덟"; 9 -> "아홉"; 10 -> "열"
        else -> n.toString()
    }

    /** 마지막 급정거가 언제였는지. 달력 날짜로 센다 (어제 아침 8시 10분 → 오늘 8시 5분은 '어제'). */
    fun whenWord(eventMs: Long, nowMs: Long): String {
        fun date(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(date(eventMs), date(nowMs)).toInt()
        return when {
            days <= 0 -> "오늘"
            days == 1 -> "어제"
            days < 7 -> "${days}일 전"
            days < 14 -> "지난주"
            days < 31 -> "${days / 7}주 전"
            else -> "한 달도 더 전"
        }
    }

    /** 알림 거리는 50m 단위로 어림한다 (정확한 숫자는 운전 중에 쓸모가 없다). */
    fun roundDistance(m: Double): Int = ((m / 50.0).roundToInt() * 50).coerceAtLeast(50)

    /**
     * 급정거 시각(ms)들로 가장 많이 몰린 시간대 이름을 돌려준다.
     * 시간대 버킷: 출근(7~9), 점심(11~13), 퇴근(17~19), 심야(22~5), 나머지 낮(그 외).
     * 2회 이상 같은 버킷에 몰려야 의미 있다고 본다.
     */
    fun peakTimeLabel(eventTimesMs: List<Long>): String? {
        if (eventTimesMs.size < 2) return null
        val buckets = mutableMapOf<String, Int>()
        for (ms in eventTimesMs) {
            val h = java.time.Instant.ofEpochMilli(ms).atZone(zone).hour
            val bucket = when (h) {
                in 7..9 -> "출근 시간"
                in 11..13 -> "점심 시간"
                in 17..19 -> "퇴근 시간"
                in 22..23, in 0..5 -> "심야"
                else -> null
            }
            if (bucket != null) buckets[bucket] = (buckets[bucket] ?: 0) + 1
        }
        // 2회 이상이고 가장 많은 버킷
        return buckets.entries.filter { it.value >= 2 }.maxByOrNull { it.value }?.key
    }

    /**
     * 주행 중 알림. 예: "150미터 앞, 지난주까지 세 번 급정거하신 곳이에요. 골목에서 차가 나올 수 있어요."
     * 운전자가 남긴 메모가 있으면 원인 문장 대신 그 말을 그대로 들려준다 (자기 말이 가장 잘 들린다).
     * 특정 시간대에 몰린 패턴이 있으면 그것도 말해 준다.
     */
    fun warning(spot: Spot, distanceM: Int, nowMs: Long, eventTimesMs: List<Long> = emptyList(), swerve: Boolean = false): String {
        val n = spot.eventIds.size
        val w = whenWord(spot.lastEventMs, nowMs)
        val also = if (w == "오늘" || w == "어제") "${w}도" else "${w}에도"
        val what = when { spot.lost -> "길을 놓치신"; swerve -> "급히 핸들을 꺾으신"; else -> "급정거하신" }
        val head = "${distanceM}미터 앞, $also $what 곳이에요. 모두 ${countWord(n)} 번."
        val tail = spot.memo?.let { " 남기신 말: $it." } ?: spot.cause?.warn?.let { " $it." }
            ?: if (spot.lost) " 미리 차선을 확인하세요." else ""
        val timeTip = peakTimeLabel(eventTimesMs)?.let { " 이 구간, ${it}에 특히 조심하세요." } ?: ""
        return head + tail + timeTip
    }

    fun alertTitle(spot: Spot): String = spot.cause?.takeIf { it != Cause.OTHER }?.short ?: if (spot.lost) "길을 놓쳤던 곳" else "내가 놀랐던 곳"

    /** 주행 중 경고 화면의 작은 줄. 예: "어제도 급정거한 곳, 모두 세 번" */
    fun alertDetail(spot: Spot, nowMs: Long, swerve: Boolean = false): String {
        val w = whenWord(spot.lastEventMs, nowMs)
        val also = if (w == "오늘" || w == "어제") "${w}도" else "${w}에도"
        val what = when { spot.lost -> "길을 놓친"; swerve -> "핸들을 급히 꺾은"; else -> "급정거한" }
        return "$also $what 곳, 모두 ${countWord(spot.eventIds.size)} 번"
    }

    fun km(m: Number): String {
        val v = m.toDouble()
        return if (v < 1000) "${v.roundToInt()}m" else String.format(java.util.Locale.US, "%.1fkm", v / 1000)
    }

    /** 주차 화면: 급정거 설명. */
    fun eventLine(e: Output.AskCause): String = when (e.kind) {
        EventKind.HARD_BRAKE -> "출발하고 ${km(e.alongM)} 지점에서 시속 ${e.fromKmh}km 에서 ${e.toKmh}km 까지 급하게 줄었어요"
        EventKind.SWERVE -> "출발하고 ${km(e.alongM)} 지점, 시속 ${e.fromKmh}km 에서 깜빡이 없이 핸들을 크게 꺾었어요"
        EventKind.MISSED_TURN -> "출발하고 ${km(e.alongM)} 지점을 지난 뒤 내비가 경로를 다시 찾았어요"
    }

    /** 주차 화면 질문. */
    fun causeQuestion(e: Output.AskCause): String {
        val what = when (e.kind) {
            EventKind.SWERVE -> "급히 핸들을 꺾으셨어요"
            EventKind.MISSED_TURN -> "길을 놓치셨어요"
            EventKind.HARD_BRAKE -> "급정거하셨어요"
        }
        return if (e.spotEvents >= 2) "같은 곳에서 ${e.spotEvents}번째예요. 무엇 때문이었나요?" else "여기서 $what. 무엇 때문이었나요?"
    }

    /** 지점 목록의 한 줄 기록. */
    fun spotRecord(s: Spot): String {
        val after = s.passes
        val parts = mutableListOf<String>()
        if (s.hasCauseName) parts += "${s.number}번 지점"
        parts += if (s.lost) "길 놓침 ${s.eventIds.size}번" else "급정거 ${s.eventIds.size}번"
        if (after > 0) parts += "그 뒤 ${after}번 중 ${after - s.hardPasses}번 부드럽게 지나감"
        return parts.joinToString(", ")
    }

    fun statusLabel(s: Spot, cfg: Config): String = when (s.status) {
        SpotStatus.WATCH -> "지켜보는 중"
        SpotStatus.ACTIVE -> if (s.smoothStreak > 0) "알리는 중 ${s.smoothStreak}/${cfg.quietAfterSmooth}" else "알리는 중"
        SpotStatus.ACCIDENTAL -> "알리는 중, 우연일 수 있음"
        SpotStatus.QUIET -> "익숙해짐"
    }
}
