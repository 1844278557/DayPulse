package com.example.daypulse.voice

import android.content.Context
import android.content.Intent
import android.os.Build
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
    private var active = false
    private var finishing = false
    private var destroyed = false
    private var busyRetryCount = 0

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
    }

    fun start() {
        if (destroyed || active) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onStatus("系统没有可用的语音识别服务，请确认已安装并启用系统语音服务")
            return
        }

        busyRetryCount = 0
        active = true
        finishing = false
        onListeningChange(true)
        onStatus("正在听… 松开后发送给 AI")
        startFreshRecognizer()
    }

    fun stopAndFinalize() {
        if (destroyed || !active || finishing) return
        finishing = true
        onListeningChange(false)
        onStatus("正在识别并发送…")
        runCatching { recognizer?.stopListening() }
            .onFailure {
                active = false
                finishing = false
                onStatus("语音结束失败，请再试一次")
                releaseRecognizer(cancel = true)
            }

        handler.postDelayed({
            if (finishing) {
                active = false
                finishing = false
                onListeningChange(false)
                onStatus("语音识别超时，请再按住 AI 重试")
                releaseRecognizer(cancel = true)
            }
        }, 5_000L)
    }

    fun cancel() {
        active = false
        finishing = false
        busyRetryCount = 0
        onListeningChange(false)
        handler.removeCallbacksAndMessages(null)
        releaseRecognizer(cancel = true)
    }

    fun destroy() {
        destroyed = true
        cancel()
    }

    private fun startFreshRecognizer() {
        if (destroyed || !active || finishing) return
        releaseRecognizer(cancel = true)
        recognizer = createFreshRecognizer().also { it.setRecognitionListener(listener) }
        runCatching { recognizer?.startListening(intent) }
            .onFailure {
                active = false
                finishing = false
                onListeningChange(false)
                onStatus("语音识别启动失败，请再按住 AI 重试")
                releaseRecognizer(cancel = true)
            }
    }

    private fun createFreshRecognizer(): SpeechRecognizer {
        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
    }

    private fun releaseRecognizer(cancel: Boolean) {
        val current = recognizer ?: return
        recognizer = null
        if (cancel) runCatching { current.cancel() }
        runCatching { current.destroy() }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (active && !finishing) {
                onListeningChange(true)
                onStatus("正在听… 松开后发送给 AI")
            }
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            onListeningChange(false)
            onStatus("正在识别并发送…")
        }

        override fun onError(error: Int) {
            if (
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY &&
                active && !finishing && busyRetryCount < 1
            ) {
                busyRetryCount += 1
                onStatus("语音服务繁忙，正在自动重试…")
                releaseRecognizer(cancel = true)
                handler.postDelayed({ startFreshRecognizer() }, 450L)
                return
            }

            active = false
            finishing = false
            onListeningChange(false)
            releaseRecognizer(cancel = false)
            onStatus(errorText(error))
        }

        override fun onResults(results: Bundle?) {
            active = false
            finishing = false
            busyRetryCount = 0
            onListeningChange(false)
            handler.removeCallbacksAndMessages(null)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            releaseRecognizer(cancel = false)
            if (text.isNullOrBlank()) {
                onStatus("没听清，请再按住 AI 说一次")
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
        SpeechRecognizer.ERROR_AUDIO -> "录音失败，请检查麦克风权限或是否被其他 App 占用"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限，请在系统设置中允许录音"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统语音服务网络不可用"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再按住 AI 说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "系统语音服务仍然繁忙，请稍后再按住 AI"
        SpeechRecognizer.ERROR_CLIENT -> "语音识别已取消，请重新按住 AI"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到说话声音"
        SpeechRecognizer.ERROR_SERVER -> "系统语音服务暂时不可用"
        else -> "语音识别失败（$code）"
    }
}
