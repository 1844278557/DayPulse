package com.example.daypulse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddAlarm
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.daypulse.alarm.NotificationHelper
import com.example.daypulse.model.AiActionType
import com.example.daypulse.model.AiAlarmDraft
import com.example.daypulse.model.AlarmRule
import com.example.daypulse.model.Habit
import com.example.daypulse.model.ScheduleType
import com.example.daypulse.voice.SpeechInputController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val UiBg = Color(0xFFFFF8E9)
private val UiPaper = Color(0xFFFFFDF7)
private val UiWarm = Color(0xFFFFF2D1)
private val UiPine = Color(0xFF245438)
private val UiPine2 = Color(0xFF3E704F)
private val UiMoss = Color(0xFF788A67)
private val UiAmber = Color(0xFFF5AA16)
private val UiOrange = Color(0xFFF28A00)
private val UiInk = Color(0xFF163426)
private val UiSoft = Color(0xFF69746B)
private val UiLine = Color(0xFFE2D3A8)
private val UiDanger = Color(0xFFB74C3E)
private val UiSky = Color(0xFFF9EFD4)

class DayPulseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.ensureChannel(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = UiPine,
                    secondary = UiAmber,
                    tertiary = UiOrange,
                    background = UiBg,
                    surface = UiPaper,
                    surfaceVariant = UiWarm,
                    onPrimary = Color.White,
                    onBackground = UiInk,
                    onSurface = UiInk,
                    outline = UiLine,
                    error = UiDanger
                )
            ) { DayPulseApp() }
        }
    }
}

private enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Rounded.Home),
    ALARM("闹钟", Icons.Rounded.Alarm),
    STATS("统计", Icons.Rounded.BarChart),
    MINE("我的", Icons.Rounded.Person)
}

@Composable
private fun DayPulseApp() {
    val context = LocalContext.current
    val c = remember { AppController(context) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(MainTab.HOME) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var listening by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var aiStatus by remember { mutableStateOf<String?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var voiceDialogOpen by remember { mutableStateOf(false) }
    var voiceReply by remember { mutableStateOf("") }
    var aiDraft by remember { mutableStateOf<AiAlarmDraft?>(null) }

    var aiCreateDraft by remember { mutableStateOf<AlarmRule?>(null) }
    var aiUpdateEditor by remember { mutableStateOf<AlarmRule?>(null) }
    var updateCandidates by remember { mutableStateOf<List<AlarmRule>>(emptyList()) }
    var deleteCandidates by remember { mutableStateOf<List<AlarmRule>>(emptyList()) }
    var selectedDeleteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var checkinCandidates by remember { mutableStateOf<List<Habit>>(emptyList()) }
    var selectedHabitIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    fun submitVoiceToAi(text: String) {
        val request = text.trim()
        if (request.isBlank() || aiBusy) return
        transcript = request
        voiceReply = ""
        voiceDialogOpen = true
        aiBusy = true
        aiStatus = null

        scope.launch {
            c.parseAi(request).onSuccess { parsed ->
                aiDraft = parsed
                voiceReply = parsed.reply.ifBlank {
                    when (parsed.action) {
                        AiActionType.GENERAL -> "我听到了。"
                        AiActionType.CREATE -> "我理解为创建一个闹钟，确认一下设置。"
                        AiActionType.UPDATE -> "我理解为修改一个已有闹钟，确认一下目标和新设置。"
                        AiActionType.DELETE -> "我理解为删除闹钟，确认一下要删除哪些。"
                        AiActionType.CHECKIN -> "我理解为进行一次打卡操作，确认一下项目。"
                    }
                }

                when (parsed.action) {
                    AiActionType.GENERAL -> Unit
                    AiActionType.CREATE -> {
                        runCatching { parsed.toUiAlarmRule() }
                            .onSuccess { aiCreateDraft = it }
                            .onFailure { voiceReply += "\n${it.message ?: "闹钟信息还不完整"}" }
                    }
                    AiActionType.UPDATE -> {
                        updateCandidates = c.findAlarmMatches(parsed)
                        if (updateCandidates.isEmpty()) voiceReply += "\n没有找到匹配的已有闹钟。"
                    }
                    AiActionType.DELETE -> {
                        deleteCandidates = c.findAlarmMatches(parsed)
                        selectedDeleteIds = when {
                            parsed.deleteAllMatches -> deleteCandidates.map { it.id }.toSet()
                            deleteCandidates.size == 1 -> setOf(deleteCandidates.first().id)
                            else -> emptySet()
                        }
                        if (deleteCandidates.isEmpty()) voiceReply += "\n没有找到匹配的已有闹钟。"
                    }
                    AiActionType.CHECKIN -> {
                        checkinCandidates = c.findHabitMatches(parsed)
                        selectedHabitIds = if (checkinCandidates.size == 1) setOf(checkinCandidates.first().id) else emptySet()
                        if (checkinCandidates.isEmpty()) voiceReply += "\n没有找到匹配的打卡项目。"
                    }
                }
            }.onFailure {
                voiceReply = it.message ?: "AI 请求失败"
                aiDraft = null
            }
            aiBusy = false
        }
    }

    val speech = remember {
        SpeechInputController(
            context = context,
            onListeningChange = { listening = it },
            onStatus = { aiStatus = it },
            onPartialText = { transcript = it },
            onFinalText = { submitVoiceToAi(it) }
        )
    }
    DisposableEffect(speech) { onDispose { speech.destroy() } }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            transcript = ""
            speech.start()
        } else {
            aiStatus = "需要麦克风权限才能使用 AI 语音"
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        c.load()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    fun toggleVoice() {
        if (listening) {
            speech.stopAndFinalize()
            return
        }
        if (speech.isBusy()) {
            aiStatus = "正在完成上一段语音识别…"
            return
        }
        if (aiBusy) {
            aiStatus = "AI 正在处理上一条内容"
            return
        }
        transcript = ""
        voiceReply = ""
        voiceDialogOpen = false
        aiDraft = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            speech.start()
        }
    }

    Scaffold(
        containerColor = UiBg,
        bottomBar = {
            BottomDock(
                current = tab,
                listening = listening,
                onSelect = { tab = it },
                onVoiceToggle = { toggleVoice() }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(UiBg)) {
            if (c.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = UiPine)
            } else {
                when (tab) {
                    MainTab.HOME -> HomeDashboard(c, nowMillis, onOpenAlarm = { tab = MainTab.ALARM })
                    MainTab.ALARM -> AlarmPage(c, nowMillis)
                    MainTab.STATS -> StatsPage(c)
                    MainTab.MINE -> MinePage(c)
                }
            }

            if ((listening || !aiStatus.isNullOrBlank()) && !voiceDialogOpen) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 18.dp, end = 18.dp, bottom = 8.dp)
                        .fillMaxWidth(),
                    color = if (listening) UiPine else UiPaper,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, if (listening) UiAmber else UiLine),
                    shadowElevation = 6.dp
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        PixelText(
                            text = if (listening) "● 正在听… 再点一次 AI 结束" else aiStatus.orEmpty(),
                            color = if (listening) Color.White else UiPine,
                            weight = FontWeight.Bold
                        )
                        if (listening && transcript.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            PixelText("“$transcript”", color = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (voiceDialogOpen) {
        VoiceAiDialog(
            transcript = transcript,
            reply = voiceReply,
            busy = aiBusy,
            action = aiDraft?.action,
            actionReady = when (aiDraft?.action) {
                AiActionType.CREATE -> aiCreateDraft != null
                AiActionType.UPDATE -> updateCandidates.isNotEmpty()
                AiActionType.DELETE -> deleteCandidates.isNotEmpty()
                AiActionType.CHECKIN -> checkinCandidates.isNotEmpty()
                else -> false
            },
            onDismiss = {
                voiceDialogOpen = false
                if (!aiBusy) aiDraft = null
            },
            onContinue = {
                when (val draft = aiDraft) {
                    null -> Unit
                    else -> when (draft.action) {
                        AiActionType.GENERAL -> voiceDialogOpen = false
                        AiActionType.CREATE -> voiceDialogOpen = false
                        AiActionType.UPDATE -> {
                            voiceDialogOpen = false
                            if (updateCandidates.size == 1) {
                                aiUpdateEditor = applyAiUpdate(updateCandidates.first(), draft)
                                updateCandidates = emptyList()
                            }
                        }
                        AiActionType.DELETE -> voiceDialogOpen = false
                        AiActionType.CHECKIN -> voiceDialogOpen = false
                    }
                }
            }
        )
    }

    aiCreateDraft?.let { initial ->
        if (!voiceDialogOpen) {
            AlarmEditorDialog(
                initial = initial,
                title = "AI 创建闹钟 · 确认前可修改",
                onDismiss = { aiCreateDraft = null; aiDraft = null },
                onDelete = null,
                onSave = { edited ->
                    scope.launch {
                        c.addAlarm(edited.copy(id = 0))
                        aiCreateDraft = null
                        aiDraft = null
                        aiStatus = "✓ 闹钟已创建"
                    }
                }
            )
        }
    }

    if (!voiceDialogOpen && aiDraft?.action == AiActionType.UPDATE && updateCandidates.size > 1) {
        AlertDialog(
            onDismissRequest = { updateCandidates = emptyList(); aiDraft = null },
            containerColor = UiPaper.copy(alpha = 0.96f),
            title = { PixelText("选择要修改的闹钟", color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    updateCandidates.forEach { alarm ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                aiUpdateEditor = applyAiUpdate(alarm, requireNotNull(aiDraft))
                                updateCandidates = emptyList()
                            },
                            color = UiWarm,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                PixelText("%02d:%02d  %s".format(alarm.hour, alarm.minute, alarm.title), weight = FontWeight.Bold)
                                PixelText(alarmSummary(alarm), color = UiSoft)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { updateCandidates = emptyList(); aiDraft = null }) { Text("取消") } }
        )
    }

    aiUpdateEditor?.let { editedInitial ->
        if (!voiceDialogOpen) {
            AlarmEditorDialog(
                initial = editedInitial,
                title = "AI 修改闹钟 · 确认前可修改",
                onDismiss = { aiUpdateEditor = null; aiDraft = null },
                onDelete = null,
                onSave = { edited ->
                    scope.launch {
                        c.updateAlarm(edited.copy(id = editedInitial.id, createdAt = editedInitial.createdAt))
                        aiUpdateEditor = null
                        aiDraft = null
                        aiStatus = "✓ 闹钟已修改"
                    }
                }
            )
        }
    }

    if (!voiceDialogOpen && aiDraft?.action == AiActionType.DELETE && deleteCandidates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { aiDraft = null; deleteCandidates = emptyList(); selectedDeleteIds = emptySet() },
            containerColor = UiPaper.copy(alpha = 0.96f),
            title = { PixelText("AI 删除确认", weight = FontWeight.Black, color = UiDanger, size = MaterialTheme.typography.titleLarge.fontSize) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PixelText("请勾选要删除的闹钟：", color = UiSoft)
                    deleteCandidates.forEach { alarm ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selectedDeleteIds = if (alarm.id in selectedDeleteIds) selectedDeleteIds - alarm.id else selectedDeleteIds + alarm.id
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = alarm.id in selectedDeleteIds, onCheckedChange = null)
                            Column(Modifier.weight(1f)) {
                                PixelText("%02d:%02d  %s".format(alarm.hour, alarm.minute, alarm.title), weight = FontWeight.Bold)
                                PixelText(alarmSummary(alarm), color = UiSoft)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedDeleteIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = UiDanger),
                    onClick = {
                        scope.launch {
                            val chosen = deleteCandidates.filter { it.id in selectedDeleteIds }
                            c.deleteAlarms(chosen)
                            aiStatus = "✓ 已删除 ${chosen.size} 个闹钟"
                            aiDraft = null
                            deleteCandidates = emptyList()
                            selectedDeleteIds = emptySet()
                        }
                    }
                ) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { aiDraft = null; deleteCandidates = emptyList(); selectedDeleteIds = emptySet() }) { Text("取消") } }
        )
    }

    if (!voiceDialogOpen && aiDraft?.action == AiActionType.CHECKIN && checkinCandidates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { aiDraft = null; checkinCandidates = emptyList(); selectedHabitIds = emptySet() },
            containerColor = UiPaper.copy(alpha = 0.96f),
            title = { PixelText("AI 打卡确认", color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PixelText(if (aiDraft?.checkinCompleted == false) "确认取消以下打卡：" else "确认完成以下打卡：", color = UiSoft)
                    checkinCandidates.forEach { habit ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selectedHabitIds = if (habit.id in selectedHabitIds) selectedHabitIds - habit.id else selectedHabitIds + habit.id
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = habit.id in selectedHabitIds, onCheckedChange = null)
                            PixelText(habit.title, modifier = Modifier.weight(1f), weight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedHabitIds.isNotEmpty(),
                    onClick = {
                        val completed = aiDraft?.checkinCompleted != false
                        scope.launch {
                            checkinCandidates.filter { it.id in selectedHabitIds }.forEach { c.setHabitCompleted(it, completed) }
                            aiStatus = if (completed) "✓ 打卡已完成" else "✓ 已取消打卡"
                            aiDraft = null
                            checkinCandidates = emptyList()
                            selectedHabitIds = emptySet()
                        }
                    }
                ) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { aiDraft = null; checkinCandidates = emptyList(); selectedHabitIds = emptySet() }) { Text("取消") } }
        )
    }
}

@Composable
private fun VoiceAiDialog(
    transcript: String,
    reply: String,
    busy: Boolean,
    action: AiActionType?,
    actionReady: Boolean,
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = UiPaper.copy(alpha = 0.94f),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(UiPine, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                PixelText("DayPulse AI", color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = UiWarm.copy(alpha = 0.9f), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        PixelText("你刚才说", color = UiSoft, size = MaterialTheme.typography.labelMedium.fontSize)
                        Spacer(Modifier.height(3.dp))
                        PixelText(transcript.ifBlank { "…" }, weight = FontWeight.Bold)
                    }
                }

                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = UiPine)
                        Spacer(Modifier.width(9.dp))
                        PixelText("AI 正在理解并回复…", color = UiSoft)
                    }
                } else if (reply.isNotBlank()) {
                    Surface(color = Color.White.copy(alpha = 0.72f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, UiLine)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            PixelText("AI", color = UiPine, weight = FontWeight.Black)
                            Spacer(Modifier.height(3.dp))
                            PixelText(reply, color = UiInk)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!busy && action != null && action != AiActionType.GENERAL && actionReady) {
                Button(onClick = onContinue) {
                    Text(
                        when (action) {
                            AiActionType.CREATE -> "确认闹钟"
                            AiActionType.UPDATE -> "选择并修改"
                            AiActionType.DELETE -> "选择并删除"
                            AiActionType.CHECKIN -> "确认打卡"
                            AiActionType.GENERAL -> "知道了"
                        }
                    )
                }
            } else if (!busy) {
                Button(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (!busy && action != null && action != AiActionType.GENERAL && actionReady) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun BottomDock(
    current: MainTab,
    listening: Boolean,
    onSelect: (MainTab) -> Unit,
    onVoiceToggle: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "ai-pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
        label = "ai-pulse-alpha"
    )

    Box(Modifier.fillMaxWidth().height(104.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(74.dp).align(Alignment.BottomCenter),
            color = UiPaper,
            shadowElevation = 10.dp
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                DockItem(MainTab.HOME, current, onSelect, Modifier.weight(1f))
                DockItem(MainTab.ALARM, current, onSelect, Modifier.weight(1f))
                Spacer(Modifier.width(82.dp))
                DockItem(MainTab.STATS, current, onSelect, Modifier.weight(1f))
                DockItem(MainTab.MINE, current, onSelect, Modifier.weight(1f))
            }
        }

        if (listening) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size(82.dp)
                    .border(4.dp, UiAmber.copy(alpha = pulseAlpha), CircleShape)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(70.dp)
                .background(if (listening) UiOrange else UiPine, CircleShape)
                .border(4.dp, UiPaper, CircleShape)
                .clickable(onClick = onVoiceToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (listening) Icons.Rounded.GraphicEq else Icons.Rounded.Mic,
                contentDescription = if (listening) "结束 AI 语音" else "开始 AI 语音",
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

@Composable
private fun DockItem(tab: MainTab, current: MainTab, onSelect: (MainTab) -> Unit, modifier: Modifier) {
    val selected = current == tab
    Column(
        modifier = modifier.fillMaxHeight().clickable { onSelect(tab) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(tab.icon, tab.label, tint = if (selected) UiPine else UiSoft, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        PixelText(tab.label, color = if (selected) UiPine else UiSoft, weight = if (selected) FontWeight.Black else FontWeight.Normal, size = MaterialTheme.typography.labelSmall.fontSize)
    }
}

@Composable
private fun HomeDashboard(c: AppController, nowMillis: Long, onOpenAlarm: () -> Unit) {
    val scope = rememberCoroutineScope()
    val todayHabits = c.todayHabits()
    val done = c.completedTodayCount()
    val progress = if (todayHabits.isEmpty()) 0f else done.toFloat() / todayHabits.size

    // The home summary intentionally excludes INTERVAL alarms. Interval reminders can fire many
    // times a day and would otherwise constantly replace the user's meaningful "next alarm".
    val nextAlarm = c.alarms
        .filter { it.enabled && it.scheduleType != ScheduleType.INTERVAL }
        .mapNotNull { alarm -> c.scheduler.nextTriggerMillis(alarm, nowMillis)?.let { alarm to it } }
        .minByOrNull { it.second }

    var habitEditor by remember { mutableStateOf<Habit?>(null) }
    var creatingHabit by remember { mutableStateOf(false) }
    var alarmEditor by remember { mutableStateOf<AlarmRule?>(null) }

    val now = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { HomeHeader() }

        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    PixelText(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.displayMedium.fontSize)
                    PixelText(":${now.format(DateTimeFormatter.ofPattern("ss"))}", color = UiOrange, weight = FontWeight.Black, size = MaterialTheme.typography.headlineLarge.fontSize)
                }
                PixelText("${LocalDate.now()} · 保持今天的节奏", color = UiSoft)
            }
        }

        item { NextAlarmCard(nextAlarm, nowMillis, onOpenAlarm) }
        item { PixelLandscape() }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = UiPaper),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, UiLine),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            PixelText("今日打卡", color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize)
                            PixelText("TODAY TO-DO", color = UiSoft, size = MaterialTheme.typography.labelMedium.fontSize)
                        }
                        IconButton(onClick = {
                            creatingHabit = true
                            habitEditor = Habit(0, "", System.currentTimeMillis())
                        }) { Icon(Icons.Rounded.Add, "新建打卡", tint = UiPine) }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelText("$done/${todayHabits.size}", color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.headlineSmall.fontSize)
                        Spacer(Modifier.width(14.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.weight(1f).height(8.dp),
                            color = UiAmber,
                            trackColor = Color(0xFFF3E6C1)
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    if (todayHabits.isEmpty()) {
                        EmptyLine("今天还没有打卡项目，点右上角 + 创建")
                    } else {
                        todayHabits.forEachIndexed { index, habit ->
                            HabitHomeRow(
                                c = c,
                                habit = habit,
                                onEdit = { creatingHabit = false; habitEditor = habit },
                                onToggle = { complete -> scope.launch { c.setHabitCompleted(habit, complete) } }
                            )
                            if (index != todayHabits.lastIndex) HorizontalDivider(color = UiLine.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = UiPaper),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, UiLine),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelText("今日闹钟", modifier = Modifier.weight(1f), color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize)
                        TextButton(onClick = onOpenAlarm) { Text("查看全部") }
                    }
                    if (c.alarms.isEmpty()) {
                        EmptyLine("还没有闹钟，去闹钟页新建")
                    } else {
                        val sorted = c.alarms.sortedWith(compareBy<AlarmRule> { it.hour }.thenBy { it.minute })
                        sorted.forEachIndexed { index, alarm ->
                            HomeAlarmRow(
                                alarm = alarm,
                                onEdit = { alarmEditor = alarm },
                                onToggle = { scope.launch { c.toggleAlarm(alarm) } }
                            )
                            if (index != sorted.lastIndex) HorizontalDivider(color = UiLine.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }
    }

    habitEditor?.let { initial ->
        HabitEditorDialog(
            initial = initial,
            creating = creatingHabit,
            onDismiss = { habitEditor = null },
            onDelete = if (creatingHabit) null else {{ scope.launch { c.deleteHabit(initial.id); habitEditor = null } }},
            onSave = { edited ->
                scope.launch {
                    if (creatingHabit) c.addHabit(edited.title, edited.targetCount, edited.unit, edited.weekdaysMask)
                    else c.updateHabit(edited.copy(id = initial.id, createdAt = initial.createdAt))
                    habitEditor = null
                }
            }
        )
    }

    alarmEditor?.let { initial ->
        AlarmEditorDialog(
            initial = initial,
            title = "编辑闹钟",
            onDismiss = { alarmEditor = null },
            onDelete = {
                scope.launch {
                    c.deleteAlarm(initial)
                    alarmEditor = null
                }
            },
            onSave = { edited ->
                scope.launch {
                    c.updateAlarm(edited.copy(id = initial.id, createdAt = initial.createdAt))
                    alarmEditor = null
                }
            }
        )
    }
}

@Composable
private fun HomeHeader() {
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        PixelAvatar(modifier = Modifier.align(Alignment.CenterStart))
        PixelText(
            "DayPulse",
            modifier = Modifier.align(Alignment.Center),
            color = UiPine,
            weight = FontWeight.Black,
            size = MaterialTheme.typography.headlineMedium.fontSize
        )
        PixelSunDecoration(modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun PixelAvatar(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .size(44.dp)
            .background(UiWarm, RoundedCornerShape(9.dp))
            .border(2.dp, UiPine, RoundedCornerShape(9.dp))
    ) {
        val u = size.minDimension / 16f
        fun block(x: Int, y: Int, w: Int, h: Int, color: Color) {
            drawRect(color, Offset(x * u, y * u), Size(w * u, h * u))
        }
        // Original DayPulse pixel character: hair, face, eyes and green hoodie.
        block(4, 2, 8, 2, Color(0xFF5B391F))
        block(3, 4, 10, 2, Color(0xFF6B4324))
        block(4, 6, 8, 5, Color(0xFFFFD6A3))
        block(3, 6, 1, 3, Color(0xFF6B4324))
        block(12, 6, 1, 3, Color(0xFF6B4324))
        block(6, 7, 1, 1, UiInk)
        block(9, 7, 1, 1, UiInk)
        block(7, 9, 2, 1, Color(0xFFC76F57))
        block(5, 11, 6, 1, UiPine)
        block(3, 12, 10, 3, UiPine2)
        block(7, 12, 2, 2, UiWarm)
        block(2, 14, 12, 1, UiPine)
    }
}

@Composable
private fun PixelSunDecoration(modifier: Modifier = Modifier) {
    Canvas(modifier.size(38.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val p = size.minDimension / 10f
        drawRect(UiAmber, Offset(c.x - p * 1.5f, c.y - p * 1.5f), Size(p * 3f, p * 3f))
        val rays = listOf(
            Offset(c.x - p / 2, 0f), Offset(c.x - p / 2, size.height - p),
            Offset(0f, c.y - p / 2), Offset(size.width - p, c.y - p / 2),
            Offset(p, p), Offset(size.width - p * 2, p),
            Offset(p, size.height - p * 2), Offset(size.width - p * 2, size.height - p * 2)
        )
        rays.forEach { drawRect(UiOrange, it, Size(p, p)) }
    }
}

@Composable
private fun NextAlarmCard(next: Pair<AlarmRule, Long>?, nowMillis: Long, onOpenAlarm: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAlarm),
        color = UiWarm,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, UiAmber.copy(alpha = 0.65f))
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(UiPaper, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Alarm, null, tint = UiOrange)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (next == null) {
                    PixelText("暂无普通闹钟", weight = FontWeight.Bold, color = UiPine)
                    PixelText("循环/间隔提醒不会显示在这里", color = UiSoft)
                } else {
                    val (alarm, trigger) = next
                    val target = Instant.ofEpochMilli(trigger).atZone(ZoneId.systemDefault())
                    PixelText(
                        "${remainingText(trigger - nowMillis)}后 · ${target.format(DateTimeFormatter.ofPattern("HH:mm"))} ${alarm.title.ifBlank { "提醒" }}",
                        weight = FontWeight.Bold,
                        color = UiPine
                    )
                    PixelText("下一次闹钟 · ${alarmSummary(alarm)}", color = UiSoft)
                }
            }
            Icon(Icons.Rounded.KeyboardArrowRight, null, tint = UiSoft)
        }
    }
}

@Composable
private fun PixelLandscape() {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(138.dp)
            .background(UiSky, RoundedCornerShape(18.dp))
            .border(1.dp, UiLine, RoundedCornerShape(18.dp))
    ) {
        drawRect(UiSky)
        val sunCenter = Offset(size.width * 0.73f, size.height * 0.36f)
        drawCircle(UiAmber, radius = size.minDimension * 0.11f, center = sunCenter)
        val ray = size.minDimension * 0.022f
        listOf(
            Offset(sunCenter.x, sunCenter.y - 42), Offset(sunCenter.x, sunCenter.y + 42),
            Offset(sunCenter.x - 42, sunCenter.y), Offset(sunCenter.x + 42, sunCenter.y),
            Offset(sunCenter.x - 30, sunCenter.y - 30), Offset(sunCenter.x + 30, sunCenter.y - 30)
        ).forEach { p -> drawRect(UiOrange, topLeft = Offset(p.x - ray, p.y - ray), size = Size(ray * 2, ray * 2)) }

        val back = Path().apply {
            moveTo(0f, size.height * 0.72f)
            lineTo(size.width * 0.22f, size.height * 0.47f)
            lineTo(size.width * 0.38f, size.height * 0.66f)
            lineTo(size.width * 0.55f, size.height * 0.42f)
            lineTo(size.width * 0.78f, size.height * 0.67f)
            lineTo(size.width, size.height * 0.49f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(back, Color(0xFF9DB49A))

        val front = Path().apply {
            moveTo(0f, size.height * 0.82f)
            lineTo(size.width * 0.18f, size.height * 0.60f)
            lineTo(size.width * 0.34f, size.height * 0.79f)
            lineTo(size.width * 0.52f, size.height * 0.57f)
            lineTo(size.width * 0.67f, size.height * 0.80f)
            lineTo(size.width * 0.82f, size.height * 0.62f)
            lineTo(size.width, size.height * 0.78f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(front, Color(0xFF5F8767))

        fun tree(x: Float, baseY: Float, h: Float) {
            val trunkW = h * 0.11f
            drawRect(Color(0xFF7B5D35), Offset(x - trunkW / 2, baseY - h * 0.28f), Size(trunkW, h * 0.28f))
            drawRect(UiPine, Offset(x - h * 0.22f, baseY - h * 0.88f), Size(h * 0.44f, h * 0.22f))
            drawRect(UiPine, Offset(x - h * 0.30f, baseY - h * 0.68f), Size(h * 0.60f, h * 0.22f))
            drawRect(UiPine, Offset(x - h * 0.38f, baseY - h * 0.48f), Size(h * 0.76f, h * 0.22f))
        }
        tree(size.width * 0.07f, size.height, size.height * 0.62f)
        tree(size.width * 0.16f, size.height, size.height * 0.42f)
        tree(size.width * 0.88f, size.height, size.height * 0.55f)
        tree(size.width * 0.96f, size.height, size.height * 0.72f)
        tree(size.width * 0.76f, size.height, size.height * 0.35f)

        drawRect(Color(0xFF315A3B), Offset(0f, size.height * 0.91f), Size(size.width, size.height * 0.09f))
        var x = 10f
        while (x < size.width) {
            drawRect(UiAmber.copy(alpha = 0.55f), Offset(x, size.height * 0.93f), Size(5f, 5f))
            x += 34f
        }
    }
}

@Composable
private fun HabitHomeRow(c: AppController, habit: Habit, onEdit: () -> Unit, onToggle: (Boolean) -> Unit) {
    val count = c.todayCount(habit.id)
    val complete = count >= habit.targetCount
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).background(UiWarm, RoundedCornerShape(8.dp)).border(1.dp, UiLine, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) { PixelText(habitGlyph(habit.title), color = UiPine, weight = FontWeight.Black) }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            PixelText(habit.title, weight = FontWeight.Bold, color = UiInk)
            PixelText(
                if (habit.targetCount <= 1) "连续 ${c.currentStreak(habit.id)} 天" else "$count / ${habit.targetCount} ${habit.unit} · 连续 ${c.currentStreak(habit.id)} 天",
                color = UiSoft,
                size = MaterialTheme.typography.labelMedium.fontSize
            )
        }
        Icon(Icons.Rounded.KeyboardArrowRight, null, tint = UiLine, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        CheckSquare(checked = complete, onClick = { onToggle(!complete) })
    }
}

@Composable
private fun CheckSquare(checked: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .background(if (checked) UiAmber else UiPaper, RoundedCornerShape(7.dp))
            .border(2.dp, if (checked) UiOrange else UiLine, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (checked) Icon(Icons.Rounded.Check, "已完成", tint = Color.White, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun HomeAlarmRow(alarm: AlarmRule, onEdit: () -> Unit, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Alarm, null, tint = UiPine, modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(10.dp))
        PixelText("%02d:%02d".format(alarm.hour, alarm.minute), weight = FontWeight.Black, size = MaterialTheme.typography.titleMedium.fontSize)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            PixelText(alarm.title.ifBlank { "提醒" }, color = UiSoft)
            PixelText(scheduleLabel(alarm.scheduleType), color = UiSoft, size = MaterialTheme.typography.labelSmall.fontSize)
        }
        Switch(checked = alarm.enabled, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun EmptyLine(text: String) {
    Surface(color = UiWarm.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp)) {
        PixelText(text, modifier = Modifier.fillMaxWidth().padding(13.dp), color = UiSoft)
    }
}

@Composable
private fun AlarmPage(c: AppController, nowMillis: Long) {
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<AlarmRule?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PixelText("闹钟", color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.headlineMedium.fontSize)
                    PixelText("系统后台调度 · 点击任意闹钟可编辑", color = UiSoft)
                }
                IconButton(onClick = {
                    creating = true
                    editor = AlarmRule(title = "", scheduleType = ScheduleType.DAILY)
                }) { Icon(Icons.Rounded.AddAlarm, "新建闹钟", tint = UiPine) }
            }
        }

        if (c.alarms.isEmpty()) item { EmptyLine("还没有闹钟，点右上角添加") }

        items(c.alarms.sortedWith(compareBy<AlarmRule> { it.hour }.thenBy { it.minute }), key = { it.id }) { alarm ->
            val next = if (alarm.enabled) c.scheduler.nextTriggerMillis(alarm, nowMillis) else null
            Card(
                modifier = Modifier.fillMaxWidth().clickable { creating = false; editor = alarm },
                colors = CardDefaults.cardColors(containerColor = UiPaper),
                shape = RoundedCornerShape(17.dp),
                border = BorderStroke(1.dp, UiLine),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Alarm, null, tint = UiAmber, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            PixelText("%02d:%02d".format(alarm.hour, alarm.minute), weight = FontWeight.Black, size = MaterialTheme.typography.headlineSmall.fontSize)
                            PixelText(alarm.title.ifBlank { "未命名闹钟" }, color = UiSoft)
                        }
                        Switch(checked = alarm.enabled, onCheckedChange = { scope.launch { c.toggleAlarm(alarm) } })
                    }
                    Surface(color = UiWarm, shape = RoundedCornerShape(10.dp)) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Schedule, null, tint = UiPine, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(7.dp))
                            PixelText(
                                if (!alarm.enabled) "已暂停" else nextExecutionText(next, nowMillis),
                                color = if (alarm.enabled) UiPine else UiSoft,
                                weight = FontWeight.Bold
                            )
                        }
                    }
                    PixelText(alarmSummary(alarm), color = UiSoft)
                }
            }
        }
    }

    editor?.let { initial ->
        AlarmEditorDialog(
            initial = initial,
            title = if (creating) "新建闹钟" else "编辑闹钟",
            onDismiss = { editor = null },
            onDelete = if (creating) null else {{ scope.launch { c.deleteAlarm(initial); editor = null } }},
            onSave = { rule ->
                scope.launch {
                    if (creating) c.addAlarm(rule.copy(id = 0))
                    else c.updateAlarm(rule.copy(id = initial.id, createdAt = initial.createdAt))
                    editor = null
                }
            }
        )
    }
}

@Composable
private fun StatsPage(c: AppController) {
    val weekDone = c.habits.sumOf { c.completionCountLastDays(it.id, 7) }
    val weekTarget = c.habits.sumOf { c.scheduledCountLastDays(it, 7) }.coerceAtLeast(1)
    val monthDone = c.habits.sumOf { c.completionCountLastDays(it.id, 30) }
    val monthTarget = c.habits.sumOf { c.scheduledCountLastDays(it, 30) }.coerceAtLeast(1)
    val bestStreak = c.habits.maxOfOrNull { c.longestStreak(it.id) } ?: 0

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PixelText("统计", color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.headlineMedium.fontSize)
            PixelText("看看这一段时间坚持得怎么样", color = UiSoft)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("近 7 天", "${weekDone * 100 / weekTarget}%", "$weekDone/$weekTarget", Modifier.weight(1f))
                StatCard("近 30 天", "${monthDone * 100 / monthTarget}%", "$monthDone/$monthTarget", Modifier.weight(1f))
            }
        }
        item { StatCard("最长连续", "${bestStreak} 天", "所有打卡项目中的最高纪录", Modifier.fillMaxWidth()) }
        item { PixelText("项目详情", color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize) }

        if (c.habits.isEmpty()) {
            item { EmptyLine("暂无打卡项目") }
        } else {
            items(c.habits, key = { it.id }) { habit ->
                val d7 = c.completionCountLastDays(habit.id, 7)
                val t7 = c.scheduledCountLastDays(habit, 7).coerceAtLeast(1)
                val d30 = c.completionCountLastDays(habit.id, 30)
                val t30 = c.scheduledCountLastDays(habit, 30).coerceAtLeast(1)
                Card(
                    colors = CardDefaults.cardColors(containerColor = UiPaper),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, UiLine)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(30.dp).background(UiWarm, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                PixelText(habitGlyph(habit.title), color = UiPine, weight = FontWeight.Black)
                            }
                            Spacer(Modifier.width(9.dp))
                            PixelText(habit.title, modifier = Modifier.weight(1f), weight = FontWeight.Bold)
                            PixelText("连续 ${c.currentStreak(habit.id)} 天", color = UiOrange, weight = FontWeight.Bold)
                        }
                        PixelText("7 天完成率 ${d7 * 100 / t7}% · 30 天完成率 ${d30 * 100 / t30}%", color = UiSoft)
                        LinearProgressIndicator(
                            progress = { (d30.toFloat() / t30).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(7.dp),
                            color = UiAmber,
                            trackColor = UiWarm
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, detail: String, modifier: Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = UiPaper),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, UiLine)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PixelText(title, color = UiSoft)
            PixelText(value, color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.headlineSmall.fontSize)
            PixelText(detail, color = UiSoft, size = MaterialTheme.typography.labelSmall.fontSize)
        }
    }
}

@Composable
private fun MinePage(c: AppController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var isWorkday by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelAvatar()
                Spacer(Modifier.width(12.dp))
                Column {
                    PixelText("我的", color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.headlineMedium.fontSize)
                    PixelText("DayPulse 本机设置", color = UiSoft)
                }
            }
        }

        item {
            SettingsCard("后台闹钟") {
                PixelText("闹钟由 Android 系统保管，不要求 App 一直常驻后台。", color = UiSoft)
                PixelText(if (c.scheduler.canScheduleExact()) "✓ 精确闹钟已允许" else "需要开启精确闹钟权限", color = if (c.scheduler.canScheduleExact()) UiPine else UiDanger, weight = FontWeight.Bold)
                if (!c.scheduler.canScheduleExact() && Build.VERSION.SDK_INT >= 31) {
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }) { Text("去开启") }
                }
            }
        }

        item {
            SettingsCard("AI · SiliconFlow / DeepSeek") {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            runCatching { c.saveApiKey(apiKey) }
                                .onSuccess { apiKey = ""; message = "API Key 已保存" }
                                .onFailure { message = it.message }
                        }
                    }) { Text(if (c.hasApiKey) "更新 Key" else "保存 Key") }
                    if (c.hasApiKey) {
                        OutlinedButton(onClick = { scope.launch { c.clearApiKey(); message = "API Key 已删除" } }) { Text("删除") }
                    }
                }
                PixelText("Key 只保存在手机加密存储中。", color = UiSoft)
            }
        }

        item {
            SettingsCard("工作日覆盖") {
                OutlinedTextField(date, { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("日期 YYYY-MM-DD") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelText("设为工作日", modifier = Modifier.weight(1f))
                    Switch(checked = isWorkday, onCheckedChange = { isWorkday = it })
                }
                Button(onClick = {
                    scope.launch {
                        runCatching { c.setWorkdayOverride(date, isWorkday) }
                            .onSuccess { message = "工作日覆盖已保存" }
                            .onFailure { message = it.message }
                    }
                }) { Text("保存覆盖") }
                c.workdayOverrides.take(10).forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelText("${item.dateKey} · ${if (item.isWorkday) "工作日" else "休息日"}", modifier = Modifier.weight(1f), color = UiSoft)
                        IconButton(onClick = { scope.launch { c.deleteWorkdayOverride(item.dateKey) } }) {
                            Icon(Icons.Rounded.DeleteOutline, "删除", tint = UiDanger)
                        }
                    }
                }
            }
        }

        message?.let { item { PixelText(it, color = UiPine, weight = FontWeight.Bold) } }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = UiPaper),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, UiLine)
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            PixelText(title, color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.titleMedium.fontSize)
            content()
        }
    }
}

@Composable
private fun HabitEditorDialog(
    initial: Habit,
    creating: Boolean,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (Habit) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial.title) }
    var target by remember(initial) { mutableStateOf(initial.targetCount.toString()) }
    var unit by remember(initial) { mutableStateOf(initial.unit) }
    var mask by remember(initial) { mutableIntStateOf(initial.weekdaysMask) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = UiPaper,
        title = { PixelText(if (creating) "新建打卡" else "编辑打卡", color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("名称") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(target, { target = it }, modifier = Modifier.weight(1f), label = { Text("每日目标") })
                    OutlinedTextField(unit, { unit = it }, modifier = Modifier.weight(1f), label = { Text("单位") })
                }
                PixelText("执行星期", color = UiSoft)
                (1..7).chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.forEach { day ->
                            val bit = 1 shl (day - 1)
                            FilterChip(
                                selected = mask and bit != 0,
                                onClick = { mask = mask xor bit },
                                label = { Text("${"一二三四五六日"[day - 1]}") }
                            )
                        }
                    }
                }
                error?.let { PixelText(it, color = UiDanger) }
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    require(name.isNotBlank()) { "请输入名称" }
                    require(mask != 0) { "至少选择一天" }
                    val targetValue = target.toIntOrNull()?.coerceAtLeast(1) ?: error("目标必须是数字")
                    initial.copy(title = name.trim(), targetCount = targetValue, unit = unit.trim().ifBlank { "次" }, weekdaysMask = mask)
                }.onSuccess(onSave).onFailure { error = it.message }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("删除", color = UiDanger) } }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun AlarmEditorDialog(
    initial: AlarmRule,
    title: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (AlarmRule) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial.title) }
    var type by remember(initial) { mutableStateOf(initial.scheduleType) }
    var time by remember(initial) { mutableStateOf("%02d:%02d".format(initial.hour, initial.minute)) }
    var date by remember(initial) {
        mutableStateOf(initial.onceAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() } ?: LocalDate.now().plusDays(1).toString())
    }
    var weekdaysMask by remember(initial) { mutableIntStateOf(if (initial.weekdaysMask == 0) 31 else initial.weekdaysMask) }
    var interval by remember(initial) { mutableStateOf((initial.intervalMinutes ?: 120).toString()) }
    var startTime by remember(initial) { mutableStateOf(minutesToTime(initial.windowStartMinutes ?: 540)) }
    var endTime by remember(initial) { mutableStateOf(minutesToTime(initial.windowEndMinutes ?: 1260)) }
    var sound by remember(initial) { mutableStateOf(initial.sound) }
    var vibration by remember(initial) { mutableStateOf(initial.vibration) }
    var notification by remember(initial) { mutableStateOf(initial.notification) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = UiPaper,
        title = { PixelText(title, color = UiPine, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                item { OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("名称") }) }
                item {
                    PixelText("重复方式", color = UiSoft)
                    ScheduleType.entries.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            row.forEach { value ->
                                FilterChip(selected = type == value, onClick = { type = value }, label = { Text(scheduleLabel(value)) })
                            }
                        }
                    }
                }
                if (type != ScheduleType.INTERVAL) item { OutlinedTextField(time, { time = it }, modifier = Modifier.fillMaxWidth(), label = { Text("时间 HH:mm") }) }
                if (type == ScheduleType.ONCE) item { OutlinedTextField(date, { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("日期 YYYY-MM-DD") }) }
                if (type == ScheduleType.WEEKLY) {
                    item {
                        PixelText("星期", color = UiSoft)
                        (1..7).chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                row.forEach { day ->
                                    val bit = 1 shl (day - 1)
                                    FilterChip(selected = weekdaysMask and bit != 0, onClick = { weekdaysMask = weekdaysMask xor bit }, label = { Text("${"一二三四五六日"[day - 1]}") })
                                }
                            }
                        }
                    }
                }
                if (type == ScheduleType.INTERVAL) {
                    item {
                        OutlinedTextField(interval, { interval = it }, modifier = Modifier.fillMaxWidth(), label = { Text("间隔分钟") })
                        Spacer(Modifier.height(7.dp))
                        OutlinedTextField(startTime, { startTime = it }, modifier = Modifier.fillMaxWidth(), label = { Text("开始 HH:mm") })
                        Spacer(Modifier.height(7.dp))
                        OutlinedTextField(endTime, { endTime = it }, modifier = Modifier.fillMaxWidth(), label = { Text("结束 HH:mm") })
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) { PixelText("铃声", Modifier.weight(1f)); Switch(sound, { sound = it }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { PixelText("震动", Modifier.weight(1f)); Switch(vibration, { vibration = it }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { PixelText("通知", Modifier.weight(1f)); Switch(notification, { notification = it }) }
                    error?.let { PixelText(it, color = UiDanger) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    require(name.isNotBlank()) { "名称不能为空" }
                    val format = DateTimeFormatter.ofPattern("H:mm")
                    val base = LocalTime.parse(if (type == ScheduleType.INTERVAL) startTime else time, format)
                    val start = if (type == ScheduleType.INTERVAL) LocalTime.parse(startTime, format) else null
                    val end = if (type == ScheduleType.INTERVAL) LocalTime.parse(endTime, format) else null
                    if (start != null && end != null) require(end.hour * 60 + end.minute >= start.hour * 60 + start.minute) { "结束时间不能早于开始时间" }
                    if (type == ScheduleType.WEEKLY) require(weekdaysMask != 0) { "至少选择一天" }
                    initial.copy(
                        title = name.trim(),
                        scheduleType = type,
                        hour = base.hour,
                        minute = base.minute,
                        weekdaysMask = if (type == ScheduleType.WEEKLY) weekdaysMask else 0,
                        onceAt = if (type == ScheduleType.ONCE) LocalDate.parse(date).atTime(base).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else null,
                        intervalMinutes = if (type == ScheduleType.INTERVAL) interval.toIntOrNull()?.coerceAtLeast(1) ?: error("间隔必须是数字") else null,
                        windowStartMinutes = start?.let { it.hour * 60 + it.minute },
                        windowEndMinutes = end?.let { it.hour * 60 + it.minute },
                        sound = sound,
                        vibration = vibration,
                        notification = notification
                    )
                }.onSuccess(onSave).onFailure { error = it.message ?: "配置不正确" }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("删除", color = UiDanger) } }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun PixelText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = UiInk,
    weight: FontWeight = FontWeight.Normal,
    size: TextUnit = MaterialTheme.typography.bodyMedium.fontSize
) {
    Text(text = text, modifier = modifier, color = color, fontWeight = weight, fontFamily = FontFamily.Monospace, fontSize = size)
}

private fun habitGlyph(title: String): String = when {
    title.contains("水") -> "水"
    title.contains("读") || title.contains("书") -> "书"
    title.contains("跑") || title.contains("运动") -> "跑"
    title.contains("冥") || title.contains("静") -> "静"
    title.contains("日记") || title.contains("写") -> "记"
    title.contains("起") || title.contains("早") -> "☀"
    else -> "✓"
}

private fun remainingText(diffMillis: Long): String {
    val totalSeconds = (diffMillis.coerceAtLeast(0L) + 999L) / 1_000L
    if (totalSeconds < 60) return "${totalSeconds}秒"
    val totalMinutes = totalSeconds / 60
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时${minutes}分钟"
        else -> "${minutes}分钟"
    }
}

private fun nextExecutionText(triggerMillis: Long?, nowMillis: Long): String {
    if (triggerMillis == null) return "无下次执行"
    val target = Instant.ofEpochMilli(triggerMillis).atZone(ZoneId.systemDefault())
    val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val dayText = when (target.toLocalDate()) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> target.format(DateTimeFormatter.ofPattern("M月d日"))
    }
    return "下次：$dayText ${target.format(DateTimeFormatter.ofPattern("HH:mm"))} · ${remainingText(triggerMillis - nowMillis)}后"
}

private fun alarmSummary(alarm: AlarmRule): String = when (alarm.scheduleType) {
    ScheduleType.ONCE -> alarm.onceAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) } ?: "单次"
    ScheduleType.DAILY -> "每天 %02d:%02d".format(alarm.hour, alarm.minute)
    ScheduleType.WEEKLY -> "每周 ${maskText(alarm.weekdaysMask)} · %02d:%02d".format(alarm.hour, alarm.minute)
    ScheduleType.WORKDAY -> "工作日 %02d:%02d".format(alarm.hour, alarm.minute)
    ScheduleType.INTERVAL -> "${minutesToTime(alarm.windowStartMinutes ?: 540)}–${minutesToTime(alarm.windowEndMinutes ?: 1260)} · 每 ${alarm.intervalMinutes ?: 60} 分钟"
}

private fun scheduleLabel(type: ScheduleType): String = when (type) {
    ScheduleType.ONCE -> "单次"
    ScheduleType.DAILY -> "每天"
    ScheduleType.WEEKLY -> "每周"
    ScheduleType.WORKDAY -> "工作日"
    ScheduleType.INTERVAL -> "循环"
}

private fun maskText(mask: Int): String = (1..7)
    .filter { mask and (1 shl (it - 1)) != 0 }
    .joinToString("/") { "周${"一二三四五六日"[it - 1]}" }

private fun minutesToTime(value: Int): String = "%02d:%02d".format(value / 60, value % 60)

private fun AiAlarmDraft.toUiAlarmRule(): AlarmRule {
    val type = requireNotNull(scheduleType) { "AI 没有识别出重复方式" }
    val base = LocalTime.parse(time ?: startTime ?: "08:00", DateTimeFormatter.ofPattern("H:mm"))
    val mask = weekdays.fold(0) { current, day -> if (day in 1..7) current or (1 shl (day - 1)) else current }
    val start = startTime?.let { LocalTime.parse(it, DateTimeFormatter.ofPattern("H:mm")) }
    val end = endTime?.let { LocalTime.parse(it, DateTimeFormatter.ofPattern("H:mm")) }
    return AlarmRule(
        title = title.ifBlank { "AI 提醒" },
        scheduleType = type,
        hour = base.hour,
        minute = base.minute,
        weekdaysMask = mask,
        onceAt = if (type == ScheduleType.ONCE) LocalDate.parse(requireNotNull(date) { "AI 没有识别出日期" }).atTime(base).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else null,
        intervalMinutes = intervalMinutes,
        windowStartMinutes = start?.let { it.hour * 60 + it.minute },
        windowEndMinutes = end?.let { it.hour * 60 + it.minute },
        sound = sound,
        vibration = vibration,
        notification = notification
    )
}

private fun applyAiUpdate(base: AlarmRule, draft: AiAlarmDraft): AlarmRule {
    val format = DateTimeFormatter.ofPattern("H:mm")
    val desiredType = draft.scheduleType ?: base.scheduleType
    val desiredTime = draft.time?.let { runCatching { LocalTime.parse(it, format) }.getOrNull() }
    val desiredStart = draft.startTime?.let { runCatching { LocalTime.parse(it, format) }.getOrNull() }
    val desiredEnd = draft.endTime?.let { runCatching { LocalTime.parse(it, format) }.getOrNull() }
    val weekdaysMask = if (draft.weekdays.isNotEmpty()) {
        draft.weekdays.fold(0) { current, day -> if (day in 1..7) current or (1 shl (day - 1)) else current }
    } else base.weekdaysMask

    val hour = when {
        desiredType == ScheduleType.INTERVAL && desiredStart != null -> desiredStart.hour
        desiredTime != null -> desiredTime.hour
        else -> base.hour
    }
    val minute = when {
        desiredType == ScheduleType.INTERVAL && desiredStart != null -> desiredStart.minute
        desiredTime != null -> desiredTime.minute
        else -> base.minute
    }

    val onceAt = if (desiredType == ScheduleType.ONCE) {
        val baseDate = base.onceAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        val targetDate = draft.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: baseDate ?: LocalDate.now().plusDays(1)
        targetDate.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } else null

    return base.copy(
        title = draft.title.ifBlank { base.title },
        scheduleType = desiredType,
        hour = hour,
        minute = minute,
        weekdaysMask = weekdaysMask,
        onceAt = onceAt,
        intervalMinutes = draft.intervalMinutes ?: base.intervalMinutes,
        windowStartMinutes = desiredStart?.let { it.hour * 60 + it.minute } ?: base.windowStartMinutes,
        windowEndMinutes = desiredEnd?.let { it.hour * 60 + it.minute } ?: base.windowEndMinutes
    )
}
