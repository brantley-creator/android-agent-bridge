package dev.brantley.agentbridge.a11y

data class Bounds(
    val l: Int,
    val t: Int,
    val r: Int,
    val b: Int,
) {
    fun bucket(): List<Int> = listOf(l / 16, t / 16, r / 16, b / 16)

    fun centerX(): Int = (l + r) / 2

    fun centerY(): Int = (t + b) / 2
}

data class NodeView(
    val className: String,
    val text: String? = null,
    val desc: String? = null,
    val viewId: String? = null,
    val bounds: Bounds = Bounds(0, 0, 0, 0),
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val checkable: Boolean = false,
    val children: List<NodeView> = emptyList(),
) {
    fun isActionable(): Boolean = clickable || longClickable || checkable || editable || scrollable

    fun at(path: List<Int>): NodeView? {
        var current = this
        for (index in path) {
            current = current.children.getOrNull(index) ?: return null
        }
        return current
    }

    fun containsText(needle: String): Boolean {
        val shown = if (password) "" else text.orEmpty()
        if (shown.contains(needle, ignoreCase = true)) return true
        if (!password && desc.orEmpty().contains(needle, ignoreCase = true)) return true
        return children.any { it.containsText(needle) }
    }
}

data class NodeIdentity(
    val className: String,
    val viewId: String,
    val text: String,
    val bucket: List<Int>,
)

fun identityOf(node: NodeView): NodeIdentity {
    val shown = if (node.password) "[redacted]" else node.text.orEmpty()
    return NodeIdentity(
        className = node.className,
        viewId = node.viewId.orEmpty(),
        text = shown,
        bucket = node.bounds.bucket(),
    )
}

data class Scene(
    val packageName: String,
    val windowTitle: String,
    val root: NodeView,
)
