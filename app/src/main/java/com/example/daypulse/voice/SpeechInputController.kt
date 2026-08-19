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
    private val onPartialText: (String) -> Unit = {},
    private val onFinalText: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var sessionActive = false
    private var waitingForResult = false
    private var destroyed = false
    private var busyRetryCount = 0

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
    }

    fun start() {
        if (destroyed || sessionActive || waitingForResult) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onStatus("系统没有可用的语音识别服务，请检查系统语音组件")
            return
        }

        busyRetryCount = 0
        sessionActive = true
        waitingForResult = false
        onListeningChange(true)
        onStatus("正在听… 再点一次结束")
        createAndStart()
    }

    fun stopAndFinalize() {
        if (destroyed || !sessionActive || waitingForResult) return
        sessionActive = false
        waitingForResult = true
        onListeningChange(false)
        onStatus("正在识别…")

        runCatching { recognizer?.stopListening() }
            .onFailure {
                waitingForResult = false
                onStatus("结束录音失败，请再试一次")
                releaseRecognizer(cancel = true)
            }

        handler.postDelayed({
            if (waitingForResult) {
                waitingForResult = false
                onListeningChange(false)
                onStatus("语音识别超时，请重新点击 AI")
                releaseRecognizer(cancel = true)
            }
        }, 8_000L)
    }

    fun cancel() {
        sessionActive = false
        waitingForResult = false
        busyRetryCount = 0
        onListeningChange(false)
        handler.removeCallbacksAndMessages(null)
        releaseRecognizer(cancel = true)
    }

    fun destroy() {
        destroyed = true
        cancel()
    }

    fun isBusy(): Boolean = sessionActive || waitingForResult

    private fun createAndStart() {
        if (destroyed || !sessionActive || waitingForResult) return

        releaseRecognizer(cancel = true)
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(listener)
        }

        handler.postDelayed({
            if (!destroyed && sessionActive && !waitingForResult) {
                runCatching { recognizer?.startListening(intent) }
                    .onFailure {
                        sessionActive = false
                        onListeningChange(false)
                        onStatus("语音识别启动失败，请重新点击 AI")
                        releaseRecognizer(cancel = true)
                    }
            }
        }, 180L)
    }

    private fun releaseRecognizer(cancel: Boolean) {
        val current = recognizer ?: return
        recognizer = null
        if (cancel) runCatching { current.cancel() }
        runCatching { current.destroy() }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (sessionActive && !waitingForResult) {
                onListeningChange(true)
                onStatus("正在听… 再点一次结束")
            }
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (sessionActive) {
                sessionActive = false
                waitingForResult = true
                onListeningChange(false)
                onStatus("正在识别…")
            }
        }

        override fun onError(error: Int) {
            if (
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY &&
                busyRetryCount < 1 &&
                !destroyed
            ) {
                busyRetryCount += 1
                sessionActive = true
                waitingForResult = false
                onListeningChange(true)
                onStatus("语音服务繁忙，正在重新连接…")
                releaseRecognizer(cancel = true)
                handler.postDelayed({ createAndStart() }, 900L)
                return
            }

            sessionActive = false
            waitingForResult = false
            onListeningChange(false)
            handler.removeCallbacksAndMessages(null)
            releaseRecognizer(cancel = false)
            onStatus(errorText(error))
        }

        override fun onResults(results: Bundle?) {
            sessionActive = false
            waitingForResult = false
            busyRetryCount = 0
            onListeningChange(false)
            handler.removeCallbacksAndMessages(null)

            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()

            releaseRecognizer(cancel = false)
            if (text.isNullOrBlank()) {
                onStatus("没听清，请重新点击 AI 再说一次")
            } else {
                onStatus(null)
                onFinalText(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(onPartialText)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音失败，请检查麦克风是否被其他 App 占用"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限，请在系统设置中允许录音"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统语音服务网络不可用"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请重新点击 AI 再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "系统语音服务仍然繁忙，请稍后重试"
        SpeechRecognizer.ERROR_CLIENT -> "语音识别已中断，请重新点击 AI"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到说话声音"
        SpeechRecognizer.ERROR_SERVER -> "系统语音服务暂时不可用"
        else -> "语音识别失败（$code）"
    }
}
