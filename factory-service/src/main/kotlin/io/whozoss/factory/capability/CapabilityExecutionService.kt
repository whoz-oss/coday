package io.whozoss.factory.capability

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.HumanInteractionEventRecord
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
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
 *  - `agent` -> nothing (the capability is `NOT_IMPLEMENTED_YET` in W8.2).
 */
data class CapabilityExecution(
    val outcome: CapabilityOutcome,
    val codeTransitionId: String? = null,
    val evidenceId: String? = null,
    val interactionId: String? = null,
)

/**
 * Persistence boundary of capability resolution (W8.2).
 *
 * Delegates routing to [CapabilityResolver] and records the resulting facts:
 * the code verdict as a code transition + an evidence item, the human request as
 * an open `human_interactions` checkpoint. Code and human rows carry composite
 * foreign keys to the workflow instance, so the caller must have materialized
 * the instance first (the DAG sequencer of W8.3 drives that).
 */
@Service
class CapabilityExecutionService(
    private val resolver: CapabilityResolver,
    private val workflowRepository: WorkflowRepository,
    private val evidenceRepository: WorkflowEvidenceRepository,
    private val interactionRepository: HumanInteractionRepository,
) {

    @Transactional
    fun resolveAndRecord(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        repoRoot: Path,
    ): CapabilityExecution {
        val outcome = resolver.resolve(step, repoRoot)
        return when (outcome) {
            is CapabilityOutcome.CodeExecuted ->
                recordCode(scope, namespaceId, workflowId, step, outcome)
            is CapabilityOutcome.HumanCheckpointRequired ->
                recordHuman(scope, namespaceId, workflowId, step, outcome)
            else -> CapabilityExecution(outcome)
        }
    }

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
                payload = linkedMapOf("stepId" to step.id, "role" to outcome.role),
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
}
