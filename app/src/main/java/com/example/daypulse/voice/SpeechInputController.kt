package com.example.daypulse.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.daypulse.security.SecureHuaweiMlKeyStore
import com.huawei.agconnect.config.AGConnectServicesConfig
import com.huawei.hms.mlsdk.asr.MLAsrConstants
import com.huawei.hms.mlsdk.asr.MLAsrListener
import com.huawei.hms.mlsdk.asr.MLAsrRecognizer
import com.huawei.hms.mlsdk.common.MLApplication

/**
 * DayPulse voice input.
 *
 * Huawei/HarmonyOS devices use Huawei ML Kit ASR directly. Other Android devices keep the
 * platform SpeechRecognizer fallback. This mirrors the provider split used by mature apps such
 * as Catroid instead of forcing Huawei phones through Android's default RecognitionService.
 */
class SpeechInputController(
    context: Context,
    private val onListeningChange: (Boolean) -> Unit,
    private val onStatus: (String?) -> Unit,
    private val onPartialText: (String) -> Unit = {},
    private val onFinalText: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val huaweiKeyStore = SecureHuaweiMlKeyStore(appContext)

    private var androidRecognizer: SpeechRecognizer? = null
    private var hmsRecognizer: MLAsrRecognizer? = null
    private var listening = false
    private var finishing = false
    private var destroyed = false
    private var heardSpeech = false
    private var providerName: String = "系统语音服务"
    private var usingHms = false
    private var latestHmsPartial = ""

    private val isHuaweiDevice: Boolean
        get() = Build.MANUFACTURER.equals("HUAWEI", true) ||
            Build.BRAND.equals("HUAWEI", true) ||
            Build.DISPLAY.contains("HarmonyOS", true)

    private val androidRecognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

    fun start() {
        if (destroyed || listening || finishing) return
        handler.removeCallbacksAndMessages(null)
        heardSpeech = false
        latestHmsPartial = ""
        onPartialText("")

        if (isHuaweiDevice) startHuaweiAsr() else startAndroidAsr()
    }

    private fun startHuaweiAsr() {
        usingHms = true
        providerName = "Huawei ML Kit"

        val storedKey = huaweiKeyStore.load()?.trim().orEmpty()
        val bundledKey = runCatching {
            AGConnectServicesConfig.fromContext(appContext).getString("client/api_key")
        }.getOrNull()?.trim().orEmpty()
        val apiKey = storedKey.ifBlank { bundledKey }

        if (apiKey.isBlank()) {
            usingHms = false
            onListeningChange(false)
            onStatus("已检测到华为/HarmonyOS。请先在“我的 → 华为语音”中填写 Huawei ML Kit API Key。")
            return
        }

        runCatching {
            MLApplication.initialize(appContext)
            MLApplication.getInstance().apiKey = apiKey
            releaseHuaweiRecognizer()
            hmsRecognizer = MLAsrRecognizer.createAsrRecognizer(appContext).apply {
                setAsrListener(hmsListener)
            }

            usingHms = true
            listening = true
            finishing = false
            onListeningChange(true)
            onStatus("Huawei ML Kit 正在启动语音识别…")

            val intent = Intent(MLAsrConstants.ACTION_HMS_ASR_SPEECH)
                .putExtra(MLAsrConstants.LANGUAGE, "zh-CN")
                .putExtra(MLAsrConstants.FEATURE, MLAsrConstants.FEATURE_WORDFLUX)
            hmsRecognizer?.startRecognizing(intent)
        }.onFailure {
            listening = false
            finishing = false
            onListeningChange(false)
            releaseHuaweiRecognizer()
            onStatus("Huawei ML Kit 启动失败：${it.message ?: "未知错误"}")
        }
    }

    private fun startAndroidAsr() {
        usingHms = false
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

        releaseAndroidRecognizer(cancel = true)
        listening = true
        finishing = false
        onListeningChange(true)
        onStatus("正在听… 再点一次 AI 结束")

        androidRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(androidListener)
        }

        runCatching { androidRecognizer?.startListening(androidRecognitionIntent) }
            .onFailure {
                listening = false
                onListeningChange(false)
                onStatus("无法启动 $providerName：${it.message ?: "未知错误"}")
                releaseAndroidRecognizer(cancel = true)
            }
    }

    fun stopAndFinalize() {
        if (destroyed || !listening || finishing) return
        listening = false
        finishing = true
        onListeningChange(false)

        if (usingHms) {
            val partial = latestHmsPartial.trim()
            if (partial.isNotBlank()) {
                finishing = false
                releaseHuaweiRecognizer()
                onStatus(null)
                onFinalText(partial)
            } else {
                onStatus("正在等待 Huawei ML Kit 返回识别结果…")
                handler.postDelayed({
                    if (finishing && usingHms) {
                        finishing = false
                        releaseHuaweiRecognizer()
                        onStatus("Huawei ML Kit 没有返回可用文字，请再说一次")
                    }
                }, 2_500L)
            }
            return
        }

        onStatus("正在等待 $providerName 返回识别结果…")
        runCatching { androidRecognizer?.stopListening() }
            .onFailure {
                finishing = false
                onStatus("结束语音失败：${it.message ?: "未知错误"}")
                releaseAndroidRecognizer(cancel = true)
                return
            }

        handler.postDelayed({
            if (finishing && !usingHms) {
                finishing = false
                val detail = if (heardSpeech) {
                    "$providerName 已检测到说话声音，但 30 秒仍未返回识别结果"
                } else {
                    "$providerName 在 30 秒内既没有返回结果，也没有确认检测到说话声音"
                }
                onStatus(detail)
                releaseAndroidRecognizer(cancel = true)
            }
        }, 30_000L)
    }

    fun isBusy(): Boolean = listening || finishing

    fun cancel() {
        listening = false
        finishing = false
        heardSpeech = false
        latestHmsPartial = ""
        handler.removeCallbacksAndMessages(null)
        onListeningChange(false)
        releaseHuaweiRecognizer()
        releaseAndroidRecognizer(cancel = true)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        cancel()
    }

    private fun finishHuawei(text: String?) {
        listening = false
        finishing = false
        handler.removeCallbacksAndMessages(null)
        onListeningChange(false)
        val finalText = text?.trim().orEmpty().ifBlank { latestHmsPartial.trim() }
        releaseHuaweiRecognizer()
        if (finalText.isBlank()) onStatus("Huawei ML Kit 返回了空结果，请再说一次")
        else {
            onStatus(null)
            onFinalText(finalText)
        }
    }

    private fun releaseHuaweiRecognizer() {
        hmsRecognizer?.let { runCatching { it.destroy() } }
        hmsRecognizer = null
        usingHms = false
    }

    private fun releaseAndroidRecognizer(cancel: Boolean) {
        val current = androidRecognizer ?: return
        androidRecognizer = null
        if (cancel) runCatching { current.cancel() }
        runCatching { current.destroy() }
    }

    private val hmsListener = object : MLAsrListener {
        override fun onStartListening() {
            listening = true
            onListeningChange(true)
            onStatus("Huawei ML Kit 已就绪，正在听… 再点一次 AI 结束")
        }

        override fun onStartingOfSpeech() {
            heardSpeech = true
            onStatus("Huawei ML Kit 已检测到声音…")
        }

        override fun onVoiceDataReceived(data: ByteArray?, energy: Float, params: Bundle?) {
            if (!finishing && energy > 0f) heardSpeech = true
        }

        override fun onRecognizingResults(partialResults: Bundle?) {
            val text = partialResults?.getString(MLAsrRecognizer.RESULTS_RECOGNIZING)?.trim().orEmpty()
            if (text.isNotBlank()) {
                latestHmsPartial = text
                onPartialText(text)
            }
        }

        override fun onResults(results: Bundle?) {
            finishHuawei(results?.getString(MLAsrRecognizer.RESULTS_RECOGNIZED))
        }

        override fun onError(error: Int, errorMessage: String?) {
            listening = false
            finishing = false
            handler.removeCallbacksAndMessages(null)
            onListeningChange(false)
            releaseHuaweiRecognizer()
            onStatus("Huawei ML Kit 识别失败（$error）：${errorMessage.orEmpty().ifBlank { "未知错误" }}")
        }

        override fun onState(state: Int, params: Bundle?) {
            if (state == MLAsrConstants.STATE_NO_SOUND_TIMES_EXCEED && latestHmsPartial.isBlank()) {
                listening = false
                finishing = false
                onListeningChange(false)
                releaseHuaweiRecognizer()
                onStatus("Huawei ML Kit 没有检测到说话声音")
            }
        }
    }

    private val androidListener = object : RecognitionListener {
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
            releaseAndroidRecognizer(cancel = false)
            onStatus(androidErrorText(error))
        }

        override fun onResults(results: Bundle?) {
            listening = false
            finishing = false
            handler.removeCallbacksAndMessages(null)
            onListeningChange(false)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            releaseAndroidRecognizer(cancel = false)
            if (text.isNullOrBlank()) onStatus("$providerName 返回了空结果，请再说一次")
            else {
                onStatus(null)
                onFinalText(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let(onPartialText)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun androidErrorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "$providerName 报告录音错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "$providerName 网络连接失败"
        SpeechRecognizer.ERROR_NO_MATCH -> "$providerName 听到了声音，但没有识别出文字"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "$providerName 正忙，请稍后再试"
        SpeechRecognizer.ERROR_CLIENT -> "$providerName 返回客户端错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "$providerName 没有检测到说话声音"
        SpeechRecognizer.ERROR_SERVER -> "$providerName 服务端错误"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "$providerName 已断开连接"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "$providerName 不支持中文识别"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "$providerName 中文模型当前不可用"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "$providerName 请求过于频繁"
        else -> "$providerName 识别失败（错误码 $code）"
    }
}
