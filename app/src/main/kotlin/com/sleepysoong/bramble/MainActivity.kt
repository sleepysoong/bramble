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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("connection", MODE_PRIVATE) }
    private var server by mutableStateOf("")
    private var token by mutableStateOf("")
    private var theme by mutableStateOf("system")
    private var batteryExempt by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) activateConnection() else RelayState.status = "요청 알림 권한을 허용해야 백그라운드 수신을 시작할 수 있습니다"
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = RelayState.pending ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        if (request.expiresAtMillis <= System.currentTimeMillis()) {
            RelayState.status = "요청이 만료됐습니다. 코딩 도구에서 다시 요청해 주세요"
        } else RelayService.file(this, request.id, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = prefs.getString("server", "http://10.0.2.2:8787") ?: ""
        token = prefs.getString("token", "") ?: ""
        theme = prefs.getString("theme", "system") ?: "system"
        RelayState.enabled = prefs.getBoolean("enabled", false)
        if (RelayState.enabled && !RelayState.running) RelayState.status = "수신 서비스를 시작하는 중"
        setContent { Screen() }
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

    private fun validServer(value: String): Boolean = runCatching {
        val parsed = java.net.URI(value.trim())
        (parsed.scheme == "http" || parsed.scheme == "https") && parsed.host != null && parsed.userInfo == null &&
            parsed.query == null && parsed.fragment == null && (parsed.path.isNullOrEmpty() || parsed.path == "/")
    }.getOrDefault(false)

    private fun saveConnection() {
        if (RelayState.pending != null) { RelayState.status = "현재 요청을 처리한 뒤 연결을 변경하세요"; return }
        if (!validServer(server) || token.trim().length < 32) {
            RelayState.status = "서버 주소와 32자 이상의 토큰을 확인하세요"; return
        }
        prefs.edit().putString("server", server.trimEnd('/')).putString("token", token.trim()).apply()
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

    private fun shareClipboard() {
        val request = RelayState.pending ?: return
        if (request.expiresAtMillis <= System.currentTimeMillis()) {
            RelayState.status = "요청이 만료됐습니다. 코딩 도구에서 다시 요청해 주세요"
            return
        }
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text == null) { RelayState.status = "클립보드에 텍스트가 없습니다"; return }
        RelayService.clipboard(this, request.id, text)
    }

    @Composable
    private fun Screen() {
        val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
        val dark = when (theme) { "dark" -> true; "light" -> false; else -> systemDark }
        MaterialTheme(colorScheme=if (dark) darkColorScheme() else lightColorScheme()) {
            GlassHost(dark) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp, vertical=28.dp),
                    verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    GlassPanel(Modifier.fillMaxWidth(), GlassTone.Thick) {
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Image(painterResource(R.drawable.bramble_logo), contentDescription=null, modifier=Modifier.size(48.dp))
                            Column {
                                Text("Bramble", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.SemiBold)
                                Text("휴대폰과 코딩 도구 연결", style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    GlassPanel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("연결", style=MaterialTheme.typography.titleMedium)
                            Text(RelayState.status, style=MaterialTheme.typography.bodyMedium)
                            GlassField("Go 프록시 주소", server, { server = it }, Modifier.fillMaxWidth())
                            GlassField("연결 토큰", token, { token = it }, Modifier.fillMaxWidth(), secret=true)
                            GlassButton("저장하고 백그라운드 수신 시작", ::saveConnection, accent=true)
                            if (RelayState.enabled) GlassButton("수신 중지", ::stopConnection)
                        }
                    }

                    RelayState.pending?.let { request ->
                        GlassPanel(Modifier.fillMaxWidth(), GlassTone.Thick) {
                            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                Text(if (request.kind == "file") "파일 요청" else "클립보드 요청", style=MaterialTheme.typography.titleLarge)
                                Text(request.prompt.ifBlank { "코딩 도구가 휴대폰 자료를 요청했습니다." })
                                Text("공유하기 전 내용을 확인하세요.", style=MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    GlassButton(if (request.kind == "file") "파일 선택" else "클립보드 공유",
                                        if (request.kind == "file") ({ filePicker.launch(arrayOf("*/*")) }) else ::shareClipboard,
                                        accent=true, enabled=!RelayState.busy)
                                    GlassButton("거절", { RelayService.reject(this@MainActivity, request.id) }, enabled=!RelayState.busy)
                                }
                            }
                        }
                    }

                    GlassPanel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("배터리", style=MaterialTheme.typography.titleMedium)
                            Text(if (batteryExempt) "배터리 최적화 예외가 설정됐습니다" else
                                "절전 모드에서는 요청 알림이 늦어질 수 있습니다. 즉시 수신이 필요하면 Bramble을 최적화 예외로 설정하세요.",
                                style=MaterialTheme.typography.bodySmall)
                            if (!batteryExempt) GlassButton("배터리 최적화 설정 열기", {
                                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            })
                        }
                    }

                    GlassPanel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("화면 스타일", style=MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                listOf("system" to "시스템", "light" to "라이트", "dark" to "다크").forEach { (value, label) ->
                                    GlassButton(label, { theme=value; prefs.edit().putString("theme", value).apply() }, accent=theme==value)
                                }
                            }
                        }
                    }
                    Text("수신 중에는 알림이 표시됩니다. 요청은 2분 후 만료되며 파일은 최대 20 MiB입니다.", style=MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
