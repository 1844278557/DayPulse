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
            require(command.isNotBlank()) { "请输入你想让 AI 执行的操作" }

            val now = ZonedDateTime.now()
            val systemPrompt = """
                你是 Android 闹钟和习惯打卡 App 的指令解析器。只返回一个 JSON 对象，不要解释。
                当前本地时间：$now

                JSON 字段：
                action: 只能是 CREATE / DELETE
                title: string。CREATE 时为新提醒名称；DELETE 时为用户想删除的提醒关键词，可为空字符串
                scheduleType: CREATE 时为 ONCE / DAILY / WEEKLY / WORKDAY / INTERVAL；DELETE 时如果用户明确提到类型则填，否则 null
                date: CREATE 的 ONCE 时 YYYY-MM-DD；DELETE 时若明确日期则填，否则 null
                time: HH:mm；CREATE 的 ONCE/DAILY/WEEKLY/WORKDAY 使用；DELETE 时如果用户明确时间则填，否则 null
                weekdays: CREATE 的 WEEKLY 时数组，1=周一 ... 7=周日；DELETE 时若明确星期则填写，否则 []
                intervalMinutes: CREATE 的 INTERVAL 时整数；DELETE 时若明确间隔则填写，否则 null
                startTime: CREATE 的 INTERVAL 开始时间 HH:mm；DELETE 时若明确则填写，否则 null
                endTime: CREATE 的 INTERVAL 结束时间 HH:mm；DELETE 时若明确则填写，否则 null
                sound: boolean
                vibration: boolean
                notification: boolean
                deleteAllMatches: boolean。仅当用户明确说“全部/所有/都删掉”时为 true，否则 false

                规则：
                - “删除/删掉/取消/移除某个闹钟” => DELETE
                - “工作日/法定工作日” => WORKDAY
                - “每隔X分钟/小时” => INTERVAL
                - “每天” => DAILY
                - 指定星期 => WEEKLY
                - 明天/具体日期/一次性 => ONCE
                - CREATE 如果用户没指定提醒方式，默认 sound=true,vibration=true,notification=true
                - DELETE 不要虚构不存在的提醒，不要返回数据库 ID，只返回匹配条件
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
            val action = AiActionType.valueOf(json.optString("action", "CREATE").uppercase())
            val scheduleType = json.optNullableString("scheduleType")?.let { ScheduleType.valueOf(it.uppercase()) }
            val weekdaysJson = json.optJSONArray("weekdays")
            val weekdays = buildList {
                if (weekdaysJson != null) {
                    for (i in 0 until weekdaysJson.length()) add(weekdaysJson.getInt(i))
                }
            }

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
