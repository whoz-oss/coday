package io.whozoss.factory.agentattempt.domain

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Frozen context envelope of a durable execution attempt (Lot D — Context
 * Envelope & Replay Integrity).
 *
 * The envelope captures, at reservation time, the complete structured context
 * that shaped one execution attempt: the resolved workstream/namespace
 * attribution, the workflow/step/attempt identity, and the dependency inputs,
 * run-brief handoff and controller request assembled for the turn brief.
 *
 * It is serialized ONCE as JSON and persisted verbatim on the
 * [DurableAgentAttempt]. A replay (crash recovery, retry adoption or
 * re-registration) reads the frozen envelope back instead of re-deriving the
 * context from upstream evidence, guaranteeing that a replayed attempt observes
 * exactly the context it was first registered with. The [briefHash] pins the
 * exact turn-brief text that was frozen alongside the envelope (the brief text
 * itself lives on [DurableAgentAttempt.brief]).
 *
 * [expectedAmendmentSeq] is the amendment pin of the attempt: the amendment
 * sequence the context was frozen against. It is carried here (and on the
 * attempt, the DTO and the workflow start command) for schema completeness;
 * the amendment resolution/business logic itself belongs to Lot E and is
 * intentionally absent.
 */
data class AttemptContextEnvelope(
    val schemaVersion: Int = CONTEXT_ENVELOPE_SCHEMA_VERSION,
    /** Organization of the tenant scope the attempt ran in. */
    val organizationId: String,
    /** Workstream of the tenant scope the attempt ran in (explicit workstream <-> namespace mapping). */
    val workstreamId: String,
    /** Resolved namespace of the attempt. */
    val namespaceId: String,
    val workflowId: String,
    val stepId: String,
    val attemptNumber: Int,
    val agentName: String,
    val ticket: String? = null,
    /** `sha256:<hex>` of the frozen turn brief (see [DurableAgentAttempt.brief]). */
    val briefHash: String,
    /** Structured dependency inputs injected into the brief. */
    val inputs: Map<String, Any?> = emptyMap(),
    /** Structured run-brief handoff inherited from the dependency chain, when present. */
    val runBrief: Map<String, Any?>? = null,
    /** Trusted controller request text carried by the workflow instance, when present. */
    val controllerRequest: String? = null,
    /** Amendment sequence the context was frozen against (schema only; Lot E owns the logic). */
    val expectedAmendmentSeq: Long? = null,
) {
    /** The ordered, JSON-safe map form of the envelope. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to schemaVersion,
        "organizationId" to organizationId,
        "workstreamId" to workstreamId,
        "namespaceId" to namespaceId,
        "workflowId" to workflowId,
        "stepId" to stepId,
        "attemptNumber" to attemptNumber,
        "agentName" to agentName,
        "ticket" to ticket,
        "briefHash" to briefHash,
        "inputs" to inputs,
        "runBrief" to runBrief,
        "controllerRequest" to controllerRequest,
        "expectedAmendmentSeq" to expectedAmendmentSeq,
    )

    /** JSON serialization persisted verbatim on the attempt. */
    fun toJson(): String = mapper.writeValueAsString(toMap())

    companion object {
        /** Schema version of the frozen attempt context envelope. */
        const val CONTEXT_ENVELOPE_SCHEMA_VERSION = 1

        private val mapper = ObjectMapper()

        @Suppress("UNCHECKED_CAST")
        private fun readMap(json: String): Map<String, Any?> =
            mapper.readValue(json, Map::class.java) as Map<String, Any?>

        /** Deserialize an envelope persisted by [toJson]. */
        fun fromJson(json: String): AttemptContextEnvelope {
            val raw = readMap(json)
            return AttemptContextEnvelope(
                schemaVersion = (raw["schemaVersion"] as? Number)?.toInt() ?: CONTEXT_ENVELOPE_SCHEMA_VERSION,
                organizationId = raw["organizationId"] as? String ?: "",
                workstreamId = raw["workstreamId"] as? String ?: "",
                namespaceId = raw["namespaceId"] as? String ?: "",
                workflowId = raw["workflowId"] as? String ?: "",
                stepId = raw["stepId"] as? String ?: "",
                attemptNumber = (raw["attemptNumber"] as? Number)?.toInt() ?: 0,
                agentName = raw["agentName"] as? String ?: "",
                ticket = raw["ticket"] as? String,
                briefHash = raw["briefHash"] as? String ?: "",
                inputs = (raw["inputs"] as? Map<String, Any?>) ?: emptyMap(),
                runBrief = raw["runBrief"] as? Map<String, Any?>,
                controllerRequest = raw["controllerRequest"] as? String,
                expectedAmendmentSeq = (raw["expectedAmendmentSeq"] as? Number)?.toLong(),
            )
        }
    }
}
