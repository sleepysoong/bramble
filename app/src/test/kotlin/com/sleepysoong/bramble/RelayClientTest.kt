package com.sleepysoong.bramble

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayClientTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun rejectsUnsafeConnectionAndTokenValues() {
        assertTrue(RelayClient.validServer(" https://example.com:8787/ "))
        listOf("file:///tmp/server", "http://user@example.com", "http://example.com/path", "http://example.com:99999", "http://example.com?token=x")
            .forEach { assertFalse(it, RelayClient.validServer(it)) }
        assertFalse(RelayClient.validToken(token + "\r\nInjected: yes"))
        assertFalse(RelayClient.validToken("x".repeat(31)))
        assertTrue(RelayClient.validToken(token))
    }

    @Test
    fun refusesRedirectsWithoutSendingTokenToAnotherServer() = runBlocking {
        val reached = AtomicInteger()
        val destination = server("/stolen") { exchange ->
            reached.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val source = server("/v1/device/next") { exchange ->
            exchange.responseHeaders.add("Location", "${base(destination)}/stolen")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        try {
            val failure = runCatching { RelayClient(base(source), token).next() }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("302"))
            assertEquals(0, reached.get())
        } finally { source.stop(0); destination.stop(0) }
    }

    @Test
    fun rejectsUnexpectedKindPathAndMalformedDeadline() = runBlocking {
        val bodies = listOf(
            """{"id":"test-id","kind":"unknown"}""",
            """{"id":"../other","kind":"clipboard"}""",
            """{"id":"test-id","kind":"clipboard","expires_at":"invalid"}"""
        )
        for (body in bodies) {
            val source = server("/v1/device/next") { exchange ->
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            try { assertTrue(body, runCatching { RelayClient(base(source), token).next() }.isFailure) }
            finally { source.stop(0) }
        }
    }

    @Test
    fun cancellationDoesNotWaitForTheLongPollTimeout() = runBlocking {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = server("/v1/device/next") { exchange ->
            received.countDown()
            release.await(5, TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(204, -1) }
            exchange.close()
        }
        try {
            val request = launch(kotlinx.coroutines.Dispatchers.IO) { RelayClient(base(source), token).next() }
            assertTrue(received.await(3, TimeUnit.SECONDS))
            withTimeout(1_000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
        } finally { release.countDown(); source.stop(0) }
    }

    @Test
    fun keepsUnicodeFileNameAndRefusesOversizedFileBeforeNetwork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("bramble-upload", ".txt", context.cacheDir).apply { writeText("contents") }
        val provider = UploadProvider(file, file.length())
        provider.attachInfo(context, ProviderInfo().apply { authority = "bramble.test" })
        ShadowContentResolver.registerProviderInternal("bramble.test", provider)
        val requests = AtomicInteger()
        var encodedName = ""
        val source = server("/v1/requests/test-id/file") { exchange ->
            encodedName = exchange.requestHeaders.getFirst("X-Filename-Encoded")
            assertEquals("contents", exchange.requestBody.bufferedReader().readText())
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        try {
            val client = RelayClient(base(source), token)
            val uri = Uri.parse("content://bramble.test/document")
            client.upload("test-id", uri, context.contentResolver)
            assertEquals("한글 파일.txt", URLDecoder.decode(encodedName, "UTF-8"))
            provider.name = "가".repeat(80) + ".txt"
            client.upload("test-id", uri, context.contentResolver)
            assertEquals("가".repeat(60), URLDecoder.decode(encodedName, "UTF-8"))
            provider.size = RelayClient.MAX_FILE_BYTES.toLong() + 1
            val failure = runCatching { client.upload("test-id", uri, context.contentResolver) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("20 MiB"))
            assertEquals(2, requests.get())
        } finally { source.stop(0); file.delete() }
    }

    @Test
    fun capsUploadEvenWhenProviderDoesNotReportFileSize() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("bramble-large", ".bin", context.cacheDir)
        RandomAccessFile(file, "rw").use { it.setLength(RelayClient.MAX_FILE_BYTES.toLong() + 1) }
        val provider = UploadProvider(file, null)
        provider.attachInfo(context, ProviderInfo().apply { authority = "bramble.large" })
        ShadowContentResolver.registerProviderInternal("bramble.large", provider)
        val received = AtomicInteger()
        val finished = CountDownLatch(1)
        val source = server("/v1/requests/test-id/file") { exchange ->
            val buffer = ByteArray(64 * 1024)
            runCatching {
                while (true) {
                    val count = exchange.requestBody.read(buffer)
                    if (count < 0) break
                    received.addAndGet(count)
                }
                exchange.sendResponseHeaders(200, -1)
            }
            exchange.close()
            finished.countDown()
        }
        try {
            val failure = runCatching {
                RelayClient(base(source), token).upload("test-id", Uri.parse("content://bramble.large/document"), context.contentResolver)
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("20 MiB"))
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertTrue(received.get() <= RelayClient.MAX_FILE_BYTES)
        } finally { source.stop(0); file.delete() }
    }

    @Test
    fun refusesOversizedClipboardBeforeNetwork() = runBlocking {
        val failure = runCatching {
            RelayClient("http://127.0.0.1:1", token).sendClipboard("test-id", "가".repeat(RelayClient.MAX_CLIPBOARD_BYTES / 3 + 1))
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("1 MiB"))
    }

    @Test
    fun detectsClosedRequestsAndTreatsLateResponsesAsClosed() = runBlocking {
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        source.createContext("/v1/requests/test-id/status") { exchange ->
            exchange.sendResponseHeaders(410, -1)
            exchange.close()
        }
        source.createContext("/v1/requests/test-id/reject") { exchange ->
            exchange.sendResponseHeaders(409, -1)
            exchange.close()
        }
        source.start()
        try {
            val client = RelayClient(base(source), token)
            assertFalse(client.isPending("test-id"))
            assertTrue(runCatching { client.reject("test-id") }.exceptionOrNull() is RelayRequestClosedException)
        } finally { source.stop(0) }
    }

    @Test
    fun keepsLegacyRequestWhenStatusEndpointIsMissing() = runBlocking {
        val source = server("/v1/requests/test-id/status") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        try { assertTrue(RelayClient(base(source), token).isPending("test-id")) }
        finally { source.stop(0) }
    }

    private fun server(path: String, handler: com.sun.net.httpserver.HttpHandler): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { createContext(path, handler); start() }
    private fun base(server: HttpServer) = "http://127.0.0.1:${server.address.port}"

    private class UploadProvider(private val file: File, var size: Long?) : ContentProvider() {
        var name = "한글 파일.txt"
        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(name, size)) }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
