package com.sleepysoong.bramble

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("connection", MODE_PRIVATE) }
    private var server by mutableStateOf("")
    private var token by mutableStateOf("")
    private var activeServer = ""
    private var activeToken = ""
    private var theme by mutableStateOf("system")
    private var status by mutableStateOf("연결 정보를 입력하세요")
    private var pending by mutableStateOf<PhoneRequest?>(null)
    private var pollJob: Job? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = pending ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching { client().upload(request.id, uri, contentResolver) }
                .onSuccess { status = "파일을 보냈습니다"; pending = null }
                .onFailure { status = "전송 실패: ${it.message}" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = prefs.getString("server", "http://10.0.2.2:8787") ?: ""
        token = prefs.getString("token", "") ?: ""
        activeServer = server
        activeToken = token
        theme = prefs.getString("theme", "system") ?: "system"
        setContent { Screen() }
    }

    override fun onStart() {
        super.onStart()
        startPolling()
    }

    override fun onStop() {
        pollJob?.cancel()
        pollJob = null
        super.onStop()
    }

    private fun client() = RelayClient(activeServer.trimEnd('/'), activeToken.trim())

    private fun startPolling() {
        if (pollJob != null || activeToken.isBlank() || !validServer(activeServer)) return
        pollJob = lifecycleScope.launch {
            val relay = client()
            while (isActive) {
                if (pending != null) { delay(500); continue }
                try {
                    status = "요청 대기 중"
                    val request = relay.next()
                    if (request != null) { pending = request; status = "휴대폰에서 확인해 주세요" }
                } catch (e: Exception) {
                    if (!isActive) break
                    status = "연결 오류: ${e.message}"
                    delay(3000)
                }
            }
        }
    }

    private fun validServer(value: String): Boolean = runCatching {
        val parsed = java.net.URI(value.trim())
        (parsed.scheme == "http" || parsed.scheme == "https") && parsed.host != null && parsed.userInfo == null && parsed.query == null && parsed.fragment == null && (parsed.path.isNullOrEmpty() || parsed.path == "/")
    }.getOrDefault(false)

    private fun saveConnection() {
        if (pending != null) { status = "현재 요청을 처리한 뒤 연결을 변경하세요"; return }
        if (!validServer(server) || token.trim().length < 32) { status = "서버 주소와 32자 이상의 토큰을 확인하세요"; return }
        prefs.edit().putString("server", server.trimEnd('/')).putString("token", token.trim()).apply()
        activeServer = server.trimEnd('/')
        activeToken = token.trim()
        pollJob?.cancel(); pollJob = null
        lifecycleScope.launch {
            status = if (runCatching { client().health() }.getOrDefault(false)) "연결됨" else "서버에 연결할 수 없습니다"
            startPolling()
        }
    }

    private fun shareClipboard() {
        val request = pending ?: return
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text == null) { status = "클립보드에 텍스트가 없습니다"; return }
        lifecycleScope.launch {
            runCatching { client().sendClipboard(request.id, text) }
                .onSuccess { status = "클립보드를 보냈습니다"; pending = null }
                .onFailure { status = "전송 실패: ${it.message}" }
        }
    }

    private fun reject() {
        val request = pending ?: return
        lifecycleScope.launch {
            runCatching { client().reject(request.id) }
            pending = null
            status = "요청을 거절했습니다"
        }
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
                            Text(status, style=MaterialTheme.typography.bodyMedium)
                            GlassField("Go 프록시 주소", server, { server = it }, Modifier.fillMaxWidth())
                            GlassField("연결 토큰", token, { token = it }, Modifier.fillMaxWidth(), secret=true)
                            GlassButton("저장하고 연결", ::saveConnection, accent=true)
                        }
                    }

                    pending?.let { request ->
                        GlassPanel(Modifier.fillMaxWidth(), GlassTone.Thick) {
                            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                Text(if (request.kind == "file") "파일 요청" else "클립보드 요청", style=MaterialTheme.typography.titleLarge)
                                Text(request.prompt.ifBlank { "코딩 도구가 휴대폰 자료를 요청했습니다." })
                                Text("공유하기 전 내용을 확인하세요.", style=MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    GlassButton(if (request.kind == "file") "파일 선택" else "클립보드 공유",
                                        if (request.kind == "file") ({ filePicker.launch(arrayOf("*/*")) }) else ::shareClipboard, accent=true)
                                    GlassButton("거절", ::reject)
                                }
                            }
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
                    Text("앱이 열려 있는 동안 요청을 받습니다. 파일은 최대 20 MiB입니다.", style=MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
