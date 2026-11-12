package com.thatspot.core

/**
 * 말로 남긴 이유를 [Cause] 로 나눈다. Pleos SpeechToText 는 글자만 돌려주고 뜻은 풀지 않으므로
 * (문서: "음성 명령 기능을 제공하지 않습니다") 앱이 낱말로 고른다. 원문은 메모로 그대로 남긴다.
 */
object CauseMatcher {
    private val rules: List<Pair<Cause, List<String>>> = listOf(
        Cause.FORK to listOf("헷갈", "갈림길", "분기", "출구", "나들목", "램프", "IC", "JC"),
        Cause.LATE_LANE to listOf("차선을 못", "차선 못", "못 끼", "못끼", "차선 변경", "막혀서", "늦게 바꿔"),
        Cause.SIGN_HIDDEN to listOf("표지판", "이정표"),
        Cause.LATE_GUIDE to listOf("안내", "내비", "네비", "못 들", "늦게 들", "늦게 봤"),
        Cause.ALLEY to listOf("골목", "이면도로", "주차장", "건물에서", "나오는 차"),
        Cause.PEDESTRIAN to listOf("사람", "보행자", "자전거", "킥보드", "아이", "애가", "할머니", "할아버지", "학생", "오토바이"),
        Cause.CUT_IN to listOf("끼어", "깜빡이", "차선", "밀고 들어"),
        Cause.LEAD_STOP to listOf("앞차", "앞 차", "앞에 차", "급정거", "갑자기 섰", "갑자기 서"),
        Cause.SIGNAL to listOf("신호", "노란불", "빨간불", "황색"),
        Cause.OBSTACLE to listOf("장애물", "떨어진", "낙하물", "공사", "구멍", "포트홀", "동물", "고라니", "박스"),
        Cause.BLIND to listOf("안 보", "안보", "커브", "가려", "가렸", "그늘", "어두", "언덕"),
    )

    /** [kind] 를 주면 그 사건에 맞는 이유 안에서만 고른다 (길을 놓친 곳의 "차선" 은 끼어들기가 아니다). */
    fun match(text: String?, kind: EventKind? = null): Cause {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return Cause.OTHER
        val compact = t.replace(" ", "")
        // 종류를 모르면 급정거, 급조향 이유 안에서 고른다
        val allowed = (kind?.let { Cause.choicesFor(it) }
            ?: (Cause.choicesFor(EventKind.HARD_BRAKE) + Cause.choicesFor(EventKind.SWERVE))).toSet()
        // 가장 먼저 나온 낱말의 분류를 고른다 ("골목에서 자전거가" → 골목)
        return rules.filter { it.first in allowed }.flatMap { (c, words) -> words.map { w -> c to w } }
            .mapNotNull { (c, w) ->
                val i = t.indexOf(w).takeIf { it >= 0 } ?: compact.indexOf(w.replace(" ", "")).takeIf { it >= 0 }
                i?.let { c to it }
            }
            .minByOrNull { it.second }?.first ?: Cause.OTHER
    }
}
