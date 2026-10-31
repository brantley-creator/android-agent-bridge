package dev.brantley.agentbridge.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.brantley.agentbridge.BridgeApp
import dev.brantley.agentbridge.safety.ConfirmAnswer
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/** Platform value of TakeScreenshotCallback.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW. The SDK stub does not expose the constant. */
private const val SECURE_WINDOW = 6

class SwitchingPort : DevicePort {
    @Volatile var service: BridgeAccessibilityService? = null

    override fun a11yEnabled(): Boolean = service != null

    override fun currentScene(): Scene = service?.currentScene() ?: Scene("", "", NodeView("empty"))

    override suspend fun tapRef(path: List<Int>, identity: NodeIdentity, long: Boolean): ActionResult =
        service?.tapRef(path, identity, long) ?: ActionResult.FAILED

    override suspend fun tapAt(x: Int, y: Int, long: Boolean): Boolean = service?.tapAt(x, y, long) ?: false

    override fun typeText(
        path: List<Int>,
        identity: NodeIdentity,
        text: String,
        clear: Boolean,
        submit: Boolean,
    ): ActionResult = service?.typeText(path, identity, text, clear, submit) ?: ActionResult.FAILED

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        service?.swipe(x1, y1, x2, y2, durationMs) ?: false

    override suspend fun scroll(path: List<Int>?, identity: NodeIdentity?, direction: String): ActionResult =
        service?.scroll(path, identity, direction) ?: ActionResult.FAILED

    override fun key(name: String): Boolean = service?.key(name) ?: false

    override fun launch(packageName: String): Boolean = service?.launch(packageName) ?: false

    override fun listApps(): List<ListedApp> = service?.listApps() ?: emptyList()

    override fun screenshot(): ScreenshotGrab = service?.screenshotBlocking() ?: ScreenshotGrab.Failed

    override suspend fun settle() {
        service?.settle()
    }

    override suspend fun delayMs(ms: Long) {
        delay(ms)
    }

    override suspend fun waitForText(text: String, timeoutMs: Long): Boolean {
        val svc = service ?: return false
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() <= deadline) {
            if (svc.currentScene().root.containsText(text)) return true
            delay(100)
        }
        return false
    }
}

class BridgeAccessibilityService : AccessibilityService(), DevicePort {
    private val main = Handler(Looper.getMainLooper())
    private var overlay: LinearLayout? = null
    private var confirmCont: CancellableContinuation<ConfirmAnswer?>? = null
    @Volatile private var lastEventAt = SystemClock.uptimeMillis()

    override fun onServiceConnected() {
        super.onServiceConnected()
        (application as BridgeApp).port.service = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cancelConfirm()
        detach()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() = Unit

    suspend fun confirm(description: String): ConfirmAnswer? =
        suspendCancellableCoroutine { cont ->
            confirmCont = cont
            cont.invokeOnCancellation {
                confirmCont = null
                main.post { removeOverlay() }
            }
            main.post {
                if (!cont.isActive) return@post
                showOverlay(description) { answer ->
                    removeOverlay()
                    confirmCont = null
                    if (cont.isActive) cont.resume(answer)
                }
            }
        }

    fun cancelConfirm() {
        val cont = confirmCont
        confirmCont = null
        main.post { removeOverlay() }
        if (cont != null && cont.isActive) cont.resume(null)
    }

    override fun a11yEnabled(): Boolean = true

    override fun currentScene(): Scene {
        val root = rootInActiveWindow ?: return Scene("", "", NodeView("empty"))
        return try {
            val title = windows?.firstOrNull { it.isActive }?.title?.toString().orEmpty()
            Scene(root.packageName?.toString().orEmpty(), title, root.toNode())
        } finally {
            root.recycle()
        }
    }

    override suspend fun tapRef(path: List<Int>, identity: NodeIdentity, long: Boolean): ActionResult {
        val acted =
            withNode(path, identity) { node ->
                val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
                node.performAction(action)
            }
        if (acted == ActionResult.OK) return acted
        if (acted == ActionResult.STALE) return acted
        val node = shallowAt(path) ?: return ActionResult.FAILED
        if (identityOf(node) != identity) return ActionResult.STALE
        val centerX = node.bounds.centerX()
        val centerY = node.bounds.centerY()
        return if (tapAt(centerX, centerY, long)) ActionResult.OK else ActionResult.FAILED
    }

    override suspend fun tapAt(x: Int, y: Int, long: Boolean): Boolean {
        val path =
            Path().apply {
                moveTo(x.toFloat(), y.toFloat())
                lineTo(x + 1f, y + 1f)
            }
        val duration = if (long) 600L else 50L
        return gesture(path, duration)
    }

    override fun typeText(
        path: List<Int>,
        identity: NodeIdentity,
        text: String,
        clear: Boolean,
        submit: Boolean,
    ): ActionResult =
        withNode(path, identity) { node ->
            val existing = if (clear) "" else node.text?.toString().orEmpty()
            val args =
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        existing + text,
                    )
                }
            val typed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (typed && submit) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            typed
        }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path =
            Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
        return gesture(path, durationMs.coerceAtLeast(1))
    }

    override suspend fun scroll(path: List<Int>?, identity: NodeIdentity?, direction: String): ActionResult {
        if (path != null && identity != null) {
            val action =
                if (direction == "down" || direction == "right") {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
            val acted = withNode(path, identity) { node -> node.performAction(action) }
            if (acted == ActionResult.OK || acted == ActionResult.STALE) return acted
        }
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val points =
            when (direction) {
                "up" -> intArrayOf(width / 2, (height * 0.75f).toInt(), width / 2, (height * 0.3f).toInt())
                "left" -> intArrayOf((width * 0.8f).toInt(), height / 2, (width * 0.2f).toInt(), height / 2)
                "right" -> intArrayOf((width * 0.2f).toInt(), height / 2, (width * 0.8f).toInt(), height / 2)
                else -> intArrayOf(width / 2, (height * 0.3f).toInt(), width / 2, (height * 0.75f).toInt())
            }
        return if (swipe(points[0], points[1], points[2], points[3], 300)) ActionResult.OK else ActionResult.FAILED
    }

    override fun key(name: String): Boolean {
        val action =
            when (name) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                else -> return false
            }
        return performGlobalAction(action)
    }

    override fun launch(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    override fun listApps(): List<ListedApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0).map { info ->
            ListedApp(
                packageName = info.activityInfo.packageName,
                label = info.loadLabel(packageManager)?.toString().orEmpty(),
            )
        }
    }

    override fun screenshot(): ScreenshotGrab = screenshotBlocking()

    fun screenshotBlocking(): ScreenshotGrab {
        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = arrayOfNulls<ScreenshotGrab>(1)
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    holder[0] = encode(screenshot)
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    holder[0] =
                        if (errorCode == SECURE_WINDOW) {
                            ScreenshotGrab.Secure
                        } else {
                            ScreenshotGrab.Failed
                        }
                    latch.countDown()
                }
            },
        )
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return holder[0] ?: ScreenshotGrab.Failed
    }

    override suspend fun settle() {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < 2_000) {
            delay(50)
            val now = SystemClock.uptimeMillis()
            if (now - start >= 300 && now - lastEventAt >= 300) return
        }
    }

    override suspend fun delayMs(ms: Long) {
        delay(ms)
    }

    override suspend fun waitForText(text: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() <= deadline) {
            if (currentScene().root.containsText(text)) return true
            delay(100)
        }
        return false
    }

    private fun detach() {
        val app = application as? BridgeApp ?: return
        if (app.port.service === this) app.port.service = null
    }

    private fun withNode(path: List<Int>, identity: NodeIdentity, block: (AccessibilityNodeInfo) -> Boolean): ActionResult {
        val root = rootInActiveWindow ?: return ActionResult.FAILED
        val owned = mutableListOf<AccessibilityNodeInfo>()
        return try {
            var current = root
            for (index in path) {
                val next = current.getChild(index) ?: return ActionResult.STALE
                owned += next
                current = next
            }
            if (identityOf(current.toShallow()) != identity) return ActionResult.STALE
            if (block(current)) ActionResult.OK else ActionResult.FAILED
        } finally {
            owned.forEach { runCatching { it.recycle() } }
            runCatching { root.recycle() }
        }
    }

    private fun shallowAt(path: List<Int>): NodeView? {
        val root = rootInActiveWindow ?: return null
        val owned = mutableListOf<AccessibilityNodeInfo>()
        return try {
            var current = root
            for (index in path) {
                val next = current.getChild(index) ?: return null
                owned += next
                current = next
            }
            current.toShallow()
        } finally {
            owned.forEach { runCatching { it.recycle() } }
            runCatching { root.recycle() }
        }
    }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val sent =
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
            if (!sent && cont.isActive) cont.resume(false)
        }

    private fun showOverlay(description: String, onAnswer: (ConfirmAnswer) -> Unit) {
        removeOverlay()
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                setBackgroundColor(0xF0111614.toInt())
                addView(TextView(this@BridgeAccessibilityService).apply {
                    text = description
                    setTextColor(0xFFE7F0EA.toInt())
                    textSize = 18f
                })
                addView(button("Deny", "Deny") { onAnswer(ConfirmAnswer.DENY) })
                addView(button("Approve", "Approve") { onAnswer(ConfirmAnswer.APPROVE) })
                addView(button("Approve 5 min", "Approve 5 min") { onAnswer(ConfirmAnswer.APPROVE_5_MIN) })
            }
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.BOTTOM
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.addView(layout, params)
        overlay = layout
    }

    private fun button(label: String, description: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            contentDescription = description
            setOnClickListener { onClick() }
        }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(view) }
    }

    private fun encode(screenshot: ScreenshotResult): ScreenshotGrab {
        return try {
            val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
            screenshot.hardwareBuffer.close()
            if (hardware == null) return ScreenshotGrab.Failed
            val software =
                if (hardware.config == Bitmap.Config.HARDWARE) {
                    hardware.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    hardware
                }
            val bytes = ByteArrayOutputStream()
            software.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            if (software !== hardware) hardware.recycle()
            software.recycle()
            ScreenshotGrab.Png(bytes.toByteArray())
        } catch (_: Throwable) {
            ScreenshotGrab.Failed
        }
    }
}

private fun AccessibilityNodeInfo.toNode(): NodeView {
    val children = mutableListOf<NodeView>()
    for (index in 0 until childCount) {
        val child = getChild(index) ?: continue
        children += child.toNode()
        child.recycle()
    }
    return toShallow(children)
}

private fun AccessibilityNodeInfo.toShallow(children: List<NodeView> = emptyList()): NodeView {
    val rect = Rect()
    getBoundsInScreen(rect)
    return NodeView(
        className = className?.toString().orEmpty(),
        text = text?.toString(),
        desc = contentDescription?.toString(),
        viewId = viewIdResourceName,
        bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
        checked = isChecked,
        enabled = isEnabled,
        focused = isFocused,
        selected = isSelected,
        scrollable = isScrollable,
        editable = isEditable,
        password = isPassword,
        clickable = isClickable,
        longClickable = isLongClickable,
        checkable = isCheckable,
        children = children,
    )
}
