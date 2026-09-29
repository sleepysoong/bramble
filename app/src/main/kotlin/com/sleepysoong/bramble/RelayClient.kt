package com.sleepysoong.bramble

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PhoneRequest(val id: String, val kind: String, val prompt: String)

class RelayClient(private val baseUrl: String, private val token: String) {
    private fun open(path: String, method: String): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 5_000
            readTimeout = 32_000
            useCaches = false
        }

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        val conn = open("/v1/health", "GET")
        try { conn.responseCode == 200 } finally { conn.disconnect() }
    }

    suspend fun next(): PhoneRequest? = withContext(Dispatchers.IO) {
        val conn = open("/v1/device/next", "GET")
        try {
            when (conn.responseCode) {
                204 -> null
                200 -> JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).let {
                    PhoneRequest(it.getString("id"), it.getString("kind"), it.optString("prompt"))
                }
                else -> error("서버 응답: ${conn.responseCode}")
            }
        } finally { conn.disconnect() }
    }

    suspend fun sendClipboard(id: String, text: String) = withContext(Dispatchers.IO) {
        val conn = open("/v1/requests/$id/result", "POST")
        try {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 204) error("서버 응답: ${conn.responseCode}")
        } finally { conn.disconnect() }
    }

    suspend fun reject(id: String) = withContext(Dispatchers.IO) {
        val conn = open("/v1/requests/$id/reject", "POST")
        try { conn.doOutput = true; if (conn.responseCode != 204) error("서버 응답: ${conn.responseCode}") }
        finally { conn.disconnect() }
    }

    suspend fun upload(id: String, uri: Uri, resolver: ContentResolver) = withContext(Dispatchers.IO) {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "phone-file"
        val conn = open("/v1/requests/$id/file", "PUT")
        try {
            conn.setRequestProperty("X-Filename", name.filter { it.code in 32..126 }.take(120).ifEmpty { "phone-file" })
            conn.setRequestProperty("Content-Type", resolver.getType(uri) ?: "application/octet-stream")
            conn.doOutput = true
            conn.setChunkedStreamingMode(64 * 1024)
            resolver.openInputStream(uri)?.use { input -> conn.outputStream.use { output -> input.copyTo(output) } }
                ?: error("선택한 파일을 열 수 없습니다")
            if (conn.responseCode != 200) error("서버 응답: ${conn.responseCode}")
        } finally { conn.disconnect() }
    }
}
