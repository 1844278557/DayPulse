package com.example.daypulse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.daypulse.alarm.NotificationHelper
import com.example.daypulse.model.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DeepPurple = Color(0xFF10052F)
private val CardPurple = Color(0xFF24104D)
private val CardPurple2 = Color(0xFF30155D)
private val AccentOrange = Color(0xFFFF914D)
private val AccentPink = Color(0xFFFF4F8D)
private val SoftText = Color(0xFFBDAFE0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.ensureChannel(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AccentOrange,
                    secondary = AccentPink,
                    background = DeepPurple,
                    surface = CardPurple,
                    onSurface = Color.White,
                    onBackground = Color.White
                )
            ) { DayPulseApp() }
        }
    }
}

private enum class Tab(val title: String, val icon: ImageVector) {
    TODAY("今天", Icons.Rounded.Today),
    ALARM("闹钟", Icons.Rounded.Alarm),
    AI("AI", Icons.Rounded.AutoAwesome),
    STATS("统计", Icons.Rounded.BarChart),
    SETTINGS("设置", Icons.Rounded.Settings)
}

@Composable
private fun DayPulseApp() {
    val context = LocalContext.current
    val c = remember { AppController(context) }
    var tab by remember { mutableStateOf(Tab.TODAY) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        c.load()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF17073A)) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, item.title) },
                        label = { Text(item.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentOrange,
                            selectedTextColor = AccentOrange,
                            indicatorColor = Color(0xFF3D1A63),
                            unselectedIconColor = SoftText,
                            unselectedTextColor = SoftText
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).background(
                Brush.verticalGradient(listOf(Color(0xFF1A0842), DeepPurple, Color(0xFF0A0322)))
            )
        ) {
            if (c.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else when (tab) {
                Tab.TODAY -> TodayScreen(c)
                Tab.ALARM -> AlarmScreen(c)
                Tab.AI -> AiScreen(c)
                Tab.STATS -> StatsScreen(c)
                Tab.SETTINGS -> SettingsScreen(c)
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = SoftText)
    }
}

@Composable
private fun TodayScreen(c: AppController) {
    val scope = rememberCoroutineScope()
    val todayHabits = c.todayHabits()
    val done = c.completedTodayCount()
    val progress = if (todayHabits.isEmpty()) 0f else done.toFloat() / todayHabits.size
    var habitEditor by remember { mutableStateOf<Habit?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { PageTitle("今天", "${LocalDate.now()} · 专注完成今天该做的事") }
                FilledIconButton(onClick = { creating = true; habitEditor = Habit(0, "", System.currentTimeMillis()) }) {
                    Icon(Icons.Rounded.Add, "新增习惯")
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = CardPurple2)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocalFireDepartment, null, tint = AccentOrange, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("今日完成度", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("$done / ${todayHabits.size} 个习惯达标", color = SoftText)
                        }
                        Text("${(progress * 100).toInt()}%", color = AccentOrange, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(9.dp), color = AccentPink, trackColor = Color(0xFF4A3470))
                }
            }
        }

        if (todayHabits.isEmpty()) item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = CardPurple)) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.CheckCircleOutline, null, tint = AccentOrange, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("今天没有安排习惯", fontWeight = FontWeight.Bold)
                    Text("创建一个每日项目，例如喝水 8 杯、阅读 20 分钟。", color = SoftText)
                }
            }
        }

        items(todayHabits, key = { it.id }) { habit ->
            val count = c.todayCount(habit.id)
            val complete = count >= habit.targetCount
            val itemProgress = (count.toFloat() / habit.targetCount).coerceIn(0f, 1f)
            Card(
                modifier = Modifier.fillMaxWidth().clickable { creating = false; habitEditor = habit },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = if (complete) Color(0xFF2D1A50) else CardPurple)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (complete) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (complete) AccentOrange else SoftText, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(habit.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("连续 ${c.currentStreak(habit.id)} 天 · 最长 ${c.longestStreak(habit.id)} 天", color = SoftText)
                        }
                        IconButton(onClick = { creating = false; habitEditor = habit }) { Icon(Icons.Rounded.Edit, "编辑") }
                    }
                    LinearProgressIndicator(progress = { itemProgress }, modifier = Modifier.fillMaxWidth().height(7.dp), color = if (complete) AccentOrange else AccentPink, trackColor = Color(0xFF4A3470))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$count / ${habit.targetCount} ${habit.unit}", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        OutlinedIconButton(onClick = { scope.launch { c.changeHabitCount(habit, -1) } }, enabled = count > 0) { Icon(Icons.Rounded.Remove, "减一") }
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = { scope.launch { c.changeHabitCount(habit, 1) } }, enabled = count < habit.targetCount) { Icon(Icons.Rounded.Add, "加一") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { scope.launch { c.setHabitCompleted(habit, !complete) } }) { Text(if (complete) "取消完成" else "直接完成") }
                    }
                }
            }
        }

        val other = c.habits.filterNot { it.id in todayHabits.map(Habit::id).toSet() }
        if (other.isNotEmpty()) {
            item { Text("其他习惯", color = SoftText, fontWeight = FontWeight.Bold) }
            items(other, key = { "other-${it.id}" }) { h ->
                ListItem(
                    headlineContent = { Text(h.title) },
                    supportingContent = { Text("今天不在计划内 · ${weekdayText(h.weekdaysMask)}") },
                    trailingContent = { IconButton(onClick = { creating = false; habitEditor = h }) { Icon(Icons.Rounded.Edit, "编辑") } },
                    colors = ListItemDefaults.colors(containerColor = CardPurple)
                )
            }
        }
    }

    habitEditor?.let { initial ->
        HabitEditorDialog(
            title = if (creating) "创建每日习惯" else "编辑习惯",
            initial = initial,
            onDismiss = { habitEditor = null },
            onDelete = if (creating) null else {{ scope.launch { c.deleteHabit(initial.id); habitEditor = null } }},
            onSave = { edited ->
                scope.launch {
                    if (creating) c.addHabit(edited.title, edited.targetCount, edited.unit, edited.weekdaysMask)
                    else c.updateHabit(edited.copy(id = initial.id, createdAt = initial.createdAt))
                    habitEditor = null
                }
            }
        )
    }
}

@Composable
private fun HabitEditorDialog(title: String, initial: Habit, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (Habit) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.title) }
    var target by remember(initial) { mutableStateOf(initial.targetCount.toString()) }
    var unit by remember(initial) { mutableStateOf(initial.unit) }
    var mask by remember(initial) { mutableIntStateOf(initial.weekdaysMask) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF211045),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("习惯名称") }, singleLine = true)
                Row {
                    OutlinedTextField(value = target, onValueChange = { target = it }, modifier = Modifier.weight(1f), label = { Text("每日目标") }, singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(value = unit, onValueChange = { unit = it }, modifier = Modifier.weight(1f), label = { Text("单位") }, singleLine = true)
                }
                Text("执行星期", color = SoftText)
                Row {
                    (1..7).forEach { day ->
                        val bit = 1 shl (day - 1)
                        FilterChip(
                            selected = mask and bit != 0,
                            onClick = { mask = mask xor bit },
                            label = { Text("${"一二三四五六日"[day - 1]}") },
                            modifier = Modifier.padding(end = 3.dp)
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    val n = target.toInt().coerceAtLeast(1)
                    require(name.isNotBlank()) { "请输入习惯名称" }
                    require(mask != 0) { "至少选择一天" }
                    initial.copy(title = name.trim(), targetCount = n, unit = unit.trim().ifBlank { "次" }, weekdaysMask = mask)
                }.onSuccess(onSave).onFailure { error = it.message }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("删除", color = AccentPink) } }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun AlarmScreen(c: AppController) {
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<AlarmRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { PageTitle("闹钟", "每个提醒都可随时编辑") }
                FilledIconButton(onClick = { creating = true; editor = AlarmRule(title = "", scheduleType = ScheduleType.DAILY) }) { Icon(Icons.Rounded.AddAlarm, "添加") }
            }
        }
        if (c.alarms.isEmpty()) item { Text("还没有闹钟。", color = SoftText) }
        items(c.alarms, key = { it.id }) { alarm ->
            Card(Modifier.fillMaxWidth().clickable { creating = false; editor = alarm }, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = CardPurple)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Alarm, null, tint = AccentOrange, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(alarm.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(summary(alarm), color = SoftText) }
                        Switch(checked = alarm.enabled, onCheckedChange = { scope.launch { c.toggleAlarm(alarm) } })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (alarm.sound) "铃声" else "静音", color = SoftText)
                        Text(" · ${if (alarm.vibration) "震动" else "不震动"}", color = SoftText)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { creating = false; editor = alarm }) { Icon(Icons.Rounded.Edit, null); Text("编辑") }
                        IconButton(onClick = { scope.launch { c.deleteAlarm(alarm) } }) { Icon(Icons.Rounded.DeleteOutline, "删除") }
                    }
                }
            }
        }
    }
    editor?.let { initial ->
        AlarmEditorDialog(
            title = if (creating) "创建闹钟" else "编辑闹钟",
            initial = initial,
            onDismiss = { editor = null },
            onSave = { rule -> scope.launch { if (creating) c.addAlarm(rule.copy(id = 0)) else c.updateAlarm(rule.copy(id = initial.id, createdAt = initial.createdAt)); editor = null } }
        )
    }
}

@Composable
private fun AiScreen(c: AppController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<AiAlarmDraft?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var listening by remember { mutableStateOf(false) }
    var editorRule by remember { mutableStateOf<AlarmRule?>(null) }
    var deleteMatches by remember { mutableStateOf<List<AlarmRule>>(emptyList()) }
    var selectedDeleteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    val speech = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }
    DisposableEffect(speech) {
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; status = "正在听…" }
            override fun onBeginningOfSpeech() { listening = true }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening = false; status = "识别中…" }
            override fun onError(error: Int) { listening = false; status = speechErrorText(error) }
            override fun onResults(results: Bundle?) { listening = false; command = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: command; status = null }
            override fun onPartialResults(partialResults: Bundle?) { partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { command = it } }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose { speech.destroy() }
    }
    fun startSpeech() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) speech.startListening(speechIntent)
        else status = "这台手机没有可用的系统语音识别服务"
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startSpeech() else status = "需要麦克风权限" }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle("AI 助手", "创建、删除闹钟；创建前可以自己修改") }
        item {
            Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = CardPurple)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = command, onValueChange = { command = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("例如：工作日 7:30 叫我起床 / 删除健身闹钟") })
                    Row {
                        Button(onClick = {
                            scope.launch {
                                status = "AI 正在解析…"
                                c.parseAi(command).onSuccess { parsed ->
                                    draft = parsed
                                    if (parsed.action == AiActionType.CREATE) {
                                        runCatching { parsed.toRule() }.onSuccess { editorRule = it }.onFailure { status = it.message }
                                    } else {
                                        deleteMatches = c.findAlarmMatches(parsed)
                                        selectedDeleteIds = if (parsed.deleteAllMatches) deleteMatches.map { it.id }.toSet() else if (deleteMatches.size == 1) setOf(deleteMatches.first().id) else emptySet()
                                    }
                                    if (status == "AI 正在解析…") status = null
                                }.onFailure { status = it.message }
                            }
                        }, modifier = Modifier.weight(1f), enabled = command.isNotBlank()) { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("解析") }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = {
                            if (listening) speech.stopListening()
                            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSpeech()
                            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }, modifier = Modifier.weight(1f)) { Icon(if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, null); Spacer(Modifier.width(6.dp)); Text(if (listening) "停止" else "语音") }
                    }
                    status?.let { Text(it, color = SoftText) }
                }
            }
        }
        draft?.takeIf { it.action == AiActionType.DELETE }?.let {
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF35123D))) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("确认 AI 删除结果", color = AccentOrange, fontWeight = FontWeight.Bold)
                        Text("找到 ${deleteMatches.size} 个匹配闹钟。请自己勾选。", color = SoftText)
                        deleteMatches.forEach { alarm ->
                            Row(Modifier.fillMaxWidth().clickable { selectedDeleteIds = if (alarm.id in selectedDeleteIds) selectedDeleteIds - alarm.id else selectedDeleteIds + alarm.id }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = alarm.id in selectedDeleteIds, onCheckedChange = null)
                                Column(Modifier.weight(1f)) { Text(alarm.title); Text(summary(alarm), color = SoftText) }
                            }
                        }
                        Button(onClick = { scope.launch { val chosen = deleteMatches.filter { it.id in selectedDeleteIds }; c.deleteAlarms(chosen); status = "已删除 ${chosen.size} 个闹钟"; draft = null; deleteMatches = emptyList(); selectedDeleteIds = emptySet() } }, enabled = selectedDeleteIds.isNotEmpty(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AccentPink)) { Text("确认删除") }
                    }
                }
            }
        }
    }

    editorRule?.let { initial ->
        AlarmEditorDialog(
            title = "确认 AI 解析结果（可修改）",
            initial = initial,
            onDismiss = { editorRule = null },
            onSave = { edited -> scope.launch { c.addAlarm(edited.copy(id = 0)); editorRule = null; draft = null; status = "闹钟已创建" } }
        )
    }
}

@Composable
private fun AlarmEditorDialog(title: String, initial: AlarmRule, onDismiss: () -> Unit, onSave: (AlarmRule) -> Unit) {
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
        containerColor = Color(0xFF211045),
        title = { Text(title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 570.dp)) {
                item { OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("名称") }) }
                item {
                    Text("重复方式", color = SoftText)
                    ScheduleType.entries.chunked(3).forEach { row -> Row { row.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(scheduleLabel(t)) }, modifier = Modifier.padding(end = 4.dp)) } } }
                }
                if (type != ScheduleType.INTERVAL) item { OutlinedTextField(value = time, onValueChange = { time = it }, modifier = Modifier.fillMaxWidth(), label = { Text("时间 HH:mm") }) }
                if (type == ScheduleType.ONCE) item { OutlinedTextField(value = date, onValueChange = { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("日期 YYYY-MM-DD") }) }
                if (type == ScheduleType.WEEKLY) item {
                    Text("星期", color = SoftText)
                    Row { (1..7).forEach { day -> val bit = 1 shl (day - 1); FilterChip(selected = weekdaysMask and bit != 0, onClick = { weekdaysMask = weekdaysMask xor bit }, label = { Text("${"一二三四五六日"[day - 1]}") }, modifier = Modifier.padding(end = 3.dp)) } }
                }
                if (type == ScheduleType.INTERVAL) item {
                    OutlinedTextField(value = interval, onValueChange = { interval = it }, modifier = Modifier.fillMaxWidth(), label = { Text("间隔分钟") })
                    OutlinedTextField(value = startTime, onValueChange = { startTime = it }, modifier = Modifier.fillMaxWidth(), label = { Text("开始 HH:mm") })
                    OutlinedTextField(value = endTime, onValueChange = { endTime = it }, modifier = Modifier.fillMaxWidth(), label = { Text("结束 HH:mm") })
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("铃声", Modifier.weight(1f)); Switch(checked = sound, onCheckedChange = { sound = it }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("震动", Modifier.weight(1f)); Switch(checked = vibration, onCheckedChange = { vibration = it }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("通知", Modifier.weight(1f)); Switch(checked = notification, onCheckedChange = { notification = it }) }
                    Text("响铃时会弹出全屏停止界面。", color = SoftText)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
private fun StatsScreen(c: AppController) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageTitle("统计", "连续性比一次性冲刺更重要") }
        items(c.habits, key = { it.id }) { h ->
            val scheduled7 = c.scheduledCountLastDays(h, 7).coerceAtLeast(1)
            val scheduled30 = c.scheduledCountLastDays(h, 30).coerceAtLeast(1)
            val done7 = c.completionCountLastDays(h.id, 7)
            val done30 = c.completionCountLastDays(h.id, 30)
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardPurple)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(h.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("当前连续 ${c.currentStreak(h.id)} 天 · 最长 ${c.longestStreak(h.id)} 天", color = AccentOrange)
                    Text("近 7 天 $done7 / $scheduled7 · 近 30 天 $done30 / $scheduled30", color = SoftText)
                    Text("目标：每天 ${h.targetCount} ${h.unit} · ${weekdayText(h.weekdaysMask)}", color = SoftText)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(c: AppController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var workday by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle("设置", "权限、AI 与法定工作日覆盖") }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardPurple)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SiliconFlow / DeepSeek", fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation())
                    Row { Button(onClick = { scope.launch { runCatching { c.saveApiKey(key) }.onSuccess { key = ""; message = "Key 已保存" }.onFailure { message = it.message } } }) { Text(if (c.hasApiKey) "更新 Key" else "保存 Key") }; if (c.hasApiKey) TextButton(onClick = { scope.launch { c.clearApiKey(); message = "Key 已删除" } }) { Text("删除") } }
                    Text("模型：deepseek-ai/DeepSeek-V3.2", color = SoftText)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardPurple)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("闹钟权限", fontWeight = FontWeight.Bold)
                    Text(if (c.scheduler.canScheduleExact()) "已允许精确闹钟" else "未允许，提醒可能延迟", color = if (c.scheduler.canScheduleExact()) AccentOrange else SoftText)
                    if (!c.scheduler.canScheduleExact() && Build.VERSION.SDK_INT >= 31) Button(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("去授权") }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardPurple)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("法定工作日覆盖", fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = date, onValueChange = { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("YYYY-MM-DD") })
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("设为工作日", Modifier.weight(1f)); Switch(checked = workday, onCheckedChange = { workday = it }) }
                    Button(onClick = { scope.launch { runCatching { c.setWorkdayOverride(date, workday) }.onSuccess { message = "已保存" }.onFailure { message = it.message } } }) { Text("保存") }
                    c.workdayOverrides.take(12).forEach { o -> Row(verticalAlignment = Alignment.CenterVertically) { Text("${o.dateKey} · ${if (o.isWorkday) "工作日" else "休息日"}", Modifier.weight(1f)); IconButton(onClick = { scope.launch { c.deleteWorkdayOverride(o.dateKey) } }) { Icon(Icons.Rounded.DeleteOutline, "删除") } } }
                }
            }
        }
        message?.let { item { Text(it, color = SoftText) } }
    }
}

private fun AiAlarmDraft.toRule(): AlarmRule {
    val type = requireNotNull(scheduleType) { "AI 没识别出重复方式，请手动补充" }
    val base = LocalTime.parse(time ?: startTime ?: "08:00", DateTimeFormatter.ofPattern("H:mm"))
    val mask = weekdays.fold(0) { m, d -> if (d in 1..7) m or (1 shl (d - 1)) else m }
    val start = startTime?.let { LocalTime.parse(it, DateTimeFormatter.ofPattern("H:mm")) }
    val end = endTime?.let { LocalTime.parse(it, DateTimeFormatter.ofPattern("H:mm")) }
    return AlarmRule(
        title = title.ifBlank { "AI 提醒" }, scheduleType = type, hour = base.hour, minute = base.minute, weekdaysMask = mask,
        onceAt = if (type == ScheduleType.ONCE) LocalDate.parse(requireNotNull(date)).atTime(base).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else null,
        intervalMinutes = intervalMinutes, windowStartMinutes = start?.let { it.hour * 60 + it.minute }, windowEndMinutes = end?.let { it.hour * 60 + it.minute },
        sound = sound, vibration = vibration, notification = notification
    )
}

private fun summary(a: AlarmRule): String = when (a.scheduleType) {
    ScheduleType.ONCE -> a.onceAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) } ?: "单次"
    ScheduleType.DAILY -> "每天 %02d:%02d".format(a.hour, a.minute)
    ScheduleType.WEEKLY -> "每周 ${maskText(a.weekdaysMask)} · %02d:%02d".format(a.hour, a.minute)
    ScheduleType.WORKDAY -> "工作日 %02d:%02d".format(a.hour, a.minute)
    ScheduleType.INTERVAL -> "${minutesToTime(a.windowStartMinutes ?: 540)}–${minutesToTime(a.windowEndMinutes ?: 1260)} · 每隔 ${a.intervalMinutes ?: 60} 分钟"
}
private fun maskText(mask: Int): String = (1..7).filter { mask and (1 shl (it - 1)) != 0 }.joinToString("/") { "周${"一二三四五六日"[it - 1]}" }
private fun weekdayText(mask: Int): String = if (mask == 127) "每天" else maskText(mask)
private fun minutesToTime(value: Int): String = "%02d:%02d".format(value / 60, value % 60)
private fun scheduleLabel(type: ScheduleType): String = when (type) { ScheduleType.ONCE -> "单次"; ScheduleType.DAILY -> "每天"; ScheduleType.WEEKLY -> "每周"; ScheduleType.WORKDAY -> "工作日"; ScheduleType.INTERVAL -> "循环" }
private fun speechErrorText(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> "录音失败，请检查麦克风"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音服务网络不可用"
    SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再说一次"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别正忙，请稍后重试"
    SpeechRecognizer.ERROR_CLIENT -> "语音识别已停止"
    else -> "语音识别失败（$code）"
}
