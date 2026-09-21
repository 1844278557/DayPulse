package com.example.daypulse.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.daypulse.security.SecureApiKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * DayPulse two-tap voice controller. The microphone is captured directly by AudioRecord:
 * it never binds to the non-responsive Huawei FakeRecognitionService or requires HMS keys.
 * Only the temporary recording and the user's existing SiliconFlow API key are uploaded to ASR.
 */
class SpeechInputController(
    context: Context,
    private val onListeningChange: (Boolean) -> Unit,
    private val onStatus: (String?) -> Unit,
    private val onPartialText: (String) -> Unit = {},
    private val onFinalText: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val recorder = AudioRecorderController(appContext)
    private val keyStore = SecureApiKeyStore(appContext)
    private val asr = SiliconFlowAsrService()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var task: Job? = null
    private var recording = false
    private var processing = false
    private var destroyed = false
    private var generation = 0
    private var activeApiKey: String? = null
    private var recordingDeadline: Runnable? = null

    fun start() {
        if (destroyed || recording || processing) return
        val requestId = ++generation
        processing = true
        onPartialText("")
        onListeningChange(false)
        onStatus("正在检查语音识别设置…")
        task = scope.launch {
            try {
                val key = withContext(Dispatchers.IO) { keyStore.load()?.trim().orEmpty() }
                if (requestId != generation || destroyed) return@launch
                if (key.isBlank()) {
                    onStatus("请先在「我的」页面填写硅基流动 API Key，再使用 AI 语音")
                    return@launch
                }
                activeApiKey = key
                withContext(Dispatchers.IO) { recorder.start() }
                if (requestId != generation || destroyed) {
                    withContext(Dispatchers.IO) { recorder.cancel() }
                    return@launch
                }
                recording = true
                processing = false
                onListeningChange(true)
                onStatus("正在录音… 再点一次 AI 结束（最长 60 秒）")
                val deadline = Runnable {
                    if (requestId == generation && recording && !destroyed) stopAndFinalize()
                }
                recordingDeadline = deadline
                handler.postDelayed(deadline, 60_000L)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (requestId == generation && !destroyed) {
                    onListeningChange(false)
                    onStatus("无法开始录音：${error.message ?: "请检查麦克风权限"}")
                }
                withContext(Dispatchers.IO) { recorder.cancel() }
            } finally {
                if (requestId == generation && !recording) processing = false
            }
        }
    }

    fun stopAndFinalize() {
        if (destroyed || !recording || processing) return
        val requestId = generation
        clearDeadline()
        recording = false
        processing = true
        onListeningChange(false)
        onStatus("正在上传录音并识别文字…")
        val key = activeApiKey.orEmpty()
        activeApiKey = null
        task = scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val audio = recorder.stop()
                    try {
                        asr.transcribe(audio, key)
                    } finally {
                        audio.delete()
                    }
                }
                if (requestId == generation && !destroyed) {
                    onStatus(null)
                    onFinalText(text)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (requestId == generation && !destroyed) {
                    val message = if (error is IOException) "网络连接失败，请检查网络后重试" else
                        error.message ?: "语音识别失败，请重试"
                    onStatus(message)
                }
            } finally {
                if (requestId == generation) processing = false
            }
        }
    }

    fun isBusy(): Boolean = recording || processing

    fun cancel() {
        ++generation
        clearDeadline()
        task?.cancel()
        task = null
        recording = false
        processing = false
        activeApiKey = null
        onListeningChange(false)
        // Cancelling AudioRecord is safe even when its capture worker is still unwinding.
        scope.launch(Dispatchers.IO) { recorder.cancel() }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        cancel()
        scope.cancel()
    }

    private fun clearDeadline() {
        recordingDeadline?.let(handler::removeCallbacks)
        recordingDeadline = null
    }
}
