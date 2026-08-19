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
            require(apiKey.isNotBlank()) { "请先在我的页面保存 API Key" }
            require(command.isNotBlank()) { "没有识别到语音内容" }

            val now = ZonedDateTime.now()
            val systemPrompt = """
                你是 DayPulse 的语音 AI 助手。用户可能只是聊天，也可能要求操作闹钟或进行每日打卡。
                当前本地时间：$now

                只返回一个 JSON 对象，不要 Markdown，不要额外解释。

                顶层字段：
                action: GENERAL / CREATE / UPDATE / DELETE / CHECKIN
                reply: 简短自然的中文回复，通常 1~2 句话。无论哪种 action 都必须填写。

                当 action=GENERAL：
                - 正常回答用户的问题，不要强行转成闹钟。
                - 其它操作字段保持空/null/默认值。
                - 如果问题需要实时联网数据但你无法确认，要直接说明不能确认实时信息，不要编造。

                当 action=CREATE（创建闹钟）：
                title: 提醒名称
                scheduleType: ONCE / DAILY / WEEKLY / WORKDAY / INTERVAL
                date: ONCE 使用 YYYY-MM-DD，否则 null
                time: HH:mm；INTERVAL 可为 null
                weekdays: WEEKLY 使用 1=周一 ... 7=周日，否则 []
                intervalMinutes: INTERVAL 间隔分钟，否则 null
                startTime/endTime: INTERVAL 时间范围 HH:mm，否则 null
                sound/vibration/notification: boolean，未指定时都 true

                当 action=UPDATE（修改已有闹钟）：
                targetTitle: 用于定位旧闹钟的标题关键词，没有则空字符串
                targetTime: 用于定位旧闹钟的旧时间 HH:mm，没有则 null
                targetScheduleType: 用于定位旧闹钟的旧类型，没有则 null
                title/scheduleType/date/time/weekdays/intervalMinutes/startTime/endTime: 用户想修改后的新值；没有要求修改的字段保持空/null/[]
                sound/vibration/notification: 只有用户明确要求改变时才按要求填写；否则保持 true，App 会优先保留原值

                当 action=DELETE（删除闹钟）：
                targetTitle: 标题关键词，没有则空字符串
                targetTime: 时间筛选，没有则 null
                targetScheduleType: 类型筛选，没有则 null
                deleteAllMatches: 只有用户明确说“全部/所有/都删掉”才 true
                不要返回数据库 ID。

                当 action=CHECKIN（打卡/取消打卡）：
                habitTitle: 用户要打卡的项目关键词
                checkinCompleted: 打卡/完成=true；取消打卡/撤销=false

                判定规则：
                - 普通问答、闲聊、计算、解释 => GENERAL
                - “提醒我/设置闹钟/叫我” => CREATE
                - “把某闹钟改成/修改/调整” => UPDATE
                - “删除/删掉/取消某个闹钟/移除提醒” => DELETE
                - “帮我打卡/完成XX/取消XX打卡” => CHECKIN
                - 工作日/法定工作日 => WORKDAY
                - 每隔X分钟/小时 => INTERVAL
                - 每天 => DAILY
                - 指定星期 => WEEKLY
                - 明天/具体日期/一次性 => ONCE
                - 时间统一为24小时制
                - 不确定用户是否要执行操作时，宁可 GENERAL 并在 reply 中询问，不要擅自创建/删除。
            """.trimIndent()

            val body = JSONObject().apply {
                put("model", MODEL)
                put("temperature", 0.2)
                put("max_tokens", 1200)
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
                readTimeout = 35_000
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

            val action = runCatching {
                AiActionType.valueOf(json.optString("action", "GENERAL").uppercase())
            }.getOrDefault(AiActionType.GENERAL)

            fun schedule(key: String): ScheduleType? = json.optNullableString(key)?.let {
                runCatching { ScheduleType.valueOf(it.uppercase()) }.getOrNull()
            }

            val weekdaysJson = json.optJSONArray("weekdays")
            val weekdays = buildList {
                if (weekdaysJson != null) {
                    for (i in 0 until weekdaysJson.length()) add(weekdaysJson.optInt(i))
                }
            }.filter { it in 1..7 }

            AiAlarmDraft(
                action = action,
                reply = json.optString("reply", "").trim(),
                title = json.optString("title", "").trim(),
                scheduleType = schedule("scheduleType"),
                date = json.optNullableString("date"),
                time = json.optNullableString("time"),
                weekdays = weekdays,
                intervalMinutes = json.optNullableInt("intervalMinutes"),
                startTime = json.optNullableString("startTime"),
                endTime = json.optNullableString("endTime"),
                sound = json.optBoolean("sound", true),
                vibration = json.optBoolean("vibration", true),
                notification = json.optBoolean("notification", true),
                targetTitle = json.optString("targetTitle", "").trim(),
                targetTime = json.optNullableString("targetTime"),
                targetScheduleType = schedule("targetScheduleType"),
                deleteAllMatches = json.optBoolean("deleteAllMatches", false),
                habitTitle = json.optString("habitTitle", "").trim(),
                checkinCompleted = json.optBoolean("checkinCompleted", true)
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
