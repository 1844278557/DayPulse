package com.example.daypulse.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.daypulse.DayPulseActivity
import com.example.daypulse.data.AppDatabase
import com.example.daypulse.model.AlarmRule
import com.example.daypulse.model.ScheduleType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AlarmScheduler(private val context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val db = AppDatabase(appContext)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun schedule(rule: AlarmRule, afterMillis: Long = System.currentTimeMillis()): Long? {
        cancel(rule.id)
        if (!rule.enabled) return null
        val next = nextTriggerMillis(rule, afterMillis) ?: return null
        val operation = alarmPendingIntent(rule.id)

        if (canScheduleExact()) {
            val showIntent = PendingIntent.getActivity(
                appContext,
                (rule.id xor 0x5A5A).toInt(),
                Intent(appContext, DayPulseActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(next, showIntent), operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation)
        }
        return next
    }

    fun cancel(ruleId: Long) {
        alarmManager.cancel(alarmPendingIntent(ruleId))
    }

    fun rescheduleAll() {
        db.getAlarms().filter { it.enabled }.forEach { schedule(it) }
    }

    fun nextTriggerMillis(rule: AlarmRule, afterMillis: Long = System.currentTimeMillis()): Long? {
        val zone = ZoneId.systemDefault()
        val after = Instant.ofEpochMilli(afterMillis).atZone(zone)

        return when (rule.scheduleType) {
            ScheduleType.ONCE -> rule.onceAt?.takeIf { it > afterMillis }
            ScheduleType.DAILY -> {
                val today = after.toLocalDate()
                val todayAt = today.atTime(rule.hour, rule.minute).atZone(zone)
                val target = if (todayAt.toInstant().toEpochMilli() > afterMillis) todayAt
                else today.plusDays(1).atTime(rule.hour, rule.minute).atZone(zone)
                target.toInstant().toEpochMilli()
            }
            ScheduleType.WEEKLY -> {
                (0L..14L).firstNotNullOfOrNull { offset ->
                    val date = after.toLocalDate().plusDays(offset)
                    if (!rule.isWeekdaySelected(date.dayOfWeek)) return@firstNotNullOfOrNull null
                    val candidate = date.atTime(rule.hour, rule.minute).atZone(zone).toInstant().toEpochMilli()
                    candidate.takeIf { it > afterMillis }
                }
            }
            ScheduleType.WORKDAY -> {
                (0L..370L).firstNotNullOfOrNull { offset ->
                    val date = after.toLocalDate().plusDays(offset)
                    if (!isWorkday(date)) return@firstNotNullOfOrNull null
                    val candidate = date.atTime(rule.hour, rule.minute).atZone(zone).toInstant().toEpochMilli()
                    candidate.takeIf { it > afterMillis }
                }
            }
            ScheduleType.INTERVAL -> nextInterval(rule, afterMillis)
        }
    }

    private fun nextInterval(rule: AlarmRule, afterMillis: Long): Long? {
        val interval = rule.intervalMinutes?.coerceAtLeast(1) ?: return null
        val startMin = rule.windowStartMinutes ?: (rule.hour * 60 + rule.minute)
        val endMin = rule.windowEndMinutes ?: 23 * 60 + 59
        val zone = ZoneId.systemDefault()
        val afterDate = Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDate()

        for (dayOffset in 0L..370L) {
            val date = afterDate.plusDays(dayOffset)
            var minuteOfDay = startMin
            while (minuteOfDay <= endMin) {
                val hour = minuteOfDay / 60
                val minute = minuteOfDay % 60
                val candidate = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
                if (candidate > afterMillis) return candidate
                minuteOfDay += interval
            }
        }
        return null
    }

    private fun isWorkday(date: LocalDate): Boolean {
        val override = db.getWorkdayOverride(date.toString())
        if (override != null) return override
        return date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
    }

    private fun alarmPendingIntent(ruleId: Long): PendingIntent {
        val intent = Intent(appContext, AlarmReceiver::class.java).apply {
            action = "com.example.daypulse.ALARM.$ruleId"
            putExtra(EXTRA_RULE_ID, ruleId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            ruleId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val EXTRA_RULE_ID = "rule_id"
    }
}
