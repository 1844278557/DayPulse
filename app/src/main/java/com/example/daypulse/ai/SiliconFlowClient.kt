package com.example.daypulse.ai

import com.example.daypulse.model.AiAlarmDraft
import com.example.daypulse.model.ScheduleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime

class SiliconFlowClient {
    suspend fun parseAlarm(apiKey: String, command: String): Result<AiAlarmDraft> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "请先在设置中保存 API Key" }
            require(command.isNotBlank()) { "请输入你想设置的提醒" }

            val now = ZonedDateTime.now()
            val systemPrompt = """
                你是 Android 闹钟和习惯打卡 App 的指令解析器。只返回一个 JSON 对象，不要解释。
                当前本地时间：$now

                JSON 字段：
                title: string，简短任务名
                scheduleType: 只能是 ONCE / DAILY / WEEKLY / WORKDAY / INTERVAL
                date: ONCE 时使用 YYYY-MM-DD，其他情况 null
                time: HH:mm；ONCE/DAILY/WEEKLY/WORKDAY 使用
                weekdays: WEEKLY 时数组，1=周一 ... 7=周日，否则 []
                intervalMinutes: INTERVAL 时整数，否则 null
                startTime: INTERVAL 每日开始时间 HH:mm，默认 09:00
                endTime: INTERVAL 每日结束时间 HH:mm，默认 22:00
                sound: boolean
                vibration: boolean
                notification: boolean

                规则：
                - “工作日/法定工作日” => WORKDAY
                - “每隔X分钟/小时” => INTERVAL
                - “每天” => DAILY
                - 指定星期 => WEEKLY
                - 明天/具体日期/一次性 => ONCE
                - 如果用户没指定提醒方式，默认 sound=true,vibration=true,notification=true
                - 时间必须转换成24小时制。
            """.trimIndent()

            val body = JSONObject().apply {
                put("model", MODEL)
                put("temperature", 0.1)
                put("max_tokens", 900)
                put("enable_thinking", false)
                put("response_format", JSONObject().put("type", "json_object"))
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", command))
                })
            }

            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                error("硅基流动请求失败 ($responseCode)：${responseText.take(300)}")
            }

            val root = JSONObject(responseText)
            val content = root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val json = JSONObject(content)
            val scheduleType = ScheduleType.valueOf(json.getString("scheduleType").uppercase())
            val weekdaysJson = json.optJSONArray("weekdays")
            val weekdays = buildList {
                if (weekdaysJson != null) {
                    for (i in 0 until weekdaysJson.length()) add(weekdaysJson.getInt(i))
                }
            }

            AiAlarmDraft(
                title = json.optString("title", "AI 提醒").ifBlank { "AI 提醒" },
                scheduleType = scheduleType,
                date = json.optNullableString("date"),
                time = json.optNullableString("time"),
                weekdays = weekdays,
                intervalMinutes = json.optNullableInt("intervalMinutes"),
                startTime = json.optNullableString("startTime"),
                endTime = json.optNullableString("endTime"),
                sound = json.optBoolean("sound", true),
                vibration = json.optBoolean("vibration", true),
                notification = json.optBoolean("notification", true)
            )
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key).takeIf { it > 0 }
    }

    companion object {
        const val ENDPOINT = "https://api.siliconflow.cn/v1/chat/completions"
        const val MODEL = "deepseek-ai/DeepSeek-V3.2"
    }
}
