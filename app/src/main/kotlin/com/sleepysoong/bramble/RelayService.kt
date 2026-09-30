package com.sleepysoong.bramble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Shared process state; the service owns every transition, including expiry. */
object RelayState {
    var enabled by mutableStateOf(false)
    var running by mutableStateOf(false)
    var status by mutableStateOf("연결 정보를 입력하세요")
    var pending by mutableStateOf<PhoneRequest?>(null)
    var busy by mutableStateOf(false)
    var outboundClipboard: Pair<String, String>? = null
}

class RelayService : Service() {
    companion object {
        const val ACTION_START = "com.sleepysoong.bramble.START"
        const val ACTION_STOP = "com.sleepysoong.bramble.STOP"
        const val ACTION_CLIPBOARD = "com.sleepysoong.bramble.CLIPBOARD"
        const val ACTION_FILE = "com.sleepysoong.bramble.FILE"
        const val ACTION_REJECT = "com.sleepysoong.bramble.REJECT"
        private const val EXTRA_ID = "request_id"
        private const val CHANNEL_CONNECTION = "connection"
        private const val CHANNEL_REQUEST = "requests"
        private const val CONNECTION_ID = 100
        private const val REQUEST_ID = 101

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RelayService::class.java).setAction(ACTION_START))
        }
        fun stop(context: Context) {
            context.startService(Intent(context, RelayService::class.java).setAction(ACTION_STOP))
        }
        fun clipboard(context: Context, id: String, text: String) {
            // Large clipboard values must not cross Android's Binder transaction limit.
            RelayState.outboundClipboard = id to text
            context.startService(Intent(context, RelayService::class.java).setAction(ACTION_CLIPBOARD)
                .putExtra(EXTRA_ID, id))
        }
        fun file(context: Context, id: String, uri: android.net.Uri) {
            context.startService(Intent(context, RelayService::class.java).setAction(ACTION_FILE)
                .putExtra(EXTRA_ID, id).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        fun reject(context: Context, id: String) {
            context.startService(Intent(context, RelayService::class.java).setAction(ACTION_REJECT)
                .putExtra(EXTRA_ID, id))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private var responseJob: Job? = null
    private var connection: Pair<String, String>? = null
    private var generation = 0
    private val prefs by lazy { getSharedPreferences("connection", MODE_PRIVATE) }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL_CONNECTION, "Bramble 연결", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_REQUEST, "휴대폰 요청", NotificationManager.IMPORTANCE_HIGH))
        RelayState.running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !prefs.getBoolean("enabled", false)) {
            generation++
            responseJob?.cancel()
            responseJob = null
            prefs.edit().putBoolean("enabled", false).apply()
            RelayState.enabled = false
            RelayState.pending = null
            RelayState.busy = false
            RelayState.outboundClipboard = null
            RelayState.status = "백그라운드 수신 중지됨"
            manager.cancel(REQUEST_ID)
            pollJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Must run before any network operation, including after a sticky restart or boot.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(CONNECTION_ID, connectionNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(CONNECTION_ID, connectionNotification())
        RelayState.enabled = true
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> restartPolling()
            ACTION_CLIPBOARD, ACTION_FILE, ACTION_REJECT -> handleResponse(intent!!)
        }
        return START_STICKY
    }

    private fun restartPolling() {
        val base = prefs.getString("server", null)
        val token = prefs.getString("token", null)
        if (base.isNullOrBlank() || token.isNullOrBlank() || !RelayClient.validServer(base) || !RelayClient.validToken(token)) {
            RelayState.status = "연결 정보를 확인하세요"
            prefs.edit().putBoolean("enabled", false).apply()
            RelayState.enabled = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (connection == (base to token) && pollJob?.isActive == true) return
        generation++
        pollJob?.cancel()
        responseJob?.cancel()
        responseJob = null
        RelayState.pending = null
        RelayState.busy = false
        RelayState.outboundClipboard = null
        manager.cancel(REQUEST_ID)
        connection = base to token
        val client = RelayClient(base, token)
        pollJob = scope.launch {
            var checkAt = 0L
            while (isActive && prefs.getBoolean("enabled", false)) {
                val active = RelayState.pending
                if (active != null) {
                    val now = System.currentTimeMillis()
                    if (now >= active.expiresAtMillis) clearPending("요청이 만료됐습니다. 다시 대기합니다")
                    else {
                        if (now >= checkAt) {
                            checkAt = now + 3_000
                            try {
                                val pending = withTimeoutOrNull(minOf(5_000L, active.expiresAtMillis - now).coerceAtLeast(1L)) {
                                    client.isPending(active.id)
                                }
                                coroutineContext.ensureActive()
                                if (pending == false && RelayState.pending?.id == active.id) clearPending("코딩 도구에서 요청을 종료했습니다")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (RelayState.pending?.id == active.id && !RelayState.busy) {
                                    RelayState.status = "요청 상태 확인 실패: ${e.message}"
                                }
                            }
                        }
                        delay(500)
                    }
                    continue
                }
                try {
                    RelayState.status = "백그라운드에서 요청 대기 중"
                    val request = client.next()
                    coroutineContext.ensureActive()
                    if (request != null && request.expiresAtMillis > System.currentTimeMillis()) {
                        RelayState.pending = request
                        checkAt = System.currentTimeMillis() + 3_000
                        RelayState.status = "휴대폰에서 요청을 확인해 주세요"
                        manager.notify(REQUEST_ID, requestNotification(request))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RelayState.status = "연결 오류: ${e.message}"
                    delay(3000)
                }
            }
        }
    }

    private fun clearPending(message: String) {
        val response = responseJob
        responseJob = null
        response?.cancel()
        RelayState.pending = null
        RelayState.busy = false
        RelayState.outboundClipboard = null
        RelayState.status = message
        manager.cancel(REQUEST_ID)
    }

    private fun handleResponse(intent: Intent) {
        // Remove the one-shot clipboard even if its activity callback became stale.
        val outbound = RelayState.outboundClipboard
        RelayState.outboundClipboard = null
        val active = RelayState.pending ?: return
        if (active.id != intent.getStringExtra(EXTRA_ID) || RelayState.busy || active.expiresAtMillis <= System.currentTimeMillis()) return
        if ((intent.action == ACTION_CLIPBOARD && active.kind != "clipboard") ||
            (intent.action == ACTION_FILE && active.kind != "file")) return
        val base = prefs.getString("server", null) ?: return
        val token = prefs.getString("token", null) ?: return
        val clipboardText = if (intent.action == ACTION_CLIPBOARD) {
            outbound?.takeIf { it.first == active.id }?.second ?: return
        } else null
        val session = generation
        RelayState.busy = true
        responseJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val client = RelayClient(base, token)
                when (intent.action) {
                    ACTION_CLIPBOARD -> client.sendClipboard(active.id, clipboardText ?: "")
                    ACTION_FILE -> client.upload(active.id, intent.data ?: error("선택한 파일이 없습니다"), contentResolver)
                    ACTION_REJECT -> client.reject(active.id)
                }
                coroutineContext.ensureActive()
                if (generation == session && RelayState.pending?.id == active.id) {
                    RelayState.pending = null
                    RelayState.status = if (intent.action == ACTION_REJECT) "요청을 거절했습니다" else "자료를 보냈습니다"
                    manager.cancel(REQUEST_ID)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayRequestClosedException) {
                if (generation == session && RelayState.pending?.id == active.id) clearPending(e.message.orEmpty())
            } catch (e: Exception) {
                if (generation == session && RelayState.pending?.id == active.id) {
                    RelayState.status = "전송 실패: ${e.message}"
                }
            } finally {
                if (responseJob === coroutineContext[Job]) {
                    responseJob = null
                    RelayState.busy = false
                }
            }
        }
        responseJob?.start()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun connectionNotification(): Notification {
        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_bramble)
            .setContentTitle("Bramble 연결 유지 중")
            .setContentText("코딩 도구의 휴대폰 요청을 기다립니다")
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null, "중지", stopIntent).build())
            .setOngoing(true)
            .build()
    }

    private fun requestNotification(request: PhoneRequest): Notification =
        Notification.Builder(this, CHANNEL_REQUEST)
            .setSmallIcon(R.drawable.ic_stat_bramble)
            .setContentTitle(if (request.kind == "file") "파일 선택 요청" else "클립보드 요청")
            .setContentText(request.prompt.ifBlank { "Bramble을 열어 요청을 확인하세요" })
            .setStyle(Notification.BigTextStyle().bigText(request.prompt.ifBlank { "Bramble을 열어 요청을 확인하세요" }))
            .setContentIntent(openAppIntent())
            .setAutoCancel(false)
            .setTimeoutAfter((request.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(1_000L))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()

    override fun onDestroy() {
        generation++
        scope.cancel()
        manager.cancel(REQUEST_ID)
        RelayState.running = false
        RelayState.pending = null
        RelayState.busy = false
        RelayState.outboundClipboard = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** User-enabled connections recover after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!context.getSharedPreferences("connection", Context.MODE_PRIVATE).getBoolean("enabled", false)) return
        try {
            RelayService.start(context)
        } catch (e: RuntimeException) {
            Log.w("Bramble", "Could not restart relay after boot", e)
        }
    }
}
