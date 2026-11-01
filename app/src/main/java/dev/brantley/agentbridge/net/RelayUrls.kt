package dev.brantley.agentbridge.net

fun httpOrigin(relay: String): String {
    val trimmed = relay.trim().trimEnd('/')
    return when {
        trimmed.startsWith("wss://") -> "https://" + trimmed.removePrefix("wss://")
        trimmed.startsWith("ws://") -> "http://" + trimmed.removePrefix("ws://")
        else -> trimmed
    }
}

fun deviceWebSocketUrl(relay: String, room: String): String {
    val trimmed = relay.trim().trimEnd('/')
    val base =
        when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> trimmed
        }
    return "$base/v1/room/$room/device"
}

fun claimUrl(relay: String): String = httpOrigin(relay) + "/v1/pair/claim"

fun revokeUrl(relay: String): String = httpOrigin(relay) + "/v1/revoke"

fun relayHost(relay: String): String =
    Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:?#]+)")
        .find(relay.trim())
        ?.groupValues
        ?.get(1)
        .orEmpty()
        .ifBlank { relay.trim() }
