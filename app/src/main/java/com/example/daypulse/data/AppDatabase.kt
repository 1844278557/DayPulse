package com.example.daypulse.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.daypulse.model.AlarmRule
import com.example.daypulse.model.CheckIn
import com.example.daypulse.model.Habit
import com.example.daypulse.model.ScheduleType
import com.example.daypulse.model.WorkdayOverride

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "daypulse.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE habits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE checkins (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                habit_id INTEGER NOT NULL,
                date_key TEXT NOT NULL,
                completed_at INTEGER NOT NULL,
                UNIQUE(habit_id, date_key)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE alarms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                schedule_type TEXT NOT NULL,
                hour INTEGER NOT NULL,
                minute INTEGER NOT NULL,
                weekdays_mask INTEGER NOT NULL DEFAULT 0,
                once_at INTEGER,
                interval_minutes INTEGER,
                window_start_minutes INTEGER,
                window_end_minutes INTEGER,
                sound INTEGER NOT NULL DEFAULT 1,
                vibration INTEGER NOT NULL DEFAULT 1,
                notification INTEGER NOT NULL DEFAULT 1,
                enabled INTEGER NOT NULL DEFAULT 1,
                linked_habit_id INTEGER,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE workday_overrides (
                date_key TEXT PRIMARY KEY,
                is_workday INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun getHabits(): List<Habit> {
        readableDatabase.rawQuery(
            "SELECT id,title,created_at,enabled FROM habits WHERE enabled=1 ORDER BY created_at ASC",
            null
        ).use { c ->
            val items = mutableListOf<Habit>()
            while (c.moveToNext()) {
                items += Habit(
                    id = c.getLong(0),
                    title = c.getString(1),
                    createdAt = c.getLong(2),
                    enabled = c.getInt(3) == 1
                )
            }
            return items
        }
    }

    @Synchronized
    fun insertHabit(title: String): Long {
        val values = ContentValues().apply {
            put("title", title.trim())
            put("created_at", System.currentTimeMillis())
            put("enabled", 1)
        }
        return writableDatabase.insertOrThrow("habits", null, values)
    }

    @Synchronized
    fun deleteHabit(id: Long) {
        writableDatabase.delete("checkins", "habit_id=?", arrayOf(id.toString()))
        writableDatabase.delete("habits", "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun getCheckIns(fromDateKey: String? = null): List<CheckIn> {
        val sql = if (fromDateKey == null) {
            "SELECT id,habit_id,date_key,completed_at FROM checkins ORDER BY date_key DESC"
        } else {
            "SELECT id,habit_id,date_key,completed_at FROM checkins WHERE date_key>=? ORDER BY date_key DESC"
        }
        val args = fromDateKey?.let { arrayOf(it) }
        readableDatabase.rawQuery(sql, args).use { c ->
            val items = mutableListOf<CheckIn>()
            while (c.moveToNext()) {
                items += CheckIn(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3))
            }
            return items
        }
    }

    @Synchronized
    fun isCheckedIn(habitId: Long, dateKey: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM checkins WHERE habit_id=? AND date_key=? LIMIT 1",
            arrayOf(habitId.toString(), dateKey)
        ).use { return it.moveToFirst() }
    }

    @Synchronized
    fun toggleCheckIn(habitId: Long, dateKey: String) {
        if (isCheckedIn(habitId, dateKey)) {
            writableDatabase.delete(
                "checkins",
                "habit_id=? AND date_key=?",
                arrayOf(habitId.toString(), dateKey)
            )
        } else {
            val values = ContentValues().apply {
                put("habit_id", habitId)
                put("date_key", dateKey)
                put("completed_at", System.currentTimeMillis())
            }
            writableDatabase.insertOrThrow("checkins", null, values)
        }
    }

    @Synchronized
    fun getAlarms(): List<AlarmRule> {
        readableDatabase.rawQuery(
            """
            SELECT id,title,schedule_type,hour,minute,weekdays_mask,once_at,interval_minutes,
                   window_start_minutes,window_end_minutes,sound,vibration,notification,enabled,
                   linked_habit_id,created_at
            FROM alarms ORDER BY created_at DESC
            """.trimIndent(),
            null
        ).use { c ->
            val items = mutableListOf<AlarmRule>()
            while (c.moveToNext()) {
                items += AlarmRule(
                    id = c.getLong(0),
                    title = c.getString(1),
                    scheduleType = ScheduleType.valueOf(c.getString(2)),
                    hour = c.getInt(3),
                    minute = c.getInt(4),
                    weekdaysMask = c.getInt(5),
                    onceAt = if (c.isNull(6)) null else c.getLong(6),
                    intervalMinutes = if (c.isNull(7)) null else c.getInt(7),
                    windowStartMinutes = if (c.isNull(8)) null else c.getInt(8),
                    windowEndMinutes = if (c.isNull(9)) null else c.getInt(9),
                    sound = c.getInt(10) == 1,
                    vibration = c.getInt(11) == 1,
                    notification = c.getInt(12) == 1,
                    enabled = c.getInt(13) == 1,
                    linkedHabitId = if (c.isNull(14)) null else c.getLong(14),
                    createdAt = c.getLong(15)
                )
            }
            return items
        }
    }

    @Synchronized
    fun getAlarm(id: Long): AlarmRule? = getAlarms().firstOrNull { it.id == id }

    @Synchronized
    fun insertAlarm(rule: AlarmRule): Long {
        val values = alarmValues(rule)
        return writableDatabase.insertOrThrow("alarms", null, values)
    }

    @Synchronized
    fun updateAlarm(rule: AlarmRule) {
        writableDatabase.update("alarms", alarmValues(rule), "id=?", arrayOf(rule.id.toString()))
    }

    @Synchronized
    fun setAlarmEnabled(id: Long, enabled: Boolean) {
        val values = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
        writableDatabase.update("alarms", values, "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun deleteAlarm(id: Long) {
        writableDatabase.delete("alarms", "id=?", arrayOf(id.toString()))
    }

    private fun alarmValues(rule: AlarmRule) = ContentValues().apply {
        put("title", rule.title)
        put("schedule_type", rule.scheduleType.name)
        put("hour", rule.hour)
        put("minute", rule.minute)
        put("weekdays_mask", rule.weekdaysMask)
        if (rule.onceAt == null) putNull("once_at") else put("once_at", rule.onceAt)
        if (rule.intervalMinutes == null) putNull("interval_minutes") else put("interval_minutes", rule.intervalMinutes)
        if (rule.windowStartMinutes == null) putNull("window_start_minutes") else put("window_start_minutes", rule.windowStartMinutes)
        if (rule.windowEndMinutes == null) putNull("window_end_minutes") else put("window_end_minutes", rule.windowEndMinutes)
        put("sound", if (rule.sound) 1 else 0)
        put("vibration", if (rule.vibration) 1 else 0)
        put("notification", if (rule.notification) 1 else 0)
        put("enabled", if (rule.enabled) 1 else 0)
        if (rule.linkedHabitId == null) putNull("linked_habit_id") else put("linked_habit_id", rule.linkedHabitId)
        put("created_at", rule.createdAt)
    }

    @Synchronized
    fun getWorkdayOverrides(): List<WorkdayOverride> {
        readableDatabase.rawQuery(
            "SELECT date_key,is_workday FROM workday_overrides ORDER BY date_key ASC",
            null
        ).use { c ->
            val items = mutableListOf<WorkdayOverride>()
            while (c.moveToNext()) items += WorkdayOverride(c.getString(0), c.getInt(1) == 1)
            return items
        }
    }

    @Synchronized
    fun getWorkdayOverride(dateKey: String): Boolean? {
        readableDatabase.rawQuery(
            "SELECT is_workday FROM workday_overrides WHERE date_key=?",
            arrayOf(dateKey)
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) == 1 else null
        }
    }

    @Synchronized
    fun setWorkdayOverride(dateKey: String, isWorkday: Boolean) {
        val values = ContentValues().apply {
            put("date_key", dateKey)
            put("is_workday", if (isWorkday) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(
            "workday_overrides",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun deleteWorkdayOverride(dateKey: String) {
        writableDatabase.delete("workday_overrides", "date_key=?", arrayOf(dateKey))
    }
}
