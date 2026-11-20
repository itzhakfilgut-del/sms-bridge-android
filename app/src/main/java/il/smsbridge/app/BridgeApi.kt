package il.smsbridge.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BridgeApi {
    private fun readJson(conn: HttpURLConnection, path: String): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val content = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        if (code !in 200..299) throw IllegalStateException("$path HTTP $code")
        return JSONObject(content)
    }

    fun post(base: String, path: String, data: JSONObject, token: String = ""): JSONObject {
        require(base.startsWith("https://") && !base.contains("@")) { "HTTPS address required" }
        val conn = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 9000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            return readJson(conn, path)
        } finally { conn.disconnect() }
    }

    fun get(ctx: Context, path: String): JSONObject {
        val base = BridgeStore.base(ctx)
        require(base.startsWith("https://") && !base.contains("@")) { "HTTPS address required" }
        val conn = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 9000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer ${BridgeStore.token(ctx)}")
            return readJson(conn, path)
        } finally { conn.disconnect() }
    }

    fun authPost(ctx: Context, path: String, data: JSONObject) =
        post(BridgeStore.base(ctx), path, data, BridgeStore.token(ctx))
}
