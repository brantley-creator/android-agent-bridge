package dev.brantley.agentbridge

import dev.brantley.agentbridge.a11y.ActionResult
import dev.brantley.agentbridge.a11y.DevicePort
import dev.brantley.agentbridge.a11y.ListedApp
import dev.brantley.agentbridge.a11y.NodeIdentity
import dev.brantley.agentbridge.a11y.Scene
import dev.brantley.agentbridge.a11y.ScreenshotGrab
import dev.brantley.agentbridge.a11y.VolumeLevel
import dev.brantley.agentbridge.a11y.mediaVolumeIndex
import dev.brantley.agentbridge.a11y.mediaVolumePercent

class FakePort(
    var scene: Scene,
) : DevicePort {
    var a11y = true
    var taps = 0
    var types = 0
    var launched: String? = null
    var secure = false
    var lastTyped: String? = null
    var volumeMax = 15
    var volumeSets = 0
    var lastVolumePercent: Int? = null

    override fun a11yEnabled(): Boolean = a11y

    override fun currentScene(): Scene = scene

    override suspend fun tapRef(path: List<Int>, identity: NodeIdentity, long: Boolean): ActionResult {
        taps += 1
        return ActionResult.OK
    }

    override suspend fun tapAt(x: Int, y: Int, long: Boolean): Boolean {
        taps += 1
        return true
    }

    override fun typeText(
        path: List<Int>,
        identity: NodeIdentity,
        text: String,
        clear: Boolean,
        submit: Boolean,
    ): ActionResult {
        types += 1
        lastTyped = text
        return ActionResult.OK
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = true

    override suspend fun scroll(path: List<Int>?, identity: NodeIdentity?, direction: String): ActionResult = ActionResult.OK

    override fun key(name: String): Boolean = true

    override fun launch(packageName: String): Boolean {
        launched = packageName
        return true
    }

    override fun listApps(): List<ListedApp> = listOf(ListedApp("com.example.messages", "Messages"))

    override fun screenshot(): ScreenshotGrab = if (secure) ScreenshotGrab.Secure else ScreenshotGrab.Png(byteArrayOf(1, 2, 3))

    override fun setMediaVolume(percent: Int): VolumeLevel {
        volumeSets += 1
        lastVolumePercent = percent
        val index = mediaVolumeIndex(percent, volumeMax)
        return VolumeLevel(index, volumeMax, mediaVolumePercent(index, volumeMax))
    }

    override suspend fun settle() = Unit

    override suspend fun delayMs(ms: Long) = Unit

    override suspend fun waitForText(text: String, timeoutMs: Long): Boolean = scene.root.containsText(text)
}
