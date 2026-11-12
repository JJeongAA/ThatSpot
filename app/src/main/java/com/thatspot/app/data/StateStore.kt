package com.thatspot.app.data

import android.content.Context
import android.util.Log
import com.thatspot.core.Cause
import com.thatspot.core.EngineState
import com.thatspot.core.EventKind
import com.thatspot.core.HardEvent
import com.thatspot.core.Pass
import com.thatspot.core.PassOutcome
import com.thatspot.core.Spot
import com.thatspot.core.SpotStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 기기 안 JSON 저장. 서버로 보내지 않는다.
 * 저장하는 것: 급정거 순간(시각·위치·속도), 지점(위치·방향·이유·메모·횟수), 지나간 기록.
 * 저장하지 않는 것: 주행 경로 전체, 음성. 경로 스케치는 마지막 주행 것만 메모리에 둔다.
 */
class StateStore(context: Context) {
    private val file = File(context.filesDir, "thatspot_state.json")

    fun load(): EngineState {
        val s = EngineState()
        if (!file.exists()) return s
        runCatching {
            val o = JSONObject(file.readText())
            s.tripCounter = o.optInt("tripCounter")
            s.nextId = o.optLong("nextId", 1)
            s.nextSpotNumber = o.optInt("nextSpotNumber", 1)
            o.optJSONArray("asked")?.let { a -> for (i in 0 until a.length()) s.askedEventIds += a.getLong(i) }
            o.optJSONArray("askedKeep")?.let { a -> for (i in 0 until a.length()) s.askedKeepAlertIds += a.getLong(i) }
            o.optJSONArray("events")?.forEachObj { j ->
                s.events += HardEvent(
                    id = j.getLong("id"), timeMs = j.getLong("t"), tripId = j.getInt("trip"),
                    lat = j.optDoubleOrNull("lat"), lon = j.optDoubleOrNull("lon"), headingDeg = j.optDoubleOrNull("h"),
                    fromKmh = j.getDouble("from"), toKmh = j.getDouble("to"), peakG = j.getDouble("g"),
                    kind = EventKind.valueOf(j.getString("kind")), alongM = j.optDouble("along", 0.0),
                    spotId = if (j.has("spot")) j.getLong("spot") else null,
                )
            }
            o.optJSONArray("spots")?.forEachObj { j ->
                val sp = Spot(j.getLong("id"), j.getInt("n"), j.getDouble("lat"), j.getDouble("lon"), j.optDoubleOrNull("h"), j.getLong("created"))
                j.getJSONArray("events").let { a -> for (i in 0 until a.length()) sp.eventIds += a.getLong(i) }
                j.getJSONArray("trips").let { a -> for (i in 0 until a.length()) sp.tripIds += a.getInt(i) }
                sp.lastEventMs = j.getLong("last")
                sp.cause = j.optString("cause").ifEmpty { null }?.let { runCatching { Cause.valueOf(it) }.getOrNull() }
                sp.memo = j.optString("memo").ifEmpty { null }
                sp.status = SpotStatus.valueOf(j.getString("status"))
                sp.passes = j.optInt("passes"); sp.hardPasses = j.optInt("hardPasses")
                sp.smoothStreak = j.optInt("streak"); sp.warnings = j.optInt("warnings")
                sp.lastWarnMs = j.optLong("lastWarn", Long.MIN_VALUE / 2)
                sp.lost = j.optBoolean("lost", false)
                s.spots[sp.id] = sp
            }
            o.optJSONArray("passes")?.forEachObj { j ->
                s.passes += Pass(j.getLong("spot"), j.getLong("t"), j.getInt("trip"), j.getBoolean("warned"), PassOutcome.valueOf(j.getString("out")))
            }
        }.onFailure { Log.w(TAG, "저장 파일을 읽지 못해 새로 시작합니다", it) }
        return s
    }

    fun save(s: EngineState) {
        val o = JSONObject()
            .put("tripCounter", s.tripCounter).put("nextId", s.nextId).put("nextSpotNumber", s.nextSpotNumber)
            .put("asked", JSONArray(s.askedEventIds.toList()))
            .put("askedKeep", JSONArray(s.askedKeepAlertIds.toList()))
            .put("events", JSONArray().apply {
                s.events.forEach { e ->
                    put(JSONObject().put("id", e.id).put("t", e.timeMs).put("trip", e.tripId)
                        .putOpt("lat", e.lat).putOpt("lon", e.lon).putOpt("h", e.headingDeg)
                        .put("from", e.fromKmh).put("to", e.toKmh).put("g", e.peakG).put("kind", e.kind.name)
                        .put("along", e.alongM).putOpt("spot", e.spotId))
                }
            })
            .put("spots", JSONArray().apply {
                s.spots.values.forEach { sp ->
                    put(JSONObject().put("id", sp.id).put("n", sp.number).put("lat", sp.lat).put("lon", sp.lon).putOpt("h", sp.headingDeg)
                        .put("created", sp.createdMs).put("events", JSONArray(sp.eventIds)).put("trips", JSONArray(sp.tripIds.toList()))
                        .put("last", sp.lastEventMs).put("cause", sp.cause?.name ?: "").put("memo", sp.memo ?: "")
                        .put("status", sp.status.name).put("passes", sp.passes).put("hardPasses", sp.hardPasses)
                        .put("streak", sp.smoothStreak).put("warnings", sp.warnings).put("lastWarn", sp.lastWarnMs).put("lost", sp.lost))
                }
            })
            .put("passes", JSONArray().apply {
                s.passes.takeLast(500).forEach { p ->
                    put(JSONObject().put("spot", p.spotId).put("t", p.timeMs).put("trip", p.tripId).put("warned", p.warned).put("out", p.outcome.name))
                }
            })
        // 쓰다가 꺼져도 깨지지 않게: 임시 파일에 쓰고 바꿔 끼운다
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun clear() { file.delete() }

    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
    private inline fun JSONArray.forEachObj(f: (JSONObject) -> Unit) { for (i in 0 until length()) f(getJSONObject(i)) }

    companion object { private const val TAG = "StateStore" }
}
