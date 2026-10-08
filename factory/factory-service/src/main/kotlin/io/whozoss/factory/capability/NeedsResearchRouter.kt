package io.whozoss.factory.capability

import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Outcome of one automatic NEEDS_RESEARCH routing pass.
 *
 *  - [searcherAttemptId] is the durable attempt id of the Searcher turn that was
 *    run (if any);
 *  - [searcherSucceeded] tells whether the Searcher produced its research;
 *  - [reArmedAttemptId] is the brand-new attempt registered for the initial step
 *    once the research was available;
 *  - [reArmed] is `true` only when the initial step was re-armed, i.e. the
 *    orchestration may set it `ready` again and let it re-run.
 */
data class NeedsResearchRoutingOutcome(
    val searcherAttemptId: String?,
    val searcherSucceeded: Boolean,
    val reArmedAttemptId: String?,
    val reArmed: Boolean,
)

/**
 * Automatic NEEDS_RESEARCH → Searcher routing (Lot E).
 *
 * When an agent step's authoritative result is `NEEDS_RESEARCH`, the engine must
 * NOT wait for a human and must NEVER seal the run as a terminal `FAIL`. Instead
 * it:
 *  1. durably preserves the original `NEEDS_RESEARCH` proof (summary / findings /
 *     artifacts / claims) as an append-only evidence item — the original verdict
 *     is never rewritten;
 *  2. runs a Searcher attempt that produces the missing research;
 *  3. re-arms the initial step as a brand-new attempt `N+1` on the SAME worktree /
 *     sub-case (Lot B case family preserved), carrying a bounded research
 *     resumption context, so the initial step can be retried with fresh input.
 *
 * If the Searcher did not succeed, the routing reports `reArmed = false`: the
 * step stays blocked (`needs_research`) and a later pass may retry. Nothing is
 * ever converted into a terminal failure here.
 *
 * The Searcher turn itself is injected as a [runSearcher] callback so the router
 * stays free of the AgentOS transport and is trivially testable.
 */
@Service
class NeedsResearchRouter(
    private val attempts: DurableAgentAttemptService,
    private val evidence: WorkflowEvidenceRepository,
) {

    private val logger = KotlinLogging.logger {}

    fun route(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        result: CapabilityOutcome.AgentNeedsResearch,
        runSearcher: (WorkflowStepDefinition) -> CapabilityExecution,
    ): NeedsResearchRoutingOutcome {
        val evidenceId = preserveProof(scope, namespaceId, workflowId, step, result)

        val searcherStep = WorkflowStepDefinition(
            id = searcherStepId(step.id),
            name = SEARCHER_STEP_NAME,
            responsibility = WorkflowStepResponsibility(ResponsibilityKind.AGENT, SEARCHER_AGENT_NAME),
            dependsOn = emptyList(),
        )
        val execution = runSearcher(searcherStep)
        val searcherSucceeded = execution.outcome is CapabilityOutcome.AgentCompleted
        if (!searcherSucceeded) {
            logger.info {
                "NEEDS_RESEARCH routing for step '${step.id}' of workflow '$workflowId' did not re-arm: " +
                    "the Searcher attempt '${execution.attemptId}' did not complete successfully"
            }
            return NeedsResearchRoutingOutcome(execution.attemptId, searcherSucceeded = false, reArmedAttemptId = null, reArmed = false)
        }

        val reArmedAttemptId = reArmInitialAttempt(scope, namespaceId, workflowId, step, result, execution, evidenceId)
        logger.info {
            "NEEDS_RESEARCH routing for step '${step.id}' of workflow '$workflowId' re-armed as attempt " +
                "'$reArmedAttemptId' after Searcher attempt '${execution.attemptId}'"
        }
        return NeedsResearchRoutingOutcome(
            searcherAttemptId = execution.attemptId,
            searcherSucceeded = true,
            reArmedAttemptId = reArmedAttemptId,
            reArmed = true,
        )
    }

    /** Durable, append-only preservation of the original NEEDS_RESEARCH proof. */
    private fun preserveProof(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        result: CapabilityOutcome.AgentNeedsResearch,
    ): String {
        val evidenceId = UUID.randomUUID().toString()
        evidence.append(
            scope,
            namespaceId,
            workflowId,
            WorkflowEvidenceItem(
                evidenceId = evidenceId,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                kind = NEEDS_RESEARCH_EVIDENCE_KIND,
                outcome = NEEDS_RESEARCH_OUTCOME,
                source = mapOf(
                    "kind" to "factory-needs-research-router",
                    "attemptId" to result.attemptId,
                    "persona" to result.persona,
                ),
                facts = mapOf(
                    "stepId" to step.id,
                    "status" to "NEEDS_RESEARCH",
                    "summary" to result.summary,
                    "findings" to result.findings,
                    "artifacts" to result.artifacts,
                    "claims" to result.claims,
                    "resultId" to result.resultId,
                    "attemptId" to result.attemptId,
                ),
                idempotencyKey = "needs-research:${result.attemptId ?: step.id}",
                createdAt = null,
            ),
        )
        return evidenceId
    }

    /**
     * Registers attempt `N+1` of the initial step on the SAME worktree / sub-case
     * as the predecessor (`caseId` / `rootCaseId` / `parentCaseId` preserved —
     * Lot B), with a bounded research resumption context. The predecessor attempt
     * is left strictly untouched (it stays the immutable record of the blocked
     * try).
     */
    private fun reArmInitialAttempt(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        result: CapabilityOutcome.AgentNeedsResearch,
        searcherExecution: CapabilityExecution,
        proofEvidenceId: String,
    ): String {
        val predecessor = result.attemptId
            ?.let { attempts.find(scope, namespaceId, workflowId, step.id, it) }
        val nextNumber = attempts.nextAttemptNumber(scope, namespaceId, workflowId, step.id)
        val successorId = CapabilityExecutionService.retryAttemptId(workflowId, step.id, nextNumber)
        val resumptionContext = resumptionContext(result, searcherExecution.attemptId, proofEvidenceId)
        attempts.registerRetry(
            scope,
            DurableAgentAttempt(
                attemptId = successorId,
                caseId = predecessor?.caseId ?: CapabilityExecutionService.stableCaseId(workflowId, step.id),
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                attemptNumber = nextNumber,
                agentName = predecessor?.agentName ?: step.responsibility.name ?: "agent",
                capabilityToken = predecessor?.capabilityToken,
                brief = predecessor?.brief,
                environmentRef = predecessor?.environmentRef,
                expectedEnvironmentRevision = predecessor?.expectedEnvironmentRevision,
                resumptionContext = resumptionContext,
                rootCaseId = predecessor?.rootCaseId,
                parentCaseId = predecessor?.parentCaseId,
            ),
        )
        return successorId
    }

    /** Compact JSON research resumption context persisted on the re-armed attempt. */
    private fun resumptionContext(
        result: CapabilityOutcome.AgentNeedsResearch,
        searcherAttemptId: String?,
        proofEvidenceId: String,
    ): String = buildString {
        append("{\"reason\":\"NEEDS_RESEARCH\",")
        append("\"summary\":").append(quote(result.summary)).append(',')
        append("\"searcherAttemptId\":").append(quote(searcherAttemptId)).append(',')
        append("\"proofEvidenceId\":").append(quote(proofEvidenceId))
        append('}')
    }

    private fun quote(value: String?): String {
        if (value == null) return "null"
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    companion object {
        /** Durable evidence kind of a preserved NEEDS_RESEARCH verdict. */
        const val NEEDS_RESEARCH_EVIDENCE_KIND = "needs-research"
        const val NEEDS_RESEARCH_OUTCOME = "needs_research"
        const val SEARCHER_AGENT_NAME = "Searcher"
        const val SEARCHER_STEP_NAME = "Searcher research"

        /** Deterministic synthetic step id of the Searcher attempt spawned for [stepId]. */
        fun searcherStepId(stepId: String): String = "$stepId::searcher"
    }
}
