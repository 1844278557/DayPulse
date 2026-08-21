package com.example.daypulse.voice

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Records microphone input directly.
 *
 * This bypasses Android SpeechRecognizer because some HarmonyOS devices expose
 * a non-functional RecognitionService. The resulting audio will be passed to
 * the ASR backend in the next step.
 */
class AudioRecorderController(
    private val context: Context,
    private val onState: (Boolean) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start(): File? {
        if (recorder != null) return outputFile

        val file = File(context.cacheDir, "daypulse_voice_${System.currentTimeMillis()}.m4a")
        val r = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        recorder = r
        outputFile = file
        onState(true)
        onStatus("正在听…")
        return file
    }

    fun stop(): File? {
        val file = outputFile
        runCatching {
            recorder?.stop()
        }
        runCatching {
            recorder?.release()
        }
        recorder = null
        outputFile = null
        onState(false)
        onStatus("正在识别…")
        return file
    }

    fun cancel() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        outputFile?.delete()
        outputFile = null
        onState(false)
    }
}
