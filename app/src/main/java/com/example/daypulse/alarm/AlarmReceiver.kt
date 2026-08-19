package com.example.daypulse.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.daypulse.DayPulseActivity
import com.example.daypulse.data.AppDatabase
import com.example.daypulse.model.ScheduleType

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ruleId = intent.getLongExtra(AlarmScheduler.EXTRA_RULE_ID, -1L)
        if (ruleId <= 0) return
        val db = AppDatabase(context)
        val rule = db.getAlarm(ruleId) ?: return
        if (!rule.enabled) return

        if (rule.sound || rule.vibration) {
            val service = Intent(context, AlarmRingingService::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_RULE_ID, ruleId)
            }
            ContextCompat.startForegroundService(context, service)
        } else if (rule.notification) {
            NotificationHelper.showSimpleAlarmNotification(context, ruleId, rule.title)
        }

        if (rule.scheduleType == ScheduleType.ONCE) {
            db.setAlarmEnabled(ruleId, false)
        } else {
            AlarmScheduler(context).schedule(rule, System.currentTimeMillis() + 1_000L)
        }
    }
}

object NotificationHelper {
    const val CHANNEL_ALARMS = "daypulse_alarms"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ALARMS,
                "DayPulse 提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "闹钟与打卡提醒"
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun contentIntent(context: Context): PendingIntent {
        return PendingIntent.getActivity(
            context,
            100,
            Intent(context, DayPulseActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun showSimpleAlarmNotification(context: Context, ruleId: Long, title: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText("提醒时间到了")
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify((10_000 + ruleId).toInt(), notification)
    }
}
