package com.thatspot.pleos

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.thatspot.app.PleosKit
import java.lang.reflect.Proxy

/**
 * Pleos NaviHelper 에서 '경로를 다시 찾음' 신호만 받는다. 갈림길이나 출구를 놓쳤다는 뜻이다.
 *
 * 호출 순서는 Pleos 공식 샘플(pleos-sample-navi-app, NaviHelper 3.0.5) 과 같다:
 *   NaviHelper(context) → initialize() → addListener(listener) … removeListener → release()
 *
 * NaviHelperEventListener 는 콜백이 25개가 넘는다. 여기서는 onReRouteEnded 하나만 쓰므로
 * java.lang.reflect.Proxy 로 리스너를 만들고 나머지 콜백은 무시한다.
 * 내비 앱이 경로 안내 중이 아니면 이 신호는 오지 않는다 (목적지 없이 다니는 길은 길을 놓쳤는지 알 수 없다).
 */
class PleosNavi(private val context: Context) : PleosKit.RerouteSource, AutoCloseable {
    override val name = "Pleos 내비 (NaviHelper)"
    override var ready = false
        private set
    override var onReroute: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null

    private val listener: NaviHelperEventListener = Proxy.newProxyInstance(
        NaviHelperEventListener::class.java.classLoader,
        arrayOf(NaviHelperEventListener::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ThatSpotNaviListener"
            else -> {
                when (method.name) {
                    "onReRouteEnded" -> { Log.i(TAG, "경로 재탐색"); main.post { onReroute?.invoke() } }
                    "onError" -> Log.w(TAG, "NaviHelper 오류 ${args?.firstOrNull()}")
                }
                Bridge.defaultFor(method.returnType)
            }
        }
    } as NaviHelperEventListener

    init {
        runCatching {
            val h = NaviHelper(context)
            h.initialize()
            h.addListener(listener)
            helper = h
            ready = true
            Log.i(TAG, "NaviHelper 연결됨")
        }.onFailure { Log.w(TAG, "NaviHelper 를 연결하지 못했어요 (내비 앱이 없거나 권한이 없음)", it) }
    }

    override fun close() = release()

    fun release() {
        runCatching { helper?.removeListener(listener); helper?.release() }
        helper = null; ready = false
    }

    companion object { private const val TAG = "PleosNavi" }
}
