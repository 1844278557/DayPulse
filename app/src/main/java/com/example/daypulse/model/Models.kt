package com.example.daypulse.model

import java.time.DayOfWeek

enum class ScheduleType {
    ONCE,
    DAILY,
    WEEKLY,
    WORKDAY,
    INTERVAL
}

enum class AiActionType {
    GENERAL,
    CREATE,
    UPDATE,
    DELETE,
    CHECKIN
}

data class Habit(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val enabled: Boolean = true,
    val targetCount: Int = 1,
    val unit: String = "次",
    val weekdaysMask: Int = 127
)

data class CheckIn(
    val id: Long,
    val habitId: Long,
    val dateKey: String,
    val count: Int,
    val completedAt: Long
)

data class AlarmRule(
    val id: Long = 0,
    val title: String,
    val scheduleType: ScheduleType,
    val hour: Int = 8,
    val minute: Int = 0,
    val weekdaysMask: Int = 0,
    val onceAt: Long? = null,
    val intervalMinutes: Int? = null,
    val windowStartMinutes: Int? = null,
    val windowEndMinutes: Int? = null,
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val notification: Boolean = true,
    val enabled: Boolean = true,
    val linkedHabitId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun isWeekdaySelected(day: DayOfWeek): Boolean {
        val bit = 1 shl (day.value - 1)
        return weekdaysMask and bit != 0
    }
}

data class WorkdayOverride(
    val dateKey: String,
    val isWorkday: Boolean
)

data class AiAlarmDraft(
    val action: AiActionType = AiActionType.GENERAL,
    val reply: String = "",

    // CREATE / UPDATE desired values. Empty/null means keep existing value during UPDATE.
    val title: String = "",
    val scheduleType: ScheduleType? = null,
    val date: String? = null,
    val time: String? = null,
    val weekdays: List<Int> = emptyList(),
    val intervalMinutes: Int? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val notification: Boolean = true,

    // UPDATE / DELETE matching criteria.
    val targetTitle: String = "",
    val targetTime: String? = null,
    val targetScheduleType: ScheduleType? = null,
    val deleteAllMatches: Boolean = false,

    // CHECKIN matching / desired state.
    val habitTitle: String = "",
    val checkinCompleted: Boolean = true
)
