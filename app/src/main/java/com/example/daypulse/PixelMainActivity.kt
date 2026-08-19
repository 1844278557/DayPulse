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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.daypulse.alarm.NotificationHelper
import com.example.daypulse.model.*
import com.example.daypulse.voice.SpeechInputController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PxCream = Color(0xFFFFF8E7)
private val PxPaper = Color(0xFFFFFDF6)
private val PxPaper2 = Color(0xFFF3E8C8)
private val PxPine = Color(0xFF204A36)
private val PxPine2 = Color(0xFF35634A)
private val PxMoss = Color(0xFF718561)
private val PxAmber = Color(0xFFF2A91F)
private val PxOrange = Color(0xFFE9772D)
private val PxInk = Color(0xFF183528)
private val PxSoft = Color(0xFF6B746B)
private val PxLine = Color(0xFFCEBD8B)
private val PxDanger = Color(0xFFB64A3D)
private val PxSky = Color(0xFFE9E9C9)

class PixelMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.ensureChannel(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = PxPine,
                    secondary = PxAmber,
                    tertiary = PxOrange,
                    background = PxCream,
                    surface = PxPaper,
                    surfaceVariant = PxPaper2,
                    onPrimary = Color.White,
                    onBackground = PxInk,
                    onSurface = PxInk,
                    outline = PxLine,
                    error = PxDanger
                )
            ) { PixelDayPulseApp() }
        }
    }
}

private enum class PixelTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Rounded.Home),
    CHECKIN("打卡", Icons.Rounded.CheckCircle),
    AI("AI", Icons.Rounded.Mic),
    ALARM("闹钟", Icons.Rounded.Alarm),
    STATS("统计", Icons.Rounded.BarChart)
}

@Composable
private fun PixelDayPulseApp() {
    val context = LocalContext.current
    val c = remember { AppController(context) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(PixelTab.HOME) }
    var settingsOpen by remember { mutableStateOf(false) }

    var voiceListening by remember { mutableStateOf(false) }
    var voiceTranscript by remember { mutableStateOf("") }
    var aiStatus by remember { mutableStateOf<String?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiDraft by remember { mutableStateOf<AiAlarmDraft?>(null) }
    var aiEditor by remember { mutableStateOf<AlarmRule?>(null) }
    var deleteMatches by remember { mutableStateOf<List<AlarmRule>>(emptyList()) }
    var selectedDeleteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    fun sendToAi(text: String) {
        if (text.isBlank() || aiBusy) return
        voiceTranscript = text.trim()
        tab = PixelTab.AI
        aiBusy = true
        aiStatus = "AI 正在理解你的需求…"
        scope.launch {
            c.parseAi(text.trim()).onSuccess { parsed ->
                aiDraft = parsed
                if (parsed.action == AiActionType.CREATE) {
                    aiEditor = parsed.toPixelRule()
                    aiStatus = "已整理成闹钟草稿，确认前可以修改。"
                } else {
                    deleteMatches = c.findAlarmMatches(parsed)
                    selectedDeleteIds = when {
                        parsed.deleteAllMatches -> deleteMatches.map { it.id }.toSet()
                        deleteMatches.size == 1 -> setOf(deleteMatches.first().id)
                        else -> emptySet()
                    }
                    aiStatus = if (deleteMatches.isEmpty()) "没有找到匹配的闹钟。" else "找到 ${deleteMatches.size} 个候选，请确认删除。"
                }
            }.onFailure {
                aiStatus = it.message ?: "AI 请求失败"
            }
            aiBusy = false
        }
    }

    val speech = remember {
        SpeechInputController(
            context = context,
            onListeningChange = { voiceListening = it },
            onStatus = { aiStatus = it },
            onPartialText = { voiceTranscript = it },
            onFinalText = { sendToAi(it) }
        )
    }
    DisposableEffect(speech) { onDispose { speech.destroy() } }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        aiStatus = if (granted) "麦克风已授权，按住中间 AI 说话" else "需要麦克风权限才能语音操作"
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        c.load()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun startVoice() {
        tab = PixelTab.AI
        voiceTranscript = ""
        if (aiBusy) {
            aiStatus = "AI 正在处理上一条需求"
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            aiStatus = "授权麦克风后，再按住 AI 说话"
        } else {
            speech.start()
        }
    }

    Scaffold(
        containerColor = PxCream,
        topBar = { PixelTopBar(onSettings = { settingsOpen = true }) },
        bottomBar = {
            PixelBottomBar(
                current = tab,
                listening = voiceListening,
                onSelect = { tab = it },
                onVoiceStart = { startVoice() },
                onVoiceEnd = { speech.stopAndFinalize() }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(PxCream)) {
            if (c.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = PxPine)
            } else {
                when (tab) {
                    PixelTab.HOME -> PixelHomeScreen(c, onOpenCheckin = { tab = PixelTab.CHECKIN }, onOpenAlarm = { tab = PixelTab.ALARM })
                    PixelTab.CHECKIN -> PixelCheckinScreen(c)
                    PixelTab.AI -> PixelAiScreen(voiceTranscript, aiStatus, aiBusy, voiceListening)
                    PixelTab.ALARM -> PixelAlarmScreen(c)
                    PixelTab.STATS -> PixelStatsScreen(c)
                }
            }

            if (voiceListening || aiBusy) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).border(2.dp, PxPine, RoundedCornerShape(4.dp)),
                    color = if (voiceListening) PxPine else PxPaper,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        if (voiceListening) "● 正在听… 松开 AI 发送" else "AI 正在处理…",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = if (voiceListening) Color.White else PxPine,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (settingsOpen) {
        Dialog(onDismissRequest = { settingsOpen = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).border(2.dp, PxPine, RoundedCornerShape(4.dp)),
                color = PxCream,
                shape = RoundedCornerShape(4.dp)
            ) { PixelSettingsScreen(c, onClose = { settingsOpen = false }) }
        }
    }

    aiEditor?.let { initial ->
        PixelAlarmEditor(
            initial = initial,
            title = "AI 草稿 · 确认前可修改",
            onDismiss = { aiEditor = null },
            onSave = { edited ->
                scope.launch {
                    c.addAlarm(edited.copy(id = 0))
                    aiEditor = null
                    aiDraft = null
                    aiStatus = "✓ 闹钟已创建"
                }
            }
        )
    }

    if (aiDraft?.action == AiActionType.DELETE && deleteMatches.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { aiDraft = null; deleteMatches = emptyList(); selectedDeleteIds = emptySet() },
            containerColor = PxPaper,
            title = { PixelText("确认删除闹钟", weight = FontWeight.Black, color = PxDanger) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    deleteMatches.forEach { alarm ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selectedDeleteIds = if (alarm.id in selectedDeleteIds) selectedDeleteIds - alarm.id else selectedDeleteIds + alarm.id
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = alarm.id in selectedDeleteIds, onCheckedChange = null)
                            Column(Modifier.weight(1f)) {
                                PixelText(alarm.title.ifBlank { "未命名闹钟" }, weight = FontWeight.Bold)
                                PixelText(pixelAlarmSummary(alarm), color = PxSoft)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedDeleteIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = PxDanger),
                    onClick = {
                        scope.launch {
                            val chosen = deleteMatches.filter { it.id in selectedDeleteIds }
                            c.deleteAlarms(chosen)
                            aiStatus = "✓ 已删除 ${chosen.size} 个闹钟"
                            aiDraft = null
                            deleteMatches = emptyList()
                            selectedDeleteIds = emptySet()
                        }
                    }
                ) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { aiDraft = null; deleteMatches = emptyList(); selectedDeleteIds = emptySet() }) { Text("取消") } }
        )
    }
}

@Composable
private fun PixelTopBar(onSettings: () -> Unit) {
    Surface(color = PxPaper, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(30.dp).background(PxPine, RoundedCornerShape(3.dp)).border(2.dp, PxAmber, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center
            ) { PixelText("DP", color = Color.White, weight = FontWeight.Black) }
            Spacer(Modifier.width(10.dp))
            PixelText("DayPulse", size = MaterialTheme.typography.titleLarge.fontSize, weight = FontWeight.Black, color = PxPine, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "设置", tint = PxPine) }
        }
    }
}

@Composable
private fun PixelBottomBar(
    current: PixelTab,
    listening: Boolean,
    onSelect: (PixelTab) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceEnd: () -> Unit
) {
    Surface(color = PxPaper, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically) {
            PixelNavItem(PixelTab.HOME, current, onSelect, Modifier.weight(1f))
            PixelNavItem(PixelTab.CHECKIN, current, onSelect, Modifier.weight(1f))

            Box(
                modifier = Modifier.weight(1.12f),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .background(if (listening) PxOrange else PxPine, RoundedCornerShape(6.dp))
                        .border(3.dp, PxAmber, RoundedCornerShape(6.dp))
                        .pointerInput(listening) {
                            awaitEachGesture {
                                awaitFirstDown()
                                val releasedQuickly = withTimeoutOrNull(360L) { waitForUpOrCancellation() }
                                if (releasedQuickly != null) {
                                    onSelect(PixelTab.AI)
                                } else {
                                    onVoiceStart()
                                    waitForUpOrCancellation()
                                    onVoiceEnd()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (listening) Icons.Rounded.GraphicEq else Icons.Rounded.Mic, "AI 语音", tint = Color.White)
                        PixelText(if (listening) "松开发送" else "按住 AI", color = Color.White, size = MaterialTheme.typography.labelSmall.fontSize, weight = FontWeight.Bold)
                    }
                }
            }

            PixelNavItem(PixelTab.ALARM, current, onSelect, Modifier.weight(1f))
            PixelNavItem(PixelTab.STATS, current, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PixelNavItem(tab: PixelTab, current: PixelTab, onSelect: (PixelTab) -> Unit, modifier: Modifier = Modifier) {
    val selected = tab == current
    Column(
        modifier.fillMaxHeight().clickable { onSelect(tab) }.padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(tab.icon, tab.label, tint = if (selected) PxOrange else PxMoss, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(3.dp))
        PixelText(tab.label, color = if (selected) PxPine else PxSoft, weight = if (selected) FontWeight.Black else FontWeight.Normal, size = MaterialTheme.typography.labelSmall.fontSize)
    }
}

@Composable
private fun PixelHomeScreen(c: AppController, onOpenCheckin: () -> Unit, onOpenAlarm: () -> Unit) {
    val today = c.todayHabits()
    val done = c.completedTodayCount()
    val nextAlarm = c.alarms.filter { it.enabled }
        .mapNotNull { a -> c.scheduler.nextTriggerMillis(a)?.let { a to it } }
        .minByOrNull { it.second }
    val overall7Done = c.habits.sumOf { c.completionCountLastDays(it.id, 7) }
    val overall7Target = c.habits.sumOf { c.scheduledCountLastDays(it, 7) }.coerceAtLeast(1)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PixelForestHero() }

        item {
            PixelPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        PixelText("今日打卡", weight = FontWeight.Black, color = PxPine, size = MaterialTheme.typography.titleLarge.fontSize)
                        PixelText("TODAY CHECK-IN", color = PxSoft, size = MaterialTheme.typography.labelMedium.fontSize)
                    }
                    PixelText("$done/${today.size}", weight = FontWeight.Black, color = PxOrange, size = MaterialTheme.typography.headlineMedium.fontSize)
                }
                Spacer(Modifier.height(10.dp))
                val p = if (today.isEmpty()) 0f else done.toFloat() / today.size
                PixelProgress(p)
                Spacer(Modifier.height(8.dp))
                PixelText("${(p * 100).toInt()}% 完成 · 继续保持今天的节奏", color = PxSoft)
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText("TODAY TO-DO", weight = FontWeight.Black, color = PxPine, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenCheckin) { Text("全部打卡") }
            }
        }

        if (today.isEmpty()) {
            item { PixelPanel { PixelText("今天还没有安排打卡项目。", color = PxSoft) } }
        } else {
            items(today.take(6), key = { it.id }) { habit -> PixelTodoRow(c, habit) }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText("NEXT ALARM", weight = FontWeight.Black, color = PxPine, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenAlarm) { Text("闹钟列表") }
            }
        }

        item {
            PixelPanel {
                if (nextAlarm == null) {
                    PixelText("还没有开启的闹钟", color = PxSoft)
                } else {
                    val (alarm, trigger) = nextAlarm
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelBadge("⏰", PxAmber)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            PixelText(alarm.title.ifBlank { "未命名闹钟" }, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize)
                            PixelText(pixelAlarmSummary(alarm), color = PxSoft)
                            PixelText(nextExecutionPixel(alarm, trigger, System.currentTimeMillis()), color = PxOrange, weight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item { PixelText("THIS WEEK", weight = FontWeight.Black, color = PxPine) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PixelStatCard("7日完成", "$overall7Done/$overall7Target", Modifier.weight(1f))
                PixelStatCard("今日打卡", "$done/${today.size}", Modifier.weight(1f))
                PixelStatCard("闹钟", "${c.alarms.count { it.enabled }}", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PixelForestHero() {
    Box(
        Modifier.fillMaxWidth().height(154.dp).clip(RoundedCornerShape(4.dp)).background(PxSky).border(2.dp, PxPine, RoundedCornerShape(4.dp))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 28f
            drawRect(PxSky)
            for (x in 19..22) for (y in 2..5) drawRect(PxAmber, topLeft = androidx.compose.ui.geometry.Offset(x * u, y * u), size = androidx.compose.ui.geometry.Size(u, u))
            for (i in 0..7) drawRect(Color(0xFF9BAE78), topLeft = androidx.compose.ui.geometry.Offset(i * 4f * u - 3f * u, size.height - (5 + i % 3) * u), size = androidx.compose.ui.geometry.Size(7f * u, 8f * u))
            for (i in 0..6) drawRect(PxPine2, topLeft = androidx.compose.ui.geometry.Offset(i * 5f * u, size.height - (4 + i % 2) * u), size = androidx.compose.ui.geometry.Size(4f * u, 6f * u))
            for (x in listOf(2, 7, 12, 24)) {
                drawRect(PxPine, topLeft = androidx.compose.ui.geometry.Offset(x * u, size.height - 8f * u), size = androidx.compose.ui.geometry.Size(2f * u, 7f * u))
                drawRect(PxPine, topLeft = androidx.compose.ui.geometry.Offset((x - 1) * u, size.height - 6f * u), size = androidx.compose.ui.geometry.Size(4f * u, 2f * u))
            }
            drawRect(Color(0xFFD6A24E), topLeft = androidx.compose.ui.geometry.Offset(13f * u, size.height - 5f * u), size = androidx.compose.ui.geometry.Size(3f * u, 5f * u))
        }
        Column(Modifier.padding(16.dp)) {
            PixelText("早上好，DayPulse", color = PxPine, weight = FontWeight.Black, size = MaterialTheme.typography.headlineSmall.fontSize)
            Spacer(Modifier.height(4.dp))
            PixelText("踏上今天的节奏。", color = PxInk, weight = FontWeight.Bold)
            PixelText(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd")), color = PxSoft)
        }
    }
}

@Composable
private fun PixelTodoRow(c: AppController, habit: Habit) {
    val scope = rememberCoroutineScope()
    val count = c.todayCount(habit.id)
    val complete = count >= habit.targetCount
    PixelPanel(compact = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).background(if (complete) PxAmber else PxPaper2, RoundedCornerShape(2.dp)).border(2.dp, PxPine, RoundedCornerShape(2.dp)).clickable {
                    scope.launch { c.setHabitCompleted(habit, !complete) }
                },
                contentAlignment = Alignment.Center
            ) { if (complete) PixelText("✓", weight = FontWeight.Black, color = PxPine) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                PixelText(habit.title, weight = FontWeight.Bold)
                PixelText("$count/${habit.targetCount} ${habit.unit} · 连续 ${c.currentStreak(habit.id)} 天", color = PxSoft, size = MaterialTheme.typography.bodySmall.fontSize)
            }
            if (habit.targetCount > 1 && !complete) {
                FilledIconButton(
                    onClick = { scope.launch { c.changeHabitCount(habit, 1) } },
                    modifier = Modifier.size(34.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = PxPine)
                ) { Icon(Icons.Rounded.Add, "加一", modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun PixelCheckinScreen(c: AppController) {
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<Habit?>(null) }
    var creating by remember { mutableStateOf(false) }
    val today = c.todayHabits()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PixelText("今日打卡", size = MaterialTheme.typography.headlineMedium.fontSize, weight = FontWeight.Black, color = PxPine)
                    PixelText("DAILY CHECK-IN · ${LocalDate.now()}", color = PxSoft)
                }
                FilledIconButton(
                    onClick = { creating = true; editor = Habit(0, "", System.currentTimeMillis()) },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = PxPine)
                ) { Icon(Icons.Rounded.Add, "新增打卡") }
            }
        }
        if (today.isEmpty()) item { PixelPanel { PixelText("今天没有需要完成的打卡。", color = PxSoft) } }
        items(c.habits, key = { it.id }) { habit ->
            val scheduled = c.isHabitScheduled(habit)
            val count = c.todayCount(habit.id)
            val complete = count >= habit.targetCount
            PixelPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelBadge(if (complete) "✓" else if (scheduled) "□" else "–", if (complete) PxAmber else PxPaper2)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        PixelText(habit.title, weight = FontWeight.Black, size = MaterialTheme.typography.titleMedium.fontSize)
                        PixelText(if (scheduled) "今日 $count/${habit.targetCount} ${habit.unit}" else "今天不在计划内", color = PxSoft)
                        PixelText("连续 ${c.currentStreak(habit.id)} 天 · 最长 ${c.longestStreak(habit.id)} 天", color = PxOrange, size = MaterialTheme.typography.bodySmall.fontSize)
                    }
                    IconButton(onClick = { creating = false; editor = habit }) { Icon(Icons.Rounded.Edit, "编辑", tint = PxPine) }
                }
                Spacer(Modifier.height(8.dp))
                PixelProgress((count.toFloat() / habit.targetCount).coerceIn(0f, 1f))
                if (scheduled) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedIconButton(onClick = { scope.launch { c.changeHabitCount(habit, -1) } }, enabled = count > 0) { Icon(Icons.Rounded.Remove, "减一") }
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = { scope.launch { c.changeHabitCount(habit, 1) } }, enabled = count < habit.targetCount, colors = IconButtonDefaults.filledIconButtonColors(containerColor = PxPine)) { Icon(Icons.Rounded.Add, "加一") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { c.setHabitCompleted(habit, !complete) } }) { Text(if (complete) "取消完成" else "直接完成") }
                    }
                }
            }
        }
    }

    editor?.let { initial ->
        PixelHabitEditor(
            initial = initial,
            creating = creating,
            onDismiss = { editor = null },
            onDelete = if (creating) null else {{ scope.launch { c.deleteHabit(initial.id); editor = null } }},
            onSave = { edited ->
                scope.launch {
                    if (creating) c.addHabit(edited.title, edited.targetCount, edited.unit, edited.weekdaysMask)
                    else c.updateHabit(edited.copy(id = initial.id, createdAt = initial.createdAt))
                    editor = null
                }
            }
        )
    }
}

@Composable
private fun PixelAiScreen(transcript: String, status: String?, busy: Boolean, listening: Boolean) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PixelPanel {
                PixelText("AI 快捷操作", size = MaterialTheme.typography.headlineMedium.fontSize, weight = FontWeight.Black, color = PxPine)
                Spacer(Modifier.height(8.dp))
                PixelText("不需要打字。", weight = FontWeight.Bold)
                PixelText("长按底部中间的 AI → 说出需求 → 松手 → AI 自动理解 → 弹窗确认。", color = PxSoft)
            }
        }
        item {
            PixelPanel {
                PixelText("可以这样说", weight = FontWeight.Black, color = PxPine)
                Spacer(Modifier.height(8.dp))
                PixelText("• 工作日早上 7:30 叫我起床")
                PixelText("• 上午 9 点到晚上 9 点每两小时提醒喝水")
                PixelText("• 删除每天晚上十点的提醒")
            }
        }
        if (transcript.isNotBlank()) {
            item {
                PixelPanel {
                    PixelText("刚才听到", weight = FontWeight.Black, color = PxPine)
                    Spacer(Modifier.height(6.dp))
                    PixelText("“$transcript”", color = PxOrange, weight = FontWeight.Bold)
                }
            }
        }
        item {
            PixelPanel {
                PixelText("状态", weight = FontWeight.Black, color = PxPine)
                Spacer(Modifier.height(6.dp))
                PixelText(
                    when {
                        listening -> "● 正在听… 松开后立即发送"
                        busy -> "AI 正在处理…"
                        !status.isNullOrBlank() -> status
                        else -> "按住底部 AI 开始"
                    },
                    color = if (listening) PxOrange else PxSoft
                )
            }
        }
    }
}

@Composable
private fun PixelAlarmScreen(c: AppController) {
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<AlarmRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PixelText("闹钟", size = MaterialTheme.typography.headlineMedium.fontSize, weight = FontWeight.Black, color = PxPine)
                    PixelText("ALARM · 系统后台调度", color = PxSoft)
                }
                FilledIconButton(onClick = { creating = true; editor = AlarmRule(title = "", scheduleType = ScheduleType.DAILY) }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = PxPine)) { Icon(Icons.Rounded.AddAlarm, "新增") }
            }
        }
        if (c.alarms.isEmpty()) item { PixelPanel { PixelText("还没有闹钟。", color = PxSoft) } }
        items(c.alarms, key = { it.id }) { alarm ->
            val next = if (alarm.enabled) c.scheduler.nextTriggerMillis(alarm, now) else null
            PixelPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelBadge("⏰", PxAmber)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        PixelText(alarm.title.ifBlank { "未命名闹钟" }, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize)
                        PixelText(pixelAlarmSummary(alarm), color = PxSoft)
                    }
                    Switch(checked = alarm.enabled, onCheckedChange = { scope.launch { c.toggleAlarm(alarm) } })
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().background(PxPaper2, RoundedCornerShape(2.dp)).border(1.dp, PxLine, RoundedCornerShape(2.dp)).padding(10.dp)) {
                    PixelText(if (!alarm.enabled) "已暂停 · 不会执行" else nextExecutionPixel(alarm, next, now), color = if (alarm.enabled) PxPine else PxSoft, weight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelText("${if (alarm.sound) "铃声" else "静音"} · ${if (alarm.vibration) "震动" else "不震动"}", color = PxSoft, modifier = Modifier.weight(1f))
                    TextButton(onClick = { creating = false; editor = alarm }) { Text("编辑") }
                    IconButton(onClick = { scope.launch { c.deleteAlarm(alarm) } }) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = PxDanger) }
                }
            }
        }
    }

    editor?.let { initial ->
        PixelAlarmEditor(
            initial = initial,
            title = if (creating) "创建闹钟" else "编辑闹钟",
            onDismiss = { editor = null },
            onSave = { rule -> scope.launch { if (creating) c.addAlarm(rule.copy(id = 0)) else c.updateAlarm(rule.copy(id = initial.id, createdAt = initial.createdAt)); editor = null } }
        )
    }
}

@Composable
private fun PixelStatsScreen(c: AppController) {
    val all7Done = c.habits.sumOf { c.completionCountLastDays(it.id, 7) }
    val all7Scheduled = c.habits.sumOf { c.scheduledCountLastDays(it, 7) }.coerceAtLeast(1)
    val all30Done = c.habits.sumOf { c.completionCountLastDays(it.id, 30) }
    val all30Scheduled = c.habits.sumOf { c.scheduledCountLastDays(it, 30) }.coerceAtLeast(1)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            PixelText("统计", size = MaterialTheme.typography.headlineMedium.fontSize, weight = FontWeight.Black, color = PxPine)
            PixelText("STATS · 看见每天积累的节奏", color = PxSoft)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PixelStatCard("近7天", "${all7Done * 100 / all7Scheduled}%", Modifier.weight(1f))
                PixelStatCard("近30天", "${all30Done * 100 / all30Scheduled}%", Modifier.weight(1f))
                PixelStatCard("项目", "${c.habits.size}", Modifier.weight(1f))
            }
        }
        items(c.habits, key = { it.id }) { h ->
            val s7 = c.scheduledCountLastDays(h, 7).coerceAtLeast(1)
            val s30 = c.scheduledCountLastDays(h, 30).coerceAtLeast(1)
            val d7 = c.completionCountLastDays(h.id, 7)
            val d30 = c.completionCountLastDays(h.id, 30)
            PixelPanel {
                PixelText(h.title, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize)
                Spacer(Modifier.height(5.dp))
                PixelText("当前连续 ${c.currentStreak(h.id)} 天 · 最长 ${c.longestStreak(h.id)} 天", color = PxOrange, weight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                PixelText("近 7 天 $d7/$s7 · 近 30 天 $d30/$s30", color = PxSoft)
                Spacer(Modifier.height(8.dp))
                PixelProgress(d30.toFloat() / s30)
            }
        }
    }
}

@Composable
private fun PixelSettingsScreen(c: AppController, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var workday by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText("设置", size = MaterialTheme.typography.headlineMedium.fontSize, weight = FontWeight.Black, color = PxPine, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭") }
            }
        }
        item {
            PixelPanel {
                PixelText("后台闹钟", weight = FontWeight.Black, color = PxPine)
                PixelText("Android 系统负责保存和触发闹钟，不要求 DayPulse 一直常驻后台。", color = PxSoft)
                Spacer(Modifier.height(6.dp))
                PixelText(if (c.scheduler.canScheduleExact()) "✓ 已允许精确闹钟" else "! 需要精确闹钟权限", color = if (c.scheduler.canScheduleExact()) PxPine else PxDanger, weight = FontWeight.Bold)
                if (!c.scheduler.canScheduleExact() && Build.VERSION.SDK_INT >= 31) {
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("去授权") }
                }
            }
        }
        item {
            PixelPanel {
                PixelText("AI · SiliconFlow", weight = FontWeight.Black, color = PxPine)
                OutlinedTextField(key, { key = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation())
                Row {
                    Button(onClick = { scope.launch { runCatching { c.saveApiKey(key) }.onSuccess { key = ""; message = "Key 已保存" }.onFailure { message = it.message } } }) { Text(if (c.hasApiKey) "更新 Key" else "保存 Key") }
                    if (c.hasApiKey) TextButton(onClick = { scope.launch { c.clearApiKey(); message = "Key 已删除" } }) { Text("删除") }
                }
            }
        }
        item {
            PixelPanel {
                PixelText("工作日覆盖", weight = FontWeight.Black, color = PxPine)
                OutlinedTextField(date, { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("YYYY-MM-DD") })
                Row(verticalAlignment = Alignment.CenterVertically) { Text("设为工作日", Modifier.weight(1f)); Switch(workday, { workday = it }) }
                Button(onClick = { scope.launch { runCatching { c.setWorkdayOverride(date, workday) }.onSuccess { message = "已保存" }.onFailure { message = it.message } } }) { Text("保存") }
                c.workdayOverrides.take(8).forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelText("${o.dateKey} · ${if (o.isWorkday) "工作日" else "休息日"}", color = PxSoft, modifier = Modifier.weight(1f))
                        IconButton(onClick = { scope.launch { c.deleteWorkdayOverride(o.dateKey) } }) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = PxDanger) }
                    }
                }
            }
        }
        message?.let { item { PixelText(it, color = PxPine, weight = FontWeight.Bold) } }
    }
}

@Composable
private fun PixelHabitEditor(initial: Habit, creating: Boolean, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (Habit) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.title) }
    var target by remember(initial) { mutableStateOf(initial.targetCount.toString()) }
    var unit by remember(initial) { mutableStateOf(initial.unit) }
    var mask by remember(initial) { mutableIntStateOf(initial.weekdaysMask) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PxPaper,
        title = { PixelText(if (creating) "创建打卡项目" else "编辑打卡项目", weight = FontWeight.Black, color = PxPine) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("名称") })
                Row {
                    OutlinedTextField(target, { target = it }, modifier = Modifier.weight(1f), label = { Text("每日目标") })
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(unit, { unit = it }, modifier = Modifier.weight(1f), label = { Text("单位") })
                }
                PixelText("执行星期", color = PxSoft)
                (1..7).chunked(4).forEach { row ->
                    Row { row.forEach { day -> val bit = 1 shl (day - 1); FilterChip(selected = mask and bit != 0, onClick = { mask = mask xor bit }, label = { Text("${"一二三四五六日"[day - 1]}") }, modifier = Modifier.padding(end = 4.dp)) } }
                }
                error?.let { Text(it, color = PxDanger) }
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    require(name.isNotBlank()) { "请输入名称" }
                    require(mask != 0) { "至少选择一天" }
                    initial.copy(title = name.trim(), targetCount = target.toInt().coerceAtLeast(1), unit = unit.trim().ifBlank { "次" }, weekdaysMask = mask)
                }.onSuccess(onSave).onFailure { error = it.message }
            }) { Text("保存") }
        },
        dismissButton = { Row { onDelete?.let { TextButton(onClick = it) { Text("删除", color = PxDanger) } }; TextButton(onClick = onDismiss) { Text("取消") } } }
    )
}

@Composable
private fun PixelAlarmEditor(initial: AlarmRule, title: String, onDismiss: () -> Unit, onSave: (AlarmRule) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.title) }
    var type by remember(initial) { mutableStateOf(initial.scheduleType) }
    var time by remember(initial) { mutableStateOf("%02d:%02d".format(initial.hour, initial.minute)) }
    var date by remember(initial) { mutableStateOf(initial.onceAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() } ?: LocalDate.now().plusDays(1).toString()) }
    var interval by remember(initial) { mutableStateOf((initial.intervalMinutes ?: 120).toString()) }
    var startTime by remember(initial) { mutableStateOf(minutesToPixelTime(initial.windowStartMinutes ?: 540)) }
    var endTime by remember(initial) { mutableStateOf(minutesToPixelTime(initial.windowEndMinutes ?: 1260)) }
    var weekdaysMask by remember(initial) { mutableIntStateOf(if (initial.weekdaysMask == 0) 31 else initial.weekdaysMask) }
    var sound by remember(initial) { mutableStateOf(initial.sound) }
    var vibration by remember(initial) { mutableStateOf(initial.vibration) }
    var notification by remember(initial) { mutableStateOf(initial.notification) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PxPaper,
        title = { PixelText(title, weight = FontWeight.Black, color = PxPine) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.heightIn(max = 570.dp)) {
                item { OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("名称") }) }
                item {
                    PixelText("重复方式", color = PxSoft)
                    ScheduleType.entries.chunked(3).forEach { row -> Row { row.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(pixelScheduleLabel(t)) }, modifier = Modifier.padding(end = 4.dp)) } } }
                }
                if (type != ScheduleType.INTERVAL) item { OutlinedTextField(time, { time = it }, modifier = Modifier.fillMaxWidth(), label = { Text("时间 HH:mm") }) }
                if (type == ScheduleType.ONCE) item { OutlinedTextField(date, { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("日期 YYYY-MM-DD") }) }
                if (type == ScheduleType.WEEKLY) item {
                    PixelText("星期", color = PxSoft)
                    (1..7).chunked(4).forEach { row -> Row { row.forEach { day -> val bit = 1 shl (day - 1); FilterChip(selected = weekdaysMask and bit != 0, onClick = { weekdaysMask = weekdaysMask xor bit }, label = { Text("${"一二三四五六日"[day - 1]}") }, modifier = Modifier.padding(end = 4.dp)) } } }
                }
                if (type == ScheduleType.INTERVAL) item {
                    OutlinedTextField(interval, { interval = it }, modifier = Modifier.fillMaxWidth(), label = { Text("间隔分钟") })
                    OutlinedTextField(startTime, { startTime = it }, modifier = Modifier.fillMaxWidth(), label = { Text("开始 HH:mm") })
                    OutlinedTextField(endTime, { endTime = it }, modifier = Modifier.fillMaxWidth(), label = { Text("结束 HH:mm") })
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("铃声", Modifier.weight(1f)); Switch(sound, { sound = it }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("震动", Modifier.weight(1f)); Switch(vibration, { vibration = it }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("通知", Modifier.weight(1f)); Switch(notification, { notification = it }) }
                    PixelText("到点由系统后台唤醒，并显示全屏停止界面。", color = PxSoft)
                    error?.let { Text(it, color = PxDanger) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    require(name.isNotBlank()) { "名称不能为空" }
                    val base = LocalTime.parse(if (type == ScheduleType.INTERVAL) startTime else time, DateTimeFormatter.ofPattern("H:mm"))
                    val start = LocalTime.parse(startTime, DateTimeFormatter.ofPattern("H:mm"))
                    val end = LocalTime.parse(endTime, DateTimeFormatter.ofPattern("H:mm"))
                    require(end.hour * 60 + end.minute >= start.hour * 60 + start.minute) { "结束时间不能早于开始时间" }
                    initial.copy(
                        title = name.trim(), scheduleType = type, hour = base.hour, minute = base.minute,
                        weekdaysMask = if (type == ScheduleType.WEEKLY) weekdaysMask else 0,
                        onceAt = if (type == ScheduleType.ONCE) LocalDate.parse(date).atTime(base).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else null,
                        intervalMinutes = if (type == ScheduleType.INTERVAL) interval.toInt().coerceAtLeast(1) else null,
                        windowStartMinutes = if (type == ScheduleType.INTERVAL) start.hour * 60 + start.minute else null,
                        windowEndMinutes = if (type == ScheduleType.INTERVAL) end.hour * 60 + end.minute else null,
                        sound = sound, vibration = vibration, notification = notification
                    )
                }.onSuccess(onSave).onFailure { error = it.message ?: "配置不正确" }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PixelPanel(compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().border(2.dp, PxPine, RoundedCornerShape(4.dp)),
        color = PxPaper,
        shape = RoundedCornerShape(4.dp),
        shadowElevation = 3.dp
    ) {
        Column(Modifier.padding(if (compact) 12.dp else 15.dp), content = content)
    }
}

@Composable
private fun PixelProgress(progress: Float) {
    Row(Modifier.fillMaxWidth().height(12.dp).border(2.dp, PxPine, RoundedCornerShape(1.dp)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val blocks = 10
        repeat(blocks) { i -> Box(Modifier.weight(1f).fillMaxHeight().background(if (i < (progress.coerceIn(0f, 1f) * blocks).toInt()) PxAmber else PxPaper2)) }
    }
}

@Composable
private fun PixelBadge(text: String, color: Color) {
    Box(Modifier.size(38.dp).background(color, RoundedCornerShape(3.dp)).border(2.dp, PxPine, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
        PixelText(text, weight = FontWeight.Black, color = PxPine)
    }
}

@Composable
private fun PixelStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier.border(2.dp, PxPine, RoundedCornerShape(4.dp)), color = PxPaper, shape = RoundedCornerShape(4.dp)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PixelText(value, weight = FontWeight.Black, color = PxOrange, size = MaterialTheme.typography.titleLarge.fontSize)
            PixelText(label, color = PxSoft, size = MaterialTheme.typography.labelSmall.fontSize)
        }
    }
}

@Composable
private fun PixelText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = PxInk,
    weight: FontWeight = FontWeight.Normal,
    size: androidx.compose.ui.unit.TextUnit = MaterialTheme.typography.bodyMedium.fontSize
) {
    Text(text, modifier = modifier, color = color, fontWeight = weight, fontFamily = FontFamily.Monospace, fontSize = size)
}

private fun AiAlarmDraft.toPixelRule(): AlarmRule {
    val type = scheduleType ?: ScheduleType.DAILY
    val base = runCatching { LocalTime.parse(time ?: startTime ?: "08:00", DateTimeFormatter.ofPattern("H:mm")) }.getOrDefault(LocalTime.of(8, 0))
    val mask = weekdays.fold(0) { m, d -> if (d in 1..7) m or (1 shl (d - 1)) else m }
    val start = startTime?.let { runCatching { LocalTime.parse(it, DateTimeFormatter.ofPattern("H:mm")) }.getOrNull() }
    val end = endTime?.let { runCatching { LocalTime.parse(it, DateTimeFormatter.ofPattern("H:mm")) }.getOrNull() }
    val onceDate = runCatching { LocalDate.parse(date ?: LocalDate.now().plusDays(1).toString()) }.getOrDefault(LocalDate.now().plusDays(1))
    return AlarmRule(
        title = title.ifBlank { "AI 提醒" },
        scheduleType = type,
        hour = base.hour,
        minute = base.minute,
        weekdaysMask = if (type == ScheduleType.WEEKLY) mask else 0,
        onceAt = if (type == ScheduleType.ONCE) onceDate.atTime(base).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else null,
        intervalMinutes = if (type == ScheduleType.INTERVAL) (intervalMinutes ?: 120) else null,
        windowStartMinutes = if (type == ScheduleType.INTERVAL) (start ?: base).let { it.hour * 60 + it.minute } else null,
        windowEndMinutes = if (type == ScheduleType.INTERVAL) (end ?: LocalTime.of(21, 0)).let { it.hour * 60 + it.minute } else null,
        sound = sound,
        vibration = vibration,
        notification = notification
    )
}

private fun pixelAlarmSummary(a: AlarmRule): String = when (a.scheduleType) {
    ScheduleType.ONCE -> a.onceAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) } ?: "单次"
    ScheduleType.DAILY -> "每天 %02d:%02d".format(a.hour, a.minute)
    ScheduleType.WEEKLY -> "每周 ${pixelMaskText(a.weekdaysMask)} · %02d:%02d".format(a.hour, a.minute)
    ScheduleType.WORKDAY -> "工作日 %02d:%02d".format(a.hour, a.minute)
    ScheduleType.INTERVAL -> "${minutesToPixelTime(a.windowStartMinutes ?: 540)}–${minutesToPixelTime(a.windowEndMinutes ?: 1260)} · 每 ${a.intervalMinutes ?: 60} 分钟"
}

private fun nextExecutionPixel(alarm: AlarmRule, triggerMillis: Long?, nowMillis: Long): String {
    if (!alarm.enabled) return "已暂停"
    if (triggerMillis == null) return "无下次执行"
    val zone = ZoneId.systemDefault()
    val target = Instant.ofEpochMilli(triggerMillis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val dayText = when (target.toLocalDate()) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> "周${"一二三四五六日"[target.dayOfWeek.value - 1]} ${target.format(DateTimeFormatter.ofPattern("M月d日"))}"
    }
    val totalMinutes = ((triggerMillis - nowMillis).coerceAtLeast(0L) + 59_999L) / 60_000L
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    val remain = when {
        totalMinutes <= 0 -> "不到1分钟"
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时${minutes}分钟"
        else -> "${minutes}分钟"
    }
    return "下次 $dayText ${target.format(DateTimeFormatter.ofPattern("HH:mm"))} · 还有 $remain"
}

private fun pixelMaskText(mask: Int): String = (1..7).filter { mask and (1 shl (it - 1)) != 0 }.joinToString("/") { "周${"一二三四五六日"[it - 1]}" }
private fun minutesToPixelTime(v: Int): String = "%02d:%02d".format(v / 60, v % 60)
private fun pixelScheduleLabel(t: ScheduleType): String = when (t) {
    ScheduleType.ONCE -> "单次"
    ScheduleType.DAILY -> "每天"
    ScheduleType.WEEKLY -> "每周"
    ScheduleType.WORKDAY -> "工作日"
    ScheduleType.INTERVAL -> "循环"
}
