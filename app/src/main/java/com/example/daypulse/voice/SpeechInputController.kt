package com.example.daypulse.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Voice input backed by Android's SpeechRecognizer.
 *
 * Chinese OEM ROMs can expose a working RecognitionService even when
 * SpeechRecognizer.isRecognitionAvailable() is false or when the default recognizer selection
 * is broken. DayPulse therefore discovers the configured OEM service and every exported
 * RecognitionService, then explicitly tries them before falling back to the platform default.
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

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var finishing = false
    private var destroyed = false
    private var heardSpeech = false
    private var serviceResponded = false
    private var providerName = "系统语音服务"
    private var latestPartial = ""

    private var candidates: List<SpeechCandidate> = emptyList()
    private var candidateIndex = -1
    private var activeCandidate: SpeechCandidate? = null
    private var recognitionGeneration = 0
    private val attemptedCandidates = mutableListOf<String>()
    private var discoverySummary = ""

    private var startupTimeout: Runnable? = null
    private var resultTimeout: Runnable? = null

    private val isHuaweiDevice: Boolean
        get() = Build.MANUFACTURER.equals("HUAWEI", true) ||
            Build.BRAND.equals("HUAWEI", true) ||
            Build.DISPLAY.contains("HarmonyOS", true)

    private val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

    fun start() {
        if (destroyed || listening || finishing) return

        cancelTimers()
        releaseRecognizer(cancel = true)
        latestPartial = ""
        heardSpeech = false
        serviceResponded = false
        attemptedCandidates.clear()
        onPartialText("")

        val discovery = discoverCandidates()
        candidates = discovery.candidates
        discoverySummary = discovery.summary
        candidateIndex = -1
        activeCandidate = null

        if (candidates.isEmpty()) {
            onListeningChange(false)
            onStatus("本机没有发现可尝试的 Android 语音识别服务。$discoverySummary")
            return
        }

        listening = true
        finishing = false
        onListeningChange(true)
        startNextCandidate()
    }

    /**
     * Enumerate the device's actual RecognitionService components and the service selected by
     * Settings.Secure.voice_recognition_service. The secure setting is read-only here; DayPulse
     * never changes the user's system speech configuration.
     */
    private fun discoverCandidates(): Discovery {
        val resolved = runCatching {
            appContext.packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { info ->
                    val service = info.serviceInfo ?: return@mapNotNull null
                    ComponentName(service.packageName, service.name)
                }
                .distinctBy { it.flattenToString() }
        }.getOrDefault(emptyList())

        val configuredRaw = runCatching {
            Settings.Secure.getString(appContext.contentResolver, "voice_recognition_service")
        }.getOrNull()?.trim().orEmpty()
        val configured = configuredRaw
            .takeIf { it.isNotBlank() }
            ?.let(ComponentName::unflattenFromString)

        val ordered = mutableListOf<SpeechCandidate>()
        val seen = mutableSetOf<String>()

        fun add(component: ComponentName?, source: String) {
            val key = component?.flattenToString() ?: DEFAULT_CANDIDATE_KEY
            if (!seen.add(key)) return
            ordered += SpeechCandidate(
                component = component,
                label = component?.let(::componentLabel) ?: "Android 系统默认识别器",
                source = source
            )
        }

        if (isHuaweiDevice) {
            configured?.let { add(it, "系统当前配置") }
            resolved.filter(::looksLikeHuaweiSpeechService).forEach { add(it, "华为候选") }
            resolved.filterNot(::looksLikeHuaweiSpeechService).forEach { add(it, "其他已注册服务") }
            add(null, "Android 默认")
        } else {
            if (SpeechRecognizer.isRecognitionAvailable(appContext)) add(null, "Android 默认")
            configured?.let { add(it, "系统当前配置") }
            resolved.forEach { add(it, "已注册服务") }
            add(null, "Android 默认后备")
        }

        val configuredText = configured?.let(::componentLabel)
            ?: configuredRaw.ifBlank { "未设置/不可读取" }
        val resolvedText = if (resolved.isEmpty()) {
            "无"
        } else {
            resolved.joinToString("；") { componentLabel(it) }
        }
        val availability = SpeechRecognizer.isRecognitionAvailable(appContext)

        return Discovery(
            candidates = ordered,
            summary = "isRecognitionAvailable=$availability；系统配置=$configuredText；已注册 RecognitionService=$resolvedText"
        )
    }

    private fun looksLikeHuaweiSpeechService(component: ComponentName): Boolean {
        val text = "${component.packageName}/${component.className}".lowercase()
        return text.contains("huawei") ||
            text.contains("hivoice") ||
            text.contains("vassistant") ||
            text.contains("voiceassist")
    }

    private fun componentLabel(component: ComponentName): String {
        val shortClass = component.className.substringAfterLast('.')
        return "${component.packageName}/$shortClass"
    }

    private fun startNextCandidate(reason: String? = null) {
        cancelStartupTimeout()
        releaseRecognizer(cancel = true)

        while (++candidateIndex < candidates.size) {
            val candidate = candidates[candidateIndex]
            activeCandidate = candidate
            providerName = candidate.label
            attemptedCandidates += "${candidate.label}（${candidate.source}）"
            serviceResponded = false
            heardSpeech = false
            latestPartial = ""
            recognitionGeneration += 1
            val generation = recognitionGeneration

            val created = runCatching {
                if (candidate.component == null) {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(appContext, candidate.component)
                }
            }.getOrElse { error ->
                reason?.let { onStatus(it) }
                onStatus("$providerName 创建失败：${error.message ?: "未知错误"}，正在尝试下一个服务…")
                continue
            }

            recognizer = created
            created.setRecognitionListener(createRecognitionListener(generation))
            listening = true
            finishing = false
            onListeningChange(true)

            val prefix = reason?.takeIf { it.isNotBlank() }?.let { "$it\n" }.orEmpty()
            onStatus("${prefix}正在尝试 $providerName… 请继续说话")

            val started = runCatching {
                created.startListening(recognitionIntent)
            }.onFailure { error ->
                releaseRecognizer(cancel = true)
                onStatus("$providerName 启动失败：${error.message ?: "未知错误"}，正在尝试下一个服务…")
            }.isSuccess

            if (started) {
                scheduleStartupTimeout(generation, candidate)
                return
            }
        }

        listening = false
        finishing = false
        activeCandidate = null
        onListeningChange(false)
        val attempted = attemptedCandidates.joinToString("；").ifBlank { "无" }
        onStatus(
            "系统内置 Android 语音服务均未成功响应。\n" +
                "$discoverySummary\n" +
                "已尝试：$attempted"
        )
    }

    /**
     * A completely silent RecognitionService is the failure mode seen on the Huawei device.
     * Six seconds here is only a startup probe: once any RecognitionListener callback arrives,
     * the timeout is cancelled and normal speech recognition continues without a forced limit.
     */
    private fun scheduleStartupTimeout(generation: Int, candidate: SpeechCandidate) {
        cancelStartupTimeout()
        val task = Runnable {
            if (
                generation == recognitionGeneration &&
                listening &&
                !finishing &&
                !serviceResponded &&
                activeCandidate == candidate
            ) {
                val failedName = candidate.label
                releaseRecognizer(cancel = true)
                startNextCandidate("$failedName 启动 6 秒后仍没有任何 RecognitionListener 回调，已切换服务。")
            }
        }
        startupTimeout = task
        handler.postDelayed(task, STARTUP_PROBE_MS)
    }

    private fun markServiceResponded() {
        if (!serviceResponded) {
            serviceResponded = true
            cancelStartupTimeout()
        }
    }

    fun stopAndFinalize() {
        if (destroyed || !listening || finishing) return
        cancelStartupTimeout()
        listening = false
        finishing = true
        onListeningChange(false)
        onStatus("正在等待 $providerName 返回识别结果…")

        runCatching { recognizer?.stopListening() }
            .onFailure {
                finishing = false
                onStatus("结束 $providerName 语音失败：${it.message ?: "未知错误"}")
                releaseRecognizer(cancel = true)
                return
            }

        scheduleResultTimeout()
    }

    fun isBusy(): Boolean = listening || finishing

    fun cancel() {
        listening = false
        finishing = false
        heardSpeech = false
        serviceResponded = false
        latestPartial = ""
        activeCandidate = null
        cancelTimers()
        onListeningChange(false)
        releaseRecognizer(cancel = true)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        cancel()
    }

    private fun scheduleResultTimeout() {
        cancelResultTimeout()
        val task = Runnable {
            if (!finishing) return@Runnable
            finishing = false
            onListeningChange(false)
            val partial = latestPartial.trim()
            releaseRecognizer(cancel = true)

            if (partial.isNotBlank()) {
                onStatus(null)
                onFinalText(partial)
                return@Runnable
            }

            val detail = when {
                !serviceResponded -> "$providerName 从启动到结束都没有返回任何 RecognitionListener 回调"
                heardSpeech -> "$providerName 已检测到说话声音，但没有返回识别文字"
                else -> "$providerName 有回调，但没有确认检测到说话声音，也没有返回文字"
            }
            onStatus("$detail。\n$discoverySummary")
        }
        resultTimeout = task
        handler.postDelayed(task, RESULT_WAIT_MS)
    }

    private fun releaseRecognizer(cancel: Boolean) {
        val current = recognizer ?: return
        recognizer = null
        if (cancel) runCatching { current.cancel() }
        runCatching { current.destroy() }
    }

    private fun createRecognitionListener(generation: Int): RecognitionListener =
        object : RecognitionListener {
            private fun current(): Boolean = generation == recognitionGeneration && recognizer != null

            override fun onReadyForSpeech(params: Bundle?) {
                if (!current()) return
                markServiceResponded()
                if (!finishing) {
                    onListeningChange(true)
                    onStatus("$providerName 已就绪，正在听… 再点一次 AI 结束")
                }
            }

            override fun onBeginningOfSpeech() {
                if (!current()) return
                markServiceResponded()
                heardSpeech = true
                onStatus("$providerName 已检测到声音… 再点一次 AI 结束")
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!current()) return
                markServiceResponded()
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                if (!current()) return
                markServiceResponded()
            }

            override fun onEndOfSpeech() {
                if (!current()) return
                markServiceResponded()
                listening = false
                finishing = true
                onListeningChange(false)
                onStatus("正在等待 $providerName 返回识别结果…")
                scheduleResultTimeout()
            }

            override fun onError(error: Int) {
                if (!current()) return
                markServiceResponded()
                cancelTimers()
                listening = false
                finishing = false
                onListeningChange(false)
                val failedName = providerName
                releaseRecognizer(cancel = false)

                if (shouldTryAnotherService(error) && candidateIndex + 1 < candidates.size) {
                    startNextCandidate("$failedName 返回 ${androidErrorName(error)}，正在尝试下一个系统识别服务。")
                } else {
                    onStatus(androidErrorText(error))
                }
            }

            override fun onResults(results: Bundle?) {
                if (!current()) return
                markServiceResponded()
                cancelTimers()
                listening = false
                finishing = false
                onListeningChange(false)
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                    .ifBlank { latestPartial.trim() }
                releaseRecognizer(cancel = false)

                if (text.isBlank()) {
                    onStatus("$providerName 返回了空结果，请再说一次")
                } else {
                    onStatus(null)
                    onFinalText(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!current()) return
                markServiceResponded()
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        latestPartial = it
                        onPartialText(it)
                    }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                if (!current()) return
                markServiceResponded()
            }
        }

    private fun shouldTryAnotherService(code: Int): Boolean = when (code) {
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> true
        else -> false
    }

    private fun androidErrorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限错误"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "无匹配结果"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
        SpeechRecognizer.ERROR_SERVER -> "服务端错误"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "服务已断开"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "语言不支持"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "语言模型不可用"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "请求过多"
        else -> "错误码 $code"
    }

    private fun androidErrorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "$providerName 报告录音错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "$providerName 网络连接失败"
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

    private fun cancelStartupTimeout() {
        startupTimeout?.let(handler::removeCallbacks)
        startupTimeout = null
    }

    private fun cancelResultTimeout() {
        resultTimeout?.let(handler::removeCallbacks)
        resultTimeout = null
    }

    private fun cancelTimers() {
        cancelStartupTimeout()
        cancelResultTimeout()
    }

    private data class SpeechCandidate(
        val component: ComponentName?,
        val label: String,
        val source: String
    )

    private data class Discovery(
        val candidates: List<SpeechCandidate>,
        val summary: String
    )

    private companion object {
        const val STARTUP_PROBE_MS = 6_000L
        const val RESULT_WAIT_MS = 12_000L
        const val DEFAULT_CANDIDATE_KEY = "__android_default__"
    }
}
