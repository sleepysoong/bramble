package com.sleepysoong.bramble

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("connection", MODE_PRIVATE) }
    private var server by mutableStateOf("")
    private var token by mutableStateOf("")
    private var theme by mutableStateOf("system")
    private var batteryExempt by mutableStateOf(false)
    private var fileRequestId: String? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) activateConnection() else RelayState.status = "요청 알림 권한을 허용해야 백그라운드 수신을 시작할 수 있습니다"
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val selectedFor = fileRequestId
        fileRequestId = null
        if (uri == null || selectedFor == null) return@registerForActivityResult
        val request = RelayState.pending
        // A file selected for an expired request must never answer a newer request.
        if (request?.id != selectedFor || request.kind != "file" || request.expiresAtMillis <= System.currentTimeMillis()) {
            Toast.makeText(this, "파일을 요청했던 작업이 만료되거나 취소됐습니다. 다시 요청해 주세요", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        RelayService.file(this, selectedFor, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        server = savedInstanceState?.getString("server") ?: prefs.getString("server", "http://10.0.2.2:8787") ?: ""
        token = savedInstanceState?.getString("token") ?: prefs.getString("token", "") ?: ""
        theme = prefs.getString("theme", "system") ?: "system"
        fileRequestId = savedInstanceState?.getString("file_request_id")
        RelayState.enabled = prefs.getBoolean("enabled", false)
        if (RelayState.enabled && !RelayState.running) RelayState.status = "수신 서비스를 시작하는 중"
        setContent {
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val dark = when (theme) { "dark" -> true; "light" -> false; else -> systemDark }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            BrambleTheme(dark) {
                BrambleScreen(server, token, theme, batteryExempt,
                    onServerChange = { server = it }, onTokenChange = { token = it },
                    onSave = ::saveConnection, onStop = ::stopConnection,
                    onThemeChange = { theme = it; prefs.edit().putString("theme", it).apply() },
                    onBatterySettings = {
                        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                            .onFailure { RelayState.status = "이 기기에서는 앱 설정에서 배터리 최적화를 변경해 주세요" }
                    },
                    onShare = ::shareRequest,
                    onReject = { RelayState.pending?.let { RelayService.reject(this, it.id) } })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("server", server)
        outState.putString("token", token)
        outState.putString("file_request_id", fileRequestId)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        batteryExempt = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        if (prefs.getBoolean("enabled", false) && !RelayState.running) {
            if (hasNotificationPermission()) startServiceSafely()
            else RelayState.status = "알림 권한을 다시 허용해 주세요"
        }
    }

    private fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun saveConnection() {
        if (RelayState.pending != null || RelayState.busy) { RelayState.status = "현재 요청을 처리한 뒤 연결을 변경하세요"; return }
        val normalizedServer = server.trim().trimEnd('/')
        val normalizedToken = token.trim()
        if (!RelayClient.validServer(normalizedServer) || !RelayClient.validToken(normalizedToken)) {
            RelayState.status = "서버 주소와 공백 없는 32~4096자 ASCII 토큰을 확인하세요"; return
        }
        server = normalizedServer
        token = normalizedToken
        prefs.edit().putString("server", server).putString("token", token).apply()
        if (hasNotificationPermission()) activateConnection()
        else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun activateConnection() {
        prefs.edit().putBoolean("enabled", true).apply()
        RelayState.enabled = true
        startServiceSafely()
    }

    private fun startServiceSafely() {
        try { RelayService.start(this) }
        catch (e: RuntimeException) {
            prefs.edit().putBoolean("enabled", false).apply()
            RelayState.enabled = false
            RelayState.status = "수신 서비스를 시작할 수 없습니다: ${e.message}"
        }
    }

    private fun stopConnection() {
        prefs.edit().putBoolean("enabled", false).apply()
        RelayState.enabled = false
        RelayService.stop(this)
    }

    private fun shareRequest() {
        val request = RelayState.pending ?: return
        if (RelayState.busy || request.expiresAtMillis <= System.currentTimeMillis()) return
        if (request.kind == "file") {
            fileRequestId = request.id
            filePicker.launch(arrayOf("*/*"))
        } else shareClipboard()
    }

    private fun shareClipboard() {
        val request = RelayState.pending ?: return
        if (request.kind != "clipboard" || request.expiresAtMillis <= System.currentTimeMillis()) return
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text == null) { RelayState.status = "클립보드에 텍스트가 없습니다"; return }
        if (text.toByteArray(Charsets.UTF_8).size > RelayClient.MAX_CLIPBOARD_BYTES) {
            RelayState.status = "클립보드는 최대 1 MiB까지 보낼 수 있습니다"; return
        }
        RelayService.clipboard(this, request.id, text)
    }
}

@Composable
internal fun BrambleTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFA7D6C0), onPrimary = Color(0xFF0F382A),
        primaryContainer = Color(0xFF335447), onPrimaryContainer = Color(0xFFD5F3E5),
        background = Color(0xFF161A17), surface = Color(0xFF202621),
        onSurface = Color(0xFFE8EDE7), onSurfaceVariant = Color(0xFFBBC7BD)
    ) else lightColorScheme(
        primary = Color(0xFF285E48), onPrimary = Color.White,
        primaryContainer = Color(0xFFC8E7D7), onPrimaryContainer = Color(0xFF183C2D),
        background = Color(0xFFF8F7F3), surface = Color(0xFFF8F7F3),
        onSurface = Color(0xFF252C26), onSurfaceVariant = Color(0xFF546157)
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            GlassHost(dark) { content() }
        }
    }
}

@Composable
internal fun BrambleScreen(
    server: String, token: String, theme: String, batteryExempt: Boolean,
    onServerChange: (String) -> Unit, onTokenChange: (String) -> Unit,
    onSave: () -> Unit, onStop: () -> Unit, onThemeChange: (String) -> Unit,
    onBatterySettings: () -> Unit, onShare: () -> Unit, onReject: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        // Fixed floating bar, outside the scroll viewport and with shadow clearance.
        GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("top-bar"),
            GlassTone.Thick, PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(painterResource(R.drawable.bramble_logo), contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Bramble", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("휴대폰과 코딩 도구 연결", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                GlassBadge(if (RelayState.enabled) "수신 켜짐" else "중지", accent = RelayState.enabled)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().testTag("content-scroll")
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Spacer(Modifier.height(6.dp))
            RelayState.pending?.let { request ->
                key(request.id) { RequestCard(request, onShare, onReject) }
            }
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading("백그라운드 수신", "앱을 닫아도 휴대폰 요청을 기다립니다")
                    Text(RelayState.status, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    if (RelayState.enabled) GlassButton("수신 중지", onStop, Modifier.fillMaxWidth(), destructive = true)
                }
            }
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeading("연결 설정", "컴퓨터에서 실행 중인 프록시에 연결하세요")
                    GlassField("프록시 주소", server, onServerChange, Modifier.fillMaxWidth())
                    GlassField("연결 토큰", token, onTokenChange, Modifier.fillMaxWidth(), secret = true)
                    GlassButton(if (RelayState.enabled) "저장하고 다시 연결" else "저장하고 수신 시작", onSave,
                        Modifier.fillMaxWidth(), accent = true, enabled = RelayState.pending == null && !RelayState.busy)
                }
            }
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeading("화면 스타일", "앱에 사용할 밝기를 선택하세요")
                    val themes = listOf("system", "light", "dark")
                    GlassSegmentedControl(listOf("시스템", "라이트", "다크"), themes.indexOf(theme).coerceAtLeast(0),
                        { onThemeChange(themes[it]) }, Modifier.fillMaxWidth().testTag("theme-control"))
                }
            }
            GlassPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading("배터리 최적화", if (batteryExempt) "최적화 예외가 설정됐습니다" else "절전 중에는 요청 알림이 늦어질 수 있습니다")
                    if (!batteryExempt) {
                        Text("즉시 수신이 필요하면 기기 설정에서 Bramble을 최적화 예외로 설정하세요.",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        GlassButton("배터리 설정 열기", onBatterySettings, Modifier.fillMaxWidth())
                    }
                }
            }
            Text("수신 중에는 알림이 표시됩니다. 요청은 2분 후 만료되며, 파일은 최대 20 MiB · 클립보드는 최대 1 MiB입니다.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RequestCard(request: PhoneRequest, onShare: () -> Unit, onReject: () -> Unit) {
    val entrance = remember { Animatable(.94f, visibilityThreshold = .0005f) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(request.id) { entrance.animateTo(1f, spring(.68f, 380f, .0005f)) }
    LaunchedEffect(request.id) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val seconds = ((request.expiresAtMillis - now + 999) / 1000).coerceAtLeast(0)
    GlassPanel(Modifier.fillMaxWidth().graphicsLayer { scaleX = entrance.value; scaleY = entrance.value }.testTag("request-card"), GlassTone.Thick) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassBadge(if (RelayState.busy) "전송 중" else "${seconds}초 남음", accent = true)
            Text(if (request.kind == "file") "파일을 보내주세요" else "클립보드를 공유해주세요",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(request.prompt.ifBlank { "코딩 도구가 휴대폰 자료를 요청했습니다." }, style = MaterialTheme.typography.bodyMedium)
            Text(if (request.kind == "file") "선택한 파일을 연결된 컴퓨터로 전송합니다." else "현재 클립보드 텍스트를 연결된 컴퓨터로 전송합니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Full-width actions also fit narrow phones and enlarged text.
            GlassButton(if (request.kind == "file") "파일 선택" else "클립보드 공유", onShare,
                Modifier.fillMaxWidth(), accent = true, enabled = !RelayState.busy && seconds > 0)
            GlassButton("요청 거절", onReject, Modifier.fillMaxWidth(), enabled = !RelayState.busy && seconds > 0)
        }
    }
}
