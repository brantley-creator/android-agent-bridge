package dev.brantley.agentbridge.safety

import kotlinx.coroutines.withTimeoutOrNull

enum class ConfirmAnswer {
    APPROVE,
    DENY,
    APPROVE_5_MIN,
}

class ConfirmPrompter(
    private val ask: suspend (String) -> ConfirmAnswer?,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    suspend fun prompt(description: String): ConfirmAnswer? = withTimeoutOrNull(timeoutMs) { ask(description) }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
    }
}
