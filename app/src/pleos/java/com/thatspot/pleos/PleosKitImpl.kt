package com.thatspot.pleos

import android.content.Context
import com.thatspot.app.PleosKit
import com.thatspot.app.location.Geofencer
import com.thatspot.app.location.LocationSource
import com.thatspot.app.vehicle.VehicleExtras
import com.thatspot.app.voice.Ears
import com.thatspot.app.voice.Speaker

/**
 * usePleos=true 빌드에서 AppController 가 불러 쓰는 Pleos SDK 묶음.
 *
 * | 앱에서 하는 일                       | Pleos SDK                                   |
 * |--------------------------------------|---------------------------------------------|
 * | 브레이크를 밟았는지 (오탐 줄이기)    | Vehicle  Brake.getCurrentBrakePedalStatus    |
 * | 급히 핸들을 꺾었는지                 | Vehicle  SteeringWheel.getCurrentSteeringWheelAngle |
 * | 깜빡이 (회전은 회피가 아님)          | Vehicle  TurnSignal.getCurrentTurnSignalState |
 * | 급정거한 곳의 위치와 방향            | FusedLocation registerFusedLocationCallback |
 * | 위험 지점에 가까워짐                 | FusedLocation addProximityAlert (ENTER/EXIT) |
 * | 미리 말해 주기                       | TextToSpeech (ON_DEVICE, ko-KR)              |
 * | 주차 후 이유를 말로 남기기           | SpeechToText (ON_DEVICE, ko-KR)              |
 * | 길을 놓친 곳 (내비가 경로를 다시 찾음)| NaviHelper  NaviHelperEventListener.onReRouteEnded |
 */
class PleosKitImpl(context: Context) : PleosKit {
    private val fused = PleosFusedLocation(context)
    private val speech = PleosSpeech(context)

    private val vehicle = PleosVehicleExtras(context)
    /** useNavi=false 빌드에는 PleosNavi 가 없다. 그때는 길을 놓친 곳만 빠진다 */
    private val naviHelper: PleosKit.RerouteSource? = runCatching {
        Class.forName("com.thatspot.pleos.PleosNavi").getConstructor(Context::class.java).newInstance(context) as PleosKit.RerouteSource
    }.getOrNull()

    override val extras: VehicleExtras = vehicle
    override val location: LocationSource = fused
    override val geofencer: Geofencer = fused
    override val speaker: Speaker = speech.speaker
    override val ears: Ears = speech.ears
    override val navi: PleosKit.RerouteSource? = naviHelper

    override fun release() {
        vehicle.release()
        fused.release()
        speech.release()
        (naviHelper as? AutoCloseable)?.close()
    }
}
