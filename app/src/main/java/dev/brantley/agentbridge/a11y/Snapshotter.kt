package dev.brantley.agentbridge.a11y

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class Assignment(
    val n: Int,
    val path: List<Int>,
    val identity: NodeIdentity,
)

data class CapturedSnapshot(
    val tree: JsonObject,
    val refCount: Int,
    val complete: Boolean,
    val assignments: List<Assignment>,
)

object Snapshotter {
    fun capture(
        root: NodeView,
        snapshotId: String,
        interactiveOnly: Boolean,
        maxNodes: Int,
        pathPrefix: List<Int> = emptyList(),
    ): CapturedSnapshot {
        var budget = maxNodes
        var complete = true
        var next = 1
        val assignments = mutableListOf<Assignment>()

        fun emit(node: NodeView, path: List<Int>): JsonObject? {
            if (budget <= 0) {
                complete = false
                return null
            }
            budget -= 1
            val childJson = mutableListOf<JsonObject>()
            for ((index, child) in node.children.withIndex()) {
                val emitted = emit(child, path + index) ?: continue
                childJson += emitted
            }
            val actionable = node.isActionable()
            val keep = !interactiveOnly || actionable || childJson.isNotEmpty() || path == pathPrefix
            if (!keep) {
                budget += 1
                return null
            }
            val ref =
                if (actionable) {
                    val n = next
                    next += 1
                    assignments += Assignment(n, path, identityOf(node))
                    "@$snapshotId:e$n"
                } else {
                    null
                }
            return nodeJson(node, childJson, ref)
        }

        val tree = emit(root, pathPrefix) ?: nodeJson(root, emptyList(), null)
        return CapturedSnapshot(tree, assignments.size, complete, assignments.toList())
    }

    fun summary(root: NodeView, refsByPath: Map<List<Int>, String>, limit: Int = 40): JsonArray {
        val rows = mutableListOf<JsonObject>()
        fun walk(node: NodeView, path: List<Int>) {
            if (rows.size >= limit) return
            if (node.isActionable()) {
                rows +=
                    buildJsonObject {
                        put("role", node.className.ifBlank { "unknown" })
                        put("text", if (node.password) "[redacted]" else node.text.orEmpty())
                        put("bounds", boundsJson(node.bounds))
                        refsByPath[path]?.let { put("ref", it) }
                    }
            }
            if (rows.size >= limit) return
            node.children.forEachIndexed { index, child -> walk(child, path + index) }
        }
        walk(root, emptyList())
        return JsonArray(rows)
    }

    private fun nodeJson(node: NodeView, children: List<JsonObject>, ref: String?): JsonObject {
        val secret = node.password
        return buildJsonObject {
            put("role", node.className.ifBlank { "unknown" })
            put("text", if (secret) "[redacted]" else node.text.orEmpty())
            put("desc", if (secret) "[redacted]" else node.desc.orEmpty())
            put("viewId", node.viewId.orEmpty())
            put("bounds", boundsJson(node.bounds))
            put(
                "states",
                buildJsonObject {
                    put("checked", node.checked)
                    put("enabled", node.enabled)
                    put("focused", node.focused)
                    put("selected", node.selected)
                    put("scrollable", node.scrollable)
                    put("editable", node.editable)
                    put("password", node.password)
                },
            )
            if (ref != null) put("ref", ref)
            put("children", JsonArray(children))
        }
    }

    private fun boundsJson(bounds: Bounds) =
        buildJsonArray {
            add(bounds.l)
            add(bounds.t)
            add(bounds.r)
            add(bounds.b)
        }
}
