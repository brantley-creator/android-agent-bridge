package dev.brantley.agentbridge.pair

import dev.brantley.agentbridge.net.claimUrl
import dev.brantley.agentbridge.net.revokeUrl
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object Pairing {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val media = "application/json".toMediaType()

    suspend fun claim(relay: String, room: String, code: String, deviceName: String): String =
        withContext(Dispatchers.IO) {
            val payload =
                buildJsonObject {
                    put("room", room)
                    put("pairCode", code)
                    put("deviceName", deviceName.take(64))
                }
            val request =
                Request.Builder()
                    .url(claimUrl(relay))
                    .post(payload.toString().toRequestBody(media))
                    .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Claim was rejected.")
                val token = json.parseToJsonElement(text).jsonObject["deviceToken"]?.jsonPrimitive?.contentOrNull
                token?.takeIf { it.isNotBlank() } ?: error("Claim returned no device token.")
            }
        }

    suspend fun revoke(relay: String, room: String, token: String) {
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject { put("room", room) }
            val request =
                Request.Builder()
                    .url(revokeUrl(relay))
                    .header("Authorization", "Bearer $token")
                    .post(payload.toString().toRequestBody(media))
                    .build()
            runCatching { client.newCall(request).execute().close() }
        }
    }
}
