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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DeepPurple = Color(0xFF11062F)
private val CardPurple = Color(0xFF25104F)
private val AccentOrange = Color(0xFFFF8A4C)
private val AccentPink = Color(0xFFFF4F8B)
private val SoftText = Color(0xFFB9A9DD)

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

private enum class Tab(val text: String, val icon: ImageVector) {
    TODAY("今天", Icons.Rounded.WbSunny),
    ALARM("闹钟", Icons.Rounded.Alarm),
    AI("AI", Icons.Rounded.AutoAwesome),
    STATS("统计", Icons.Rounded.BarChart),
    SETTINGS("设置", Icons.Rounded.Settings)
}

@Composable
private fun DayPulseApp() {
    val context = LocalContext.current
    val controller = remember { AppController(context) }
    var tab by remember { mutableStateOf(Tab.TODAY) }
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        controller.load()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        containerColor = DeepPurple,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF17073C)) {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab == it,
                        onClick = { tab = it },
                        icon = { Icon(it.icon, it.text) },
                        label = { Text(it.text) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentOrange,
                            selectedTextColor = AccentOrange,
                            indicatorColor = Color(0xFF3A175F),
                            unselectedIconColor = SoftText,
                            unselectedTextColor = SoftText
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(
            Modifier.fillMaxSize().padding(pad).background(
                Brush.verticalGradient(listOf(Color(0xFF16083F), DeepPurple, Color(0xFF0C0527)))
            )
        ) {
            if (controller.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else when (tab) {
                Tab.TODAY -> TodayScreen(controller)
                Tab.ALARM -> AlarmScreen(controller)
                Tab.AI -> AiScreen(controller)
                Tab.STATS -> StatsScreen(controller)
                Tab.SETTINGS -> SettingsScreen(controller)
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, color = SoftText) }
    }
}

@Composable
private fun TodayScreen(c: AppController) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    val done = c.completedTodayCount()
    val total = c.habits.size
    val progress = if (total == 0) 0f else done.toFloat() / total

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle("早上好 · DayPulse", "${LocalDate.now()} · 把今天过得有节奏") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2B105A)), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocalFireDepartment, null, tint = AccentOrange, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("今日进度", style = MaterialTheme.typography.titleLarge)
                            Text("已完成 $done / $total", color = SoftText)
                        }
                        Text("${(progress * 100).toInt()}%", color = AccentOrange, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp), color = AccentPink, trackColor = Color(0xFF46316E))
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.weight(1f), label = { Text("新增打卡项目") }, singleLine = true)
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { scope.launch { c.addHabit(title); title = "" } }, enabled = title.isNotBlank()) { Icon(Icons.Rounded.Add, null) }
            }
        }
        if (c.habits.isEmpty()) item { Text("还没有习惯，先创建一个吧。", color = SoftText) }
        items(c.habits, key = { it.id }) { h ->
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardPurple)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (c.isDoneToday(h.id)) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (c.isDoneToday(h.id)) AccentOrange else SoftText)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("连续 ${c.currentStreak(h.id)} 天 · 近30天 ${c.completionCountLastDays(h.id, 30)} 次", color = SoftText)
                    }
                    TextButton(onClick = { scope.launch { c.toggleToday(h.id) } }) { Text(if (c.isDoneToday(h.id)) "取消" else "打卡") }
                    IconButton(onClick = { scope.launch { c.deleteHabit(h.id) } }) { Icon(Icons.Rounded.DeleteOutline, "删除") }
                }
            }
        }
    }
}

@Composable
private fun AlarmScreen(c: AppController) {
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<AlarmRule?>(null) }
    var isNew by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { PageTitle("闹钟", "单次 · 每天 · 每周 · 工作日 · 循环") }
                FilledIconButton(onClick = { isNew = true; editor = AlarmRule(title = "", scheduleType = ScheduleType.DAILY) }) { Icon(Icons.Rounded.AddAlarm, "添加") }
            }
        }
        if (c.alarms.isEmpty()) item { Text("还没有闹钟。", color = SoftText) }
        items(c.alarms, key = { it.id }) { a ->
            Card(
                Modifier.fillMaxWidth().clickable { isNew = false; editor = a },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = CardPurple)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Alarm, null, tint = AccentOrange, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(summary(a), color = SoftText)
                        }
                        Switch(checked = a.enabled, onCheckedChange = { scope.launch { c.toggleAlarm(a) } })
                    }
                    HorizontalDivider(color = Color(0xFF4B3373))
                    Row {
                        AssistChip(onClick = {}, label = { Text(if (a.sound) "铃声" else "静音") }, leadingIcon = { Icon(Icons.Rounded.MusicNote, null) })
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick = {}, label = { Text(if (a.vibration) "震动" else "不震动") }, leadingIcon = { Icon(Icons.Rounded.Vibration, null) })
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { editor = a; isNew = false }) { Icon(Icons.Rounded.Edit, null); Text("编辑") }
                        IconButton(onClick = { scope.launch { c.deleteAlarm(a) } }) { Icon(Icons.Rounded.DeleteOutline, "删除") }
                    }
                }
            }
        }
    }

    editor?.let { initial ->
        AlarmEditorDialog(
            title = if (isNew) "添加闹钟" else "编辑闹钟",
            initial = initial,
            onDismiss = { editor = null },
            onSave = { rule ->
                scope.launch {
                    if (isNew) c.addAlarm(rule.copy(id = 0, createdAt = System.currentTimeMillis())) else c.updateAlarm(rule.copy(id = initial.id, createdAt = initial.createdAt))
                    editor = null
                }
            }
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

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }
    DisposableEffect(speechRecognizer) {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; status = "正在听…" }
            override fun onBeginningOfSpeech() { listening = true }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening = false; status = "正在识别…" }
            override fun onError(error: Int) { listening = false; status = speechErrorText(error) }
            override fun onResults(results: Bundle?) {
                listening = false
                command = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: command
                status = null
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { command = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose { speechRecognizer.destroy() }
    }

    fun startSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status = "这台手机没有可用的系统语音识别服务"
            return
        }
        speechRecognizer.startListening(speechIntent)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSpeech() else status = "需要麦克风权限才能使用语音"
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle("AI 设置", "创建、删除闹钟都可以直接说") }
        item {
            Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = CardPurple)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("✨ 用自然语言告诉我", color = AccentPink, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        label = { Text("例如：每周一三五晚上8点提醒我健身") }
                    )
                    Row {
                        Button(
                            onClick = {
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
                                        status = null
                                    }.onFailure { status = it.message }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = command.isNotBlank()
                        ) { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("解析") }
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(
                            onClick = {
                                if (listening) speechRecognizer.stopListening()
                                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSpeech()
                                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Icon(if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, null); Spacer(Modifier.width(6.dp)); Text(if (listening) "停止" else "语音") }
                    }
                    status?.let { Text(it, color = SoftText) }
                }
            }
        }

        draft?.takeIf { it.action == AiActionType.DELETE }?.let { d ->
            item {
                Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF35123E))) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("AI 识别为删除操作", color = AccentOrange, fontWeight = FontWeight.Bold)
                        Text("匹配到 ${deleteMatches.size} 个闹钟，请勾选后确认。", color = SoftText)
                        if (deleteMatches.isEmpty()) Text("没有找到符合条件的闹钟。")
                        deleteMatches.forEach { alarm ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                                selectedDeleteIds = if (alarm.id in selectedDeleteIds) selectedDeleteIds - alarm.id else selectedDeleteIds + alarm.id
                            }) {
                                Checkbox(checked = alarm.id in selectedDeleteIds, onCheckedChange = null)
                                Column(Modifier.weight(1f)) { Text(alarm.title); Text(summary(alarm), color = SoftText) }
                            }
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    c.deleteAlarms(deleteMatches.filter { it.id in selectedDeleteIds })
                                    status = "已删除 ${selectedDeleteIds.size} 个闹钟"
                                    draft = null; deleteMatches = emptyList(); selectedDeleteIds = emptySet()
                                }
                            },
                            enabled = selectedDeleteIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPink),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("确认删除") }
                    }
                }
            }
        }
    }

    editorRule?.let { initial ->
        AlarmEditorDialog(
            title = "确认 AI 创建结果",
            initial = initial,
            onDismiss = { editorRule = null },
            onSave = { edited ->
                scope.launch {
                    c.addAlarm(edited.copy(id = 0, createdAt = System.currentTimeMillis()))
                    editorRule = null; draft = null; status = "闹钟已创建"
                }
            }
        )
    }
}

@Composable
private fun AlarmEditorDialog(title: String, initial: AlarmRule, onDismiss: () -> Unit, onSave: (AlarmRule) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.title) }
    var type by remember(initial) { mutableStateOf(initial.scheduleType) }
    var time by remember(initial) { mutableStateOf("%02d:%02d".format(initial.hour, initial.minute)) }
    var date by remember(initial) { mutableStateOf(initial.onceAt?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() } ?: LocalDate.now().plusDays(1).toString()) }
    var interval by remember(initial) { mutableStateOf((initial.intervalMinutes ?: 120).toString()) }
    var startTime by remember(initial) { mutableStateOf(minutesToTime(initial.windowStartMinutes ?: 9 * 60)) }
    var endTime by remember(initial) { mutableStateOf(minutesToTime(initial.windowEndMinutes ?: 21 * 60)) }
    var weekdaysMask by remember(initial) { mutableIntStateOf(if (initial.weekdaysMask == 0) 31 else initial.weekdaysMask) }
    var sound by remember(initial) { mutableStateOf(initial.sound) }
    var vibration by remember(initial) { mutableStateOf(initial.vibration) }
    var notification by remember(initial) { mutableStateOf(initial.notification) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF211046),
        title = { Text(title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 560.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item {
                    Column {
                        Text("重复方式", color = SoftText)
                        ScheduleType.entries.chunked(3).forEach { row ->
                            Row { row.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(scheduleLabel(t)) }, modifier = Modifier.padding(end = 6.dp)) } }
                        }
                    }
                }
                if (type != ScheduleType.INTERVAL) item { OutlinedTextField(time, { time = it }, label = { Text("时间 HH:mm") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                if (type == ScheduleType.ONCE) item { OutlinedTextField(date, { date = it }, label = { Text("日期 YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                if (type == ScheduleType.WEEKLY) item {
                    Column {
                        Text("星期", color = SoftText)
                        Row {
                            (1..7).forEach { day ->
                                val bit = 1 shl (day - 1)
                                FilterChip(selected = weekdaysMask and bit != 0, onClick = { weekdaysMask = weekdaysMask xor bit }, label = { Text("${"一二三四五六日"[day - 1]}") }, modifier = Modifier.padding(end = 4.dp))
                            }
                        }
                    }
                }
                if (type == ScheduleType.INTERVAL) item {
                    OutlinedTextField(interval, { interval = it }, label = { Text("间隔分钟") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(startTime, { startTime = it }, label = { Text("开始 HH:mm") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(endTime, { endTime = it }, label = { Text("结束 HH:mm") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("铃声", Modifier.weight(1f)); Switch(sound, { sound = it }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("震动", Modifier.weight(1f)); Switch(vibration, { vibration = it }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("通知", Modifier.weight(1f)); Switch(notification, { notification = it }) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    require(name.isNotBlank()) { "名称不能为空" }
                    val baseTime = LocalTime.parse(if (type == ScheduleType.INTERVAL) startTime else time, DateTimeFormatter.ofPattern("H:mm"))
                    val start = LocalTime.parse(startTime, DateTimeFormatter.ofPattern("H:mm"))
                    val end = LocalTime.parse(endTime, DateTimeFormatter.ofPattern("H:mm"))
                    initial.copy(
                        title = name.trim(), scheduleType = type, hour = baseTime.hour, minute = baseTime.minute,
                        weekdaysMask = if (type == ScheduleType.WEEKLY) weekdaysMask else 0,
                        onceAt = if (type == ScheduleType.ONCE) LocalDate.parse(date).atTime(baseTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else null,
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
        item { PageTitle("统计", "把坚持变成看得见的进步") }
        items(c.habits) { h ->
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CardPurple)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(h.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("当前连续 ${c.currentStreak(h.id)} 天", color = AccentOrange)
                    Text("近7天 ${c.completionCountLastDays(h.id, 7)}/7 · 近30天 ${c.completionCountLastDays(h.id, 30)}/30", color = SoftText)
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
        item { PageTitle("设置", "本地优先 · AI 按需联网") }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardPurple)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("SiliconFlow / DeepSeek", fontWeight = FontWeight.Bold)
                    OutlinedTextField(key, { key = it }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Row {
                        Button(onClick = { scope.launch { runCatching { c.saveApiKey(key) }.onSuccess { key = ""; message = "Key 已保存" }.onFailure { message = it.message } } }) { Text(if (c.hasApiKey) "更新 Key" else "保存 Key") }
                        if (c.hasApiKey) TextButton(onClick = { scope.launch { c.clearApiKey(); message = "Key 已清除" } }) { Text("清除") }
                    }
                    Text("模型：deepseek-ai/DeepSeek-V3.2", color = SoftText)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardPurple)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("精确闹钟", fontWeight = FontWeight.Bold)
                    Text(if (c.scheduler.canScheduleExact()) "已允许精确闹钟" else "未允许，提醒可能延迟", color = if (c.scheduler.canScheduleExact()) AccentOrange else SoftText)
                    if (!c.scheduler.canScheduleExact() && Build.VERSION.SDK_INT >= 31) Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }) { Text("去授权") }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardPurple)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("工作日覆盖", fontWeight = FontWeight.Bold)
                    OutlinedTextField(date, { date = it }, label = { Text("YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("设为工作日", Modifier.weight(1f)); Switch(workday, { workday = it }) }
                    Button(onClick = { scope.launch { runCatching { c.setWorkdayOverride(date, workday) }.onSuccess { message = "已保存" }.onFailure { message = it.message } } }) { Text("保存") }
                    c.workdayOverrides.take(12).forEach { o ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${o.dateKey} · ${if (o.isWorkday) "工作日" else "休息日"}", Modifier.weight(1f))
                            IconButton(onClick = { scope.launch { c.deleteWorkdayOverride(o.dateKey) } }) { Icon(Icons.Rounded.DeleteOutline, "删除") }
                        }
                    }
                }
            }
        }
        message?.let { item { Text(it, color = SoftText) } }
    }
}

private fun AiAlarmDraft.toRule(): AlarmRule {
    val type = requireNotNull(scheduleType) { "AI 没有识别出重复方式，请手动补充" }
    val base = LocalTime.parse(time ?: startTime ?: "08:00", DateTimeFormatter.ofPattern("H:mm"))
    val mask = weekdays.fold(0) { m, d -> if (d in 1..7) m or (1 shl (d - 1)) else m }
    val start = startTime?.let { LocalTime.parse(it, DateTimeFormatter.ofPattern("H:mm")) }
    val end = endTime?.let { LocalTime.parse(it, DateTimeFormatter.ofPattern("H:mm")) }
    return AlarmRule(
        title = title.ifBlank { "AI 提醒" }, scheduleType = type, hour = base.hour, minute = base.minute,
        weekdaysMask = mask,
        onceAt = if (type == ScheduleType.ONCE) LocalDate.parse(requireNotNull(date)).atTime(base).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else null,
        intervalMinutes = intervalMinutes,
        windowStartMinutes = start?.let { it.hour * 60 + it.minute }, windowEndMinutes = end?.let { it.hour * 60 + it.minute },
        sound = sound, vibration = vibration, notification = notification
    )
}

private fun summary(a: AlarmRule): String = when (a.scheduleType) {
    ScheduleType.ONCE -> a.onceAt?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) } ?: "单次"
    ScheduleType.DAILY -> "每天 %02d:%02d".format(a.hour, a.minute)
    ScheduleType.WEEKLY -> "每周 ${maskText(a.weekdaysMask)} · %02d:%02d".format(a.hour, a.minute)
    ScheduleType.WORKDAY -> "工作日 %02d:%02d".format(a.hour, a.minute)
    ScheduleType.INTERVAL -> "${minutesToTime(a.windowStartMinutes ?: 540)}–${minutesToTime(a.windowEndMinutes ?: 1260)} · 每隔 ${a.intervalMinutes ?: 60} 分钟"
}

private fun maskText(mask: Int): String = (1..7).filter { mask and (1 shl (it - 1)) != 0 }.joinToString("/") { "周${"一二三四五六日"[it - 1]}" }
private fun minutesToTime(v: Int): String = "%02d:%02d".format(v / 60, v % 60)
private fun scheduleLabel(t: ScheduleType) = when (t) { ScheduleType.ONCE -> "单次"; ScheduleType.DAILY -> "每天"; ScheduleType.WEEKLY -> "每周"; ScheduleType.WORKDAY -> "工作日"; ScheduleType.INTERVAL -> "循环" }
private fun speechErrorText(code: Int) = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> "录音失败，请检查麦克风"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音服务网络不可用"
    SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再说一次"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别正忙，请稍后重试"
    SpeechRecognizer.ERROR_CLIENT -> "语音识别已停止"
    else -> "语音识别失败（$code）"
}
