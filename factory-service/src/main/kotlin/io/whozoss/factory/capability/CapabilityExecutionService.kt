package io.whozoss.factory.capability

import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
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
import org.springframework.transaction.annotation.Transactional

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
 */
@Service
class CapabilityExecutionService(
    private val resolver: CapabilityResolver,
    private val workflowRepository: WorkflowRepository,
    private val evidenceRepository: WorkflowEvidenceRepository,
    private val interactionRepository: HumanInteractionRepository,
    private val attemptRepository: AgentStepAttemptRepository,
) {

    @Transactional
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
            else -> {
                val outcome = resolver.resolve(step, repoRoot, namespaceId, workflowId)
                when (outcome) {
                    is CapabilityOutcome.CodeExecuted ->
                        recordCode(scope, namespaceId, workflowId, step, outcome)
                    is CapabilityOutcome.HumanCheckpointRequired ->
                        recordHuman(scope, namespaceId, workflowId, step, outcome)
                    else -> CapabilityExecution(outcome)
                }
            }
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
     * Opens an `agent_step_attempts` row, runs the turn through the resolver, then
     * terminalizes the attempt (`completed` / `failed`) and records an
     * `agent-turn` evidence fact. Any transport exception is turned into an
     * explicit failure — never a false success.
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
        val outcome = try {
            resolver.resolve(step, repoRoot, namespaceId, workflowId, briefFromTicket(ticket))
        } catch (error: Exception) {
            CapabilityOutcome.AgentFailed(
                stepId = step.id,
                persona = step.responsibility.name,
                code = "AGENT_TURN_ERROR",
                message = error.message ?: error.toString(),
            )
        }
        val passed = outcome is CapabilityOutcome.AgentCompleted
        attemptRepository.terminalize(
            scope,
            namespaceId,
            workflowId,
            step.id,
            attemptId,
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
                source = mapOf("kind" to "agentos", "agentId" to agentId, "attemptId" to attemptId),
                facts = agentFacts(outcome, attemptId),
                idempotencyKey = null,
                createdAt = null,
            ),
        )
        return CapabilityExecution(outcome, evidenceId = evidenceId, attemptId = attemptId)
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
