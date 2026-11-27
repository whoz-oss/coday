package io.whozoss.factory.capability

import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AttemptContextEnvelope
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.persistence.TenantScope

/**
 * Building blocks shared by every flow that registers a successor attempt `N+1` on the SAME case family as its
 * predecessor: the NEEDS_RESEARCH router and the external-poll re-arm of an `IN_PROGRESS` agent step.
 *
 * Pure move out of [NeedsResearchRouter]: the behaviour of the NEEDS_RESEARCH flow is unchanged.
 */
internal object SuccessorAttempts {

    /**
     * The successor brief: the predecessor's frozen brief with its submission identifiers rewritten to the
     * successor's, plus an optional supplement. The predecessor's attempt id is a UUID, so a plain textual
     * replacement is exact.
     */
    fun brief(predecessor: DurableAgentAttempt, successorId: String, supplement: String?): String? {
        val frozen = predecessor.brief ?: return supplement
        // Never replace an empty id: String.replace("", x) would splice x between every character.
        val base = if (predecessor.attemptId.isBlank()) frozen else frozen.replace(predecessor.attemptId, successorId)
        return if (supplement == null) base else "$base\n\n$supplement"
    }

    /** Keeps the predecessor envelope verbatim unless the brief changed, in which case only its hash/number are re-pinned. */
    fun envelope(predecessor: DurableAgentAttempt, brief: String?, attemptNumber: Int): String? {
        val envelope = predecessor.contextEnvelope ?: return null
        if (brief == null || brief == predecessor.brief || envelope.isBlank()) return envelope
        // An envelope that cannot be read is kept verbatim: the frozen pair is never replaced by a guess.
        val parsed = runCatching { AttemptContextEnvelope.fromJson(envelope) }.getOrNull() ?: return envelope
        return parsed.copy(briefHash = CanonicalJsonHash.sha256(brief), attemptNumber = attemptNumber).toJson()
    }

    /**
     * Issues the successor's OWN capability. The result-channel row is registered first (issuance refuses an
     * unknown attempt) and the capability is bound to the hash of the successor brief, never to the predecessor's.
     */
    fun issueCapability(
        resultService: AgentStepResultService?,
        stepAttempts: AgentStepAttemptRepository?,
        scope: TenantScope,
        predecessor: DurableAgentAttempt?,
        successorId: String,
        successorBrief: String?,
        namespaceId: String,
        workflowId: String,
        stepId: String,
    ): String? {
        val service = resultService ?: return null
        val source = predecessor ?: return null
        stepAttempts?.let { repository ->
            if (!repository.exists(scope, namespaceId, workflowId, stepId, successorId)) {
                repository.insert(
                    scope,
                    AgentStepAttemptRecord(namespaceId, workflowId, stepId, successorId, source.agentName, "running", 1, "{}"),
                )
            }
        }
        return service.issue(
            scope,
            AgentStepResultCapabilityIdentity(
                attemptId = successorId,
                caseId = source.caseId,
                agentName = source.agentName,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = stepId,
                briefHash = CanonicalJsonHash.sha256(successorBrief ?: ""),
            ),
        ).token
    }
}
