package dev.brantley.agentbridge.safety

import dev.brantley.agentbridge.a11y.ActionResult
import dev.brantley.agentbridge.a11y.DevicePort
import dev.brantley.agentbridge.a11y.NodeView
import dev.brantley.agentbridge.a11y.RefTable
import dev.brantley.agentbridge.a11y.ResolveResult
import dev.brantley.agentbridge.a11y.Scene
import dev.brantley.agentbridge.a11y.ScreenshotGrab
import dev.brantley.agentbridge.a11y.Snapshotter
import dev.brantley.agentbridge.a11y.boolOrNull
import dev.brantley.agentbridge.a11y.describeAction
import dev.brantley.agentbridge.a11y.intOrNull
import dev.brantley.agentbridge.a11y.identityOf
import dev.brantley.agentbridge.a11y.labelOf
import dev.brantley.agentbridge.a11y.string
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed class Outcome {
    data class Ok(val data: JsonObject) : Outcome()

    data class Fail(val error: ErrorBody) : Outcome()
}

data class ErrorBody(
    val code: String,
    val message: String,
    val retry: String,
    val suggestion: String,
)

class PhoneCore(
    private val port: DevicePort,
    private val audit: AuditLog,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val prompter: ConfirmPrompter,
) {
    val armed = MutableStateFlow(false)
    var config = SafetyConfig()
    private var sessionStart = 0L
    private var lastActivity = 0L
    private var grantUntil = 0L
    private val refs = RefTable()
    private var seq = 0

    fun arm(now: Long = clock()) {
        sessionStart = now
        lastActivity = now
        grantUntil = 0L
        armed.value = true
    }

    fun disarm() {
        armed.value = false
        grantUntil = 0L
    }

    fun isExpired(now: Long = clock()): Boolean {
        if (!armed.value) return false
        return now - lastActivity >= idleMs() || now - sessionStart >= sessionMs()
    }

    suspend fun handle(method: String, params: JsonObject): Outcome {
        val now = clock()
        val scene = if (port.a11yEnabled()) port.currentScene() else Scene("", "", NodeView("empty"))
        val typedLength = if (method == "type") params.string("text")?.length else null
        if (armed.value && isExpired(now)) {
            disarm()
            return finish(method, scene, "session", "expired", fail("SESSION_EXPIRED", "The session timed out.", "Arm Agent Bridge again on the phone."), typedLength)
        }
        if (!armed.value) {
            return finish(method, scene, method, "paused", fail("PAUSED", "Agent Bridge is disarmed.", "Arm it from the app on the phone."), typedLength)
        }
        lastActivity = now
        if (needsTree(method) && !port.a11yEnabled()) {
            return finish(method, scene, method, "denied", fail("A11Y_OFF", "The accessibility service is off.", "Turn on Agent Bridge in Accessibility settings."), typedLength)
        }
        val launchTarget = if (method == "launch") params.string("package") else null
        if (Policy.isDenied(scene.packageName, config) && method != "status") {
            return finish(method, scene, method, "denied", fail("DENIED_APP", "The foreground app is blocked.", "Leave that app before asking the agent to continue."), typedLength)
        }
        if (launchTarget != null && Policy.isDenied(launchTarget, config)) {
            return finish(method, scene, launchTarget, "denied", fail("DENIED_APP", "That app is blocked.", "Remove it from the denylist on the phone if you really want it launchable."), typedLength)
        }
        val resolved = resolveIfNeeded(method, params, scene)
        if (resolved is ResolveResult.Bad) {
            val suggestion =
                if (resolved.code == "BAD_ARGS") {
                    "Use a ref like @s1:e1 from the latest snapshot."
                } else {
                    "Take a new snapshot and use a ref from it."
                }
            val message = if (resolved.code == "BAD_ARGS") "That ref is not valid." else "That ref is no longer valid."
            return finish(method, scene, method, "denied", fail(resolved.code, message, suggestion), typedLength)
        }
        val hit = resolved as? ResolveResult.Hit
        if (method == "type" && targetIsPassword(params, scene, hit)) {
            return finish(method, scene, "password field", "denied", fail("PASSWORD_FIELD", "Refusing to type into a password field.", "Type passwords yourself."), typedLength)
        }
        if (method == "screenshot" && !config.screenshotsEnabled) {
            return finish(method, scene, "screenshot", "denied", fail("DENIED_BY_USER", "Screenshots are turned off on the phone.", "Enable screenshots in Agent Bridge settings."), typedLength)
        }
        val argsError = validate(method, params)
        if (argsError != null) {
            return finish(method, scene, method, "denied", argsError, typedLength)
        }
        val description = describeAction(method, params, scene, labelOf(hit?.node))
        if (Policy.needsConfirm(method, scene.packageName, launchTarget, config, grantUntil, now)) {
            val answer = prompter.prompt(description)
            if (!armed.value) {
                return finish(method, scene, description, "paused", fail("PAUSED", "Agent Bridge is disarmed.", "Arm it from the app on the phone."), typedLength)
            }
            when (answer) {
                null ->
                    return finish(
                        method,
                        scene,
                        description,
                        "denied",
                        fail("CONFIRM_TIMEOUT", "No answer within 20 seconds.", "Answer the prompt on the phone or try again."),
                        typedLength,
                    )
                ConfirmAnswer.DENY ->
                    return finish(method, scene, description, "denied", fail("DENIED_BY_USER", "You denied this action.", "Approve it on the phone if you want it to run."), typedLength)
                ConfirmAnswer.APPROVE_5_MIN -> grantUntil = clock() + 5 * 60_000
                ConfirmAnswer.APPROVE -> Unit
            }
        }
        if (!armed.value) {
            return finish(method, scene, description, "paused", fail("PAUSED", "Agent Bridge is disarmed.", "Arm it from the app on the phone."), typedLength)
        }
        val outcome = execute(method, params, scene, hit)
        return finish(method, scene, description, "allowed", outcome, typedLength)
    }

    private suspend fun execute(
        method: String,
        params: JsonObject,
        scene: Scene,
        hit: ResolveResult.Hit?,
    ): Outcome =
        when (method) {
            "status" -> Outcome.Ok(statusData(scene))
            "snapshot" -> snapshot(params, scene)
            "tap" -> tap(params, hit)
            "type" -> type(params, hit)
            "swipe" -> swipe(params)
            "scroll" -> scroll(params, hit)
            "key" -> key(params)
            "launch" -> launch(params)
            "list_apps" -> listApps()
            "wait" -> wait(params)
            "screenshot" -> screenshot()
            "volume" -> volume(params)
            else -> fail("UNSUPPORTED", "Unknown method.", "Use a method from the protocol.")
        }

    private fun snapshot(params: JsonObject, scene: Scene): Outcome {
        val interactive = params.boolOrNull("interactiveOnly") ?: if ("interactiveOnly" in params) {
            return fail("BAD_ARGS", "interactiveOnly must be a boolean.", "Pass true or false.")
        } else {
            true
        }
        val maxNodes = if ("maxNodes" in params) {
            params.intOrNull("maxNodes") ?: return fail("BAD_ARGS", "maxNodes must be an integer.", "Use a number from 1 to 2000.")
        } else {
            300
        }
        if (maxNodes !in 1..2000) return fail("BAD_ARGS", "maxNodes is out of range.", "Use a number from 1 to 2000.")
        var root = scene.root
        var prefix = emptyList<Int>()
        val rootRef = params.string("rootRef")
        if (rootRef != null) {
            when (val resolved = refs.resolve(rootRef, scene.root)) {
                is ResolveResult.Bad ->
                    return fail(
                        resolved.code,
                        if (resolved.code == "BAD_ARGS") "That ref is not valid." else "That ref is no longer valid.",
                        "Take a new snapshot and use a ref from it.",
                    )
                is ResolveResult.Hit -> {
                    root = resolved.node
                    prefix = resolved.path
                }
            }
        }
        val id = "s${++seq}"
        val captured = Snapshotter.capture(root, id, interactive, maxNodes, prefix)
        refs.remember(id, captured.assignments)
        return Outcome.Ok(
            buildJsonObject {
                put("snapshot_id", id)
                put("package", scene.packageName)
                put("window_title", scene.windowTitle)
                put("ref_count", captured.refCount)
                put("complete", captured.complete)
                put("tree", captured.tree)
            },
        )
    }

    private suspend fun tap(params: JsonObject, hit: ResolveResult.Hit?): Outcome {
        val long = params.boolOrNull("long") ?: false
        if (hit != null) {
            return when (port.tapRef(hit.path, hit.identity, long)) {
                ActionResult.OK -> observed("tap")
                ActionResult.STALE -> fail("STALE_REF", "That ref is no longer valid.", "Take a new snapshot and use a ref from it.")
                ActionResult.FAILED -> fail("UNSUPPORTED", "The phone could not tap that node.", "Take a new snapshot and try a different ref.")
            }
        }
        val x = params.intOrNull("x") ?: return fail("BAD_ARGS", "Tap needs a ref or x and y.", "Pass a ref from the snapshot.")
        val y = params.intOrNull("y") ?: return fail("BAD_ARGS", "Tap needs a ref or x and y.", "Pass a ref from the snapshot.")
        if (!port.tapAt(x, y, long)) return fail("UNSUPPORTED", "The phone could not tap there.", "Try a ref from a fresh snapshot.")
        return observed("tap")
    }

    private suspend fun type(params: JsonObject, hit: ResolveResult.Hit?): Outcome {
        val text = params.string("text") ?: return fail("BAD_ARGS", "Type needs text.", "Pass a text string.")
        val clear = params.boolOrNull("clear") ?: false
        val submit = params.boolOrNull("submit") ?: false
        if (hit == null) {
            val focused = findFocusedEditable(port.currentScene().root, emptyList())
            if (focused == null) return fail("NOT_FOUND", "No editable field is focused.", "Pass a ref or focus a field.")
            if (focused.second.password) {
                return fail("PASSWORD_FIELD", "Refusing to type into a password field.", "Type passwords yourself.")
            }
            return when (port.typeText(focused.first, identityOf(focused.second), text, clear, submit)) {
                ActionResult.OK -> observed("type")
                ActionResult.STALE -> fail("STALE_REF", "That field changed.", "Take a new snapshot.")
                ActionResult.FAILED -> fail("UNSUPPORTED", "The phone could not type into that field.", "Try a ref from a fresh snapshot.")
            }
        }
        return when (port.typeText(hit.path, hit.identity, text, clear, submit)) {
            ActionResult.OK -> observed("type")
            ActionResult.STALE -> fail("STALE_REF", "That ref is no longer valid.", "Take a new snapshot and use a ref from it.")
            ActionResult.FAILED -> fail("UNSUPPORTED", "The phone could not type into that field.", "Try a ref from a fresh snapshot.")
        }
    }

    private suspend fun swipe(params: JsonObject): Outcome {
        val x1 = params.intOrNull("x1") ?: return fail("BAD_ARGS", "Swipe needs x1, y1, x2, y2.", "Pass four integer coordinates.")
        val y1 = params.intOrNull("y1") ?: return fail("BAD_ARGS", "Swipe needs x1, y1, x2, y2.", "Pass four integer coordinates.")
        val x2 = params.intOrNull("x2") ?: return fail("BAD_ARGS", "Swipe needs x1, y1, x2, y2.", "Pass four integer coordinates.")
        val y2 = params.intOrNull("y2") ?: return fail("BAD_ARGS", "Swipe needs x1, y1, x2, y2.", "Pass four integer coordinates.")
        val duration = if ("durationMs" in params) params.intOrNull("durationMs") ?: return fail("BAD_ARGS", "durationMs must be an integer.", "Use 1 to 5000.") else 300
        if (duration !in 1..5000) return fail("BAD_ARGS", "durationMs is out of range.", "Use 1 to 5000.")
        if (!port.swipe(x1, y1, x2, y2, duration.toLong())) {
            return fail("UNSUPPORTED", "The phone could not swipe.", "Try again after a snapshot.")
        }
        return observed("swipe")
    }

    private suspend fun scroll(params: JsonObject, hit: ResolveResult.Hit?): Outcome {
        val direction = params.string("direction") ?: return fail("BAD_ARGS", "Scroll needs a direction.", "Use up, down, left, or right.")
        if (direction !in DIRECTIONS) return fail("BAD_ARGS", "Unknown scroll direction.", "Use up, down, left, or right.")
        return when (port.scroll(hit?.path, hit?.identity, direction)) {
            ActionResult.OK -> observed("scroll")
            ActionResult.STALE -> fail("STALE_REF", "That ref is no longer valid.", "Take a new snapshot and use a ref from it.")
            ActionResult.FAILED -> fail("UNSUPPORTED", "The phone could not scroll.", "Swipe instead, or snapshot a scrollable node.")
        }
    }

    private suspend fun key(params: JsonObject): Outcome {
        val name = params.string("name") ?: return fail("BAD_ARGS", "Key needs a name.", "Use back, home, recents, or notifications.")
        if (name !in KEYS) return fail("BAD_ARGS", "Unknown key.", "Use back, home, recents, or notifications.")
        if (!port.key(name)) return fail("UNSUPPORTED", "The phone could not press that key.", "Try again.")
        return observed("key")
    }

    private suspend fun launch(params: JsonObject): Outcome {
        val packageName = params.string("package") ?: return fail("BAD_ARGS", "Launch needs a package name.", "Pass a launcher package.")
        if (!PACKAGE.matches(packageName)) return fail("BAD_ARGS", "That package name is not valid.", "Use a Java package name.")
        if (!port.launch(packageName)) return fail("NOT_FOUND", "No launcher activity for that package.", "Check list_apps.")
        return observed("launch")
    }

    private fun listApps(): Outcome {
        val apps = port.listApps()
        return Outcome.Ok(
            buildJsonObject {
                put(
                    "apps",
                    buildJsonArray {
                        for (app in apps) {
                            add(
                                buildJsonObject {
                                    put("package", app.packageName)
                                    put("label", app.label)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private suspend fun wait(params: JsonObject): Outcome {
        val hasMs = "ms" in params
        val hasText = "forText" in params
        if (hasMs == hasText) return fail("BAD_ARGS", "Wait needs ms or forText, not both.", "Pass one of them.")
        if (hasMs) {
            val ms = params.intOrNull("ms") ?: return fail("BAD_ARGS", "ms must be an integer.", "Use 0 to 15000.")
            if (ms !in 0..15_000) return fail("BAD_ARGS", "ms is out of range.", "Use 0 to 15000.")
            port.delayMs(ms.toLong())
            return observed("wait")
        }
        val text = params.string("forText") ?: return fail("BAD_ARGS", "forText must be a string.", "Pass the text to wait for.")
        val timeout = params.intOrNull("timeoutMs") ?: return fail("BAD_ARGS", "forText needs timeoutMs.", "Pass timeoutMs up to 15000.")
        if (timeout !in 0..15_000) return fail("BAD_ARGS", "timeoutMs is out of range.", "Use 0 to 15000.")
        if (!port.a11yEnabled()) return fail("A11Y_OFF", "The accessibility service is off.", "Turn on Agent Bridge in Accessibility settings.")
        if (!port.waitForText(text, timeout.toLong())) {
            return fail("TIMEOUT", "The text did not appear.", "Take a snapshot and try a different string.")
        }
        return observed("wait")
    }

    private suspend fun volume(params: JsonObject): Outcome {
        val percent = params.intOrNull("percent") ?: return fail("BAD_ARGS", "Volume needs a percent.", "Pass an integer from 0 to 100.")
        if (percent !in 0..100) return fail("BAD_ARGS", "Volume percent is out of range.", "Pass an integer from 0 to 100.")
        val level = port.setMediaVolume(percent) ?: return fail("UNSUPPORTED", "The phone could not set media volume.", "Try again in a moment.")
        port.settle()
        val scene = if (port.a11yEnabled()) port.currentScene() else Scene("", "", NodeView("empty"))
        return Outcome.Ok(
            buildJsonObject {
                put("performed", "volume")
                put("stream", "music")
                put("index", level.index)
                put("max", level.max)
                put("percent", level.percent)
                put("observation", observation(scene))
            },
        )
    }

    private fun screenshot(): Outcome =
        when (val shot = port.screenshot()) {
            is ScreenshotGrab.Png ->
                Outcome.Ok(
                    buildJsonObject {
                        put("mime", "image/png")
                        put("base64", Base64.getEncoder().encodeToString(shot.bytes))
                    },
                )
            ScreenshotGrab.Secure -> fail("SECURE_SCREEN", "This screen blocks screenshots.", "Leave the secure screen.")
            ScreenshotGrab.Failed -> fail("UNSUPPORTED", "The phone could not take a screenshot.", "Try again in a moment.")
        }

    private suspend fun observed(performed: String): Outcome {
        port.settle()
        val scene = if (port.a11yEnabled()) port.currentScene() else Scene("", "", NodeView("empty"))
        return Outcome.Ok(
            buildJsonObject {
                put("performed", performed)
                put("observation", observation(scene))
            },
        )
    }

    private fun observation(scene: Scene) =
        buildJsonObject {
            put("package", scene.packageName)
            put("window_title", scene.windowTitle)
            put("summary", Snapshotter.summary(scene.root, refs.latestRefs()))
        }

    private fun statusData(scene: Scene) =
        buildJsonObject {
            put("armed", armed.value)
            put("a11y", port.a11yEnabled())
            put("confirm_mode", config.confirmMode.wire)
            put("screenshots", config.screenshotsEnabled)
            put("package", scene.packageName)
            put("window_title", scene.windowTitle)
            put("idle_timeout_min", config.idleTimeoutMin.coerceIn(1, 60))
            put("session_remaining_ms", (sessionStart + sessionMs() - clock()).coerceAtLeast(0))
        }

    private fun resolveIfNeeded(method: String, params: JsonObject, scene: Scene): ResolveResult? {
        val ref = params.string("ref") ?: return null
        if (method !in setOf("tap", "type", "scroll", "snapshot")) return null
        if (!port.a11yEnabled()) return null
        return refs.resolve(ref, scene.root)
    }

    private fun targetIsPassword(params: JsonObject, scene: Scene, hit: ResolveResult.Hit?): Boolean {
        if (hit?.node?.password == true) return true
        if (params.string("ref") != null) return false
        return findFocusedEditable(scene.root, emptyList())?.second?.password == true
    }

    private fun validate(method: String, params: JsonObject): Outcome.Fail? {
        if (method == "tap") {
            val hasRef = params.string("ref") != null
            val hasPoint = "x" in params || "y" in params
            if (hasRef && hasPoint) return fail("BAD_ARGS", "Pass a ref or x and y, not both.", "Pick one.")
            if (!hasRef && !hasPoint) return fail("BAD_ARGS", "Tap needs a ref or x and y.", "Pass a ref from the snapshot.")
            if ("long" in params && params.boolOrNull("long") == null) return fail("BAD_ARGS", "long must be a boolean.", "Pass true or false.")
        }
        if (method == "type" && "text" !in params) return fail("BAD_ARGS", "Type needs text.", "Pass a text string.")
        if (method == "scroll") {
            val direction = params.string("direction") ?: return fail("BAD_ARGS", "Scroll needs a direction.", "Use up, down, left, or right.")
            if (direction !in DIRECTIONS) return fail("BAD_ARGS", "Unknown scroll direction.", "Use up, down, left, or right.")
        }
        if (method == "key") {
            val name = params.string("name") ?: return fail("BAD_ARGS", "Key needs a name.", "Use back, home, recents, or notifications.")
            if (name !in KEYS) return fail("BAD_ARGS", "Unknown key.", "Use back, home, recents, or notifications.")
        }
        if (method == "launch") {
            val packageName = params.string("package") ?: return fail("BAD_ARGS", "Launch needs a package name.", "Pass a launcher package.")
            if (!PACKAGE.matches(packageName)) return fail("BAD_ARGS", "That package name is not valid.", "Use a Java package name.")
        }
        if (method == "volume") {
            val percent = params.intOrNull("percent") ?: return fail("BAD_ARGS", "Volume needs a percent.", "Pass an integer from 0 to 100.")
            if (percent !in 0..100) return fail("BAD_ARGS", "Volume percent is out of range.", "Pass an integer from 0 to 100.")
        }
        return null
    }

    private fun finish(
        method: String,
        scene: Scene,
        target: String,
        decision: String,
        outcome: Outcome,
        typedLength: Int?,
    ): Outcome {
        val result = when (outcome) { is Outcome.Ok -> "OK"; is Outcome.Fail -> outcome.error.code }
        audit.record(clock(), method, scene.packageName, target, decision, result, typedLength)
        return outcome
    }

    private fun idleMs(): Long = config.idleTimeoutMin.coerceIn(1, 60) * 60_000L

    private fun sessionMs(): Long = config.sessionTimeoutMin.coerceIn(5, 240) * 60_000L

    private fun needsTree(method: String): Boolean =
        method in setOf("snapshot", "tap", "type", "swipe", "scroll", "key", "screenshot") || method == "wait" && false

    companion object {
        private val DIRECTIONS = setOf("up", "down", "left", "right")
        private val KEYS = setOf("back", "home", "recents", "notifications")
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}

private fun findFocusedEditable(node: NodeView, path: List<Int>): Pair<List<Int>, NodeView>? {
    if (node.editable && node.focused) return path to node
    node.children.forEachIndexed { index, child ->
        val found = findFocusedEditable(child, path + index)
        if (found != null) return found
    }
    return null
}

fun fail(code: String, message: String, suggestion: String): Outcome.Fail {
    val retry =
        when (code) {
            "STALE_REF", "NOT_FOUND", "TIMEOUT" -> "safe"
            "CONFIRM_TIMEOUT", "A11Y_OFF" -> "unsafe"
            else -> "never"
        }
    return Outcome.Fail(ErrorBody(code, message, retry, suggestion))
}
