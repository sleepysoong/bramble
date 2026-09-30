package com.sleepysoong.bramble

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PhoneRequest(val id: String, val kind: String, val prompt: String, val expiresAtMillis: Long)
class RelayRequestClosedException : IOException("요청이 종료됐습니다. 새 요청을 기다립니다")

class RelayClient(baseUrl: String, private val token: String) {
    companion object {
        const val MAX_FILE_BYTES = 20 * 1024 * 1024
        const val MAX_CLIPBOARD_BYTES = 1024 * 1024
        private const val MAX_REQUEST_BYTES = 64 * 1024
        private val requestId = Regex("[A-Za-z0-9_-]{1,128}")

        fun validServer(value: String): Boolean = runCatching { normalizedServer(value); true }.getOrDefault(false)
        fun validToken(value: String): Boolean = value.length in 32..4096 && value.all { it.code in 33..126 }

        private fun normalizedServer(value: String): String {
            val uri = URI(value.trim())
            require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "HTTP 또는 HTTPS 서버 주소를 입력하세요" }
            require(!uri.host.isNullOrEmpty() && uri.userInfo == null && uri.query == null && uri.fragment == null &&
                (uri.path.isNullOrEmpty() || uri.path == "/") && (uri.port == -1 || uri.port in 1..65535)) { "서버 주소와 포트를 확인하세요" }
            return uri.toASCIIString().trimEnd('/')
        }

        private fun checkedId(id: String): String {
            require(requestId.matches(id)) { "잘못된 요청 ID입니다" }
            return id
        }
    }

    private val baseUrl = normalizedServer(baseUrl)

    init { require(validToken(token)) { "공백 없는 32~4096자 ASCII 토큰을 입력하세요" } }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 5_000
            readTimeout = 32_000
            useCaches = false
            // The pairing token and phone content belong only to the configured server.
            instanceFollowRedirects = false
        }

    /** Disconnect blocking I/O when the service stops, changes connection, or a request expires. */
    private suspend fun <T> request(path: String, method: String, block: (HttpURLConnection, CoroutineContext) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val conn = open(path, method)
            continuation.invokeOnCancellation {
                // disconnect() can close a blocking stream; never perform it on the UI thread.
                Dispatchers.IO.dispatch(EmptyCoroutineContext) { conn.disconnect() }
            }
            Dispatchers.IO.dispatch(continuation.context) {
                try {
                    continuation.context.ensureActive()
                    continuation.resume(block(conn, continuation.context))
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                } finally {
                    conn.disconnect()
                }
            }
        }

    suspend fun health(): Boolean = request("/v1/health", "GET") { conn, _ -> conn.responseCode == 200 }

    suspend fun isPending(id: String): Boolean = request("/v1/requests/${checkedId(id)}/status", "GET") { conn, _ ->
        when (conn.responseCode) {
            204 -> true
            // Older relays have no status endpoint. Keep their request until its local deadline.
            404 -> true
            410 -> false
            else -> error("서버 응답: ${conn.responseCode}")
        }
    }

    suspend fun next(): PhoneRequest? = request("/v1/device/next", "GET") { conn, context ->
        when (conn.responseCode) {
            204 -> null
            200 -> {
                val body = conn.inputStream.use { input ->
                    val bytes = ByteArrayOutputStream()
                    copyLimited(input, bytes, MAX_REQUEST_BYTES, context, "서버 요청이 너무 큽니다")
                    bytes.toString(Charsets.UTF_8.name())
                }
                val json = JSONObject(body)
                val id = checkedId(json.getString("id"))
                val kind = json.getString("kind")
                require(kind == "file" || kind == "clipboard") { "지원하지 않는 요청 종류입니다" }
                val now = System.currentTimeMillis()
                val expiry = if (json.has("expires_at")) Instant.parse(json.getString("expires_at")).toEpochMilli()
                    else now + 120_000 // Compatibility with the initial relay release.
                PhoneRequest(id, kind, json.optString("prompt"), expiry.coerceAtMost(now + 120_000))
            }
            else -> error("서버 응답: ${conn.responseCode}")
        }
    }

    suspend fun sendClipboard(id: String, text: String) {
        checkedId(id)
        val bytes = withContext(Dispatchers.IO) {
            // Check characters first so an arbitrarily large clipboard cannot allocate another huge buffer.
            require(text.length <= MAX_CLIPBOARD_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_CLIPBOARD_BYTES) {
                "클립보드는 최대 1 MiB까지 보낼 수 있습니다"
            }
            JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)
        }
        request("/v1/requests/$id/result", "POST") { conn, context ->
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { output ->
                context.ensureActive()
                output.write(bytes)
            }
            requireResponse(conn, 204)
        }
    }

    suspend fun reject(id: String) = request("/v1/requests/${checkedId(id)}/reject", "POST") { conn, _ ->
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(0)
        requireResponse(conn, 204)
    }

    suspend fun upload(id: String, uri: Uri, resolver: ContentResolver) {
        checkedId(id)
        val metadata = withContext(Dispatchers.IO) {
            var name = "phone-file"
            var size: Long? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                }
            }
            require(size == null || size <= MAX_FILE_BYTES) { "파일은 최대 20 MiB까지 보낼 수 있습니다" }
            safeFileName(name) to resolver.getType(uri)
        }
        request("/v1/requests/$id/file", "PUT") { conn, context ->
            val (name, mime) = metadata
            conn.setRequestProperty("X-Filename", name.filter { it.code in 32..126 }.ifEmpty { "phone-file" })
            conn.setRequestProperty("X-Filename-Encoded", URLEncoder.encode(name, Charsets.UTF_8.name()).replace("+", "%20"))
            conn.setRequestProperty("Content-Type", mime?.takeIf { !it.any(Char::isISOControl) } ?: "application/octet-stream")
            conn.doOutput = true
            conn.setChunkedStreamingMode(64 * 1024)
            resolver.openInputStream(uri)?.use { input ->
                conn.outputStream.use { output -> copyLimited(input, output, MAX_FILE_BYTES, context, "파일은 최대 20 MiB까지 보낼 수 있습니다") }
            } ?: error("선택한 파일을 열 수 없습니다")
            requireResponse(conn, 200)
        }
    }

    private fun safeFileName(name: String): String {
        val result = StringBuilder()
        var bytes = 0
        val characters = name.codePoints().iterator()
        while (characters.hasNext()) {
            val codePoint = characters.nextInt()
            if (Character.isISOControl(codePoint)) continue
            val character = String(Character.toChars(codePoint))
            val size = character.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 180) break
            result.append(character)
            bytes += size
        }
        return result.toString().ifEmpty { "phone-file" }
    }

    private fun requireResponse(conn: HttpURLConnection, expected: Int) {
        when (val code = conn.responseCode) {
            expected -> Unit
            404, 409, 410 -> throw RelayRequestClosedException()
            else -> error("서버 응답: $code")
        }
    }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Int, context: CoroutineContext, message: String) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            context.ensureActive()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - copied + 1).toInt())
            if (count < 0) return
            copied += count
            require(copied <= limit) { message }
            output.write(buffer, 0, count)
        }
    }
}
