package com.thatspot.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.tts.TextToSpeech
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.thatspot.app.voice.Ears
import com.thatspot.app.voice.Speaker
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.Mode as TtsMode

/**
 * Pleos TextToSpeech / SpeechToText SDK 3.3.1, ON_DEVICE 모드 (앱 등록 없이 차 안에서 처리, 네트워크 불필요).
 *
 * 확인된 것 (Pleos 문서 Introduction 페이지):
 *   TextToSpeech(applicationContext, Mode.ON_DEVICE), SpeechToText(applicationContext, Mode.ON_DEVICE),
 *   initialize → 리스너 등록 → setLocale → speak / request → stop → release 순서.
 * 리스너 이름 (Pleos SDK 소스를 확인한 공개 저장소 Mujin8623/chadak-pleos-app docs/feasibility.md 기준):
 *   TTS initialize(OnInitializeListener{onSuccess,onFailure}), addEventListener(EventListener{…, onDone, onStop, onError}),
 *       speak(text, isFinal)
 *   STT addListener(ResultListener{…, onUpdated(stt: String, completed: Boolean), onEndedRecognition, onError}), request()
 *
 * 리스너 인터페이스의 패키지 경로는 확인하지 못해서, 리스너는 메서드의 매개변수 타입에서 직접 만든다 ([Bridge]).
 * SDK 버전이 바뀌어도 이름이 같으면 동작하고, 맞지 않으면 안드로이드 기본 음성으로 넘어간다 (앱은 멈추지 않는다).
 */
class PleosSpeech(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    val speaker: Speaker = PleosSpeaker()
    val ears: Ears = PleosEars()

    fun release() { speaker.shutdown(); ears.shutdown() }

    // ------------------------------------------------------------------ 말하기

    private inner class PleosSpeaker : Speaker {
        override val name = "Pleos 음성 (TextToSpeech)"
        @Volatile private var state = 0 // 0 준비 중, 1 준비됨, -1 실패
        override val ready get() = state == 1
        private var tts: TextToSpeech? = null
        private val doneQueue = ArrayDeque<(() -> Unit)?>()

        init {
            runCatching {
                val t = TextToSpeech(app, TtsMode.ON_DEVICE)
                tts = t
                val initOk = Bridge.callWithListener(t, "initialize") { m, _ ->
                    when (m) {
                        "onSuccess" -> main.post { onReady(t) }
                        "onFailure", "onError" -> { Log.w(TAG, "TTS 초기화 실패 ($m)"); state = -1 }
                    }
                }
                if (!initOk) { Bridge.callNoArg(t, "initialize"); main.post { onReady(t) } }
            }.onFailure { Log.w(TAG, "Pleos TTS 를 만들지 못했어요 → 안드로이드 음성", it); state = -1 }
        }

        private fun onReady(t: TextToSpeech) {
            runCatching {
                Bridge.callWithListener(t, "addEventListener") { m, _ ->
                    if (m == "onDone" || m == "onStop" || m == "onError") main.post { doneQueue.removeFirstOrNull()?.invoke() }
                }
                t.setLocale(Locale.KOREA)
                state = 1
                Log.i(TAG, "Pleos TTS 준비됨 (ON_DEVICE, ko-KR)")
            }.onFailure { Log.w(TAG, "Pleos TTS 설정 실패", it); state = -1 }
        }

        override fun speak(text: String, onDone: (() -> Unit)?): Boolean {
            val t = tts ?: return false
            if (state != 1) return false
            return runCatching {
                val m = t.javaClass.methods.firstOrNull { it.name == "speak" && it.parameterTypes.firstOrNull() == String::class.java }
                    ?: error("speak 없음")
                doneQueue.addLast(onDone)
                m.invoke(t, *Bridge.args(m, text))
                true
            }.onFailure { Log.w(TAG, "Pleos TTS speak 실패", it); doneQueue.removeLastOrNull() }.getOrDefault(false)
        }

        override fun stop() { runCatching { tts?.stop() } }
        override fun shutdown() { runCatching { tts?.stop(); tts?.release() }; tts = null; state = -1 }
    }

    // ------------------------------------------------------------------ 듣기

    private inner class PleosEars : Ears {
        override val name = "Pleos 음성 인식 (SpeechToText)"
        @Volatile private var state = 0
        override val ready get() = state == 1
        private var stt: SpeechToText? = null
        private var pending: ((String?) -> Unit)? = null
        private var last: String? = null

        init {
            runCatching {
                val s = SpeechToText(app, SttMode.ON_DEVICE)
                stt = s
                // initialize() 가 리스너를 받는 버전과 받지 않는 버전 모두 처리
                if (!Bridge.callWithListener(s, "initialize") { m, _ -> if (m == "onFailure") state = -1 }) Bridge.callNoArg(s, "initialize")
                Bridge.callWithListener(s, "addListener") { m, a -> onResult(m, a) }
                s.setLocale(Locale.KOREA)
                state = 1
                Log.i(TAG, "Pleos STT 준비됨 (ON_DEVICE, ko-KR)")
            }.onFailure { Log.w(TAG, "Pleos STT 를 만들지 못했어요 → 안드로이드 음성 인식", it); state = -1 }
        }

        private fun onResult(method: String, args: Array<Any?>?) {
            when (method) {
                "onUpdated" -> {
                    val text = args?.firstOrNull() as? String
                    val completed = args?.getOrNull(1) as? Boolean ?: false
                    if (!text.isNullOrBlank()) last = text
                    if (completed) main.post { finish(last) }
                }
                "onEndedRecognition" -> main.postDelayed({ finish(last) }, 300)
                "onError" -> main.post { finish(null) }
            }
        }

        override fun listen(onText: (String?) -> Unit): Boolean {
            val s = stt ?: return false
            if (state != 1) return false
            pending = onText
            last = null
            return runCatching { s.request(); main.postDelayed({ finish(last) }, 10_000); true }
                .onFailure { Log.w(TAG, "STT request 실패", it); pending = null }.getOrDefault(false)
        }

        private fun finish(text: String?) {
            val cb = pending ?: return
            pending = null
            main.removeCallbacksAndMessages(null)
            runCatching { stt?.stop() }
            cb(text)
        }

        override fun cancel() { pending = null; runCatching { stt?.stop() } }
        override fun shutdown() { cancel(); runCatching { stt?.release() }; stt = null; state = -1 }
    }

    companion object { private const val TAG = "PleosSpeech" }
}

/** 리스너 인터페이스를 매개변수 타입에서 직접 만들어 호출한다. */
internal object Bridge {
    /** name(listener) 꼴의 메서드를 찾아, 모든 콜백을 [handler] 로 받는 리스너를 넣어 부른다. 없으면 false. */
    fun callWithListener(target: Any, name: String, handler: (String, Array<Any?>?) -> Unit): Boolean {
        val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0].isInterface }
            ?: return false
        val type = m.parameterTypes[0]
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, a ->
            when (method.name) {
                "equals" -> self === a?.getOrNull(0)
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "ThatSpotListener(${type.simpleName})"
                else -> { runCatching { handler(method.name, a) }; defaultFor(method.returnType) }
            }
        }
        m.invoke(target, proxy)
        return true
    }

    fun callNoArg(target: Any, name: String) {
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.invoke(target)
    }

    /** speak(text, isFinal = true, …) 처럼 첫 인자가 글자인 메서드의 나머지 인자를 채운다. */
    fun args(m: Method, text: String): Array<Any?> = m.parameterTypes.mapIndexed { i, t ->
        when {
            i == 0 -> text
            t == java.lang.Boolean.TYPE || t == java.lang.Boolean::class.java -> true
            t == Integer.TYPE -> 0
            t == java.lang.Long.TYPE -> 0L
            t == java.lang.Float.TYPE -> 1f
            else -> null
        }
    }.toTypedArray()

    fun defaultFor(t: Class<*>): Any? = when (t) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
}
