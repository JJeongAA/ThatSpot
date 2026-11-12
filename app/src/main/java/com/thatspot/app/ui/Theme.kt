package com.thatspot.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thatspot.app.R

/**
 * 디자인 토큰. 도로 위에 있는 색과 형태만 쓴다.
 *
 * - 바탕은 아스팔트 회색, 글자는 노면 표시 흰색.
 * - 노랑(중앙선)은 '위험' 한 가지 뜻으로만 쓴다: 주행 중 경고, 알리는 지점.
 *   주 버튼은 노랑이 아니라 흰색이다. 노랑이 보이면 곧 위험이라는 뜻이 되도록.
 * - 카드로 감싸지 않는다. 구역은 여백과 가는 선으로 나눈다.
 * - 상태 표시는 점이 아니라 차선처럼 짧은 막대.
 * - 차량 가이드: 큰 터치 영역(최소 80dp), 높은 대비, 짧은 문장, 주행 중 움직임 없음.
 */
object SB {
    val Asphalt = Color(0xFF202326)    // 바탕
    val Raised = Color(0xFF2B2F33)     // 보조 버튼, 선택지
    val Line = Color(0xFF393E43)       // 구분선
    val Paint = Color(0xFFF2F3EF)      // 본문, 주 버튼
    val OnPaint = Color(0xFF1A1C1E)    // 주 버튼 글자
    val Muted = Color(0xFF949A9F)      // 보조 글
    val Dim = Color(0xFF62686D)        // 꺼진 것, 익숙해진 지점
    val Yellow = Color(0xFFF5C400)     // 위험 (중앙선 노랑)
    val Route = Color(0xFF454B51)      // 경로 스케치

    val ButtonShape = RoundedCornerShape(10.dp)
    val ButtonMinHeight = 84.dp
}

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

/** 차 화면용 타입 스케일 (팔 길이 거리 기준, 가장 작은 글자 18sp). 숫자는 고정폭. */
object SBType {
    private const val TNUM = "tnum"
    /** 주행 중 거리 숫자 하나에만 쓴다 */
    val Distance = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 240.sp, lineHeight = 220.sp, letterSpacing = (-8).sp, fontFeatureSettings = TNUM)
    val Unit = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 84.sp, letterSpacing = (-2).sp)
    val Figure = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = (-1.5).sp, fontFeatureSettings = TNUM)
    val Alert = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 52.sp, lineHeight = 62.sp, letterSpacing = (-1).sp)
    val Headline = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 50.sp, letterSpacing = (-0.8).sp)
    val Title = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp)
    val Brand = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.3).sp)
    val Body = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 36.sp)
    val Label = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp, fontFeatureSettings = TNUM)
    val Caption = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp)
    val Button = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Medium, fontSize = 24.sp)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SB.Paint, onPrimary = SB.OnPaint,
            background = SB.Asphalt, onBackground = SB.Paint,
            surface = SB.Asphalt, onSurface = SB.Paint,
            surfaceVariant = SB.Raised, onSurfaceVariant = SB.Muted,
            outline = SB.Line,
        ),
        typography = Typography(
            bodyLarge = SBType.Body, bodyMedium = SBType.Label, labelLarge = SBType.Button,
            titleLarge = SBType.Title, headlineMedium = SBType.Headline,
        ),
        content = content,
    )
}
