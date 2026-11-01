package dev.brantley.agentbridge

import dev.brantley.agentbridge.a11y.Bounds
import dev.brantley.agentbridge.a11y.NodeView
import dev.brantley.agentbridge.a11y.Scene
import dev.brantley.agentbridge.net.Rpc
import dev.brantley.agentbridge.safety.AuditLog
import dev.brantley.agentbridge.safety.ConfirmAnswer
import dev.brantley.agentbridge.safety.ConfirmMode
import dev.brantley.agentbridge.safety.ConfirmPrompter
import dev.brantley.agentbridge.safety.Outcome
import dev.brantley.agentbridge.safety.PhoneCore
import dev.brantley.agentbridge.safety.Policy
import dev.brantley.agentbridge.safety.SafetyConfig
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyCoreTest {
    @Test
    fun refGenerationUsesSnapshotId() = runBlocking {
        val world = world()
        world.core.arm(0)
        val data = ok(world.core.handle("snapshot", buildJsonObject { }))
        assertEquals("s1", data.string("snapshot_id"))
        assertEquals(1, data.int("ref_count"))
        val raw = data.toString()
        assertTrue(raw.contains("\"ref\":\"@s1:e1\""))
        assertTrue(raw.contains("Send"))
        assertFalse(raw.contains("Hello there"))
    }

    @Test
    fun bareRefIsBadArgsAndChangedIdentityIsStale() = runBlocking {
        val world = world()
        world.core.arm(0)
        world.core.handle("snapshot", buildJsonObject { })
        val bare = world.rpc.handle("""{"id":"1","method":"tap","params":{"ref":"@e3"}}""")
        assertTrue(bare.contains("\"ok\":false"))
        assertTrue(bare.contains("BAD_ARGS"))
        world.port.scene = world.port.scene.copy(root = messages(buttonText = "Sent"))
        val stale = fail(world.core.handle("tap", buildJsonObject { put("ref", "@s1:e1") }))
        assertEquals("STALE_REF", stale)
        repeat(3) { world.core.handle("snapshot", buildJsonObject { }) }
        world.port.scene = world.port.scene.copy(root = messages())
        val evicted = fail(world.core.handle("tap", buildJsonObject { put("ref", "@s1:e1") }))
        assertEquals("STALE_REF", evicted)
    }

    @Test
    fun passwordIsRedactedAndTypingIsRefused() = runBlocking {
        val file = File.createTempFile("audit", ".jsonl")
        val audit = AuditLog(file)
        val port = FakePort(Scene("com.example.messages", "Login", passwordTree()))
        val core = core(port, audit, ask = { ConfirmAnswer.APPROVE })
        core.arm(0)
        val snapshot = ok(core.handle("snapshot", buildJsonObject { })).toString()
        assertTrue(snapshot.contains("[redacted]"))
        assertFalse(snapshot.contains("hunter2"))
        val typed = "s3cr3t-typed-value"
        val code = fail(core.handle("type", buildJsonObject {
            put("ref", "@s1:e1")
            put("text", typed)
        }))
        assertEquals("PASSWORD_FIELD", code)
        assertEquals(0, port.types)
        val dumped = file.readText() + audit.entries.joinToString { it.target + it.result + it.typedLength }
        assertFalse(dumped.contains(typed))
        assertFalse(dumped.contains("hunter2"))
        assertEquals(typed.length, audit.entries.last().typedLength)
        assertEquals("PASSWORD_FIELD", audit.entries.last().result)
    }

    @Test
    fun denylistedForegroundReturnsNoTree() = runBlocking {
        val world = world(Scene("com.x8bit.bitwarden", "Vault", messages(buttonText = "vault-secret-row")))
        world.core.arm(0)
        val response = world.rpc.handle("""{"id":"9","method":"snapshot","params":{}}""")
        assertTrue(response.contains("DENIED_APP"))
        assertFalse(response.contains("vault-secret-row"))
        assertFalse(response.contains("\"tree\""))
        val launch = fail(world.core.handle("launch", buildJsonObject { put("package", "com.x8bit.bitwarden") }))
        assertEquals("DENIED_APP", launch)
        assertNull(world.port.launched)
        assertTrue(Policy.isDenied("com.android.permissioncontroller", SafetyConfig(removedDefaults = setOf("com.android.permissioncontroller"))))
        assertFalse(Policy.isFloor("com.chase.sig.android"))
    }

    @Test
    fun confirmModeAndSettingsAlwaysConfirm() = runBlocking {
        val prompts = mutableListOf<String>()
        val world = world(prompter = { description ->
            prompts += description
            ConfirmAnswer.APPROVE
        })
        world.core.arm(0)
        world.core.handle("snapshot", buildJsonObject { })
        assertTrue(prompts.isEmpty())
        world.core.handle("tap", buildJsonObject { put("ref", "@s1:e1") })
        assertEquals(listOf("Tap 'Send' in Messages"), prompts)
        world.core.config = SafetyConfig(confirmMode = ConfirmMode.OFF)
        world.core.handle("tap", buildJsonObject { put("ref", "@s1:e1") })
        assertEquals(1, prompts.size)
        world.port.scene = Scene("com.android.settings", "Settings", messages())
        world.core.handle("snapshot", buildJsonObject { })
        world.core.handle("tap", buildJsonObject { put("ref", "@s2:e1") })
        assertTrue(prompts.any { it == "Tap 'Send' in Settings" })
        val grantPrompts = prompts.size
        world.port.scene = Scene("com.example.messages", "Messages", messages())
        world.core.config = SafetyConfig(confirmMode = ConfirmMode.WRITES)
        world.core.handle("tap", buildJsonObject { put("ref", "@s2:e1") })
        // The settings snapshot above created s2 from the settings tree, then we changed the scene
        // back. Use a fresh snapshot so the ref matches Messages.
        world.core.handle("snapshot", buildJsonObject { })
        val before = prompts.size
        val approver = world
        approver.core.handle("tap", buildJsonObject { put("ref", "@s3:e1") })
        assertTrue(prompts.size > before)
        prompts.clear()
        val five = world(prompter = {
            prompts += it
            ConfirmAnswer.APPROVE_5_MIN
        })
        five.core.arm(0)
        five.core.handle("snapshot", buildJsonObject { })
        five.core.handle("tap", buildJsonObject { put("ref", "@s1:e1") })
        five.core.handle("tap", buildJsonObject { put("ref", "@s1:e1") })
        assertEquals(1, prompts.size)
        five.port.scene = Scene("com.android.settings", "Settings", messages())
        five.core.handle("tap", buildJsonObject { put("ref", "@s1:e1") })
        assertEquals(2, prompts.size)
        assertTrue(Policy.needsConfirm("launch", "com.example.messages", "com.android.settings", SafetyConfig(confirmMode = ConfirmMode.OFF), 0, 0))
        assertEquals(20_000L, ConfirmPrompter.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun confirmTimesOutAfterTwentySeconds() = runTest {
        val port = FakePort(Scene("com.example.messages", "Messages", messages()))
        val core =
            PhoneCore(
                port,
                AuditLog(),
                clock = { 0L },
                prompter = ConfirmPrompter(ask = { awaitCancellation() }),
            )
        core.arm(0)
        core.handle("snapshot", buildJsonObject { })
        assertEquals("CONFIRM_TIMEOUT", fail(core.handle("tap", buildJsonObject { put("ref", "@s1:e1") })))
        assertEquals(0, port.taps)
        assertEquals(20_000L, ConfirmPrompter.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun killReturnsPausedAndIdleExpiresTheSession() = runBlocking {
        var now = 0L
        val port = FakePort(Scene("com.example.messages", "Messages", messages()))
        val core = PhoneCore(port, AuditLog(), clock = { now }, prompter = ConfirmPrompter(ask = { ConfirmAnswer.APPROVE }))
        core.arm(0)
        core.disarm()
        assertEquals("PAUSED", fail(core.handle("status", buildJsonObject { })))
        assertEquals(0, port.taps)
        core.arm(0)
        now = 10 * 60 * 1000
        assertEquals("SESSION_EXPIRED", fail(core.handle("snapshot", buildJsonObject { })))
        assertFalse(core.armed.value)
        assertEquals("PAUSED", fail(core.handle("tap", buildJsonObject { put("x", 1); put("y", 2) })))
    }

    @Test
    fun volumeSetsMusicStreamThroughConfirmAndAudit() = runBlocking {
        val prompts = mutableListOf<String>()
        val world =
            world(
                prompter = { description ->
                    prompts += description
                    ConfirmAnswer.APPROVE
                },
            )
        assertEquals("PAUSED", fail(world.core.handle("volume", buildJsonObject { put("percent", 40) })))
        assertEquals(0, world.port.volumeSets)
        world.core.arm(0)
        val data = ok(world.core.handle("volume", buildJsonObject { put("percent", 40) }))
        assertEquals("music", data.string("stream"))
        assertEquals("volume", data.string("performed"))
        assertEquals(6, data.int("index"))
        assertEquals(15, data.int("max"))
        assertEquals(40, data.int("percent"))
        assertEquals(40, world.port.lastVolumePercent)
        assertEquals(listOf("Set media volume to 40 percent in Messages"), prompts)
        assertEquals("volume", world.audit.entries.last().method)
        assertEquals("OK", world.audit.entries.last().result)
        world.core.disarm()
        assertEquals("PAUSED", fail(world.core.handle("volume", buildJsonObject { put("percent", 10) })))
        assertEquals(1, world.port.volumeSets)
        world.core.arm(0)
        assertEquals("BAD_ARGS", fail(world.core.handle("volume", buildJsonObject { put("percent", 101) })))
        assertEquals("BAD_ARGS", fail(world.core.handle("volume", buildJsonObject { })))
        assertEquals(1, world.port.volumeSets)
        val denied =
            world(
                prompter = {
                    ConfirmAnswer.DENY
                },
            )
        denied.core.arm(0)
        assertEquals("DENIED_BY_USER", fail(denied.core.handle("volume", buildJsonObject { put("percent", 20) })))
        assertEquals(0, denied.port.volumeSets)
        world.core.config = SafetyConfig(confirmMode = ConfirmMode.OFF)
        world.port.scene = Scene("com.android.settings", "Settings", messages())
        val before = prompts.size
        val muted = ok(world.core.handle("volume", buildJsonObject { put("percent", 0) }))
        assertTrue(prompts.size > before)
        assertEquals(0, muted.int("index"))
        assertEquals(0, muted.int("percent"))
        assertTrue(prompts.last().contains("Settings"))
    }
}

private class World(
    val port: FakePort,
    val audit: AuditLog,
    val core: PhoneCore,
    val rpc: Rpc,
)

private fun world(
    scene: Scene = Scene("com.example.messages", "Messages", messages()),
    prompter: suspend (String) -> ConfirmAnswer? = { ConfirmAnswer.APPROVE },
): World {
    val port = FakePort(scene)
    val audit = AuditLog()
    val core = core(port, audit, prompter)
    return World(port, audit, core, Rpc(core))
}

private fun core(
    port: FakePort,
    audit: AuditLog,
    ask: suspend (String) -> ConfirmAnswer?,
    timeoutMs: Long = ConfirmPrompter.DEFAULT_TIMEOUT_MS,
): PhoneCore = PhoneCore(port, audit, clock = { 0L }, prompter = ConfirmPrompter(ask, timeoutMs))

private fun messages(buttonText: String = "Send") =
    NodeView(
        className = "android.widget.FrameLayout",
        children =
            listOf(
                NodeView(
                    className = "android.widget.Button",
                    text = buttonText,
                    clickable = true,
                    bounds = Bounds(0, 100, 200, 180),
                ),
                NodeView(className = "android.widget.TextView", text = "Hello there"),
            ),
    )

private fun passwordTree() =
    NodeView(
        className = "android.widget.FrameLayout",
        children =
            listOf(
                NodeView(
                    className = "android.widget.EditText",
                    text = "hunter2",
                    desc = "hunter2",
                    editable = true,
                    password = true,
                    focused = true,
                    bounds = Bounds(0, 0, 100, 40),
                ),
            ),
    )

private fun ok(outcome: Outcome): JsonObject {
    val success = outcome as Outcome.Ok
    return success.data
}

private fun fail(outcome: Outcome): String = (outcome as Outcome.Fail).error.code

private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.content.toInt()

private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

private val parser = Json { ignoreUnknownKeys = true }
