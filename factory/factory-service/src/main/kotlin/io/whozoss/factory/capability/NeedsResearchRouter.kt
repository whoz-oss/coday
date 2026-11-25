package io.whozoss.factory.capability

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AttemptContextEnvelope
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.StepQuestionLimits
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.persistence.EvidenceAppendResult
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyHandle
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyService
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
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
 * Bounded view of the result of a failed attempt, handed to [NeedsResearchRouter.retryFailed].
 *
 *  - [resultId] is the accepted structured result, when one exists;
 *  - [failureCode] is the durable failure code of the attempt, when it has one;
 *  - [summary] is the business summary (or the failure code and message read from the
 *    `agent-result` evidence). A blank or `null` summary means the previous result could not be retrieved;
 *  - [findings] are the structured findings of the accepted result.
 */
data class FailedAttemptSummary(
    val resultId: String?,
    val failureCode: String?,
    val summary: String?,
    val findings: List<*> = emptyList<Any?>(),
) {
    /** Whether anything about the previous result could be retrieved. */
    val available: Boolean get() = !summary.isNullOrBlank()
}

/**
 * Governed successor attempts: automatic NEEDS_RESEARCH → Searcher routing (Lot E), governed human
 * resumption and governed human retry of a failed agent step.
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
 * [resume] is the human counterpart: an authenticated engineer supplies missing
 * information (or confirms that repository access was fixed out of band) and a
 * brand-new attempt `N+1` is registered. The successor always gets:
 *  - its OWN result-channel row and single-use capability, bound to the hash of
 *    ITS brief (the predecessor's capability is consumed and never reused);
 *  - a brief whose submission identifiers (`attemptId`, `Idempotency-Key`) are
 *    those of the successor, enriched with the supplemental context;
 *  - the predecessor's case family, environment link and amendment pin.
 *
 * [retryFailed] is the human retry of a FAILED agent step. It registers the same kind of successor
 * (same case family, own capability, brief rewritten with the successor ids) and enriches the brief with
 * the previous attempt's result and the engineer's optional text. Renaming this class to a neutral
 * successor-attempt name is out of scope.
 *
 * The Searcher turn itself is injected as a [runSearcher] callback so the router
 * stays free of the AgentOS transport and is trivially testable.
 */
@Service
class NeedsResearchRouter(
    private val attempts: DurableAgentAttemptService,
    private val evidence: WorkflowEvidenceRepository,
    private val resultService: AgentStepResultService? = null,
    /**
     * Legacy result-channel attempt rows. A capability can only be issued for an
     * existing row, so the successor row is registered here before issuance.
     * Optional so narrow unit compositions can omit it.
     */
    private val stepAttempts: AgentStepAttemptRepository? = null,
    /**
     * Releases the physical-worktree occupation a failed attempt kept (fail-closed, see
     * `releaseAgentWorktreeIfResultAccepted`) so the successor is not deferred with `AGENT_WORKTREE_OCCUPIED`.
     * Optional so narrow unit compositions can omit it.
     */
    @param:Autowired(required = false)
    private val occupancyService: WorktreeExecutionOccupancyService? = null,
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

    /**
     * Durable, append-only preservation of the original NEEDS_RESEARCH proof.
     * Returns the id of the item actually persisted: on an idempotent replay it
     * is the pre-existing item, never the freshly generated candidate id.
     */
    private fun preserveProof(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        result: CapabilityOutcome.AgentNeedsResearch,
    ): String {
        val candidateId = UUID.randomUUID().toString()
        val appended = evidence.append(
            scope,
            namespaceId,
            workflowId,
            WorkflowEvidenceItem(
                evidenceId = candidateId,
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
        return persistedEvidenceId(appended)
    }

    private fun persistedEvidenceId(result: EvidenceAppendResult): String = when (result) {
        is EvidenceAppendResult.Created -> result.item.evidenceId
        is EvidenceAppendResult.Idempotent -> result.item.evidenceId
        is EvidenceAppendResult.Collision -> error("Evidence append collided: ${result.code}")
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
        val brief = predecessor?.let { successorBrief(it, successorId, supplement = null) }
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
                capabilityToken = issueSuccessorCapability(scope, predecessor, successorId, brief, namespaceId, workflowId, step.id),
                brief = brief,
                environmentRef = predecessor?.environmentRef,
                expectedEnvironmentRevision = predecessor?.expectedEnvironmentRevision,
                resumptionContext = resumptionContext,
                // Preserve the predecessor's frozen pair. The envelope is only
                // re-pinned when the brief text actually changed, so a legacy
                // null is never replaced by current workflow state.
                contextEnvelope = predecessor?.let { successorEnvelope(it, brief, nextNumber) },
                expectedAmendmentSeq = predecessor?.expectedAmendmentSeq,
                rootCaseId = predecessor?.rootCaseId,
                parentCaseId = predecessor?.parentCaseId,
            ),
        )
        return successorId
    }

    /**
     * Issues the successor's OWN capability. The result-channel row is registered
     * first (issuance refuses an unknown attempt) and the capability is bound to
     * the hash of the successor brief, never to the predecessor's.
     */
    private fun issueSuccessorCapability(
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

    /**
     * Human, governed resumption of a NEEDS_RESEARCH step as attempt `N+1`.
     *
     * The supplemental context is persisted as an audited evidence item, stored
     * in the bounded resumption context AND injected into the successor's brief
     * so the resumed turn actually receives it. Returns the successor attempt id.
     */
    fun resume(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        result: CapabilityOutcome.AgentNeedsResearch,
        additionalContext: String,
        actorId: String,
    ): String {
        val predecessor = result.attemptId?.let { attempts.find(scope, namespaceId, workflowId, step.id, it) }
            ?: error("NEEDS_RESEARCH predecessor attempt is unavailable")
        val nextNumber = attempts.nextAttemptNumber(scope, namespaceId, workflowId, step.id)
        val successorId = CapabilityExecutionService.retryAttemptId(workflowId, step.id, nextNumber)

        val evidenceId = persistedEvidenceId(
            evidence.append(
                scope, namespaceId, workflowId,
                WorkflowEvidenceItem(
                    evidenceId = UUID.randomUUID().toString(),
                    namespaceId = namespaceId,
                    workflowId = workflowId,
                    stepId = step.id,
                    kind = RESUMPTION_EVIDENCE_KIND,
                    outcome = "accepted",
                    source = mapOf("kind" to "factory-needs-research-resumption", "actorId" to actorId),
                    facts = mapOf(
                        "attemptId" to result.attemptId,
                        "resultId" to result.resultId,
                        "successorAttemptId" to successorId,
                        "additionalContext" to additionalContext,
                    ),
                    idempotencyKey = "needs-research-resumption:$successorId",
                    createdAt = null,
                ),
            ),
        )
        val context = resumptionContextJson(result, additionalContext, evidenceId)
        val brief = successorBrief(predecessor, successorId, supplement = researchSupplement(result, additionalContext))
        attempts.registerRetry(
            scope,
            DurableAgentAttempt(
                attemptId = successorId,
                caseId = predecessor.caseId,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                attemptNumber = nextNumber,
                agentName = predecessor.agentName,
                capabilityToken = issueSuccessorCapability(scope, predecessor, successorId, brief, namespaceId, workflowId, step.id),
                brief = brief,
                environmentRef = predecessor.environmentRef,
                expectedEnvironmentRevision = predecessor.expectedEnvironmentRevision,
                resumptionContext = context,
                contextEnvelope = successorEnvelope(predecessor, brief, nextNumber),
                expectedAmendmentSeq = predecessor.expectedAmendmentSeq,
                rootCaseId = predecessor.rootCaseId,
                parentCaseId = predecessor.parentCaseId,
            ),
        )
        return successorId
    }

    /**
     * Human, governed retry of a FAILED agent step as attempt `N+1`, in the same case family.
     *
     * The explicit human decision stands in for the accepted result that would normally release the
     * worktree: when the predecessor carries a complete occupancy handle it is released first (owner- and
     * generation-fenced), and the retry evidence records `releasedOccupancy`. The predecessor attempt and its
     * evidence are never rewritten. Returns the successor attempt id.
     */
    fun retryFailed(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        predecessor: DurableAgentAttempt,
        previousResult: FailedAttemptSummary,
        additionalContext: String,
        actorId: String,
    ): String {
        val nextNumber = attempts.nextAttemptNumber(scope, namespaceId, workflowId, step.id)
        val successorId = CapabilityExecutionService.retryAttemptId(workflowId, step.id, nextNumber)
        val releasedOccupancy = releasePredecessorOccupancy(scope, predecessor)

        val evidenceId = persistedEvidenceId(
            evidence.append(
                scope, namespaceId, workflowId,
                WorkflowEvidenceItem(
                    evidenceId = UUID.randomUUID().toString(),
                    namespaceId = namespaceId,
                    workflowId = workflowId,
                    stepId = step.id,
                    kind = FAILED_STEP_RETRY_EVIDENCE_KIND,
                    outcome = "accepted",
                    source = mapOf("kind" to "factory-failed-step-retry", "actorId" to actorId),
                    facts = mapOf(
                        "attemptId" to predecessor.attemptId,
                        "successorAttemptId" to successorId,
                        "previousFailureCode" to previousResult.failureCode,
                        "previousResultId" to previousResult.resultId,
                        "additionalContext" to additionalContext,
                        "releasedOccupancy" to releasedOccupancy,
                    ),
                    idempotencyKey = "failed-step-retry:$successorId",
                    createdAt = null,
                ),
            ),
        )
        val context = retryContextJson(predecessor, previousResult, additionalContext, evidenceId)
        val brief = successorBrief(predecessor, successorId, supplement = retrySupplement(previousResult, additionalContext))
        attempts.registerRetry(
            scope,
            DurableAgentAttempt(
                attemptId = successorId,
                caseId = predecessor.caseId,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                attemptNumber = nextNumber,
                agentName = predecessor.agentName,
                capabilityToken = issueSuccessorCapability(scope, predecessor, successorId, brief, namespaceId, workflowId, step.id),
                brief = brief,
                environmentRef = predecessor.environmentRef,
                expectedEnvironmentRevision = predecessor.expectedEnvironmentRevision,
                resumptionContext = context,
                contextEnvelope = successorEnvelope(predecessor, brief, nextNumber),
                expectedAmendmentSeq = predecessor.expectedAmendmentSeq,
                rootCaseId = predecessor.rootCaseId,
                parentCaseId = predecessor.parentCaseId,
            ),
        )
        return successorId
    }

    /**
     * Releases the occupation held by a failed predecessor, only when it carries a complete handle.
     * Returns whether a release was actually performed (a stale handle is not an error: the retry proceeds).
     */
    private fun releasePredecessorOccupancy(scope: TenantScope, predecessor: DurableAgentAttempt): Boolean {
        val service = occupancyService ?: return false
        val resourceIdentity = predecessor.occupancyResourceIdentity ?: return false
        val ownerKind = predecessor.occupancyOwnerKind ?: return false
        val ownerId = predecessor.occupancyOwnerId ?: return false
        val generation = predecessor.occupancyGeneration ?: return false
        return service.releaseOwned(
            WorktreeExecutionOccupancyHandle(
                environmentId = predecessor.environmentRef ?: "",
                resourceIdentity = resourceIdentity,
                ownerScope = scope,
                ownerKind = ownerKind,
                ownerId = ownerId,
                generation = generation,
            ),
        )
    }

    /** Brief section handed to the retried turn. The engineer text is fenced, never interpreted as tool instructions. */
    private fun retrySupplement(previousResult: FailedAttemptSummary, additionalContext: String): String =
        buildString {
            appendLine("## Retry of a failed attempt")
            appendLine("A previous attempt of this step failed and an engineer asked to retry it in the same case.")
            if (previousResult.available) {
                appendLine("Previous result: ${previousResult.summary!!.take(SUMMARY_IN_BRIEF)}")
            } else {
                appendLine(RESULT_UNAVAILABLE_SENTENCE)
            }
            if (additionalContext.isNotBlank()) {
                appendLine("Information supplied by the engineer:")
                appendLine("<<<")
                appendLine(additionalContext)
                appendLine(">>>")
            }
            append("Fix the cause of the failure and complete the same step. Submit your result with the identifiers above; ")
            append("do not reuse identifiers of the previous attempt.")
        }

    /**
     * Bounded resumption context of a failed-step retry, serialized with Jackson. The previous summary is
     * shrunk first when the byte bound would be exceeded; the engineer text is never silently truncated.
     */
    private fun retryContextJson(
        predecessor: DurableAgentAttempt,
        previousResult: FailedAttemptSummary,
        additionalContext: String,
        retryEvidenceId: String,
    ): String {
        fun build(summaryLimit: Int): String = mapper.writeValueAsString(
            linkedMapOf(
                "reason" to "FAILED_STEP_RETRY",
                "previousAttemptId" to predecessor.attemptId,
                "previousResultId" to previousResult.resultId,
                "failureCode" to previousResult.failureCode,
                "summary" to previousResult.summary?.take(summaryLimit),
                "additionalContext" to additionalContext,
                "retryEvidenceId" to retryEvidenceId,
            ),
        )
        for (limit in intArrayOf(SUMMARY_LIMIT, 500, 0)) {
            val json = build(limit)
            if (json.toByteArray(StandardCharsets.UTF_8).size <= StepQuestionLimits.RESUMPTION_CONTEXT_BYTES) return json
        }
        throw IllegalArgumentException(
            "The failed-step retry context exceeds ${StepQuestionLimits.RESUMPTION_CONTEXT_BYTES} bytes",
        )
    }

    /**
     * The successor brief: the predecessor's frozen brief with its submission
     * identifiers rewritten to the successor's, plus an optional supplement. The
     * predecessor's attempt id is a UUID, so a plain textual replacement is exact.
     */
    private fun successorBrief(predecessor: DurableAgentAttempt, successorId: String, supplement: String?): String? {
        val frozen = predecessor.brief ?: return supplement
        // Never replace an empty id: String.replace("", x) would splice x between every character.
        val base = if (predecessor.attemptId.isBlank()) frozen else frozen.replace(predecessor.attemptId, successorId)
        return if (supplement == null) base else "$base\n\n$supplement"
    }

    /** Brief section handed to the resumed turn. The user text is fenced, never interpreted as instructions about tools. */
    private fun researchSupplement(result: CapabilityOutcome.AgentNeedsResearch, additionalContext: String): String =
        buildString {
            appendLine("## Research resumption")
            appendLine("A previous attempt of this step ended with NEEDS_RESEARCH and an engineer has now supplied more information.")
            appendLine("Previous blocking summary: ${result.summary.take(SUMMARY_IN_BRIEF)}")
            appendLine("Information supplied by the engineer (it may also state that repository access was fixed out of band):")
            appendLine("<<<")
            appendLine(additionalContext)
            appendLine(">>>")
            append("Continue the same step with this information. Submit your result with the identifiers above; ")
            append("do not reuse identifiers of the previous attempt. Submit NEEDS_RESEARCH again only if a blocker remains.")
        }

    /** Keeps the predecessor envelope verbatim unless the brief changed, in which case only its hash/number are re-pinned. */
    private fun successorEnvelope(predecessor: DurableAgentAttempt, brief: String?, attemptNumber: Int): String? {
        val envelope = predecessor.contextEnvelope ?: return null
        if (brief == null || brief == predecessor.brief || envelope.isBlank()) return envelope
        // An envelope that cannot be read is kept verbatim: the frozen pair is never replaced by a guess.
        val parsed = runCatching { AttemptContextEnvelope.fromJson(envelope) }.getOrNull() ?: return envelope
        return parsed.copy(briefHash = CanonicalJsonHash.sha256(brief), attemptNumber = attemptNumber).toJson()
    }

    /**
     * Bounded resumption context of a human resumption, serialized with Jackson
     * (never hand-escaped). The previous summary is shrunk first when the byte
     * bound would be exceeded; the engineer text is never silently truncated.
     */
    private fun resumptionContextJson(
        result: CapabilityOutcome.AgentNeedsResearch,
        additionalContext: String,
        resumptionEvidenceId: String,
    ): String {
        fun build(summaryLimit: Int): String = mapper.writeValueAsString(
            linkedMapOf(
                "reason" to "NEEDS_RESEARCH",
                "summary" to result.summary.take(summaryLimit),
                "previousResultId" to result.resultId,
                "previousAttemptId" to result.attemptId,
                "additionalContext" to additionalContext,
                "resumptionEvidenceId" to resumptionEvidenceId,
            ),
        )
        for (limit in intArrayOf(SUMMARY_LIMIT, 500, 0)) {
            val json = build(limit)
            if (json.toByteArray(StandardCharsets.UTF_8).size <= StepQuestionLimits.RESUMPTION_CONTEXT_BYTES) return json
        }
        throw IllegalArgumentException(
            "The research resumption context exceeds ${StepQuestionLimits.RESUMPTION_CONTEXT_BYTES} bytes",
        )
    }

    /** Compact JSON research resumption context persisted on the automatically re-armed attempt. */
    private fun resumptionContext(
        result: CapabilityOutcome.AgentNeedsResearch,
        searcherAttemptId: String?,
        proofEvidenceId: String,
    ): String = mapper.writeValueAsString(
        linkedMapOf(
            "reason" to "NEEDS_RESEARCH",
            "summary" to result.summary,
            "searcherAttemptId" to searcherAttemptId,
            "proofEvidenceId" to proofEvidenceId,
        ),
    )

    companion object {
        /** Durable evidence kind of a preserved NEEDS_RESEARCH verdict. */
        const val NEEDS_RESEARCH_EVIDENCE_KIND = "needs-research"
        const val NEEDS_RESEARCH_OUTCOME = "needs_research"

        /** Durable evidence kind of an audited human resumption. */
        const val RESUMPTION_EVIDENCE_KIND = "needs-research-resumption"

        /** Durable evidence kind of an audited human retry of a failed agent step. */
        const val FAILED_STEP_RETRY_EVIDENCE_KIND = "failed-step-retry"

        /** Sentence of the retry brief when nothing could be retrieved about the previous result. */
        const val RESULT_UNAVAILABLE_SENTENCE = "The previous result could not be retrieved."
        const val SEARCHER_AGENT_NAME = "Searcher"
        const val SEARCHER_STEP_NAME = "Searcher research"

        private const val SUMMARY_LIMIT = 2_000
        private const val SUMMARY_IN_BRIEF = 1_000
        private val mapper = ObjectMapper()

        /** Deterministic synthetic step id of the Searcher attempt spawned for [stepId]. */
        fun searcherStepId(stepId: String): String = "$stepId--searcher"
    }
}
