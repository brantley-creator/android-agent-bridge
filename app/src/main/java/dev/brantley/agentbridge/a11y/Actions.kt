package dev.brantley.agentbridge.a11y

import dev.brantley.agentbridge.safety.Policy
import dev.brantley.agentbridge.safety.SafetyConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class ActionResult {
    OK,
    STALE,
    FAILED,
}

sealed class ScreenshotGrab {
    data class Png(val bytes: ByteArray) : ScreenshotGrab()

    data object Secure : ScreenshotGrab()

    data object Failed : ScreenshotGrab()
}

data class ListedApp(
    val packageName: String,
    val label: String,
)

interface DevicePort {
    fun a11yEnabled(): Boolean

    fun currentScene(): Scene

    suspend fun tapRef(path: List<Int>, identity: NodeIdentity, long: Boolean): ActionResult

    suspend fun tapAt(x: Int, y: Int, long: Boolean): Boolean

    fun typeText(
        path: List<Int>,
        identity: NodeIdentity,
        text: String,
        clear: Boolean,
        submit: Boolean,
    ): ActionResult

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean

    suspend fun scroll(path: List<Int>?, identity: NodeIdentity?, direction: String): ActionResult

    fun key(name: String): Boolean

    fun launch(packageName: String): Boolean

    fun listApps(): List<ListedApp>

    fun screenshot(): ScreenshotGrab

    suspend fun settle()

    suspend fun delayMs(ms: Long)

    suspend fun waitForText(text: String, timeoutMs: Long): Boolean
}

fun describeAction(method: String, params: JsonObject, scene: Scene, label: String?): String {
    val where = scene.windowTitle.ifBlank { scene.packageName }.ifBlank { "the screen" }
    val name = label?.takeIf { it.isNotBlank() } ?: "the control"
    return when (method) {
        "tap" -> "Tap '$name' in $where"
        "type" -> "Type ${params.string("text")?.length ?: 0} characters into '$name' in $where"
        "swipe" -> "Swipe in $where"
        "scroll" -> "Scroll ${params.string("direction").orEmpty()} in $where".replace("  ", " ").trim()
        "key" -> "Press ${params.string("name") ?: "key"} in $where"
        "launch" -> "Launch ${params.string("package") ?: "an app"}"
        "snapshot" -> "Read the screen in $where"
        "screenshot" -> "Take a screenshot of $where"
        "wait" -> "Wait in $where"
        "list_apps" -> "List apps"
        else -> "Run $method in $where"
    }
}

fun labelOf(node: NodeView?): String? {
    if (node == null) return null
    if (node.password) return "password field"
    return node.text?.takeIf { it.isNotBlank() }
        ?: node.desc?.takeIf { it.isNotBlank() }
        ?: node.className.substringAfterLast('.').ifBlank { null }
}

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.boolOrNull(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

fun JsonObject.intOrNull(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toIntOrNull()
}

fun launchPackageOf(method: String, params: JsonObject): String? =
    if (method == "launch") params.string("package") else null

fun foregroundDenied(scene: Scene, config: SafetyConfig): Boolean = Policy.isDenied(scene.packageName, config)
