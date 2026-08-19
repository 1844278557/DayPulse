package com.example.daypulse.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.example.daypulse.security.SecureApiKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Voice input for DayPulse.
 *
 * We intentionally do not use Android SpeechRecognizer here. On some HarmonyOS/EMUI devices the
 * recognizer service can start but never return a usable result. Instead we record a short local
 * audio file and send it to SiliconFlow's speech-to-text endpoint using the same API key as AI.
 */
class SpeechInputController(
    context: Context,
    private val onListeningChange: (Boolean) -> Unit,
    private val onStatus: (String?) -> Unit,
    private val onPartialText: (String) -> Unit = {},
    private val onFinalText: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val keyStore = SecureApiKeyStore(appContext)
    private val transcriber = SiliconFlowTranscriber()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var recording = false
    private var transcribing = false
    private var destroyed = false
    private var startedAt = 0L

    fun start() {
        if (destroyed || recording || transcribing) return

        val target = File(appContext.cacheDir, "daypulse_voice_${System.currentTimeMillis()}.m4a")
        runCatching {
            createRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(64_000)
                setOutputFile(target.absolutePath)
                prepare()
                start()
            }
        }.onSuccess { ready ->
            recorder = ready
            audioFile = target
            recording = true
            startedAt = System.currentTimeMillis()
            onListeningChange(true)
            onPartialText("")
            onStatus("正在录音… 再点一次 AI 结束")
        }.onFailure {
            releaseRecorder()
            target.delete()
            onListeningChange(false)
            onStatus("无法启动麦克风录音，请检查录音权限或是否被其他应用占用")
        }
    }

    fun stopAndFinalize() {
        if (destroyed || !recording || transcribing) return
        recording = false
        onListeningChange(false)

        val duration = System.currentTimeMillis() - startedAt
        val file = audioFile
        val stopResult = runCatching { recorder?.stop() }
        releaseRecorder()

        if (duration < 500L || stopResult.isFailure || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            audioFile = null
            onStatus("录音太短，请点一下 AI 后说完再点一次结束")
            return
        }

        transcribing = true
        onStatus("正在把语音转成文字…")
        scope.launch {
            val key = withContext(Dispatchers.IO) { keyStore.load() }
            if (key.isNullOrBlank()) {
                transcribing = false
                file.delete()
                audioFile = null
                onStatus("请先在“我的”页面保存 SiliconFlow API Key")
                return@launch
            }

            transcriber.transcribe(key, file)
                .onSuccess { text ->
                    onStatus(null)
                    onFinalText(text)
                }
                .onFailure { error ->
                    onStatus(error.message ?: "语音转文字失败，请再试一次")
                }

            transcribing = false
            file.delete()
            audioFile = null
        }
    }

    fun isBusy(): Boolean = recording || transcribing

    fun cancel() {
        if (recording) runCatching { recorder?.stop() }
        recording = false
        transcribing = false
        onListeningChange(false)
        releaseRecorder()
        audioFile?.delete()
        audioFile = null
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        cancel()
        scope.cancel()
    }

    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext)
        else @Suppress("DEPRECATION") MediaRecorder()

    private fun releaseRecorder() {
        val current = recorder ?: return
        recorder = null
        runCatching { current.reset() }
        runCatching { current.release() }
    }
}
