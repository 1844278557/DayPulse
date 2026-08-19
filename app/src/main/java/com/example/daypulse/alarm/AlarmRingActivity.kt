package com.example.daypulse.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class AlarmRingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "DayPulse 提醒" }
        setContent {
            MaterialTheme {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0xFF100637), Color(0xFF2A0B5E), Color(0xFF100637)))
                    ).padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(32.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF241054))
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            Text("⏰", fontSize = 64.sp)
                            Text(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                            Text(title, color = Color.White, fontSize = 24.sp)
                            Text("提醒时间到了", color = Color(0xFFB9A9DD))
                            Button(
                                onClick = {
                                    stopService(Intent(this@AlarmRingActivity, AlarmRingingService::class.java))
                                    finishAndRemoveTask()
                                },
                                modifier = Modifier.fillMaxWidth().height(58.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B5F))
                            ) { Text("停止闹钟", fontSize = 18.sp) }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "alarm_title"
    }
}
