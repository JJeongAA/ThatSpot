package com.thatspot.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thatspot.app.service.DriveWatchService
import com.thatspot.app.ui.AppTheme
import com.thatspot.app.ui.SB
import com.thatspot.app.ui.SBType
import com.thatspot.core.Cause
import com.thatspot.core.DriveState
import com.thatspot.core.EventKind
import com.thatspot.core.LatLon
import com.thatspot.core.Output
import com.thatspot.core.Spot
import com.thatspot.core.SpotStatus
import com.thatspot.core.SummaryLine
import com.thatspot.core.Texts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * 화면 원칙 (Pleos 주행 안전 가이드):
 *  - 주행 중, 정차 중: 버튼 없음. 알림이 오면 거리와 이름만 크게. 내용은 음성으로.
 *  - 주차 중: 급정거 이유 묻기, 주행 정리, 지점 목록, 설정.
 *
 * 생김새 (ui/Theme.kt): 아스팔트 바탕, 흰 노면 글자, 노랑은 위험에만. 카드 없이 여백과 가는 선으로 나눈다.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = (application as ThatSpotApp).controller
        askPermissions(c)
        setContent { AppTheme { Box(Modifier.fillMaxSize().background(SB.Asphalt)) { Root(c) } } }
    }
}

/** 차량 값, 위치, 마이크는 '위험 권한' 이라 설치만으로는 허용되지 않는다. 허용되면 입력을 다시 연결한다. */
private fun ComponentActivity.askPermissions(c: AppController) {
    val wanted = arrayOf(
        "android.car.permission.CAR_SPEED",
        "android.car.permission.CAR_POWERTRAIN",
        "pleos.car.permission.CAR_DRIVE",
        "pleos.car.permission.READ_CAR_STEERING",
        "pleos.car.permission.CAR_LIGHTS",
        "pleos.car.permission.CAR_DOORS",
        "pleos.car.permission.FUSED_LOCATION",
        "pleos.car.permission.TTS_SERVICE",
        "pleos.car.permission.STT_SERVICE",
        "pleos.car.permission.NAVI_ROUTE",
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (wanted.isEmpty()) { DriveWatchService.start(this); return }
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) c.onPermissionsChanged()
        DriveWatchService.start(this)
    }.launch(wanted.toTypedArray())
}

private val clock = SimpleDateFormat("a h:mm", Locale.KOREA)
private val day = SimpleDateFormat("M월 d일", Locale.KOREA)

// ============================================================================ 뼈대

@Composable
private fun Root(c: AppController) {
    val drive by c.driveState.collectAsStateWithLifecycle()
    val restricted by c.uxRestricted.collectAsStateWithLifecycle()
    val cards by c.cards.collectAsStateWithLifecycle()
    val paused by c.demoPaused.collectAsStateWithLifecycle()
    val caption by c.caption.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 44.dp)) {
        TopBar(c, drive)
        Spacer(Modifier.height(40.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val card = cards.firstOrNull()
            when {
                drive != DriveState.PARKED -> DrivingScreen(c)
                card is Output.AskCause -> CauseScreen(c, card)
                card is Output.TripSummary -> SummaryScreen(c, card)
                card is Output.TripReport -> TripReportScreen(c, card)
                card is Output.AskKeepAlert -> AskKeepAlertScreen(c, card)
                paused != null -> DemoPausedScreen(c, paused!!)
                restricted -> QuietScreen("주행 중에는 목록을 열 수 없어요")
                else -> HomeScreen(c)
            }
        }
        // 자막: 주행 중에는 글을 띄우지 않는다
        val cap = caption
        if (cap != null && drive == DriveState.PARKED) CaptionBar(c, cap)
    }
}

@Composable
private fun TopBar(c: AppController, drive: DriveState) {
    val note by c.demoNote.collectAsStateWithLifecycle()
    val mode by c.mode.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        StopMark()
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.app_name), style = SBType.Brand, color = SB.Paint)
        if (mode == AppController.Mode.DEMO) {
            Spacer(Modifier.width(32.dp))
            Text(note?.let { "시연 중, $it" } ?: "시연 중", style = SBType.Label, color = SB.Muted)
        }
        Spacer(Modifier.weight(1f))
        Text(
            when (drive) { DriveState.MOVING -> "주행 중"; DriveState.IDLING -> "정차 중"; DriveState.PARKED -> "주차 중" },
            style = SBType.Label, color = SB.Muted,
        )
    }
}

/** 앱 표시: 정지선 한 줄. */
@Composable
private fun StopMark(color: Color = SB.Paint) {
    Box(Modifier.size(width = 34.dp, height = 8.dp).background(color, RoundedCornerShape(1.dp)))
}

/** 지점 상태 막대. 알리는 곳은 노랑, 지켜보는 곳은 빈 테두리, 익숙해진 곳은 어두운 회색. */
@Composable
private fun StatusBar(status: SpotStatus, height: Dp = 44.dp, width: Dp = 6.dp) {
    val m = Modifier.size(width = width, height = height)
    when (status) {
        SpotStatus.ACTIVE, SpotStatus.ACCIDENTAL -> Box(m.background(SB.Yellow, RoundedCornerShape(1.dp)))
        SpotStatus.WATCH -> Box(m.border(2.dp, SB.Muted, RoundedCornerShape(1.dp)))
        SpotStatus.QUIET -> Box(m.background(SB.Dim, RoundedCornerShape(1.dp)))
    }
}

@Composable
private fun VLine() { Box(Modifier.width(1.dp).fillMaxHeight().background(SB.Line)) }

@Composable
private fun HLine() { Box(Modifier.fillMaxWidth().height(1.dp).background(SB.Line)) }

@Composable
private fun QuietScreen(text: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(text, style = SBType.Headline, color = SB.Paint)
    }
}

// ============================================================================ 주행 중: 거리와 이름만

/**
 * 주행 중 화면의 전부. 누를 것이 없다. 평소에는 조용한 한 줄, 알림이 오면 거리와 이름만 크게.
 * 왼쪽의 차선 점선 끝 노란 정지선이 '앞에 있는 그 자리'.
 */
@Composable
private fun DrivingScreen(c: AppController) {
    val alert by c.alert.collectAsStateWithLifecycle()
    c.revision.collectAsStateWithLifecycle().value
    val a = alert

    if (a == null) {
        // ── 대기: 도로 그래픽이 화면 전체 ──
        val speed by c.speedKmh.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize()) {
            StandbyRoad(speed)
            // 하단 상태 텍스트 오버레이
            Column(
                Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 28.dp),
            ) {
                val n = c.activeCount()
                Text("지켜보는 중", style = SBType.Headline, color = SB.Paint)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (n == 0) "놀란 곳이 생기면 다음부터 미리 알려 드려요" else "이 길에서 미리 알려 드릴 곳 ${n}곳",
                    style = SBType.Body, color = SB.Muted,
                )
            }
        }
    } else {
        // ── 경고: 차선 + 거리 숫자 ──
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
            LaneMark(stop = true)
            Spacer(Modifier.width(72.dp))
            Column(Modifier.padding(bottom = 16.dp)) {
                Row {
                    Text("${a.distanceM}", style = SBType.Distance, color = SB.Paint, modifier = Modifier.alignByBaseline())
                    Spacer(Modifier.width(14.dp))
                    Text("m", style = SBType.Unit, color = SB.Muted, modifier = Modifier.alignByBaseline())
                }
                Spacer(Modifier.height(36.dp))
                Text(a.title, style = SBType.Alert, color = SB.Yellow)
                if (a.detail.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(a.detail, style = SBType.Body, color = SB.Muted)
                }
            }
        }
    }
}

/** 차선 점선. [stop] 이면 끝에 노란 정지선. */
@Composable
private fun LaneMark(stop: Boolean) {
    Column(
        Modifier.width(120.dp).padding(bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        repeat(4) { Box(Modifier.size(width = 8.dp, height = 52.dp).background(SB.Paint.copy(alpha = 0.3f), RoundedCornerShape(1.dp))) }
        Box(Modifier.size(width = 120.dp, height = 14.dp).background(if (stop) SB.Yellow else SB.Line, RoundedCornerShape(1.dp)))
    }
}

/**
 * 대기 화면을 꽉 채우는 도로. 차선 점선이 차 속도에 맞춰 앞으로 흘러온다 (정차하면 멈춘다).
 * [speedKmh] 는 지금 속도. 시속 50km 에서 점선이 1초에 1.2개쯤 지나간다.
 */
@Composable
private fun StandbyRoad(speedKmh: Float) {
    val speed by rememberUpdatedState(speedKmh)
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        var shown = 0f // 화면에 쓰는 속도. 급하게 튀지 않게 부드럽게 따라간다.
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    shown += (speed - shown) * min(1f, dt * 3f)
                    phase = (phase + dt * shown / 50f * 1.2f) % 1f
                }
                last = now
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val vy = h * 0.08f            // 소실점 높이
        val rb = w * 0.46f            // 아래쪽 도로 반폭
        val rt = w * 0.038f           // 위쪽 도로 반폭
        fun y(z: Float) = vy + (h - vy) * z * z          // z: 0 멀리 → 1 바로 앞
        fun half(z: Float) = rt + (rb - rt) * z * z

        val road = Path().apply {
            moveTo(cx - rb, h); lineTo(cx + rb, h); lineTo(cx + rt, vy); lineTo(cx - rt, vy); close()
        }
        drawPath(road, SB.Raised)
        drawLine(SB.Route, Offset(cx - rb, h), Offset(cx - rt, vy), strokeWidth = 4f, cap = StrokeCap.Round)
        drawLine(SB.Route, Offset(cx + rb, h), Offset(cx + rt, vy), strokeWidth = 4f, cap = StrokeCap.Round)

        val n = 9
        val len = 0.52f / n
        for (i in -1 until n) {
            val z0 = (i + phase) / n + len         // 점선 앞쪽 끝
            val z1 = z0 - len                      // 점선 뒤쪽 끝
            if (z0 <= 0f || z1 >= 1f) continue
            val a = z0.coerceIn(0f, 1f); val b = z1.coerceIn(0f, 1f)
            val alpha = min(0.12f + a * a * 0.55f, 0.65f)
            val stroke = 2.5f + a * a * 11f
            for (side in listOf(-0.09f, 0.09f)) {
                drawLine(
                    SB.Paint.copy(alpha = alpha),
                    Offset(cx + half(a) * side, y(a)), Offset(cx + half(b) * side, y(b)),
                    strokeWidth = stroke, cap = StrokeCap.Round,
                )
            }
        }
    }
}

// ============================================================================ 주차 후: 왜 놀랐나요

@Composable
private fun CauseScreen(c: AppController, q: Output.AskCause) {
    val listening by c.listening.collectAsStateWithLifecycle()
    val heard by c.heard.collectAsStateWithLifecycle()
    c.revision.collectAsStateWithLifecycle().value
    Split(
        left = { TrackSketch(q.track, c.allSpots(), highlight = q.eventPos, highlightSpot = q.spotId) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Meta(clock.format(Date(q.timeMs)))
            Meta("출발 후 ${Texts.km(q.alongM)}")
            Meta(
                when (q.kind) {
                    EventKind.SWERVE -> "시속 ${q.fromKmh}km, 핸들 급히 꺾음"
                    EventKind.MISSED_TURN -> "내비가 경로를 다시 찾음"
                    EventKind.HARD_BRAKE -> "${q.fromKmh} → ${q.toKmh}km/h"
                },
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(Texts.causeQuestion(q).replace(". ", ".\n"), style = SBType.Headline, color = SB.Paint)
        Spacer(Modifier.height(8.dp))
        Text("고른 이유는 다음에 이곳에 가까워질 때 말씀드려요.", style = SBType.Body, color = SB.Muted)
        Spacer(Modifier.height(32.dp))
        Cause.choicesFor(q.kind).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { cause -> SecondaryButton(cause.label, Modifier.weight(1f), start = true) { c.answerCause(q.eventId, cause) } }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(if (listening) "듣고 있어요" else "말로 답하기", Modifier.weight(1f), enabled = !listening) { c.askCauseByVoice(q) }
            SecondaryButton(Cause.OTHER.label, Modifier.weight(1f)) { c.answerCause(q.eventId, Cause.OTHER) }
        }
        heard?.let { Spacer(Modifier.height(14.dp)); Text("들은 말: $it", style = SBType.Label, color = SB.Paint) }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { c.notHardBrake(q.eventId) }, contentPadding = PaddingValues(0.dp)) {
            Text(
                when (q.kind) {
                    EventKind.SWERVE -> "급조향이 아니었어요"
                    EventKind.MISSED_TURN -> "일부러 다른 길로 갔어요"
                    EventKind.HARD_BRAKE -> "급정거가 아니었어요"
                },
                style = SBType.Label, color = SB.Muted,
            )
        }
    }
}

@Composable
private fun Meta(text: String) { Text(text, style = SBType.Label, color = SB.Muted) }

/** 왼쪽 경로, 가는 세로선, 오른쪽 내용. 카드 없이. */
@Composable
private fun Split(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(0.36f).fillMaxHeight().padding(end = 48.dp), content = left)
        VLine()
        Column(Modifier.weight(0.64f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 64.dp), content = right)
    }
}

// ============================================================================ 주차 후: 주행 정리

@Composable
private fun SummaryScreen(c: AppController, s: Output.TripSummary) {
    Split(left = { TrackSketch(s.track, c.allSpots(), highlight = null, highlightSpot = null) }) {
        Text(headline(s), style = SBType.Headline, color = SB.Paint)
        Spacer(Modifier.height(20.dp))
        s.lines.forEach { l ->
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp)) { LineBar(l.kind) }
                Spacer(Modifier.width(24.dp))
                Text(l.text, style = SBType.Body, color = SB.Paint)
            }
            HLine()
        }
        Spacer(Modifier.height(32.dp))
        PrimaryButton("확인", Modifier.widthIn(min = 280.dp)) { c.dismissCard() }
    }
}

@Composable
private fun LineBar(k: SummaryLine.Kind) {
    val m = Modifier.size(width = 4.dp, height = 24.dp)
    when (k) {
        SummaryLine.Kind.NEW, SummaryLine.Kind.AGAIN -> Box(m.background(SB.Yellow))
        SummaryLine.Kind.GOOD -> Box(m.background(SB.Paint))
        SummaryLine.Kind.QUIET -> Box(m.background(SB.Dim))
        SummaryLine.Kind.INFO -> Box(m.border(1.5.dp, SB.Muted))
    }
}

private fun headline(s: Output.TripSummary): String {
    val good = s.lines.count { it.kind == SummaryLine.Kind.GOOD || it.kind == SummaryLine.Kind.QUIET }
    val again = s.lines.count { it.kind == SummaryLine.Kind.AGAIN }
    return when {
        again > 0 -> "다시 놀란 곳이 있어요"
        good > 0 -> "알려 드린 곳 ${good}곳, 모두 부드럽게 지나셨어요"
        else -> "새로 기억해 둔 곳이 있어요"
    }
}

/**
 * 경로 스케치 (지도 없이). 이번 주행 경로를 굵은 선으로, 지점을 짧은 막대로 그린다.
 * 알리는 지점은 노랑, 지켜보는 지점은 회색, 익숙해진 지점은 어두운 회색. 방금 급정거한 곳은 흰 고리.
 */
@Composable
private fun ColumnScope.TrackSketch(track: List<LatLon>, spots: Collection<Spot>, highlight: LatLon?, highlightSpot: Long?) {
    val near = spots.filter { s -> track.isEmpty() || track.any { com.thatspot.core.Geo.distanceM(it, s.pos) < 300 } }
    val all = track + near.map { it.pos } + listOfNotNull(highlight)
    Text("이번 경로", style = SBType.Caption, color = SB.Muted)
    Spacer(Modifier.height(16.dp))
    if (all.size < 2) {
        Text("위치 기록이 없어요", style = SBType.Body, color = SB.Muted)
        return
    }
    Canvas(Modifier.fillMaxWidth().weight(1f)) {
        val lat0 = all.map { it.lat }.average()
        val k = cos(Math.toRadians(lat0))
        val xs = all.map { it.lon * k }; val ys = all.map { it.lat }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val pad = 36f
        val scale = min((size.width - 2 * pad) / max(maxX - minX, 1e-6), (size.height - 2 * pad) / max(maxY - minY, 1e-6)).toFloat()
        val ox = (size.width - (maxX - minX).toFloat() * scale) / 2
        val oy = (size.height - (maxY - minY).toFloat() * scale) / 2
        fun pt(p: LatLon) = Offset(ox + ((p.lon * k - minX) * scale).toFloat(), oy + ((maxY - p.lat) * scale).toFloat())
        if (track.size >= 2) {
            val path = Path().apply { moveTo(pt(track[0]).x, pt(track[0]).y); track.drop(1).forEach { lineTo(pt(it).x, pt(it).y) } }
            drawPath(path, SB.Route, style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(SB.Muted, 8f, pt(track.first()))
        }
        near.forEach { s ->
            val col = when (s.status) { SpotStatus.ACTIVE, SpotStatus.ACCIDENTAL -> SB.Yellow; SpotStatus.WATCH -> SB.Muted; SpotStatus.QUIET -> SB.Dim }
            val w = if (s.id == highlightSpot) 30f else 24f
            val p = pt(s.pos)
            drawRoundRect(col, topLeft = Offset(p.x - w / 2, p.y - 4f), size = Size(w, 8f), cornerRadius = CornerRadius(1.5f))
        }
        highlight?.let { drawCircle(SB.Paint, 30f, pt(it), style = Stroke(width = 4f)) }
    }
    Spacer(Modifier.height(16.dp))
    Legend()
}

@Composable
private fun Legend() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        listOf("알리는 곳" to SpotStatus.ACTIVE, "지켜보는 곳" to SpotStatus.WATCH, "익숙해진 곳" to SpotStatus.QUIET).forEach { (t, st) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBar(st, height = 16.dp, width = 4.dp)
                Spacer(Modifier.width(10.dp))
                Text(t, style = SBType.Caption, color = SB.Muted)
            }
        }
    }
}

// ============================================================================ 주차 후: 주행 리포트

/**
 * 급정거가 있었던 주행 후 간단 리포트.
 * 마이현대 안전점수와 달리, 경고를 듣고 부드럽게 지나간 횟수를 앞에 둔다 (행동이 바뀐 증거).
 */
@Composable
private fun TripReportScreen(c: AppController, r: Output.TripReport) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val fresh = r.hardCount + r.missedCount
        val headline = when {
            r.warnedSmooth > 0 && fresh == 0 -> "알려 드린 곳 ${r.warnedSmooth}곳, 모두 부드럽게 지나셨어요"
            r.warnedSmooth > 0 -> "알려 드린 곳은 잘 지나셨고, 새로 기억한 곳이 있어요"
            r.hardCount > 0 -> "이번 주행에서 ${r.hardCount}번 놀라셨어요"
            r.missedCount > 0 -> "이번 주행에서 길을 ${r.missedCount}번 놓치셨어요"
            else -> "주행을 마쳤어요"
        }
        Text(headline, style = SBType.Headline, color = SB.Paint)
        Spacer(Modifier.height(48.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(88.dp)) {
            Stat("${r.warnedSmooth}", "알림 듣고 부드럽게 지나감", SB.Paint)
            Stat("${r.hardCount}", "급정거, 급조향", if (r.hardCount > 0) SB.Yellow else SB.Paint)
            if (r.missedCount > 0) Stat("${r.missedCount}", "길 놓침", SB.Yellow)
            if (r.tripDistanceM > 0) Stat(Texts.km(r.tripDistanceM), "주행 거리", SB.Paint)
        }
        if (r.newSpots > 0) {
            Spacer(Modifier.height(40.dp))
            HLine()
            Spacer(Modifier.height(20.dp))
            Text("새로 기억한 곳 ${r.newSpots}곳. 다른 날 같은 곳에서 또 놀라면 그때부터 미리 알려 드려요.", style = SBType.Body, color = SB.Muted)
        }
        Spacer(Modifier.height(40.dp))
        PrimaryButton("확인", Modifier.widthIn(min = 280.dp)) { c.dismissCard() }
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color) {
    Column {
        Text(value, style = SBType.Figure, color = color)
        Spacer(Modifier.height(6.dp))
        Text(label, style = SBType.Label, color = SB.Muted)
    }
}

// ============================================================================ 주차 후: 우연일 수 있는 지점

/**
 * 끼어들기, 앞차 급정거처럼 그때의 상황 때문이었던 지점은
 * 같은 자리에서 반복되지 않을 수 있으니 계속 알릴지 운전자가 정한다.
 */
@Composable
private fun AskKeepAlertScreen(c: AppController, q: Output.AskKeepAlert) {
    Column(Modifier.fillMaxSize().widthIn(max = 1100.dp)) {
        Text("${q.spotName}, 계속 미리 알려 드릴까요?", style = SBType.Headline, color = SB.Paint)
        Spacer(Modifier.height(12.dp))
        Text(
            "“${q.cause.label}”는 그때의 상황이라 같은 자리에서 다시 일어나지 않을 수 있어요.",
            style = SBType.Body, color = SB.Muted,
        )
        Spacer(Modifier.height(40.dp))
        Row(Modifier.widthIn(max = 900.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("계속 알리기", Modifier.weight(1f)) { c.keepAlert(q.spotId) }
            SecondaryButton("알림 끄기", Modifier.weight(1f)) { c.stopAlert(q.spotId) }
        }
    }
}

// ============================================================================ 시연 일시정지

@Composable
private fun DemoPausedScreen(c: AppController, text: String) {
    Column(Modifier.fillMaxSize()) {
        Text(text, style = SBType.Headline, color = SB.Paint)
        Spacer(Modifier.height(12.dp))
        Text("다음 장면은 다음 날 아침, 같은 길이에요.", style = SBType.Body, color = SB.Muted)
        Spacer(Modifier.height(40.dp))
        PrimaryButton("다음 날 아침으로", Modifier.widthIn(min = 360.dp)) { c.continueDemo() }
    }
}

// ============================================================================ 홈 (주차 중)

@Composable
private fun HomeScreen(c: AppController) {
    val mode by c.mode.collectAsStateWithLifecycle()
    val caps by c.capabilities.collectAsStateWithLifecycle()
    val spots by c.spots.collectAsStateWithLifecycle()
    val gps by c.gpsSpeed.collectAsStateWithLifecycle()
    c.revision.collectAsStateWithLifecycle().value
    var open by remember { mutableStateOf<Long?>(null) }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1.5f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 64.dp)) {
            Text("기억한 곳", style = SBType.Headline, color = SB.Paint)
            Spacer(Modifier.height(12.dp))
            if (spots.isEmpty()) {
                Text(
                    "운전만 하세요. 급정거한 곳과 길을 놓친 곳을 기억해 두었다가,\n다른 날 같은 곳에서 또 그러면 다음부터 미리 말로 알려 드려요.",
                    style = SBType.Body, color = SB.Muted,
                )
            }
            spots.forEach { s ->
                SpotRow(c, s, open == s.id) { open = if (open == s.id) null else s.id }
                HLine()
            }
            Spacer(Modifier.height(56.dp))
            if (mode == AppController.Mode.REAL) {
                Text("시연", style = SBType.Title, color = SB.Paint)
                Spacer(Modifier.height(6.dp))
                Text("지난 10일 출근길 기록을 만들고 오늘 아침부터 재생해요. 실제 기록은 바뀌지 않아요.", style = SBType.Body, color = SB.Muted)
                Spacer(Modifier.height(20.dp))
                OutlineButton("시연 시작", Modifier.widthIn(min = 320.dp)) { c.startDemo() }
            } else {
                OutlineButton("시연 끝내고 실제 기록으로", Modifier.widthIn(min = 360.dp)) { c.stopDemo() }
            }
        }
        VLine()
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 56.dp)) {
            Section("음성")
            KeyValue("안내 음성", c.speakerName)
            Spacer(Modifier.height(12.dp))
            OutlineButton("음성 들어 보기", Modifier.fillMaxWidth()) {
                c.speak("150미터 앞, 어제도 급정거하신 곳이에요. 모두 두 번. 골목에서 차가 나올 수 있어요.")
            }
            Spacer(Modifier.height(40.dp))
            Section("센서")
            caps?.let { k ->
                KeyValue("속도", k.speed)
                KeyValue("기어", if (k.gear) "읽는 중" else null)
                KeyValue("위치", k.location)
                KeyValue("브레이크", if (k.brake) "읽는 중" else null)
                KeyValue("핸들 각도", if (k.steering) "읽는 중" else null)
                KeyValue("깜빡이", if (k.turnSignal) "읽는 중" else null)
                KeyValue("경로 재탐색", if (mode == AppController.Mode.DEMO) "시연 데이터" else c.naviStatus)
                Spacer(Modifier.height(8.dp))
                Text("브레이크 값이 있으면 언덕이나 변속으로 느려진 것은 세지 않아요. 길을 놓친 곳은 내비 경로 안내 중일 때만 알 수 있어요.", style = SBType.Caption, color = SB.Muted)
            }
            if (mode == AppController.Mode.REAL) {
                Spacer(Modifier.height(32.dp))
                HLine()
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("에뮬레이터 경로 시험", style = SBType.Body, color = SB.Paint)
                        Text("차량 속도 대신 위치 속도를 써요", style = SBType.Caption, color = SB.Muted)
                    }
                    Switch(
                        checked = gps, onCheckedChange = { c.setGpsSpeed(it) },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = SB.Paint, checkedThumbColor = SB.Asphalt,
                            uncheckedTrackColor = SB.Raised, uncheckedThumbColor = SB.Muted, uncheckedBorderColor = SB.Line,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = SBType.Label, color = SB.Muted)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SpotRow(c: AppController, s: Spot, expanded: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 22.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusBar(s.status)
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = SBType.Title, color = SB.Paint)
                Spacer(Modifier.height(2.dp))
                Text("${Texts.spotRecord(s)}, 마지막 ${day.format(Date(s.lastEventMs))}", style = SBType.Label, color = SB.Muted)
            }
            Spacer(Modifier.width(24.dp))
            val on = s.status == SpotStatus.ACTIVE || s.status == SpotStatus.ACCIDENTAL
            Text(c.spotStatus(s), style = SBType.Label, color = if (on) SB.Yellow else SB.Muted)
        }
        if (expanded) {
            Column(Modifier.padding(start = 34.dp, top = 16.dp)) {
                s.memo?.let { Text("남기신 말: $it", style = SBType.Body, color = SB.Paint); Spacer(Modifier.height(16.dp)) }
                Text("이유 바꾸기", style = SBType.Label, color = SB.Muted)
                Spacer(Modifier.height(10.dp))
                Cause.choicesFor(s).chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { cause ->
                            val on = s.cause == cause
                            OutlinedButton(
                                onClick = { c.setSpotCause(s.id, cause) }, shape = SB.ButtonShape,
                                modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                                border = BorderStroke(1.5.dp, if (on) SB.Paint else SB.Line),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = if (on) SB.Paint else SB.Muted),
                            ) { Text(cause.short, style = SBType.Label) }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                TextButton(onClick = { c.deleteSpot(s.id) }, contentPadding = PaddingValues(0.dp)) {
                    Text("이 지점 지우기", style = SBType.Label, color = SB.Muted)
                }
            }
        }
    }
}

@Composable
private fun KeyValue(name: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = SBType.Body, color = SB.Paint, modifier = Modifier.weight(1f))
        Text(value ?: "없음", style = SBType.Label, color = if (value != null) SB.Muted else SB.Dim)
    }
}

// ============================================================================ 공통 부품

@Composable
private fun CaptionBar(c: AppController, cap: AppController.Caption) {
    Spacer(Modifier.height(16.dp))
    HLine()
    Row(
        Modifier.fillMaxWidth().clickable { c.dismissCaption() }.padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeakerMark(if (cap.audible) SB.Paint else SB.Dim)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(cap.text, style = SBType.Body, color = SB.Paint)
            if (!cap.audible) Text("소리 없이 글로 보여 드려요", style = SBType.Caption, color = SB.Muted)
        }
    }
}

@Composable
private fun SpeakerMark(color: Color) {
    Canvas(Modifier.size(28.dp)) {
        val w = size.width; val h = size.height
        val body = Path().apply {
            moveTo(w * 0.08f, h * 0.36f); lineTo(w * 0.30f, h * 0.36f); lineTo(w * 0.55f, h * 0.14f)
            lineTo(w * 0.55f, h * 0.86f); lineTo(w * 0.30f, h * 0.64f); lineTo(w * 0.08f, h * 0.64f); close()
        }
        drawPath(body, color)
        drawLine(color, Offset(w * 0.70f, h * 0.36f), Offset(w * 0.70f, h * 0.64f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.86f, h * 0.24f), Offset(w * 0.86f, h * 0.76f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
    }
}

/** 주 버튼: 흰 노면색. 노랑은 위험에만 쓰므로 버튼에 쓰지 않는다. */
@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, shape = SB.ButtonShape,
        modifier = modifier.heightIn(min = SB.ButtonMinHeight), contentPadding = PaddingValues(horizontal = 32.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SB.Paint, contentColor = SB.OnPaint, disabledContainerColor = SB.Raised, disabledContentColor = SB.Muted,
        ),
    ) { Text(text, style = SBType.Button.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) }
}

/** 선택지 버튼: 테두리 없는 평평한 면. [start] 면 글자를 왼쪽에 둔다 (긴 문장). */
@Composable
private fun SecondaryButton(text: String, modifier: Modifier = Modifier, start: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick, shape = SB.ButtonShape,
        modifier = modifier.heightIn(min = SB.ButtonMinHeight), contentPadding = PaddingValues(horizontal = 28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SB.Raised, contentColor = SB.Paint),
    ) {
        if (start) Row(Modifier.fillMaxWidth()) { Text(text, style = SBType.Button) } else Text(text, style = SBType.Button)
    }
}

/** 가벼운 동작 (시연, 음성 들어 보기): 테두리만. */
@Composable
private fun OutlineButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, shape = SB.ButtonShape,
        modifier = modifier.heightIn(min = 72.dp), contentPadding = PaddingValues(horizontal = 28.dp),
        border = BorderStroke(1.5.dp, SB.Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = SB.Paint),
    ) { Text(text, style = SBType.Button) }
}
