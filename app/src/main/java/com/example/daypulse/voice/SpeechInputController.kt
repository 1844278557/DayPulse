package com.example.daypulse.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechInputController(
    private val context: Context,
    private val onListeningChange: (Boolean) -> Unit,
    private val onStatus: (String?) -> Unit,
    private val onText: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var destroyed = false
    private var busyRetryCount = 0

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

    private val listener: RecognitionListener by lazy {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                active = true
                onListeningChange(true)
                onStatus("正在听…")
            }

            override fun onBeginningOfSpeech() {
                active = true
                onListeningChange(true)
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                onListeningChange(false)
                onStatus("正在识别…")
            }

            override fun onError(error: Int) {
                active = false
                onListeningChange(false)
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && busyRetryCount < 1) {
                    busyRetryCount += 1
                    onStatus("语音服务正在重置…")
                    recreateRecognizer()
                    handler.postDelayed({ start() }, 650)
                    return
                }
                busyRetryCount = 0
                onStatus(errorText(error))
            }

            override fun onResults(results: Bundle?) {
                active = false
                busyRetryCount = 0
                onListeningChange(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    onText(text)
                    onStatus(null)
                } else {
                    onStatus("没听清，请再说一次")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(onText)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
    }

    init {
        recreateRecognizer()
    }

    fun start() {
        if (destroyed) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onListeningChange(false)
            onStatus("这台手机没有可用的系统语音识别服务")
            return
        }
        if (active) {
            onStatus("正在听，请说完后再试")
            return
        }
        active = true
        onListeningChange(true)
        onStatus("正在准备语音识别…")
        handler.postDelayed({
            if (!destroyed && active) {
                runCatching { recognizer?.startListening(intent) }
                    .onFailure {
                        active = false
                        onListeningChange(false)
                        onStatus("语音识别启动失败，请再试一次")
                    }
            }
        }, 180)
    }

    fun stop() {
        active = false
        onListeningChange(false)
        runCatching { recognizer?.stopListening() }
    }

    fun destroy() {
        destroyed = true
        active = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun recreateRecognizer() {
        if (destroyed) return
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音失败，请检查麦克风"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音服务网络不可用"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音服务仍然繁忙，请稍后再试"
        SpeechRecognizer.ERROR_CLIENT -> "语音识别已停止"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到说话声音"
        else -> "语音识别失败（$code）"
    }
}
