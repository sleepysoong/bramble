package com.sleepysoong.bramble

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayServiceTest {
    @Test
    fun startsAsConnectedDeviceServiceAndStopsByUserAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit()
            .putString("server", "http://127.0.0.1:1")
            .putString("token", "0123456789abcdef0123456789abcdef")
            .putBoolean("enabled", true).commit()

        val controller = Robolectric.buildService(RelayService::class.java).create()
        val service = controller.get()
        val started = service.onStartCommand(Intent(context, RelayService::class.java).setAction(RelayService.ACTION_START), 0, 1)
        assertEquals(Service.START_STICKY, started)
        assertTrue(RelayState.running)
        assertTrue(RelayState.enabled)
        assertNotNull(context.getSystemService(NotificationManager::class.java).getNotificationChannel("connection"))

        val stopped = service.onStartCommand(Intent(context, RelayService::class.java).setAction(RelayService.ACTION_STOP), 0, 2)
        assertEquals(Service.START_NOT_STICKY, stopped)
        assertFalse(RelayState.enabled)
        assertFalse(context.getSharedPreferences("connection", Context.MODE_PRIVATE).getBoolean("enabled", true))
        controller.destroy()
    }

    @Test
    fun receivesRequestAndPostsNotificationWithoutAnActivity() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val deadline = Instant.now().plusSeconds(60)
        server.createContext("/v1/device/next") { exchange ->
            val body = """{"id":"test-request","kind":"file","prompt":"사진 선택","expires_at":"$deadline"}"""
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit()
            .putString("server", "http://127.0.0.1:${server.address.port}")
            .putString("token", "0123456789abcdef0123456789abcdef")
            .putBoolean("enabled", true).commit()
        val controller = Robolectric.buildService(RelayService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(context, RelayService::class.java).setAction(RelayService.ACTION_START), 0, 1)
            val until = System.currentTimeMillis() + 5_000
            while (RelayState.pending == null && System.currentTimeMillis() < until) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertEquals("test-request", RelayState.pending?.id)
            val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
            assertTrue(notifications.any { it.id == 101 })
        } finally {
            controller.destroy()
            server.stop(0)
        }
    }
}
