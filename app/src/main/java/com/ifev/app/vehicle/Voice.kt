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

    /** Which engine is speaking, for the screen. */
    @Volatile var engine: String = "준비 중"
        private set

    @Synchronized
    fun start() {
        if (started) return
        started = true
        runCatching {
            val tts = TextToSpeech(app, Mode.ON_DEVICE)
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
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
                    startAndroid()
                }
            })
        }.onFailure {
            log("Pleos 음성 합성 사용 불가: ${it.message}")
            startAndroid()
        }
    }

    private fun startAndroid() {
        var tts: AndroidTts? = null
        tts = AndroidTts(app) { status ->
            if (status == AndroidTts.SUCCESS) {
                tts?.language = Locale.KOREAN
                android = tts
                engine = "Android TTS (대체)"
                flush()
            } else {
                engine = "음성 없음"
            }
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
            runCatching { p.speak(text, true) }.onFailure { log("음성 실패: ${it.message}") }
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
        var tts: AndroidTts? = null
        tts = AndroidTts(app) { status ->
            if (status == AndroidTts.SUCCESS) {
                tts?.language = Locale.KOREAN
                android = tts
                tts?.speak(text, AndroidTts.QUEUE_FLUSH, null, "ifev")
            }
        }
    }

    private fun flush() {
        val next = synchronized(pending) { pending.removeLastOrNull().also { pending.clear() } } ?: return
        speakNow(next)
    }
}
