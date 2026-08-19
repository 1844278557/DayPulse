package com.example.daypulse.alarm

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.daypulse.data.AppDatabase

class AlarmRingingService : Service() {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ruleId = intent?.getLongExtra(AlarmScheduler.EXTRA_RULE_ID, -1L) ?: -1L
        if (ruleId <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        val rule = AppDatabase(this).getAlarm(ruleId) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()

        val stopIntent = Intent(this, AlarmActionReceiver::class.java).apply { action = AlarmActionReceiver.ACTION_STOP }
        val stopPi = PendingIntent.getBroadcast(
            this,
            22,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ringIntent = Intent(this, AlarmRingActivity::class.java).apply {
            putExtra(AlarmRingActivity.EXTRA_TITLE, rule.title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val ringPi = PendingIntent.getActivity(
            this,
            (30_000 + ruleId).toInt(),
            ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ALARMS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(rule.title.ifBlank { "DayPulse 闹钟" })
            .setContentText("提醒时间到了")
            .setContentIntent(ringPi)
            .setFullScreenIntent(ringPi, true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPi)
            .build()

        startForeground((20_000 + ruleId).toInt(), notification)
        if (rule.sound) startRingtone()
        if (rule.vibration) startVibration()

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, 120_000L)
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DayPulse:AlarmRinging"
        ).apply {
            setReferenceCounted(false)
            acquire(125_000L)
        }
    }

    private fun startRingtone() {
        if (ringtone?.isPlaying == true) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            play()
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 700, 350, 700, 350)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ringtone?.stop()
        vibrator?.cancel()
        if (wakeLock?.isHeld == true) runCatching { wakeLock?.release() }
        wakeLock = null
        ringtone = null
        vibrator = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            context.stopService(Intent(context, AlarmRingingService::class.java))
        }
    }

    companion object {
        const val ACTION_STOP = "com.example.daypulse.STOP_ALARM"
    }
}
