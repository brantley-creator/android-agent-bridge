package dev.brantley.agentbridge.safety

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AuditEntry(
    val ts: Long,
    val method: String,
    @SerialName("package") val pkg: String,
    val target: String,
    val decision: String,
    val result: String,
    val typedLength: Int? = null,
)

class AuditLog(
    private val file: File? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val items = mutableListOf<AuditEntry>()

    val entries: List<AuditEntry>
        get() = synchronized(items) { items.toList() }

    fun record(
        ts: Long,
        method: String,
        pkg: String,
        target: String,
        decision: String,
        result: String,
        typedLength: Int? = null,
    ) {
        val entry = AuditEntry(ts, method, pkg, target, decision, result, typedLength)
        synchronized(items) {
            items += entry
            val targetFile = file ?: return
            targetFile.parentFile?.mkdirs()
            targetFile.appendText(json.encodeToString(AuditEntry.serializer(), entry) + "\n")
        }
    }

    fun load() {
        val targetFile = file ?: return
        if (!targetFile.exists()) return
        val loaded = mutableListOf<AuditEntry>()
        for (line in targetFile.readLines()) {
            if (line.isBlank()) continue
            runCatching { json.decodeFromString(AuditEntry.serializer(), line) }.getOrNull()?.let(loaded::add)
        }
        synchronized(items) {
            items.clear()
            items += loaded
        }
    }

    fun clear() {
        synchronized(items) {
            items.clear()
            file?.delete()
        }
    }
}
