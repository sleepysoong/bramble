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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun repeatedStartKeepsTheRequestAwaitingUserChoice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit()
            .putString("server", "http://127.0.0.1:1")
            .putString("token", "0123456789abcdef0123456789abcdef")
            .putBoolean("enabled", true).commit()
        val controller = Robolectric.buildService(RelayService::class.java).create()
        try {
            val service = controller.get()
            val start = Intent(context, RelayService::class.java).setAction(RelayService.ACTION_START)
            service.onStartCommand(start, 0, 1)
            RelayState.pending = PhoneRequest("pending-id", "file", "선택 중", System.currentTimeMillis() + 60_000)
            service.onStartCommand(start, 0, 2)
            assertEquals("pending-id", RelayState.pending?.id)
        } finally { controller.destroy() }
    }

    @Test
    fun stoppingCancelsResponseAndPreventsLateStatusChanges() {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/device/next") { exchange ->
            val body = """{"id":"test-request","kind":"file","expires_at":"${Instant.now().plusSeconds(60)}"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/requests/test-request/reject") { exchange ->
            received.countDown()
            release.await(5, TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(204, -1) }
            exchange.close()
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
            assertNotNull(RelayState.pending)
            service.onStartCommand(Intent(context, RelayService::class.java).setAction(RelayService.ACTION_REJECT)
                .putExtra("request_id", "test-request"), 0, 2)
            assertTrue(received.await(3, TimeUnit.SECONDS))
            assertTrue(RelayState.busy)
            service.onStartCommand(Intent(context, RelayService::class.java).setAction(RelayService.ACTION_STOP), 0, 3)
            release.countDown()
            repeat(5) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
            assertEquals("백그라운드 수신 중지됨", RelayState.status)
            assertFalse(RelayState.busy)
            assertEquals(null, RelayState.pending)
        } finally {
            release.countDown()
            controller.destroy()
            server.stop(0)
        }
    }

    @Test
    fun cancelledHarnessRequestClearsNotificationAndResumesPolling() {
        val polls = AtomicInteger()
        val statusChecks = AtomicInteger()
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/device/next") { exchange ->
            if (polls.incrementAndGet() == 1) {
                val body = """{"id":"test-request","kind":"file","expires_at":"${Instant.now().plusSeconds(60)}"}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                release.await(5, TimeUnit.SECONDS)
                runCatching { exchange.sendResponseHeaders(204, -1) }
                exchange.close()
            }
        }
        server.createContext("/v1/requests/test-request/status") { exchange ->
            statusChecks.incrementAndGet()
            exchange.sendResponseHeaders(410, -1)
            exchange.close()
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit()
            .putString("server", "http://127.0.0.1:${server.address.port}")
            .putString("token", "0123456789abcdef0123456789abcdef")
            .putBoolean("enabled", true).commit()
        val controller = Robolectric.buildService(RelayService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(context, RelayService::class.java).setAction(RelayService.ACTION_START), 0, 1)
            val until = System.currentTimeMillis() + 6_000
            while ((statusChecks.get() == 0 || RelayState.pending != null || polls.get() < 2) && System.currentTimeMillis() < until) {
                shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
                Thread.sleep(20)
            }
            assertTrue(statusChecks.get() >= 1)
            assertTrue(polls.get() >= 2)
            assertEquals(null, RelayState.pending)
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 101 })
        } finally {
            controller.destroy()
            release.countDown()
            server.stop(0)
        }
    }
}
