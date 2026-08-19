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
import com.example.daypulse.voice.SpeechInputController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ForestBg = Color(0xFFFFFAED)
private val ForestSurface = Color(0xFFFFFDF6)
private val ForestSurface2 = Color(0xFFF5EACB)
private val ForestPine = Color(0xFF24523A)
private val ForestMoss = Color(0xFF64805D)
private val ForestAmber = Color(0xFFF2A51A)
private val ForestOrange = Color(0xFFE77829)
private val ForestText = Color(0xFF18372A)
private val ForestSoft = Color(0xFF6A7468)
private val ForestOutline = Color(0xFFD8C89F)
private val ForestDanger = Color(0xFFB84C3D)

class ForestMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.ensureChannel(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = ForestPine,
                    secondary = ForestAmber,
                    tertiary = ForestOrange,
                    background = ForestBg,
                    surface = ForestSurface,
                    surfaceVariant = ForestSurface2,
                    onPrimary = Color.White,
                    onBackground = ForestText,
                    onSurface = ForestText,
                    outline = ForestOutline,
                    error = ForestDanger
                )
            ) {
                ForestDayPulseApp()
            }
        }
    }
}

private enum class ForestTab(val label: String, val icon: ImageVector) {
    TODAY("首页", Icons.Rounded.Home),
    HABITS("习惯", Icons.Rounded.FavoriteBorder),
    ALARMS("闹钟", Icons.Rounded.Alarm),
    AI("AI", Icons.Rounded.AutoAwesome),
    SETTINGS("设置", Icons.Rounded.Settings)
}

@Composable
private fun ForestDayPulseApp() {
    val context = LocalContext.current
    val controller = remember { AppController(context) }
    var tab by remember { mutableStateOf(ForestTab.TODAY) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        controller.load()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        containerColor = ForestBg,
        bottomBar = {
            NavigationBar(containerColor = ForestSurface, tonalElevation = 3.dp) {
                ForestTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, item.label) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ForestAmber,
                            selectedTextColor = ForestPine,
                            indicatorColor = ForestPine,
                            unselectedIconColor = ForestMoss,
                            unselectedTextColor = ForestSoft
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).background(
                Brush.verticalGradient(listOf(Color(0xFFFFFCF4), ForestBg, Color(0xFFF7F0D8)))
            )
        ) {
            if (controller.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = ForestPine)
            } else {
                when (tab) {
                    ForestTab.TODAY -> ForestTodayScreen(controller)
                    ForestTab.HABITS -> ForestHabitsScreen(controller)
                    ForestTab.ALARMS -> ForestAlarmScreen(controller)
                    ForestTab.AI -> ForestAiScreen(controller)
                    ForestTab.SETTINGS -> ForestSettingsScreen(controller)
                }
            }
        }
    }
}

@Composable
private fun ForestHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = ForestPine)
        Text(subtitle, color = ForestSoft)
    }
}

@Composable
private fun ForestTodayScreen(c: AppController) {
    val todayHabits = c.todayHabits()
    val done = c.completedTodayCount()
    val nextAlarm = c.alarms.filter { it.enabled }
        .mapNotNull { alarm -> c.scheduler.nextTriggerMillis(alarm)?.let { alarm to it } }
        .minByOrNull { it.second }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ForestHeader("早上好，DayPulse", "踏上今天的节奏 · ${LocalDate.now()}")
        }
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = ForestSurface2),
                border = androidx.compose.foundation.BorderStroke(1.dp, ForestOutline)
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WbSunny, null, tint = ForestAmber, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("今日习惯", fontWeight = FontWeight.Bold, color = ForestPine)
                            Text("$done / ${todayHabits.size} 已完成", color = ForestSoft)
                        }
                        Text("${if (todayHabits.isEmpty()) 0 else done * 100 / todayHabits.size}%", color = ForestOrange, fontWeight = FontWeight.Black)
                    }
                    val p = if (todayHabits.isEmpty()) 0f else done.toFloat() / todayHabits.size
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(8.dp), color = ForestAmber, trackColor = Color(0xFFE6DAB8))
                }
            }
        }
        nextAlarm?.let { (alarm, trigger) ->
            item {
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = ForestSurface), border = androidx.compose.foundation.BorderStroke(1.dp, ForestOutline)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Alarm, null, tint = ForestPine, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("下一个闹钟", color = ForestSoft)
                            Text(alarm.title, fontWeight = FontWeight.Bold, color = ForestText)
                            Text(nextExecutionText(alarm, trigger, System.currentTimeMillis()), color = ForestOrange, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        if (todayHabits.isEmpty()) {
            item { ForestEmptyCard("今天还没有习惯", "去“习惯”页添加每日项目，例如喝水 8 杯。") }
        } else {
            items(todayHabits.take(5), key = { it.id }) { habit ->
                val count = c.todayCount(habit.id)
                ForestHabitCompact(c, habit, count)
            }
        }
    }
}

@Composable
private fun ForestHabitCompact(c: AppController, habit: Habit, count: Int) {
    val scope = rememberCoroutineScope()
    val complete = count >= habit.targetCount
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = ForestSurface), border = androidx.compose.foundation.BorderStroke(1.dp, ForestOutline)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (complete) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null, tint = if (complete) ForestAmber else ForestMoss)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(habit.title, fontWeight = FontWeight.SemiBold)
                Text("$count / ${habit.targetCount} ${habit.unit} · 连续 ${c.currentStreak(habit.id)} 天", color = ForestSoft)
            }
            IconButton(onClick = { scope.launch { c.changeHabitCount(habit, if (complete) -habit.targetCount else 1) } }) {
                Icon(if (complete) Icons.Rounded.Remove else Icons.Rounded.Add, if (complete) "减少" else "增加", tint = ForestPine)
            }
        }
    }
}

@Composable
private fun ForestEmptyCard(title: String, body: String) {
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = ForestSurface), border = androidx.compose.foundation.BorderStroke(1.dp, ForestOutline)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = ForestPine)
            Text(body, color = ForestSoft)
        }
    }
}

@Composable
private fun ForestHabitsScreen(c: AppController) {
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<Habit?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { ForestHeader("每日习惯", "目标、执行星期和进度都可以编辑") }
                FilledIconButton(onClick = { creating = true; editor = Habit(0, "", System.currentTimeMillis()) }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = ForestPine)) {
                    Icon(Icons.Rounded.Add, "新增")
                }
            }
        }
        if (c.habits.isEmpty()) item { ForestEmptyCard("还没有习惯", "创建第一个每日目标。") }
        items(c.habits, key = { it.id }) { habit ->
            val count = c.todayCount(habit.id)
            Card(
                Modifier.fillMaxWidth().clickable { creating = false; editor = habit },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = ForestSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, ForestOutline)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(habit.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.Edit, "编辑", tint = ForestMoss)
                    }
                    Text("今日 $count / ${habit.targetCount} ${habit.unit} · ${weekdayTextForest(habit.weekdaysMask)}", color = ForestSoft)
                    Text("当前连续 ${c.currentStreak(habit.id)} 天 · 最长 ${c.longestStreak(habit.id)} 天", color = ForestOrange)
                    LinearProgressIndicator(
                        progress = { (count.toFloat() / habit.targetCount).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(7.dp),
                        color = ForestAmber,
                        trackColor = Color(0xFFE6DAB8)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedIconButton(onClick = { scope.launch { c.changeHabitCount(habit, -1) } }, enabled = count > 0) { Icon(Icons.Rounded.Remove, "减一") }
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = { scope.launch { c.changeHabitCount(habit, 1) } }, enabled = count < habit.targetCount, colors = IconButtonDefaults.filledIconButtonColors(containerColor = ForestPine)) { Icon(Icons.Rounded.Add, "加一") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { c.setHabitCompleted(habit, count < habit.targetCount) } }) { Text(if (count >= habit.targetCount) "取消完成" else "直接完成") }
                    }
                }
            }
        }
    }

    editor?.let { initial ->
        ForestHabitEditor(
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
private fun ForestHabitEditor(initial: Habit, creating: Boolean, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (Habit) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.title) }
    var target by remember(initial) { mutableStateOf(initial.targetCount.toString()) }
    var unit by remember(initial) { mutableStateOf(initial.unit) }
    var mask by remember(initial) { mutableIntStateOf(initial.weekdaysMask) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForestSurface,
        title = { Text(if (creating) "创建每日习惯" else "编辑习惯", color = ForestPine) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                Row {
                    OutlinedTextField(target, { target = it }, label = { Text("每日目标") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(unit, { unit = it }, label = { Text("单位") }, modifier = Modifier.weight(1f))
                }
                Text("执行星期", color = ForestSoft)
                (1..7).chunked(4).forEach { row ->
                    Row { row.forEach { day -> val bit = 1 shl (day - 1); FilterChip(selected = mask and bit != 0, onClick = { mask = mask xor bit }, label = { Text("${"一二三四五六日"[day - 1]}") }, modifier = Modifier.padding(end = 5.dp)) } }
                }
                error?.let { Text(it, color = ForestDanger) }
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
        dismissButton = { Row { onDelete?.let { TextButton(onClick = it) { Text("删除", color = ForestDanger) } }; TextButton(onClick = onDismiss) { Text("取消") } } }
    )
}

@Composable
private fun ForestAlarmScreen(c: AppController) {
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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { ForestHeader("闹钟", "系统后台调度 · 显示每次下次执行") }
                FilledIconButton(onClick = { creating = true; editor = AlarmRule(title = "", scheduleType = ScheduleType.DAILY) }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = ForestPine)) {
                    Icon(Icons.Rounded.AddAlarm, "新增")
                }
            }
        }
        if (c.alarms.isEmpty()) item { ForestEmptyCard("还没有闹钟", "添加一个提醒，系统会在后台替 DayPulse 保管。") }
        items(c.alarms, key = { it.id }) { alarm ->
            val next = if (alarm.enabled) c.scheduler.nextTriggerMillis(alarm, now) else null
            Card(
                Modifier.fillMaxWidth().clickable { creating = false; editor = alarm },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = ForestSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, ForestOutline)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Alarm, null, tint = ForestAmber, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(alarm.title.ifBlank { "未命名闹钟" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text(alarmSummaryForest(alarm), color = ForestSoft)
                        }
                        Switch(checked = alarm.enabled, onCheckedChange = { scope.launch { c.toggleAlarm(alarm) } })
                    }
                    Surface(color = ForestSurface2, shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Schedule, null, tint = ForestPine, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (!alarm.enabled) "已暂停 · 不会执行" else nextExecutionText(alarm, next, now),
                                color = if (alarm.enabled) ForestPine else ForestSoft,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (alarm.sound) "铃声" else "静音", color = ForestSoft)
                        Text(" · ${if (alarm.vibration) "震动" else "不震动"}", color = ForestSoft)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { creating = false; editor = alarm }) { Icon(Icons.Rounded.Edit, null); Text("编辑") }
                        IconButton(onClick = { scope.launch { c.deleteAlarm(alarm) } }) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = ForestDanger) }
                    }
                }
            }
        }
    }

    editor?.let { initial ->
        ForestAlarmEditor(
            initial = initial,
            title = if (creating) "创建闹钟" else "编辑闹钟",
            onDismiss = { editor = null },
            onSave = { rule -> scope.launch { if (creating) c.addAlarm(rule.copy(id = 0)) else c.updateAlarm(rule.copy(id = initial.id, createdAt = initial.createdAt)); editor = null } }
        )
    }
}

@Composable
private fun ForestAiScreen(c: AppController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var listening by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<AiAlarmDraft?>(null) }
    var editorRule by remember { mutableStateOf<AlarmRule?>(null) }
    var deleteMatches by remember { mutableStateOf<List<AlarmRule>>(emptyList()) }
    var selectedDeleteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val speech = remember {
        SpeechInputController(
            context = context,
            onListeningChange = { listening = it },
            onStatus = { status = it },
            onText = { message = it }
        )
    }
    DisposableEffect(speech) { onDispose { speech.destroy() } }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) speech.start() else status = "需要麦克风权限"
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ForestHeader("AI 助手", "像聊天一样说需求，最后仍由你确认") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Surface(color = ForestSurface2, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(0.88f)) {
                    Text("告诉我你想创建、修改思路或删除什么闹钟。例如：\n“工作日早上 7:30 叫我起床”\n“删除每天晚上十点的提醒”", modifier = Modifier.padding(14.dp), color = ForestText)
                }
            }
        }
        if (message.isNotBlank()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(color = ForestPine, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(0.82f)) {
                        Text(message, modifier = Modifier.padding(14.dp), color = Color.White)
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = ForestSurface), border = androidx.compose.foundation.BorderStroke(1.dp, ForestOutline)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("输入你的需求") })
                    Row {
                        Button(
                            onClick = {
                                scope.launch {
                                    status = "AI 正在理解你的需求…"
                                    c.parseAi(message).onSuccess { parsed ->
                                        draft = parsed
                                        if (parsed.action == AiActionType.CREATE) {
                                            runCatching { parsed.toForestRule() }
                                                .onSuccess { editorRule = it; status = "我已经整理好了，确认前你还可以修改。" }
                                                .onFailure { status = it.message }
                                        } else {
                                            deleteMatches = c.findAlarmMatches(parsed)
                                            selectedDeleteIds = if (parsed.deleteAllMatches) deleteMatches.map { it.id }.toSet() else if (deleteMatches.size == 1) setOf(deleteMatches.first().id) else emptySet()
                                            status = if (deleteMatches.isEmpty()) "没有找到匹配的闹钟。" else "我找到了 ${deleteMatches.size} 个候选，请确认要删除哪些。"
                                        }
                                    }.onFailure { status = it.message }
                                }
                            },
                            enabled = message.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = ForestPine)
                        ) { Icon(Icons.Rounded.Send, null); Spacer(Modifier.width(6.dp)); Text("发送给 AI") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (listening) speech.stop()
                                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) speech.start()
                                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        ) { Icon(if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, null); Spacer(Modifier.width(5.dp)); Text(if (listening) "停止" else "语音") }
                    }
                    status?.let { Text(it, color = ForestSoft) }
                }
            }
        }

        if (draft?.action == AiActionType.DELETE) {
            item {
                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = ForestSurface2)) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("删除确认", fontWeight = FontWeight.Bold, color = ForestDanger)
                        deleteMatches.forEach { alarm ->
                            Row(Modifier.fillMaxWidth().clickable { selectedDeleteIds = if (alarm.id in selectedDeleteIds) selectedDeleteIds - alarm.id else selectedDeleteIds + alarm.id }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = alarm.id in selectedDeleteIds, onCheckedChange = null)
                                Column(Modifier.weight(1f)) { Text(alarm.title); Text(alarmSummaryForest(alarm), color = ForestSoft) }
                            }
                        }
                        Button(
                            onClick = { scope.launch { val chosen = deleteMatches.filter { it.id in selectedDeleteIds }; c.deleteAlarms(chosen); status = "已删除 ${chosen.size} 个闹钟"; draft = null; deleteMatches = emptyList(); selectedDeleteIds = emptySet() } },
                            enabled = selectedDeleteIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = ForestDanger),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("确认删除") }
                    }
                }
            }
        }
    }

    editorRule?.let { initial ->
        ForestAlarmEditor(
            initial = initial,
            title = "AI 已整理好 · 确认前可修改",
            onDismiss = { editorRule = null },
            onSave = { edited -> scope.launch { c.addAlarm(edited.copy(id = 0)); editorRule = null; draft = null; status = "已创建闹钟" } }
        )
    }
}

@Composable
private fun ForestAlarmEditor(initial: AlarmRule, title: String, onDismiss: () -> Unit, onSave: (AlarmRule) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.title) }
    var type by remember(initial) { mutableStateOf(initial.scheduleType) }
    var time by remember(initial) { mutableStateOf("%02d:%02d".format(initial.hour, initial.minute)) }
    var date by remember(initial) { mutableStateOf(initial.onceAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() } ?: LocalDate.now().plusDays(1).toString()) }
    var interval by remember(initial) { mutableStateOf((initial.intervalMinutes ?: 120).toString()) }
    var startTime by remember(initial) { mutableStateOf(minutesToForestTime(initial.windowStartMinutes ?: 540)) }
    var endTime by remember(initial) { mutableStateOf(minutesToForestTime(initial.windowEndMinutes ?: 1260)) }
    var weekdaysMask by remember(initial) { mutableIntStateOf(if (initial.weekdaysMask == 0) 31 else initial.weekdaysMask) }
    var sound by remember(initial) { mutableStateOf(initial.sound) }
    var vibration by remember(initial) { mutableStateOf(initial.vibration) }
    var notification by remember(initial) { mutableStateOf(initial.notification) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForestSurface,
        title = { Text(title, color = ForestPine) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 570.dp)) {
                item { OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("名称") }) }
                item {
                    Text("重复方式", color = ForestSoft)
                    ScheduleType.entries.chunked(3).forEach { row ->
                        Row { row.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(scheduleLabelForest(t)) }, modifier = Modifier.padding(end = 5.dp)) } }
                    }
                }
                if (type != ScheduleType.INTERVAL) item { OutlinedTextField(time, { time = it }, modifier = Modifier.fillMaxWidth(), label = { Text("时间 HH:mm") }) }
                if (type == ScheduleType.ONCE) item { OutlinedTextField(date, { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("日期 YYYY-MM-DD") }) }
                if (type == ScheduleType.WEEKLY) item {
                    Text("星期", color = ForestSoft)
                    (1..7).chunked(4).forEach { row -> Row { row.forEach { day -> val bit = 1 shl (day - 1); FilterChip(selected = weekdaysMask and bit != 0, onClick = { weekdaysMask = weekdaysMask xor bit }, label = { Text("${"一二三四五六日"[day - 1]}") }, modifier = Modifier.padding(end = 5.dp)) } } }
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
                    Text("到点后系统会唤醒 DayPulse，并显示全屏停止界面。", color = ForestSoft)
                    error?.let { Text(it, color = ForestDanger) }
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
            }, colors = ButtonDefaults.buttonColors(containerColor = ForestPine)) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ForestSettingsScreen(c: AppController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var workday by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ForestHeader("设置", "后台闹钟、AI 和工作日规则") }
        item {
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = ForestSurface), border = androidx.compose.foundation.BorderStroke(1.dp, ForestOutline)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("后台闹钟可靠性", fontWeight = FontWeight.Bold, color = ForestPine)
                    Text("闹钟由 Android 系统 AlarmManager 保管，不依赖 App 一直常驻后台。", color = ForestSoft)
                    Text(if (c.scheduler.canScheduleExact()) "✓ 已允许精确闹钟" else "需要开启精确闹钟权限，否则可能有延迟", color = if (c.scheduler.canScheduleExact()) ForestPine else ForestDanger)
                    if (!c.scheduler.canScheduleExact() && Build.VERSION.SDK_INT >= 31) {
                        Button(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("去开启精确闹钟") }
                    }
                    Text("手机重启或 App 更新后会自动重新注册已开启的闹钟。", color = ForestSoft)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = ForestSurface), border = androidx.compose.foundation.BorderStroke(1.dp, ForestOutline)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI · SiliconFlow / DeepSeek", fontWeight = FontWeight.Bold, color = ForestPine)
                    OutlinedTextField(key, { key = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation())
                    Row {
                        Button(onClick = { scope.launch { runCatching { c.saveApiKey(key) }.onSuccess { key = ""; message = "Key 已保存" }.onFailure { message = it.message } } }) { Text(if (c.hasApiKey) "更新 Key" else "保存 Key") }
                        if (c.hasApiKey) TextButton(onClick = { scope.launch { c.clearApiKey(); message = "Key 已删除" } }) { Text("删除") }
                    }
                    Text("Key 只保存在本机加密存储中。", color = ForestSoft)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = ForestSurface), border = androidx.compose.foundation.BorderStroke(1.dp, ForestOutline)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("法定工作日覆盖", fontWeight = FontWeight.Bold, color = ForestPine)
                    OutlinedTextField(date, { date = it }, modifier = Modifier.fillMaxWidth(), label = { Text("YYYY-MM-DD") })
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("设为工作日", Modifier.weight(1f)); Switch(workday, { workday = it }) }
                    Button(onClick = { scope.launch { runCatching { c.setWorkdayOverride(date, workday) }.onSuccess { message = "已保存" }.onFailure { message = it.message } } }) { Text("保存覆盖") }
                    c.workdayOverrides.take(10).forEach { o ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${o.dateKey} · ${if (o.isWorkday) "工作日" else "休息日"}", Modifier.weight(1f), color = ForestSoft)
                            IconButton(onClick = { scope.launch { c.deleteWorkdayOverride(o.dateKey) } }) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = ForestDanger) }
                        }
                    }
                }
            }
        }
        message?.let { item { Text(it, color = ForestPine) } }
    }
}

private fun AiAlarmDraft.toForestRule(): AlarmRule {
    val type = requireNotNull(scheduleType) { "AI 没识别出重复方式，请在确认框里补充" }
    val base = LocalTime.parse(time ?: startTime ?: "08:00", DateTimeFormatter.ofPattern("H:mm"))
    val mask = weekdays.fold(0) { m, d -> if (d in 1..7) m or (1 shl (d - 1)) else m }
    val start = startTime?.let { LocalTime.parse(it, DateTimeFormatter.ofPattern("H:mm")) }
    val end = endTime?.let { LocalTime.parse(it, DateTimeFormatter.ofPattern("H:mm")) }
    return AlarmRule(
        title = title.ifBlank { "AI 提醒" },
        scheduleType = type,
        hour = base.hour,
        minute = base.minute,
        weekdaysMask = mask,
        onceAt = if (type == ScheduleType.ONCE) LocalDate.parse(requireNotNull(date)).atTime(base).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else null,
        intervalMinutes = intervalMinutes,
        windowStartMinutes = start?.let { it.hour * 60 + it.minute },
        windowEndMinutes = end?.let { it.hour * 60 + it.minute },
        sound = sound,
        vibration = vibration,
        notification = notification
    )
}

private fun alarmSummaryForest(a: AlarmRule): String = when (a.scheduleType) {
    ScheduleType.ONCE -> a.onceAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) } ?: "单次"
    ScheduleType.DAILY -> "每天 %02d:%02d".format(a.hour, a.minute)
    ScheduleType.WEEKLY -> "每周 ${maskTextForest(a.weekdaysMask)} · %02d:%02d".format(a.hour, a.minute)
    ScheduleType.WORKDAY -> "工作日 %02d:%02d".format(a.hour, a.minute)
    ScheduleType.INTERVAL -> "${minutesToForestTime(a.windowStartMinutes ?: 540)}–${minutesToForestTime(a.windowEndMinutes ?: 1260)} · 每 ${a.intervalMinutes ?: 60} 分钟"
}

private fun nextExecutionText(alarm: AlarmRule, triggerMillis: Long?, nowMillis: Long): String {
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
        totalMinutes <= 0 -> "不到 1 分钟"
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时${minutes}分钟"
        else -> "${minutes}分钟"
    }
    return "下次：$dayText ${target.format(DateTimeFormatter.ofPattern("HH:mm"))} · 还有 $remain"
}

private fun maskTextForest(mask: Int): String = (1..7).filter { mask and (1 shl (it - 1)) != 0 }.joinToString("/") { "周${"一二三四五六日"[it - 1]}" }
private fun weekdayTextForest(mask: Int): String = if (mask == 127) "每天" else maskTextForest(mask)
private fun minutesToForestTime(value: Int): String = "%02d:%02d".format(value / 60, value % 60)
private fun scheduleLabelForest(type: ScheduleType): String = when (type) {
    ScheduleType.ONCE -> "单次"
    ScheduleType.DAILY -> "每天"
    ScheduleType.WEEKLY -> "每周"
    ScheduleType.WORKDAY -> "工作日"
    ScheduleType.INTERVAL -> "循环"
}
