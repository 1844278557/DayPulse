package com.example.daypulse.voice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.daypulse.security.SecureHuaweiMlKeyStore

class HuaweiMlSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SecureHuaweiMlKeyStore(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF245438),
                    background = Color(0xFFFFF8E9),
                    surface = Color(0xFFFFFDF7)
                )
            ) {
                var key by remember { mutableStateOf("") }
                var message by remember { mutableStateOf<String?>(null) }
                Surface(
                    modifier = Modifier.fillMaxSize().background(Color(0xFFFFF8E9)),
                    color = Color(0xFFFFF8E9)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(22.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("华为语音", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = Color(0xFF245438))
                        Spacer(Modifier.height(8.dp))
                        Text("HarmonyOS 设备将优先使用 Huawei ML Kit 实时语音识别，不再走系统 SpeechRecognizer。")
                        Spacer(Modifier.height(18.dp))
                        Surface(color = Color(0xFFFFF2D1), shape = RoundedCornerShape(14.dp)) {
                            Text(
                                "请填写 AppGallery Connect → ML Kit 对应应用的 API Key。Key 只会加密保存在这台手机。",
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = key,
                            onValueChange = { key = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Huawei ML Kit API Key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (key.isBlank()) {
                                    message = "API Key 不能为空"
                                } else {
                                    runCatching { store.save(key.trim()) }
                                        .onSuccess {
                                            message = "已保存。返回 DayPulse 后重新点击 AI 即可测试。"
                                            key = ""
                                        }
                                        .onFailure { message = it.message ?: "保存失败" }
                                }
                            }
                        ) { Text(if (store.hasKey()) "更新华为语音 Key" else "保存华为语音 Key") }

                        if (store.hasKey()) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    store.clear()
                                    message = "华为语音 Key 已删除"
                                }
                            ) { Text("删除已保存 Key") }
                        }

                        message?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(it, color = Color(0xFF245438), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(20.dp))
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { finish() }) { Text("返回 DayPulse") }
                    }
                }
            }
        }
    }
}
