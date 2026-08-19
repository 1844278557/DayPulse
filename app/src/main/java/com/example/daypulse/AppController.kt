package com.example.daypulse

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.daypulse.ai.SiliconFlowClient
import com.example.daypulse.alarm.AlarmScheduler
import com.example.daypulse.data.AppDatabase
import com.example.daypulse.model.AiAlarmDraft
import com.example.daypulse.model.AlarmRule
import com.example.daypulse.model.CheckIn
import com.example.daypulse.model.Habit
import com.example.daypulse.model.ScheduleType
import com.example.daypulse.model.WorkdayOverride
import com.example.daypulse.security.SecureApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

class AppController(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase(appContext)
    val scheduler = AlarmScheduler(appContext)
    private val keyStore = SecureApiKeyStore(appContext)
    private val aiClient = SiliconFlowClient()

    var habits by mutableStateOf<List<Habit>>(emptyList())
        private set
    var checkIns by mutableStateOf<List<CheckIn>>(emptyList())
        private set
    var alarms by mutableStateOf<List<AlarmRule>>(emptyList())
        private set
    var workdayOverrides by mutableStateOf<List<WorkdayOverride>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var hasApiKey by mutableStateOf(false)
        private set

    suspend fun load() {
        loading = true
        val snapshot = withContext(Dispatchers.IO) {
            Snapshot(
                habits = db.getHabits(),
                checkIns = db.getCheckIns(),
                alarms = db.getAlarms(),
                overrides = db.getWorkdayOverrides(),
                hasKey = keyStore.hasKey()
            )
        }
        habits = snapshot.habits
        checkIns = snapshot.checkIns
        alarms = snapshot.alarms
        workdayOverrides = snapshot.overrides
        hasApiKey = snapshot.hasKey
        loading = false
    }

    suspend fun addHabit(title: String) {
        if (title.isBlank()) return
        withContext(Dispatchers.IO) { db.insertHabit(title) }
        refreshHabitsAndChecks()
    }

    suspend fun deleteHabit(id: Long) {
        withContext(Dispatchers.IO) { db.deleteHabit(id) }
        refreshHabitsAndChecks()
    }

    suspend fun toggleToday(habitId: Long) {
        withContext(Dispatchers.IO) { db.toggleCheckIn(habitId, LocalDate.now().toString()) }
        refreshHabitsAndChecks()
    }

    fun isDoneToday(habitId: Long): Boolean {
        val key = LocalDate.now().toString()
        return checkIns.any { it.habitId == habitId && it.dateKey == key }
    }

    suspend fun addAlarm(rule: AlarmRule): Long {
        val id = withContext(Dispatchers.IO) { db.insertAlarm(rule) }
        val stored = rule.copy(id = id)
        if (stored.enabled) scheduler.schedule(stored)
        refreshAlarms()
        return id
    }

    suspend fun updateAlarm(rule: AlarmRule) {
        require(rule.id > 0) { "无效的闹钟" }
        scheduler.cancel(rule.id)
        withContext(Dispatchers.IO) { db.updateAlarm(rule) }
        if (rule.enabled) scheduler.schedule(rule)
        refreshAlarms()
    }

    suspend fun toggleAlarm(rule: AlarmRule) {
        val updated = rule.copy(enabled = !rule.enabled)
        updateAlarm(updated)
    }

    suspend fun deleteAlarm(rule: AlarmRule) {
        scheduler.cancel(rule.id)
        withContext(Dispatchers.IO) { db.deleteAlarm(rule.id) }
        refreshAlarms()
    }

    suspend fun deleteAlarms(rules: List<AlarmRule>) {
        if (rules.isEmpty()) return
        rules.forEach { scheduler.cancel(it.id) }
        withContext(Dispatchers.IO) { rules.forEach { db.deleteAlarm(it.id) } }
        refreshAlarms()
    }

    fun findAlarmMatches(draft: AiAlarmDraft): List<AlarmRule> {
        val titleQuery = draft.title.trim()
        val requestedTime = draft.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val criteriaCount = listOf(
            titleQuery.isNotBlank(),
            requestedTime != null,
            draft.scheduleType != null,
            draft.intervalMinutes != null,
            draft.weekdays.isNotEmpty()
        ).count { it }
        if (criteriaCount == 0) return emptyList()

        return alarms.filter { alarm ->
            val titleOk = titleQuery.isBlank() || alarm.title.contains(titleQuery, ignoreCase = true) || titleQuery.contains(alarm.title, ignoreCase = true)
            val timeOk = requestedTime == null || (alarm.hour == requestedTime.hour && alarm.minute == requestedTime.minute)
            val typeOk = draft.scheduleType == null || alarm.scheduleType == draft.scheduleType
            val intervalOk = draft.intervalMinutes == null || alarm.intervalMinutes == draft.intervalMinutes
            val weekdayOk = if (draft.weekdays.isEmpty()) true else draft.weekdays.all { day -> alarm.weekdaysMask and (1 shl (day - 1)) != 0 }
            titleOk && timeOk && typeOk && intervalOk && weekdayOk
        }
    }

    suspend fun parseAi(command: String): Result<AiAlarmDraft> {
        val key = withContext(Dispatchers.IO) { keyStore.load() }
            ?: return Result.failure(IllegalStateException("请先在设置里填写新的硅基流动 API Key"))
        return aiClient.parseAlarm(key, command)
    }

    suspend fun saveApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        withContext(Dispatchers.IO) { keyStore.save(apiKey.trim()) }
        hasApiKey = withContext(Dispatchers.IO) { keyStore.hasKey() }
    }

    suspend fun clearApiKey() {
        withContext(Dispatchers.IO) { keyStore.clear() }
        hasApiKey = false
    }

    suspend fun setWorkdayOverride(dateKey: String, isWorkday: Boolean) {
        LocalDate.parse(dateKey)
        withContext(Dispatchers.IO) { db.setWorkdayOverride(dateKey, isWorkday) }
        workdayOverrides = withContext(Dispatchers.IO) { db.getWorkdayOverrides() }
        rescheduleWorkdayAlarms()
    }

    suspend fun deleteWorkdayOverride(dateKey: String) {
        withContext(Dispatchers.IO) { db.deleteWorkdayOverride(dateKey) }
        workdayOverrides = withContext(Dispatchers.IO) { db.getWorkdayOverrides() }
        rescheduleWorkdayAlarms()
    }

    fun completedTodayCount(): Int = habits.count { isDoneToday(it.id) }

    fun completionCountLastDays(habitId: Long, days: Long): Int {
        val from = LocalDate.now().minusDays(days - 1).toString()
        return checkIns.count { it.habitId == habitId && it.dateKey >= from }
    }

    fun currentStreak(habitId: Long): Int {
        val dates = checkIns.filter { it.habitId == habitId }.map { it.dateKey }.toHashSet()
        var date = LocalDate.now()
        var streak = 0
        while (dates.contains(date.toString())) {
            streak += 1
            date = date.minusDays(1)
        }
        return streak
    }

    private suspend fun refreshHabitsAndChecks() {
        val pair = withContext(Dispatchers.IO) { db.getHabits() to db.getCheckIns() }
        habits = pair.first
        checkIns = pair.second
    }

    private suspend fun refreshAlarms() {
        alarms = withContext(Dispatchers.IO) { db.getAlarms() }
    }

    private suspend fun rescheduleWorkdayAlarms() {
        alarms.filter { it.enabled && it.scheduleType == ScheduleType.WORKDAY }.forEach { scheduler.schedule(it) }
    }

    private data class Snapshot(
        val habits: List<Habit>,
        val checkIns: List<CheckIn>,
        val alarms: List<AlarmRule>,
        val overrides: List<WorkdayOverride>,
        val hasKey: Boolean
    )
}
