package com.example.daypulse.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechInputController(
    context: Context,
    private val onListeningChange: (Boolean) -> Unit,
    private val onStatus: (String?) -> Unit,
    private val onPartialText: (String) -> Unit = {},
    private val onFinalText: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var finishing = false
    private var destroyed = false
    private var heardSpeech = false
    private var providerName: String = "系统语音服务"

    private val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

    fun start() {
        if (destroyed || listening || finishing) return

        val services = appContext.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            0
        )
        if (services.isEmpty()) {
            onListeningChange(false)
            onStatus("本机没有发现可供第三方 App 使用的语音识别服务")
            return
        }

        providerName = services.firstOrNull()?.serviceInfo?.packageName ?: "系统语音服务"
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onListeningChange(false)
            onStatus("检测到了 $providerName，但 Android SpeechRecognizer 当前不可用")
            return
        }

        releaseRecognizer(cancel = true)
        heardSpeech = false
        listening = true
        finishing = false
        onListeningChange(true)
        onPartialText("")
        onStatus("正在听… 再点一次 AI 结束")

        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(listener)
        }

        runCatching { recognizer?.startListening(recognitionIntent) }
            .onFailure {
                listening = false
                onListeningChange(false)
                onStatus("无法启动 $providerName：${it.message ?: "未知错误"}")
                releaseRecognizer(cancel = true)
            }
    }

    fun stopAndFinalize() {
        if (destroyed || !listening || finishing) return
        listening = false
        finishing = true
        onListeningChange(false)
        onStatus("正在等待 $providerName 返回识别结果…")

        runCatching { recognizer?.stopListening() }
            .onFailure {
                finishing = false
                onStatus("结束语音失败：${it.message ?: "未知错误"}")
                releaseRecognizer(cancel = true)
                return
            }

        // This is intentionally much longer than the old 5-second timeout. Android does not
        // guarantee that a RecognitionService must return onResults within five seconds.
        handler.postDelayed({
            if (finishing) {
                finishing = false
                val detail = if (heardSpeech) {
                    "$providerName 已检测到说话声音，但 30 秒仍未返回识别结果"
                } else {
                    "$providerName 在 30 秒内既没有返回结果，也没有确认检测到说话声音"
                }
                onStatus("$detail。说明问题更可能在系统语音服务本身。")
                releaseRecognizer(cancel = true)
            }
        }, 30_000L)
    }

    fun isBusy(): Boolean = listening || finishing

    fun cancel() {
        listening = false
        finishing = false
        heardSpeech = false
        handler.removeCallbacksAndMessages(null)
        onListeningChange(false)
        releaseRecognizer(cancel = true)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        cancel()
    }

    private fun releaseRecognizer(cancel: Boolean) {
        val current = recognizer ?: return
        recognizer = null
        if (cancel) runCatching { current.cancel() }
        runCatching { current.destroy() }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!finishing) {
                onListeningChange(true)
                onStatus("$providerName 已就绪，正在听…")
            }
        }

        override fun onBeginningOfSpeech() {
            heardSpeech = true
            onStatus("已检测到声音… 再点一次 AI 结束")
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            listening = false
            finishing = true
            onListeningChange(false)
            onStatus("正在等待 $providerName 返回识别结果…")
        }

        override fun onError(error: Int) {
            listening = false
            finishing = false
            handler.removeCallbacksAndMessages(null)
            onListeningChange(false)
            releaseRecognizer(cancel = false)
            onStatus(errorText(error))
        }

        override fun onResults(results: Bundle?) {
            listening = false
            finishing = false
            handler.removeCallbacksAndMessages(null)
            onListeningChange(false)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            releaseRecognizer(cancel = false)
            if (text.isNullOrBlank()) onStatus("$providerName 返回了空结果，请再说一次")
            else {
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
        SpeechRecognizer.ERROR_AUDIO -> "$providerName 报告录音错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "$providerName 网络连接失败"
        SpeechRecognizer.ERROR_NO_MATCH -> "$providerName 听到了声音，但没有识别出文字"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "$providerName 正忙，请等上一段识别结束后再试"
        SpeechRecognizer.ERROR_CLIENT -> "$providerName 返回客户端错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "$providerName 没有检测到说话声音"
        SpeechRecognizer.ERROR_SERVER -> "$providerName 服务端错误"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "$providerName 已断开连接"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "$providerName 不支持中文识别"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "$providerName 支持中文，但当前中文模型不可用"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "$providerName 请求过于频繁"
        else -> "$providerName 识别失败（错误码 $code）"
    }
}
