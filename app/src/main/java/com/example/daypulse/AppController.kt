package com.example.daypulse

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.daypulse.ai.SiliconFlowClient
import com.example.daypulse.alarm.AlarmScheduler
import com.example.daypulse.data.AppDatabase
import com.example.daypulse.model.*
import com.example.daypulse.security.SecureApiKeyStore
import com.example.daypulse.security.SecureHuaweiMlKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

class AppController(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase(appContext)
    val scheduler = AlarmScheduler(appContext)
    private val keyStore = SecureApiKeyStore(appContext)
    private val huaweiKeyStore = SecureHuaweiMlKeyStore(appContext)
    private val aiClient = SiliconFlowClient()

    var habits by mutableStateOf<List<Habit>>(emptyList()); private set
    var checkIns by mutableStateOf<List<CheckIn>>(emptyList()); private set
    var alarms by mutableStateOf<List<AlarmRule>>(emptyList()); private set
    var workdayOverrides by mutableStateOf<List<WorkdayOverride>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var hasApiKey by mutableStateOf(false); private set
    var hasHuaweiMlKey by mutableStateOf(false); private set

    suspend fun load() {
        loading = true
        val snapshot = withContext(Dispatchers.IO) {
            Snapshot(
                db.getHabits(),
                db.getCheckIns(),
                db.getAlarms(),
                db.getWorkdayOverrides(),
                keyStore.hasKey(),
                huaweiKeyStore.hasKey()
            )
        }
        habits = snapshot.habits; checkIns = snapshot.checkIns; alarms = snapshot.alarms
        workdayOverrides = snapshot.overrides; hasApiKey = snapshot.hasKey
        hasHuaweiMlKey = snapshot.hasHuaweiKey; loading = false
    }

    suspend fun addHabit(title: String, targetCount: Int = 1, unit: String = "次", weekdaysMask: Int = 127) {
        if (title.isBlank()) return
        withContext(Dispatchers.IO) { db.insertHabit(title, targetCount, unit, weekdaysMask) }
        refreshHabitsAndChecks()
    }

    suspend fun updateHabit(habit: Habit) {
        require(habit.title.isNotBlank()) { "习惯名称不能为空" }
        withContext(Dispatchers.IO) { db.updateHabit(habit) }
        refreshHabitsAndChecks()
    }

    suspend fun deleteHabit(id: Long) {
        withContext(Dispatchers.IO) { db.deleteHabit(id) }
        refreshHabitsAndChecks()
    }

    fun isHabitScheduled(habit: Habit, date: LocalDate = LocalDate.now()): Boolean =
        habit.weekdaysMask and (1 shl (date.dayOfWeek.value - 1)) != 0

    fun todayCount(habitId: Long): Int = countForDate(habitId, LocalDate.now())

    fun countForDate(habitId: Long, date: LocalDate): Int =
        checkIns.firstOrNull { it.habitId == habitId && it.dateKey == date.toString() }?.count ?: 0

    fun isDoneOnDate(habit: Habit, date: LocalDate): Boolean = countForDate(habit.id, date) >= habit.targetCount
    fun isDoneToday(habitId: Long): Boolean = habits.firstOrNull { it.id == habitId }?.let { isDoneOnDate(it, LocalDate.now()) } == true

    suspend fun changeHabitCount(habit: Habit, delta: Int, date: LocalDate = LocalDate.now()) {
        val next = (countForDate(habit.id, date) + delta).coerceIn(0, habit.targetCount)
        withContext(Dispatchers.IO) { db.setCheckInCount(habit.id, date.toString(), next) }
        refreshHabitsAndChecks()
    }

    suspend fun setHabitCompleted(habit: Habit, completed: Boolean, date: LocalDate = LocalDate.now()) {
        withContext(Dispatchers.IO) { db.setCheckInCount(habit.id, date.toString(), if (completed) habit.targetCount else 0) }
        refreshHabitsAndChecks()
    }

    suspend fun addAlarm(rule: AlarmRule): Long {
        val id = withContext(Dispatchers.IO) { db.insertAlarm(rule) }
        val stored = rule.copy(id = id)
        if (stored.enabled) scheduler.schedule(stored)
        refreshAlarms(); return id
    }

    suspend fun updateAlarm(rule: AlarmRule) {
        require(rule.id > 0) { "无效的闹钟" }
        scheduler.cancel(rule.id)
        withContext(Dispatchers.IO) { db.updateAlarm(rule) }
        if (rule.enabled) scheduler.schedule(rule)
        refreshAlarms()
    }

    suspend fun toggleAlarm(rule: AlarmRule) = updateAlarm(rule.copy(enabled = !rule.enabled))

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
        val titleQuery = draft.targetTitle.ifBlank {
            if (draft.action == AiActionType.DELETE) draft.title.trim() else ""
        }
        val requestedTimeText = draft.targetTime ?: if (draft.action == AiActionType.DELETE) draft.time else null
        val requestedTime = requestedTimeText?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val requestedType = draft.targetScheduleType ?: if (draft.action == AiActionType.DELETE) draft.scheduleType else null

        if (titleQuery.isBlank() && requestedTime == null && requestedType == null) return emptyList()

        return alarms.filter { alarm ->
            val titleOk = titleQuery.isBlank() ||
                alarm.title.contains(titleQuery, ignoreCase = true) ||
                titleQuery.contains(alarm.title, ignoreCase = true)
            val timeOk = requestedTime == null || (alarm.hour == requestedTime.hour && alarm.minute == requestedTime.minute)
            val typeOk = requestedType == null || alarm.scheduleType == requestedType
            titleOk && timeOk && typeOk
        }
    }

    fun findHabitMatches(draft: AiAlarmDraft): List<Habit> {
        val query = draft.habitTitle.trim()
        if (query.isBlank()) return emptyList()
        return habits.filter { habit ->
            habit.title.contains(query, ignoreCase = true) || query.contains(habit.title, ignoreCase = true)
        }
    }

    suspend fun parseAi(command: String): Result<AiAlarmDraft> {
        val key = withContext(Dispatchers.IO) { keyStore.load() }
            ?: return Result.failure(IllegalStateException("请先在我的页面填写硅基流动 API Key"))
        return aiClient.parseAlarm(key, command)
    }

    suspend fun saveApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        withContext(Dispatchers.IO) { keyStore.save(apiKey.trim()) }
        hasApiKey = true
    }

    suspend fun clearApiKey() { withContext(Dispatchers.IO) { keyStore.clear() }; hasApiKey = false }

    suspend fun saveHuaweiMlKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "Huawei ML Kit API Key 不能为空" }
        withContext(Dispatchers.IO) { huaweiKeyStore.save(apiKey.trim()) }
        hasHuaweiMlKey = true
    }

    suspend fun clearHuaweiMlKey() {
        withContext(Dispatchers.IO) { huaweiKeyStore.clear() }
        hasHuaweiMlKey = false
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

    fun todayHabits(): List<Habit> = habits.filter { isHabitScheduled(it) }
    fun completedTodayCount(): Int = todayHabits().count { isDoneOnDate(it, LocalDate.now()) }

    fun completionCountLastDays(habitId: Long, days: Long): Int {
        val habit = habits.firstOrNull { it.id == habitId } ?: return 0
        val from = LocalDate.now().minusDays(days - 1)
        return (0 until days).count { offset ->
            val date = from.plusDays(offset)
            isHabitScheduled(habit, date) && isDoneOnDate(habit, date)
        }
    }

    fun scheduledCountLastDays(habit: Habit, days: Long): Int {
        val from = LocalDate.now().minusDays(days - 1)
        return (0 until days).count { isHabitScheduled(habit, from.plusDays(it)) }
    }

    fun currentStreak(habitId: Long): Int {
        val habit = habits.firstOrNull { it.id == habitId } ?: return 0
        var date = LocalDate.now()
        var streak = 0
        var scanned = 0
        while (scanned < 3660) {
            if (isHabitScheduled(habit, date)) {
                if (!isDoneOnDate(habit, date)) break
                streak++
            }
            date = date.minusDays(1); scanned++
        }
        return streak
    }

    fun longestStreak(habitId: Long, lookbackDays: Int = 365): Int {
        val habit = habits.firstOrNull { it.id == habitId } ?: return 0
        var best = 0; var current = 0
        for (offset in lookbackDays downTo 0) {
            val date = LocalDate.now().minusDays(offset.toLong())
            if (!isHabitScheduled(habit, date)) continue
            if (isDoneOnDate(habit, date)) { current++; best = maxOf(best, current) } else current = 0
        }
        return best
    }

    private suspend fun refreshHabitsAndChecks() {
        val pair = withContext(Dispatchers.IO) { db.getHabits() to db.getCheckIns() }
        habits = pair.first; checkIns = pair.second
    }
    private suspend fun refreshAlarms() { alarms = withContext(Dispatchers.IO) { db.getAlarms() } }
    private suspend fun rescheduleWorkdayAlarms() { alarms.filter { it.enabled && it.scheduleType == ScheduleType.WORKDAY }.forEach { scheduler.schedule(it) } }

    private data class Snapshot(
        val habits: List<Habit>,
        val checkIns: List<CheckIn>,
        val alarms: List<AlarmRule>,
        val overrides: List<WorkdayOverride>,
        val hasKey: Boolean,
        val hasHuaweiKey: Boolean
    )
}
