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
    CREATE,
    DELETE
}

data class Habit(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val enabled: Boolean = true,
    val targetCount: Int