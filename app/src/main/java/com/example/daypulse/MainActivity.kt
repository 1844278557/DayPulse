package com.example.daypulse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.daypulse.alarm.NotificationHelper
import com.example.daypulse.model.AiAlarmDraft
import com.example.daypulse.model.AlarmRule
import com.example.daypulse.model.ScheduleType
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.ensureChannel(this)
        setContent { MaterialTheme { DayPulseApp() } }
    }
}

private enum class Tab(val text: String) { TODAY("今天"), ALARM("闹钟"), AI("AI"), STATS("统计"), SETTINGS("设置") }

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
    Scaffold(bottomBar = {
        NavigationBar { Tab.entries.forEach { NavigationBarItem(selected = tab == it, onClick = { tab = it }, icon = {}, label = { Text(it.text) }) } }
    }) { pad ->
        if (controller.loading) Box(Modifier.fillMaxSize().padding(pad)) { CircularProgressIndicator() }
        else when (tab) {
            Tab.TODAY -> TodayScreen(controller, Modifier.padding(pad))
            Tab.ALARM -> AlarmScreen(controller, Modifier.padding(pad))
            Tab.AI -> AiScreen(controller, Modifier.padding(pad))
            Tab.STATS -> StatsScreen(controller, Modifier.padding(pad))
            Tab.SETTINGS -> SettingsScreen(controller, Modifier.padding(pad))
        }
    }
}

@Composable
private fun TodayScreen(c: AppController, modifier: Modifier) {
    val scope = rememberCoroutineScope(); var title by remember { mutableStateOf("") }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("DayPulse", style = MaterialTheme.typography.headlineMedium); Text("${LocalDate.now()} · ${c.completedTodayCount()}/${c.habits.size} 已完成") }
        item { Row { OutlinedTextField(title, { title = it }, label = { Text("新增打卡") }, modifier = Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); Button({ scope.launch { c.addHabit(title); title = "" } }) { Text("添加") } } }
        if (c.habits.isEmpty()) item { Text("还没有打卡项目，先添加一个吧。") }
        items(c.habits, key = { it.id }) { h ->
            Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { Text(h.title, style = MaterialTheme.typography.titleMedium); Text("连续 ${c.currentStreak(h.id)} 天 · 近30天 ${c.completionCountLastDays(h.id,30)} 次") }
                Button({ scope.launch { c.toggleToday(h.id) } }) { Text(if (c.isDoneToday(h.id)) "已打卡" else "打卡") }
                TextButton({ scope.launch { c.deleteHabit(h.id) } }) { Text("删除") }
            } }
        }
    }
}

@Composable
private fun AlarmScreen(c: AppController, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("08:00") }
    var type by remember { mutableStateOf(ScheduleType.DAILY) }
    var interval by remember { mutableStateOf("120") }
    var message by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("闹钟", style = MaterialTheme.typography.headlineMedium) }
        item {
            OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("提醒名称") })
            OutlinedTextField(value = time, onValueChange = { time = it }, modifier = Modifier.fillMaxWidth(), label = { Text("时间 HH:mm") })
            Row { ScheduleType.entries.forEach { FilterChip(selected = type == it, onClick = { type = it }, label = { Text(it.name) }, modifier = Modifier.padding(end = 4.dp)) } }
            if (type == ScheduleType.INTERVAL) {
                OutlinedTextField(value = interval, onValueChange = { interval = it }, modifier = Modifier.fillMaxWidth(), label = { Text("间隔分钟") })
            }
            Button({ scope.launch { runCatching { c.addAlarm(manualRule(title,time,type,interval)) }.onSuccess { title=""; message="已创建" }.onFailure { message=it.message } } }, enabled=title.isNotBlank()) { Text("创建提醒") }
            message?.let { Text(it) }
        }
        items(c.alarms, key={it.id}) { a -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(a.title, style=MaterialTheme.typography.titleMedium); Text(summary(a)); Row { Button({scope.launch{c.toggleAlarm(a)}}){Text(if(a.enabled)"暂停" else "启用")}; TextButton({scope.launch{c.deleteAlarm(a)}}){Text("删除")} } } } }
    }
}

@Composable
private fun AiScreen(c: AppController, modifier: Modifier) {
    val scope=rememberCoroutineScope(); var command by remember{mutableStateOf("")}; var draft by remember{mutableStateOf<AiAlarmDraft?>(null)}; var status by remember{mutableStateOf<String?>(null)}
    val voice=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){ r -> command=r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?:command }
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { Text("AI 设置",style=MaterialTheme.typography.headlineMedium); Text("例如：每周一三五晚上8点提醒我健身") }
        item { OutlinedTextField(command,{command=it},label={Text("告诉 AI 你想怎么提醒")},modifier=Modifier.fillMaxWidth(),minLines=3); Row { Button({ scope.launch { status="解析中…"; c.parseAi(command).onSuccess{draft=it;status=null}.onFailure{status=it.message} } }){Text("解析")}; Spacer(Modifier.width(8.dp)); OutlinedButton({ voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN") }) }){Text("语音")} }; status?.let{Text(it)} }
        draft?.let { d -> item { Card { Column(Modifier.padding(16.dp)) { Text(d.title,style=MaterialTheme.typography.titleLarge); Text(d.toText()); Button({scope.launch{runCatching{c.addAlarm(d.toRule())}.onSuccess{draft=null;status="已创建"}.onFailure{status=it.message}}}){Text("确认创建")} } } } }
    }
}

@Composable
private fun StatsScreen(c:AppController,modifier:Modifier){ LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){ item{Text("统计",style=MaterialTheme.typography.headlineMedium)}; items(c.habits){h->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text(h.title);Text("当前连续 ${c.currentStreak(h.id)} 天 · 近7天 ${c.completionCountLastDays(h.id,7)}/7 · 近30天 ${c.completionCountLastDays(h.id,30)}/30")}}} } }

@Composable
private fun SettingsScreen(c:AppController,modifier:Modifier){ val context=LocalContext.current; val scope=rememberCoroutineScope(); var key by remember{mutableStateOf("")}; var date by remember{mutableStateOf(LocalDate.now().toString())}; var workday by remember{mutableStateOf(true)}; LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){ item{Text("设置",style=MaterialTheme.typography.headlineMedium)}; item{Card{Column(Modifier.padding(14.dp)){Text("SiliconFlow / DeepSeek");OutlinedTextField(key,{key=it},label={Text("API Key")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Button({scope.launch{c.saveApiKey(key);key=""}}){Text(if(c.hasApiKey)"更新 Key" else "保存 Key")};Text("模型：deepseek-ai/DeepSeek-V3.2")}}}; item{Card{Column(Modifier.padding(14.dp)){Text("精确闹钟");Text(if(c.scheduler.canScheduleExact())"已允许" else "未允许，提醒可能延迟");if(!c.scheduler.canScheduleExact()&&Build.VERSION.SDK_INT>=31) Button({context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))}){Text("授权")}}}}; item{Card{Column(Modifier.padding(14.dp)){Text("工作日覆盖");OutlinedTextField(date,{date=it},label={Text("YYYY-MM-DD")});Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("设为工作日");Switch(workday,{workday=it})};Button({scope.launch{c.setWorkdayOverride(date,workday)}}){Text("保存")};c.workdayOverrides.take(12).forEach{o->Row{Text("${o.dateKey} ${if(o.isWorkday)"工作日" else "休息日"}",Modifier.weight(1f));TextButton({scope.launch{c.deleteWorkdayOverride(o.dateKey)}}){Text("删除")}}}}}} } }

private fun manualRule(title:String,time:String,type:ScheduleType,interval:String):AlarmRule{ val t=LocalTime.parse(time,DateTimeFormatter.ofPattern("H:mm")); return AlarmRule(title=title.trim(),scheduleType=type,hour=t.hour,minute=t.minute,weekdaysMask=if(type==ScheduleType.WEEKLY)31 else 0,intervalMinutes=if(type==ScheduleType.INTERVAL) interval.toIntOrNull()?.coerceAtLeast(1)?:60 else null,windowStartMinutes=if(type==ScheduleType.INTERVAL)t.hour*60+t.minute else null,windowEndMinutes=if(type==ScheduleType.INTERVAL)22*60 else null,onceAt=if(type==ScheduleType.ONCE)LocalDate.now().plusDays(1).atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else null) }
private fun summary(a:AlarmRule)=when(a.scheduleType){ScheduleType.ONCE->"单次";ScheduleType.DAILY->"每天 %02d:%02d".format(a.hour,a.minute);ScheduleType.WEEKLY->"每周一至五 %02d:%02d".format(a.hour,a.minute);ScheduleType.WORKDAY->"工作日 %02d:%02d".format(a.hour,a.minute);ScheduleType.INTERVAL->"每隔 ${a.intervalMinutes} 分钟"}
private fun AiAlarmDraft.toText()=when(scheduleType){ScheduleType.ONCE->"$date $time";ScheduleType.DAILY->"每天 $time";ScheduleType.WEEKLY->"每周 ${weekdays.joinToString()} $time";ScheduleType.WORKDAY->"工作日 $time";ScheduleType.INTERVAL->"$startTime-$endTime 每隔 $intervalMinutes 分钟"}
private fun AiAlarmDraft.toRule():AlarmRule{ val t=LocalTime.parse(time?:startTime?:"08:00",DateTimeFormatter.ofPattern("H:mm")); val mask=weekdays.fold(0){m,d->if(d in 1..7)m or(1 shl(d-1))else m}; val start=startTime?.let{LocalTime.parse(it)}; val end=endTime?.let{LocalTime.parse(it)}; return AlarmRule(title=title,scheduleType=scheduleType,hour=t.hour,minute=t.minute,weekdaysMask=mask,onceAt=if(scheduleType==ScheduleType.ONCE)LocalDate.parse(requireNotNull(date)).atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else null,intervalMinutes=intervalMinutes,windowStartMinutes=start?.let{it.hour*60+it.minute},windowEndMinutes=end?.let{it.hour*60+it.minute},sound=sound,vibration=vibration,notification=notification) }
