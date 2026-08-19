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
import androidx.compose.ui.unit.TextUnit
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

private val PBg = Color(0xFFFFF8E7)
private val PPaper = Color(0xFFFFFDF6)
private val PPaper2 = Color(0xFFF1E5C2)
private val PPine = Color(0xFF204A36)
private val PPine2 = Color(0xFF3D684D)
private val PMoss = Color(0xFF768965)
private val PAmber = Color(0xFFF2AA1F)
private val POrange = Color(0xFFE7772D)
private val PInk = Color(0xFF183528)
private val PSoft = Color(0xFF6B746B)
private val PLine = Color(0xFFC9B77D)
private val PDanger = Color(0xFFB64A3D)
private val PSky = Color(0xFFE8E9C8)

class DayPulseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.ensureChannel(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = PPine,
                    secondary = PAmber,
                    tertiary = POrange,
                    background = PBg,
                    surface = PPaper,
                    surfaceVariant = PPaper2,
                    onPrimary = Color.White,
                    onBackground = PInk,
                    onSurface = PInk,
                    outline = PLine,
                    error = PDanger
                )
            ) { DayPulseRoot() }
        }
    }
}

private enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Rounded.Home),
    CHECKIN("打卡", Icons.Rounded.CheckCircle),
    ALARM("闹钟", Icons.Rounded.Alarm),
    STATS("统计", Icons.Rounded.BarChart)
}

@Composable
private fun DayPulseRoot() {
    val context = LocalContext.current
    val c = remember { AppController(context) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(MainTab.HOME) }
    var settingsOpen by remember { mutableStateOf(false) }

    var listening by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var aiStatus by remember { mutableStateOf<String?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiDraft by remember { mutableStateOf<AiAlarmDraft?>(null) }
    var aiCreateDraft by remember { mutableStateOf<AlarmRule?>(null) }
    var deleteCandidates by remember { mutableStateOf<List<AlarmRule>>(emptyList()) }
    var selectedDeleteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    fun submitVoiceToAi(text: String) {
        if (text.isBlank() || aiBusy) return
        transcript = text.trim()
        aiBusy = true
        aiStatus = "AI 正在理解：${text.trim()}"
        scope.launch {
            c.parseAi(text.trim()).onSuccess { parsed ->
                aiDraft = parsed
                if (parsed.action == AiActionType.CREATE) {
                    aiCreateDraft = parsed.toDayPulseRule()
                    aiStatus = null
                } else {
                    deleteCandidates = c.findAlarmMatches(parsed)
                    selectedDeleteIds = when {
                        parsed.deleteAllMatches -> deleteCandidates.map { it.id }.toSet()
                        deleteCandidates.size == 1 -> setOf(deleteCandidates.first().id)
                        else -> emptySet()
                    }
                    aiStatus = if (deleteCandidates.isEmpty()) "没有找到匹配的闹钟" else null
                }
            }.onFailure { aiStatus = it.message ?: "AI 请求失败" }
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
        aiStatus = if (granted) "麦克风已授权，再按住中间 AI 说话" else "需要麦克风权限才能使用 AI 语音"
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        c.load()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun startVoice() {
        transcript = ""
        if (aiBusy) {
            aiStatus = "AI 正在处理上一条需求"
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            aiStatus = "请先允许麦克风权限"
        } else {
            speech.start()
        }
    }

    Scaffold(
        containerColor = PBg,
        topBar = { AppTopBar(onSettings = { settingsOpen = true }) },
        bottomBar = {
            AppBottomBar(
                current = tab,
                listening = listening,
                onSelect = { tab = it },
                onVoiceStart = { startVoice() },
                onVoiceEnd = { speech.stopAndFinalize() }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(PBg)) {
            if (c.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = PPine)
            } else {
                when (tab) {
                    MainTab.HOME -> HomeScreen(c, onCheckin = { tab = MainTab.CHECKIN }, onAlarm = { tab = MainTab.ALARM }, onStats = { tab = MainTab.STATS })
                    MainTab.CHECKIN -> CheckinScreen(c)
                    MainTab.ALARM -> AlarmScreenPixel(c)
                    MainTab.STATS -> StatsScreenPixel(c)
                }
            }

            if (listening || aiBusy || !aiStatus.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(0.9f).border(2.dp, PPine, RoundedCornerShape(3.dp)),
                    color = if (listening) PPine else PPaper,
                    shape = RoundedCornerShape(3.dp),
                    shadowElevation = 5.dp
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        PixelLabel(
                            when {
                                listening -> "● 正在听… 松开 AI 发送"
                                aiBusy -> "AI 正在处理…"
                                else -> aiStatus.orEmpty()
                            },
                            color = if (listening) Color.White else if (aiStatus?.contains("失败") == true) PDanger else PPine,
                            weight = FontWeight.Bold
                        )
                        if (listening && transcript.isNotBlank()) PixelLabel("“$transcript”", color = Color.White)
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        Dialog(onDismissRequest = { settingsOpen = false }) {
            Surface(
                Modifier.fillMaxWidth().fillMaxHeight(0.92f).border(2.dp, PPine, RoundedCornerShape(4.dp)),
                color = PBg,
                shape = RoundedCornerShape(4.dp)
            ) { SettingsScreenPixel(c, onClose = { settingsOpen = false }) }
        }
    }

    aiCreateDraft?.let { initial ->
        AlarmEditorPixel(
            initial = initial,
            title = "AI 已整理好 · 确认前可修改",
            onDismiss = { aiCreateDraft = null; aiDraft = null },
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

    if (aiDraft?.action == AiActionType.DELETE && deleteCandidates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { aiDraft = null; deleteCandidates = emptyList(); selectedDeleteIds = emptySet() },
            containerColor = PPaper,
            title = { PixelLabel("AI 删除确认", weight = FontWeight.Black, color = PDanger, size = MaterialTheme.typography.titleLarge.fontSize) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PixelLabel("AI 找到以下闹钟，请勾选确认：", color = PSoft)
                    deleteCandidates.forEach { alarm ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selectedDeleteIds = if (alarm.id in selectedDeleteIds) selectedDeleteIds - alarm.id else selectedDeleteIds + alarm.id
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = alarm.id in selectedDeleteIds, onCheckedChange = null)
                            Column(Modifier.weight(1f)) {
                                PixelLabel(alarm.title.ifBlank { "未命名闹钟" }, weight = FontWeight.Bold)
                                PixelLabel(alarmSummary(alarm), color = PSoft)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedDeleteIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = PDanger),
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
}

@Composable
private fun AppTopBar(onSettings: () -> Unit) {
    Surface(color = PPaper, shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(PPine, RoundedCornerShape(2.dp)).border(2.dp, PAmber, RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
                PixelLabel("DP", color = Color.White, weight = FontWeight.Black)
            }
            Spacer(Modifier.width(10.dp))
            PixelLabel("DayPulse", modifier = Modifier.weight(1f), color = PPine, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize)
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "设置", tint = PPine) }
        }
    }
}

@Composable
private fun AppBottomBar(current: MainTab, listening: Boolean, onSelect: (MainTab) -> Unit, onVoiceStart: () -> Unit, onVoiceEnd: () -> Unit) {
    Surface(color = PPaper, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically) {
            NavSlot(MainTab.HOME, current, onSelect, Modifier.weight(1f))
            NavSlot(MainTab.CHECKIN, current, onSelect, Modifier.weight(1f))
            Box(Modifier.weight(1.14f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(64.dp)
                        .background(if (listening) POrange else PPine, RoundedCornerShape(5.dp))
                        .border(3.dp, PAmber, RoundedCornerShape(5.dp))
                        .pointerInput(listening) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val quickRelease = withTimeoutOrNull(300L) { waitForUpOrCancellation() }
                                if (quickRelease == null) {
                                    onVoiceStart()
                                    waitForUpOrCancellation()
                                    onVoiceEnd()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (listening) Icons.Rounded.GraphicEq else Icons.Rounded.Mic, "长按 AI", tint = Color.White, modifier = Modifier.size(25.dp))
                        PixelLabel(if (listening) "松开发送" else "长按 AI", color = Color.White, weight = FontWeight.Black, size = MaterialTheme.typography.labelSmall.fontSize)
                    }
                }
            }
            NavSlot(MainTab.ALARM, current, onSelect, Modifier.weight(1f))
            NavSlot(MainTab.STATS, current, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavSlot(tab: MainTab, current: MainTab, onSelect: (MainTab) -> Unit, modifier: Modifier) {
    val selected = tab == current
    Column(
        modifier.fillMaxHeight().clickable { onSelect(tab) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(tab.icon, tab.label, tint = if (selected) POrange else PMoss, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(3.dp))
        PixelLabel(tab.label, color = if (selected) PPine else PSoft, weight = if (selected) FontWeight.Black else FontWeight.Normal, size = MaterialTheme.typography.labelSmall.fontSize)
    }
}

@Composable
private fun HomeScreen(c: AppController, onCheckin: () -> Unit, onAlarm: () -> Unit, onStats: () -> Unit) {
    val today = c.todayHabits()
    val done = c.completedTodayCount()
    val nextAlarm = c.alarms.filter { it.enabled }.mapNotNull { a -> c.scheduler.nextTriggerMillis(a)?.let { a to it } }.minByOrNull { it.second }
    val weekDone = c.habits.sumOf { c.completionCountLastDays(it.id, 7) }
    val weekTarget = c.habits.sumOf { c.scheduledCountLastDays(it, 7) }.coerceAtLeast(1)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item { ForestHero() }
        item {
            PixelPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        PixelLabel("今日打卡", color = PPine, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize)
                        PixelLabel("TODAY CHECK-IN", color = PSoft, size = MaterialTheme.typography.labelMedium.fontSize)
                    }
                    PixelLabel("$done/${today.size}", color = POrange, weight = FontWeight.Black, size = MaterialTheme.typography.headlineMedium.fontSize)
                }
                Spacer(Modifier.height(9.dp))
                val p = if (today.isEmpty()) 0f else done.toFloat() / today.size
                BlockProgress(p)
                Spacer(Modifier.height(6.dp))
                PixelLabel("${(p * 100).toInt()}% 完成", color = PSoft)
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelLabel("TODAY TO-DO", color = PPine, weight = FontWeight.Black, modifier = Modifier.weight(1f))
                TextButton(onClick = onCheckin) { Text("全部") }
            }
        }
        if (today.isEmpty()) item { PixelPanel { PixelLabel("今天没有安排打卡项目", color = PSoft) } }
        items(today.take(6), key = { it.id }) { habit -> TodoRow(c, habit) }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelLabel("NEXT ALARM", color = PPine, weight = FontWeight.Black, modifier = Modifier.weight(1f))
                TextButton(onClick = onAlarm) { Text("全部") }
            }
        }
        item {
            PixelPanel {
                if (nextAlarm == null) PixelLabel("还没有开启的闹钟", color = PSoft)
                else {
                    val (alarm, next) = nextAlarm
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelBadge("⏰", PAmber)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            PixelLabel(alarm.title.ifBlank { "未命名闹钟" }, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize)
                            PixelLabel(alarmSummary(alarm), color = PSoft)
                            PixelLabel(nextExecution(alarm, next, System.currentTimeMillis()), color = POrange, weight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelLabel("THIS WEEK", color = PPine, weight = FontWeight.Black, modifier = Modifier.weight(1f))
                TextButton(onClick = onStats) { Text("统计") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox("7日完成", "${weekDone * 100 / weekTarget}%", Modifier.weight(1f))
                StatBox("连续项目", "${c.habits.count { c.currentStreak(it.id) > 0 }}", Modifier.weight(1f))
                StatBox("开启闹钟", "${c.alarms.count { it.enabled }}", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ForestHero() {
    Box(Modifier.fillMaxWidth().height(152.dp).clip(RoundedCornerShape(4.dp)).background(PSky).border(2.dp, PPine, RoundedCornerShape(4.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 30f
            drawRect(PSky)
            for (x in 21..24) for (y in 2..5) drawRect(PAmber, topLeft = androidx.compose.ui.geometry.Offset(x * u, y * u), size = androidx.compose.ui.geometry.Size(u, u))
            for (i in 0..7) drawRect(Color(0xFF98AA75), topLeft = androidx.compose.ui.geometry.Offset((i * 4 - 2) * u, size.height - (5 + i % 3) * u), size = androidx.compose.ui.geometry.Size(7 * u, 8 * u))
            for (i in 0..7) drawRect(PPine2, topLeft = androidx.compose.ui.geometry.Offset(i * 4.7f * u, size.height - (4 + i % 2) * u), size = androidx.compose.ui.geometry.Size(4 * u, 6 * u))
            for (x in listOf(2, 8, 14, 27)) {
                drawRect(PPine, topLeft = androidx.compose.ui.geometry.Offset(x * u, size.height - 8 * u), size = androidx.compose.ui.geometry.Size(2 * u, 7 * u))
                drawRect(PPine, topLeft = androidx.compose.ui.geometry.Offset((x - 1) * u, size.height - 6 * u), size = androidx.compose.ui.geometry.Size(4 * u, 2 * u))
            }
            drawRect(Color(0xFFD5A24F), topLeft = androidx.compose.ui.geometry.Offset(14 * u, size.height - 5 * u), size = androidx.compose.ui.geometry.Size(3 * u, 5 * u))
        }
        Column(Modifier.padding(15.dp)) {
            PixelLabel("早上好，DayPulse", color = PPine, weight = FontWeight.Black, size = MaterialTheme.typography.headlineSmall.fontSize)
            Spacer(Modifier.height(3.dp))
            PixelLabel("踏上今天的节奏。", weight = FontWeight.Bold)
            PixelLabel(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd")), color = PSoft)
        }
    }
}

@Composable
private fun TodoRow(c: AppController, habit: Habit) {
    val scope = rememberCoroutineScope()
    val count = c.todayCount(habit.id)
    val complete = count >= habit.targetCount
    PixelPanel(compact = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).background(if (complete) PAmber else PPaper2, RoundedCornerShape(2.dp)).border(2.dp, PPine, RoundedCornerShape(2.dp)).clickable { scope.launch { c.setHabitCompleted(habit, !complete) } },
                contentAlignment = Alignment.Center
            ) { if (complete) PixelLabel("✓", color = PPine, weight = FontWeight.Black) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                PixelLabel(habit.title, weight = FontWeight.Bold)
                PixelLabel("$count/${habit.targetCount} ${habit.unit} · 连续 ${c.currentStreak(habit.id)} 天", color = PSoft, size = MaterialTheme.typography.bodySmall.fontSize)
            }
            if (habit.targetCount > 1 && !complete) {
                FilledIconButton(onClick = { scope.launch { c.changeHabitCount(habit, 1) } }, modifier = Modifier.size(34.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = PPine)) {
                    Icon(Icons.Rounded.Add, "加一", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun CheckinScreen(c: AppController) {
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<Habit?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PixelLabel("今日打卡", color = PPine, weight = FontWeight.Black, size = MaterialTheme.typography.headlineMedium.fontSize)
                    PixelLabel("DAILY CHECK-IN · ${LocalDate.now()}", color = PSoft)
                }
                FilledIconButton(onClick = { creating = true; editor = Habit(0, "", System.currentTimeMillis()) }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = PPine)) { Icon(Icons.Rounded.Add, "新增") }
            }
        }
        if (c.habits.isEmpty()) item { PixelPanel { PixelLabel("还没有打卡项目", color = PSoft) } }
        items(c.habits, key = { it.id }) { habit ->
            val scheduled = c.isHabitScheduled(habit)
            val count = c.todayCount(habit.id)
            val complete = count >= habit.targetCount
            PixelPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelBadge(if (complete) "✓" else if (scheduled) "□" else "–", if (complete) PAmber else PPaper2)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        PixelLabel(habit.title, weight = FontWeight.Black, size = MaterialTheme.typography.titleMedium.fontSize)
                        PixelLabel(if (scheduled) "今日 $count/${habit.targetCount} ${habit.unit}" else "今天不在计划内", color = PSoft)
                        PixelLabel("连续 ${c.currentStreak(habit.id)} 天 · 最长 ${c.longestStreak(habit.id)} 天", color = POrange, size = MaterialTheme.typography.bodySmall.fontSize)
                    }
                    IconButton(onClick = { creating = false; editor = habit }) { Icon(Icons.Rounded.Edit, "编辑", tint = PPine) }
                }
                Spacer(Modifier.height(8.dp))
                BlockProgress((count.toFloat() / habit.targetCount).coerceIn(0f, 1f))
                if (scheduled) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedIconButton(onClick = { scope.launch { c.changeHabitCount(habit, -1) } }, enabled = count > 0) { Icon(Icons.Rounded.Remove, "减一") }
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = { scope.launch { c.changeHabitCount(habit, 1) } }, enabled = count < habit.targetCount, colors = IconButtonDefaults.filledIconButtonColors(containerColor = PPine)) { Icon(Icons.Rounded.Add, "加一") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { c.setHabitCompleted(habit, !complete) } }) { Text(if (complete) "取消完成" else "直接完成") }
                    }
                }
            }
        }
    }

    editor?.let { initial ->
        HabitEditorPixel(
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
private fun AlarmScreenPixel(c: AppController) {
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<AlarmRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PixelLabel("闹钟", color = PPine, weight = FontWeight.Black, size = MaterialTheme.typography.headlineMedium.fontSize)
                    PixelLabel("ALARM · 系统后台调度", color = PSoft)
                }
                FilledIconButton(onClick = { creating = true; editor = AlarmRule(title = "", scheduleType = ScheduleType.DAILY) }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = PPine)) { Icon(Icons.Rounded.AddAlarm, "新增") }
            }
        }
        if (c.alarms.isEmpty()) item { PixelPanel { PixelLabel("还没有闹钟", color = PSoft) } }
        items(c.alarms, key = { it.id }) { alarm ->
            val next = if (alarm.enabled) c.scheduler.nextTriggerMillis(alarm, now) else null
            PixelPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelBadge("⏰", PAmber)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        PixelLabel(alarm.title.ifBlank { "未命名闹钟" }, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize)
                        PixelLabel(alarmSummary(alarm), color = PSoft)
                    }
                    Switch(checked = alarm.enabled, onCheckedChange = { scope.launch { c.toggleAlarm(alarm) } })
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().background(PPaper2, RoundedCornerShape(2.dp)).border(1.dp, PLine, RoundedCornerShape(2.dp)).padding(10.dp)) {
                    PixelLabel(if (!alarm.enabled) "已暂停 · 不会执行" else nextExecution(alarm, next, now), color = if (alarm.enabled) PPine else PSoft, weight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelLabel("${if (alarm.sound) "铃声" else "静音"} · ${if (alarm.vibration) "震动" else "不震动"}", color = PSoft, modifier = Modifier.weight(1f))
                    TextButton(onClick = { creating = false; editor = alarm }) { Text("编辑") }
                    IconButton(onClick = { scope.launch { c.deleteAlarm(alarm) } }) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = PDanger) }
                }
            }
        }
    }

    editor?.let { initial ->
        AlarmEditorPixel(
            initial = initial,
            title = if (creating) "创建闹钟" else "编辑闹钟",
            onDismiss = { editor = null },
            onSave = { rule -> scope.launch { if (creating) c.addAlarm(rule.copy(id = 0)) else c.updateAlarm(rule.copy(id = initial.id, createdAt = initial.createdAt)); editor = null } }
        )
    }
}

@Composable
private fun StatsScreenPixel(c: AppController) {
    val all7Done = c.habits.sumOf { c.completionCountLastDays(it.id, 7) }
    val all7Scheduled = c.habits.sumOf { c.scheduledCountLastDays(it, 7) }.coerceAtLeast(1)
    val all30Done = c.habits.sumOf { c.completionCountLastDays(it.id, 30) }
    val all30Scheduled = c.habits.sumOf { c.scheduledCountLastDays(it, 30) }.coerceAtLeast(1)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            PixelLabel("统计", color = PPine, weight = FontWeight.Black, size = MaterialTheme.typography.headlineMedium.fontSize)
            PixelLabel("STATS · 看见每天积累的节奏", color = PSoft)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox("近7天", "${all7Done * 100 / all7Scheduled}%", Modifier.weight(1f))
                StatBox("近30天", "${all30Done * 100 / all30Scheduled}%", Modifier.weight(1f))
                StatBox("项目", "${c.habits.size}", Modifier.weight(1f))
            }
        }
        items(c.habits, key = { it.id }) { h ->
            val s7 = c.scheduledCountLastDays(h, 7).coerceAtLeast(1)
            val s30 = c.scheduledCountLastDays(h, 30).coerceAtLeast(1)
            val d7 = c.completionCountLastDays(h.id, 7)
            val d30 = c.completionCountLastDays(h.id, 30)
            PixelPanel {
                PixelLabel(h.title, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize)
                PixelLabel("当前连续 ${c.currentStreak(h.id)} 天 · 最长 ${c.longestStreak(h.id)} 天", color = POrange, weight = FontWeight.Bold)
                PixelLabel("近 7 天 $d7/$s7 · 近 30 天 $d30/$s30", color = PSoft)
                Spacer(Modifier.height(8.dp))
                BlockProgress(d30.toFloat() / s30)
            }
        }
    }
}

@Composable
private fun SettingsScreenPixel(c: AppController, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var workday by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelLabel("设置", color = PPine, weight = FontWeight.Black, size = MaterialTheme.typography.headlineMedium.fontSize, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭") }
            }
        }
        item {
            PixelPanel {
                PixelLabel("后台闹钟", color = PPine, weight = FontWeight.Black)
                PixelLabel("由 Android 系统保存和触发，不要求 DayPulse 常驻后台。", color = PSoft)
                PixelLabel(if (c.scheduler.canScheduleExact()) "✓ 已允许精确闹钟" else "! 需要精确闹钟权限", color = if (c.scheduler.canScheduleExact()) PPine else PDanger, weight = FontWeight.Bold)
                if (!c.scheduler.canScheduleExact() && Build.VERSION.SDK_INT >= 31) {
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("去授权") }
                }
            }
        }
        item {
            PixelPanel {
                PixelLabel("AI · SiliconFlow", color = PPine, weight = FontWeight.Black)
                OutlinedTextField(key, { key = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation())
                Row {
                    Button(onClick = { scope.launch { runCatching { c.saveApiKey(key) }.onSuccess { key = ""; message = "Key 已保存" }.onFailure { message = it.message } } }) { Text(if (c.hasApiKey) "更新 Key" else "保存 Key") }
                    if (c.hasApiKey) TextButton(onClick = { scope.launch { c.clearApiKey(); message = "Key 已删除" } }) { Text("删除") }
                }
            }
        }
        item {
            PixelPanel {
                PixelLabel("工作日覆盖", color = PPine, weight = FontWeight.Black)
                OutlinedTextField(date, { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("YYYY-MM-DD") })
                Row(verticalAlignment = Alignment.CenterVertically) { Text("设为工作日", Modifier.weight(1f)); Switch(workday, { workday = it }) }
                Button(onClick = { scope.launch { runCatching { c.setWorkdayOverride(date, workday) }.onSuccess { message = "已保存" }.onFailure { message = it.message } } }) { Text("保存") }
            }
        }
        message?.let { item { PixelLabel(it, color = PPine, weight = FontWeight.Bold) } }
    }
}

@Composable
private fun HabitEditorPixel(initial: Habit, creating: Boolean, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (Habit) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.title) }
    var target by remember(initial) { mutableStateOf(initial.targetCount.toString()) }
    var unit by remember(initial) { mutableStateOf(initial.unit) }
    var mask by remember(initial) { mutableIntStateOf(initial.weekdaysMask) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PPaper,
        title = { PixelLabel(if (creating) "创建打卡项目" else "编辑打卡项目", color = PPine, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("名称") })
                Row {
                    OutlinedTextField(target, { target = it }, modifier = Modifier.weight(1f), label = { Text("每日目标") })
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(unit, { unit = it }, modifier = Modifier.weight(1f), label = { Text("单位") })
                }
                PixelLabel("执行星期", color = PSoft)
                (1..7).chunked(4).forEach { row ->
                    Row { row.forEach { day -> val bit = 1 shl (day - 1); FilterChip(selected = mask and bit != 0, onClick = { mask = mask xor bit }, label = { Text("${"一二三四五六日"[day - 1]}") }, modifier = Modifier.padding(end = 4.dp)) } }
                }
                error?.let { Text(it, color = PDanger) }
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
        dismissButton = { Row { onDelete?.let { TextButton(onClick = it) { Text("删除", color = PDanger) } }; TextButton(onClick = onDismiss) { Text("取消") } } }
    )
}

@Composable
private fun AlarmEditorPixel(initial: AlarmRule, title: String, onDismiss: () -> Unit, onSave: (AlarmRule) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.title) }
    var type by remember(initial) { mutableStateOf(initial.scheduleType) }
    var time by remember(initial) { mutableStateOf("%02d:%02d".format(initial.hour, initial.minute)) }
    var date by remember(initial) { mutableStateOf(initial.onceAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() } ?: LocalDate.now().plusDays(1).toString()) }
    var interval by remember(initial) { mutableStateOf((initial.intervalMinutes ?: 120).toString()) }
    var startTime by remember(initial) { mutableStateOf(minutesToTime(initial.windowStartMinutes ?: 540)) }
    var endTime by remember(initial) { mutableStateOf(minutesToTime(initial.windowEndMinutes ?: 1260)) }
    var weekdaysMask by remember(initial) { mutableIntStateOf(if (initial.weekdaysMask == 0) 31 else initial.weekdaysMask) }
    var sound by remember(initial) { mutableStateOf(initial.sound) }
    var vibration by remember(initial) { mutableStateOf(initial.vibration) }
    var notification by remember(initial) { mutableStateOf(initial.notification) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PPaper,
        title = { PixelLabel(title, color = PPine, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 570.dp)) {
                item { OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("名称") }) }
                item {
                    PixelLabel("重复方式", color = PSoft)
                    ScheduleType.entries.chunked(3).forEach { row -> Row { row.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(scheduleLabel(t)) }, modifier = Modifier.padding(end = 4.dp)) } } }
                }
                if (type != ScheduleType.INTERVAL) item { OutlinedTextField(time, { time = it }, modifier = Modifier.fillMaxWidth(), label = { Text("时间 HH:mm") }) }
                if (type == ScheduleType.ONCE) item { OutlinedTextField(date, { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("日期 YYYY-MM-DD") }) }
                if (type == ScheduleType.WEEKLY) item {
                    PixelLabel("星期", color = PSoft)
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
                    PixelLabel("到点由系统后台唤醒并显示全屏停止界面。", color = PSoft)
                    error?.let { Text(it, color = PDanger) }
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
        Modifier.fillMaxWidth().border(2.dp, PPine, RoundedCornerShape(4.dp)),
        color = PPaper,
        shape = RoundedCornerShape(4.dp),
        shadowElevation = 3.dp
    ) { Column(Modifier.padding(if (compact) 11.dp else 14.dp), content = content) }
}

@Composable
private fun BlockProgress(progress: Float) {
    Row(Modifier.fillMaxWidth().height(13.dp).border(2.dp, PPine, RoundedCornerShape(1.dp)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(10) { i -> Box(Modifier.weight(1f).fillMaxHeight().background(if (i < (progress.coerceIn(0f, 1f) * 10).toInt()) PAmber else PPaper2)) }
    }
}

@Composable
private fun PixelBadge(text: String, color: Color) {
    Box(Modifier.size(38.dp).background(color, RoundedCornerShape(2.dp)).border(2.dp, PPine, RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
        PixelLabel(text, color = PPine, weight = FontWeight.Black)
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier) {
    Surface(modifier.border(2.dp, PPine, RoundedCornerShape(4.dp)), color = PPaper, shape = RoundedCornerShape(4.dp)) {
        Column(Modifier.padding(vertical = 11.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PixelLabel(value, color = POrange, weight = FontWeight.Black, size = MaterialTheme.typography.titleLarge.fontSize)
            PixelLabel(label, color = PSoft, size = MaterialTheme.typography.labelSmall.fontSize)
        }
    }
}

@Composable
private fun PixelLabel(text: String, modifier: Modifier = Modifier, color: Color = PInk, weight: FontWeight = FontWeight.Normal, size: TextUnit = MaterialTheme.typography.bodyMedium.fontSize) {
    Text(text, modifier = modifier, color = color, fontFamily = FontFamily.Monospace, fontWeight = weight, fontSize = size)
}

private fun AiAlarmDraft.toDayPulseRule(): AlarmRule {
    val type = scheduleType ?: ScheduleType.DAILY
    val base = runCatching { LocalTime.parse(time ?: startTime ?: "08:00", DateTimeFormatter.ofPattern("H:mm")) }.getOrDefault(LocalTime.of(8, 0))
    val mask = weekdays.fold(0) { m, d -> if (d in 1..7) m or (1 shl (d - 1)) else m }
    val start = startTime?.let { runCatching { LocalTime.parse(it, DateTimeFormatter.ofPattern("H:mm")) }.getOrNull() }
    val end = endTime?.let { runCatching { LocalTime.parse(it, DateTimeFormatter.ofPattern("H:mm")) }.getOrNull() }
    val onceDate = runCatching { LocalDate.parse(date ?: LocalDate.now().plusDays(1).toString()) }.getOrDefault(LocalDate.now().plusDays(1))
    return AlarmRule(
        title = title.ifBlank { "AI 提醒" }, scheduleType = type, hour = base.hour, minute = base.minute,
        weekdaysMask = if (type == ScheduleType.WEEKLY) mask else 0,
        onceAt = if (type == ScheduleType.ONCE) onceDate.atTime(base).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else null,
        intervalMinutes = if (type == ScheduleType.INTERVAL) (intervalMinutes ?: 120) else null,
        windowStartMinutes = if (type == ScheduleType.INTERVAL) (start ?: base).let { it.hour * 60 + it.minute } else null,
        windowEndMinutes = if (type == ScheduleType.INTERVAL) (end ?: LocalTime.of(21, 0)).let { it.hour * 60 + it.minute } else null,
        sound = sound, vibration = vibration, notification = notification
    )
}

private fun alarmSummary(a: AlarmRule): String = when (a.scheduleType) {
    ScheduleType.ONCE -> a.onceAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) } ?: "单次"
    ScheduleType.DAILY -> "每天 %02d:%02d".format(a.hour, a.minute)
    ScheduleType.WEEKLY -> "每周 ${maskText(a.weekdaysMask)} · %02d:%02d".format(a.hour, a.minute)
    ScheduleType.WORKDAY -> "工作日 %02d:%02d".format(a.hour, a.minute)
    ScheduleType.INTERVAL -> "${minutesToTime(a.windowStartMinutes ?: 540)}–${minutesToTime(a.windowEndMinutes ?: 1260)} · 每 ${a.intervalMinutes ?: 60} 分钟"
}

private fun nextExecution(alarm: AlarmRule, triggerMillis: Long?, nowMillis: Long): String {
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
    val totalMinutes = ((triggerMillis - nowMillis).coerceAtLeast(0L) + 59_999) / 60_000
    val days = totalMinutes / 1440
    val hours = totalMinutes % 1440 / 60
    val minutes = totalMinutes % 60
    val remain = when {
        totalMinutes <= 0 -> "不到1分钟"
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时${minutes}分钟"
        else -> "${minutes}分钟"
    }
    return "下次 $dayText ${target.format(DateTimeFormatter.ofPattern("HH:mm"))} · 还有 $remain"
}

private fun maskText(mask: Int): String = (1..7).filter { mask and (1 shl (it - 1)) != 0 }.joinToString("/") { "周${"一二三四五六日"[it - 1]}" }
private fun minutesToTime(v: Int): String = "%02d:%02d".format(v / 60, v % 60)
private fun scheduleLabel(t: ScheduleType): String = when (t) {
    ScheduleType.ONCE -> "单次"
    ScheduleType.DAILY -> "每天"
    ScheduleType.WEEKLY -> "每周"
    ScheduleType.WORKDAY -> "工作日"
    ScheduleType.INTERVAL -> "循环"
}
