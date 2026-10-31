package dev.brantley.agentbridge.a11y

class RefTable {
    private val snapshots = ArrayDeque<StoredSnapshot>()

    fun remember(snapshotId: String, assignments: List<Assignment>) {
        snapshots.addLast(StoredSnapshot(snapshotId, assignments.associateBy { it.n }))
        while (snapshots.size > 3) snapshots.removeFirst()
    }

    fun latestRefs(): Map<List<Int>, String> {
        val stored = snapshots.lastOrNull() ?: return emptyMap()
        return stored.nodes.map { (n, assignment) -> assignment.path to "@${stored.id}:e$n" }.toMap()
    }

    fun resolve(ref: String, live: NodeView): ResolveResult {
        val match = REF.matchEntire(ref) ?: return ResolveResult.Bad("BAD_ARGS")
        val snapshotId = match.groupValues[1]
        val index = match.groupValues[2].toIntOrNull() ?: return ResolveResult.Bad("BAD_ARGS")
        val stored = snapshots.findLast { it.id == snapshotId } ?: return ResolveResult.Bad("STALE_REF")
        val assignment = stored.nodes[index] ?: return ResolveResult.Bad("STALE_REF")
        val liveNode = live.at(assignment.path) ?: return ResolveResult.Bad("STALE_REF")
        if (identityOf(liveNode) != assignment.identity) return ResolveResult.Bad("STALE_REF")
        return ResolveResult.Hit(assignment.path, assignment.identity, liveNode)
    }

    private data class StoredSnapshot(
        val id: String,
        val nodes: Map<Int, Assignment>,
    )

    companion object {
        private val REF = Regex("^@([A-Za-z0-9_-]+):e([1-9][0-9]*)$")
    }
}

sealed class ResolveResult {
    data class Hit(
        val path: List<Int>,
        val identity: NodeIdentity,
        val node: NodeView,
    ) : ResolveResult()

    data class Bad(val code: String) : ResolveResult()
}
