package io.whozoss.factory.forge.domain

/**
 * Story-phase workflow projection sync (pure guards + orchestration).
 *
 * Port of the pure parts of
 * `factory/src/application/forge-bmad/forge-workflow-sync.ts`. The authoritative
 * BMAD read is injected so this module stays free of the YAML parser.
 */
object WorkflowSync {

    private val ATTRIBUTION_FIELDS = setOf("actorId", "agentId", "caseId", "runId")
    private val SAFE_ATTRIBUTION = Regex("^[A-Za-z0-9][A-Za-z0-9._:@-]{0,255}$")

    /** Safe trusted Forge ticket identifier. */
    val SAFE_FORGE_TICKET_ID = Regex("^[A-Z][A-Z0-9]+-\\d+$")

    /** Validate a workflow-sync attribution payload. */
    fun sanitizeForgeSyncAttribution(body: Any?): Map<String, Any?> {
        val map = body as? Map<*, *> ?: return mapOf("ok" to false, "error" to mapOf("code" to "INVALID_ATTRIBUTION"))
        if (map.keys.any { it.toString() !in ATTRIBUTION_FIELDS }) {
            return mapOf("ok" to false, "error" to mapOf("code" to "INVALID_ATTRIBUTION"))
        }
        val attribution = LinkedHashMap<String, Any?>()
        for ((key, value) in map) {
            if (value !is String || !SAFE_ATTRIBUTION.matches(value)) {
                return mapOf("ok" to false, "error" to mapOf("code" to "INVALID_ATTRIBUTION"))
            }
            attribution[key.toString()] = value
        }
        return mapOf("ok" to true, "attribution" to attribution)
    }

    /**
     * Read the authoritative BMAD run and publish its workflow projection.
     *
     * [reader] returns the normalized run record or a failure envelope; [store]
     * publishes the adapted projection and reports `{ok, changed, snapshot}`.
     */
    fun syncForgeWorkflowProjection(
        repoRoot: String,
        namespaceId: String,
        ticketId: String,
        attribution: Map<String, Any?>,
        reader: (String, String) -> Map<String, Any?>,
        store: (String, Map<String, Any?>, Map<String, Any?>) -> Map<String, Any?>,
    ): Map<String, Any?> {
        if (ticketId.isEmpty() || !SAFE_FORGE_TICKET_ID.matches(ticketId)) {
            return mapOf("ok" to false, "error" to mapOf("code" to "INVALID_SYNC_TARGET"))
        }
        val authoritative = reader(repoRoot, ticketId)
        if (authoritative["ok"] != true) return authoritative
        val run = authoritative["run"]
        val adapted = ForgeWorkflowAdapter.adapt(run)
        if (adapted["ok"] != true) return adapted
        @Suppress("UNCHECKED_CAST")
        val projection = adapted["projection"] as? Map<String, Any?> ?: return adapted
        val published = store(namespaceId, projection, attribution)
        if (published["ok"] != true) return mapOf("ok" to false, "error" to published["error"])
        val snapshot = published["snapshot"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        return mapOf(
            "ok" to true,
            "changed" to published["changed"],
            "workflowId" to projection["workflowId"],
            "revision" to snapshot["revision"],
            "projectionHash" to snapshot["projectionHash"],
        )
    }
}
