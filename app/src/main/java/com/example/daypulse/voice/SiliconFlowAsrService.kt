package com.example.daypulse.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Uploads a single WAV recording with the user's existing, locally stored SiliconFlow key. */
class SiliconFlowAsrService(
    private val endpoint: URL = URL("https://api.siliconflow.cn/v1/audio/transcriptions")
) {
    suspend fun transcribe(audio: File, apiKey: String): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "请先在「我的」页面填写硅基流动 API Key" }
        require(audio.isFile && audio.length() > 44L) { "录音文件为空，请重试" }
        require(audio.length() <= MAX_AUDIO_BYTES) { "录音过长，请缩短语音后重试" }

        val boundary = "DayPulse-${UUID.randomUUID()}"
        val modelPart = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"model\"\r\n\r\n" +
                "FunAudioLLM/SenseVoiceSmall\r\n"
        ).toByteArray(Charsets.UTF_8)
        val fileHeader = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n"
        ).toByteArray(Charsets.UTF_8)
        val ending = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val total = modelPart.size + fileHeader.size + audio.length() + ending.size

        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setFixedLengthStreamingMode(total)
            connection.outputStream.use { body ->
                body.write(modelPart)
                body.write(fileHeader)
                audio.inputStream().use { it.copyTo(body, bufferSize = 8_192) }
                body.write(ending)
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                // Do not expose server response bodies, which could contain uploaded text or secrets.
                val reason = when (status) {
                    400, 415, 422 -> "服务端不接受该音频格式（HTTP $status）"
                    401, 403 -> "SiliconFlow API Key 无效或没有语音模型访问权限（HTTP $status）"
                    413 -> "上传文件过大（HTTP 413）"
                    429 -> "语音识别请求过于频繁（HTTP 429）"
                    in 500..599 -> "语音识别服务暂时不可用（HTTP $status）"
                    else -> "语音识别请求失败（HTTP $status）"
                }
                error(reason)
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val text = runCatching { JSONObject(response).optString("text").trim() }
                .getOrElse { error("语音识别返回格式异常") }
            check(text.isNotBlank()) { "没有识别到文字，请重试" }
            text
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_AUDIO_BYTES = 50L * 1024L * 1024L
    }
}
