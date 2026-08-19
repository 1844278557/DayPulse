package com.example.daypulse.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.daypulse.MainActivity
import com.example.daypulse.data.AppDatabase
import com.example.daypulse.model.AlarmRule
import com.example.daypulse.model.ScheduleType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate