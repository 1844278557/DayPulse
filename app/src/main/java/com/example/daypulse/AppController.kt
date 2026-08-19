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
import com.example.daypulse.model.WorkdayOverride
import com.example.daypulse.security.SecureApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

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
        alarms = withContext(Dispatchers.IO) { db.getAlarms() }
        return id
    }

    suspend fun toggleAlarm(rule: AlarmRule) {
        val enabled = !rule.enabled
        withContext(Dispatchers.IO) { db.setAlarmEnabled(rule.id, enabled) }
        if (enabled) scheduler.schedule(rule.copy(enabled = true)) else scheduler.cancel(rule.id)
        alarms = withContext(Dispatchers.IO) { db.getAlarms() }
    }

    suspend fun deleteAlarm(rule: AlarmRule) {
        scheduler.cancel(rule.id)
        withContext(Dispatchers.IO) { db.deleteAlarm(rule.id) }
        alarms = withContext(Dispatchers.IO) { db.getAlarms() }
    }

    suspend fun parseAi(command: String): Result<AiAlarmDraft> {
        val key = withContext(Dispatchers.IO) { keyStore.load() }
            ?: return Result.failure(IllegalStateException("请先在设置里填写新的硅基流动 API Key"))
        return aiClient.parseAlarm(key, command)
    }

    suspend fun saveApiKey(apiKey: String) {
        withContext(Dispatchers.IO) { keyStore.save(apiKey) }
        hasApiKey = withContext(Dispatchers.IO) { keyStore.hasKey() }
    }

    suspend fun clearApiKey() {
        withContext(Dispatchers.IO) { keyStore.clear() }
        hasApiKey = false
    }

    suspend fun setWorkdayOverride(dateKey: String, isWorkday: Boolean) {
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

    private suspend fun rescheduleWorkdayAlarms() {
        alarms.filter { it.enabled && it.scheduleType.name == "WORKDAY" }.forEach { scheduler.schedule(it) }
    }

    private data class Snapshot(
        val habits: List<Habit>,
        val checkIns: List<CheckIn>,
        val alarms: List<AlarmRule>,
        val overrides: List<WorkdayOverride>,
        val hasKey: Boolean
    )
}
