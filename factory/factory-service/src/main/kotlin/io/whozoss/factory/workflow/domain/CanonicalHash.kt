package io.whozoss.factory.workflow.domain

import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

/**
 * Strict canonical JSON hashing for the workflow aggregate.
 *
 * Faithful port of the `canonicalize` / `canonicalizeWorkflowDefinition` /
 * `workflowStartCommandHash` helpers in
 * `factory/src/domain/workflow/workflow-definition.ts` and
 * `factory/src/domain/workflow/workflow-instance.ts`:
 *
 *   * object keys are recursively sorted with the natural (lexicographic)
 *     `String` ordering — the Node `Object.keys(record).sort()` default;
 *   * arrays keep their position (array order is semantic);
 *   * `null` values are **preserved** (unlike the delivery `CanonicalHash`
 *     which drops them) so the wire digest is identical to the Node
 *     `JSON.stringify(canonicalize(value))` output;
 *   * the digest is a lowercase SHA-256 hex string without a prefix, matching
 *     `createHash('sha256').update(json, 'utf8').digest('hex')`.
 *
 * The canonical JSON is produced by Jackson in compact form (no indentation,
 * no extra whitespace), which reproduces `JSON.stringify` for the bounded
 * primitive/string/array/object payloads the workflow domain accepts.
 */
object CanonicalHash {

    private val mapper = ObjectMapper()

    /** Recursively canonicalized value: sorted object keys, ordered arrays, preserved nulls. */
    fun canonicalize(value: Any?): Any? = when (value) {
        null -> null
        is Map<*, *> -> {
            val sorted = LinkedHashMap<String, Any?>()
            value.keys
                .map { it.toString() }
                .sorted()
                .forEach { key -> sorted[key] = canonicalize(value[key]) }
            sorted
        }
        is List<*> -> value.map { canonicalize(it) }
        is Array<*> -> value.map { canonicalize(it) }
        else -> value
    }

    /** Compact canonical JSON of the recursively key-sorted value. */
    fun canonicalizeJson(value: Any?): String = mapper.writeValueAsString(canonicalize(value))

    /** Lowercase SHA-256 hex digest of the UTF-8 encoded [input]. */
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Canonical SHA-256 digest of an arbitrary value (no prefix). */
    fun canonicalHash(value: Any?): String = sha256Hex(canonicalizeJson(value))

    /**
     * Port of `hashWorkflowDefinition`: the SHA-256 of the canonicalized
     * definition JSON.
     */
    fun workflowDefinitionHash(definition: Any?): String = canonicalHash(definition)

    /**
     * Port of `workflowStartCommandHash`: the SHA-256 of the canonicalized start
     * command bound to its resolved definition identity. Independent relations
     * default to `{ rootWorkflowId: workflowId }` when the command omits them.
     */
    fun workflowStartCommandHash(command: WorkflowStartCommand, definition: WorkflowDefinitionInput): String {
        val relations = command.relations ?: mapOf("rootWorkflowId" to command.workflowId)
        val payload = linkedMapOf<String, Any?>(
            "workflowId" to command.workflowId,
            "workflowType" to command.workflowType,
            "title" to command.title,
            "relations" to relations.toMap(),
            "definitionVersion" to definition.version,
            "definitionHash" to definition.definitionHash,
        )
        command.ticket?.let { payload["ticket"] = it }
        command.workstream?.let { payload["workstream"] = it }
        // Preserve the legacy digest for callers that do not yet carry a
        // controller request. When present, hash only its semantic text: the
        // trusted observedAt snapshot changes on an HTTP retry, but an identical
        // initial request must remain idempotent. A different text still changes
        // the command identity and is rejected rather than overwritten.
        command.controllerRequest?.let { payload["initialRequest"] = it.text }
        return canonicalHash(payload)
    }

    /**
     * Port of `hashWorkflowProjection`: the SHA-256 of the canonicalized
     * projection. Any `expectedRevision` command precondition is removed by the
     * caller before hashing, exactly like the Node `normalizeProjection`.
     */
    fun workflowProjectionHash(projection: Any?): String = canonicalHash(projection)

    /**
     * SHA-256 of the transition semantic tuple, exactly as the Node
     * `transitionSemanticHash` computes it: a plain `JSON.stringify` of a fixed
     * key order (NOT canonicalized) with the evidence ids sorted.
     */
    fun transitionSemanticHash(request: WorkflowTransitionRequest): String {
        val payload = linkedMapOf<String, Any?>(
            "workflowId" to request.workflowId,
            "stepId" to request.stepId,
            "expectedRevision" to request.expectedRevision,
            "requestedStatus" to request.requestedStatus,
            "evidenceIds" to request.evidenceIds.sorted(),
        )
        return sha256Hex(mapper.writeValueAsString(payload))
    }

    /**
     * SHA-256 of the transition scope tuple, exactly as the Node
     * `transitionScopeHash` computes it: a plain `JSON.stringify` of a fixed key
     * order (NOT canonicalized).
     */
    fun transitionScopeHash(namespaceId: String?, request: WorkflowTransitionRequest, execution: WorkflowExecution): String {
        val payload = linkedMapOf<String, Any?>(
            "namespaceId" to namespaceId,
            "workflowId" to request.workflowId,
            "stepId" to request.stepId,
            "source" to linkedMapOf<String, Any?>(
                "kind" to execution.kind,
                "runtimeId" to execution.runtimeId,
                "agentId" to execution.agentId,
                "actorId" to execution.actorId,
                "caseId" to execution.caseId,
                "threadId" to execution.threadId,
            ),
            "idempotencyKey" to request.idempotencyKey,
        )
        return sha256Hex(mapper.writeValueAsString(payload))
    }
}
