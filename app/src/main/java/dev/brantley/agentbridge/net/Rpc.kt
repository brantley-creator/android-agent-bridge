package dev.brantley.agentbridge.net

import dev.brantley.agentbridge.safety.Outcome
import dev.brantley.agentbridge.safety.PhoneCore
import dev.brantley.agentbridge.safety.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class Rpc(
    private val core: PhoneCore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(raw: String): String {
        val parsed =
            runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return encode("", fail("BAD_ARGS", "The frame is not a JSON object.", "Send {id, method, params}."))
        val id = (parsed["id"] as? JsonPrimitive)?.contentOrNull ?: ""
        if (id.isBlank()) {
            return encode(id, fail("BAD_ARGS", "The request needs an id.", "Send a string id."))
        }
        val method = (parsed["method"] as? JsonPrimitive)?.contentOrNull
        if (method.isNullOrBlank()) {
            return encode(id, fail("BAD_ARGS", "The request needs a method.", "Use a method from the protocol."))
        }
        val params =
            when (val value = parsed["params"]) {
                null -> JsonObject(emptyMap())
                is JsonObject -> value
                else -> return encode(id, fail("BAD_ARGS", "Params must be an object.", "Send params as a JSON object."))
            }
        return encode(id, core.handle(method, params))
    }

    private fun encode(id: String, outcome: Outcome): String =
        buildJsonObject {
            put("id", id)
            when (outcome) {
                is Outcome.Ok -> {
                    put("ok", true)
                    put("data", outcome.data)
                }
                is Outcome.Fail -> {
                    put("ok", false)
                    put(
                        "error",
                        buildJsonObject {
                            put("code", outcome.error.code)
                            put("message", outcome.error.message)
                            put("retry", outcome.error.retry)
                            put("suggestion", outcome.error.suggestion)
                        },
                    )
                }
            }
        }.toString()
}
