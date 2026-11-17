package com.penbridge

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleCommand(intent)
        try {
            startForegroundService(Intent(this, PenService::class.java))
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommand(intent)
    }

    private fun handleCommand(intent: Intent?) {
        if (intent?.getStringExtra("cmd") == "dump") {
            PenService.dumpToFile()
            toast("界面已转储")
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun repair() {
        Thread({ AccessibilityFixer.repair(this) }, "acc-repair").start()
    }

    private fun launchPackage(pkg: String) {
        var intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            val probe = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(pkg)
            val list = packageManager.queryIntentActivities(probe, 0)
            if (list.isNotEmpty()) {
                val ri = list[0]
                intent = Intent(probe).setComponent(
                    ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                )
            }
        }
        if (intent == null) {
            toast("未安装 $pkg")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("打开失败: $e")
        }
    }

    @Composable
    private fun App() {
        var page by remember { mutableStateOf(0) }
        MiuixTheme {
            Surface(color = MiuixTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    if (page == 0) {
                        MainScreen(
                            onOpenLicenses = { page = 1 },
                            onAccessibility = ::openAccessibilitySettings,
                            onRepair = ::repair,
                            onLaunch = ::launchPackage,
                            toast = ::toast,
                        )
                    } else {
                        BackHandler { page = 0 }
                        LicenseScreen(onBack = { page = 0 })
                    }
                }
            }
        }
    }

    @Composable
    private fun MainScreen(
        onOpenLicenses: () -> Unit,
        onAccessibility: () -> Unit,
        onRepair: () -> Unit,
        onLaunch: (String) -> Unit,
        toast: (String) -> Unit,
    ) {
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                tick++
            }
        }
        LaunchedEffect(Unit) {
            delay(1500)
            if (PenAccessibilityService.INSTANCE == null) onRepair()
        }

        val acc = PenAccessibilityService.INSTANCE != null
        val service = PenService.isRunning()
        val foreground = if (acc) PenAccessibilityService.INSTANCE.foregroundPackage() else null
        val mode = Prefs.eraserModeName(this)
        val level = Prefs.pressureLevel(this)
        val logs = remember(tick) { LogBuffer.text() }

        Scaffold(
            topBar = { SmallTopAppBar(title = "笔控") },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = 24.dp,
                ),
            ) {
                item {
                    StatusCard(acc, service, foreground, mode, level)
                }
                item {
                    SmallTitle(text = "控制")
                    Card(Modifier.padding(horizontal = 12.dp)) {
                        BasicComponent(
                            title = "开启无障碍服务",
                            summary = if (acc) "已连接，无需操作" else "未连接，点这里去系统设置开启",
                            onClick = onAccessibility,
                        )
                        BasicComponent(
                            title = "修复无障碍 / 电池白名单",
                            summary = "需要 root，执行后稍等 2 秒",
                            onClick = {
                                onRepair()
                                toast("已执行，稍等 2 秒")
                            },
                        )
                        BasicComponent(
                            title = if (service) "笔控服务运行中" else "启动笔控服务",
                            summary = if (service) "前台服务已启动" else "点此启动监听",
                            onClick = {
                                startForegroundService(Intent(this@MainActivity, PenService::class.java))
                                toast("已启动")
                            },
                        )
                        BasicComponent(
                            title = "停止服务",
                            summary = "临时关闭手势监听",
                            onClick = {
                                stopService(Intent(this@MainActivity, PenService::class.java))
                                toast("已停止")
                            },
                        )
                    }
                }
                item {
                    SmallTitle(text = "手势")
                    Card(Modifier.padding(horizontal = 12.dp)) {
                        BasicComponent(
                            title = "草稿纸开 / 关",
                            summary = "单击笔键触发",
                            onClick = { toast(ActionRunner.handle(ActionRunner.GESTURE_DOUBLE)) },
                        )
                        BasicComponent(
                            title = "橡皮 / 画笔",
                            summary = "重按/长按触发，这里可手动切换",
                            onClick = { toast(ActionRunner.handle(ActionRunner.GESTURE_SINGLE)) },
                        )
                        BasicComponent(
                            title = "橡皮触发方式",
                            summary = mode,
                            onClick = {
                                Prefs.cycleEraserMode(this@MainActivity)
                                toast("橡皮触发: " + Prefs.eraserModeName(this@MainActivity))
                            },
                        )
                        BasicComponent(
                            title = "重按压力阈值",
                            summary = "$level（重按笔尖并停顿）",
                            onClick = {
                                Prefs.cyclePressureLevel(this@MainActivity)
                                toast("重按阈值: " + Prefs.pressureLevel(this@MainActivity))
                            },
                        )
                    }
                }
                item {
                    SmallTitle(text = "工具")
                    Card(Modifier.padding(horizontal = 12.dp)) {
                        BasicComponent(
                            title = "打开粉笔",
                            summary = ActionRunner.FENBI,
                            onClick = { onLaunch(ActionRunner.FENBI) },
                        )
                        BasicComponent(
                            title = "打开华图",
                            summary = ActionRunner.HUATU,
                            onClick = { onLaunch(ActionRunner.HUATU) },
                        )
                        BasicComponent(
                            title = "转储当前界面",
                            summary = "界面识别失败时导出控件树",
                            onClick = {
                                PenService.dumpToFile()
                                toast("已转储")
                            },
                        )
                        BasicComponent(
                            title = "按键调试日志",
                            summary = if (PenService.DEBUG_KEYS) "已开启" else "已关闭",
                            onClick = {
                                PenService.DEBUG_KEYS = !PenService.DEBUG_KEYS
                                PenAccessibilityService.DEBUG_KEYS = PenService.DEBUG_KEYS
                                toast("按键调试: " + if (PenService.DEBUG_KEYS) "开" else "关")
                            },
                        )
                    }
                }
                item {
                    SmallTitle(text = "日志")
                    Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                        if (logs.isBlank()) {
                            Text(
                                text = "暂无日志",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(12.dp),
                            )
                        } else {
                            BasicText(
                                text = logs,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                ),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
                item {
                    SmallTitle(text = "关于")
                    Card(Modifier.padding(horizontal = 12.dp)) {
                        BasicComponent(
                            title = "开源许可",
                            summary = "Miuix / Compose / Kotlin 等组件声明",
                            onClick = onOpenLicenses,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusCard(acc: Boolean, service: Boolean, foreground: String?, mode: String, level: Int) {
        Card(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                StatusLine("无障碍", if (acc) "已连接" else "未连接", acc)
                StatusLine("笔控服务", if (service) "运行中" else "未运行", service)
                StatusLine("前台应用", foreground ?: "-", ActionRunner.isTarget(foreground))
                StatusLine("橡皮触发", mode, true)
                StatusLine("压力阈值", level.toString(), true)
            }
        }
    }

    @Composable
    private fun StatusLine(label: String, value: String, ok: Boolean) {
        Box(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(text = label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(
                text = value,
                color = if (ok) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.error,
                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd),
            )
        }
    }

    @Composable
    private fun LicenseScreen(onBack: () -> Unit) {
        val context = this
        val apache = remember {
            runCatching {
                context.assets.open("licenses/Apache-2.0.txt").bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
        val notice = remember {
            runCatching {
                context.assets.open("licenses/NOTICE.txt").bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = "开源许可",
                    navigationIcon = { TextButton(text = "返回", onClick = onBack) },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(padding)) {
                        SmallTitle(text = "本应用")
                        Card(Modifier.padding(horizontal = 12.dp)) {
                            BasicComponent(
                                title = "笔控 com.penbridge",
                                summary = "自有代码，按 Apache License 2.0 发布",
                            )
                        }
                        SmallTitle(text = "第三方组件")
                        Card(Modifier.padding(horizontal = 12.dp)) {
                            BasicComponent(
                                title = "Miuix 0.9.4",
                                summary = "compose-miuix-ui contributors · Apache-2.0 · github.com/compose-miuix-ui/miuix",
                            )
                            BasicComponent(
                                title = "Jetpack Compose / AndroidX Activity Compose 1.13.0",
                                summary = "The Android Open Source Project · Apache-2.0",
                            )
                            BasicComponent(
                                title = "Kotlin 2.4.20",
                                summary = "JetBrains s.r.o. · Apache-2.0",
                            )
                            BasicComponent(
                                title = "Material Color Utilities 5.0.1",
                                summary = "Google LLC · Apache-2.0",
                            )
                            BasicComponent(
                                title = "AndroidX NavigationEvent",
                                summary = "The Android Open Source Project · Apache-2.0",
                            )
                        }
                        SmallTitle(text = "声明")
                        Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                            BasicText(
                                text = notice,
                                style = TextStyle(fontSize = 12.sp),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        SmallTitle(text = "Apache License 2.0 全文")
                        Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                            BasicText(
                                text = apache,
                                style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
