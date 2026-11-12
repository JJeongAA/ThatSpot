package com.thatspot.app

import android.content.Context
import android.util.Log
import com.thatspot.app.location.Geofencer
import com.thatspot.app.location.LocationSource
import com.thatspot.app.vehicle.VehicleExtras
import com.thatspot.app.voice.Ears
import com.thatspot.app.voice.Speaker

/**
 * Pleos SDK 묶음. usePleos=true 빌드에서만 src/pleos 의 PleosKitImpl 이 들어간다.
 * 각 항목은 null 일 수 있고, 그러면 안드로이드 기본 기능으로 대신한다.
 */
interface PleosKit {
    val extras: VehicleExtras
    val location: LocationSource?
    val geofencer: Geofencer?
    val speaker: Speaker?
    val ears: Ears?
    /** 내비 경로 재탐색 신호 (Pleos NaviHelper). 길을 놓친 곳을 찾는 데 쓴다 */
    val navi: RerouteSource?
    fun release()

    /** 내비가 경로를 다시 찾았을 때 알려 주는 곳. */
    interface RerouteSource {
        val name: String
        /** 연결돼서 신호를 받을 수 있으면 true */
        val ready: Boolean
        var onReroute: (() -> Unit)?
    }

    companion object {
        fun loadOrNull(context: Context): PleosKit? {
            if (!BuildConfig.USE_PLEOS) return null
            return runCatching {
                Class.forName("com.thatspot.pleos.PleosKitImpl").getConstructor(Context::class.java).newInstance(context) as PleosKit
            }.onFailure { Log.w("PleosKit", "Pleos SDK 연동을 만들지 못했어요", it) }.getOrNull()
        }
    }
}
