package io.whozoss.factory.capability

import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.HumanInteractionEventRecord
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowCodeTransitionRecord
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import java.nio.file.Path
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * A resolved capability plus the ids of the facts it recorded (if any).
 *
 *  - `code`  -> a `workflow_code_transitions` row and a `workflow_evidence` fact;
 *  - `human` -> a `human_interactions` checkpoint and its opening event;
 *  - `agent` -> an `agent_step_attempts` attempt and a `workflow_evidence` fact.
 */
data class CapabilityExecution(
    val outcome: CapabilityOutcome,
    val codeTransitionId: String? = null,
    val evidenceId: String? = null,
    val interactionId: String? = null,
    val attemptId: String? = null,
)

/**
 * Persistence boundary of capability resolution (W8.2 / W8.3).
 *
 * Delegates routing to [CapabilityResolver] and records the resulting facts:
 * the code verdict as a code transition + an evidence item, the human request as
 * an open `human_interactions` checkpoint, and the agent turn as an
 * `agent_step_attempts` lifecycle plus an evidence item. Code, human and agent
 * rows carry composite foreign keys to the workflow instance, so the caller must
 * have materialized the instance first (the DAG sequencer of W8.3 drives that).
 *
 * ## Transaction boundaries
 * [resolveAndRecord] is deliberately NOT transactional: the external execution it
 * drives (AgentOS HTTP proxy calls, local verification processes) must never run
 * inside a Neo4j transaction, otherwise a slow turn outlives the transaction
 * timeout and every subsequent write fails with
 * "Cannot run more queries in this transaction". Each persistence phase instead
 * runs in its own short `REQUIRES_NEW` transaction (see [newTransaction]):
 *  - Phase 1 claims the step (attempt reservation + capability issuance);
 *  - Phase 2 executes the capability with no active transaction;
 *  - Phase 3 terminalizes the attempt and records the evidence.
 * When no [transactionManager] is supplied (pure unit tests), the phases execute
 * inline and rely on the individual repository operations for durability.
 */
@Service
class CapabilityExecutionService(
    private val resolver: CapabilityResolver,
    private val workflowRepository: WorkflowRepository,
    private val evidenceRepository: WorkflowEvidenceRepository,
    private val interactionRepository: HumanInteractionRepository,
    private val attemptRepository: AgentStepAttemptRepository,
    /**
     * Optional issuer of the single-use result-submission capability. It is
     * injected in production; pure unit tests that only exercise the DAG
     * persistence boundary may leave it `null` (capability issuance is then
     * skipped and the turn runs without a delegate token).
     */
    private val agentStepResultService: AgentStepResultService? = null,
    /**
     * Optional transaction manager used to isolate each persistence phase in its
     * own short `REQUIRES_NEW` transaction. Injected in production; pure unit
     * tests may omit it and the phases then run inline.
     */
    private val transactionManager: PlatformTransactionManager? = null,
) {

    private val shortTransaction: TransactionTemplate? = transactionManager?.let { manager ->
        TransactionTemplate(manager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    }

    /** Runs [block] in a fresh short transaction, or inline when none is configured. */
    private fun <T : Any> newTransaction(block: () -> T): T {
        val template = shortTransaction ?: return block()
        return template.execute { block() }!!
    }

    /** The durable ids produced by the agent claim phase (Phase 1). */
    private data class AgentClaim(val attemptId: String, val capabilityToken: String?)

    fun resolveAndRecord(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        repoRoot: Path,
        ticket: String? = null,
    ): CapabilityExecution {
        return when (step.responsibility.kind) {
            ResponsibilityKind.AGENT -> resolveAgent(scope, namespaceId, workflowId, step, repoRoot, ticket)
            ResponsibilityKind.HUMAN -> resolveHuman(scope, namespaceId, workflowId, step, repoRoot)
            ResponsibilityKind.CODE -> resolveCode(scope, namespaceId, workflowId, step, repoRoot)
        }
    }

    /**
     * Code capability: external process execution (Phase 2) then a short
     * transaction recording the code transition + evidence (Phase 3).
     */
    private fun resolveCode(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        repoRoot: Path,
    ): CapabilityExecution {
        val outcome = resolver.resolve(step, repoRoot, namespaceId, workflowId)
        return when (outcome) {
            is CapabilityOutcome.CodeExecuted -> newTransaction { recordCode(scope, namespaceId, workflowId, step, outcome) }
            else -> CapabilityExecution(outcome)
        }
    }

    /** Human checkpoint: no external execution; the checkpoint is recorded in a short transaction. */
    private fun resolveHuman(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        repoRoot: Path,
    ): CapabilityExecution {
        val outcome = resolver.resolve(step, repoRoot, namespaceId, workflowId)
        return when (outcome) {
            is CapabilityOutcome.HumanCheckpointRequired ->
                newTransaction { recordHuman(scope, namespaceId, workflowId, step, outcome) }
            else -> CapabilityExecution(outcome)
        }
    }

    /** The optional ticket reaches the agent persona through the turn brief. */
    private fun briefFromTicket(ticket: String?): String? =
        ticket?.takeIf { it.isNotBlank() }?.let { "Execute this Factory session step for ticket $it." }

    private fun recordCode(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        outcome: CapabilityOutcome.CodeExecuted,
    ): CapabilityExecution {
        val outcomeLabel = if (outcome.verdict) "pass" else "fail"
        val codeTransitionId = UUID.randomUUID().toString()
        workflowRepository.appendCodeTransition(
            scope,
            namespaceId,
            workflowId,
            WorkflowCodeTransitionRecord(
                codeTransitionId = codeTransitionId,
                stepId = step.id,
                outcome = outcomeLabel,
                exitCode = outcome.exitCode,
                payload = mapOf(
                    "verification" to outcome.verificationName,
                    "command" to outcome.command,
                    "verdict" to outcome.verdict,
                    "timedOut" to outcome.timedOut,
                    "durationMs" to outcome.durationMs,
                ),
                createdAt = null,
            ),
        )

        val evidenceId = UUID.randomUUID().toString()
        evidenceRepository.append(
            scope,
            namespaceId,
            workflowId,
            WorkflowEvidenceItem(
                evidenceId = evidenceId,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                kind = "code-verification",
                outcome = outcomeLabel,
                source = mapOf("kind" to "factory-verification", "name" to outcome.verificationName),
                facts = mapOf(
                    "stepId" to step.id,
                    "verification" to outcome.verificationName,
                    "command" to outcome.command,
                    "exitCode" to outcome.exitCode,
                    "timedOut" to outcome.timedOut,
                    "durationMs" to outcome.durationMs,
                    "verdict" to outcome.verdict,
                ),
                idempotencyKey = null,
                createdAt = null,
            ),
        )
        return CapabilityExecution(outcome, codeTransitionId = codeTransitionId, evidenceId = evidenceId)
    }

    private fun recordHuman(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        outcome: CapabilityOutcome.HumanCheckpointRequired,
    ): CapabilityExecution {
        val interactionId = UUID.randomUUID().toString()
        interactionRepository.insert(
            scope,
            HumanInteractionRecord(
                interactionId = interactionId,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                interactionType = "checkpoint",
                status = "waiting",
                revision = 1,
                payload = linkedMapOf(
                    "stepId" to step.id,
                    "role" to outcome.role,
                    "actions" to listOf(
                        mapOf("id" to "approve", "label" to "Approuver"),
                        mapOf("id" to "reject", "label" to "Rejeter"),
                    ),
                ),
            ),
        )
        interactionRepository.appendEvent(
            scope,
            namespaceId,
            workflowId,
            HumanInteractionEventRecord(
                eventId = UUID.randomUUID().toString(),
                interactionId = interactionId,
                eventType = "interaction_opened",
                actorId = "factory-capability-resolver",
                payload = mapOf("revision" to 1),
            ),
        )
        return CapabilityExecution(outcome, interactionId = interactionId)
    }

    /**
     * Agent capability: three phases — claim (short tx), external turn (no tx),
     * terminalize + evidence (short tx). Any transport exception is turned into
     * an explicit failure and terminalized outside the failed call — never a
     * false success and never an unhandled 500 masking the cause.
     */
    private fun resolveAgent(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        repoRoot: Path,
        ticket: String?,
    ): CapabilityExecution {
        val agentId = step.responsibility.name ?: "agent"
        val brief = briefFromTicket(ticket)
        // The Factory chooses the AgentOS case id so the submission capability
        // it mints can be bound to the exact case that will submit the result.
        val caseId = UUID.randomUUID().toString()

        // Phase 1 — short transaction: reserve the attempt and mint the token.
        val claim = newTransaction {
            val attemptId = UUID.randomUUID().toString()
            attemptRepository.insert(
                scope,
                AgentStepAttemptRecord(
                    namespaceId = namespaceId,
                    workflowId = workflowId,
                    stepId = step.id,
                    attemptId = attemptId,
                    agentId = agentId,
                    status = "running",
                    revision = 1,
                    payload = "{}",
                ),
            )
            AgentClaim(
                attemptId = attemptId,
                capabilityToken = issueCapability(scope, namespaceId, workflowId, step, attemptId, caseId, agentId, brief),
            )
        }

        // Phase 2 — no transaction: the (potentially long) AgentOS turn.
        val outcome = try {
            resolver.resolve(
                step,
                repoRoot,
                namespaceId,
                workflowId,
                brief,
                claim.attemptId,
                claim.capabilityToken,
                caseId,
            )
        } catch (error: Exception) {
            CapabilityOutcome.AgentFailed(
                stepId = step.id,
                persona = step.responsibility.name,
                code = "AGENT_TURN_ERROR",
                message = error.message ?: error.toString(),
            )
        }

        // Phase 3 — short transaction: terminalize the attempt and record the evidence.
        return newTransaction {
            val passed = outcome is CapabilityOutcome.AgentCompleted
            attemptRepository.terminalize(
                scope,
                namespaceId,
                workflowId,
                step.id,
                claim.attemptId,
                if (passed) "completed" else "failed",
            )
            val evidenceId = UUID.randomUUID().toString()
            evidenceRepository.append(
                scope,
                namespaceId,
                workflowId,
                WorkflowEvidenceItem(
                    evidenceId = evidenceId,
                    namespaceId = namespaceId,
                    workflowId = workflowId,
                    stepId = step.id,
                    kind = "agent-turn",
                    outcome = if (passed) "pass" else "fail",
                    source = mapOf("kind" to "agentos", "agentId" to agentId, "attemptId" to claim.attemptId),
                    facts = agentFacts(outcome, claim.attemptId),
                    idempotencyKey = null,
                    createdAt = null,
                ),
            )
            CapabilityExecution(outcome, evidenceId = evidenceId, attemptId = claim.attemptId)
        }
    }

    /**
     * Mint the single-use submission capability for [attemptId]. Issuance is
     * best-effort: a non-safe persona or a missing issuer must not fail the DAG
     * step, it only means the worker cannot submit through the capability
     * channel. Failures are swallowed (the step still runs and is recorded).
     */
    private fun issueCapability(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        attemptId: String,
        caseId: String,
        agentName: String,
        brief: String?,
    ): String? {
        val issuer = agentStepResultService ?: return null
        return runCatching {
            issuer.issue(
                scope,
                AgentStepResultCapabilityIdentity(
                    attemptId = attemptId,
                    workflowId = workflowId,
                    stepId = step.id,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentName = agentName,
                    briefHash = CanonicalJsonHash.sha256(brief ?: ""),
                ),
            ).token
        }.getOrNull()
    }

    private fun agentFacts(outcome: CapabilityOutcome, attemptId: String): Map<String, Any?> = when (outcome) {
        is CapabilityOutcome.AgentCompleted ->
            outcome.facts + mapOf("attemptId" to attemptId, "status" to outcome.status)
        is CapabilityOutcome.AgentFailed ->
            outcome.facts + mapOf("attemptId" to attemptId, "code" to outcome.code, "message" to outcome.message)
        is CapabilityOutcome.AgentDeferred ->
            mapOf("attemptId" to attemptId, "code" to outcome.code, "message" to outcome.message)
        else -> mapOf("attemptId" to attemptId)
    }
}
