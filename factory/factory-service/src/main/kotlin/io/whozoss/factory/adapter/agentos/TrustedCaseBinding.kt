package io.whozoss.factory.adapter.agentos

/**
 * Trusted identities bound to an AgentOS case at the Factory boundary.
 *
 * Every field originates from the Factory authority — the durable agent
 * attempt, the resolved capability and the governed work environment — and
 * never from LLM-supplied arguments. The binding is the single vehicle
 * carrying these identities through the [AgentRuntimeAdapter] lifecycle:
 * create, turn start, observation, reconciliation and shutdown.
 *
 * @property caseId deterministic, Factory-assigned case identifier.
 * @property attemptId durable attempt identifier; the idempotency key.
 * @property namespaceId owning namespace, when the caller boundary resolved
 *   one (legacy entry points that never carried it pass null).
 * @property parentCaseId parent case of this execution inside the durable case
 *   family (Lot B): `null` for the entry (root) agent step and the run's
 *   `rootCaseId` for every subsequent (child) sub-case. Carried verbatim to
 *   AgentOS (`parentCaseId`) and used as the family-compatibility proof when a
 *   case is adopted from a cold cache (never silently dropped).
 * @property runtimeId identifier of the runtime serving the case (e.g.
 *   `agentos-primary`), when known.
 * @property capabilityToken single-use capability token bound to the attempt.
 * @property environmentRef governed work-environment reference of the attempt.
 * @property environmentRevision expected revision of [environmentRef].
 * @property externalUserId trusted external identity forwarded as
 *   `X-External-User-Id`.
 */
data class TrustedCaseBinding(
    val caseId: String,
    val attemptId: String,
    val namespaceId: String? = null,
    val parentCaseId: String? = null,
    val runtimeId: String? = null,
    val capabilityToken: String? = null,
    val agentName: String? = null,
    val environmentRef: String? = null,
    val environmentRevision: Int? = null,
    val externalUserId: String? = null,
)
