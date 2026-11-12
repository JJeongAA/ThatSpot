package com.thatspot.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/** 말하기. Pleos TextToSpeech 와 안드로이드 TTS 가 같은 모양이다. */
interface Speaker {
    val name: String
    val ready: Boolean
    /** @return 소리로 나갈 예정이면 true */
    fun speak(text: String, onDone: (() -> Unit)? = null): Boolean
    fun stop()
    fun shutdown()
}

/** 한 번 듣기. 다 들으면 글자를, 못 들으면 null 을 준다. */
interface Ears {
    val name: String
    val ready: Boolean
    fun listen(onText: (String?) -> Unit): Boolean
    fun cancel()
    fun shutdown()
}

/**
 * 안드로이드 기본 TTS. 길 안내 음성 용도로 재생하고, 말하는 동안 다른 소리를 줄인다.
 * 초기화 전에 들어온 문장은 기다렸다가 말한다.
 */
class AndroidSpeaker(context: Context) : Speaker {
    override val name = "안드로이드 음성"
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
    private val pending = ArrayDeque<Pair<String, (() -> Unit)?>>()
    private val done = mutableMapOf<String, () -> Unit>()
    @Volatile private var state = 0 // 0 준비 중, 1 준비됨, -1 쓸 수 없음
    override val ready get() = state == 1
    val failed get() = state == -1

    private val tts = TextToSpeech(context.applicationContext) { s -> main.post { onInit(s) } }

    private fun onInit(s: Int) {
        if (s != TextToSpeech.SUCCESS) { state = -1; pending.clear(); return }
        val r = tts.setLanguage(Locale.KOREAN)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) { state = -1; pending.clear(); return }
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { main.post { finish(id) } }
            @Deprecated("API 21 이전")
            override fun onError(id: String?) { main.post { finish(id) } }
        })
        state = 1
        while (pending.isNotEmpty()) pending.removeFirst().let { (t, d) -> play(t, d) }
    }

    override fun speak(text: String, onDone: (() -> Unit)?): Boolean = when (state) {
        1 -> { play(text, onDone); true }
        0 -> { pending.addLast(text to onDone); true }
        else -> false
    }

    private fun play(text: String, onDone: (() -> Unit)?) {
        val id = "u${System.nanoTime()}"
        onDone?.let { done[id] = it }
        audio.requestAudioFocus(focus)
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id) != TextToSpeech.SUCCESS) finish(id)
    }

    private fun finish(id: String?) {
        audio.abandonAudioFocusRequest(focus)
        id?.let { done.remove(it)?.invoke() }
    }

    override fun stop() { tts.stop(); audio.abandonAudioFocusRequest(focus) }
    override fun shutdown() { tts.stop(); tts.shutdown() }
}

/** 안드로이드 기본 음성 인식. 8초 안에 못 들으면 null. */
class AndroidEars(private val context: Context) : Ears {
    override val name = "안드로이드 음성 인식"
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var pending: ((String?) -> Unit)? = null

    override val ready: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context) &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun listen(onText: (String?) -> Unit): Boolean {
        cancel()
        if (!ready) return false
        pending = onText
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) =
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            override fun onError(error: Int) { Log.w("AndroidEars", "인식 오류 $error"); finish(null) }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR"),
        )
        main.postDelayed({ finish(null) }, 8_000)
        return true
    }

    private fun finish(text: String?) {
        val cb = pending ?: return
        pending = null
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        cb(text)
    }

    override fun cancel() { pending = null; main.removeCallbacksAndMessages(null); runCatching { recognizer?.cancel() } }
    override fun shutdown() { cancel(); runCatching { recognizer?.destroy() }; recognizer = null }
}
