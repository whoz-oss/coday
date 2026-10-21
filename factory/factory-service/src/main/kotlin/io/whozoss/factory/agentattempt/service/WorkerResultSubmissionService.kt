package io.whozoss.factory.agentattempt.service

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.InvalidResultRequestException
import io.whozoss.factory.agentattempt.persistence.ScopedDurableAgentAttempt
import io.whozoss.factory.error.ResourceNotFoundException
import org.springframework.stereotype.Service

/**
 * Accepts the structured result a worker submits through the standard AgentOS
 * `HTTP_API` integration (`POST /api/factory/agent-step-results`).
 *
 * The worker only knows the Factory `attemptId`. The workflow, step, namespace
 * and tenant are never taken from the caller: the attempt is resolved within the
 * caller's ORGANIZATION and the submission then runs under the attempt's OWN
 * persisted scope. Authentication establishes only the organization; the
 * attempt owns the authoritative workstream and namespace.
 */
@Service
class WorkerResultSubmissionService(
    private val durableAttempts: DurableAgentAttemptService,
    private val results: AgentStepResultService,
) {

    fun submit(
        organizationId: String,
        attemptId: String,
        result: JsonNode,
        idempotencyKey: String,
    ): AgentStepResultSubmission {
        val found = resolveAttempt(organizationId, attemptId)
        val attempt = found.attempt
        // Integrity: the attempt must still carry the frozen brief it was registered with.
        if (attempt.brief?.let(CanonicalJsonHash::sha256) == null) {
            throw InvalidResultRequestException("The durable attempt has no frozen context")
        }
        return results.submitTrusted(
            scope = found.scope,
            attempt = attempt,
            business = result,
            agentName = null,
            idempotencyKey = "${attempt.attemptId}:$idempotencyKey",
        )
    }

    private fun resolveAttempt(organizationId: String, attemptId: String): ScopedDurableAgentAttempt {
        val matches = durableAttempts.findByOrganizationAndAttemptId(organizationId, attemptId)
        return when (matches.size) {
            0 -> throw ResourceNotFoundException("No durable attempt '$attemptId' exists")
            1 -> matches.single()
            else -> throw InvalidResultRequestException("The attemptId resolves to several durable attempts")
        }
    }
}
