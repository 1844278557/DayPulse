package com.example.daypulse.ai

import com.example.daypulse.model.AiActionType
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
            require(command.isNotBlank()) { "请输入你的闹钟指令" }

            val now = ZonedDateTime.now()
            val systemPrompt = """
                你是 Android 闹钟 App 的指令解析器。只返回一个 JSON 对象，不要解释。
                当前本地时间：$now

                JSON 字段：
                action: CREATE 或 DELETE
                title: string。CREATE 是提醒名称；DELETE 是用户想删除的闹钟标题关键词，没有则空字符串
                scheduleType: ONCE / DAILY / WEEKLY / WORKDAY / INTERVAL；DELETE 未指定时 null
                date: ONCE 使用 YYYY-MM-DD，否则 null
                time: HH:mm；DELETE 若用户用时间筛选则填写，否则 null
                weekdays: WEEKLY 数组，1=周一 ... 7=周日，否则 []
                intervalMinutes: INTERVAL 的间隔分钟，否则 null
                startTime: INTERVAL 开始时间 HH:mm，否则 null
                endTime: INTERVAL 结束时间 HH:mm，否则 null
                sound: boolean
                vibration: boolean
                notification: boolean
                deleteAllMatches: DELETE 时，只有用户明确说“全部/所有/都删掉”才为 true，否则 false

                规则：
                - “删除/删掉/取消这个闹钟/移除提醒” => DELETE
                - “工作日/法定工作日” => WORKDAY
                - “每隔X分钟/小时” => INTERVAL
                - “每天” => DAILY
                - 指定星期 => WEEKLY
                - 明天/具体日期/一次性 => ONCE
                - CREATE 如果没指定提醒方式，默认 sound=true,vibration=true,notification=true
                - DELETE 只提取筛选条件，不要发明标题、时间或类型
                - 时间统一为24小时制
                - 不要返回数据库 ID
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
            if (responseCode !in 200..299) error("硅基流动请求失败 ($responseCode)：${responseText.take(300)}")

            val content = JSONObject(responseText).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content")
                .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val json = JSONObject(content)
            val action = runCatching { AiActionType.valueOf(json.optString("action", "CREATE").uppercase()) }.getOrDefault(AiActionType.CREATE)
            val scheduleType = json.optNullableString("scheduleType")?.let { runCatching { ScheduleType.valueOf(it.uppercase()) }.getOrNull() }
            val weekdaysJson = json.optJSONArray("weekdays")
            val weekdays = buildList {
                if (weekdaysJson != null) for (i in 0 until weekdaysJson.length()) add(weekdaysJson.optInt(i)).also { }
            }.filter { it in 1..7 }

            AiAlarmDraft(
                action = action,
                title = json.optString("title", "").trim(),
                scheduleType = scheduleType,
                date = json.optNullableString("date"),
                time = json.optNullableString("time"),
                weekdays = weekdays,
                intervalMinutes = json.optNullableInt("intervalMinutes"),
                startTime = json.optNullableString("startTime"),
                endTime = json.optNullableString("endTime"),
                sound = json.optBoolean("sound", true),
                vibration = json.optBoolean("vibration", true),
                notification = json.optBoolean("notification", true),
                deleteAllMatches = json.optBoolean("deleteAllMatches", false)
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
