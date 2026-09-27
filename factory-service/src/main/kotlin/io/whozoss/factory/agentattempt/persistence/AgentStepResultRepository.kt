package io.whozoss.factory.agentattempt.persistence

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultSubmitted
import io.whozoss.factory.agentattempt.domain.IssuedCapability
import io.whozoss.factory.agentattempt.domain.SubmitOutcome
import io.whozoss.factory.persistence.TenantScope
import java.time.Instant

/**
 * Persistence port of the AGENT-STEP result context (V6 `agent_step_results` /
 * `result_capabilities` + V4 `outbox_events`).
 *
 * A submission capability is issued for an attempt identity, then redeemed by a
 * worker that declares its observed identity and a structured business result.
 * On submission the attempt root is terminalized and a `result_submitted` event
 * is written to the transactional outbox, all inside one unit of work.
 */
interface AgentStepResultRepository {

    /** Issue a single-use capability bound to an attempt identity. */
    fun issue(
        scope: TenantScope,
        identity: AgentStepResultCapabilityIdentity,
        now: Instant = Instant.now(),
        ttlSeconds: Long = 15L * 60L,
    ): IssuedCapability

    /** Redeem a capability with a structured business result; replays are idempotent. */
    fun submit(
        scope: TenantScope,
        token: String,
        business: JsonNode,
        observed: AgentStepResultObservedIdentity,
        now: Instant = Instant.now(),
    ): SubmitOutcome

    /** The submitted result of one attempt, or `null` when none was recorded. */
    fun getByAttempt(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): AgentStepResultSubmitted?
}
