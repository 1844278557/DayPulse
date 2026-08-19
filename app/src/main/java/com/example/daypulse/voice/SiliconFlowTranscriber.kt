package com.example.daypulse.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class SiliconFlowTranscriber {
    suspend fun transcribe(apiKey: String, audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "请先在“我的”页面保存 SiliconFlow API Key" }
            require(audioFile.exists() && audioFile.length() > 0L) { "录音文件为空，请重新录音" }

            val boundary = "----DayPulse${System.currentTimeMillis()}"
            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 45_000
                doOutput = true
                useCaches = false
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(connection.outputStream).use { out ->
                fun line(value: String = "") {
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.write("\r\n".toByteArray(Charsets.UTF_8))
                }

                line("--$boundary")
                line("Content-Disposition: form-data; name=\"model\"")
                line()
                line(MODEL)

                line("--$boundary")
                line("Content-Disposition: form-data; name=\"file\"; filename=\"daypulse.m4a\"")
                line("Content-Type: audio/mp4")
                line()
                BufferedInputStream(audioFile.inputStream()).use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        out.write(buffer, 0, count)
                    }
                }
                line()
                line("--$boundary--")
                out.flush()
            }

            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()

            if (code !in 200..299) {
                val message = runCatching { JSONObject(response).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: response.take(240)
                error("语音转文字失败 ($code)${if (message.isBlank()) "" else "：$message"}")
            }

            JSONObject(response).optString("text").trim().also {
                require(it.isNotBlank()) { "没有识别到有效语音，请再说一次" }
            }
        }
    }

    companion object {
        const val ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"
        const val MODEL = "FunAudioLLM/SenseVoiceSmall"
    }
}
