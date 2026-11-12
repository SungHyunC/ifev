package com.ifev.app.vehicle

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import java.util.Locale
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * Speaks through the Pleos TextToSpeech SDK in on-device mode (no server, no app registration). If
 * the car's speech service is missing, falls back to Android's own engine so the moment still lands.
 */
class Voice(context: Context, private val log: (String) -> Unit) {
    private val app = context.applicationContext
    @Volatile private var pleos: TextToSpeech? = null
    @Volatile private var android: AndroidTts? = null
    @Volatile private var started = false
    private val pending = ArrayDeque<String>()
    @Volatile private var lastLine: String? = null
    /** Bumped by [stop]; an engine that finishes starting after a stop is released, not used. */
    @Volatile private var generation = 0

    /** Which engine is speaking, for the screen. */
    @Volatile var engine: String = "준비 중"
        private set

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val gen = generation
        runCatching {
            val tts = TextToSpeech(app, Mode.ON_DEVICE)
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    if (gen != generation) { runCatching { tts.release() }; return }
                    runCatching { tts.setLocale(Locale.KOREA) }  // "ko-KR": the engine rejects a bare "ko"
                    tts.addEventListener(object : EventListener {
                        override fun onStart() = Unit
                        override fun onReady() = Unit
                        override fun onDone() = Unit
                        override fun onStop() = Unit
                        override fun onUpdatedRms(rms: Double) = Unit
                        override fun onError(errorType: ErrorType) {
                            // The car's engine failed on this line: say it with Android's instead.
                            log("Pleos 음성 오류 $errorType, Android TTS로 다시 말함")
                            fallbackSay(lastLine ?: return)
                        }
                    })
                    pleos = tts
                    engine = "Pleos TextToSpeech"
                    flush()
                }

                override fun onFailure(errorType: ErrorType) {
                    log("Pleos 음성 합성 시작 실패: $errorType")
                    runCatching { tts.release() }
                    if (gen == generation) startAndroid()
                }
            })
        }.onFailure {
            log("Pleos 음성 합성 사용 불가: ${it.message}")
            startAndroid()
        }
    }

    /**
     * Releases both engines; [start] (which [say] callers such as the narrator call first) brings them
     * back. Called when the recording service is destroyed.
     */
    @Synchronized
    fun stop() {
        generation++
        started = false
        val p = pleos
        val a = android
        pleos = null
        android = null
        engine = "준비 중"
        runCatching { p?.release() }.onFailure { log("Pleos 음성 해제 실패: ${it.message}") }
        runCatching { a?.stop(); a?.shutdown() }.onFailure { log("Android TTS 해제 실패: ${it.message}") }
    }

    private fun startAndroid() {
        val gen = generation
        var tts: AndroidTts? = null
        tts = AndroidTts(app) init@{ status ->
            val t = tts
            if (gen != generation) { t?.shutdown(); return@init }
            if (status == AndroidTts.SUCCESS && t != null) {
                setKorean(t)
                android = t
                engine = "Android TTS (대체)"
                flush()
            } else {
                log("Android TTS 시작 실패: status=$status")
                engine = "음성 없음"
            }
        }
    }

    /** Korean if the engine has it; otherwise it would read Korean lines with another voice, or not at all. */
    private fun setKorean(tts: AndroidTts) {
        val result = runCatching { tts.setLanguage(Locale.KOREA) }.getOrDefault(AndroidTts.LANG_NOT_SUPPORTED)
        if (result == AndroidTts.LANG_MISSING_DATA || result == AndroidTts.LANG_NOT_SUPPORTED) {
            log("Android TTS에 한국어 음성 없음(결과 $result): 설정 > 텍스트 음성 변환에서 한국어 음성 데이터를 설치해야 함")
        }
    }

    fun say(text: String) {
        synchronized(pending) {
            if (pleos == null && android == null) {
                // Keep only the latest line until an engine is ready; a stale one is worse than none.
                pending.clear()
                pending.addLast(text)
                return
            }
        }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        log("음성: $text")
        lastLine = text
        val p = pleos
        if (p != null) {
            // A synchronous failure gets the same fallback as the asynchronous onError.
            runCatching { p.speak(text, true) }.onFailure {
                log("Pleos 음성 실패: ${it.message}, Android TTS로 다시 말함")
                fallbackSay(text)
            }
            return
        }
        android?.speak(text, AndroidTts.QUEUE_FLUSH, null, "ifev")
    }

    private fun fallbackSay(text: String) {
        val a = android
        if (a != null) {
            a.speak(text, AndroidTts.QUEUE_FLUSH, null, "ifev")
            return
        }
        val gen = generation
        var tts: AndroidTts? = null
        tts = AndroidTts(app) init@{ status ->
            val t = tts
            if (gen != generation) { t?.shutdown(); return@init }
            if (status == AndroidTts.SUCCESS && t != null) {
                setKorean(t)
                android = t
                t.speak(text, AndroidTts.QUEUE_FLUSH, null, "ifev")
            } else {
                log("Android TTS 시작 실패: status=$status")
            }
        }
    }

    private fun flush() {
        val next = synchronized(pending) { pending.removeLastOrNull().also { pending.clear() } } ?: return
        speakNow(next)
    }
}
